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

package com.android.messaging.util;

import android.content.Context;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;

/**
 * ConnectivityUtil listens to the network service state changes.
 *
 * On N and beyond, This class instance can be created via ConnectivityUtil(context, subId), use
 * ConnectivityUtil(context) for others.
 *
 * Note that TelephonyManager has createForSubscriptionId() for a specific subId from N but listen()
 * does not use the subId on the manager, and uses the default subId on PhoneStateListener. From O,
 * the manager uses its' own subId in listen().
 */
public class ConnectivityUtil {
    // Assume not connected until informed differently
    private volatile int mCurrentServiceState = ServiceState.STATE_POWER_OFF;

    private final TelephonyManager mTelephonyManager;

    private ConnectivityListener mListener;

    public interface ConnectivityListener {
        public void onPhoneStateChanged(int serviceState);
    }

    public ConnectivityUtil(final Context context, final int subId) {
        mTelephonyManager =
                ((TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE))
                        .createForSubscriptionId(subId);
    }

    public int getCurrentServiceState() {
        return mCurrentServiceState;
    }

    private final PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        @Override
        public void onServiceStateChanged(final ServiceState serviceState) {
            if (mCurrentServiceState != serviceState.getState()) {
                mCurrentServiceState = serviceState.getState();
                onPhoneStateChanged(mCurrentServiceState);
            }
        }

        @Override
        public void onDataConnectionStateChanged(final int state) {
            mCurrentServiceState = (state == TelephonyManager.DATA_DISCONNECTED) ?
                    ServiceState.STATE_OUT_OF_SERVICE : ServiceState.STATE_IN_SERVICE;
        }
    };

    private void onPhoneStateChanged(final int serviceState) {
        final ConnectivityListener listener = mListener;
        if (listener != null) {
            listener.onPhoneStateChanged(serviceState);
        }
    }

    public void register(final ConnectivityListener listener) {
        Assert.isTrue(mListener == null || mListener == listener);
        if (mListener == null) {
            if (mTelephonyManager != null) {
                mCurrentServiceState = (PhoneUtils.getDefault().isAirplaneModeOn() ?
                        ServiceState.STATE_POWER_OFF : ServiceState.STATE_IN_SERVICE);
                mTelephonyManager.listen(mPhoneStateListener,
                        PhoneStateListener.LISTEN_SERVICE_STATE);
            }
        }
        mListener = listener;
    }

    public void unregister() {
        if (mListener != null) {
            if (mTelephonyManager != null) {
                mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);
                mCurrentServiceState = ServiceState.STATE_POWER_OFF;
            }
        }
        mListener = null;
    }
}
