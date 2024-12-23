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

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.res.Resources;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.android.messaging.Factory;
import com.android.messaging.R;
import com.android.messaging.ui.UIIntents;
import com.android.messaging.util.NotificationChannelUtil;
import com.android.messaging.util.PendingIntentConstants;
import com.android.messaging.util.PhoneUtils;

/**
 * Class that handles SMS auto delete and notification when storage is low
 */
public class SmsStorageStatusManager {
    /**
     * Handles storage low signal for SMS
     */
    public static void handleStorageLow() {
        if (!PhoneUtils.getDefault().isSmsEnabled()) {
            return;
        }

        // TODO: Auto-delete messages, when that setting exists and is enabled

        // Notify low storage for SMS
        postStorageLowNotification();
    }

    /**
     * Handles storage OK signal for SMS
     */
    public static void handleStorageOk() {
        if (!PhoneUtils.getDefault().isSmsEnabled()) {
            return;
        }
        cancelStorageLowNotification();
    }

    /**
     * Post sms storage low notification
     */
    private static void postStorageLowNotification() {
        final Context context = Factory.get().getApplicationContext();
        final Resources resources = context.getResources();
        final PendingIntent pendingIntent = UIIntents.get()
                .getPendingIntentForLowStorageNotifications(context);

        final NotificationCompat.Builder builder =
                new NotificationCompat.Builder(context, NotificationChannelUtil.ALERTS);
        builder.setContentTitle(resources.getString(R.string.sms_storage_low_title))
                .setTicker(resources.getString(R.string.sms_storage_low_notification_ticker))
                .setSmallIcon(R.drawable.ic_failed_light)
                .setPriority(Notification.PRIORITY_DEFAULT)
                .setOngoing(true)      // Can't be swiped off
                .setAutoCancel(false)  // Don't auto cancel
                .setContentIntent(pendingIntent);

        final NotificationCompat.BigTextStyle bigTextStyle =
                new NotificationCompat.BigTextStyle(builder);
        bigTextStyle.bigText(resources.getString(R.string.sms_storage_low_text));
        final Notification notification = bigTextStyle.build();

        final NotificationManagerCompat notificationManager =
                NotificationManagerCompat.from(Factory.get().getApplicationContext());

        notificationManager.notify(getNotificationTag(),
                PendingIntentConstants.SMS_STORAGE_LOW_NOTIFICATION_ID, notification);
    }

    /**
     * Cancel the notification
     */
    public static void cancelStorageLowNotification() {
        final NotificationManagerCompat notificationManager =
                NotificationManagerCompat.from(Factory.get().getApplicationContext());
        notificationManager.cancel(getNotificationTag(),
                PendingIntentConstants.SMS_STORAGE_LOW_NOTIFICATION_ID);
    }

    private static String getNotificationTag() {
        return Factory.get().getApplicationContext().getPackageName() + ":smsstoragelow";
    }
}
