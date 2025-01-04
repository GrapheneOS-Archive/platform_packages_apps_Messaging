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
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.util.SparseArray;

/**
 * The public interface of MMS library
 */
public class MmsManager {
    /**
     * Default subscription ID
     */
    public static final int DEFAULT_SUB_ID = -1;

    // Cached computed overrides for carrier configuration values
    private static SparseArray<Bundle> sConfigOverridesMap = new SparseArray<>();

    /**
     * Set the optional carrier config values loader
     *
     * This is used to compute the overrides of carrier configuration values
     *
     * @param loader the carrier config values loader
     */
    public static void setCarrierConfigValuesLoader(CarrierConfigValuesLoader loader) {
        if (loader == null) {
            throw new IllegalArgumentException("Carrier configuration loader can not be empty");
        }
        synchronized (sConfigOverridesMap) {
            MmsService.setCarrierConfigValuesLoader(loader);
            sConfigOverridesMap.clear();
        }
    }

    /**
     * Set user agent info loader
     *
     * This is used to compute the overrides of carrier configuration values
     *
     * @param loader the user agent info loader
     */
    public static void setUserAgentInfoLoader(final UserAgentInfoLoader loader) {
        if (loader == null) {
            throw new IllegalArgumentException("User agent info loader can not be empty");
        }
        synchronized (sConfigOverridesMap) {
            MmsService.setUserAgentInfoLoader(loader);
            sConfigOverridesMap.clear();
        }
    }

    /**
     * Send MMS via platform MMS API.
     *
     * @param subId the subscription ID of the SIM to use
     * @param context the Context to use
     * @param contentUri the content URI of the PDU to be sent
     * @param locationUrl the optional location URL to use for sending
     * @param sentIntent the pending intent for returning results
     */
    public static void sendMultimediaMessage(int subId, Context context, Uri contentUri,
            String locationUrl, PendingIntent sentIntent) {
        subId = Utils.getEffectiveSubscriptionId(subId);
        final SmsManager smsManager = Utils.getSmsManager(subId);
        smsManager.sendMultimediaMessage(context, contentUri, locationUrl,
                getConfigOverrides(subId), sentIntent);
    }

    /**
     * Download MMS via platform MMS API.
     *
     * @param subId the subscription ID of the SIM to use
     * @param context the Context to use
     * @param contentUri the content URI of the PDU to be sent
     * @param locationUrl the optional location URL to use for sending
     * @param downloadedIntent the pending intent for returning results
     */
    public static void downloadMultimediaMessage(int subId, Context context, String locationUrl,
            Uri contentUri, PendingIntent downloadedIntent) {
        subId = Utils.getEffectiveSubscriptionId(subId);
        final SmsManager smsManager = Utils.getSmsManager(subId);
        smsManager.downloadMultimediaMessage(context, locationUrl, contentUri,
                getConfigOverrides(subId), downloadedIntent);
    }

    /**
     * Get carrier configuration values overrides when platform MMS API is called.
     * We only need to compute this if customized carrier config values loader or
     * user agent info loader are set
     *
     * @param subId the ID of the SIM to use
     * @return a Bundle containing the overrides
     */
    private static Bundle getConfigOverrides(final int subId) {
        Bundle overrides = null;
        synchronized (sConfigOverridesMap) {
            overrides = sConfigOverridesMap.get(subId);
            if (overrides == null) {
                overrides = new Bundle();
                sConfigOverridesMap.put(subId, overrides);
                computeOverridesLocked(subId, overrides);
            }
        }
        return overrides;
    }

    /**
     * Compute the overrides, incorporating the user agent info
     *
     * @param subId the subId of the SIM to use
     * @param overrides the computed values overrides
     */
    private static void computeOverridesLocked(final int subId, final Bundle overrides) {
        // Overrides not computed yet
        final CarrierConfigValuesLoader carrierConfigValuesLoader =
                MmsService.getCarrierConfigValuesLoader();
        if (carrierConfigValuesLoader != null) {
            // Compute the overrides for carrier config values first if the config loader
            // is not the default one.
            final Bundle systemValues = Utils.getSmsManager(subId).getCarrierConfigValues();
            final Bundle callerValues =
                    MmsService.getCarrierConfigValuesLoader().get(subId);
            if (callerValues != null) {
                computeConfigDelta(systemValues, callerValues, overrides);
            }
        }
        final UserAgentInfoLoader userAgentInfoLoader = MmsService.getUserAgentInfoLoader();
        if (userAgentInfoLoader != null) {
            // Also set the user agent and ua prof url via the overrides
            // if the user agent loader is not the default one.
            overrides.putString(UserAgentInfoLoader.CONFIG_USER_AGENT,
                    userAgentInfoLoader.getUserAgent());
            overrides.putString(UserAgentInfoLoader.CONFIG_UA_PROF_URL,
                    userAgentInfoLoader.getUAProfUrl());
        }
    }

    /**
     * Compute the delta between two sets of carrier configuration values: system and caller
     *
     * @param systemValues the system config values
     * @param callerValues the caller's config values
     * @param delta the delta of values (caller - system), using caller value to override system's
     */
    private static void computeConfigDelta(final Bundle systemValues, final Bundle callerValues,
            final Bundle delta) {
        for (final String key : callerValues.keySet()) {
            final Object callerValue = callerValues.get(key);
            final Object systemValue = systemValues.get(key);
            if ((callerValue != null && systemValue != null && !callerValue.equals(systemValue)) ||
                    (callerValue != null && systemValue == null) ||
                    (callerValue == null && systemValue != null)) {
                if (callerValue == null || callerValue instanceof String) {
                    delta.putString(key, (String) callerValue);
                } else if (callerValue instanceof Integer) {
                    delta.putInt(key, (Integer) callerValue);
                } else if (callerValue instanceof Boolean) {
                    delta.putBoolean(key, (Boolean) callerValue);
                }
            }
        }
    }
}
