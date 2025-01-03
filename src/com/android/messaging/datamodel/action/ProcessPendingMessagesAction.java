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

package com.android.messaging.datamodel.action;

import android.content.ContentValues;
import android.database.Cursor;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.ServiceState;

import com.android.messaging.Factory;
import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DataModelImpl;
import com.android.messaging.datamodel.DatabaseHelper;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.MessagingContentProvider;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.util.BugleGservices;
import com.android.messaging.util.BugleGservicesKeys;
import com.android.messaging.util.BuglePrefs;
import com.android.messaging.util.BuglePrefsKeys;
import com.android.messaging.util.ConnectivityUtil;
import com.android.messaging.util.ConnectivityUtil.ConnectivityListener;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.PhoneUtils;

/**
 * Action used to lookup any messages in the pending send/download state and either fail them or
 * retry their action based on subscriptions. This action only initiates one retry at a time for
 * both sending/downloading. Further retries should be triggered by successful sending/downloading
 * of a message, network status change or exponential backoff timer.
 */
public class ProcessPendingMessagesAction extends Action implements Parcelable {
    private static final String TAG = LogUtil.BUGLE_DATAMODEL_TAG;
    // PENDING_INTENT_BASE_REQUEST_CODE + subId(-1 for pre-L_MR1) is used per subscription uniquely.
    private static final int PENDING_INTENT_BASE_REQUEST_CODE = 103;

    private static final String KEY_SUB_ID = "sub_id";

    public static void processFirstPendingMessage() {
        PhoneUtils.forEachActiveSubscription(new PhoneUtils.SubscriptionRunnable() {
            @Override
            public void runForSubscription(final int subId) {
                // Clear any pending alarms or connectivity events
                unregister(subId);
                // Clear retry count
                setRetry(0, subId);
                // Start action
                final ProcessPendingMessagesAction action = new ProcessPendingMessagesAction();
                action.actionParameters.putInt(KEY_SUB_ID, subId);
                action.start();
            }
        });
    }

    public static void scheduleProcessPendingMessagesAction(final boolean failed,
            final Action processingAction) {
        final int subId = processingAction.actionParameters
                .getInt(KEY_SUB_ID, ParticipantData.DEFAULT_SELF_SUB_ID);
        LogUtil.i(TAG, "ProcessPendingMessagesAction: Scheduling pending messages"
                + (failed ? "(message failed)" : "") + " for subId " + subId);
        // Can safely clear any pending alarms or connectivity events as either an action
        // is currently running or we will run now or register if pending actions possible.
        unregister(subId);

        final boolean isDefaultSmsApp = PhoneUtils.getDefault().isDefaultSmsApp();
        boolean scheduleAlarm = false;
        // If message succeeded and if Bugle is default SMS app just carry on with next message
        if (!failed && isDefaultSmsApp) {
            // Clear retry attempt count as something just succeeded
            setRetry(0, subId);

            // Lookup and queue next message for each sending/downloading for immediate processing
            // by background worker. If there are no pending messages, this will do nothing and
            // return true.
            final ProcessPendingMessagesAction action = new ProcessPendingMessagesAction();
            if (action.queueActions(processingAction)) {
                if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
                    if (processingAction.hasBackgroundActions()) {
                        LogUtil.v(TAG, "ProcessPendingMessagesAction: Action queued");
                    } else {
                        LogUtil.v(TAG, "ProcessPendingMessagesAction: No actions to queue");
                    }
                }
                // Have queued next action if needed, nothing more to do
                return;
            }
            // In case of error queuing schedule a retry
            scheduleAlarm = true;
            LogUtil.w(TAG, "ProcessPendingMessagesAction: Action failed to queue; retrying");
        }
        if (getHavePendingMessages(subId) || scheduleAlarm) {
            // Still have a pending message that needs to be queued for processing
            final ConnectivityListener listener = new ConnectivityListener() {
                @Override
                public void onPhoneStateChanged(final int serviceState) {
                    if (serviceState == ServiceState.STATE_IN_SERVICE) {
                        LogUtil.i(TAG, "ProcessPendingMessagesAction: Now connected for subId "
                                + subId + ", starting action");

                        // Clear any pending alarms or connectivity events but leave attempt count
                        // alone
                        unregister(subId);

                        // Start action
                        final ProcessPendingMessagesAction action =
                                new ProcessPendingMessagesAction();
                        action.actionParameters.putInt(KEY_SUB_ID, subId);
                        action.start();
                    }
                }
            };
            // Read and increment attempt number from shared prefs
            final int retryAttempt = getNextRetry(subId);
            register(listener, retryAttempt, subId);
        } else {
            // No more pending messages (presumably the message that failed has expired) or it
            // may be possible that a send and a download are already in process.
            // Clear retry attempt count.
            // TODO Might be premature if send and download in process...
            // but worst case means we try to send a bit more often.
            setRetry(0, subId);
            LogUtil.i(TAG, "ProcessPendingMessagesAction: No more pending messages");
        }
    }

    private static void register(final ConnectivityListener listener, final int retryAttempt,
            int subId) {
        int retryNumber = retryAttempt;

        // Register to be notified about connectivity changes
        ConnectivityUtil connectivityUtil = DataModelImpl.getConnectivityUtil(subId);
        if (connectivityUtil != null) {
            connectivityUtil.register(listener);
        }

        final ProcessPendingMessagesAction action = new ProcessPendingMessagesAction();
        action.actionParameters.putInt(KEY_SUB_ID, subId);
        final long initialBackoffMs = BugleGservices.get().getLong(
                BugleGservicesKeys.INITIAL_MESSAGE_RESEND_DELAY_MS,
                BugleGservicesKeys.INITIAL_MESSAGE_RESEND_DELAY_MS_DEFAULT);
        final long maxDelayMs = BugleGservices.get().getLong(
                BugleGservicesKeys.MAX_MESSAGE_RESEND_DELAY_MS,
                BugleGservicesKeys.MAX_MESSAGE_RESEND_DELAY_MS_DEFAULT);
        long delayMs;
        long nextDelayMs = initialBackoffMs;
        do {
            delayMs = nextDelayMs;
            retryNumber--;
            nextDelayMs = delayMs * 2;
        }
        while (retryNumber > 0 && nextDelayMs < maxDelayMs);

        LogUtil.i(TAG, "ProcessPendingMessagesAction: Registering for retry #" + retryAttempt
                + " in " + delayMs + " ms for subId " + subId);

        action.schedule(PENDING_INTENT_BASE_REQUEST_CODE + subId, delayMs);
    }

    private static void unregister(final int subId) {
        // Clear any pending alarms or connectivity events
        ConnectivityUtil connectivityUtil = DataModelImpl.getConnectivityUtil(subId);
        if (connectivityUtil != null) {
            connectivityUtil.unregister();
        }

        final ProcessPendingMessagesAction action = new ProcessPendingMessagesAction();
        action.schedule(PENDING_INTENT_BASE_REQUEST_CODE + subId, Long.MAX_VALUE);

        if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
            LogUtil.v(TAG, "ProcessPendingMessagesAction: Unregistering for connectivity changed "
                    + "events and clearing scheduled alarm for subId " + subId);
        }
    }

    private static void setRetry(final int retryAttempt, int subId) {
        final BuglePrefs prefs = Factory.get().getSubscriptionPrefs(subId);
        prefs.putInt(BuglePrefsKeys.PROCESS_PENDING_MESSAGES_RETRY_COUNT, retryAttempt);
    }

    private static int getNextRetry(int subId) {
        final BuglePrefs prefs = Factory.get().getSubscriptionPrefs(subId);
        final int retryAttempt =
                prefs.getInt(BuglePrefsKeys.PROCESS_PENDING_MESSAGES_RETRY_COUNT, 0) + 1;
        prefs.putInt(BuglePrefsKeys.PROCESS_PENDING_MESSAGES_RETRY_COUNT, retryAttempt);
        return retryAttempt;
    }

    private ProcessPendingMessagesAction() {
    }

    /**
     * Read from the DB and determine if there are any messages we should process
     *
     * @param subId the subId
     * @return true if we have pending messages
     */
    private static boolean getHavePendingMessages(final int subId) {
        final DatabaseWrapper db = DataModel.get().getDatabase();
        final long now = System.currentTimeMillis();
        final String selfId = ParticipantData.getParticipantId(db, subId);
        if (selfId == null) {
            // This could be happened before refreshing participant.
            LogUtil.w(TAG, "ProcessPendingMessagesAction: selfId is null for subId " + subId);
            return false;
        }

        final String toSendMessageId = findNextMessageToSend(db, now, selfId);
        if (toSendMessageId != null) {
            return true;
        } else {
            final String toDownloadMessageId = findNextMessageToDownload(db, now, selfId);
            if (toDownloadMessageId != null) {
                return true;
            }
        }
        // Messages may be in the process of sending/downloading even when there are no pending
        // messages...
        return false;
    }

    /**
     * Queue any pending actions
     *
     * @param actionState
     * @return true if action queued (or no actions to queue) else false
     */
    private boolean queueActions(final Action processingAction) {
        final DatabaseWrapper db = DataModel.get().getDatabase();
        final long now = System.currentTimeMillis();
        boolean succeeded = true;
        final int subId = processingAction.actionParameters
                .getInt(KEY_SUB_ID, ParticipantData.DEFAULT_SELF_SUB_ID);

        LogUtil.i(TAG, "ProcessPendingMessagesAction: Start queueing for subId " + subId);

        final String selfId = ParticipantData.getParticipantId(db, subId);
        if (selfId == null) {
            // This could be happened before refreshing participant.
            LogUtil.w(TAG, "ProcessPendingMessagesAction: selfId is null");
            return false;
        }

        // Will queue no more than one message to send plus one message to download
        // This keeps outgoing messages "in order" but allow downloads to happen even if sending
        // gets blocked until messages time out. Manual resend bumps messages to head of queue.
        final String toSendMessageId = findNextMessageToSend(db, now, selfId);
        final String toDownloadMessageId = findNextMessageToDownload(db, now, selfId);
        if (toSendMessageId != null) {
            LogUtil.i(TAG, "ProcessPendingMessagesAction: Queueing message " + toSendMessageId
                    + " for sending");
            // This could queue nothing
            if (!SendMessageAction.queueForSendInBackground(toSendMessageId, processingAction)) {
                LogUtil.w(TAG, "ProcessPendingMessagesAction: Failed to queue message "
                        + toSendMessageId + " for sending");
                succeeded = false;
            }
        }
        if (toDownloadMessageId != null) {
            LogUtil.i(TAG, "ProcessPendingMessagesAction: Queueing message " + toDownloadMessageId
                    + " for download");
            // This could queue nothing
            if (!DownloadMmsAction.queueMmsForDownloadInBackground(toDownloadMessageId,
                    processingAction)) {
                LogUtil.w(TAG, "ProcessPendingMessagesAction: Failed to queue message "
                        + toDownloadMessageId + " for download");
                succeeded = false;
            }
        }
        if (toSendMessageId == null && toDownloadMessageId == null) {
            LogUtil.i(TAG, "ProcessPendingMessagesAction: No messages to send or download");
        }
        return succeeded;
    }

    @Override
    protected Object executeAction() {
        final int subId = actionParameters.getInt(KEY_SUB_ID, ParticipantData.DEFAULT_SELF_SUB_ID);
        // If triggered by alarm will not have unregistered yet
        unregister(subId);

        if (PhoneUtils.getDefault().isDefaultSmsApp()) {
            if (!queueActions(this)) {
                LogUtil.v(TAG, "ProcessPendingMessagesAction: rescheduling");
                // TODO: Need to clear retry count here?
                scheduleProcessPendingMessagesAction(true /* failed */, this);
            }
        } else {
            if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
                LogUtil.v(TAG, "ProcessPendingMessagesAction: Not default SMS app; rescheduling");
            }
            scheduleProcessPendingMessagesAction(true /* failed */, this);
        }

        return null;
    }

    private static String findNextMessageToSend(final DatabaseWrapper db, final long now,
            final String selfId) {
        String toSendMessageId = null;
        Cursor cursor = null;
        int sendingCnt = 0;
        int pendingCnt = 0;
        int failedCnt = 0;
        db.beginTransaction();
        try {
            // First check to see if we have any messages already sending
            sendingCnt = (int) db.queryNumEntries(DatabaseHelper.MESSAGES_TABLE,
                    DatabaseHelper.MessageColumns.STATUS + " IN (?, ?) AND "
                    + DatabaseHelper.MessageColumns.SELF_PARTICIPANT_ID + " =? ",
                    new String[] {
                        Integer.toString(MessageData.BUGLE_STATUS_OUTGOING_SENDING),
                        Integer.toString(MessageData.BUGLE_STATUS_OUTGOING_RESENDING),
                        selfId}
                    );

            // Look for messages we could send
            cursor = db.query(DatabaseHelper.MESSAGES_TABLE,
                    MessageData.getProjection(),
                    DatabaseHelper.MessageColumns.STATUS + " IN (?, ?) AND "
                    + DatabaseHelper.MessageColumns.SELF_PARTICIPANT_ID + " =? ",
                    new String[] {
                        Integer.toString(MessageData.BUGLE_STATUS_OUTGOING_YET_TO_SEND),
                        Integer.toString(MessageData.BUGLE_STATUS_OUTGOING_AWAITING_RETRY),
                        selfId
                    },
                    null,
                    null,
                    DatabaseHelper.MessageColumns.RECEIVED_TIMESTAMP + " ASC");
            pendingCnt = cursor.getCount();

            final ContentValues values = new ContentValues();
            values.put(DatabaseHelper.MessageColumns.STATUS,
                    MessageData.BUGLE_STATUS_OUTGOING_FAILED);

            boolean isActiveSubscription = true;
            final ParticipantData messageSelf =
                    BugleDatabaseOperations.getExistingParticipant(db, selfId);
            if (messageSelf == null || !messageSelf.isActiveSubscription()) {
                isActiveSubscription = false;
            }

            while (cursor.moveToNext()) {
                final MessageData message = new MessageData();
                message.bind(cursor);

                // Mark this message as failed if the message's self is inactive or the message is
                // outside of resend window
                if (!isActiveSubscription || !message.getInResendWindow(now)) {
                    failedCnt++;

                    // Mark message as failed
                    BugleDatabaseOperations.updateMessageRow(db, message.getMessageId(), values);
                    MessagingContentProvider.notifyMessagesChanged(message.getConversationId());
                } else {
                    // If no messages currently sending
                    if (sendingCnt == 0) {
                        // Send this message
                        toSendMessageId = message.getMessageId();
                    }
                    break;
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            if (cursor != null) {
                cursor.close();
            }
        }

        if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
            LogUtil.d(TAG, "ProcessPendingMessagesAction: "
                    + sendingCnt + " messages already sending, "
                    + pendingCnt + " messages to send, "
                    + failedCnt + " failed messages");
        }

        return toSendMessageId;
    }

    private static String findNextMessageToDownload(final DatabaseWrapper db, final long now,
            final String selfId) {
        String toDownloadMessageId = null;
        Cursor cursor = null;
        int downloadingCnt = 0;
        int pendingCnt = 0;
        db.beginTransaction();
        try {
            // First check if we have any messages already downloading
            downloadingCnt = (int) db.queryNumEntries(DatabaseHelper.MESSAGES_TABLE,
                    DatabaseHelper.MessageColumns.STATUS + " IN (?, ?) AND "
                    + DatabaseHelper.MessageColumns.SELF_PARTICIPANT_ID + " =?",
                    new String[] {
                        Integer.toString(MessageData.BUGLE_STATUS_INCOMING_AUTO_DOWNLOADING),
                        Integer.toString(MessageData.BUGLE_STATUS_INCOMING_MANUAL_DOWNLOADING),
                        selfId
                    });

            // TODO: This query is not actually needed if downloadingCnt == 0.
            cursor = db.query(DatabaseHelper.MESSAGES_TABLE,
                    MessageData.getProjection(),
                    DatabaseHelper.MessageColumns.STATUS + " IN (?, ?) AND "
                    + DatabaseHelper.MessageColumns.SELF_PARTICIPANT_ID + " =? ",
                    new String[]{
                        Integer.toString(MessageData.BUGLE_STATUS_INCOMING_RETRYING_AUTO_DOWNLOAD),
                        Integer.toString(
                                MessageData.BUGLE_STATUS_INCOMING_RETRYING_MANUAL_DOWNLOAD),
                        selfId
                    },
                    null,
                    null,
                    DatabaseHelper.MessageColumns.RECEIVED_TIMESTAMP + " ASC");

            pendingCnt = cursor.getCount();

            // If no messages are currently downloading and there is a download pending,
            // queue the download of the oldest pending message.
            if (downloadingCnt == 0 && cursor.moveToNext()) {
                // Always start the next pending message. We will check if a download has
                // expired in DownloadMmsAction and mark message failed there.
                final MessageData message = new MessageData();
                message.bind(cursor);
                toDownloadMessageId = message.getMessageId();
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            if (cursor != null) {
                cursor.close();
            }
        }

        if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
            LogUtil.d(TAG, "ProcessPendingMessagesAction: "
                    + downloadingCnt + " messages already downloading, "
                    + pendingCnt + " messages to download");
        }

        return toDownloadMessageId;
    }

    private ProcessPendingMessagesAction(final Parcel in) {
        super(in);
    }

    public static final Parcelable.Creator<ProcessPendingMessagesAction> CREATOR
            = new Parcelable.Creator<ProcessPendingMessagesAction>() {
        @Override
        public ProcessPendingMessagesAction createFromParcel(final Parcel in) {
            return new ProcessPendingMessagesAction(in);
        }

        @Override
        public ProcessPendingMessagesAction[] newArray(final int size) {
            return new ProcessPendingMessagesAction[size];
        }
    };

    @Override
    public void writeToParcel(final Parcel parcel, final int flags) {
        writeActionToParcel(parcel, flags);
    }
}
