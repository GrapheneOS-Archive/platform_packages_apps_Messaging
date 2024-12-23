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

package com.android.messaging.sms;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import androidx.appcompat.mms.MmsManager;
import android.telephony.SmsManager;

import com.android.messaging.datamodel.MmsFileProvider;
import com.android.messaging.datamodel.action.SendMessageAction;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.mmslib.InvalidHeaderValueException;
import com.android.messaging.mmslib.pdu.AcknowledgeInd;
import com.android.messaging.mmslib.pdu.EncodedStringValue;
import com.android.messaging.mmslib.pdu.GenericPdu;
import com.android.messaging.mmslib.pdu.NotifyRespInd;
import com.android.messaging.mmslib.pdu.PduComposer;
import com.android.messaging.mmslib.pdu.PduHeaders;
import com.android.messaging.mmslib.pdu.PduParser;
import com.android.messaging.mmslib.pdu.RetrieveConf;
import com.android.messaging.mmslib.pdu.SendConf;
import com.android.messaging.mmslib.pdu.SendReq;
import com.android.messaging.receiver.SendStatusReceiver;
import com.android.messaging.util.Assert;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.PhoneUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Class that sends chat message via MMS.
 *
 * The interface emulates a blocking send similar to making an HTTP request.
 */
public class MmsSender {
    private static final String TAG = LogUtil.BUGLE_TAG;

    /**
     * Send an MMS message.
     *
     * @param context Context
     * @param messageUri The unique URI of the message for identifying it during sending
     * @param sendReq The SendReq PDU of the message
     * @throws MmsFailureException
     */
    public static void sendMms(final Context context, final int subId, final Uri messageUri,
            final SendReq sendReq, final Bundle sentIntentExras) throws MmsFailureException {
        sendMms(context,
                subId,
                messageUri,
                null /* locationUrl */,
                sendReq,
                true /* responseImportant */,
                sentIntentExras);
    }

    /**
     * Send NotifyRespInd (response to mms auto download).
     *
     * @param context Context
     * @param subId subscription to use to send the response
     * @param transactionId The transaction id of the MMS message
     * @param contentLocation The url of the MMS message
     * @param status The status to send with the NotifyRespInd
     * @throws MmsFailureException
     * @throws InvalidHeaderValueException
     */
    public static void sendNotifyResponseForMmsDownload(final Context context, final int subId,
            final byte[] transactionId, final String contentLocation, final int status)
            throws MmsFailureException, InvalidHeaderValueException {
        // Create the M-NotifyResp.ind
        final NotifyRespInd notifyRespInd = new NotifyRespInd(
                PduHeaders.CURRENT_MMS_VERSION, transactionId, status);
        final Uri messageUri = Uri.parse(contentLocation);
        // Pack M-NotifyResp.ind and send it
        sendMms(context,
                subId,
                messageUri,
                MmsConfig.get(subId).getNotifyWapMMSC() ? contentLocation : null,
                notifyRespInd,
                false /* responseImportant */,
                null /* sentIntentExtras */);
    }

    /**
     * Send AcknowledgeInd (response to mms manual download). Ignore failures.
     *
     * @param context Context
     * @param subId The SIM's subId we are currently using
     * @param transactionId The transaction id of the MMS message
     * @param contentLocation The url of the MMS message
     * @throws MmsFailureException
     * @throws InvalidHeaderValueException
     */
    public static void sendAcknowledgeForMmsDownload(final Context context, final int subId,
            final byte[] transactionId, final String contentLocation)
            throws MmsFailureException, InvalidHeaderValueException {
        final String selfNumber = PhoneUtils.get(subId).getCanonicalForSelf(true/*allowOverride*/);
        // Create the M-Acknowledge.ind
        final AcknowledgeInd acknowledgeInd = new AcknowledgeInd(PduHeaders.CURRENT_MMS_VERSION,
                transactionId);
        acknowledgeInd.setFrom(new EncodedStringValue(selfNumber));
        final Uri messageUri = Uri.parse(contentLocation);
        // Sending
        sendMms(context,
                subId,
                messageUri,
                MmsConfig.get(subId).getNotifyWapMMSC() ? contentLocation : null,
                acknowledgeInd,
                false /*responseImportant*/,
                null /* sentIntentExtras */);
    }

    /**
     * Send a generic PDU.
     *
     * @param context Context
     * @param messageUri The unique URI of the message for identifying it during sending
     * @param locationUrl The optional URL to send to
     * @param pdu The PDU to send
     * @param responseImportant If the sending response is important. Responses to the
     * Sending of AcknowledgeInd and NotifyRespInd are not important.
     * @throws MmsFailureException
     */
    private static void sendMms(final Context context, final int subId, final Uri messageUri,
            final String locationUrl, final GenericPdu pdu, final boolean responseImportant,
            final Bundle sentIntentExtras) throws MmsFailureException {
        // Write PDU to temporary file to send to platform
        final Uri contentUri = writePduToTempFile(context, pdu, subId);

        // Construct PendingIntent that will notify us when message sending is complete
        final Intent sentIntent = new Intent(SendStatusReceiver.MMS_SENT_ACTION,
                messageUri,
                context,
                SendStatusReceiver.class);
        sentIntent.putExtra(SendMessageAction.EXTRA_CONTENT_URI, contentUri);
        sentIntent.putExtra(SendMessageAction.EXTRA_RESPONSE_IMPORTANT, responseImportant);
        if (sentIntentExtras != null) {
            sentIntent.putExtras(sentIntentExtras);
        }
        final PendingIntent sentPendingIntent = PendingIntent.getBroadcast(
                context,
                0 /*request code*/,
                sentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Send the message
        MmsManager.sendMultimediaMessage(subId, context, contentUri, locationUrl,
                sentPendingIntent);
    }

    private static Uri writePduToTempFile(final Context context, final GenericPdu pdu, int subId)
            throws MmsFailureException {
        final Uri contentUri = MmsFileProvider.buildRawMmsUri();
        final File tempFile = MmsFileProvider.getFile(contentUri);
        FileOutputStream writer = null;
        try {
            // Ensure rawmms directory exists
            tempFile.getParentFile().mkdirs();
            writer = new FileOutputStream(tempFile);
            final byte[] pduBytes = new PduComposer(context, pdu).make();
            if (pduBytes == null) {
                throw new MmsFailureException(
                        MmsUtils.MMS_REQUEST_NO_RETRY, "Failed to compose PDU");
            }
            if (pduBytes.length > MmsConfig.get(subId).getMaxMessageSize()) {
                throw new MmsFailureException(
                        MmsUtils.MMS_REQUEST_NO_RETRY,
                        MessageData.RAW_TELEPHONY_STATUS_MESSAGE_TOO_BIG);
            }
            writer.write(pduBytes);
        } catch (final IOException e) {
            if (tempFile != null) {
                tempFile.delete();
            }
            LogUtil.e(TAG, "Cannot create temporary file " + tempFile.getAbsolutePath(), e);
            throw new MmsFailureException(
                    MmsUtils.MMS_REQUEST_AUTO_RETRY, "Cannot create raw mms file");
        } catch (final OutOfMemoryError e) {
            if (tempFile != null) {
                tempFile.delete();
            }
            LogUtil.e(TAG, "Out of memory in composing PDU", e);
            throw new MmsFailureException(
                    MmsUtils.MMS_REQUEST_MANUAL_RETRY,
                    MessageData.RAW_TELEPHONY_STATUS_MESSAGE_TOO_BIG);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (final IOException e) {
                    // no action we can take here
                }
            }
        }
        return contentUri;
    }

    public static SendConf parseSendConf(byte[] response, int subId) {
        if (response != null) {
            final GenericPdu respPdu = new PduParser(
                    response, MmsConfig.get(subId).getSupportMmsContentDisposition()).parse();
            if (respPdu != null) {
                if (respPdu instanceof SendConf) {
                    return (SendConf) respPdu;
                } else {
                    LogUtil.e(TAG, "MmsSender: send response not SendConf");
                }
            } else {
                // Invalid PDU
                LogUtil.e(TAG, "MmsSender: send invalid response");
            }
        }
        // Empty or invalid response
        return null;
    }

    /**
     * Download an MMS message.
     *
     * @param context Context
     * @param contentLocation The url of the MMS message
     * @throws MmsFailureException
     * @throws InvalidHeaderValueException
     */
    public static void downloadMms(final Context context, final int subId,
            final String contentLocation, Bundle extras) throws MmsFailureException,
            InvalidHeaderValueException {
        final Uri requestUri = Uri.parse(contentLocation);
        final Uri contentUri = MmsFileProvider.buildRawMmsUri();

        final Intent downloadedIntent = new Intent(SendStatusReceiver.MMS_DOWNLOADED_ACTION,
                requestUri,
                context,
                SendStatusReceiver.class);
        downloadedIntent.putExtra(SendMessageAction.EXTRA_CONTENT_URI, contentUri);
        if (extras != null) {
            downloadedIntent.putExtras(extras);
        }
        final PendingIntent downloadedPendingIntent = PendingIntent.getBroadcast(
                context,
                0 /*request code*/,
                downloadedIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        MmsManager.downloadMultimediaMessage(subId, context, contentLocation, contentUri,
                downloadedPendingIntent);
    }

    public static RetrieveConf parseRetrieveConf(byte[] data, int subId) {
        if (data != null) {
            final GenericPdu pdu = new PduParser(
                    data, MmsConfig.get(subId).getSupportMmsContentDisposition()).parse();
            if (pdu != null) {
                if (pdu instanceof RetrieveConf) {
                    return (RetrieveConf) pdu;
                } else {
                    LogUtil.e(TAG, "MmsSender: downloaded pdu not RetrieveConf: "
                            + pdu.getClass().getName());
                }
            } else {
                LogUtil.e(TAG, "MmsSender: downloaded pdu could not be parsed (invalid)");
            }
        }
        LogUtil.e(TAG, "MmsSender: downloaded pdu is empty");
        return null;
    }

    // Process different result code from platform MMS service
    public static int getErrorResultStatus(int resultCode, int httpStatusCode) {
        Assert.isFalse(resultCode == Activity.RESULT_OK);
        switch (resultCode) {
            case SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS:
            case SmsManager.MMS_ERROR_IO_ERROR:
                return MmsUtils.MMS_REQUEST_AUTO_RETRY;
            case SmsManager.MMS_ERROR_INVALID_APN:
            case SmsManager.MMS_ERROR_CONFIGURATION_ERROR:
            case SmsManager.MMS_ERROR_NO_DATA_NETWORK:
            case SmsManager.MMS_ERROR_UNSPECIFIED:
                return MmsUtils.MMS_REQUEST_MANUAL_RETRY;
            case SmsManager.MMS_ERROR_HTTP_FAILURE:
                if (httpStatusCode == 404) {
                    return MmsUtils.MMS_REQUEST_NO_RETRY;
                } else {
                    return MmsUtils.MMS_REQUEST_AUTO_RETRY;
                }
            default:
                return MmsUtils.MMS_REQUEST_MANUAL_RETRY;
        }
    }
}
