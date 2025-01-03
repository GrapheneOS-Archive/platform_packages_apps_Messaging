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
import android.text.TextUtils;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Request to download an MMS
 */
class DownloadRequest extends MmsRequest {

    DownloadRequest(final String locationUrl, final Uri pduUri,
            final PendingIntent sentIntent) {
        super(locationUrl, pduUri, sentIntent);
    }

    @Override
    protected boolean loadRequest(final Context context, final Bundle mmsConfig) {
        // No need to load PDU from app. Always true.
        return true;
    }

    @Override
    protected boolean transferResponse(Context context, Intent fillIn, byte[] response) {
        return writePduToContentUri(context, mPduUri, response);
    }

    @Override
    protected byte[] doHttp(Context context, MmsNetworkManager netMgr, ApnSettingsLoader.Apn apn,
            Bundle mmsConfig, String userAgent, String uaProfUrl) throws MmsHttpException {
        final MmsHttpClient httpClient = netMgr.getHttpClient();
        return httpClient.execute(getHttpRequestUrl(apn), null/*pdu*/, MmsHttpClient.METHOD_GET,
                !TextUtils.isEmpty(apn.getMmsProxy()), apn.getMmsProxy(), apn.getMmsProxyPort(),
                mmsConfig, userAgent, uaProfUrl);

    }

    @Override
    protected String getHttpRequestUrl(final ApnSettingsLoader.Apn apn) {
        return mLocationUrl;
    }

    /**
     * Write pdu bytes to content provider uri
     *
     * @param contentUri content provider uri to which bytes should be written
     * @param pdu Bytes to write
     * @return true if all bytes successfully written else false
     */
    public boolean writePduToContentUri(final Context context, final Uri contentUri,
            final byte[] pdu) {
        if (contentUri == null || pdu == null) {
            return false;
        }
        final Callable<Boolean> copyDownloadedPduToOutput = new Callable<Boolean>() {
            public Boolean call() {
                ParcelFileDescriptor.AutoCloseOutputStream outStream = null;
                try {
                    final ContentResolver cr = context.getContentResolver();
                    final ParcelFileDescriptor pduFd = cr.openFileDescriptor(contentUri, "w");
                    outStream = new ParcelFileDescriptor.AutoCloseOutputStream(pduFd);
                    outStream.write(pdu);
                    return true;
                } catch (IOException e) {
                    Log.e(MmsService.TAG, "Writing PDU to downloader: IO exception", e);
                    return false;
                } finally {
                    if (outStream != null) {
                        try {
                            outStream.close();
                        } catch (IOException ex) {
                            // Ignore
                        }
                    }
                }
            }
        };
        final Future<Boolean> pendingResult =
                mPduTransferExecutor.submit(copyDownloadedPduToOutput);
        try {
            return pendingResult.get(TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // Typically a timeout occurred - cancel task
            pendingResult.cancel(true);
        }
        return false;
    }

    public static final Parcelable.Creator<DownloadRequest> CREATOR
            = new Parcelable.Creator<DownloadRequest>() {
        public DownloadRequest createFromParcel(Parcel in) {
            return new DownloadRequest(in);
        }

        public DownloadRequest[] newArray(int size) {
            return new DownloadRequest[size];
        }
    };

    private DownloadRequest(Parcel in) {
        super(in);
    }
}
