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

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.telephony.SmsManager;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Service to execute MMS requests using deprecated legacy APIs on older platform (prior to L)
 */
public class MmsService extends Service {
    static final String TAG = "MmsLib";

    //The default number of threads allowed to run MMS requests
    private static final int DEFAULT_THREAD_POOL_SIZE = 4;
    // Delay before stopping the service
    private static final int SERVICE_STOP_DELAY_MILLIS = 2000;

    private static final String EXTRA_REQUEST = "request";
    private static final String EXTRA_MYPID = "mypid";

    private static final String WAKELOCK_ID = "mmslib_wakelock";

    /**
     * Thread pool size for each request queue
     */
    private static volatile int sThreadPoolSize = DEFAULT_THREAD_POOL_SIZE;

    /**
     * Optional wake lock to use
     */
    private static volatile boolean sUseWakeLock = true;
    private static volatile PowerManager.WakeLock sWakeLock = null;
    private static final Object sWakeLockLock = new Object();

    /**
     * Carrier configuration values loader
     */
    private static volatile CarrierConfigValuesLoader sCarrierConfigValuesLoader = null;

    /**
     * APN loader
     */
    private static volatile ApnSettingsLoader sApnSettingsLoader = null;

    /**
     * UserAgent and UA Prof URL loader
     */
    private static volatile UserAgentInfoLoader sUserAgentInfoLoader = null;

    /**
     * Set the size of thread pool for request execution.
     * Default is DEFAULT_THREAD_POOL_SIZE
     *
     * @param size thread pool size
     */
    static void setThreadPoolSize(final int size) {
        sThreadPoolSize = size;
    }

    /**
     * Set whether to use wake lock
     *
     * @param useWakeLock true to use wake lock, false otherwise
     */
    static void setUseWakeLock(final boolean useWakeLock) {
        sUseWakeLock = useWakeLock;
    }

    /**
     * Set the optional carrier config values
     *
     * @param loader the carrier config values loader
     */
    static void setCarrierConfigValuesLoader(final CarrierConfigValuesLoader loader) {
        sCarrierConfigValuesLoader = loader;
    }

    /**
     * Get the current carrier config values loader
     *
     * @return the carrier config values loader currently set
     */
    static CarrierConfigValuesLoader getCarrierConfigValuesLoader() {
        return sCarrierConfigValuesLoader;
    }

    /**
     * Set APN settings loader
     *
     * @param loader the APN settings loader
     */
    static void setApnSettingsLoader(final ApnSettingsLoader loader) {
        sApnSettingsLoader = loader;
    }

    /**
     * Get the current APN settings loader
     *
     * @return the APN settings loader currently set
     */
    static ApnSettingsLoader getApnSettingsLoader() {
        return sApnSettingsLoader;
    }

    /**
     * Set user agent info loader
     *
     * @param loader the user agent info loader
     */
    static void setUserAgentInfoLoader(final UserAgentInfoLoader loader) {
        sUserAgentInfoLoader = loader;
    }

    /**
     * Get the current user agent info loader
     *
     * @return the user agent info loader currently set
     */
    static UserAgentInfoLoader getUserAgentInfoLoader() {
        return sUserAgentInfoLoader;
    }

    /**
     * Make sure loaders are not null. Set to default if that's the case
     *
     * @param context the Context to use
     */
    private static void ensureLoaders(final Context context) {
        if (sUserAgentInfoLoader == null) {
            sUserAgentInfoLoader = new DefaultUserAgentInfoLoader(context);
        }
        if (sCarrierConfigValuesLoader == null) {
            sCarrierConfigValuesLoader = new DefaultCarrierConfigValuesLoader(context);
        }
        if (sApnSettingsLoader == null) {
            sApnSettingsLoader = new DefaultApnSettingsLoader(context);
        }
    }

    /**
     * Acquire the wake lock
     *
     * @param context the context to use
     */
    private static void acquireWakeLock(final Context context) {
        synchronized (sWakeLockLock) {
            if (sWakeLock == null) {
                final PowerManager pm =
                        (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                sWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_ID);
            }
            sWakeLock.acquire();
        }
    }

    /**
     * Release the wake lock
     */
    private static void releaseWakeLock() {
        boolean releasedEmptyWakeLock = false;
        synchronized (sWakeLockLock) {
            if (sWakeLock != null) {
                sWakeLock.release();
            } else {
                releasedEmptyWakeLock = true;
            }
        }
        if (releasedEmptyWakeLock) {
            Log.w(TAG, "Releasing empty wake lock");
        }
    }

    /**
     * Check if wake lock is not held (e.g. when service stops)
     */
    private static void verifyWakeLockNotHeld() {
        boolean wakeLockHeld = false;
        synchronized (sWakeLockLock) {
            wakeLockHeld = sWakeLock != null && sWakeLock.isHeld();
        }
        if (wakeLockHeld) {
            Log.e(TAG, "Wake lock still held!");
        }
    }

    // Remember my PID to discard restarted intent
    private static volatile int sMyPid = -1;

    /**
     * Get the current PID
     *
     * @return the current PID
     */
    private static int getMyPid() {
        if (sMyPid < 0) {
            sMyPid = Process.myPid();
        }
        return sMyPid;
    }

    /**
     * Check if the intent is coming from this process
     *
     * @param intent the incoming intent for the service
     * @return true if the intent is from the current process
     */
    private static boolean fromThisProcess(final Intent intent) {
        final int pid = intent.getIntExtra(EXTRA_MYPID, -1);
        return pid == getMyPid();
    }

    // Request execution thread pools. One thread pool for sending and one for downloading.
    // The size of the thread pool controls the parallelism of request execution.
    // See {@link setThreadPoolSize}
    private ExecutorService[] mExecutors = new ExecutorService[2];

    // Active request count
    private int mActiveRequestCount;
    // The latest intent startId, used for safely stopping service
    private int mLastStartId;

    private MmsNetworkManager mNetworkManager;

    // Handler for scheduling service stop
    private final Handler mHandler = new Handler();
    // Service stop task
    private final Runnable mServiceStopRunnable = new Runnable() {
        @Override
        public void run() {
            tryStopService();
        }
    };

    /**
     * Start the service with a request
     *
     * @param context the Context to use
     * @param request the request to start
     */
    public static void startRequest(final Context context, final MmsRequest request) {
        final boolean useWakeLock = sUseWakeLock;
        request.setUseWakeLock(useWakeLock);
        final Intent intent = new Intent(context, MmsService.class);
        intent.putExtra(EXTRA_REQUEST, request);
        intent.putExtra(EXTRA_MYPID, getMyPid());
        if (useWakeLock) {
            acquireWakeLock(context);
        }
        if (context.startService(intent) == null) {
            if (useWakeLock) {
                releaseWakeLock();
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        ensureLoaders(this);

        for (int i = 0; i < mExecutors.length; i++) {
            mExecutors[i] = Executors.newFixedThreadPool(sThreadPoolSize);
        }

        mNetworkManager = new MmsNetworkManager(this);

        synchronized (this) {
            mActiveRequestCount = 0;
            mLastStartId = -1;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        for (ExecutorService executor : mExecutors) {
            executor.shutdown();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Always remember the latest startId for use when we try releasing the service
        synchronized (this) {
            mLastStartId = startId;
        }
        boolean scheduled = false;
        if (intent != null) {
            // There is a rare situation that right after a intent is started,
            // the service gets killed. Then the service will restart with
            // the old intent which we don't want it to run since it will
            // break our assumption for wake lock. Check the process ID
            // embedded in the intent to make sure it is indeed from the
            // the current life of this service.
            if (fromThisProcess(intent)) {
                final MmsRequest request = intent.getParcelableExtra(EXTRA_REQUEST);
                if (request != null) {
                    try {
                        retainService(request, new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    request.execute(
                                            MmsService.this,
                                            mNetworkManager,
                                            getApnSettingsLoader(),
                                            getCarrierConfigValuesLoader(),
                                            getUserAgentInfoLoader());
                                } catch (Exception e) {
                                    Log.w(TAG, "Unexpected execution failure", e);
                                } finally {
                                    if (request.getUseWakeLock()) {
                                        releaseWakeLock();
                                    }
                                    releaseService();
                                }
                            }
                        });
                        scheduled = true;
                    } catch (RejectedExecutionException e) {
                        // Rare thing happened. Send back failure using the pending intent
                        // and also release the wake lock.
                        Log.w(TAG, "Executing request failed " + e);
                        request.returnResult(this, SmsManager.MMS_ERROR_UNSPECIFIED,
                                null/*response*/, 0/*httpStatusCode*/);
                        if (request.getUseWakeLock()) {
                            releaseWakeLock();
                        }
                    }
                } else {
                    Log.w(TAG, "Empty request");
                }
            } else {
                Log.w(TAG, "Got a restarted intent from previous incarnation");
            }
        } else {
            Log.w(TAG, "Empty intent");
        }
        if (!scheduled) {
            // If the request is not started successfully, we need to try shutdown the service
            // if nobody is using it.
            tryScheduleStop();
        }
        return START_NOT_STICKY;
    }

    /**
     * Retain the service for executing the request in service thread pool
     *
     * @param request The request to execute
     * @param runnable The runnable to run the request in thread pool
     */
    private void retainService(final MmsRequest request, final Runnable runnable) {
        final ExecutorService executor = getRequestExecutor(request);
        synchronized (this) {
            executor.execute(runnable);
            mActiveRequestCount++;
        }
    }

    /**
     * Release the service from the request. If nobody is using it, schedule service stop.
     */
    private void releaseService() {
        synchronized (this) {
            mActiveRequestCount--;
            if (mActiveRequestCount <= 0) {
                mActiveRequestCount = 0;
                rescheduleServiceStop();
            }
        }
    }

    /**
     * Schedule the service stop if there is no active request
     */
    private void tryScheduleStop() {
        synchronized (this) {
            if (mActiveRequestCount == 0) {
                rescheduleServiceStop();
            }
        }
    }

    /**
     * Reschedule service stop task
     */
    private void rescheduleServiceStop() {
        mHandler.removeCallbacks(mServiceStopRunnable);
        mHandler.postDelayed(mServiceStopRunnable, SERVICE_STOP_DELAY_MILLIS);
    }

    /**
     * Really try to stop the service if there is not active request
     */
    private void tryStopService() {
        Boolean stopped = null;
        synchronized (this) {
            if (mActiveRequestCount == 0) {
                stopped = stopSelfResult(mLastStartId);
            }
        }
        logServiceStop(stopped);
    }

    /**
     * Log the result of service stopping. Also check wake lock status when service stops.
     *
     * @param stopped Not empty if service stop is performed: true if really stopped, false
     *                if cancelled.
     */
    private void logServiceStop(final Boolean stopped) {
        if (stopped != null) {
            if (stopped) {
                Log.i(TAG, "Service successfully stopped");
                verifyWakeLockNotHeld();
            } else {
                Log.i(TAG, "Service stopping cancelled");
            }
        }
    }

    private ExecutorService getRequestExecutor(final MmsRequest request) {
        if (request instanceof SendRequest) {
            // Send
            return mExecutors[0];
        } else {
            // Download
            return mExecutors[1];
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
