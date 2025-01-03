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

package com.android.messaging.datamodel.data;

import android.app.LoaderManager;
import android.content.Context;
import android.content.Loader;
import android.database.Cursor;
import android.os.Bundle;
import android.text.TextUtils;

import com.android.messaging.R;
import com.android.messaging.datamodel.BoundCursorLoader;
import com.android.messaging.datamodel.DatabaseHelper.ParticipantColumns;
import com.android.messaging.datamodel.MessagingContentProvider;
import com.android.messaging.datamodel.binding.BindableData;
import com.android.messaging.datamodel.binding.BindingBase;
import com.android.messaging.util.Assert;
import com.android.messaging.util.LogUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Services SettingsFragment's data needs for loading active self participants to display
 * the list of active subscriptions.
 */
public class SettingsData extends BindableData implements
        LoaderManager.LoaderCallbacks<Cursor> {
    public interface SettingsDataListener {
        void onSelfParticipantDataLoaded(SettingsData data);
    }

    public static class SettingsItem {
        public static final int TYPE_GENERAL_SETTINGS = 1;
        public static final int TYPE_PER_SUBSCRIPTION_SETTINGS = 2;

        private final String mDisplayName;
        private final String mDisplayDetail;
        private final String mActivityTitle;
        private final int mType;
        private final int mSubId;

        private SettingsItem(final String displayName, final String displayDetail,
                final String activityTitle, final int type, final int subId) {
            mDisplayName = displayName;
            mDisplayDetail = displayDetail;
            mActivityTitle = activityTitle;
            mType = type;
            mSubId = subId;
        }

        public String getDisplayName() {
            return mDisplayName;
        }

        public String getDisplayDetail() {
            return mDisplayDetail;
        }

        public int getType() {
            return mType;
        }

        public int getSubId() {
            return mSubId;
        }

        public String getActivityTitle() {
            return mActivityTitle;
        }

        public static SettingsItem fromSelfParticipant(final Context context,
                final ParticipantData self) {
            Assert.isTrue(self.isSelf());
            Assert.isTrue(self.isActiveSubscription());
            final String displayDetail = TextUtils.isEmpty(self.getDisplayDestination()) ?
                    context.getString(R.string.sim_settings_unknown_number) :
                        self.getDisplayDestination();
            final String displayName = context.getString(R.string.sim_specific_settings,
                    self.getSubscriptionName());
            return new SettingsItem(displayName, displayDetail, displayName,
                    TYPE_PER_SUBSCRIPTION_SETTINGS, self.getSubId());
        }

        public static SettingsItem createGeneralSettingsItem(final Context context) {
            return new SettingsItem(context.getString(R.string.general_settings),
                    null, context.getString(R.string.general_settings_activity_title),
                    TYPE_GENERAL_SETTINGS, -1);
        }

        public static SettingsItem createDefaultMmsSettingsItem(final Context context,
                final int subId) {
            return new SettingsItem(context.getString(R.string.advanced_settings),
                    null, context.getString(R.string.advanced_settings_activity_title),
                    TYPE_PER_SUBSCRIPTION_SETTINGS, subId);
        }
    }

    private static final String BINDING_ID = "bindingId";
    private final Context mContext;
    private final SelfParticipantsData mSelfParticipantsData;
    private LoaderManager mLoaderManager;
    private SettingsDataListener mListener;

    public SettingsData(final Context context,
            final SettingsDataListener listener) {
        mListener = listener;
        mContext = context;
        mSelfParticipantsData = new SelfParticipantsData();
    }

    private static final int SELF_PARTICIPANT_LOADER = 1;

    @Override
    public Loader<Cursor> onCreateLoader(final int id, final Bundle args) {
        Assert.equals(SELF_PARTICIPANT_LOADER, id);
        Loader<Cursor> loader = null;

        final String bindingId = args.getString(BINDING_ID);
        // Check if data still bound to the requesting ui element
        if (isBound(bindingId)) {
            loader = new BoundCursorLoader(bindingId, mContext,
                    MessagingContentProvider.PARTICIPANTS_URI,
                    ParticipantData.ParticipantsQuery.PROJECTION,
                    ParticipantColumns.SUB_ID + " <> ?",
                    new String[] { String.valueOf(ParticipantData.OTHER_THAN_SELF_SUB_ID) },
                    null);
        } else {
            LogUtil.w(LogUtil.BUGLE_TAG, "Creating self loader after unbinding");
        }
        return loader;
    }

    @Override
    public void onLoadFinished(final Loader<Cursor> generic, final Cursor data) {
        final BoundCursorLoader loader = (BoundCursorLoader) generic;

        // Check if data still bound to the requesting ui element
        if (isBound(loader.getBindingId())) {
            mSelfParticipantsData.bind(data);
            mListener.onSelfParticipantDataLoaded(this);
        } else {
            LogUtil.w(LogUtil.BUGLE_TAG, "Self loader finished after unbinding");
        }
    }

    @Override
    public void onLoaderReset(final Loader<Cursor> generic) {
        final BoundCursorLoader loader = (BoundCursorLoader) generic;

        // Check if data still bound to the requesting ui element
        if (isBound(loader.getBindingId())) {
            mSelfParticipantsData.bind(null);
        } else {
            LogUtil.w(LogUtil.BUGLE_TAG, "Self loader reset after unbinding");
        }
    }

    public void init(final LoaderManager loaderManager,
            final BindingBase<SettingsData> binding) {
        final Bundle args = new Bundle();
        args.putString(BINDING_ID, binding.getBindingId());
        mLoaderManager = loaderManager;
        mLoaderManager.initLoader(SELF_PARTICIPANT_LOADER, args, this);
    }

    @Override
    protected void unregisterListeners() {
        mListener = null;

        // This could be null if we bind but the caller doesn't init the BindableData
        if (mLoaderManager != null) {
            mLoaderManager.destroyLoader(SELF_PARTICIPANT_LOADER);
            mLoaderManager = null;
        }
    }

    public List<SettingsItem> getSettingsItems() {
        final List<ParticipantData> selfs = mSelfParticipantsData.getSelfParticipants(true);
        final List<SettingsItem> settingsItems = new ArrayList<SettingsItem>();
        // First goes the general settings, followed by per-subscription settings.
        settingsItems.add(SettingsItem.createGeneralSettingsItem(mContext));
        // For per-subscription settings, show the actual SIM name with phone number if the
        // platorm is at least L-MR1 and there are multiple active SIMs.
        final int activeSubCountExcludingDefault =
                mSelfParticipantsData.getSelfParticipantsCountExcludingDefault(true);
        if (activeSubCountExcludingDefault > 0) {
            for (ParticipantData self : selfs) {
                if (!self.isDefaultSelf()) {
                    if (activeSubCountExcludingDefault > 1) {
                        settingsItems.add(SettingsItem.fromSelfParticipant(mContext, self));
                    } else {
                        // This is the only active non-default SIM.
                        settingsItems.add(SettingsItem.createDefaultMmsSettingsItem(mContext,
                                self.getSubId()));
                        break;
                    }
                }
            }
        } else {
            // Either pre-L-MR1, or there's no active SIM, so show the default MMS settings.
            settingsItems.add(SettingsItem.createDefaultMmsSettingsItem(mContext,
                    ParticipantData.DEFAULT_SELF_SUB_ID));
        }
        return settingsItems;
    }
}
