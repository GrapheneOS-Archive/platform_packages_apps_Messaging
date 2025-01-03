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

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import androidx.appcompat.mms.pdu.GenericPdu;
import androidx.appcompat.mms.pdu.PduHeaders;
import androidx.appcompat.mms.pdu.PduParser;
import androidx.appcompat.mms.pdu.SendConf;
import android.telephony.SmsManager;
import android.text.TextUtils;
import android.util.Log;

import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MMS request base class. This handles the execution of any MMS request.
 */
abstract class MmsRequest implements Parcelable {
    /**
     * Prepare to make the HTTP request - will download message for sending
     *
     * @param context the Context
     * @param mmsConfig carrier config values to use
     * @return true if loading request PDU from calling app succeeds, false otherwise
     */
    protected abstract boolean loadRequest(Context context, Bundle mmsConfig);

    /**
     * Transfer the received response to the caller
     *
     * @param context the Context
     * @param fillIn the content of pending intent to be returned
     * @param response the pdu to transfer
     * @return true if transferring response PDU to calling app succeeds, false otherwise
     */
    protected abstract boolean transferResponse(Context context, Intent fillIn, byte[] response);

    /**
     * Making the HTTP request to MMSC
     *
     * @param context The context
     * @param netMgr The current {@link MmsNetworkManager}
     * @param apn The APN
     * @param mmsConfig The carrier configuration values to use
     * @param userAgent The User-Agent header value
     * @param uaProfUrl The UA Prof URL header value
     * @return The HTTP response data
     * @throws MmsHttpException If any network error happens
     */
    protected abstract byte[] doHttp(Context context, MmsNetworkManager netMgr,
            ApnSettingsLoader.Apn apn, Bundle mmsConfig, String userAgent, String uaProfUrl)
            throws MmsHttpException;

    /**
     * Get the HTTP request URL for this MMS request
     *
     * @param apn The APN to use
     * @return The HTTP request URL in text
     */
    protected abstract String getHttpRequestUrl(ApnSettingsLoader.Apn apn);

    // Maximum time to spend waiting to read data from a content provider before failing with error.
    protected static final int TASK_TIMEOUT_MS = 30 * 1000;

    protected final String mLocationUrl;
    protected final Uri mPduUri;
    protected final PendingIntent mPendingIntent;
    // Thread pool for transferring PDU with MMS apps
    protected final ExecutorService mPduTransferExecutor = Executors.newCachedThreadPool();

    // Whether this request should acquire wake lock
    private boolean mUseWakeLock;

    protected MmsRequest(final String locationUrl, final Uri pduUri,
            final PendingIntent pendingIntent) {
        mLocationUrl = locationUrl;
        mPduUri = pduUri;
        mPendingIntent = pendingIntent;
        mUseWakeLock = true;
    }

    void setUseWakeLock(final boolean useWakeLock) {
        mUseWakeLock = useWakeLock;
    }

    boolean getUseWakeLock() {
        return mUseWakeLock;
    }

    /**
     * Run the MMS request.
     *
     * @param context the context to use
     * @param networkManager the MmsNetworkManager to use to setup MMS network
     * @param apnSettingsLoader the APN loader
     * @param carrierConfigValuesLoader the carrier config loader
     * @param userAgentInfoLoader the user agent info loader
     */
    void execute(final Context context, final MmsNetworkManager networkManager,
            final ApnSettingsLoader apnSettingsLoader,
            final CarrierConfigValuesLoader carrierConfigValuesLoader,
            final UserAgentInfoLoader userAgentInfoLoader) {
        Log.i(MmsService.TAG, "Execute " + this.getClass().getSimpleName());
        int result = SmsManager.MMS_ERROR_UNSPECIFIED;
        int httpStatusCode = 0;
        byte[] response = null;
        final Bundle mmsConfig = carrierConfigValuesLoader.get(MmsManager.DEFAULT_SUB_ID);
        if (mmsConfig == null) {
            Log.e(MmsService.TAG, "Failed to load carrier configuration values");
            result = SmsManager.MMS_ERROR_CONFIGURATION_ERROR;
        } else if (!loadRequest(context, mmsConfig)) {
            Log.e(MmsService.TAG, "Failed to load PDU");
            result = SmsManager.MMS_ERROR_IO_ERROR;
        } else {
            // Everything's OK. Now execute the request.
            try {
                // Acquire the MMS network
                networkManager.acquireNetwork();
                // Load the potential APNs. In most cases there should be only one APN available.
                // On some devices on which we can't obtain APN from system, we look up our own
                // APN list. Since we don't have exact information, we may get a list of potential
                // APNs to try. Whenever we found a successful APN, we signal it and return.
                final String apnName = networkManager.getApnName();
                final List<ApnSettingsLoader.Apn> apns = apnSettingsLoader.get(apnName);
                if (apns.size() < 1) {
                    throw new ApnException("No valid APN");
                } else {
                    Log.d(MmsService.TAG, "Trying " + apns.size() + " APNs");
                }
                final String userAgent = userAgentInfoLoader.getUserAgent();
                final String uaProfUrl = userAgentInfoLoader.getUAProfUrl();
                MmsHttpException lastException = null;
                for (ApnSettingsLoader.Apn apn : apns) {
                    Log.i(MmsService.TAG, "Using APN ["
                            + "MMSC=" + apn.getMmsc() + ", "
                            + "PROXY=" + apn.getMmsProxy() + ", "
                            + "PORT=" + apn.getMmsProxyPort() + "]");
                    try {
                        final String url = getHttpRequestUrl(apn);
                        // Request a global route for the host to connect
                        requestRoute(networkManager.getConnectivityManager(), apn, url);
                        // Perform the HTTP request
                        response = doHttp(
                                context, networkManager, apn, mmsConfig, userAgent, uaProfUrl);
                        // Additional check of whether this is a success
                        if (isWrongApnResponse(response, mmsConfig)) {
                            throw new MmsHttpException(0/*statusCode*/, "Invalid sending address");
                        }
                        // Notify APN loader this is a valid APN
                        apn.setSuccess();
                        result = Activity.RESULT_OK;
                        break;
                    } catch (MmsHttpException e) {
                        Log.w(MmsService.TAG, "HTTP or network failure", e);
                        lastException = e;
                    }
                }
                if (lastException != null) {
                    throw lastException;
                }
            } catch (ApnException e) {
                Log.e(MmsService.TAG, "MmsRequest: APN failure", e);
                result = SmsManager.MMS_ERROR_INVALID_APN;
            } catch (MmsNetworkException e) {
                Log.e(MmsService.TAG, "MmsRequest: MMS network acquiring failure", e);
                result = SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS;
            } catch (MmsHttpException e) {
                Log.e(MmsService.TAG, "MmsRequest: HTTP or network I/O failure", e);
                result = SmsManager.MMS_ERROR_HTTP_FAILURE;
                httpStatusCode = e.getStatusCode();
            } catch (Exception e) {
                Log.e(MmsService.TAG, "MmsRequest: unexpected failure", e);
                result = SmsManager.MMS_ERROR_UNSPECIFIED;
            } finally {
                // Release MMS network
                networkManager.releaseNetwork();
            }
        }
        // Process result and send back via PendingIntent
        returnResult(context, result, response, httpStatusCode);
    }

    /**
     * Check if the response indicates a failure when we send to wrong APN.
     * Sometimes even if you send to the wrong APN, a response in valid PDU format can still
     * be sent back but with an error status. Check one specific case here.
     *
     * TODO: maybe there are other possibilities.
     *
     * @param response the response data
     * @param mmsConfig the carrier configuration values to use
     * @return false if we find an invalid response case, otherwise true
     */
    static boolean isWrongApnResponse(final byte[] response, final Bundle mmsConfig) {
        if (response != null && response.length > 0) {
            try {
                final GenericPdu pdu = new PduParser(
                        response,
                        mmsConfig.getBoolean(
                                CarrierConfigValuesLoader
                                        .CONFIG_SUPPORT_MMS_CONTENT_DISPOSITION,
                                CarrierConfigValuesLoader
                                        .CONFIG_SUPPORT_MMS_CONTENT_DISPOSITION_DEFAULT))
                        .parse();
                if (pdu != null && pdu instanceof SendConf) {
                    final SendConf sendConf = (SendConf) pdu;
                    final int responseStatus = sendConf.getResponseStatus();
                    return responseStatus ==
                            PduHeaders.RESPONSE_STATUS_ERROR_PERMANENT_SENDING_ADDRESS_UNRESOLVED ||
                            responseStatus ==
                                    PduHeaders.RESPONSE_STATUS_ERROR_SENDING_ADDRESS_UNRESOLVED;
                }
            } catch (RuntimeException e) {
                Log.w(MmsService.TAG, "Parsing response failed", e);
            }
        }
        return false;
    }

    /**
     * Return the result back via pending intent
     *
     * @param context The context
     * @param result The result code of execution
     * @param response The response body
     * @param httpStatusCode The optional http status code in case of http failure
     */
    void returnResult(final Context context, int result, final byte[] response,
            final int httpStatusCode) {
        if (mPendingIntent == null) {
            // Result not needed
            return;
        }
        // Extra information to send back with the pending intent
        final Intent fillIn = new Intent();
        if (response != null) {
            if (!transferResponse(context, fillIn, response)) {
                // Failed to send PDU data back to caller
                result = SmsManager.MMS_ERROR_IO_ERROR;
            }
        }
        if (result == SmsManager.MMS_ERROR_HTTP_FAILURE && httpStatusCode != 0) {
            // For HTTP failure, fill in the status code for more information
            fillIn.putExtra(SmsManager.EXTRA_MMS_HTTP_STATUS, httpStatusCode);
        }
        try {
            mPendingIntent.send(context, result, fillIn);
        } catch (PendingIntent.CanceledException e) {
            Log.e(MmsService.TAG, "Sending pending intent canceled", e);
        }
    }

    /**
     * Request the route to the APN (either proxy host or the MMSC host)
     *
     * @param connectivityManager the ConnectivityManager to use
     * @param apn the current APN
     * @param url the URL to connect to
     * @throws MmsHttpException for unknown host or route failure
     */
    private static void requestRoute(final ConnectivityManager connectivityManager,
            final ApnSettingsLoader.Apn apn, final String url) throws MmsHttpException {
        String host = apn.getMmsProxy();
        if (TextUtils.isEmpty(host)) {
            final Uri uri = Uri.parse(url);
            host = uri.getHost();
        }
        boolean success = false;
        // Request route to all resolved host addresses
        try {
            for (final InetAddress addr : InetAddress.getAllByName(host)) {
                final boolean requested = requestRouteToHostAddress(connectivityManager, addr);
                if (requested) {
                    success = true;
                    Log.i(MmsService.TAG, "Requested route to " + addr);
                } else {
                    Log.i(MmsService.TAG, "Could not requested route to " + addr);
                }
            }
            if (!success) {
                throw new MmsHttpException(0/*statusCode*/, "No route requested");
            }
        } catch (UnknownHostException e) {
            Log.w(MmsService.TAG, "Unknown host " + host);
            throw new MmsHttpException(0/*statusCode*/, "Unknown host");
        }
    }

    private static final Integer TYPE_MOBILE_MMS =
            Integer.valueOf(ConnectivityManager.TYPE_MOBILE_MMS);
    /**
     * Wrapper for platform API requestRouteToHostAddress
     *
     * We first try the hidden but correct method on ConnectivityManager. If we can't, use
     * the old but buggy one
     *
     * @param connMgr the ConnectivityManager instance
     * @param inetAddr the InetAddress to request
     * @return true if route is successfully setup, false otherwise
     */
    private static boolean requestRouteToHostAddress(final ConnectivityManager connMgr,
            final InetAddress inetAddr) {
        // First try the good method using reflection
        try {
            final Method method = connMgr.getClass().getMethod("requestRouteToHostAddress",
                    Integer.TYPE, InetAddress.class);
            if (method != null) {
                return (Boolean) method.invoke(connMgr, TYPE_MOBILE_MMS, inetAddr);
            }
        } catch (Exception e) {
            Log.w(MmsService.TAG, "ConnectivityManager.requestRouteToHostAddress failed " + e);
        }
        // If we fail, try the old but buggy one
        if (inetAddr instanceof Inet4Address) {
            try {
                final Method method = connMgr.getClass().getMethod("requestRouteToHost",
                        Integer.TYPE, Integer.TYPE);
                if (method != null) {
                    return (Boolean) method.invoke(connMgr, TYPE_MOBILE_MMS,
                        inetAddressToInt(inetAddr));
                }
            } catch (Exception e) {
                Log.w(MmsService.TAG, "ConnectivityManager.requestRouteToHost failed " + e);
            }
        }
        return false;
    }

    /**
     * Convert a IPv4 address from an InetAddress to an integer
     *
     * @param inetAddr is an InetAddress corresponding to the IPv4 address
     * @return the IP address as an integer in network byte order
     */
    private static int inetAddressToInt(final InetAddress inetAddr)
            throws IllegalArgumentException {
        final byte [] addr = inetAddr.getAddress();
        return ((addr[3] & 0xff) << 24) | ((addr[2] & 0xff) << 16) |
                ((addr[1] & 0xff) << 8) | (addr[0] & 0xff);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeByte((byte) (mUseWakeLock ? 1 : 0));
        parcel.writeString(mLocationUrl);
        parcel.writeParcelable(mPduUri, 0);
        parcel.writeParcelable(mPendingIntent, 0);
    }

    protected MmsRequest(final Parcel in) {
        final ClassLoader classLoader = MmsRequest.class.getClassLoader();
        mUseWakeLock = in.readByte() != 0;
        mLocationUrl = in.readString();
        mPduUri = in.readParcelable(classLoader);
        mPendingIntent = in.readParcelable(classLoader);
    }
}
