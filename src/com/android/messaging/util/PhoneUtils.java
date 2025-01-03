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
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.Cursor;
import android.provider.Settings;
import android.provider.Telephony;
import android.telephony.PhoneNumberUtils;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import com.android.messaging.Factory;
import com.android.messaging.R;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.sms.MmsSmsUtils;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import androidx.collection.ArrayMap;

/**
 * This class abstracts away platform dependency of calling telephony related
 * platform APIs, mostly involving TelephonyManager, SubscriptionManager and
 * a bit of SmsManager.
 *
 * The class instance can only be obtained via the get(int subId) method parameterized
 * by a SIM subscription ID.
 *
 * A convenient getDefault() method is provided for default subId (-1) on any platform
 */
public class PhoneUtils {
    private static final String TAG = LogUtil.BUGLE_TAG;

    private static final int MINIMUM_PHONE_NUMBER_LENGTH_TO_FORMAT = 6;

    private static final List<SubscriptionInfo> EMPTY_SUBSCRIPTION_LIST = new ArrayList<>();

    // The canonical phone number cache
    // Each country gets its own cache. The following maps from ISO country code to
    // the country's cache. Each cache maps from original phone number to canonicalized phone
    private static final ArrayMap<String, ArrayMap<String, String>> sCanonicalPhoneNumberCache =
            new ArrayMap<>();

    protected final Context mContext;
    protected final TelephonyManager mTelephonyManager;
    private final SubscriptionManager mSubscriptionManager;
    protected final int mSubId;

    public PhoneUtils(int subId) {
        mSubId = subId;
        mContext = Factory.get().getApplicationContext();
        mTelephonyManager =
                (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
        mSubscriptionManager = SubscriptionManager.from(Factory.get().getApplicationContext());
    }

    /**
     * Get the SIM's country code
     *
     * @return the country code on the SIM
     */
    public String getSimCountry() {
        final SubscriptionInfo subInfo = getActiveSubscriptionInfo();
        if (subInfo != null) {
            final String country = subInfo.getCountryIso();
            if (TextUtils.isEmpty(country)) {
                return null;
            }
            return country.toUpperCase();
        }
        return null;
    }

    /**
     * Get number of SIM slots
     *
     * @return the SIM slot count
     */
    public int getSimSlotCount() {
        return mSubscriptionManager.getActiveSubscriptionInfoCountMax();
    }

    /**
     * Get SIM's carrier name
     *
     * @return the carrier name of the SIM
     */
    public String getCarrierName() {
        final SubscriptionInfo subInfo = getActiveSubscriptionInfo();
        if (subInfo != null) {
            final CharSequence displayName = subInfo.getDisplayName();
            if (!TextUtils.isEmpty(displayName)) {
                return displayName.toString();
            }
            final CharSequence carrierName = subInfo.getCarrierName();
            if (carrierName != null) {
                return carrierName.toString();
            }
        }
        return null;
    }

    /**
     * Check if there is SIM inserted on the device
     *
     * @return true if there is SIM inserted, false otherwise
     */
    public boolean hasSim() {
        return mSubscriptionManager.getActiveSubscriptionInfoCount() > 0;
    }

    /**
     * Check if the SIM is roaming
     *
     * @return true if the SIM is in romaing state, false otherwise
     */
    public boolean isRoaming() {
        return mSubscriptionManager.isNetworkRoaming(mSubId);
    }

    /**
     * Get the MCC and MNC in integer of the SIM's provider
     *
     * @return an array of two ints, [0] is the MCC code and [1] is the MNC code
     */
    public int[] getMccMnc() {
        int mcc = 0;
        int mnc = 0;
        final SubscriptionInfo subInfo = getActiveSubscriptionInfo();
        if (subInfo != null) {
            mcc = subInfo.getMcc();
            mnc = subInfo.getMnc();
        }
        return new int[]{mcc, mnc};
    }

    /**
     * Get the mcc/mnc string
     *
     * @return the text of mccmnc string
     */
    public String getSimOperatorNumeric() {
        return getMccMncString(getMccMnc());
    }

    /**
     * Get the SIM's self raw number, i.e. not canonicalized
     *
     * @param allowOverride Whether to use the app's setting to override the self number
     * @return the original self number
     * @throws IllegalStateException if no active subscription
     */
    public String getSelfRawNumber(final boolean allowOverride) {
        if (allowOverride) {
            final String userDefinedNumber = getNumberFromPrefs(mContext, mSubId);
            if (!TextUtils.isEmpty(userDefinedNumber)) {
                return userDefinedNumber;
            }
        }

        final SubscriptionInfo subInfo = getActiveSubscriptionInfo();
        if (subInfo != null) {
            String phoneNumber = subInfo.getNumber();
            if (TextUtils.isEmpty(phoneNumber) && LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
                LogUtil.d(TAG, "SubscriptionInfo phone number for self is empty!");
            }
            return phoneNumber;
        }
        LogUtil.w(TAG, "PhoneUtils.getSelfRawNumber: subInfo is null for " + mSubId);
        throw new IllegalStateException("No active subscription");
    }

    /**
     * Returns the "effective" subId, or the subId used in the context of actual messages,
     * conversations and subscription-specific settings, for the given "nominal" sub id.
     *
     * DEFAULT_SELF_SUB_ID will be mapped to the system default subscription id for SMS.
     *
     * @param subId The input subId
     * @return the real subId if we can convert
     */
    public int getEffectiveSubId(int subId) {
        if (subId == ParticipantData.DEFAULT_SELF_SUB_ID) {
            return getDefaultSmsSubscriptionId();
        }
        return subId;
    }

    /**
     * Returns the number of active subscriptions in the device.
     */
    public int getActiveSubscriptionCount() {
        return mSubscriptionManager.getActiveSubscriptionInfoCount();
    }

    /**
     * Get {@link SmsManager} instance
     *
     * @return the relevant SmsManager instance based on OS version and subId
     */
    public SmsManager getSmsManager() {
        return SmsManager.getSmsManagerForSubscriptionId(mSubId);
    }

    /**
     * Get the default SMS subscription id
     *
     * @return the default sub ID
     */
    public int getDefaultSmsSubscriptionId() {
        final int systemDefaultSubId = SmsManager.getDefaultSmsSubscriptionId();
        if (systemDefaultSubId < 0) {
            // Always use -1 for any negative subId from system
            return ParticipantData.DEFAULT_SELF_SUB_ID;
        }
        return systemDefaultSubId;
    }

    /**
     * Returns if there's currently a system default SIM selected for sending SMS.
     */
    public boolean getHasPreferredSmsSim() {
        return getDefaultSmsSubscriptionId() != ParticipantData.DEFAULT_SELF_SUB_ID;
    }

    /**
     * System may return a negative subId. Convert this into our own subId, so that we consistently
     * use -1 for invalid or default.
     *
     * see b/18629526 and b/18670346
     *
     * @param intent The push intent from system
     * @param extraName The name of the sub id extra
     * @return the subId that is valid and meaningful for the app
     */
    public int getEffectiveIncomingSubIdFromSystem(Intent intent, String extraName) {
        return getEffectiveIncomingSubIdFromSystem(intent.getIntExtra(extraName,
                ParticipantData.DEFAULT_SELF_SUB_ID));
    }

    /**
     * Get the subscription_id column value from a telephony provider cursor
     *
     * @param cursor The database query cursor
     * @param subIdIndex The index of the subId column in the cursor
     * @return the subscription_id column value from the cursor
     */
    public int getSubIdFromTelephony(Cursor cursor, int subIdIndex) {
        return getEffectiveIncomingSubIdFromSystem(cursor.getInt(subIdIndex));
    }

    /**
     * Check if data roaming is enabled
     *
     * @return true if data roaming is enabled, false otherwise
     */
    public boolean isDataRoamingEnabled() {
        final SubscriptionInfo subInfo = getActiveSubscriptionInfo();
        if (subInfo == null) {
            // There is nothing we can do if system give us empty sub info
            LogUtil.e(TAG, "PhoneUtils.isDataRoamingEnabled: system return empty sub info for "
                    + mSubId);
            return false;
        }
        return subInfo.getDataRoaming() != SubscriptionManager.DATA_ROAMING_DISABLE;
    }

    /**
     * Check if mobile data is enabled
     *
     * @return true if mobile data is enabled, false otherwise
     */
    public boolean isMobileDataEnabled() {
        return mTelephonyManager.createForSubscriptionId(mSubId)
                .isDataEnabledForReason(TelephonyManager.DATA_ENABLED_REASON_USER);
    }

    /**
     * Get the set of self phone numbers, all normalized
     *
     * @return the set of normalized self phone numbers
     */
    public HashSet<String> getNormalizedSelfNumbers() {
        final HashSet<String> numbers = new HashSet<>();
        for (SubscriptionInfo info : getActiveSubscriptionInfoList()) {
            numbers.add(PhoneUtils.get(info.getSubscriptionId()).getCanonicalForSelf(
                    true/*allowOverride*/));
        }
        return numbers;
    }


    /**
     * Get this SIM's information.
     *
     * @return the subscription info of the SIM
     */
    public SubscriptionInfo getActiveSubscriptionInfo() {
        try {
            final SubscriptionInfo subInfo =
                    mSubscriptionManager.getActiveSubscriptionInfo(mSubId);
            if (subInfo == null) {
                if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
                    // This is possible if the sub id is no longer available.
                    LogUtil.d(TAG, "PhoneUtils.getActiveSubscriptionInfo(): empty sub info for "
                            + mSubId);
                }
            }
            return subInfo;
        } catch (Exception e) {
            LogUtil.e(TAG, "PhoneUtils.getActiveSubscriptionInfo: system exception for "
                    + mSubId, e);
        }
        return null;
    }

    /**
     * Get the list of active SIMs in system.
     *
     * @return the list of subscription info for all inserted SIMs
     */
    public List<SubscriptionInfo> getActiveSubscriptionInfoList() {
        final List<SubscriptionInfo> subscriptionInfos =
                mSubscriptionManager.getActiveSubscriptionInfoList();
        if (subscriptionInfos != null) {
            return subscriptionInfos;
        }
        return EMPTY_SUBSCRIPTION_LIST;
    }

    /**
     * Register subscription change listener.
     *
     * @param listener The listener to register
     */
    public void registerOnSubscriptionsChangedListener(
            SubscriptionManager.OnSubscriptionsChangedListener listener) {
        mSubscriptionManager.addOnSubscriptionsChangedListener(listener);
    }

    private int getEffectiveIncomingSubIdFromSystem(int subId) {
        if (subId < 0) {
            if (mSubscriptionManager.getActiveSubscriptionInfoCount() > 1) {
                // For multi-SIM device, we can not decide which SIM to use if system
                // does not know either. So just make it the invalid sub id.
                return ParticipantData.DEFAULT_SELF_SUB_ID;
            }
            // For single-SIM device, it must come from the only SIM we have
            return getDefaultSmsSubscriptionId();
        }
        return subId;
    }

    /**
     * A convenient get() method that uses the default SIM. Use this when SIM is
     * not relevant, e.g. isDefaultSmsApp
     *
     * @return an instance of PhoneUtils for default SIM
     */
    public static PhoneUtils getDefault() {
        return Factory.get().getPhoneUtils(ParticipantData.DEFAULT_SELF_SUB_ID);
    }

    /**
     * Get an instance of PhoneUtils associated with a specific SIM, which is also platform
     * specific.
     *
     * @param subId The SIM's subscription ID
     * @return the instance
     */
    public static PhoneUtils get(int subId) {
        return Factory.get().getPhoneUtils(subId);
    }

    /**
     * Check if this device supports SMS
     *
     * @return true if SMS is supported, false otherwise
     */
    public boolean isSmsCapable() {
        return mTelephonyManager.isSmsCapable();
    }

    /**
     * Check if this device supports voice calling
     *
     * @return true if voice calling is supported, false otherwise
     */
    public boolean isVoiceCapable() {
        return mTelephonyManager.isVoiceCapable();
    }

    /**
     * Get the ISO country code from system locale setting
     *
     * @return the ISO country code from system locale
     */
    private static String getLocaleCountry() {
        final String country = Locale.getDefault().getCountry();
        if (TextUtils.isEmpty(country)) {
            return null;
        }
        return country.toUpperCase();
    }

    /**
     * Get ISO country code from the SIM, if not available, fall back to locale
     *
     * @return SIM or locale ISO country code
     */
    public String getSimOrDefaultLocaleCountry() {
        String country = getSimCountry();
        if (country == null) {
            country = getLocaleCountry();
        }
        return country;
    }

    // Get or set the cache of canonicalized phone numbers for a specific country
    private static ArrayMap<String, String> getOrAddCountryMapInCacheLocked(String country) {
        if (country == null) {
            country = "";
        }
        ArrayMap<String, String> countryMap = sCanonicalPhoneNumberCache.get(country);
        if (countryMap == null) {
            countryMap = new ArrayMap<>();
            sCanonicalPhoneNumberCache.put(country, countryMap);
        }
        return countryMap;
    }

    // Get canonicalized phone number from cache
    private static String getCanonicalFromCache(final String phoneText, String country) {
        synchronized (sCanonicalPhoneNumberCache) {
            final ArrayMap<String, String> countryMap = getOrAddCountryMapInCacheLocked(country);
            return countryMap.get(phoneText);
        }
    }

    // Put canonicalized phone number into cache
    private static void putCanonicalToCache(final String phoneText, String country,
            final String canonical) {
        synchronized (sCanonicalPhoneNumberCache) {
            final ArrayMap<String, String> countryMap = getOrAddCountryMapInCacheLocked(country);
            countryMap.put(phoneText, canonical);
        }
    }

    /**
     * Utility method to parse user input number into standard E164 number.
     *
     * @param phoneText Phone number text as input by user.
     * @param country ISO country code based on which to parse the number.
     * @return E164 phone number. Returns null in case parsing failed.
     */
    private static String getValidE164Number(final String phoneText, final String country) {
        final PhoneNumberUtil phoneNumberUtil = PhoneNumberUtil.getInstance();
        try {
            final PhoneNumber phoneNumber = phoneNumberUtil.parse(phoneText, country);
            if (phoneNumber != null && phoneNumberUtil.isValidNumber(phoneNumber)) {
                return phoneNumberUtil.format(phoneNumber, PhoneNumberFormat.E164);
            }
        } catch (final NumberParseException e) {
            LogUtil.e(TAG, "PhoneUtils.getValidE164Number(): Not able to parse phone number "
                        + LogUtil.sanitizePII(phoneText) + " for country " + country);
        }
        return null;
    }

    /**
     * Canonicalize phone number using system locale country
     *
     * @param phoneText The phone number to canonicalize
     * @return the canonicalized number
     */
    public String getCanonicalBySystemLocale(final String phoneText) {
        return getCanonicalByCountry(phoneText, getLocaleCountry());
    }

    /**
     * Canonicalize phone number using SIM's country, may fall back to system locale country
     * if SIM country can not be obtained
     *
     * @param phoneText The phone number to canonicalize
     * @return the canonicalized number
     */
    public String getCanonicalBySimLocale(final String phoneText) {
        return getCanonicalByCountry(phoneText, getSimOrDefaultLocaleCountry());
    }

    /**
     * Canonicalize phone number using a country code.
     * This uses an internal cache per country to speed up.
     *
     * @param phoneText The phone number to canonicalize
     * @param country The ISO country code to use
     * @return the canonicalized number, or the original number if can't be parsed
     */
    private String getCanonicalByCountry(final String phoneText, final String country) {
        Assert.notNull(phoneText);

        String canonicalNumber = getCanonicalFromCache(phoneText, country);
        if (canonicalNumber != null) {
            return canonicalNumber;
        }
        canonicalNumber = getValidE164Number(phoneText, country);
        if (canonicalNumber == null) {
            // If we can't normalize this number, we just use the display string number.
            // This is possible for short codes and other non-localizable numbers.
            canonicalNumber = phoneText;
        }
        putCanonicalToCache(phoneText, country, canonicalNumber);
        return canonicalNumber;
    }

    /**
     * Canonicalize the self (per SIM) phone number
     *
     * @param allowOverride whether to use the override number in app settings
     * @return the canonicalized self phone number
     */
    public String getCanonicalForSelf(final boolean allowOverride) {
        String selfNumber = null;
        try {
            selfNumber = getSelfRawNumber(allowOverride);
        } catch (IllegalStateException e) {
            // continue;
        }
        if (selfNumber == null) {
            return "";
        }
        return getCanonicalBySimLocale(selfNumber);
    }

    /**
     * Get the SIM's phone number in NATIONAL format with only digits, used in sending
     * as LINE1NOCOUNTRYCODE macro in mms_config
     *
     * @return all digits national format number of the SIM
     */
    public String getSimNumberNoCountryCode() {
        String selfNumber = null;
        try {
            selfNumber = getSelfRawNumber(false/*allowOverride*/);
        } catch (IllegalStateException e) {
            // continue
        }
        if (selfNumber == null) {
            selfNumber = "";
        }
        final String country = getSimCountry();
        final PhoneNumberUtil phoneNumberUtil = PhoneNumberUtil.getInstance();
        try {
            final PhoneNumber phoneNumber = phoneNumberUtil.parse(selfNumber, country);
            if (phoneNumber != null && phoneNumberUtil.isValidNumber(phoneNumber)) {
                return phoneNumberUtil
                        .format(phoneNumber, PhoneNumberFormat.NATIONAL)
                        .replaceAll("\\D", "");
            }
        } catch (final NumberParseException e) {
            LogUtil.e(TAG, "PhoneUtils.getSimNumberNoCountryCode(): Not able to parse phone number "
                    + LogUtil.sanitizePII(selfNumber) + " for country " + country);
        }
        return selfNumber;

    }

    /**
     * Format a phone number for displaying, using system locale country.
     * If the country code matches between the system locale and the input phone number,
     * it will be formatted into NATIONAL format, otherwise, the INTERNATIONAL format
     *
     * @param phoneText The original phone text
     * @return formatted number
     */
    public String formatForDisplay(final String phoneText) {
        // Only format a valid number which length >=6
        if (TextUtils.isEmpty(phoneText) ||
                phoneText.replaceAll("\\D", "").length() < MINIMUM_PHONE_NUMBER_LENGTH_TO_FORMAT) {
            return phoneText;
        }
        final PhoneNumberUtil phoneNumberUtil = PhoneNumberUtil.getInstance();
        final String systemCountry = getLocaleCountry();
        final int systemCountryCode = phoneNumberUtil.getCountryCodeForRegion(systemCountry);
        try {
            final PhoneNumber parsedNumber = phoneNumberUtil.parse(phoneText, systemCountry);
            final PhoneNumberFormat phoneNumberFormat =
                    (systemCountryCode > 0 && parsedNumber.getCountryCode() == systemCountryCode) ?
                            PhoneNumberFormat.NATIONAL : PhoneNumberFormat.INTERNATIONAL;
            return phoneNumberUtil.format(parsedNumber, phoneNumberFormat);
        } catch (NumberParseException e) {
            LogUtil.e(TAG, "PhoneUtils.formatForDisplay: invalid phone number "
                    + LogUtil.sanitizePII(phoneText) + " with country " + systemCountry);
            return phoneText;
        }
    }

    /**
     * Is Messaging the default SMS app?
     */
    public boolean isDefaultSmsApp() {
        final String configuredApplication = Telephony.Sms.getDefaultSmsPackage(mContext);
        return mContext.getPackageName().equals(configuredApplication);
    }

    /**
     * Get default SMS app package name
     *
     * @return the package name of default SMS app
     */
    public String getDefaultSmsApp() {
        return Telephony.Sms.getDefaultSmsPackage(mContext);
    }

    /**
     * Determines if SMS is currently enabled on this device.
     * - Device must support SMS
     * - We must be set as the default SMS app
     */
    public boolean isSmsEnabled() {
        return isSmsCapable() && isDefaultSmsApp();
    }

    /**
     * Returns the name of the default SMS app, or the empty string if there is
     * an error or there is no default app.
     */
    public String getDefaultSmsAppLabel() {
        final String packageName = Telephony.Sms.getDefaultSmsPackage(mContext);
        final PackageManager pm = mContext.getPackageManager();
        try {
            final ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(appInfo).toString();
        } catch (NameNotFoundException e) {
            // Fall through and return empty string
        }
        return "";
    }

    /**
     * Gets the state of Airplane Mode.
     *
     * @return true if enabled.
     */
    public boolean isAirplaneModeOn() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
    }

    public static String getMccMncString(int[] mccmnc) {
        if (mccmnc == null || mccmnc.length != 2) {
            return "000000";
        }
        return String.format(Locale.ROOT, "%03d%03d", mccmnc[0], mccmnc[1]);
    }

    public static String canonicalizeMccMnc(final String mcc, final String mnc) {
        try {
            return String.format(Locale.ROOT, "%03d%03d", Integer.parseInt(mcc), Integer.parseInt(mnc));
        } catch (final NumberFormatException e) {
            // Return invalid as is
            LogUtil.w(TAG, "canonicalizeMccMnc: invalid mccmnc:" + mcc + " ," + mnc);
        }
        return mcc + mnc;
    }

    /**
     * Returns whether the given destination is valid for sending SMS/MMS message.
     */
    public static boolean isValidSmsMmsDestination(final String destination) {
        return PhoneNumberUtils.isWellFormedSmsAddress(destination) ||
                MmsSmsUtils.isEmailAddress(destination);
    }

    public interface SubscriptionRunnable {
        void runForSubscription(int subId);
    }

    /**
     * A convenience method for iterating through all active subscriptions
     *
     * @param runnable a {@link SubscriptionRunnable} for performing work on each subscription.
     */
    public static void forEachActiveSubscription(final SubscriptionRunnable runnable) {
        final List<SubscriptionInfo> subscriptionList =
                getDefault().getActiveSubscriptionInfoList();
        for (final SubscriptionInfo subscriptionInfo : subscriptionList) {
            runnable.runForSubscription(subscriptionInfo.getSubscriptionId());
        }
    }

    private static String getNumberFromPrefs(final Context context, final int subId) {
        final BuglePrefs prefs = BuglePrefs.getSubscriptionPrefs(subId);
        final String mmsPhoneNumberPrefKey =
                context.getString(R.string.mms_phone_number_pref_key);
        final String userDefinedNumber = prefs.getString(mmsPhoneNumberPrefKey, null);
        if (!TextUtils.isEmpty(userDefinedNumber)) {
            return userDefinedNumber;
        }
        return null;
    }
}
