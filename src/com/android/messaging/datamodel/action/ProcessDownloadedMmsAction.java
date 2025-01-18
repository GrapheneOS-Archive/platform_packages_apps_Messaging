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

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.provider.Telephony.Mms;
import android.telephony.SmsManager;
import android.text.TextUtils;

import com.android.messaging.Factory;
import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.BugleNotifications;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DataModelException;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.MessagingContentProvider;
import com.android.messaging.datamodel.MmsFileProvider;
import com.android.messaging.datamodel.SyncManager;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.mmslib.SqliteWrapper;
import com.android.messaging.mmslib.pdu.PduHeaders;
import com.android.messaging.mmslib.pdu.RetrieveConf;
import com.android.messaging.sms.DatabaseMessages;
import com.android.messaging.sms.MmsSender;
import com.android.messaging.sms.MmsUtils;
import com.android.messaging.util.Assert;
import com.android.messaging.util.LogUtil;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/**
 * Processes an MMS message after it has been downloaded.
 * NOTE: This action must queue a ProcessPendingMessagesAction when it is done (success or failure).
 */
public class ProcessDownloadedMmsAction extends Action {
    private static final String TAG = LogUtil.BUGLE_DATAMODEL_TAG;

    // Always set when message downloaded
    private static final String KEY_DOWNLOADED_BY_PLATFORM = "downloaded_by_platform";
    private static final String KEY_MESSAGE_ID = "message_id";
    private static final String KEY_NOTIFICATION_URI = "notification_uri";
    private static final String KEY_CONVERSATION_ID = "conversation_id";
    private static final String KEY_PARTICIPANT_ID = "participant_id";
    private static final String KEY_STATUS_IF_FAILED = "status_if_failed";

    // Set when message downloaded by platform (L+)
    private static final String KEY_RESULT_CODE = "result_code";
    private static final String KEY_HTTP_STATUS_CODE = "http_status_code";
    private static final String KEY_CONTENT_URI = "content_uri";
    private static final String KEY_SUB_ID = "sub_id";
    private static final String KEY_SUB_PHONE_NUMBER = "sub_phone_number";
    private static final String KEY_TRANSACTION_ID = "transaction_id";
    private static final String KEY_CONTENT_LOCATION = "content_location";
    private static final String KEY_AUTO_DOWNLOAD = "auto_download";
    private static final String KEY_RECEIVED_TIMESTAMP = "received_timestamp";
    private static final String KEY_EXPIRY = "expiry";

    private static final String KEY_STATUS = "status";
    private static final String KEY_RAW_STATUS = "raw_status";
    private static final String KEY_MMS_URI = "mms_uri";

    // Used to send a deferred response in response to auto-download failure
    private static final String KEY_SEND_DEFERRED_RESP_STATUS = "send_deferred_resp_status";

    // Results passed from background worker to processCompletion
    private static final String BUNDLE_REQUEST_STATUS = "request_status";
    private static final String BUNDLE_RAW_TELEPHONY_STATUS = "raw_status";
    private static final String BUNDLE_MMS_URI = "mms_uri";

    // This is called when MMS lib API returns via PendingIntent
    public static void processMessageDownloaded(final int resultCode, final Bundle extras) {
        final String messageId = extras.getString(DownloadMmsAction.EXTRA_MESSAGE_ID);
        final Uri contentUri = extras.getParcelable(DownloadMmsAction.EXTRA_CONTENT_URI);
        final Uri notificationUri = extras.getParcelable(DownloadMmsAction.EXTRA_NOTIFICATION_URI);
        final String conversationId = extras.getString(DownloadMmsAction.EXTRA_CONVERSATION_ID);
        final String participantId = extras.getString(DownloadMmsAction.EXTRA_PARTICIPANT_ID);
        Assert.notNull(messageId);
        Assert.notNull(contentUri);
        Assert.notNull(notificationUri);
        Assert.notNull(conversationId);
        Assert.notNull(participantId);

        final ProcessDownloadedMmsAction action = new ProcessDownloadedMmsAction();
        final Bundle params = action.actionParameters;
        params.putBoolean(KEY_DOWNLOADED_BY_PLATFORM, true);
        params.putString(KEY_MESSAGE_ID, messageId);
        params.putInt(KEY_RESULT_CODE, resultCode);
        params.putInt(KEY_HTTP_STATUS_CODE,
                extras.getInt(SmsManager.EXTRA_MMS_HTTP_STATUS, 0));
        params.putParcelable(KEY_CONTENT_URI, contentUri);
        params.putParcelable(KEY_NOTIFICATION_URI, notificationUri);
        params.putInt(KEY_SUB_ID,
                extras.getInt(DownloadMmsAction.EXTRA_SUB_ID, ParticipantData.DEFAULT_SELF_SUB_ID));
        params.putString(KEY_SUB_PHONE_NUMBER,
                extras.getString(DownloadMmsAction.EXTRA_SUB_PHONE_NUMBER));
        params.putString(KEY_TRANSACTION_ID,
                extras.getString(DownloadMmsAction.EXTRA_TRANSACTION_ID));
        params.putString(KEY_CONTENT_LOCATION,
                extras.getString(DownloadMmsAction.EXTRA_CONTENT_LOCATION));
        params.putBoolean(KEY_AUTO_DOWNLOAD,
                extras.getBoolean(DownloadMmsAction.EXTRA_AUTO_DOWNLOAD));
        params.putLong(KEY_RECEIVED_TIMESTAMP,
                extras.getLong(DownloadMmsAction.EXTRA_RECEIVED_TIMESTAMP));
        params.putString(KEY_CONVERSATION_ID, conversationId);
        params.putString(KEY_PARTICIPANT_ID, participantId);
        params.putInt(KEY_STATUS_IF_FAILED,
                extras.getInt(DownloadMmsAction.EXTRA_STATUS_IF_FAILED));
        params.putLong(KEY_EXPIRY, extras.getLong(DownloadMmsAction.EXTRA_EXPIRY));
        action.start();
    }

    // This is called for fast failing downloading (due to airplane mode or mobile data )
    public static void processMessageDownloadFastFailed(final String messageId,
            final Uri notificationUri, final String conversationId, final String participantId,
            final String contentLocation, final int subId, final String subPhoneNumber,
            final int statusIfFailed, final boolean autoDownload, final String transactionId,
            final int resultCode) {
        Assert.notNull(messageId);
        Assert.notNull(notificationUri);
        Assert.notNull(conversationId);
        Assert.notNull(participantId);

        final ProcessDownloadedMmsAction action = new ProcessDownloadedMmsAction();
        final Bundle params = action.actionParameters;
        params.putBoolean(KEY_DOWNLOADED_BY_PLATFORM, true);
        params.putString(KEY_MESSAGE_ID, messageId);
        params.putInt(KEY_RESULT_CODE, resultCode);
        params.putParcelable(KEY_NOTIFICATION_URI, notificationUri);
        params.putInt(KEY_SUB_ID, subId);
        params.putString(KEY_SUB_PHONE_NUMBER, subPhoneNumber);
        params.putString(KEY_CONTENT_LOCATION, contentLocation);
        params.putBoolean(KEY_AUTO_DOWNLOAD, autoDownload);
        params.putString(KEY_CONVERSATION_ID, conversationId);
        params.putString(KEY_PARTICIPANT_ID, participantId);
        params.putInt(KEY_STATUS_IF_FAILED, statusIfFailed);
        params.putString(KEY_TRANSACTION_ID, transactionId);
        action.start();
    }

    public static void processDownloadActionFailure(final String messageId, final int status,
            final int rawStatus, final String conversationId, final String participantId,
            final int statusIfFailed, final int subId, final String transactionId) {
        Assert.notNull(messageId);
        Assert.notNull(conversationId);
        Assert.notNull(participantId);

        final ProcessDownloadedMmsAction action = new ProcessDownloadedMmsAction();
        final Bundle params = action.actionParameters;
        params.putBoolean(KEY_DOWNLOADED_BY_PLATFORM, false);
        params.putString(KEY_MESSAGE_ID, messageId);
        params.putInt(KEY_STATUS, status);
        params.putInt(KEY_RAW_STATUS, rawStatus);
        params.putString(KEY_CONVERSATION_ID, conversationId);
        params.putString(KEY_PARTICIPANT_ID, participantId);
        params.putInt(KEY_STATUS_IF_FAILED, statusIfFailed);
        params.putInt(KEY_SUB_ID, subId);
        params.putString(KEY_TRANSACTION_ID, transactionId);
        action.start();
    }

    public static void sendDeferredRespStatus(final String messageId, final String transactionId,
            final String contentLocation, final int subId) {
        final ProcessDownloadedMmsAction action = new ProcessDownloadedMmsAction();
        final Bundle params = action.actionParameters;
        params.putString(KEY_MESSAGE_ID, messageId);
        params.putString(KEY_TRANSACTION_ID, transactionId);
        params.putString(KEY_CONTENT_LOCATION, contentLocation);
        params.putBoolean(KEY_SEND_DEFERRED_RESP_STATUS, true);
        params.putInt(KEY_SUB_ID, subId);
        action.start();
    }

    private ProcessDownloadedMmsAction() {
        // Callers must use one of the static methods above
    }

    @Override
    protected Object executeAction() {
        // Fire up the background worker
        requestBackgroundWork();
        return null;
    }

    @Override
    protected Bundle doBackgroundWork() throws DataModelException {
        final Context context = Factory.get().getApplicationContext();
        final int subId = actionParameters.getInt(KEY_SUB_ID, ParticipantData.DEFAULT_SELF_SUB_ID);
        final String messageId = actionParameters.getString(KEY_MESSAGE_ID);
        final String transactionId = actionParameters.getString(KEY_TRANSACTION_ID);
        final String contentLocation = actionParameters.getString(KEY_CONTENT_LOCATION);
        final boolean sendDeferredRespStatus =
                actionParameters.getBoolean(KEY_SEND_DEFERRED_RESP_STATUS, false);

        // Send a response indicating that auto-download failed
        if (sendDeferredRespStatus) {
            if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
                LogUtil.v(TAG, "DownloadMmsAction: Auto-download of message " + messageId
                        + " failed; sending DEFERRED NotifyRespInd");
            }
            MmsUtils.sendNotifyResponseForMmsDownload(
                    context,
                    subId,
                    MmsUtils.stringToBytes(transactionId, "UTF-8"),
                    contentLocation,
                    PduHeaders.STATUS_DEFERRED);
            return null;
        }

        // Processing a real MMS download
        final boolean downloadedByPlatform = actionParameters.getBoolean(
                KEY_DOWNLOADED_BY_PLATFORM);

        final int status;
        int rawStatus = MmsUtils.PDU_HEADER_VALUE_UNDEFINED;
        Uri mmsUri = null;

        if (downloadedByPlatform) {
            final int resultCode = actionParameters.getInt(KEY_RESULT_CODE);
            if (resultCode == Activity.RESULT_OK) {
                final Uri contentUri = actionParameters.getParcelable(KEY_CONTENT_URI);
                final File downloadedFile = MmsFileProvider.getFile(contentUri);
                byte[] downloadedData = null;
                try {
                    downloadedData = Files.readAllBytes(downloadedFile.toPath());
                } catch (final FileNotFoundException e) {
                    LogUtil.e(TAG, "ProcessDownloadedMmsAction: MMS download file not found: "
                            + downloadedFile.getAbsolutePath());
                } catch (final IOException e) {
                    LogUtil.e(TAG, "ProcessDownloadedMmsAction: Error reading MMS download file: "
                            + downloadedFile.getAbsolutePath(), e);
                }

                // Can delete the temp file now
                if (downloadedFile.exists()) {
                    downloadedFile.delete();
                    if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
                        LogUtil.d(TAG, "ProcessDownloadedMmsAction: Deleted temp file with "
                                + "downloaded MMS pdu: " + downloadedFile.getAbsolutePath());
                    }
                }

                if (downloadedData != null) {
                    final RetrieveConf retrieveConf =
                            MmsSender.parseRetrieveConf(downloadedData, subId);
                    if (MmsUtils.isDumpMmsEnabled()) {
                        MmsUtils.dumpPdu(downloadedData, retrieveConf);
                    }
                    if (retrieveConf != null) {
                        // Insert the downloaded MMS into telephony
                        final Uri notificationUri = actionParameters.getParcelable(
                                KEY_NOTIFICATION_URI);
                        final String subPhoneNumber = actionParameters.getString(
                                KEY_SUB_PHONE_NUMBER);
                        final boolean autoDownload = actionParameters.getBoolean(
                                KEY_AUTO_DOWNLOAD);
                        final long receivedTimestampInSeconds =
                                actionParameters.getLong(KEY_RECEIVED_TIMESTAMP);
                        final long expiry = actionParameters.getLong(KEY_EXPIRY);

                        // Inform sync we're adding a message to telephony
                        final SyncManager syncManager = DataModel.get().getSyncManager();
                        syncManager.onNewMessageInserted(receivedTimestampInSeconds * 1000L);

                        final MmsUtils.StatusPlusUri result =
                                MmsUtils.insertDownloadedMessageAndSendResponse(context,
                                        notificationUri, subId, subPhoneNumber, transactionId,
                                        contentLocation, autoDownload, receivedTimestampInSeconds,
                                        expiry, retrieveConf);
                        status = result.status;
                        rawStatus = result.rawStatus;
                        mmsUri = result.uri;
                    } else {
                        // Invalid response PDU
                        status = MmsUtils.MMS_REQUEST_MANUAL_RETRY;
                    }
                } else {
                    // Failed to read download file
                    status = MmsUtils.MMS_REQUEST_MANUAL_RETRY;
                }
            } else {
                LogUtil.w(TAG, "ProcessDownloadedMmsAction: Platform returned error resultCode: "
                        + resultCode);
                final int httpStatusCode = actionParameters.getInt(KEY_HTTP_STATUS_CODE);
                status = MmsSender.getErrorResultStatus(resultCode, httpStatusCode);
            }
        } else {
            // Message was already processed by the internal API, or the download action failed.
            // In either case, we just need to copy the status to the response bundle.
            status = actionParameters.getInt(KEY_STATUS);
            rawStatus = actionParameters.getInt(KEY_RAW_STATUS);
            mmsUri = actionParameters.getParcelable(KEY_MMS_URI);
        }

        final Bundle response = new Bundle();
        response.putInt(BUNDLE_REQUEST_STATUS, status);
        response.putInt(BUNDLE_RAW_TELEPHONY_STATUS, rawStatus);
        response.putParcelable(BUNDLE_MMS_URI, mmsUri);
        return response;
    }

    @Override
    protected Object processBackgroundResponse(final Bundle response) {
        if (response == null) {
            // No message download to process; doBackgroundWork sent a notify deferred response
            Assert.isTrue(actionParameters.getBoolean(KEY_SEND_DEFERRED_RESP_STATUS));
            ProcessPendingMessagesAction.scheduleProcessPendingMessagesAction(
                    true /* failed */, this);
            return null;
        }

        final int status = response.getInt(BUNDLE_REQUEST_STATUS);
        final int rawStatus = response.getInt(BUNDLE_RAW_TELEPHONY_STATUS);
        final Uri messageUri = response.getParcelable(BUNDLE_MMS_URI);
        final boolean autoDownload = actionParameters.getBoolean(KEY_AUTO_DOWNLOAD);
        final String messageId = actionParameters.getString(KEY_MESSAGE_ID);

        // Do post-processing on downloaded message
        final MessageData message = processResult(status, rawStatus, messageUri);

        final int subId = actionParameters.getInt(KEY_SUB_ID, ParticipantData.DEFAULT_SELF_SUB_ID);
        // If we were trying to auto-download but have failed need to send the deferred response
        final boolean needToSendDeferredResp =
                autoDownload && (status == MmsUtils.MMS_REQUEST_MANUAL_RETRY);
        if (needToSendDeferredResp) {
            final String transactionId = actionParameters.getString(KEY_TRANSACTION_ID);
            final String contentLocation = actionParameters.getString(KEY_CONTENT_LOCATION);
            sendDeferredRespStatus(messageId, transactionId, contentLocation, subId);
        }

        if (autoDownload) {
            final DatabaseWrapper db = DataModel.get().getDatabase();
            MessageData toastMessage = message;
            if (toastMessage == null) {
                // If the downloaded failed (message is null), then we should announce the
                // receiving of the wap push message. Load the wap push message here instead.
                toastMessage = BugleDatabaseOperations.readMessageData(db, messageId);
            }
            if (toastMessage != null) {
                final ParticipantData sender = ParticipantData.getFromId(
                        db, toastMessage.getParticipantId());
                BugleActionToasts.onMessageReceived(
                        toastMessage.getConversationId(), sender, toastMessage);
            }
        } else {
            final boolean success = message != null && status == MmsUtils.MMS_REQUEST_SUCCEEDED;
            BugleActionToasts.onSendMessageOrManualDownloadActionCompleted(
                    // If download failed, use the wap push message's conversation instead
                    success ? message.getConversationId()
                            : actionParameters.getString(KEY_CONVERSATION_ID),
                    success, status, false/*isSms*/, subId, false /*isSend*/);
        }

        final boolean failed = (messageUri == null);
        // Scheduling pending messages. If auto downloading is failed and it needs to send the
        // deferred response, Skip it here and it will be scheduled after sending the response.
        if (!needToSendDeferredResp) {
            ProcessPendingMessagesAction.scheduleProcessPendingMessagesAction(failed, this);
        }
        if (failed) {
            BugleNotifications.update(false, BugleNotifications.UPDATE_ERRORS);
        }

        return message;
    }

    @Override
    protected Object processBackgroundFailure() {
        if (actionParameters.getBoolean(KEY_SEND_DEFERRED_RESP_STATUS)) {
            // We can early-out for these failures. processResult is only designed to handle
            // post-processing of MMS downloads (whether successful or not).
            LogUtil.w(TAG,
                    "ProcessDownloadedMmsAction: Exception while sending deferred NotifyRespInd");
            return null;
        }

        // Background worker threw an exception; require manual retry
        processResult(MmsUtils.MMS_REQUEST_MANUAL_RETRY, MessageData.RAW_TELEPHONY_STATUS_UNDEFINED,
                null /* mmsUri */);

        ProcessPendingMessagesAction.scheduleProcessPendingMessagesAction(true /* failed */,
                this);

        return null;
    }

    private MessageData processResult(final int status, final int rawStatus, final Uri mmsUri) {
        final Context context = Factory.get().getApplicationContext();
        final String messageId = actionParameters.getString(KEY_MESSAGE_ID);
        final Uri mmsNotificationUri = actionParameters.getParcelable(KEY_NOTIFICATION_URI);
        final String notificationConversationId = actionParameters.getString(KEY_CONVERSATION_ID);
        final String notificationParticipantId = actionParameters.getString(KEY_PARTICIPANT_ID);
        final int statusIfFailed = actionParameters.getInt(KEY_STATUS_IF_FAILED);
        final int subId = actionParameters.getInt(KEY_SUB_ID, ParticipantData.DEFAULT_SELF_SUB_ID);

        Assert.notNull(messageId);

        LogUtil.i(TAG, "ProcessDownloadedMmsAction: Processed MMS download of message " + messageId
                + "; status is " + MmsUtils.getRequestStatusDescription(status));

        DatabaseMessages.MmsMessage mms = null;
        if (status == MmsUtils.MMS_REQUEST_SUCCEEDED && mmsUri != null) {
            // Delete the initial M-Notification.ind from telephony
            SqliteWrapper.delete(context, context.getContentResolver(),
                    mmsNotificationUri, null, null);

            // Read the sent MMS from the telephony provider
            mms = MmsUtils.loadMms(mmsUri);
        }

        boolean messageInFocusedConversation = false;
        boolean messageInObservableConversation = false;
        String conversationId = null;
        MessageData message = null;
        final DatabaseWrapper db = DataModel.get().getDatabase();
        db.beginTransaction();
        try {
            if (mms != null) {
                final ParticipantData self = ParticipantData.getSelfParticipant(mms.getSubId());
                final String selfId =
                        BugleDatabaseOperations.getOrCreateParticipantInTransaction(db, self);

                final List<String> recipients = MmsUtils.getRecipientsByThread(mms.mThreadId);
                String from = MmsUtils.getMmsSender(recipients, mms.getUri());
                if (from == null) {
                    LogUtil.w(TAG,
                            "Downloaded an MMS without sender address; using unknown sender.");
                    from = ParticipantData.getUnknownSenderDestination();
                }
                final ParticipantData sender = ParticipantData.getFromRawPhoneBySimLocale(from,
                        subId);
                final String senderParticipantId =
                        BugleDatabaseOperations.getOrCreateParticipantInTransaction(db, sender);
                if (!senderParticipantId.equals(notificationParticipantId)) {
                    LogUtil.e(TAG, "ProcessDownloadedMmsAction: Downloaded MMS message "
                            + messageId + " has different sender (participantId = "
                            + senderParticipantId + ") than notification ("
                            + notificationParticipantId + ")");
                }
                final boolean blockedSender = BugleDatabaseOperations.isBlockedDestination(
                        db, sender.getNormalizedDestination());
                conversationId = BugleDatabaseOperations.getOrCreateConversationFromThreadId(db,
                        mms.mThreadId, blockedSender, subId);

                messageInFocusedConversation =
                        DataModel.get().isFocusedConversation(conversationId);
                messageInObservableConversation =
                        DataModel.get().isNewMessageObservable(conversationId);

                // TODO: Also write these values to the telephony provider
                mms.mRead = messageInFocusedConversation;
                mms.mSeen = messageInObservableConversation;

                // Translate to our format
                message = MmsUtils.createMmsMessage(mms, conversationId, senderParticipantId,
                        selfId, MessageData.BUGLE_STATUS_INCOMING_COMPLETE);
                // Update image sizes.
                message.updateSizesForImageParts();
                // Inform sync that message has been added at local received timestamp
                final SyncManager syncManager = DataModel.get().getSyncManager();
                syncManager.onNewMessageInserted(message.getReceivedTimeStamp());
                final MessageData current = BugleDatabaseOperations.readMessageData(db, messageId);
                if (current == null) {
                    LogUtil.w(TAG, "Message deleted prior to update");
                    BugleDatabaseOperations.insertNewMessageInTransaction(db, message);
                } else {
                    // Overwrite existing notification message
                    message.updateMessageId(messageId);
                    // Write message
                    BugleDatabaseOperations.updateMessageInTransaction(db, message);
                }

                if (!TextUtils.equals(notificationConversationId, conversationId)) {
                    // If this is a group conversation, the message is moved. So the original
                    // 1v1 conversation (as referenced by notificationConversationId) could
                    // be left with no non-draft message. Delete the conversation if that
                    // happens. See the comment for the method below for why we need to do this.
                    if (!BugleDatabaseOperations.deleteConversationIfEmptyInTransaction(
                            db, notificationConversationId)) {
                        BugleDatabaseOperations.maybeRefreshConversationMetadataInTransaction(
                                db, notificationConversationId, messageId,
                                true /*shouldAutoSwitchSelfId*/, blockedSender /*keepArchived*/);
                    }
                }

                BugleDatabaseOperations.refreshConversationMetadataInTransaction(db, conversationId,
                        true /*shouldAutoSwitchSelfId*/, blockedSender /*keepArchived*/);
            } else {
                messageInFocusedConversation =
                        DataModel.get().isFocusedConversation(notificationConversationId);

                // Default to retry status unless status indicates otherwise
                int bugleStatus = statusIfFailed;
                if (status == MmsUtils.MMS_REQUEST_MANUAL_RETRY) {
                    bugleStatus = MessageData.BUGLE_STATUS_INCOMING_DOWNLOAD_FAILED;
                } else if (status == MmsUtils.MMS_REQUEST_NO_RETRY) {
                    bugleStatus = MessageData.BUGLE_STATUS_INCOMING_EXPIRED_OR_NOT_AVAILABLE;
                }
                DownloadMmsAction.updateMessageStatus(mmsNotificationUri, messageId,
                        notificationConversationId, bugleStatus, rawStatus);

                // Log MMS download failed
                final int resultCode = actionParameters.getInt(KEY_RESULT_CODE);
                final int httpStatusCode = actionParameters.getInt(KEY_HTTP_STATUS_CODE);

                // Just in case this was the latest message update the summary data
                BugleDatabaseOperations.refreshConversationMetadataInTransaction(db,
                        notificationConversationId, true /*shouldAutoSwitchSelfId*/,
                        false /*keepArchived*/);
            }

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        if (mmsUri != null) {
            // Update mms table with read status now we know the conversation id
            final ContentValues values = new ContentValues(1);
            values.put(Mms.READ, messageInFocusedConversation);
            SqliteWrapper.update(context, context.getContentResolver(), mmsUri, values,
                    null, null);
        }

        // Show a notification to let the user know a new message has arrived
        BugleNotifications.update(false /*silent*/, conversationId, BugleNotifications.UPDATE_ALL);

        // Messages may have changed in two conversations
        if (conversationId != null) {
            MessagingContentProvider.notifyMessagesChanged(conversationId);
        }
        MessagingContentProvider.notifyMessagesChanged(notificationConversationId);
        MessagingContentProvider.notifyPartsChanged();

        return message;
    }

    private ProcessDownloadedMmsAction(final Parcel in) {
        super(in);
    }

    public static final Parcelable.Creator<ProcessDownloadedMmsAction> CREATOR
            = new Parcelable.Creator<ProcessDownloadedMmsAction>() {
        @Override
        public ProcessDownloadedMmsAction createFromParcel(final Parcel in) {
            return new ProcessDownloadedMmsAction(in);
        }

        @Override
        public ProcessDownloadedMmsAction[] newArray(final int size) {
            return new ProcessDownloadedMmsAction[size];
        }
    };

    @Override
    public void writeToParcel(final Parcel parcel, final int flags) {
        writeActionToParcel(parcel, flags);
    }
}
