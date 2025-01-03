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

package com.android.messaging.util;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.UserManager;

import com.android.messaging.Factory;
import com.android.messaging.R;
import com.android.messaging.datamodel.DataModel;

/**
 * Utility class including logic to verify requirements to run Bugle and other activity startup
 * logic. Called from base Bugle activity classes.
 */
public class BugleActivityUtil {

    private static final int REQUEST_GOOGLE_PLAY_SERVICES = 0;

    /**
     * Determine if the requirements for the app to run are met. Log any Activity startup
     * analytics.
     * @param context
     * @param activity is used to launch an error Dialog if necessary
     * @return true if resume should continue normally. Returns false if some requirements to run
     * are not met.
     */
    public static boolean onActivityResume(Context context, Activity activity) {
        DataModel.get().onActivityResume();
        Factory.get().onActivityResume();

        // Validate all requirements to run are met
        return checkHasSmsPermissionsForUser(context, activity);
    }

    /**
     * Determine if the user doesn't have SMS permissions. This can happen if you are not the phone
     * owner and the owner has disabled your SMS permissions.
     * @param context is the Context used to resolve the user permissions
     * @param activity is the Activity used to launch an error Dialog if necessary
     * @return true if the user has SMS permissions, otherwise false.
     */
    private static boolean checkHasSmsPermissionsForUser(Context context, Activity activity) {
        UserManager userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        if (userManager.hasUserRestriction(UserManager.DISALLOW_SMS)) {
            new AlertDialog.Builder(activity)
                    .setMessage(R.string.requires_sms_permissions_message)
                    .setCancelable(false)
                    .setNegativeButton(R.string.requires_sms_permissions_close_button,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(final DialogInterface dialog,
                                        final int button) {
                                    System.exit(0);
                                }
                            })
                    .show();
            return false;
        }
        return true;
    }
}

