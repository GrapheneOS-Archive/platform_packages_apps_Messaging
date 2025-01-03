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

import android.content.ContentValues;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.graphics.Color;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.telephony.SubscriptionInfo;
import android.text.TextUtils;

import com.android.messaging.Factory;
import com.android.messaging.datamodel.DatabaseHelper.ConversationColumns;
import com.android.messaging.datamodel.DatabaseHelper.ConversationParticipantsColumns;
import com.android.messaging.datamodel.DatabaseHelper.ParticipantColumns;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.datamodel.data.ParticipantData.ParticipantsQuery;
import com.android.messaging.ui.UIIntents;
import com.android.messaging.util.Assert;
import com.android.messaging.util.ContactUtil;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.OsUtil;
import com.android.messaging.util.PhoneUtils;
import com.android.messaging.util.SafeAsyncTask;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import androidx.collection.ArrayMap;

/**
 * Utility class for refreshing participant information based on matching contact. This updates
 *     1. name, photo_uri, matching contact_id of participants.
 *     2. generated_name of conversations.
 *
 * There are two kinds of participant refreshes,
 *     1. Full refresh, this is triggered at application start or activity resumes after contact
 *        change is detected.
 *     2. Partial refresh, this is triggered when a participant is added to a conversation. This
 *        normally happens during SMS sync.
 */
@VisibleForTesting
public class ParticipantRefresh {
    private static final String TAG = LogUtil.BUGLE_DATAMODEL_TAG;

    /**
     * Refresh all participants including ones that were resolved before.
     */
    public static final int REFRESH_MODE_FULL = 0;

    /**
     * Refresh all unresolved participants.
     */
    public static final int REFRESH_MODE_INCREMENTAL = 1;

    /**
     * Force refresh all self participants.
     */
    public static final int REFRESH_MODE_SELF_ONLY = 2;

    public static class ConversationParticipantsQuery {
        public static final String[] PROJECTION = new String[] {
            ConversationParticipantsColumns._ID,
            ConversationParticipantsColumns.CONVERSATION_ID,
            ConversationParticipantsColumns.PARTICIPANT_ID
        };

        public static final int INDEX_ID                        = 0;
        public static final int INDEX_CONVERSATION_ID           = 1;
        public static final int INDEX_PARTICIPANT_ID            = 2;
    }

    // Track whether observer is initialized or not.
    private static volatile boolean sObserverInitialized = false;
    private static final Object sLock = new Object();
    private static final AtomicBoolean sFullRefreshScheduled = new AtomicBoolean(false);
    private static final Runnable sFullRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            final boolean oldScheduled = sFullRefreshScheduled.getAndSet(false);
            Assert.isTrue(oldScheduled);
            refreshParticipants(REFRESH_MODE_FULL);
        }
    };
    private static final Runnable sSelfOnlyRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshParticipants(REFRESH_MODE_SELF_ONLY);
        }
    };

    /**
     * A customized content resolver to track contact changes.
     */
    public static class ContactContentObserver extends ContentObserver {
        private volatile boolean mContactChanged = false;

        public ContactContentObserver() {
            super(null);
        }

        @Override
        public void onChange(final boolean selfChange) {
            super.onChange(selfChange);
            if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
                LogUtil.v(TAG, "Contacts changed");
            }
            mContactChanged = true;
        }

        public boolean getContactChanged() {
            return mContactChanged;
        }

        public void resetContactChanged() {
            mContactChanged = false;
        }

        public void initialize() {
            // TODO: Handle enterprise contacts post M once contacts provider supports it
            Factory.get().getApplicationContext().getContentResolver().registerContentObserver(
                    Phone.CONTENT_URI, true, this);
            mContactChanged = true; // Force a full refresh on initialization.
        }
    }

    /**
     * Refresh participants only if needed, i.e., application start or contact changed.
     */
    public static void refreshParticipantsIfNeeded() {
        if (ParticipantRefresh.getNeedFullRefresh() &&
                sFullRefreshScheduled.compareAndSet(false, true)) {
            if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
                LogUtil.v(TAG, "Started full participant refresh");
            }
            SafeAsyncTask.executeOnThreadPool(sFullRefreshRunnable);
        } else if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
            LogUtil.v(TAG, "Skipped full participant refresh");
        }
    }

    /**
     * Refresh self participants on subscription or settings change.
     */
    public static void refreshSelfParticipants() {
        SafeAsyncTask.executeOnThreadPool(sSelfOnlyRefreshRunnable);
    }

    private static boolean getNeedFullRefresh() {
        final ContactContentObserver observer = Factory.get().getContactContentObserver();
        if (observer == null) {
            // If there is no observer (for unittest cases), we don't need to refresh participants.
            return false;
        }

        if (!sObserverInitialized) {
            synchronized (sLock) {
                if (!sObserverInitialized) {
                    observer.initialize();
                    sObserverInitialized = true;
                }
            }
        }

        return observer.getContactChanged();
    }

    private static void resetNeedFullRefresh() {
        final ContactContentObserver observer = Factory.get().getContactContentObserver();
        if (observer != null) {
            observer.resetContactChanged();
        }
    }

    /**
     * This class is totally static. Make constructor to be private so that an instance
     * of this class would not be created by by mistake.
     */
    private ParticipantRefresh() {
    }

    /**
     * Refresh participants in Bugle.
     *
     * @param refreshMode the refresh mode desired. See {@link #REFRESH_MODE_FULL},
     *        {@link #REFRESH_MODE_INCREMENTAL}, and {@link #REFRESH_MODE_SELF_ONLY}
     */
     @VisibleForTesting
     static void refreshParticipants(final int refreshMode) {
        Assert.inRange(refreshMode, REFRESH_MODE_FULL, REFRESH_MODE_SELF_ONLY);
        if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
            switch (refreshMode) {
                case REFRESH_MODE_FULL:
                    LogUtil.v(TAG, "Start full participant refresh");
                    break;
                case REFRESH_MODE_INCREMENTAL:
                    LogUtil.v(TAG, "Start partial participant refresh");
                    break;
                case REFRESH_MODE_SELF_ONLY:
                    LogUtil.v(TAG, "Start self participant refresh");
                    break;
            }
        }

        if (!ContactUtil.hasReadContactsPermission() || !OsUtil.hasPhonePermission()) {
            if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
                LogUtil.v(TAG, "Skipping participant referesh because of permissions");
            }
            return;
        }

        if (refreshMode == REFRESH_MODE_FULL) {
            // resetNeedFullRefresh right away so that we will skip duplicated full refresh
            // requests.
            resetNeedFullRefresh();
        }

        if (refreshMode == REFRESH_MODE_FULL || refreshMode == REFRESH_MODE_SELF_ONLY) {
            refreshSelfParticipantList();
        }

        final ArrayList<String> changedParticipants = new ArrayList<String>();

        String selection = null;
        String[] selectionArgs = null;

        if (refreshMode == REFRESH_MODE_INCREMENTAL) {
            // In case of incremental refresh, filter out participants that are already resolved.
            selection = ParticipantColumns.CONTACT_ID + "=?";
            selectionArgs = new String[] {
                    String.valueOf(ParticipantData.PARTICIPANT_CONTACT_ID_NOT_RESOLVED) };
        } else if (refreshMode == REFRESH_MODE_SELF_ONLY) {
            // In case of self-only refresh, filter out non-self participants.
            selection = SELF_PARTICIPANTS_CLAUSE;
            selectionArgs = null;
        }

        final DatabaseWrapper db = DataModel.get().getDatabase();
        Cursor cursor = null;
        boolean selfUpdated = false;
        try {
            cursor = db.query(DatabaseHelper.PARTICIPANTS_TABLE,
                    ParticipantsQuery.PROJECTION, selection, selectionArgs, null, null, null);

            if (cursor != null) {
                while (cursor.moveToNext()) {
                    try {
                        final ParticipantData participantData =
                                ParticipantData.getFromCursor(cursor);
                        if (refreshParticipant(db, participantData)) {
                            if (participantData.isSelf()) {
                                selfUpdated = true;
                            }
                            updateParticipant(db, participantData);
                            final String id = participantData.getId();
                            changedParticipants.add(id);
                        }
                    } catch (final Exception exception) {
                        // Failure to update one participant shouldn't cancel the entire refresh.
                        // Log the failure so we know what's going on and resume the loop.
                        LogUtil.e(LogUtil.BUGLE_DATAMODEL_TAG, "ParticipantRefresh: Failed to " +
                                "update participant", exception);
                    }
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
            LogUtil.v(TAG, "Number of participants refreshed:" + changedParticipants.size());
        }

        // Refresh conversations for participants that are changed.
        if (changedParticipants.size() > 0) {
            BugleDatabaseOperations.refreshConversationsForParticipants(changedParticipants);
        }
        if (selfUpdated) {
            // Boom
            MessagingContentProvider.notifyAllParticipantsChanged();
            MessagingContentProvider.notifyAllMessagesChanged();
        }
    }

    private static final String SELF_PARTICIPANTS_CLAUSE = ParticipantColumns.SUB_ID
            + " NOT IN ( "
            + ParticipantData.OTHER_THAN_SELF_SUB_ID
            + " )";

    private static final Set<Integer> getExistingSubIds() {
        final DatabaseWrapper db = DataModel.get().getDatabase();
        final HashSet<Integer> existingSubIds = new HashSet<Integer>();

        Cursor cursor = null;
        try {
            cursor = db.query(DatabaseHelper.PARTICIPANTS_TABLE,
                    ParticipantsQuery.PROJECTION,
                    SELF_PARTICIPANTS_CLAUSE, null, null, null, null);

            if (cursor != null) {
                while (cursor.moveToNext()) {
                    final int subId = cursor.getInt(ParticipantsQuery.INDEX_SUB_ID);
                    existingSubIds.add(subId);
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return existingSubIds;
    }

    private static final String UPDATE_SELF_PARTICIPANT_SUBSCRIPTION_SQL =
            "UPDATE " + DatabaseHelper.PARTICIPANTS_TABLE + " SET "
            +  ParticipantColumns.SIM_SLOT_ID + " = %d, "
            +  ParticipantColumns.SUBSCRIPTION_COLOR + " = %d, "
            +  ParticipantColumns.SUBSCRIPTION_NAME + " = %s "
            + " WHERE %s";

    static String getUpdateSelfParticipantSubscriptionInfoSql(final int slotId,
            final int subscriptionColor, final String subscriptionName, final String where) {
        return String.format((Locale) null /* construct SQL string without localization */,
                UPDATE_SELF_PARTICIPANT_SUBSCRIPTION_SQL,
                slotId, subscriptionColor, subscriptionName, where);
    }

    /**
     * Ensure that there is a self participant corresponding to every active SIM. Also, ensure
     * that any other older SIM self participants are marked as inactive.
     */
    private static void refreshSelfParticipantList() {
        final DatabaseWrapper db = DataModel.get().getDatabase();

        final List<SubscriptionInfo> subInfoRecords =
                PhoneUtils.getDefault().getActiveSubscriptionInfoList();
        final ArrayMap<Integer, SubscriptionInfo> activeSubscriptionIdToRecordMap =
                new ArrayMap<Integer, SubscriptionInfo>();
        db.beginTransaction();
        final Set<Integer> existingSubIds = getExistingSubIds();

        try {
            if (subInfoRecords != null) {
                for (final SubscriptionInfo subInfoRecord : subInfoRecords) {
                    final int subId = subInfoRecord.getSubscriptionId();
                    // If its a new subscription, add it to the database.
                    if (!existingSubIds.contains(subId)) {
                        db.execSQL(DatabaseHelper.getCreateSelfParticipantSql(subId));
                        // Add it to the local set to guard against duplicated entries returned
                        // by subscription manager.
                        existingSubIds.add(subId);
                    }
                    activeSubscriptionIdToRecordMap.put(subId, subInfoRecord);

                    if (subId == PhoneUtils.getDefault().getDefaultSmsSubscriptionId()) {
                        // This is the system default subscription, so update the default self.
                        activeSubscriptionIdToRecordMap.put(ParticipantData.DEFAULT_SELF_SUB_ID,
                                subInfoRecord);
                    }
                }
            }

            // For subscriptions already in the database, refresh ParticipantColumns.SIM_SLOT_ID.
            for (final Integer subId : activeSubscriptionIdToRecordMap.keySet()) {
                final SubscriptionInfo record = activeSubscriptionIdToRecordMap.get(subId);
                final String displayName =
                        DatabaseUtils.sqlEscapeString(Objects.toString(record.getDisplayName(), ""));
                db.execSQL(getUpdateSelfParticipantSubscriptionInfoSql(record.getSimSlotIndex(),
                        record.getIconTint(), displayName,
                        ParticipantColumns.SUB_ID + " = " + subId));
            }
            db.execSQL(getUpdateSelfParticipantSubscriptionInfoSql(
                    ParticipantData.INVALID_SLOT_ID, Color.TRANSPARENT, "''",
                    ParticipantColumns.SUB_ID + " NOT IN (" +
                    Joiner.on(", ").join(activeSubscriptionIdToRecordMap.keySet()) + ")"));
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        // Fix up conversation self ids by reverting to default self for conversations whose self
        // ids are no longer active.
        refreshConversationSelfIds();
    }

    /**
     * Refresh one participant.
     * @return true if the ParticipantData was changed
     */
    public static boolean refreshParticipant(final DatabaseWrapper db,
            final ParticipantData participantData) {
        boolean updated = false;

        if (participantData.isSelf()) {
            final int selfChange = refreshFromSelfProfile(db, participantData);

            if (selfChange == SELF_PROFILE_EXISTS) {
                // If a self-profile exists, it takes precedence over Contacts data. So we are done.
                return true;
            }

            updated = (selfChange == SELF_PHONE_NUMBER_OR_SUBSCRIPTION_CHANGED);

            // Fall-through and try to update based on Contacts data
        }

        updated |= refreshFromContacts(db, participantData);
        return updated;
    }

    private static final int SELF_PHONE_NUMBER_OR_SUBSCRIPTION_CHANGED = 1;
    private static final int SELF_PROFILE_EXISTS = 2;

    private static int refreshFromSelfProfile(final DatabaseWrapper db,
            final ParticipantData participantData) {
        int changed = 0;
        // Refresh the phone number based on information from telephony
        if (participantData.updatePhoneNumberForSelfIfChanged()) {
            changed = SELF_PHONE_NUMBER_OR_SUBSCRIPTION_CHANGED;
        }

        // Refresh the subscription info based on information from SubscriptionManager.
        final SubscriptionInfo subscriptionInfo =
                PhoneUtils.get(participantData.getSubId()).getActiveSubscriptionInfo();
        if (participantData.updateSubscriptionInfoForSelfIfChanged(subscriptionInfo)) {
            changed = SELF_PHONE_NUMBER_OR_SUBSCRIPTION_CHANGED;
        }

        // For self participant, try getting name/avatar from self profile in CP2 first.
        // TODO: in case of multi-sim, profile would not be able to be used for
        // different numbers. Need to figure out that.
        Cursor selfCursor = null;
        try {
            selfCursor = ContactUtil.getSelf(db.getContext()).performSynchronousQuery();
            if (selfCursor != null && selfCursor.getCount() > 0) {
                selfCursor.moveToNext();
                final long selfContactId = selfCursor.getLong(ContactUtil.INDEX_CONTACT_ID);
                participantData.setContactId(selfContactId);
                participantData.setFullName(selfCursor.getString(
                        ContactUtil.INDEX_DISPLAY_NAME));
                participantData.setFirstName(
                        ContactUtil.lookupFirstName(db.getContext(), selfContactId));
                participantData.setProfilePhotoUri(selfCursor.getString(
                        ContactUtil.INDEX_PHOTO_URI));
                participantData.setLookupKey(selfCursor.getString(
                        ContactUtil.INDEX_SELF_QUERY_LOOKUP_KEY));
                return SELF_PROFILE_EXISTS;
            }
        } catch (final Exception exception) {
            // It's possible for contact query to fail and we don't want that to crash our app.
            // However, we need to at least log the exception so we know something was wrong.
            LogUtil.e(LogUtil.BUGLE_DATAMODEL_TAG, "Participant refresh: failed to refresh " +
                    "participant. exception=" + exception);
        } finally {
            if (selfCursor != null) {
                selfCursor.close();
            }
        }
        return changed;
    }

    private static boolean refreshFromContacts(final DatabaseWrapper db,
            final ParticipantData participantData) {
        final String normalizedDestination = participantData.getNormalizedDestination();
        final long currentContactId = participantData.getContactId();
        final String currentDisplayName = participantData.getFullName();
        final String currentFirstName = participantData.getFirstName();
        final String currentPhotoUri = participantData.getProfilePhotoUri();
        final String currentContactDestination = participantData.getContactDestination();

        Cursor matchingContactCursor = null;
        long matchingContactId = -1;
        String matchingDisplayName = null;
        String matchingFirstName = null;
        String matchingPhotoUri = null;
        String matchingLookupKey = null;
        String matchingDestination = null;
        boolean updated = false;

        if (TextUtils.isEmpty(normalizedDestination)) {
            // The normalized destination can be "" for the self id if we can't get it from the
            // SIM.  Some contact providers throw an IllegalArgumentException if you lookup "",
            // so we early out.
            return false;
        }

        try {
            matchingContactCursor = ContactUtil.lookupDestination(db.getContext(),
                    normalizedDestination).performSynchronousQuery();
            if (matchingContactCursor == null || matchingContactCursor.getCount() == 0) {
                // If there is no match, mark the participant as contact not found.
                if (currentContactId != ParticipantData.PARTICIPANT_CONTACT_ID_NOT_FOUND) {
                    participantData.setContactId(ParticipantData.PARTICIPANT_CONTACT_ID_NOT_FOUND);
                    participantData.setFullName(null);
                    participantData.setFirstName(null);
                    participantData.setProfilePhotoUri(null);
                    participantData.setLookupKey(null);
                    updated = true;
                }
                return updated;
            }

            while (matchingContactCursor.moveToNext()) {
                final long contactId = matchingContactCursor.getLong(ContactUtil.INDEX_CONTACT_ID);
                // Pick either the first contact or the contact with same id as previous matched
                // contact id.
                if (matchingContactId == -1 || currentContactId == contactId) {
                    matchingContactId = contactId;
                    matchingDisplayName = matchingContactCursor.getString(
                            ContactUtil.INDEX_DISPLAY_NAME);
                    matchingFirstName = ContactUtil.lookupFirstName(db.getContext(), contactId);
                    matchingPhotoUri = matchingContactCursor.getString(
                            ContactUtil.INDEX_PHOTO_URI);
                    matchingLookupKey = matchingContactCursor.getString(
                            ContactUtil.INDEX_LOOKUP_KEY);
                    matchingDestination = matchingContactCursor.getString(
                            ContactUtil.INDEX_PHONE_EMAIL);
                }

                // There is no need to try other contacts if the current contactId was not filled...
                if (currentContactId < 0
                        // or we found the matching contact id
                        || currentContactId == contactId) {
                    break;
                }
            }
        } catch (final Exception exception) {
            // It's possible for contact query to fail and we don't want that to crash our app.
            // However, we need to at least log the exception so we know something was wrong.
            LogUtil.e(LogUtil.BUGLE_DATAMODEL_TAG, "Participant refresh: failed to refresh " +
                    "participant. exception=" + exception);
            return false;
        } finally {
            if (matchingContactCursor != null) {
                matchingContactCursor.close();
            }
        }

        // Update participant only if something changed.
        final boolean isContactIdChanged = (matchingContactId != currentContactId);
        final boolean isDisplayNameChanged =
                !TextUtils.equals(matchingDisplayName, currentDisplayName);
        final boolean isFirstNameChanged = !TextUtils.equals(matchingFirstName, currentFirstName);
        final boolean isPhotoUrlChanged = !TextUtils.equals(matchingPhotoUri, currentPhotoUri);
        final boolean isDestinationChanged = !TextUtils.equals(matchingDestination,
                currentContactDestination);

        if (isContactIdChanged || isDisplayNameChanged || isFirstNameChanged || isPhotoUrlChanged
                || isDestinationChanged) {
            participantData.setContactId(matchingContactId);
            participantData.setFullName(matchingDisplayName);
            participantData.setFirstName(matchingFirstName);
            participantData.setProfilePhotoUri(matchingPhotoUri);
            participantData.setLookupKey(matchingLookupKey);
            participantData.setContactDestination(matchingDestination);
            if (isDestinationChanged) {
                // Update the send destination to the new one entered by user in Contacts.
                participantData.setSendDestination(matchingDestination);
            }
            updated = true;
        }

        return updated;
    }

    /**
     * Update participant with matching contact's contactId, displayName and photoUri.
     */
    private static void updateParticipant(final DatabaseWrapper db,
            final ParticipantData participantData) {
        final ContentValues values = new ContentValues();
        if (participantData.isSelf()) {
            // Self participants can refresh their normalized phone numbers
            values.put(ParticipantColumns.NORMALIZED_DESTINATION,
                    participantData.getNormalizedDestination());
            values.put(ParticipantColumns.DISPLAY_DESTINATION,
                    participantData.getDisplayDestination());
        }
        values.put(ParticipantColumns.CONTACT_ID, participantData.getContactId());
        values.put(ParticipantColumns.LOOKUP_KEY, participantData.getLookupKey());
        values.put(ParticipantColumns.FULL_NAME, participantData.getFullName());
        values.put(ParticipantColumns.FIRST_NAME, participantData.getFirstName());
        values.put(ParticipantColumns.PROFILE_PHOTO_URI, participantData.getProfilePhotoUri());
        values.put(ParticipantColumns.CONTACT_DESTINATION, participantData.getContactDestination());
        values.put(ParticipantColumns.SEND_DESTINATION, participantData.getSendDestination());

        db.beginTransaction();
        try {
            db.update(DatabaseHelper.PARTICIPANTS_TABLE, values, ParticipantColumns._ID + "=?",
                    new String[] { participantData.getId() });
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Get a list of inactive self ids in the participants table.
     */
    private static List<String> getInactiveSelfParticipantIds() {
        final DatabaseWrapper db = DataModel.get().getDatabase();
        final List<String> inactiveSelf = new ArrayList<String>();

        final String selection = ParticipantColumns.SIM_SLOT_ID + "=? AND " +
                SELF_PARTICIPANTS_CLAUSE;
        Cursor cursor = null;
        try {
            cursor = db.query(DatabaseHelper.PARTICIPANTS_TABLE,
                    new String[] { ParticipantColumns._ID },
                    selection, new String[] { String.valueOf(ParticipantData.INVALID_SLOT_ID) },
                    null, null, null);

            if (cursor != null) {
                while (cursor.moveToNext()) {
                    final String participantId = cursor.getString(0);
                    inactiveSelf.add(participantId);
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return inactiveSelf;
    }

    /**
     * Gets a list of conversations with the given self ids.
     */
    private static List<String> getConversationsWithSelfParticipantIds(final List<String> selfIds) {
        final DatabaseWrapper db = DataModel.get().getDatabase();
        final List<String> conversationIds = new ArrayList<String>();

        Cursor cursor = null;
        try {
            final StringBuilder selectionList = new StringBuilder();
            for (int i = 0; i < selfIds.size(); i++) {
                selectionList.append('?');
                if (i < selfIds.size() - 1) {
                    selectionList.append(',');
                }
            }
            final String selection =
                    ConversationColumns.CURRENT_SELF_ID + " IN (" + selectionList + ")";
            cursor = db.query(DatabaseHelper.CONVERSATIONS_TABLE,
                    new String[] { ConversationColumns._ID },
                    selection, selfIds.toArray(new String[0]),
                    null, null, null);

            if (cursor != null) {
                while (cursor.moveToNext()) {
                    final String conversationId = cursor.getString(0);
                    conversationIds.add(conversationId);
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return conversationIds;
    }

    /**
     * Refresh one conversation's self id.
     */
    private static void updateConversationSelfId(final String conversationId,
            final String selfId) {
        final DatabaseWrapper db = DataModel.get().getDatabase();

        db.beginTransaction();
        try {
            BugleDatabaseOperations.updateConversationSelfIdInTransaction(db, conversationId,
                    selfId);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        MessagingContentProvider.notifyMessagesChanged(conversationId);
        MessagingContentProvider.notifyConversationMetadataChanged(conversationId);
        UIIntents.get().broadcastConversationSelfIdChange(db.getContext(), conversationId, selfId);
    }

    /**
     * After refreshing the self participant list, find all conversations with inactive self ids,
     * and switch them back to system default.
     */
    private static void refreshConversationSelfIds() {
        final List<String> inactiveSelfs = getInactiveSelfParticipantIds();
        if (inactiveSelfs.size() == 0) {
            return;
        }
        final List<String> conversationsToRefresh =
                getConversationsWithSelfParticipantIds(inactiveSelfs);
        if (conversationsToRefresh.size() == 0) {
            return;
        }
        final DatabaseWrapper db = DataModel.get().getDatabase();
        final ParticipantData defaultSelf =
                BugleDatabaseOperations.getOrCreateSelf(db, ParticipantData.DEFAULT_SELF_SUB_ID);

        if (defaultSelf != null) {
            for (final String conversationId : conversationsToRefresh) {
                updateConversationSelfId(conversationId, defaultSelf.getId());
            }
        }
    }
}
