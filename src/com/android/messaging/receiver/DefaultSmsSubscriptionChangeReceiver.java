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
package com.android.messaging.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.SubscriptionManager;

import com.android.messaging.datamodel.ParticipantRefresh;

/**
 * Responds to default SMS subscription selection changes from system Settings.
 */
public class DefaultSmsSubscriptionChangeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (SubscriptionManager.ACTION_DEFAULT_SMS_SUBSCRIPTION_CHANGED.equals(intent.getAction())) {
            ParticipantRefresh.refreshSelfParticipants();
        }
    }
}
