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

package androidx.appcompat.mms;

import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.telephony.SmsManager;
import android.text.TextUtils;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Request to send an MMS
 */
class SendRequest extends MmsRequest {
    // Max send response PDU size in bytes (exceeding this may cause problem with
    // system intent delivery).
    private static final int MAX_SEND_RESPONSE_SIZE = 1000 * 1024;

    private byte[] mPduData;

    SendRequest(final String locationUrl, final Uri pduUri, final PendingIntent sentIntent) {
        super(locationUrl, pduUri, sentIntent);
    }

    @Override
    protected boolean loadRequest(final Context context, final Bundle mmsConfig) {
        mPduData = readPduFromContentUri(
                context,
                mPduUri,
                mmsConfig.getInt(
                        CarrierConfigValuesLoader.CONFIG_MAX_MESSAGE_SIZE,
                        CarrierConfigValuesLoader.CONFIG_MAX_MESSAGE_SIZE_DEFAULT));
        return (mPduData != null);
    }

    @Override
    protected boolean transferResponse(final Context context, final Intent fillIn,
            final byte[] response) {
        // SendConf pdus are always small and can be included in the intent
        if (response != null && fillIn != null) {
            if (response.length > MAX_SEND_RESPONSE_SIZE) {
                // If the response PDU is too large, it won't be able to fit in
                // the PendingIntent to be transferred via system IPC.
                return false;
            }
            fillIn.putExtra(SmsManager.EXTRA_MMS_DATA, response);
        }
        return true;
    }

    @Override
    protected byte[] doHttp(Context context, MmsNetworkManager netMgr, ApnSettingsLoader.Apn apn,
            Bundle mmsConfig, String userAgent, String uaProfUrl) throws MmsHttpException {
        final MmsHttpClient httpClient = netMgr.getHttpClient();
        return httpClient.execute(getHttpRequestUrl(apn), mPduData, MmsHttpClient.METHOD_POST,
                !TextUtils.isEmpty(apn.getMmsProxy()), apn.getMmsProxy(), apn.getMmsProxyPort(),
                mmsConfig, userAgent, uaProfUrl);
    }

    @Override
    protected String getHttpRequestUrl(final ApnSettingsLoader.Apn apn) {
        return !TextUtils.isEmpty(mLocationUrl) ? mLocationUrl : apn.getMmsc();
    }

    /**
     * Read pdu from content provider uri
     *
     * @param contentUri content provider uri from which to read
     * @param maxSize maximum number of bytes to read
     * @return pdu bytes if succeeded else null
     */
    public byte[] readPduFromContentUri(final Context context, final Uri contentUri,
            final int maxSize) {
        if (contentUri == null) {
            return null;
        }
        final Callable<byte[]> copyPduToArray = new Callable<byte[]>() {
            public byte[] call() {
                ParcelFileDescriptor.AutoCloseInputStream inStream = null;
                try {
                    final ContentResolver cr = context.getContentResolver();
                    final ParcelFileDescriptor pduFd = cr.openFileDescriptor(contentUri, "r");
                    inStream = new ParcelFileDescriptor.AutoCloseInputStream(pduFd);
                    // Request one extra byte to make sure file not bigger than maxSize
                    final byte[] readBuf = new byte[maxSize+1];
                    final int bytesRead = inStream.read(readBuf, 0, maxSize+1);
                    if (bytesRead <= 0) {
                        Log.e(MmsService.TAG, "Reading PDU from sender: empty PDU");
                        return null;
                    }
                    if (bytesRead > maxSize) {
                        Log.e(MmsService.TAG, "Reading PDU from sender: PDU too large");
                        return null;
                    }
                    // Copy and return the exact length of bytes
                    final byte[] result = new byte[bytesRead];
                    System.arraycopy(readBuf, 0, result, 0, bytesRead);
                    return result;
                } catch (IOException e) {
                    Log.e(MmsService.TAG, "Reading PDU from sender: IO exception", e);
                    return null;
                } finally {
                    if (inStream != null) {
                        try {
                            inStream.close();
                        } catch (IOException ex) {
                            // Ignore
                        }
                    }
                }
            }
        };
        final Future<byte[]> pendingResult = mPduTransferExecutor.submit(copyPduToArray);
        try {
            return pendingResult.get(TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // Typically a timeout occurred - cancel task
            pendingResult.cancel(true);
        }
        return null;
    }

    public static final Parcelable.Creator<SendRequest> CREATOR
            = new Parcelable.Creator<SendRequest>() {
        public SendRequest createFromParcel(Parcel in) {
            return new SendRequest(in);
        }

        public SendRequest[] newArray(int size) {
            return new SendRequest[size];
        }
    };

    private SendRequest(Parcel in) {
        super(in);
    }
}
