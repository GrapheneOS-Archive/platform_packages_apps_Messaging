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

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.Bundle;
import android.util.SparseArray;

import com.android.messaging.R;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.PhoneUtils;

import androidx.appcompat.mms.CarrierConfigValuesLoader;

/**
 * Carrier configuration loader
 *
 * Loader tries to load from resources. If there is MMS API available, also
 * load from system.
 */
public class BugleCarrierConfigValuesLoader implements CarrierConfigValuesLoader {
    /*
     * Key types
     */
    public static final String KEY_TYPE_INT = "int";
    public static final String KEY_TYPE_BOOL = "bool";
    public static final String KEY_TYPE_STRING = "string";

    private final Context mContext;

    // Cached values for subIds
    private final SparseArray<Bundle> mValuesCache;

    public BugleCarrierConfigValuesLoader(final Context context) {
        mContext = context;
        mValuesCache = new SparseArray<>();
    }

    @Override
    public Bundle get(int subId) {
        subId = PhoneUtils.getDefault().getEffectiveSubId(subId);
        Bundle values;
        String loadSource = null;
        synchronized (this) {
            values = mValuesCache.get(subId);
            if (values == null) {
                values = new Bundle();
                mValuesCache.put(subId, values);
                loadSource = loadLocked(subId, values);
            }
        }
        if (loadSource != null) {
            LogUtil.i(LogUtil.BUGLE_TAG, "Carrier configs loaded: " + values
                    + " from " + loadSource + " for subId=" + subId);
        }
        return values;
    }

    /**
     * Clear the cache for reloading
     */
    public void reset() {
        synchronized (this) {
            mValuesCache.clear();
        }
    }

    /**
     * Loading carrier config values
     *
     * @param subId which SIM to load for
     * @param values the result to add to
     * @return the source of the config, could be "resources" or "resources+system"
     */
    private String loadLocked(final int subId, final Bundle values) {
        // Load from resources in earlier platform
        loadFromResources(subId, values);

        // Load from system to override
        loadFromSystem(subId, values);
        return "resources+system";
    }

    /**
     * Load from system, using MMS API
     *
     * @param subId which SIM to load for
     * @param values the result to add to
     */
    private static void loadFromSystem(final int subId, final Bundle values) {
        try {
            final Bundle systemValues =
                    PhoneUtils.get(subId).getSmsManager().getCarrierConfigValues();
            if (systemValues != null) {
                values.putAll(systemValues);
            }
        } catch (final Exception e) {
            LogUtil.w(LogUtil.BUGLE_TAG, "Calling system getCarrierConfigValues exception", e);
        }
    }

    /**
     * Load from SIM-dependent resources
     *
     * @param subId which SIM to load for
     * @param values the result to add to
     */
    private void loadFromResources(final int subId, final Bundle values) {
        // Get a subscription-dependent context for loading the mms_config.xml
        final Context subContext = getSubDepContext(mContext, subId);
        // Load and parse the XML
        XmlResourceParser parser = null;
        try {
            parser = subContext.getResources().getXml(R.xml.mms_config);
            final ApnsXmlProcessor processor = ApnsXmlProcessor.get(parser);
            processor.setMmsConfigHandler(new ApnsXmlProcessor.MmsConfigHandler() {
                @Override
                public void process(final String mccMnc, final String key, final String value,
                        final String type) {
                    update(values, type, key, value);
                }
            });
            processor.process();
        } catch (final Resources.NotFoundException e) {
            LogUtil.w(LogUtil.BUGLE_TAG, "Can not find mms_config.xml");
        } finally {
            if (parser != null) {
                parser.close();
            }
        }
    }

    /**
     * Get a subscription's Context so we can load resources from it
     *
     * @param context the sub-independent Context
     * @param subId the SIM's subId
     * @return the sub-dependent Context
     */
    private static Context getSubDepContext(final Context context, final int subId) {
        final int[] mccMnc = PhoneUtils.get(subId).getMccMnc();
        final int mcc = mccMnc[0];
        final int mnc = mccMnc[1];
        final Configuration subConfig = new Configuration();
        if (mcc == 0 && mnc == 0) {
            Configuration config = context.getResources().getConfiguration();
            subConfig.mcc = config.mcc;
            subConfig.mnc = config.mnc;
        } else {
            subConfig.mcc = mcc;
            subConfig.mnc = mnc;
        }
        return context.createConfigurationContext(subConfig);
    }

    /**
     * Add or update a carrier config key/value pair to the Bundle
     *
     * @param values the result Bundle to add to
     * @param type the value type
     * @param key the key
     * @param value the value
     */
    public static void update(final Bundle values, final String type, final String key,
            final String value) {
        try {
            if (KEY_TYPE_INT.equals(type)) {
                values.putInt(key, Integer.parseInt(value));
            } else if (KEY_TYPE_BOOL.equals(type)) {
                values.putBoolean(key, Boolean.parseBoolean(value));
            } else if (KEY_TYPE_STRING.equals(type)){
                values.putString(key, value);
            }
        } catch (final NumberFormatException e) {
            LogUtil.w(LogUtil.BUGLE_TAG, "Add carrier values: "
                    + "invalid " + key + "," + value + "," + type);
        }
    }
}
