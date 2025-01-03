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
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;

/**
 * Utility methods
 */
class Utils {
    /**
     * Get system SmsManager
     *
     * @param subId the subscription ID of the SmsManager
     * @return the SmsManager for the input subId
     */
    static SmsManager getSmsManager(final int subId) {
        return SmsManager.getSmsManagerForSubscriptionId(subId);
    }

    /**
     * Get the real subscription ID if the input is -1
     *
     * @param subId input subscription ID
     * @return the default SMS subscription ID if the input is -1, otherwise the original
     */
    static int getEffectiveSubscriptionId(int subId) {
        if (subId == MmsManager.DEFAULT_SUB_ID) {
            subId = SmsManager.getDefaultSmsSubscriptionId();
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

        final SubscriptionManager subscriptionManager = SubscriptionManager.from(context);
        final SubscriptionInfo subInfo = subscriptionManager.getActiveSubscriptionInfo(subId);
        if (subInfo != null) {
            mccMnc[0] = subInfo.getMcc();
            mccMnc[1] = subInfo.getMnc();
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
}
