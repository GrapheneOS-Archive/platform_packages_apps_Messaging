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

import android.app.Activity;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.UserManager;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.provider.Telephony;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.messaging.R;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.sms.ApnDatabase;
import com.android.messaging.sms.BugleApnSettingsLoader;
import com.android.messaging.ui.BugleActionBarActivity;
import com.android.messaging.ui.UIIntents;
import com.android.messaging.util.PhoneUtils;

import androidx.core.app.NavUtils;

public class ApnSettingsActivity extends BugleActionBarActivity {
    private static final int DIALOG_RESTORE_DEFAULTAPN = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        // Display the fragment as the main content.
        final ApnSettingsFragment fragment = new ApnSettingsFragment();
        fragment.setSubId(getIntent().getIntExtra(UIIntents.UI_INTENT_EXTRA_SUB_ID,
                ParticipantData.DEFAULT_SELF_SUB_ID));
        getFragmentManager().beginTransaction()
                .replace(android.R.id.content, fragment)
                .commit();
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

    @Override
    protected Dialog onCreateDialog(int id) {
        if (id == DIALOG_RESTORE_DEFAULTAPN) {
            ProgressDialog dialog = new ProgressDialog(this);
            dialog.setMessage(getResources().getString(R.string.restore_default_apn));
            dialog.setCancelable(false);
            return dialog;
        }
        return null;
    }

    public static class ApnSettingsFragment extends PreferenceFragment implements
            Preference.OnPreferenceChangeListener {
        public static final String EXTRA_POSITION = "position";

        public static final String APN_ID = "apn_id";

        private static final String[] APN_PROJECTION = {
            Telephony.Carriers._ID,         // 0
            Telephony.Carriers.NAME,        // 1
            Telephony.Carriers.APN,         // 2
            Telephony.Carriers.TYPE         // 3
        };
        private static final int ID_INDEX    = 0;
        private static final int NAME_INDEX  = 1;
        private static final int APN_INDEX   = 2;
        private static final int TYPES_INDEX = 3;

        private static final int MENU_NEW = Menu.FIRST;
        private static final int MENU_RESTORE = Menu.FIRST + 1;

        private static final int EVENT_RESTORE_DEFAULTAPN_START = 1;
        private static final int EVENT_RESTORE_DEFAULTAPN_COMPLETE = 2;

        private static boolean mRestoreDefaultApnMode;

        private RestoreApnUiHandler mRestoreApnUiHandler;
        private RestoreApnProcessHandler mRestoreApnProcessHandler;
        private HandlerThread mRestoreDefaultApnThread;

        private String mSelectedKey;

        private static final ContentValues sCurrentNullMap;
        private static final ContentValues sCurrentSetMap;

        private UserManager mUm;

        private boolean mUnavailable;
        private int mSubId;

        static {
            sCurrentNullMap = new ContentValues(1);
            sCurrentNullMap.putNull(Telephony.Carriers.CURRENT);

            sCurrentSetMap = new ContentValues(1);
            sCurrentSetMap.put(Telephony.Carriers.CURRENT, "2");    // 2 for user-selected APN,
            // 1 for Bugle-selected APN
        }

        private SQLiteDatabase mDatabase;

        public void setSubId(final int subId) {
            mSubId = subId;
        }

        @Override
        public void onCreate(Bundle icicle) {
            super.onCreate(icicle);

            mDatabase = ApnDatabase.getApnDatabase().getWritableDatabase();

            mUm = (UserManager) getActivity().getSystemService(Context.USER_SERVICE);
            if (!mUm.hasUserRestriction(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)) {
                setHasOptionsMenu(true);
            }
        }

        @Override
        public void onActivityCreated(Bundle savedInstanceState) {
            super.onActivityCreated(savedInstanceState);

            final ListView lv = (ListView) getView().findViewById(android.R.id.list);
            TextView empty = (TextView) getView().findViewById(android.R.id.empty);
            if (empty != null) {
                empty.setText(R.string.apn_settings_not_available);
                lv.setEmptyView(empty);
            }

            if (mUm.hasUserRestriction(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)) {
                mUnavailable = true;
                setPreferenceScreen(getPreferenceManager().createPreferenceScreen(getActivity()));
                return;
            }

            addPreferencesFromResource(R.xml.apn_settings);

            lv.setItemsCanFocus(true);
        }

        @Override
        public void onResume() {
            super.onResume();

            if (mUnavailable) {
                return;
            }

            if (!mRestoreDefaultApnMode) {
                fillList();
            }
        }

        @Override
        public void onPause() {
            super.onPause();

            if (mUnavailable) {
                return;
            }
        }

        @Override
        public void onDestroy() {
            super.onDestroy();

            if (mRestoreDefaultApnThread != null) {
                mRestoreDefaultApnThread.quit();
            }
        }

        private void fillList() {
            final String mccMnc = PhoneUtils.getMccMncString(PhoneUtils.get(mSubId).getMccMnc());

            new AsyncTask<Void, Void, Cursor>() {
                @Override
                protected Cursor doInBackground(Void... params) {
                    String selection = Telephony.Carriers.NUMERIC + " =?";
                    String[] selectionArgs = new String[]{ mccMnc };
                    final Cursor cursor = mDatabase.query(ApnDatabase.APN_TABLE, APN_PROJECTION,
                            selection, selectionArgs, null, null, null, null);
                    return cursor;
                }

                @Override
                protected void onPostExecute(Cursor cursor) {
                    if (cursor != null) {
                        try {
                            PreferenceGroup apnList = (PreferenceGroup)
                                    findPreference(getString(R.string.apn_list_pref_key));
                            apnList.removeAll();

                            mSelectedKey = BugleApnSettingsLoader.getFirstTryApn(mDatabase, mccMnc);
                            while (cursor.moveToNext()) {
                                String name = cursor.getString(NAME_INDEX);
                                String apn = cursor.getString(APN_INDEX);
                                String key = cursor.getString(ID_INDEX);
                                String type = cursor.getString(TYPES_INDEX);

                                if (BugleApnSettingsLoader.isValidApnType(type,
                                        BugleApnSettingsLoader.APN_TYPE_MMS)) {
                                    ApnPreference pref = new ApnPreference(getActivity());
                                    pref.setKey(key);
                                    pref.setTitle(name);
                                    pref.setSummary(apn);
                                    pref.setPersistent(false);
                                    pref.setOnPreferenceChangeListener(ApnSettingsFragment.this);
                                    pref.setSelectable(true);

                                    // Turn on the radio button for the currently selected APN. If
                                    // there is no selected APN, don't select an APN.
                                    if ((mSelectedKey != null && mSelectedKey.equals(key))) {
                                        pref.setChecked();
                                    }
                                    apnList.addPreference(pref);
                                }
                            }
                        } finally {
                            cursor.close();
                        }
                    }
                }
            }.execute((Void) null);
        }

        @Override
        public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
            if (!mUnavailable) {
                menu.add(0, MENU_NEW, 0,
                        getResources().getString(R.string.menu_new_apn))
                        .setIcon(R.drawable.ic_add_white)
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
                menu.add(0, MENU_RESTORE, 0,
                        getResources().getString(R.string.menu_restore_default_apn))
                        .setIcon(android.R.drawable.ic_menu_upload);
            }

            super.onCreateOptionsMenu(menu, inflater);
        }

        @Override
        public boolean onOptionsItemSelected(MenuItem item) {
            switch (item.getItemId()) {
                case MENU_NEW:
                    addNewApn();
                    return true;

                case MENU_RESTORE:
                    restoreDefaultApn();
                    return true;
            }
            return super.onOptionsItemSelected(item);
        }

        private void addNewApn() {
            startActivity(UIIntents.get().getApnEditorIntent(getActivity(), null, mSubId));
        }

        @Override
        public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen,
                Preference preference) {
            startActivity(
                    UIIntents.get().getApnEditorIntent(getActivity(), preference.getKey(), mSubId));
            return true;
        }

        @Override
        public boolean onPreferenceChange(Preference preference, Object newValue) {
            if (newValue instanceof String) {
                setSelectedApnKey((String) newValue);
            }

            return true;
        }

        // current=2 means user selected APN
        private static final String UPDATE_SELECTION = Telephony.Carriers.CURRENT + " =?";
        private static final String[] UPDATE_SELECTION_ARGS = new String[] { "2" };
        private void setSelectedApnKey(final String key) {
            mSelectedKey = key;

            // Make database changes not on the UI thread
            new AsyncTask<Void, Void, Void>() {
                @Override
                protected Void doInBackground(Void... params) {
                    // null out the previous "current=2" APN
                    mDatabase.update(ApnDatabase.APN_TABLE, sCurrentNullMap,
                            UPDATE_SELECTION, UPDATE_SELECTION_ARGS);

                    // set the new "current" APN (2)
                    String selection = Telephony.Carriers._ID + " =?";
                    String[] selectionArgs = new String[]{ key };

                    mDatabase.update(ApnDatabase.APN_TABLE, sCurrentSetMap,
                            selection, selectionArgs);
                    return null;
                }
            }.execute((Void) null);
        }

        private boolean restoreDefaultApn() {
            getActivity().showDialog(DIALOG_RESTORE_DEFAULTAPN);
            mRestoreDefaultApnMode = true;

            if (mRestoreApnUiHandler == null) {
                mRestoreApnUiHandler = new RestoreApnUiHandler();
            }

            if (mRestoreApnProcessHandler == null ||
                    mRestoreDefaultApnThread == null) {
                mRestoreDefaultApnThread = new HandlerThread(
                        "Restore default APN Handler: Process Thread");
                mRestoreDefaultApnThread.start();
                mRestoreApnProcessHandler = new RestoreApnProcessHandler(
                        mRestoreDefaultApnThread.getLooper(), mRestoreApnUiHandler);
            }

            mRestoreApnProcessHandler.sendEmptyMessage(EVENT_RESTORE_DEFAULTAPN_START);
            return true;
        }

        private class RestoreApnUiHandler extends Handler {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case EVENT_RESTORE_DEFAULTAPN_COMPLETE:
                        fillList();
                        getPreferenceScreen().setEnabled(true);
                        mRestoreDefaultApnMode = false;
                        final Activity activity = getActivity();
                        activity.dismissDialog(DIALOG_RESTORE_DEFAULTAPN);
                        Toast.makeText(activity, getResources().getString(
                                        R.string.restore_default_apn_completed), Toast.LENGTH_LONG)
                                            .show();
                        break;
                }
            }
        }

        private class RestoreApnProcessHandler extends Handler {
            private Handler mCachedRestoreApnUiHandler;

            public RestoreApnProcessHandler(Looper looper, Handler restoreApnUiHandler) {
                super(looper);
                this.mCachedRestoreApnUiHandler = restoreApnUiHandler;
            }

            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case EVENT_RESTORE_DEFAULTAPN_START:
                        ApnDatabase.forceBuildAndLoadApnTables();
                        mCachedRestoreApnUiHandler.sendEmptyMessage(
                                EVENT_RESTORE_DEFAULTAPN_COMPLETE);
                        break;
                }
            }
        }
    }
}
