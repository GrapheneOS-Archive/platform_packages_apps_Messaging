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

package com.android.messaging;

import android.content.Context;
import android.os.Process;
import android.telephony.SmsManager;
import android.util.SparseArray;

import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DataModelImpl;
import com.android.messaging.datamodel.MemoryCacheManager;
import com.android.messaging.datamodel.ParticipantRefresh.ContactContentObserver;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.datamodel.media.BugleMediaCacheManager;
import com.android.messaging.datamodel.media.MediaCacheManager;
import com.android.messaging.datamodel.media.MediaResourceManager;
import com.android.messaging.sms.BugleCarrierConfigValuesLoader;
import com.android.messaging.ui.UIIntents;
import com.android.messaging.ui.UIIntentsImpl;
import com.android.messaging.util.Assert;
import com.android.messaging.util.BugleApplicationPrefs;
import com.android.messaging.util.BugleGservices;
import com.android.messaging.util.BugleGservicesImpl;
import com.android.messaging.util.BuglePrefs;
import com.android.messaging.util.BugleSubscriptionPrefs;
import com.android.messaging.util.BugleWidgetPrefs;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.MediaUtil;
import com.android.messaging.util.MediaUtilImpl;
import com.android.messaging.util.OsUtil;
import com.android.messaging.util.PhoneUtils;

import java.util.concurrent.ConcurrentHashMap;

class FactoryImpl extends Factory {
    private BugleApplication mApplication;
    private DataModel mDataModel;
    private BugleGservices mBugleGservices;
    private BugleApplicationPrefs mBugleApplicationPrefs;
    private BugleWidgetPrefs mBugleWidgetPrefs;
    private Context mApplicationContext;
    private UIIntents mUIIntents;
    private MemoryCacheManager mMemoryCacheManager;
    private MediaResourceManager mMediaResourceManager;
    private MediaCacheManager mMediaCacheManager;
    private ContactContentObserver mContactContentObserver;
    private PhoneUtils mPhoneUtils;
    private MediaUtil mMediaUtil;
    private SparseArray<BugleSubscriptionPrefs> mSubscriptionPrefs;
    private BugleCarrierConfigValuesLoader mCarrierConfigValuesLoader;

    // Cached subId->instance
    private static final ConcurrentHashMap<Integer, PhoneUtils> sPhoneUtilsInstanceCache =
            new ConcurrentHashMap<>();

    private FactoryImpl() {
    }

    public static Factory register(final Context applicationContext,
            final BugleApplication application) {
        // This only gets called once (from BugleApplication.onCreate), but its not called in tests.
        Assert.isTrue(!sRegistered);
        Assert.isNull(Factory.get());

        final FactoryImpl factory = new FactoryImpl();
        Factory.setInstance(factory);
        sRegistered = true;

        // At this point Factory is published. Services can now get initialized and depend on
        // Factory.get().
        factory.mApplication = application;
        factory.mApplicationContext = applicationContext;
        factory.mMemoryCacheManager = new MemoryCacheManager();
        factory.mMediaCacheManager = new BugleMediaCacheManager();
        factory.mMediaResourceManager = new MediaResourceManager();
        factory.mBugleGservices = new BugleGservicesImpl(applicationContext);
        factory.mBugleApplicationPrefs = new BugleApplicationPrefs(applicationContext);
        factory.mDataModel = new DataModelImpl(applicationContext);
        factory.mBugleWidgetPrefs = new BugleWidgetPrefs(applicationContext);
        factory.mUIIntents = new UIIntentsImpl();
        factory.mContactContentObserver = new ContactContentObserver();
        factory.mMediaUtil = new MediaUtilImpl();
        factory.mSubscriptionPrefs = new SparseArray<BugleSubscriptionPrefs>();
        factory.mCarrierConfigValuesLoader = new BugleCarrierConfigValuesLoader(applicationContext);

        Assert.initializeGservices(factory.mBugleGservices);
        LogUtil.initializeGservices(factory.mBugleGservices);

        if (OsUtil.hasRequiredPermissions()) {
            factory.onRequiredPermissionsAcquired();
        }

        return factory;
    }

    @Override
    public void onRequiredPermissionsAcquired() {
        if (sInitialized) {
            return;
        }
        sInitialized = true;

        mApplication.initializeSync(this);

        final Thread asyncInitialization = new Thread() {
            @Override
            public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                mApplication.initializeAsync(FactoryImpl.this);
            }
        };
        asyncInitialization.start();
    }

    @Override
    public Context getApplicationContext() {
        return mApplicationContext;
    }

    @Override
    public DataModel getDataModel() {
        return mDataModel;
    }

    @Override
    public BugleGservices getBugleGservices() {
        return mBugleGservices;
    }

    @Override
    public BuglePrefs getApplicationPrefs() {
        return mBugleApplicationPrefs;
    }

    @Override
    public BuglePrefs getWidgetPrefs() {
        return mBugleWidgetPrefs;
    }

    @Override
    public BuglePrefs getSubscriptionPrefs(int subId) {
        subId = PhoneUtils.getDefault().getEffectiveSubId(subId);
        BugleSubscriptionPrefs pref = mSubscriptionPrefs.get(subId);
        if (pref == null) {
            synchronized (this) {
                if ((pref = mSubscriptionPrefs.get(subId)) == null) {
                    pref = new BugleSubscriptionPrefs(getApplicationContext(), subId);
                    mSubscriptionPrefs.put(subId, pref);
                }
            }
        }
        return pref;
    }

    @Override
    public UIIntents getUIIntents() {
        return mUIIntents;
    }

    @Override
    public MemoryCacheManager getMemoryCacheManager() {
        return mMemoryCacheManager;
    }

    @Override
    public MediaResourceManager getMediaResourceManager() {
        return mMediaResourceManager;
    }

    @Override
    public MediaCacheManager getMediaCacheManager() {
        return mMediaCacheManager;
    }

    @Override
    public ContactContentObserver getContactContentObserver() {
        return mContactContentObserver;
    }

    @Override
    public PhoneUtils getPhoneUtils(int subId) {
        if (subId == ParticipantData.DEFAULT_SELF_SUB_ID) {
            subId = SmsManager.getDefaultSmsSubscriptionId();
        }
        if (subId < 0) {
            LogUtil.w(LogUtil.BUGLE_TAG, "getPhoneUtils(): invalid subId = " + subId);
            subId = ParticipantData.DEFAULT_SELF_SUB_ID;
        }
        PhoneUtils instance = sPhoneUtilsInstanceCache.get(subId);
        if (instance == null) {
            instance = new PhoneUtils(subId);
            sPhoneUtilsInstanceCache.putIfAbsent(subId, instance);
        }
        return instance;
    }

    @Override
    public void reclaimMemory() {
        mMemoryCacheManager.reclaimMemory();
    }

    @Override
    public void onActivityResume() {
    }

    @Override
    public MediaUtil getMediaUtil() {
        return mMediaUtil;
    }

    @Override
    public BugleCarrierConfigValuesLoader getCarrierConfigValuesLoader() {
        return mCarrierConfigValuesLoader;
    }
}
