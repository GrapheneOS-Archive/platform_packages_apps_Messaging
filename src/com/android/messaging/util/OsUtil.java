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

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.UserHandle;
import android.os.UserManager;
import androidx.core.os.BuildCompat;

import com.android.messaging.Factory;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Set;

/**
 * Android OS version utilities
 */
public class OsUtil {
    private static boolean sIsAtLeastICS_MR1;
    private static boolean sIsAtLeastJB;
    private static boolean sIsAtLeastJB_MR1;
    private static boolean sIsAtLeastJB_MR2;
    private static boolean sIsAtLeastKLP;
    private static boolean sIsAtLeastL;
    private static boolean sIsAtLeastL_MR1;
    private static boolean sIsAtLeastM;
    private static boolean sIsAtLeastN;

    private static Boolean sIsSecondaryUser = null;

    static {
        final int v = getApiVersion();
        sIsAtLeastICS_MR1 = v >= android.os.Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1;
        sIsAtLeastJB = v >= android.os.Build.VERSION_CODES.JELLY_BEAN;
        sIsAtLeastJB_MR1 = v >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1;
        sIsAtLeastJB_MR2 = v >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR2;
        sIsAtLeastKLP = v >= android.os.Build.VERSION_CODES.KITKAT;
        sIsAtLeastL = v >= android.os.Build.VERSION_CODES.LOLLIPOP;
        sIsAtLeastL_MR1 = v >= android.os.Build.VERSION_CODES.LOLLIPOP_MR1;
        sIsAtLeastM = v >= android.os.Build.VERSION_CODES.M;
        sIsAtLeastN = BuildCompat.isAtLeastN();
    }

    /**
     * @return True if the version of Android that we're running on is at least Ice Cream Sandwich
     *  MR1 (API level 15).
     */
    public static boolean isAtLeastICS_MR1() {
        return sIsAtLeastICS_MR1;
    }

    /**
     * @return True if the version of Android that we're running on is at least Jelly Bean
     *  (API level 16).
     */
    public static boolean isAtLeastJB() {
        return sIsAtLeastJB;
    }

    /**
     * @return True if the version of Android that we're running on is at least Jelly Bean MR1
     *  (API level 17).
     */
    public static boolean isAtLeastJB_MR1() {
        return sIsAtLeastJB_MR1;
    }

    /**
     * @return True if the version of Android that we're running on is at least Jelly Bean MR2
     *  (API level 18).
     */
    public static boolean isAtLeastJB_MR2() {
        return sIsAtLeastJB_MR2;
    }

    /**
     * @return True if the version of Android that we're running on is at least KLP
     *  (API level 19).
     */
    public static boolean isAtLeastKLP() {
        return sIsAtLeastKLP;
    }

    /**
     * @return True if the version of Android that we're running on is at least L
     *  (API level 21).
     */
    public static boolean isAtLeastL() {
        return sIsAtLeastL;
    }

    /**
     * @return True if the version of Android that we're running on is at least L MR1
     *  (API level 22).
     */
    public static boolean isAtLeastL_MR1() {
        return sIsAtLeastL_MR1;
    }

    /**
     * @return True if the version of Android that we're running on is at least M
     *  (API level 23).
     */
    public static boolean isAtLeastM() {
        return sIsAtLeastM;
    }

    /**
     * @return True if the version of Android that we're running on is at least N
     *  (API level 24).
     */
    public static boolean isAtLeastN() {
        return sIsAtLeastN;
    }

    /**
     * @return The Android API version of the OS that we're currently running on.
     */
    public static int getApiVersion() {
        return android.os.Build.VERSION.SDK_INT;
    }

    public static boolean isSecondaryUser() {
        if (sIsSecondaryUser == null) {
            final Context context = Factory.get().getApplicationContext();
            boolean isSecondaryUser = false;

            // Only check for newer devices (but not the nexus 10)
            if (OsUtil.sIsAtLeastJB_MR1 && !"Nexus 10".equals(Build.MODEL)) {
                final UserHandle uh = android.os.Process.myUserHandle();
                final UserManager userManager =
                        (UserManager) context.getSystemService(Context.USER_SERVICE);
                if (userManager != null) {
                    final long userSerialNumber = userManager.getSerialNumberForUser(uh);
                    isSecondaryUser = (0 != userSerialNumber);
                }
            }
            sIsSecondaryUser = isSecondaryUser;
        }
        return sIsSecondaryUser;
    }

    /**
     * Creates a joined string from a Set<String> using the given delimiter.
     * @param values
     * @param delimiter
     * @return
     */
    public static String joinFromSetWithDelimiter(
            final Set<String> values, final String delimiter) {
        if (values != null) {
            final StringBuilder joinedStringBuilder = new StringBuilder();
            boolean firstValue = true;
            for (final String value : values) {
                if (firstValue) {
                    firstValue = false;
                } else {
                    joinedStringBuilder.append(delimiter);
                }
                joinedStringBuilder.append(value);
            }
            return joinedStringBuilder.toString();
        }
        return null;
    }

    private static Hashtable<String, Integer> sPermissions = new Hashtable<String, Integer>();

    /**
     * Check if the app has the specified permission. If it does not, the app needs to use
     * {@link android.app.Activity#requestPermission}. Note that if it
     * returns true, it cannot return false in the same process as the OS kills the process when
     * any permission is revoked.
     * @param permission A permission from {@link android.Manifest.permission}
     */
    public static boolean hasPermission(final String permission) {
        if (OsUtil.isAtLeastM()) {
            // It is safe to cache the PERMISSION_GRANTED result as the process gets killed if the
            // user revokes the permission setting. However, PERMISSION_DENIED should not be
            // cached as the process does not get killed if the user enables the permission setting.
            if (!sPermissions.containsKey(permission)
                    || sPermissions.get(permission) == PackageManager.PERMISSION_DENIED) {
                final Context context = Factory.get().getApplicationContext();
                final int permissionState = context.checkSelfPermission(permission);
                sPermissions.put(permission, permissionState);
            }
            return sPermissions.get(permission) == PackageManager.PERMISSION_GRANTED;
        } else {
            return true;
        }
    }

    /** Does the app have all the specified permissions */
    public static boolean hasPermissions(final String[] permissions) {
        for (final String permission : permissions) {
            if (!hasPermission(permission)) {
                return false;
            }
        }
        return true;
    }

    public static boolean hasPhonePermission() {
        return hasPermission(Manifest.permission.READ_PHONE_STATE);
    }

    public static boolean hasSmsPermission() {
        return hasPermission(Manifest.permission.READ_SMS);
    }

    public static boolean hasLocationPermission() {
        return OsUtil.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION);
    }


    public static boolean hasReadImagesPermission() {
        return OsUtil.hasPermission(Manifest.permission.READ_MEDIA_IMAGES);
    }

    public static boolean hasReadVideoPermission() {
        return OsUtil.hasPermission(Manifest.permission.READ_MEDIA_VIDEO);
    }

    public static boolean hasReadAudioPermission() {
        return OsUtil.hasPermission(Manifest.permission.READ_MEDIA_AUDIO);
    }

    public static boolean hasRecordAudioPermission() {
        return OsUtil.hasPermission(Manifest.permission.RECORD_AUDIO);
    }

    /**
     * Returns array with the set of permissions that have not been granted from the given set.
     * The array will be empty if the app has all of the specified permissions. Note that calling
     * {@link Activity#requestPermissions} for an already granted permission can prompt the user
     * again, and its up to the app to only request permissions that are missing.
     */
    public static String[] getMissingPermissions(final String[] permissions) {
        final ArrayList<String> missingList = new ArrayList<String>();
        for (final String permission : permissions) {
            if (!hasPermission(permission)) {
                missingList.add(permission);
            }
        }

        final String[] missingArray = new String[missingList.size()];
        missingList.toArray(missingArray);
        return missingArray;
    }

    private static String[] sRequiredPermissions = new String[] {
        // Required to read existing SMS threads
        Manifest.permission.READ_SMS,
        // Required for knowing the phone number, number of SIMs, etc.
        Manifest.permission.READ_PHONE_STATE,
        // This is not strictly required, but simplifies the contact picker scenarios
        Manifest.permission.READ_CONTACTS,
    };

    /** Does the app have the minimum set of permissions required to operate. */
    public static boolean hasRequiredPermissions() {
        return hasPermissions(sRequiredPermissions);
    }

    public static String[] getMissingRequiredPermissions() {
        return getMissingPermissions(sRequiredPermissions);
    }
}
