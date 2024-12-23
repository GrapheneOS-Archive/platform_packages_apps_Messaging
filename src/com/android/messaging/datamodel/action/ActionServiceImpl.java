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

package com.android.messaging.datamodel.action;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.core.app.JobIntentService;

import com.android.messaging.Factory;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.LoggingTimer;
import com.google.common.annotations.VisibleForTesting;

/**
 * ActionService used to perform background processing for data model
 */
public class ActionServiceImpl extends JobIntentService {
    private static final String TAG = LogUtil.BUGLE_DATAMODEL_TAG;
    private static final boolean VERBOSE = false;

    /**
     * Unique job ID for this service.
     */
    public static final int JOB_ID = 1000;

    public ActionServiceImpl() {
        super();
    }

    /**
     * Start action by sending intent to the service
     * @param action - action to start
     */
    protected static void startAction(final Action action) {
        final Intent intent = makeIntent(OP_START_ACTION);
        final Bundle actionBundle = new Bundle();
        actionBundle.putParcelable(BUNDLE_ACTION, action);
        intent.putExtra(EXTRA_ACTION_BUNDLE, actionBundle);
        action.markStart();
        startServiceWithIntent(intent);
    }

    /**
     * Schedule an action to run after specified delay using alarm manager to send pendingintent
     * @param action - action to start
     * @param requestCode - request code used to collapse requests
     * @param delayMs - delay in ms (from now) before action will start
     */
    protected static void scheduleAction(final Action action, final int requestCode,
            final long delayMs) {
        final Intent intent = PendingActionReceiver.makeIntent(OP_START_ACTION);
        final Bundle actionBundle = new Bundle();
        actionBundle.putParcelable(BUNDLE_ACTION, action);
        intent.putExtra(EXTRA_ACTION_BUNDLE, actionBundle);

        PendingActionReceiver.scheduleAlarm(intent, requestCode, delayMs);
    }

    /**
     * Handle response returned by BackgroundWorker
     * @param request - request generating response
     * @param response - response from service
     */
    protected static void handleResponseFromBackgroundWorker(final Action action,
            final Bundle response) {
        final Intent intent = makeIntent(OP_RECEIVE_BACKGROUND_RESPONSE);

        final Bundle actionBundle = new Bundle();
        actionBundle.putParcelable(BUNDLE_ACTION, action);
        intent.putExtra(EXTRA_ACTION_BUNDLE, actionBundle);
        intent.putExtra(EXTRA_WORKER_RESPONSE, response);

        startServiceWithIntent(intent);
    }

    /**
     * Handle response returned by BackgroundWorker
     * @param request - request generating failure
     */
    protected static void handleFailureFromBackgroundWorker(final Action action,
            final Exception exception) {
        final Intent intent = makeIntent(OP_RECEIVE_BACKGROUND_FAILURE);

        final Bundle actionBundle = new Bundle();
        actionBundle.putParcelable(BUNDLE_ACTION, action);
        intent.putExtra(EXTRA_ACTION_BUNDLE, actionBundle);
        intent.putExtra(EXTRA_WORKER_EXCEPTION, exception);

        startServiceWithIntent(intent);
    }

    // ops
    @VisibleForTesting
    protected static final int OP_START_ACTION = 200;
    @VisibleForTesting
    protected static final int OP_RECEIVE_BACKGROUND_RESPONSE = 201;
    @VisibleForTesting
    protected static final int OP_RECEIVE_BACKGROUND_FAILURE = 202;

    // extras
    @VisibleForTesting
    protected static final String EXTRA_OP_CODE = "op";
    @VisibleForTesting
    protected static final String EXTRA_ACTION_BUNDLE = "datamodel_action_bundle";
    @VisibleForTesting
    protected static final String EXTRA_WORKER_EXCEPTION = "worker_exception";
    @VisibleForTesting
    protected static final String EXTRA_WORKER_RESPONSE = "worker_response";
    @VisibleForTesting
    protected static final String EXTRA_WORKER_UPDATE = "worker_update";
    @VisibleForTesting
    protected static final String BUNDLE_ACTION = "bundle_action";

    private BackgroundWorker mBackgroundWorker;

    /**
     * Allocate an intent with a specific opcode.
     */
    private static Intent makeIntent(final int opcode) {
        final Intent intent = new Intent(Factory.get().getApplicationContext(),
                ActionServiceImpl.class);
        intent.putExtra(EXTRA_OP_CODE, opcode);
        return intent;
    }

    /**
     * Broadcast receiver for alarms scheduled through ActionService.
     */
    public static class PendingActionReceiver extends BroadcastReceiver {
        static final String ACTION = "com.android.messaging.datamodel.PENDING_ACTION";

        /**
         * Allocate an intent with a specific opcode and alarm action.
         */
        public static Intent makeIntent(final int opcode) {
            final Intent intent = new Intent(Factory.get().getApplicationContext(),
                    PendingActionReceiver.class);
            intent.setAction(ACTION);
            intent.putExtra(EXTRA_OP_CODE, opcode);
            return intent;
        }

        public static void scheduleAlarm(final Intent intent, final int requestCode,
                final long delayMs) {
            final Context context = Factory.get().getApplicationContext();
            final PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context, requestCode, intent, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            final AlarmManager mgr =
                    (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

            if (delayMs < Long.MAX_VALUE) {
                mgr.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + delayMs, pendingIntent);
            } else {
                mgr.cancel(pendingIntent);
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void onReceive(final Context context, final Intent intent) {
            ActionServiceImpl.startServiceWithIntent(intent);
        }
    }

    /**
     * Creates a pending intent that will trigger a data model action when the intent is
     * triggered
     */
    public static PendingIntent makeStartActionPendingIntent(final Context context,
            final Action action, final int requestCode, final boolean launchesAnActivity) {
        final Intent intent = PendingActionReceiver.makeIntent(OP_START_ACTION);
        final Bundle actionBundle = new Bundle();
        actionBundle.putParcelable(BUNDLE_ACTION, action);
        intent.putExtra(EXTRA_ACTION_BUNDLE, actionBundle);
        if (launchesAnActivity) {
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        }
        return PendingIntent.getBroadcast(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onCreate() {
        super.onCreate();
        mBackgroundWorker = DataModel.get().getBackgroundWorkerForActionService();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    /**
     * Queue intent to the ActionService.
     */
    private static void startServiceWithIntent(final Intent intent) {
        final Context context = Factory.get().getApplicationContext();
        final int opcode = intent.getIntExtra(EXTRA_OP_CODE, 0);
        intent.setClass(context, ActionServiceImpl.class);
        enqueueWork(context, intent);
    }

    public static void enqueueWork(Context context, Intent work) {
        enqueueWork(context, ActionServiceImpl.class, JOB_ID, work);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onHandleWork(final Intent intent) {
        if (intent == null) {
            // Shouldn't happen but sometimes does following another crash.
            LogUtil.w(TAG, "ActionService.onHandleIntent: Called with null intent");
            return;
        }
        final int opcode = intent.getIntExtra(EXTRA_OP_CODE, 0);

        Action action;
        final Bundle actionBundle = intent.getBundleExtra(EXTRA_ACTION_BUNDLE);
        actionBundle.setClassLoader(getClassLoader());
        switch(opcode) {
            case OP_START_ACTION: {
                action = (Action) actionBundle.getParcelable(BUNDLE_ACTION);
                executeAction(action);
                break;
            }

            case OP_RECEIVE_BACKGROUND_RESPONSE: {
                action = (Action) actionBundle.getParcelable(BUNDLE_ACTION);
                final Bundle response = intent.getBundleExtra(EXTRA_WORKER_RESPONSE);
                processBackgroundResponse(action, response);
                break;
            }

            case OP_RECEIVE_BACKGROUND_FAILURE: {
                action = (Action) actionBundle.getParcelable(BUNDLE_ACTION);
                processBackgroundFailure(action);
                break;
            }

            default:
                throw new RuntimeException("Unrecognized opcode in ActionServiceImpl");
        }

        action.sendBackgroundActions(mBackgroundWorker);
    }

    private static final long EXECUTION_TIME_WARN_LIMIT_MS = 1000; // 1 second
    /**
     * Local execution of action on ActionService thread
     */
    private void executeAction(final Action action) {
        action.markBeginExecute();

        final LoggingTimer timer = createLoggingTimer(action, "#executeAction");
        timer.start();

        final Object result = action.executeAction();

        timer.stopAndLog();

        action.markEndExecute(result);
    }

    /**
     * Process response on ActionService thread
     */
    private void processBackgroundResponse(final Action action, final Bundle response) {
        final LoggingTimer timer = createLoggingTimer(action, "#processBackgroundResponse");
        timer.start();

        action.processBackgroundWorkResponse(response);

        timer.stopAndLog();
    }

    /**
     * Process failure on ActionService thread
     */
    private void processBackgroundFailure(final Action action) {
        final LoggingTimer timer = createLoggingTimer(action, "#processBackgroundFailure");
        timer.start();

        action.processBackgroundWorkFailure();

        timer.stopAndLog();
    }

    private static LoggingTimer createLoggingTimer(
            final Action action, final String methodName) {
        return new LoggingTimer(TAG, action.getClass().getSimpleName() + methodName,
                EXECUTION_TIME_WARN_LIMIT_MS);
    }
}
