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
import android.provider.Telephony;

import com.android.messaging.datamodel.action.ReceiveMmsMessageAction;
import com.android.messaging.util.ContentType;
import com.android.messaging.util.PhoneUtils;

/**
 * Class that handles MMS WAP push intent from telephony.
 */
public class MmsWapPushDeliverReceiver extends BroadcastReceiver {

    static final String EXTRA_SUBSCRIPTION = "subscription";
    static final String EXTRA_DATA = "data";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION.equals(intent.getAction())
                && ContentType.MMS_MESSAGE.equals(intent.getType())) {
            // Always convert negative subIds into -1
            int subId = PhoneUtils.getDefault().getEffectiveIncomingSubIdFromSystem(
                    intent, EXTRA_SUBSCRIPTION);
            byte[] data = intent.getByteArrayExtra(EXTRA_DATA);
            if (!PhoneUtils.getDefault().isSmsEnabled()) {
                return;
            }

            final ReceiveMmsMessageAction action = new ReceiveMmsMessageAction(subId, data);
            action.start();
        }
    }
}
