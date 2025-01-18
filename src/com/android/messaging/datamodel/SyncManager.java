/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.messaging.datamodel;

import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.provider.Telephony;

import com.android.messaging.datamodel.action.SyncMessagesAction;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.sms.MmsUtils;
import com.android.messaging.util.Assert;
import com.android.messaging.util.BugleGservices;
import com.android.messaging.util.BugleGservicesKeys;
import com.android.messaging.util.BuglePrefs;
import com.android.messaging.util.BuglePrefsKeys;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.OsUtil;
import com.android.messaging.util.PhoneUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import androidx.collection.LongSparseArray;

/**
 * This class manages message sync with the Telephony SmsProvider/MmsProvider.
 */
public class SyncManager {
    private static final String TAG = LogUtil.BUGLE_TAG;

    /**
     * Record of any user customization to conversation settings
     */
    public static class ConversationCustomization {
        private final boolean mArchived;
        private final boolean mMuted;
        private final boolean mNoVibrate;
        private final String mNotificationSoundUri;

        public ConversationCustomization(final boolean archived, final boolean muted,
                final boolean noVibrate, final String notificationSoundUri) {
            mArchived = archived;
            mMuted = muted;
            mNoVibrate = noVibrate;
            mNotificationSoundUri = notificationSoundUri;
        }

        public boolean isArchived() {
            return mArchived;
        }

        public boolean isMuted() {
            return mMuted;
        }

        public boolean noVibrate() {
            return mNoVibrate;
        }

        public String getNotificationSoundUri() {
            return mNotificationSoundUri;
        }
    }

    SyncManager() {
    }

    /**
     * Timestamp of in progress sync - used to keep track of whether sync is running
     */
    private long mSyncInProgressTimestamp = -1;

    /**
     * Timestamp of current sync batch upper bound - used to determine if message makes batch dirty
     */
    private long mCurrentUpperBoundTimestamp = -1;

    /**
     * Timestamp of messages inserted since sync batch started - used to determine if batch dirty
     */
    private long mMaxRecentChangeTimestamp = -1L;

    private final ThreadInfoCache mThreadInfoCache = new ThreadInfoCache();

    /**
     * User customization to conversations. If this is set, we need to recover them after
     * a full sync.
     */
    private LongSparseArray<ConversationCustomization> mCustomization = null;

    /**
     * Start an incremental sync (backed off a few seconds)
     */
    public static void sync() {
        SyncMessagesAction.sync();
    }

    /**
     * Start an incremental sync (with no backoff)
     */
    public static void immediateSync() {
        SyncMessagesAction.immediateSync();
    }

    /**
     * Start a full sync (for debugging)
     */
    public static void forceSync() {
        SyncMessagesAction.fullSync();
    }

    /**
     * Called from data model thread when starting a sync batch
     * @param upperBoundTimestamp upper bound timestamp for sync batch
     */
    public synchronized void startSyncBatch(final long upperBoundTimestamp) {
        Assert.isTrue(mCurrentUpperBoundTimestamp < 0);
        mCurrentUpperBoundTimestamp = upperBoundTimestamp;
        mMaxRecentChangeTimestamp = -1L;
    }

    /**
     * Called from data model thread at end of batch to determine if any messages added in window
     * @param lowerBoundTimestamp lower bound timestamp for sync batch
     * @return true if message added within window from lower to upper bound timestamp of batch
     */
    public synchronized boolean isBatchDirty(final long lowerBoundTimestamp) {
        Assert.isTrue(mCurrentUpperBoundTimestamp >= 0);
        final long max = mMaxRecentChangeTimestamp;

        final boolean dirty = (max >= 0 && max >= lowerBoundTimestamp);
        if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
            LogUtil.d(TAG, "SyncManager: Sync batch of messages from " + lowerBoundTimestamp
                    + " to " + mCurrentUpperBoundTimestamp + " is "
                    + (dirty ? "DIRTY" : "clean") + "; max change timestamp = "
                    + mMaxRecentChangeTimestamp);
        }

        mCurrentUpperBoundTimestamp = -1L;
        mMaxRecentChangeTimestamp = -1L;

        return dirty;
    }

    /**
     * Called from data model or background worker thread to indicate start of message add process
     * (add must complete on that thread before action transitions to new thread/stage)
     * @param timestamp timestamp of message being added
     */
    public synchronized void onNewMessageInserted(final long timestamp) {
        if (mCurrentUpperBoundTimestamp >= 0 && timestamp <= mCurrentUpperBoundTimestamp) {
            // Message insert in current sync window
            mMaxRecentChangeTimestamp = Math.max(mCurrentUpperBoundTimestamp, timestamp);
            if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
                LogUtil.d(TAG, "SyncManager: New message @ " + timestamp + " before upper bound of "
                        + "current sync batch " + mCurrentUpperBoundTimestamp);
            }
        } else if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
            LogUtil.d(TAG, "SyncManager: New message @ " + timestamp + " after upper bound of "
                    + "current sync batch " + mCurrentUpperBoundTimestamp);
        }
    }

    /**
     * Synchronously checks whether sync is allowed and starts sync if allowed
     * @param full - true indicates a full (not incremental) sync operation
     * @param startTimestamp - starttimestamp for this sync (if allowed)
     * @return - true if sync should start
     */
    public synchronized boolean shouldSync(final boolean full, final long startTimestamp) {
        if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
            LogUtil.v(TAG, "SyncManager: Checking shouldSync " + (full ? "full " : "")
                    + "at " + startTimestamp);
        }

        if (full) {
            final long delayUntilFullSync = delayUntilFullSync(startTimestamp);
            if (delayUntilFullSync > 0) {
                if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
                    LogUtil.d(TAG, "SyncManager: Full sync requested for " + startTimestamp
                            + " delayed for " + delayUntilFullSync + " ms");
                }
                return false;
            }
        }

        if (isSyncing()) {
            if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
                LogUtil.d(TAG, "SyncManager: Not allowed to " + (full ? "full " : "")
                        + "sync yet; still running sync started at " + mSyncInProgressTimestamp);
            }
            return false;
        }
        if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
            LogUtil.d(TAG, "SyncManager: Starting " + (full ? "full " : "") + "sync at "
                    + startTimestamp);
        }

        mSyncInProgressTimestamp = startTimestamp;

        return true;
    }

    /**
     * Return delay (in ms) until allowed to run a full sync (0 meaning can run immediately)
     * @param startTimestamp Timestamp used to start the sync
     * @return 0 if allowed to run now, else delay in ms
     */
    public long delayUntilFullSync(final long startTimestamp) {
        final BugleGservices bugleGservices = BugleGservices.get();
        final BuglePrefs prefs = BuglePrefs.getApplicationPrefs();

        final long lastFullSyncTime = prefs.getLong(BuglePrefsKeys.LAST_FULL_SYNC_TIME, -1L);
        final long smsFullSyncBackoffTimeMillis = bugleGservices.getLong(
                BugleGservicesKeys.SMS_FULL_SYNC_BACKOFF_TIME_MILLIS,
                BugleGservicesKeys.SMS_FULL_SYNC_BACKOFF_TIME_MILLIS_DEFAULT);
        final long noFullSyncBefore = (lastFullSyncTime < 0 ? startTimestamp :
            lastFullSyncTime + smsFullSyncBackoffTimeMillis);

        final long delayUntilFullSync = noFullSyncBefore - startTimestamp;
        if (delayUntilFullSync > 0) {
            return delayUntilFullSync;
        }
        return 0;
    }

    /**
     * Check if sync currently in progress (public for asserts/logging).
     */
    public synchronized boolean isSyncing() {
        return (mSyncInProgressTimestamp >= 0);
    }

    /**
     * Check if sync batch should be in progress - compares upperBound with in memory value
     * @param upperBoundTimestamp - upperbound timestamp for sync batch
     * @return - true if timestamps match (otherwise batch is orphan from older process)
     */
    public synchronized boolean isSyncing(final long upperBoundTimestamp) {
        Assert.isTrue(upperBoundTimestamp >= 0);
        return (upperBoundTimestamp == mCurrentUpperBoundTimestamp);
    }

    /**
     * Check if sync has completed for the first time.
     */
    public boolean getHasFirstSyncCompleted() {
        final BuglePrefs prefs = BuglePrefs.getApplicationPrefs();
        return prefs.getLong(BuglePrefsKeys.LAST_SYNC_TIME,
                BuglePrefsKeys.LAST_SYNC_TIME_DEFAULT) !=
                BuglePrefsKeys.LAST_SYNC_TIME_DEFAULT;
    }

    /**
     * Called once sync is complete
     */
    public synchronized void complete() {
        if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
            LogUtil.d(TAG, "SyncManager: Sync started at " + mSyncInProgressTimestamp
                    + " marked as complete");
        }
        mSyncInProgressTimestamp = -1L;
        // Conversation customization only used once
        mCustomization = null;
    }

    private final ContentObserver mMmsSmsObserver = new TelephonyMessagesObserver();
    private boolean mSyncOnChanges = false;
    private boolean mNotifyOnChanges = false;

    /**
     * Register content observer when necessary and kick off a catch up sync
     */
    public void updateSyncObserver(final Context context) {
        registerObserver(context);
        // Trigger an sms sync in case we missed and messages before registering this observer or
        // becoming the SMS provider.
        immediateSync();
    }

    private void registerObserver(final Context context) {
        if (!PhoneUtils.getDefault().isDefaultSmsApp()) {
            // Not default SMS app - need to actively monitor telephony but not notify
            mNotifyOnChanges = false;
            mSyncOnChanges = true;
        } else if (OsUtil.isSecondaryUser()){
            // Secondary users default SMS app - need to actively monitor telephony and notify
            mNotifyOnChanges = true;
            mSyncOnChanges = true;
        } else {
            // Primary users default SMS app - don't monitor telephony (most changes from this app)
            mNotifyOnChanges = false;
            mSyncOnChanges = false;
            if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
                // This is default SMS app but on Auto platform, even default SMS app needs
                // to be notified of changes because Bluetooth makes changes to Sms database
                // (thru ContentProvider). Content Change notifications help the default SMS app
                // refresh display with changes, whenever Bluetooth changes data in SMS db.
                mNotifyOnChanges = true;
                mSyncOnChanges = false;
            }
        }
        if (mNotifyOnChanges || mSyncOnChanges) {
            context.getContentResolver().registerContentObserver(Telephony.MmsSms.CONTENT_URI,
                    true, mMmsSmsObserver);
        } else {
            context.getContentResolver().unregisterContentObserver(mMmsSmsObserver);
        }
    }

    public synchronized void setCustomization(
            final LongSparseArray<ConversationCustomization> customization) {
        this.mCustomization = customization;
    }

    public synchronized ConversationCustomization getCustomizationForThread(final long threadId) {
        if (mCustomization != null) {
            return mCustomization.get(threadId);
        }
        return null;
    }

    public static void resetLastSyncTimestamps() {
        final BuglePrefs prefs = BuglePrefs.getApplicationPrefs();
        prefs.putLong(BuglePrefsKeys.LAST_FULL_SYNC_TIME,
                BuglePrefsKeys.LAST_FULL_SYNC_TIME_DEFAULT);
        prefs.putLong(BuglePrefsKeys.LAST_SYNC_TIME, BuglePrefsKeys.LAST_SYNC_TIME_DEFAULT);
    }

    private class TelephonyMessagesObserver extends ContentObserver {
        public TelephonyMessagesObserver() {
            // Just run on default thread
            super(null);
        }

        // Implement the onChange(boolean) method to delegate the change notification to
        // the onChange(boolean, Uri) method to ensure correct operation on older versions
        // of the framework that did not have the onChange(boolean, Uri) method.
        @Override
        public void onChange(final boolean selfChange) {
            onChange(selfChange, null);
        }

        // Implement the onChange(boolean, Uri) method to take advantage of the new Uri argument.
        @Override
        public void onChange(final boolean selfChange, final Uri uri) {
            // Handle change.
            if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
                LogUtil.v(TAG, "SyncManager: Sms/Mms DB changed @" + System.currentTimeMillis()
                        + " for " + (uri == null ? "<unk>" : uri.toString()) + " "
                        + mSyncOnChanges + "/" + mNotifyOnChanges);
            }

            if (mSyncOnChanges) {
                // If sync is already running this will do nothing - but at end of each sync
                // action there is a check for recent messages that should catch new changes.
                SyncManager.immediateSync();
            }
            if (mNotifyOnChanges) {
                // TODO: Secondary users are not going to get notifications
            }
        }
    }

    public ThreadInfoCache getThreadInfoCache() {
        return mThreadInfoCache;
    }

    public static class ThreadInfoCache {
        // Cache of thread->conversationId map
        private final LongSparseArray<String> mThreadToConversationId =
                new LongSparseArray<String>();

        // Cache of thread->recipients map
        private final LongSparseArray<List<String>> mThreadToRecipients =
                new LongSparseArray<List<String>>();

        // Remember the conversation ids that need to be archived
        private final HashSet<String> mArchivedConversations = new HashSet<>();

        public synchronized void clear() {
            if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
                LogUtil.d(TAG, "SyncManager: Cleared ThreadInfoCache");
            }
            mThreadToConversationId.clear();
            mThreadToRecipients.clear();
            mArchivedConversations.clear();
        }

        public synchronized boolean isArchived(final String conversationId) {
            return mArchivedConversations.contains(conversationId);
        }

        /**
         * Get or create a conversation based on the message's thread id
         *
         * @param threadId The message's thread
         * @param refSubId The subId used for normalizing phone numbers in the thread
         * @param customization The user setting customization to the conversation if any
         * @return The existing conversation id or new conversation id
         */
        public synchronized String getOrCreateConversation(final DatabaseWrapper db,
                final long threadId, int refSubId, final ConversationCustomization customization) {
            // This function has several components which need to be atomic.
            Assert.isTrue(db.getDatabase().inTransaction());

            // If we already have this conversation ID in our local map, just return it
            String conversationId = mThreadToConversationId.get(threadId);
            if (conversationId != null) {
                return conversationId;
            }

            final List<String> recipients = getThreadRecipients(threadId);
            final ArrayList<ParticipantData> participants =
                    BugleDatabaseOperations.getConversationParticipantsFromRecipients(recipients,
                            refSubId);

            if (customization != null) {
                // There is user customization we need to recover
                conversationId = BugleDatabaseOperations.getOrCreateConversation(db, threadId,
                        customization.isArchived(), participants, customization.isMuted(),
                        customization.noVibrate(), customization.getNotificationSoundUri());
                if (customization.isArchived()) {
                    mArchivedConversations.add(conversationId);
                }
            } else {
                conversationId = BugleDatabaseOperations.getOrCreateConversation(db, threadId,
                        false/*archived*/, participants, false/*noNotification*/,
                        false/*noVibrate*/, null/*soundUri*/);
            }

            if (conversationId != null) {
                mThreadToConversationId.put(threadId, conversationId);
                return conversationId;
            }

            return null;
        }


        /**
         * Load the recipients of a thread from telephony provider. If we fail, use
         * a predefined unknown recipient. This should not return null.
         *
         * @param threadId
         */
        public synchronized List<String> getThreadRecipients(final long threadId) {
            List<String> recipients = mThreadToRecipients.get(threadId);
            if (recipients == null) {
                recipients = MmsUtils.getRecipientsByThread(threadId);
                if (recipients != null && recipients.size() > 0) {
                    mThreadToRecipients.put(threadId, recipients);
                }
            }

            if (recipients == null || recipients.isEmpty()) {
                LogUtil.w(TAG, "SyncManager : using unknown sender since thread " + threadId +
                        " couldn't find any recipients.");

                // We want to try our best to load the messages,
                // so if recipient info is broken, try to fix it with unknown recipient
                recipients = new ArrayList<>();
                recipients.add(ParticipantData.getUnknownSenderDestination());
            }

            return recipients;
        }
    }
}
