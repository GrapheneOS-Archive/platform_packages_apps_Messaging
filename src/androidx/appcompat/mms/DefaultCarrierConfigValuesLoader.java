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
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.Bundle;
import android.util.Log;
import android.util.SparseArray;

import com.android.messaging.R;

/**
 * The default implementation of loader for carrier config values
 */
class DefaultCarrierConfigValuesLoader implements CarrierConfigValuesLoader {
    /*
     * Key types
     */
    public static final String KEY_TYPE_INT = "int";
    public static final String KEY_TYPE_BOOL = "bool";
    public static final String KEY_TYPE_STRING = "string";

    private final Context mContext;

    // Cached values for subIds
    private final SparseArray<Bundle> mValuesCache;

    DefaultCarrierConfigValuesLoader(final Context context) {
        mContext = context;
        mValuesCache = new SparseArray<>();
    }

    @Override
    public Bundle get(int subId) {
        subId = Utils.getEffectiveSubscriptionId(subId);
        Bundle values;
        boolean didLoad = false;
        synchronized (this) {
            values = mValuesCache.get(subId);
            if (values == null) {
                values = new Bundle();
                mValuesCache.put(subId, values);
                loadLocked(subId, values);
                didLoad = true;
            }
        }
        if (didLoad) {
            Log.i(MmsService.TAG, "Carrier configs loaded: " + values);
        }
        return values;
    }

    private void loadLocked(final int subId, final Bundle values) {
        // For K and earlier, load from resources
        loadFromResources(subId, values);
        if (Utils.hasMmsApi()) {
            // For L and later, also load from system MMS service
            loadFromSystem(subId, values);
        }
    }

    /**
     * Load from system, using MMS API
     *
     * @param subId which SIM to load for
     * @param values the result to add to
     */
    private static void loadFromSystem(final int subId, final Bundle values) {
        try {
            final Bundle systemValues = Utils.getSmsManager(subId).getCarrierConfigValues();
            if (systemValues != null) {
                values.putAll(systemValues);
            }
        } catch (final Exception e) {
            Log.w(MmsService.TAG, "Calling system getCarrierConfigValues exception", e);
        }
    }

    private void loadFromResources(final int subId, final Bundle values) {
        // Get a subscription-dependent context for loading the mms_config.xml
        final Context subContext = Utils.getSubDepContext(mContext, subId);
        XmlResourceParser xml = null;
        try {
            xml = subContext.getResources().getXml(R.xml.mms_config);
            new CarrierConfigXmlParser(xml, new CarrierConfigXmlParser.KeyValueProcessor() {
                @Override
                public void process(String type, String key, String value) {
                    try {
                        if (KEY_TYPE_INT.equals(type)) {
                            values.putInt(key, Integer.parseInt(value));
                        } else if (KEY_TYPE_BOOL.equals(type)) {
                            values.putBoolean(key, Boolean.parseBoolean(value));
                        } else if (KEY_TYPE_STRING.equals(type)) {
                            values.putString(key, value);
                        }
                    } catch (final NumberFormatException e) {
                        Log.w(MmsService.TAG, "Load carrier value from resources: "
                                + "invalid " + key + "," + value + "," + type);
                    }
                }
            }).parse();
        } catch (final Resources.NotFoundException e) {
            Log.w(MmsService.TAG, "Can not get mms_config.xml");
        } finally {
            if (xml != null) {
                xml.close();
            }
        }
    }
}
