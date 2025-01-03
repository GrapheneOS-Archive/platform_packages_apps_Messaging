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
package com.android.messaging.ui.debug;

import android.app.Fragment;
import android.content.Context;
import android.os.Bundle;
import android.telephony.SubscriptionInfo;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;

import com.android.messaging.R;
import com.android.messaging.sms.MmsConfig;
import com.android.messaging.util.PhoneUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Show list of all MmsConfig key/value pairs and allow editing.
 */
public class DebugMmsConfigFragment extends Fragment {
    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container,
            final Bundle savedInstanceState) {
        final View fragmentView = inflater.inflate(R.layout.mms_config_debug_fragment, container,
                false);
        final ListView listView = (ListView) fragmentView.findViewById(android.R.id.list);
        final Spinner spinner = (Spinner) fragmentView.findViewById(R.id.sim_selector_debug);
        final Integer[] subIdArray = getActiveSubIds();
        ArrayAdapter<Integer> spinnerAdapter = new ArrayAdapter<Integer>(getActivity(),
                android.R.layout.simple_spinner_item, subIdArray);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(spinnerAdapter);
        spinner.setOnItemSelectedListener(new OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                listView.setAdapter(new MmsConfigAdapter(getActivity(), subIdArray[position]));

                final int[] mccmnc = PhoneUtils.get(subIdArray[position]).getMccMnc();
                // Set the title with the mcc/mnc
                final TextView title = (TextView) fragmentView.findViewById(R.id.sim_title);
                title.setText("(" + mccmnc[0] + "/" + mccmnc[1] + ") " +
                        getActivity().getString(R.string.debug_sub_id_spinner_text));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        return fragmentView;
    }

    public static Integer[] getActiveSubIds() {
        final List<SubscriptionInfo> subRecords =
                PhoneUtils.getDefault().getActiveSubscriptionInfoList();
        if (subRecords == null) {
            return new Integer[0];
        }
        final Integer[] retArray = new Integer[subRecords.size()];
        for (int i = 0; i < subRecords.size(); i++) {
            retArray[i] = subRecords.get(i).getSubscriptionId();
        }
        return retArray;
    }

    private class MmsConfigAdapter extends BaseAdapter implements
            DebugMmsConfigItemView.MmsConfigItemListener {
        private final LayoutInflater mInflater;
        private final List<String> mKeys;
        private final MmsConfig mMmsConfig;

        public MmsConfigAdapter(Context context, int subId) {
            mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            mMmsConfig = MmsConfig.get(subId);
            mKeys = new ArrayList<>(mMmsConfig.keySet());
            Iterator<String> it = mKeys.iterator();
            while (it.hasNext()) {
                // Remove a config if the MmsConfig.sKeyTypeMap doesn't have it.
                if (MmsConfig.getKeyType(it.next()) == null) {
                    it.remove();
                }
            }
            Collections.sort(mKeys);
        }

        @Override
        public View getView(final int position, final View convertView, final ViewGroup parent) {
            final DebugMmsConfigItemView view;
            if (convertView != null && convertView instanceof DebugMmsConfigItemView) {
                view = (DebugMmsConfigItemView) convertView;
            } else {
                view = (DebugMmsConfigItemView) mInflater.inflate(
                        R.layout.debug_mmsconfig_item_view, parent, false);
            }
            final String key = mKeys.get(position);
            view.bind(key,
                    MmsConfig.getKeyType(key),
                    String.valueOf(mMmsConfig.getValue(key)),
                    this);
            return view;
        }

        @Override
        public void onValueChanged(String key, String keyType, String value) {
            mMmsConfig.update(keyType, key, value);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return mKeys.size();
        }

        @Override
        public Object getItem(int position) {
            return mKeys.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }
    }
}
