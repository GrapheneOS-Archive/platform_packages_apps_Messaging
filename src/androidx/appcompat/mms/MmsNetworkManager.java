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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Class manages MMS network connectivity using legacy platform APIs
 * (deprecated since Android L) on pre-L devices (or when forced to
 * be used on L and later)
 */
class MmsNetworkManager {
    // Hidden platform constants
    private static final String FEATURE_ENABLE_MMS = "enableMMS";
    private static final String REASON_VOICE_CALL_ENDED = "2GVoiceCallEnded";
    private static final int APN_ALREADY_ACTIVE     = 0;
    private static final int APN_REQUEST_STARTED    = 1;
    private static final int APN_TYPE_NOT_AVAILABLE = 2;
    private static final int APN_REQUEST_FAILED     = 3;
    private static final int APN_ALREADY_INACTIVE   = 4;
    // A map from platform APN constant to text string
    private static final String[] APN_RESULT_STRING = new String[]{
            "already active",
            "request started",
            "type not available",
            "request failed",
            "already inactive",
            "unknown",
    };

    private static final long NETWORK_ACQUIRE_WAIT_INTERVAL_MS = 15000;
    private static final long DEFAULT_NETWORK_ACQUIRE_TIMEOUT_MS = 180000;
    private static final String MMS_NETWORK_EXTENSION_TIMER = "mms_network_extension_timer";
    private static final long MMS_NETWORK_EXTENSION_TIMER_WAIT_MS = 30000;

    private static volatile long sNetworkAcquireTimeoutMs = DEFAULT_NETWORK_ACQUIRE_TIMEOUT_MS;

    /**
     * Set the network acquire timeout
     *
     * @param timeoutMs timeout in millisecond
     */
    static void setNetworkAcquireTimeout(final long timeoutMs) {
        sNetworkAcquireTimeoutMs = timeoutMs;
    }

    private final Context mContext;
    private final ConnectivityManager mConnectivityManager;

    // If the connectivity intent receiver is registered
    private boolean mReceiverRegistered;
    // Count of requests that are using the MMS network
    private int mUseCount;
    // Count of requests that are waiting for connectivity (i.e. in acquireNetwork wait loop)
    private int mWaitCount;
    // Timer to extend the network connectivity
    private Timer mExtensionTimer;

    private final MmsHttpClient mHttpClient;

    private final IntentFilter mConnectivityIntentFilter;
    private final BroadcastReceiver mConnectivityChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            if (!ConnectivityManager.CONNECTIVITY_ACTION.equals(intent.getAction())) {
                return;
            }
            final int networkType = getConnectivityChangeNetworkType(intent);
            if (networkType != ConnectivityManager.TYPE_MOBILE_MMS) {
                return;
            }
            onMmsConnectivityChange(context, intent);
        }
    };

    MmsNetworkManager(final Context context) {
        mContext = context;
        mConnectivityManager = (ConnectivityManager) mContext.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        mHttpClient = new MmsHttpClient(mContext);
        mConnectivityIntentFilter = new IntentFilter();
        mConnectivityIntentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        mUseCount = 0;
        mWaitCount = 0;
    }

    ConnectivityManager getConnectivityManager() {
        return mConnectivityManager;
    }

    MmsHttpClient getHttpClient() {
        return mHttpClient;
    }

    /**
     * Synchronously acquire MMS network connectivity
     *
     * @throws MmsNetworkException If failed permanently or timed out
     */
    void acquireNetwork() throws MmsNetworkException {
        Log.i(MmsService.TAG, "Acquire MMS network");
        synchronized (this) {
            try {
                mUseCount++;
                mWaitCount++;
                if (mWaitCount == 1) {
                    // Register the receiver for the first waiting request
                    registerConnectivityChangeReceiverLocked();
                }
                long waitMs = sNetworkAcquireTimeoutMs;
                final long beginMs = SystemClock.elapsedRealtime();
                do {
                    if (!isMobileDataEnabled()) {
                        // Fast fail if mobile data is not enabled
                        throw new MmsNetworkException("Mobile data is disabled");
                    }
                    // Always try to extend and check the MMS network connectivity
                    // before we start waiting to make sure we don't miss the change
                    // of MMS connectivity. As one example, some devices fail to send
                    // connectivity change intent. So this would make sure we catch
                    // the state change.
                    if (extendMmsConnectivityLocked()) {
                        // Connected
                        return;
                    }
                    try {
                        wait(Math.min(waitMs, NETWORK_ACQUIRE_WAIT_INTERVAL_MS));
                    } catch (final InterruptedException e) {
                        Log.w(MmsService.TAG, "Unexpected exception", e);
                    }
                    // Calculate the remaining time to wait
                    waitMs = sNetworkAcquireTimeoutMs - (SystemClock.elapsedRealtime() - beginMs);
                } while (waitMs > 0);
                // Last check
                if (extendMmsConnectivityLocked()) {
                    return;
                } else {
                    // Reaching here means timed out.
                    throw new MmsNetworkException("Acquiring MMS network timed out");
                }
            } finally {
                mWaitCount--;
                if (mWaitCount == 0) {
                    // Receiver is used to listen to connectivity change and unblock
                    // the waiting requests. If nobody's waiting on change, there is
                    // no need for the receiver. The auto extension timer will try
                    // to maintain the connectivity periodically.
                    unregisterConnectivityChangeReceiverLocked();
                }
            }
        }
    }

    /**
     * Release MMS network connectivity. This is ref counted. So it only disconnect
     * when the ref count is 0.
     */
    void releaseNetwork() {
        Log.i(MmsService.TAG, "release MMS network");
        synchronized (this) {
            mUseCount--;
            if (mUseCount == 0) {
                stopNetworkExtensionTimerLocked();
                endMmsConnectivity();
            }
        }
    }

    String getApnName() {
        String apnName = null;
        final NetworkInfo mmsNetworkInfo = mConnectivityManager.getNetworkInfo(
                ConnectivityManager.TYPE_MOBILE_MMS);
        if (mmsNetworkInfo != null) {
            apnName = mmsNetworkInfo.getExtraInfo();
        }
        return apnName;
    }

    // Process mobile MMS connectivity change, waking up the waiting request thread
    // in certain conditions:
    // - Successfully connected
    // - Failed permanently
    // - Required another kickoff
    // We don't initiate connection here but just notifyAll so the waiting request
    // would wake up and retry connection before next wait.
    private void onMmsConnectivityChange(final Context context, final Intent intent) {
        if (mUseCount < 1) {
            return;
        }
        final NetworkInfo mmsNetworkInfo =
                mConnectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE_MMS);
        // Check availability of the mobile network.
        if (mmsNetworkInfo != null) {
            if (REASON_VOICE_CALL_ENDED.equals(mmsNetworkInfo.getReason())) {
                // This is a very specific fix to handle the case where the phone receives an
                // incoming call during the time we're trying to setup the mms connection.
                // When the call ends, restart the process of mms connectivity.
                // Once the waiting request is unblocked, before the next wait, we would start
                // MMS network again.
                unblockWait();
            } else {
                final NetworkInfo.State state = mmsNetworkInfo.getState();
                if (state == NetworkInfo.State.CONNECTED ||
                        (state == NetworkInfo.State.DISCONNECTED && !isMobileDataEnabled())) {
                    // Unblock the waiting request when we either connected
                    // OR
                    // disconnected due to mobile data disabled therefore needs to fast fail
                    // (on some devices if mobile data disabled and starting MMS would cause
                    // an immediate state change to disconnected, so causing a tight loop of
                    // trying and failing)
                    // Once the waiting request is unblocked, before the next wait, we would
                    // check mobile data and start MMS network again. So we should catch
                    // both the success and the fast failure.
                    unblockWait();
                }
            }
        }
    }

    private void unblockWait() {
        synchronized (this) {
            notifyAll();
        }
    }

    private void startNetworkExtensionTimerLocked() {
        if (mExtensionTimer == null) {
            mExtensionTimer = new Timer(MMS_NETWORK_EXTENSION_TIMER, true/*daemon*/);
            mExtensionTimer.schedule(
                    new TimerTask() {
                        @Override
                        public void run() {
                            synchronized (this) {
                                if (mUseCount > 0) {
                                    try {
                                        // Try extending the connectivity
                                        extendMmsConnectivityLocked();
                                    } catch (final MmsNetworkException e) {
                                        // Ignore the exception
                                    }
                                }
                            }
                        }
                    },
                    MMS_NETWORK_EXTENSION_TIMER_WAIT_MS);
        }
    }

    private void stopNetworkExtensionTimerLocked() {
        if (mExtensionTimer != null) {
            mExtensionTimer.cancel();
            mExtensionTimer = null;
        }
    }

    private boolean extendMmsConnectivityLocked() throws MmsNetworkException {
        final int result = startMmsConnectivity();
        if (result == APN_ALREADY_ACTIVE) {
            // Already active
            startNetworkExtensionTimerLocked();
            return true;
        } else if (result != APN_REQUEST_STARTED) {
            stopNetworkExtensionTimerLocked();
            throw new MmsNetworkException("Cannot acquire MMS network: " +
                    result + " - " + getMmsConnectivityResultString(result));
        }
        return false;
    }

    private int startMmsConnectivity() {
        Log.i(MmsService.TAG, "Start MMS connectivity");
        try {
            final Method method = mConnectivityManager.getClass().getMethod(
                "startUsingNetworkFeature", Integer.TYPE, String.class);
            if (method != null) {
                return (Integer) method.invoke(
                    mConnectivityManager, ConnectivityManager.TYPE_MOBILE, FEATURE_ENABLE_MMS);
            }
        } catch (final Exception e) {
            Log.w(MmsService.TAG, "ConnectivityManager.startUsingNetworkFeature failed " + e);
        }
        return APN_REQUEST_FAILED;
    }

    private void endMmsConnectivity() {
        Log.i(MmsService.TAG, "End MMS connectivity");
        try {
            final Method method = mConnectivityManager.getClass().getMethod(
                "stopUsingNetworkFeature", Integer.TYPE, String.class);
            if (method != null) {
                method.invoke(
                        mConnectivityManager, ConnectivityManager.TYPE_MOBILE, FEATURE_ENABLE_MMS);
            }
        } catch (final Exception e) {
            Log.w(MmsService.TAG, "ConnectivityManager.stopUsingNetworkFeature failed " + e);
        }
    }

    private void registerConnectivityChangeReceiverLocked() {
        if (!mReceiverRegistered) {
            mContext.registerReceiver(mConnectivityChangeReceiver, mConnectivityIntentFilter,
                    Context.RECEIVER_EXPORTED/*UNAUDITED*/);
            mReceiverRegistered = true;
        }
    }

    private void unregisterConnectivityChangeReceiverLocked() {
        if (mReceiverRegistered) {
            mContext.unregisterReceiver(mConnectivityChangeReceiver);
            mReceiverRegistered = false;
        }
    }

    /**
     * The absence of a connection type.
     */
    private static final int TYPE_NONE = -1;

    /**
     * Get the network type of the connectivity change
     *
     * @param intent the broadcast intent of connectivity change
     * @return The change's network type
     */
    private static int getConnectivityChangeNetworkType(final Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            return intent.getIntExtra(ConnectivityManager.EXTRA_NETWORK_TYPE, TYPE_NONE);
        } else {
            final NetworkInfo info = intent.getParcelableExtra(
                    ConnectivityManager.EXTRA_NETWORK_INFO);
            if (info != null) {
                return info.getType();
            }
        }
        return TYPE_NONE;
    }

    private static String getMmsConnectivityResultString(int result) {
        if (result < 0 || result >= APN_RESULT_STRING.length) {
            result = APN_RESULT_STRING.length - 1;
        }
        return APN_RESULT_STRING[result];
    }

    private boolean isMobileDataEnabled() {
        try {
            final Class cmClass = mConnectivityManager.getClass();
            final Method method = cmClass.getDeclaredMethod("getMobileDataEnabled");
            method.setAccessible(true); // Make the method callable
            // get the setting for "mobile data"
            return (Boolean) method.invoke(mConnectivityManager);
        } catch (final Exception e) {
            Log.w(MmsService.TAG, "TelephonyManager.getMobileDataEnabled failed", e);
        }
        return false;
    }
}
