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

import android.os.Bundle;
import android.telephony.SubscriptionInfo;

import com.android.messaging.Factory;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.util.Assert;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.PhoneUtils;
import com.android.messaging.util.SafeAsyncTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import androidx.appcompat.mms.CarrierConfigValuesLoader;

/**
 * MMS configuration.
 *
 * This is now a wrapper around the BugleCarrierConfigValuesLoader, which does
 * the actual loading and stores the values in a Bundle. This class provides getter
 * methods for values used in the app, which is easier to use than the raw loader
 * class.
 */
public class MmsConfig {
    private static final String TAG = LogUtil.BUGLE_TAG;

    private static final int DEFAULT_MAX_TEXT_LENGTH = 2000;

    /*
     * Key types
     */
    public static final String KEY_TYPE_INT = "int";
    public static final String KEY_TYPE_BOOL = "bool";
    public static final String KEY_TYPE_STRING = "string";

    private static final Map<String, String> sKeyTypeMap = new HashMap<>();
    static {
        sKeyTypeMap.put(CarrierConfigValuesLoader.CONFIG_ENABLED_MMS, KEY_TYPE_BOOL);
        sKeyTypeMap.put(CarrierConfigValuesLoader.CONFIG_ENABLED_TRANS_ID, KEY_TYPE_BOOL);
        sKeyTypeMap.put(CarrierConfigValuesLoader.CONFIG_ENABLED_NOTIFY_WAP_MMSC, KEY_TYPE_BOOL);
        sKeyTypeMap.put(CarrierConfigValuesLoader.CONFIG_ALIAS_ENABLED, KEY_TYPE_BOOL);
        sKeyTypeMap.put(CarrierConfigValuesLoader.CONFIG_ALLOW_ATTACH_AUDIO, KEY_TYPE_BOOL);
        sKeyTypeMap.put(CarrierConfigValuesLoader.CONFIG_ENABLE_MULTIPART_SMS, KEY_TYPE_BOOL);
        sKeyTypeMap.put(CarrierConfigValuesLoader.CONFIG_ENABLE_SMS_DELIVERY_REPORTS,
                KEY_TYPE_BOOL);
        sKeyTypeMap.put(CarrierConfigValuesLoader.CONFIG_ENABLE_GROUP_MMS, KEY_TYPE_BOOL);
        sKeyTypeMap.put(CarrierConfigValuesLoader.CONFIG_SUPPORT_MMS_CONTENT_DISPOSITION,
                KEY_TYPE_BOOL);
        sKeyTypeMap.put(CarrierConfigValuesLoader.CONFIG_CELL_BROADCAST_APP_LINKS, KEY_TYPE_BOOL);
        sKeyTypeMap.put(CarrierConfigValuesLoader.CONFIG_SEND_MULTIPART_SMS_AS_SEPARATE_MESSAGES,
                KEY_TYPE_BOOL);
        sKeyTypeMap.put(CarrierConfigValuesLoader.CONFIG_ENABLE_MMS_READ_REPORTS, KEY_TYPE_BOOL);
        sKeyTypeMap.put(CarrierConfigValuesLoader.CONFIG_ENABLE_MMS_DELIVERY_REPORTS,
                KEY_TYPE_BOOL);
        sKeyTypeMap.put(CarrierConfigValuesLoader.CONFIG_SUPPORT_HTTP_CHARSET_HEADER,
                KEY_TYPE_BOOL);
        sKeyTypeMap.put(CarrierConfigValuesLoader.CONFIG_MAX_MESSAGE_SIZE, KEY_TYPE_INT);
        sKeyTypeMap.put(CarrierConfigValuesLoader.CONFIG_MAX_IMAGE_HEIGHT, KEY_TYPE_INT);
        sKeyTypeMap.put(CarrierConfigValuesLoader.CONFIG_MAX_IMAGE_WIDTH, KEY_TYPE_INT);
        sKeyTypeMap.put(CarrierConfigValuesLoader.CONFIG_RECIPIENT_LIMIT, KEY_TYPE_INT);
        sKeyTypeMap.put(CarrierConfigValuesLoader.CONFIG_HTTP_SOCKET_TIMEOUT, KEY_TYPE_INT);
        sKeyTypeMap.put(CarrierConfigValuesLoader.CONFIG_ALIAS_MIN_CHARS, KEY_TYPE_INT);
        sKeyTypeMap.put(CarrierConfigValuesLoader.CONFIG_ALIAS_MAX_CHARS, KEY_TYPE_INT);
        sKeyTypeMap.put(CarrierConfigValuesLoader.CONFIG_SMS_TO_MMS_TEXT_THRESHOLD, KEY_TYPE_INT);
        sKeyTypeMap.put(CarrierConfigValuesLoader.CONFIG_SMS_TO_MMS_TEXT_LENGTH_THRESHOLD,
                KEY_TYPE_INT);
        sKeyTypeMap.put(CarrierConfigValuesLoader.CONFIG_MAX_MESSAGE_TEXT_SIZE, KEY_TYPE_INT);
        sKeyTypeMap.put(CarrierConfigValuesLoader.CONFIG_MAX_SUBJECT_LENGTH, KEY_TYPE_INT);
        sKeyTypeMap.put(CarrierConfigValuesLoader.CONFIG_UA_PROF_TAG_NAME, KEY_TYPE_STRING);
        sKeyTypeMap.put(CarrierConfigValuesLoader.CONFIG_HTTP_PARAMS, KEY_TYPE_STRING);
        sKeyTypeMap.put(CarrierConfigValuesLoader.CONFIG_EMAIL_GATEWAY_NUMBER, KEY_TYPE_STRING);
        sKeyTypeMap.put(CarrierConfigValuesLoader.CONFIG_NAI_SUFFIX, KEY_TYPE_STRING);
        sKeyTypeMap.put(CarrierConfigValuesLoader.CONFIG_UA_PROF_URL, KEY_TYPE_STRING);
        sKeyTypeMap.put(CarrierConfigValuesLoader.CONFIG_USER_AGENT, KEY_TYPE_STRING);
    }

    // A map that stores all MmsConfigs, one per active subscription. For pre-LMSim, this will
    // contain just one entry with the default self sub id; for LMSim and above, this will contain
    // all active sub ids but the default subscription id - the default subscription id will be
    // resolved to an active sub id during runtime.
    private static final Map<Integer, MmsConfig> sSubIdToMmsConfigMap = new HashMap<>();
    // The fallback values
    private static final MmsConfig sFallback =
            new MmsConfig(ParticipantData.DEFAULT_SELF_SUB_ID, new Bundle());

    // Per-subscription configuration values.
    private final Bundle mValues;
    private final int mSubId;

    /**
     * Retrieves the MmsConfig instance associated with the given {@code subId}
     */
    public static MmsConfig get(final int subId) {
        final int realSubId = PhoneUtils.getDefault().getEffectiveSubId(subId);
        synchronized (sSubIdToMmsConfigMap) {
            final MmsConfig mmsConfig = sSubIdToMmsConfigMap.get(realSubId);
            if (mmsConfig == null) {
                // The subId is no longer valid. Fall back to the default config.
                LogUtil.e(LogUtil.BUGLE_TAG, "Get mms config failed: invalid subId. subId=" + subId
                        + ", real subId=" + realSubId
                        + ", map=" + sSubIdToMmsConfigMap.keySet());
                return sFallback;
            }
            return mmsConfig;
        }
    }

    private MmsConfig(final int subId, final Bundle values) {
        mSubId = subId;
        mValues = values;
    }

    /**
     * Same as load() but doing it using an async thread from SafeAsyncTask thread pool.
     */
    public static void loadAsync() {
        SafeAsyncTask.executeOnThreadPool(new Runnable() {
            @Override
            public void run() {
                load();
            }
        });
    }

    /**
     * Reload the device and per-subscription settings.
     */
    public static synchronized void load() {
        final BugleCarrierConfigValuesLoader loader = Factory.get().getCarrierConfigValuesLoader();
        // Rebuild the entire MmsConfig map.
        sSubIdToMmsConfigMap.clear();
        loader.reset();
        final List<SubscriptionInfo> subInfoRecords =
                PhoneUtils.getDefault().getActiveSubscriptionInfoList();
        if (subInfoRecords == null) {
            LogUtil.w(TAG, "Loading mms config failed: no active SIM");
            return;
        }
        for (SubscriptionInfo subInfoRecord : subInfoRecords) {
            final int subId = subInfoRecord.getSubscriptionId();
            final Bundle values = loader.get(subId);
            addMmsConfig(new MmsConfig(subId, values));
        }
    }

    private static void addMmsConfig(MmsConfig mmsConfig) {
        Assert.isTrue(mmsConfig.mSubId != ParticipantData.DEFAULT_SELF_SUB_ID);
        sSubIdToMmsConfigMap.put(mmsConfig.mSubId, mmsConfig);
    }

    public int getSmsToMmsTextThreshold() {
        return mValues.getInt(CarrierConfigValuesLoader.CONFIG_SMS_TO_MMS_TEXT_THRESHOLD,
                CarrierConfigValuesLoader.CONFIG_SMS_TO_MMS_TEXT_THRESHOLD_DEFAULT);
    }

    public int getSmsToMmsTextLengthThreshold() {
        return mValues.getInt(CarrierConfigValuesLoader.CONFIG_SMS_TO_MMS_TEXT_LENGTH_THRESHOLD,
                CarrierConfigValuesLoader.CONFIG_SMS_TO_MMS_TEXT_LENGTH_THRESHOLD_DEFAULT);
    }

    public int getMaxMessageSize() {
        return mValues.getInt(CarrierConfigValuesLoader.CONFIG_MAX_MESSAGE_SIZE,
                CarrierConfigValuesLoader.CONFIG_MAX_MESSAGE_SIZE_DEFAULT);
    }

    /**
     * Return the largest MaxMessageSize for any subid
     */
    public static int getMaxMaxMessageSize() {
        int maxMax = 0;
        for (MmsConfig config : sSubIdToMmsConfigMap.values()) {
            maxMax = Math.max(maxMax, config.getMaxMessageSize());
        }
        return maxMax > 0 ? maxMax : sFallback.getMaxMessageSize();
    }

    public boolean getTransIdEnabled() {
        return mValues.getBoolean(CarrierConfigValuesLoader.CONFIG_ENABLED_TRANS_ID,
                CarrierConfigValuesLoader.CONFIG_ENABLED_TRANS_ID_DEFAULT);
    }

    public String getEmailGateway() {
        return mValues.getString(CarrierConfigValuesLoader.CONFIG_EMAIL_GATEWAY_NUMBER,
                CarrierConfigValuesLoader.CONFIG_EMAIL_GATEWAY_NUMBER_DEFAULT);
    }

    public int getMaxImageHeight() {
        return mValues.getInt(CarrierConfigValuesLoader.CONFIG_MAX_IMAGE_HEIGHT,
                CarrierConfigValuesLoader.CONFIG_MAX_IMAGE_HEIGHT_DEFAULT);
    }

    public int getMaxImageWidth() {
        return mValues.getInt(CarrierConfigValuesLoader.CONFIG_MAX_IMAGE_WIDTH,
                CarrierConfigValuesLoader.CONFIG_MAX_IMAGE_WIDTH_DEFAULT);
    }

    public int getRecipientLimit() {
        final int limit = mValues.getInt(CarrierConfigValuesLoader.CONFIG_RECIPIENT_LIMIT,
                CarrierConfigValuesLoader.CONFIG_RECIPIENT_LIMIT_DEFAULT);
        return limit < 0 ? Integer.MAX_VALUE : limit;
    }

    public int getMaxTextLimit() {
        final int max = mValues.getInt(CarrierConfigValuesLoader.CONFIG_MAX_MESSAGE_TEXT_SIZE,
                CarrierConfigValuesLoader.CONFIG_MAX_MESSAGE_TEXT_SIZE_DEFAULT);
        return max > -1 ? max : DEFAULT_MAX_TEXT_LENGTH;
    }

    public boolean getMultipartSmsEnabled() {
        return mValues.getBoolean(CarrierConfigValuesLoader.CONFIG_ENABLE_MULTIPART_SMS,
                CarrierConfigValuesLoader.CONFIG_ENABLE_MULTIPART_SMS_DEFAULT);
    }

    public boolean getSendMultipartSmsAsSeparateMessages() {
        return mValues.getBoolean(
                CarrierConfigValuesLoader.CONFIG_SEND_MULTIPART_SMS_AS_SEPARATE_MESSAGES,
                CarrierConfigValuesLoader.CONFIG_SEND_MULTIPART_SMS_AS_SEPARATE_MESSAGES_DEFAULT);
    }

    public boolean getSMSDeliveryReportsEnabled() {
        return mValues.getBoolean(CarrierConfigValuesLoader.CONFIG_ENABLE_SMS_DELIVERY_REPORTS,
                CarrierConfigValuesLoader.CONFIG_ENABLE_SMS_DELIVERY_REPORTS_DEFAULT);
    }

    public boolean getNotifyWapMMSC() {
        return mValues.getBoolean(CarrierConfigValuesLoader.CONFIG_ENABLED_NOTIFY_WAP_MMSC,
                CarrierConfigValuesLoader.CONFIG_ENABLED_NOTIFY_WAP_MMSC_DEFAULT);
    }

    public boolean isAliasEnabled() {
        return mValues.getBoolean(CarrierConfigValuesLoader.CONFIG_ALIAS_ENABLED,
                CarrierConfigValuesLoader.CONFIG_ALIAS_ENABLED_DEFAULT);
    }

    public int getAliasMinChars() {
        return mValues.getInt(CarrierConfigValuesLoader.CONFIG_ALIAS_MIN_CHARS,
                CarrierConfigValuesLoader.CONFIG_ALIAS_MIN_CHARS_DEFAULT);
    }

    public int getAliasMaxChars() {
        return mValues.getInt(CarrierConfigValuesLoader.CONFIG_ALIAS_MAX_CHARS,
                CarrierConfigValuesLoader.CONFIG_ALIAS_MAX_CHARS_DEFAULT);
    }

    public boolean getAllowAttachAudio() {
        return mValues.getBoolean(CarrierConfigValuesLoader.CONFIG_ALLOW_ATTACH_AUDIO,
                CarrierConfigValuesLoader.CONFIG_ALLOW_ATTACH_AUDIO_DEFAULT);
    }

    public int getMaxSubjectLength() {
        return mValues.getInt(CarrierConfigValuesLoader.CONFIG_MAX_SUBJECT_LENGTH,
                CarrierConfigValuesLoader.CONFIG_MAX_SUBJECT_LENGTH_DEFAULT);
    }

    public boolean getGroupMmsEnabled() {
        return mValues.getBoolean(CarrierConfigValuesLoader.CONFIG_ENABLE_GROUP_MMS,
                CarrierConfigValuesLoader.CONFIG_ENABLE_GROUP_MMS_DEFAULT);
    }

    public boolean getSupportMmsContentDisposition() {
        return mValues.getBoolean(CarrierConfigValuesLoader.CONFIG_SUPPORT_MMS_CONTENT_DISPOSITION,
                CarrierConfigValuesLoader.CONFIG_SUPPORT_MMS_CONTENT_DISPOSITION_DEFAULT);
    }

    public boolean getShowCellBroadcast() {
        return mValues.getBoolean(CarrierConfigValuesLoader.CONFIG_CELL_BROADCAST_APP_LINKS,
                CarrierConfigValuesLoader.CONFIG_CELL_BROADCAST_APP_LINKS_DEFAULT);
    }

    public Object getValue(final String key) {
        return mValues.get(key);
    }

    public Set<String> keySet() {
        return mValues.keySet();
    }

    public static String getKeyType(final String key) {
        return sKeyTypeMap.get(key);
    }

    public void update(final String type, final String key, final String value) {
        BugleCarrierConfigValuesLoader.update(mValues, type, key, value);
    }
}
