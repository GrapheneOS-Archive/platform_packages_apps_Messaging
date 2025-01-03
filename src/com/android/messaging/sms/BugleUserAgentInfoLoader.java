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
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import com.android.messaging.util.BugleGservices;
import com.android.messaging.util.BugleGservicesKeys;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.VersionUtil;

import androidx.appcompat.mms.UserAgentInfoLoader;

/**
 * User agent and UA profile URL loader
 */
public class BugleUserAgentInfoLoader implements UserAgentInfoLoader {
    private static final String DEFAULT_USER_AGENT_PREFIX = "Bugle/";

    private Context mContext;
    private boolean mLoaded;

    private String mUserAgent;
    private String mUAProfUrl;

    public BugleUserAgentInfoLoader(final Context context) {
        mContext = context;
    }

    @Override
    public String getUserAgent() {
        load();
        return mUserAgent;
    }

    @Override
    public String getUAProfUrl() {
        load();
        return mUAProfUrl;
    }

    private void load() {
        if (mLoaded) {
            return;
        }
        boolean didLoad = false;
        synchronized (this) {
            if (!mLoaded) {
                loadLocked();
                mLoaded = true;
                didLoad = true;
            }
        }
        if (didLoad) {
            LogUtil.i(LogUtil.BUGLE_TAG, "Loaded user agent info: "
                    + "UA=" + mUserAgent + ", UAProfUrl=" + mUAProfUrl);
        }
    }

    private void loadLocked() {
        // load the MMS User agent and UaProfUrl from TelephonyManager APIs
        final TelephonyManager telephonyManager = (TelephonyManager) mContext.getSystemService(
                Context.TELEPHONY_SERVICE);
        mUserAgent = telephonyManager.getMmsUserAgent();
        mUAProfUrl = telephonyManager.getMmsUAProfUrl();

        // if user agent string isn't set, use the format "Bugle/<app_version>".
        if (TextUtils.isEmpty(mUserAgent)) {
            final String simpleVersionName = VersionUtil.getInstance(mContext).getSimpleName();
            mUserAgent = DEFAULT_USER_AGENT_PREFIX + simpleVersionName;
        }
        // if the UAProfUrl isn't set, get it from Gservices
        if (TextUtils.isEmpty(mUAProfUrl)) {
            mUAProfUrl = BugleGservices.get().getString(
                    BugleGservicesKeys.MMS_UA_PROFILE_URL,
                    BugleGservicesKeys.MMS_UA_PROFILE_URL_DEFAULT);
        }
    }
}
