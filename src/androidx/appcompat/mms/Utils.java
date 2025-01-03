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

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import java.net.MalformedURLException;
import java.net.URL;

/**
 * Utility methods
 */
class Utils {
    /**
     * Check if MMS API is available
     *
     * @return true if MMS API is available, false otherwise
     */
    static boolean hasMmsApi() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
    }

    /**
     * Check if support multi-SIM
     *
     * @return true if MSIM is supported, false otherwise
     */
    static boolean supportMSim() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1;
    }

    /**
     * Check if support APIs for getting UserAgent and UAProfUrl
     *
     * @return true if those APIs are supported, false otherwise
     */
    static boolean hasUserAgentApi() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT;
    }

    /**
     * Get system SmsManager
     *
     * @param subId the subscription ID of the SmsManager
     * @return the SmsManager for the input subId
     */
    static SmsManager getSmsManager(final int subId) {
        if (supportMSim()) {
            return SmsManager.getSmsManagerForSubscriptionId(subId);
        } else {
            return SmsManager.getDefault();
        }
    }

    /**
     * Get the real subscription ID if the input is -1
     *
     * @param subId input subscription ID
     * @return the default SMS subscription ID if the input is -1, otherwise the original
     */
    static int getEffectiveSubscriptionId(int subId) {
        if (supportMSim()) {
            if (subId == MmsManager.DEFAULT_SUB_ID) {
                subId = SmsManager.getDefaultSmsSubscriptionId();
            }
        }
        if (subId < 0) {
            subId = MmsManager.DEFAULT_SUB_ID;
        }
        return subId;
    }

    /**
     * Get MCC/MNC of an SIM subscription
     *
     * @param context the Context to use
     * @param subId the SIM subId
     * @return a non-empty array with exactly two elements, first is mcc and last is mnc.
     */
    static int[] getMccMnc(final Context context, final int subId) {
        final int[] mccMnc = new int[] { 0, 0 };
        if (Utils.supportMSim()) {
            final SubscriptionManager subscriptionManager = SubscriptionManager.from(context);
            final SubscriptionInfo subInfo = subscriptionManager.getActiveSubscriptionInfo(subId);
            if (subInfo != null) {
                mccMnc[0] = subInfo.getMcc();
                mccMnc[1] = subInfo.getMnc();
            }
        } else {
            final TelephonyManager telephonyManager =
                    (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            final String mccMncString = telephonyManager.getSimOperator();
            try {
                mccMnc[0] = Integer.parseInt(mccMncString.substring(0, 3));
                mccMnc[1] = Integer.parseInt(mccMncString.substring(3));
            } catch (Exception e) {
                Log.w(MmsService.TAG, "Invalid mcc/mnc from system " + mccMncString + ": " + e);
                mccMnc[0] = 0;
                mccMnc[1] = 0;
            }
        }
        return mccMnc;
    }

    /**
     * Get a subscription's Context so we can load resources from it
     *
     * @param context the sub-independent Context
     * @param subId the SIM's subId
     * @return the sub-dependent Context
     */
    static Context getSubDepContext(final Context context, final int subId) {
        if (!supportMSim()) {
            return context;
        }
        final int[] mccMnc = getMccMnc(context, subId);
        final int mcc = mccMnc[0];
        final int mnc = mccMnc[1];
        if (mcc == 0 && mnc == 0) {
            return context;
        }
        final Configuration subConfig = new Configuration();
        subConfig.mcc = mcc;
        subConfig.mnc = mnc;
        return context.createConfigurationContext(subConfig);
    }

    /**
     * Redact the URL for non-VERBOSE logging. Replace url with only the host part and the length
     * of the input URL string.
     *
     * @param urlString
     * @return
     */
    static String redactUrlForNonVerbose(String urlString) {
        if (Log.isLoggable(MmsService.TAG, Log.VERBOSE)) {
            // Don't redact for VERBOSE level logging
            return urlString;
        }
        if (TextUtils.isEmpty(urlString)) {
            return urlString;
        }
        String protocol = "http";
        String host = "";
        try {
            final URL url = new URL(urlString);
            protocol = url.getProtocol();
            host = url.getHost();
        } catch (MalformedURLException e) {
            // Ignore
        }
        // Print "http://host[length]"
        final StringBuilder sb = new StringBuilder();
        sb.append(protocol).append("://").append(host)
                .append("[").append(urlString.length()).append("]");
        return sb.toString();
    }
}
