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
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

/**
 * The default implementation of loader of UA and UAProfUrl
 */
class DefaultUserAgentInfoLoader implements UserAgentInfoLoader {
    // Default values to be used as user agent info
    private static final String DEFAULT_USER_AGENT = "Android MmsLib/1.0";
    private static final String DEFAULT_UA_PROF_URL =
            "http://www.gstatic.com/android/sms/mms_ua_profile.xml";

    private Context mContext;
    private boolean mLoaded;

    private String mUserAgent;
    private String mUAProfUrl;

    DefaultUserAgentInfoLoader(final Context context) {
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
            Log.i(MmsService.TAG, "Loaded user agent info: "
                    + "UA=" + mUserAgent + ", UAProfUrl=" + mUAProfUrl);
        }
    }

    private void loadLocked() {
        if (Utils.hasUserAgentApi()) {
            // load the MMS User agent and UaProfUrl from TelephonyManager APIs
            final TelephonyManager telephonyManager = (TelephonyManager) mContext.getSystemService(
                    Context.TELEPHONY_SERVICE);
            mUserAgent = telephonyManager.getMmsUserAgent();
            mUAProfUrl = telephonyManager.getMmsUAProfUrl();
        }
        if (TextUtils.isEmpty(mUserAgent)) {
            mUserAgent = DEFAULT_USER_AGENT;
        }
        if (TextUtils.isEmpty(mUAProfUrl)) {
            mUAProfUrl = DEFAULT_UA_PROF_URL;
        }
    }
}
