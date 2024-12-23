package com.android.messaging.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.android.messaging.R

object NotificationChannelUtil {
    const val CONVERSATIONS = "Conversations"
    const val ALERTS = "Alerts"

    fun onCreate(context: Context) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CONVERSATIONS,
                context.getString(R.string.conversations_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(
                ALERTS,
                context.getString(R.string.alerts_channel),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }
}
