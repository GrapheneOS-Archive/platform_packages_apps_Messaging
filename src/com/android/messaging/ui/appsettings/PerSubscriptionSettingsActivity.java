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

package com.android.messaging.ui.appsettings;

import android.app.FragmentTransaction;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.text.TextUtils;
import android.view.MenuItem;

import com.android.messaging.Factory;
import com.android.messaging.R;
import com.android.messaging.datamodel.ParticipantRefresh;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.sms.MmsConfig;
import com.android.messaging.ui.BugleActionBarActivity;
import com.android.messaging.ui.UIIntents;
import com.android.messaging.util.Assert;
import com.android.messaging.util.BuglePrefs;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.PhoneUtils;

import androidx.core.app.NavUtils;

public class PerSubscriptionSettingsActivity extends BugleActionBarActivity {
    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        final String title = getIntent().getStringExtra(
                UIIntents.UI_INTENT_EXTRA_PER_SUBSCRIPTION_SETTING_TITLE);
        if (!TextUtils.isEmpty(title)) {
            getSupportActionBar().setTitle(title);
        } else {
            // This will fall back to the default title, i.e. "Messaging settings," so No-op.
        }

        final FragmentTransaction ft = getFragmentManager().beginTransaction();
        final PerSubscriptionSettingsFragment fragment = new PerSubscriptionSettingsFragment();
        ft.replace(android.R.id.content, fragment);
        ft.commit();
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
        case android.R.id.home:
            NavUtils.navigateUpFromSameTask(this);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public static class PerSubscriptionSettingsFragment extends PreferenceFragment
            implements OnSharedPreferenceChangeListener {
        private PhoneNumberPreference mPhoneNumberPreference;
        private Preference mGroupMmsPreference;
        private String mGroupMmsPrefKey;
        private String mPhoneNumberKey;
        private int mSubId;

        public PerSubscriptionSettingsFragment() {
            // Required empty constructor
        }

        @Override
        public void onCreate(final Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            // Get sub id from launch intent
            final Intent intent = getActivity().getIntent();
            Assert.notNull(intent);
            mSubId = (intent != null) ? intent.getIntExtra(UIIntents.UI_INTENT_EXTRA_SUB_ID,
                    ParticipantData.DEFAULT_SELF_SUB_ID) : ParticipantData.DEFAULT_SELF_SUB_ID;

            final BuglePrefs subPrefs = Factory.get().getSubscriptionPrefs(mSubId);
            getPreferenceManager().setSharedPreferencesName(subPrefs.getSharedPreferencesName());
            addPreferencesFromResource(R.xml.preferences_per_subscription);

            mPhoneNumberKey = getString(R.string.mms_phone_number_pref_key);
            mPhoneNumberPreference = (PhoneNumberPreference) findPreference(mPhoneNumberKey);
            final PreferenceCategory advancedCategory = (PreferenceCategory)
                    findPreference(getString(R.string.advanced_category_pref_key));
            final PreferenceCategory mmsCategory = (PreferenceCategory)
                    findPreference(getString(R.string.mms_messaging_category_pref_key));

            mPhoneNumberPreference.setDefaultPhoneNumber(
                    PhoneUtils.get(mSubId).getCanonicalForSelf(false/*allowOverride*/), mSubId);

            mGroupMmsPrefKey = getString(R.string.group_mms_pref_key);
            mGroupMmsPreference = findPreference(mGroupMmsPrefKey);
            if (!MmsConfig.get(mSubId).getGroupMmsEnabled()) {
                // Always show group messaging setting even if the SIM has no number
                // If broadcast sms is selected, the SIM number is not needed
                // If group mms is selected, the phone number dialog will popup when message
                // is being sent, making sure we will have a self number for group mms.
                mmsCategory.removePreference(mGroupMmsPreference);
            } else {
                mGroupMmsPreference.setOnPreferenceClickListener(new OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference pref) {
                        GroupMmsSettingDialog.showDialog(getActivity(), mSubId);
                        return true;
                    }
                });
                updateGroupMmsPrefSummary();
            }

            if (!MmsConfig.get(mSubId).getSMSDeliveryReportsEnabled()) {
                final Preference deliveryReportsPref = findPreference(
                        getString(R.string.delivery_reports_pref_key));
                advancedCategory.removePreference(deliveryReportsPref);
            }
            final Preference wirelessAlertPref = findPreference(getString(
                    R.string.wireless_alerts_key));
            if (!isCellBroadcastAppLinkEnabled()) {
                advancedCategory.removePreference(wirelessAlertPref);
            } else {
                wirelessAlertPref.setOnPreferenceClickListener(
                        new Preference.OnPreferenceClickListener() {
                            @Override
                            public boolean onPreferenceClick(final Preference preference) {
                                try {
                                    startActivity(UIIntents.get().getWirelessAlertsIntent());
                                } catch (final ActivityNotFoundException e) {
                                    // Handle so we shouldn't crash if the wireless alerts
                                    // implementation is broken.
                                    LogUtil.e(LogUtil.BUGLE_TAG,
                                            "Failed to launch wireless alerts activity", e);
                                }
                                return true;
                            }
                        });
            }

            // We want to disable preferences if we are not the default app, but we do all of the
            // above first so that the user sees the correct information on the screen
            if (!PhoneUtils.getDefault().isDefaultSmsApp()) {
                mGroupMmsPreference.setEnabled(false);
                final Preference autoRetrieveMmsPreference =
                        findPreference(getString(R.string.auto_retrieve_mms_pref_key));
                autoRetrieveMmsPreference.setEnabled(false);
                final Preference deliveryReportsPreference =
                        findPreference(getString(R.string.delivery_reports_pref_key));
                if (deliveryReportsPreference != null) {
                    deliveryReportsPreference.setEnabled(false);
                }
            }

            if (advancedCategory.getPreferenceCount() == 0) {
                getPreferenceScreen().removePreference(advancedCategory);
            }
        }

        private boolean isCellBroadcastAppLinkEnabled() {
            if (!MmsConfig.get(mSubId).getShowCellBroadcast()) {
                return false;
            }
            try {
                final PackageManager pm = getActivity().getPackageManager();
                return pm.getApplicationEnabledSetting(UIIntents.CMAS_COMPONENT)
                        != PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            } catch (final IllegalArgumentException ignored) {
                // CMAS app not installed.
            }
            return false;
        }

        private void updateGroupMmsPrefSummary() {
            final boolean groupMmsEnabled = getPreferenceScreen().getSharedPreferences().getBoolean(
                    mGroupMmsPrefKey, getResources().getBoolean(R.bool.group_mms_pref_default));
            mGroupMmsPreference.setSummary(groupMmsEnabled ?
                    R.string.enable_group_mms : R.string.disable_group_mms);
        }

        @Override
        public void onResume() {
            super.onResume();
            getPreferenceScreen().getSharedPreferences()
                    .registerOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences,
                final String key) {
            if (key.equals(mGroupMmsPrefKey)) {
                updateGroupMmsPrefSummary();
            } else if (key.equals(mPhoneNumberKey)) {
                // Save the changed phone number in preferences specific to the sub id
                final String newPhoneNumber = mPhoneNumberPreference.getText();
                final BuglePrefs subPrefs = BuglePrefs.getSubscriptionPrefs(mSubId);
                if (TextUtils.isEmpty(newPhoneNumber)) {
                    subPrefs.remove(mPhoneNumberKey);
                } else {
                    subPrefs.putString(getString(R.string.mms_phone_number_pref_key),
                            newPhoneNumber);
                }
                // Update the self participants so the new phone number will be reflected
                // everywhere in the UI.
                ParticipantRefresh.refreshSelfParticipants();
            }
        }

        @Override
        public void onPause() {
            super.onPause();
            getPreferenceScreen().getSharedPreferences()
                    .unregisterOnSharedPreferenceChangeListener(this);
        }
    }
}
