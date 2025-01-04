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
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.PhoneNumberUtils;
import android.telephony.SmsManager;

import com.android.messaging.Factory;
import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.BugleNotifications;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.MmsFileProvider;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.datamodel.data.MessagePartData;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.mmslib.pdu.SendConf;
import com.android.messaging.sms.MmsConfig;
import com.android.messaging.sms.MmsSender;
import com.android.messaging.sms.MmsUtils;
import com.android.messaging.util.Assert;
import com.android.messaging.util.LogUtil;

import java.io.File;
import java.util.ArrayList;

/**
* Update message status to reflect success or failure
* Can also update the message itself if a "final" message is now available from telephony db
*/
public class ProcessSentMessageAction extends Action {
    private static final String TAG = LogUtil.BUGLE_DATAMODEL_TAG;

    // These are always set
    private static final String KEY_SMS = "is_sms";
    private static final String KEY_SENT_BY_PLATFORM = "sent_by_platform";

    // These are set when we're processing a message sent by the user. They are null for messages
    // sent automatically (e.g. a NotifyRespInd/AcknowledgeInd sent in response to a download).
    private static final String KEY_MESSAGE_ID = "message_id";
    private static final String KEY_MESSAGE_URI = "message_uri";
    private static final String KEY_UPDATED_MESSAGE_URI = "updated_message_uri";
    private static final String KEY_SUB_ID = "sub_id";

    // These are set for messages sent by the platform (L+)
    public static final String KEY_RESULT_CODE = "result_code";
    public static final String KEY_HTTP_STATUS_CODE = "http_status_code";
    private static final String KEY_CONTENT_URI = "content_uri";
    private static final String KEY_RESPONSE = "response";
    private static final String KEY_RESPONSE_IMPORTANT = "response_important";

    // These are set for messages which we fast-failed before sending.
    private static final String KEY_STATUS = "status";
    private static final String KEY_RAW_STATUS = "raw_status";

    // This is called when MMS lib API returns via PendingIntent
    public static void processMmsSent(final int resultCode, final Uri messageUri,
            final Bundle extras) {
        final ProcessSentMessageAction action = new ProcessSentMessageAction();
        final Bundle params = action.actionParameters;
        params.putBoolean(KEY_SMS, false);
        params.putBoolean(KEY_SENT_BY_PLATFORM, true);
        params.putString(KEY_MESSAGE_ID, extras.getString(SendMessageAction.EXTRA_MESSAGE_ID));
        params.putParcelable(KEY_MESSAGE_URI, messageUri);
        params.putParcelable(KEY_UPDATED_MESSAGE_URI,
                extras.getParcelable(SendMessageAction.EXTRA_UPDATED_MESSAGE_URI));
        params.putInt(KEY_SUB_ID,
                extras.getInt(SendMessageAction.KEY_SUB_ID, ParticipantData.DEFAULT_SELF_SUB_ID));
        params.putInt(KEY_RESULT_CODE, resultCode);
        params.putInt(KEY_HTTP_STATUS_CODE, extras.getInt(SmsManager.EXTRA_MMS_HTTP_STATUS, 0));
        params.putParcelable(KEY_CONTENT_URI,
                extras.getParcelable(SendMessageAction.EXTRA_CONTENT_URI));
        params.putByteArray(KEY_RESPONSE, extras.getByteArray(SmsManager.EXTRA_MMS_DATA));
        params.putBoolean(KEY_RESPONSE_IMPORTANT,
                extras.getBoolean(SendMessageAction.EXTRA_RESPONSE_IMPORTANT));
        action.start();
    }

    public static void processMessageSentFastFailed(final String messageId,
            final Uri messageUri, final Uri updatedMessageUri, final int subId, final boolean isSms,
            final int status, final int rawStatus, final int resultCode) {
        final ProcessSentMessageAction action = new ProcessSentMessageAction();
        final Bundle params = action.actionParameters;
        params.putBoolean(KEY_SMS, isSms);
        params.putBoolean(KEY_SENT_BY_PLATFORM, false);
        params.putString(KEY_MESSAGE_ID, messageId);
        params.putParcelable(KEY_MESSAGE_URI, messageUri);
        params.putParcelable(KEY_UPDATED_MESSAGE_URI, updatedMessageUri);
        params.putInt(KEY_SUB_ID, subId);
        params.putInt(KEY_STATUS, status);
        params.putInt(KEY_RAW_STATUS, rawStatus);
        params.putInt(KEY_RESULT_CODE, resultCode);
        action.start();
    }

    private ProcessSentMessageAction() {
        // Callers must use one of the static methods above
    }

    /**
    * Update message status to reflect success or failure
    * Can also update the message itself if a "final" message is now available from telephony db
    */
    @Override
    protected Object executeAction() {
        final Context context = Factory.get().getApplicationContext();
        final String messageId = actionParameters.getString(KEY_MESSAGE_ID);
        final Uri messageUri = actionParameters.getParcelable(KEY_MESSAGE_URI);
        final Uri updatedMessageUri = actionParameters.getParcelable(KEY_UPDATED_MESSAGE_URI);
        final boolean isSms = actionParameters.getBoolean(KEY_SMS);
        final boolean sentByPlatform = actionParameters.getBoolean(KEY_SENT_BY_PLATFORM);

        int status = actionParameters.getInt(KEY_STATUS, MmsUtils.MMS_REQUEST_MANUAL_RETRY);
        int rawStatus = actionParameters.getInt(KEY_RAW_STATUS,
                MmsUtils.PDU_HEADER_VALUE_UNDEFINED);
        final int subId = actionParameters.getInt(KEY_SUB_ID, ParticipantData.DEFAULT_SELF_SUB_ID);

        if (sentByPlatform) {
            // Delete temporary file backing the contentUri passed to MMS service
            final Uri contentUri = actionParameters.getParcelable(KEY_CONTENT_URI);
            Assert.isTrue(contentUri != null);
            final File tempFile = MmsFileProvider.getFile(contentUri);
            long messageSize = 0;
            if (tempFile.exists()) {
                messageSize = tempFile.length();
                tempFile.delete();
                if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
                    LogUtil.v(TAG, "ProcessSentMessageAction: Deleted temp file with outgoing "
                            + "MMS pdu: " + contentUri);
                }
            }

            final int resultCode = actionParameters.getInt(KEY_RESULT_CODE);
            final boolean responseImportant = actionParameters.getBoolean(KEY_RESPONSE_IMPORTANT);
            if (resultCode == Activity.RESULT_OK) {
                if (responseImportant) {
                    // Get the status from the response PDU and update telephony
                    final byte[] response = actionParameters.getByteArray(KEY_RESPONSE);
                    final SendConf sendConf = MmsSender.parseSendConf(response, subId);
                    if (sendConf != null) {
                        final MmsUtils.StatusPlusUri result =
                                MmsUtils.updateSentMmsMessageStatus(context, messageUri, sendConf);
                        status = result.status;
                        rawStatus = result.rawStatus;
                    }
                }
            } else {
                String errorMsg = "ProcessSentMessageAction: Platform returned error resultCode: "
                        + resultCode;
                final int httpStatusCode = actionParameters.getInt(KEY_HTTP_STATUS_CODE);
                if (httpStatusCode != 0) {
                    errorMsg += (", HTTP status code: " + httpStatusCode);
                }
                LogUtil.w(TAG, errorMsg);
                status = MmsSender.getErrorResultStatus(resultCode, httpStatusCode);

                // Check for MMS messages that failed because they exceeded the maximum size,
                // indicated by an I/O error from the platform.
                if (resultCode == SmsManager.MMS_ERROR_IO_ERROR) {
                    if (messageSize > MmsConfig.get(subId).getMaxMessageSize()) {
                        rawStatus = MessageData.RAW_TELEPHONY_STATUS_MESSAGE_TOO_BIG;
                    }
                }
            }
        }
        if (messageId != null) {
            final int resultCode = actionParameters.getInt(KEY_RESULT_CODE);
            final int httpStatusCode = actionParameters.getInt(KEY_HTTP_STATUS_CODE);
            processResult(
                    messageId, updatedMessageUri, status, rawStatus, isSms, this, subId,
                    resultCode, httpStatusCode);
        } else {
            if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
                LogUtil.v(TAG, "ProcessSentMessageAction: No sent message to process (it was "
                        + "probably a notify response for an MMS download)");
            }
        }
        return null;
    }

    static void processResult(final String messageId, Uri updatedMessageUri, int status,
            final int rawStatus, final boolean isSms, final Action processingAction,
            final int subId, final int resultCode, final int httpStatusCode) {
        final DatabaseWrapper db = DataModel.get().getDatabase();
        MessageData message = BugleDatabaseOperations.readMessage(db, messageId);
        final MessageData originalMessage = message;
        if (message == null) {
            LogUtil.w(TAG, "ProcessSentMessageAction: Sent message " + messageId
                    + " missing from local database");
            ProcessPendingMessagesAction.scheduleProcessPendingMessagesAction(
                    true /* failed */, processingAction);
            return;
        }
        final String conversationId = message.getConversationId();
        if (updatedMessageUri != null) {
            // Update message if we have newly written final message in the telephony db
            final MessageData update = MmsUtils.readSendingMmsMessage(updatedMessageUri,
                    conversationId, message.getParticipantId(), message.getSelfId());
            if (update != null) {
                // Set message Id of final message to that of the existing place holder.
                update.updateMessageId(message.getMessageId());
                // Update image sizes.
                update.updateSizesForImageParts();
                // Temp attachments are no longer needed
                for (final MessagePartData part : message.getParts()) {
                    part.destroySync();
                }
                message = update;
                // processResult will rewrite the complete message as part of update
            } else {
                updatedMessageUri = null;
                status = MmsUtils.MMS_REQUEST_MANUAL_RETRY;
                LogUtil.e(TAG, "ProcessSentMessageAction: Unable to read sending message");
            }
        }

        final long timestamp = System.currentTimeMillis();
        boolean failed;
        if (status == MmsUtils.MMS_REQUEST_SUCCEEDED) {
            message.markMessageSent(timestamp);
            failed = false;
        } else if (status == MmsUtils.MMS_REQUEST_AUTO_RETRY
                && message.getInResendWindow(timestamp)) {
            message.markMessageNotSent(timestamp);
            message.setRawTelephonyStatus(rawStatus);
            failed = false;
        } else {
            message.markMessageFailed(timestamp);
            message.setRawTelephonyStatus(rawStatus);
            message.setMessageSeen(false);
            failed = true;
        }

        // We have special handling for when a message to an emergency number fails. In this case,
        // we notify immediately of any failure (even if we auto-retry), and instruct the user to
        // try calling the emergency number instead.
        if (status != MmsUtils.MMS_REQUEST_SUCCEEDED) {
            final ArrayList<String> recipients =
                    BugleDatabaseOperations.getRecipientsForConversation(db, conversationId);
            for (final String recipient : recipients) {
                if (PhoneNumberUtils.isEmergencyNumber(recipient)) {
                    BugleNotifications.notifyEmergencySmsFailed(recipient, conversationId);
                    message.markMessageFailedEmergencyNumber(timestamp);
                    failed = true;
                    break;
                }
            }
        }

        // Update the message status and optionally refresh the message with final parts/values.
        if (SendMessageAction.updateMessageAndStatus(isSms, message, updatedMessageUri, failed)) {
            // We shouldn't show any notifications if we're not allowed to modify Telephony for
            // this message.
            if (failed) {
                BugleNotifications.update(false, BugleNotifications.UPDATE_ERRORS);
            }
            BugleActionToasts.onSendMessageOrManualDownloadActionCompleted(
                    conversationId, !failed, status, isSms, subId, true/*isSend*/);
        }

        LogUtil.i(TAG, "ProcessSentMessageAction: Done sending " + (isSms ? "SMS" : "MMS")
                + " message " + message.getMessageId()
                + " in conversation " + conversationId
                + "; status is " + MmsUtils.getRequestStatusDescription(status));

        // Whether we succeeded or failed we will check and maybe schedule some more work
        ProcessPendingMessagesAction.scheduleProcessPendingMessagesAction(
                status != MmsUtils.MMS_REQUEST_SUCCEEDED, processingAction);
    }

    private ProcessSentMessageAction(final Parcel in) {
        super(in);
    }

    public static final Parcelable.Creator<ProcessSentMessageAction> CREATOR
            = new Parcelable.Creator<ProcessSentMessageAction>() {
        @Override
        public ProcessSentMessageAction createFromParcel(final Parcel in) {
            return new ProcessSentMessageAction(in);
        }

        @Override
        public ProcessSentMessageAction[] newArray(final int size) {
            return new ProcessSentMessageAction[size];
        }
    };

    @Override
    public void writeToParcel(final Parcel parcel, final int flags) {
        writeActionToParcel(parcel, flags);
    }
}
