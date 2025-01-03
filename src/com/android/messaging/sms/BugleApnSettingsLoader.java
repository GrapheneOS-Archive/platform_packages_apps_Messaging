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

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.provider.Telephony;
import androidx.appcompat.mms.ApnSettingsLoader;
import androidx.appcompat.mms.MmsManager;
import android.text.TextUtils;
import android.util.SparseArray;

import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.mmslib.SqliteWrapper;
import com.android.messaging.util.BugleGservices;
import com.android.messaging.util.BugleGservicesKeys;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.OsUtil;
import com.android.messaging.util.PhoneUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

/**
 * APN loader for default SMS SIM
 *
 * This loader tries to load APNs from 3 sources in order:
 * 1. Gservices setting
 * 2. System APN table
 * 3. Local APN table
 */
public class BugleApnSettingsLoader implements ApnSettingsLoader {
    /**
     * The base implementation of an APN
     */
    private static class BaseApn implements Apn {
        /**
         * Create a base APN from parameters
         *
         * @param typesIn the APN type field
         * @param mmscIn the APN mmsc field
         * @param proxyIn the APN mmsproxy field
         * @param portIn the APN mmsport field
         * @return an instance of base APN, or null if any of the parameter is invalid
         */
        public static BaseApn from(final String typesIn, final String mmscIn, final String proxyIn,
                final String portIn) {
            if (!isValidApnType(trimWithNullCheck(typesIn), APN_TYPE_MMS)) {
                return null;
            }
            String mmsc = trimWithNullCheck(mmscIn);
            if (TextUtils.isEmpty(mmsc)) {
                return null;
            }
            mmsc = trimV4AddrZeros(mmsc);
            try {
                new URI(mmsc);
            } catch (final URISyntaxException e) {
                return null;
            }
            String mmsProxy = trimWithNullCheck(proxyIn);
            int mmsProxyPort = 80;
            if (!TextUtils.isEmpty(mmsProxy)) {
                mmsProxy = trimV4AddrZeros(mmsProxy);
                final String portString = trimWithNullCheck(portIn);
                if (portString != null) {
                    try {
                        mmsProxyPort = Integer.parseInt(portString);
                    } catch (final NumberFormatException e) {
                        // Ignore, just use 80 to try
                    }
                }
            }
            return new BaseApn(mmsc, mmsProxy, mmsProxyPort);
        }

        private final String mMmsc;
        private final String mMmsProxy;
        private final int mMmsProxyPort;

        public BaseApn(final String mmsc, final String proxy, final int port) {
            mMmsc = mmsc;
            mMmsProxy = proxy;
            mMmsProxyPort = port;
        }

        @Override
        public String getMmsc() {
            return mMmsc;
        }

        @Override
        public String getMmsProxy() {
            return mMmsProxy;
        }

        @Override
        public int getMmsProxyPort() {
            return mMmsProxyPort;
        }

        @Override
        public void setSuccess() {
            // Do nothing
        }

        public boolean equals(final BaseApn other) {
            return TextUtils.equals(mMmsc, other.getMmsc()) &&
                    TextUtils.equals(mMmsProxy, other.getMmsProxy()) &&
                    mMmsProxyPort == other.getMmsProxyPort();
        }
    }

    /**
     * The APN represented by the local APN table row
     */
    private static class DatabaseApn implements Apn {
        private static final ContentValues CURRENT_NULL_VALUE;
        private static final ContentValues CURRENT_SET_VALUE;
        static {
            CURRENT_NULL_VALUE = new ContentValues(1);
            CURRENT_NULL_VALUE.putNull(Telephony.Carriers.CURRENT);
            CURRENT_SET_VALUE = new ContentValues(1);
            CURRENT_SET_VALUE.put(Telephony.Carriers.CURRENT, "1"); // 1 for auto selected APN
        }
        private static final String CLEAR_UPDATE_SELECTION = Telephony.Carriers.CURRENT + " =?";
        private static final String[] CLEAR_UPDATE_SELECTION_ARGS = new String[] { "1" };
        private static final String SET_UPDATE_SELECTION = Telephony.Carriers._ID + " =?";

        /**
         * Create an APN loaded from local database
         *
         * @param apns the in-memory APN list
         * @param typesIn the APN type field
         * @param mmscIn the APN mmsc field
         * @param proxyIn the APN mmsproxy field
         * @param portIn the APN mmsport field
         * @param rowId the APN's row ID in database
         * @param current the value of CURRENT column in database
         * @return an in-memory APN instance for database APN row, null if parameter invalid
         */
        public static DatabaseApn from(final List<Apn> apns, final String typesIn,
                final String mmscIn, final String proxyIn, final String portIn,
                final long rowId, final int current) {
            if (apns == null) {
                return null;
            }
            final BaseApn base = BaseApn.from(typesIn, mmscIn, proxyIn, portIn);
            if (base == null) {
                return null;
            }
            for (final ApnSettingsLoader.Apn apn : apns) {
                if (apn instanceof DatabaseApn && ((DatabaseApn) apn).equals(base)) {
                    return null;
                }
            }
            return new DatabaseApn(apns, base, rowId, current);
        }

        private final List<Apn> mApns;
        private final BaseApn mBase;
        private final long mRowId;
        private int mCurrent;

        public DatabaseApn(final List<Apn> apns, final BaseApn base, final long rowId,
                final int current) {
            mApns = apns;
            mBase = base;
            mRowId = rowId;
            mCurrent = current;
        }

        @Override
        public String getMmsc() {
            return mBase.getMmsc();
        }

        @Override
        public String getMmsProxy() {
            return mBase.getMmsProxy();
        }

        @Override
        public int getMmsProxyPort() {
            return mBase.getMmsProxyPort();
        }

        @Override
        public void setSuccess() {
            moveToListHead();
            setCurrentInDatabase();
        }

        /**
         * Try to move this APN to the head of in-memory list
         */
        private void moveToListHead() {
            // If this is being marked as a successful APN, move it to the top of the list so
            // next time it will be tried first
            boolean moved = false;
            synchronized (mApns) {
                if (mApns.get(0) != this) {
                    mApns.remove(this);
                    mApns.add(0, this);
                    moved = true;
                }
            }
            if (moved) {
                LogUtil.d(LogUtil.BUGLE_TAG, "Set APN ["
                        + "MMSC=" + getMmsc() + ", "
                        + "PROXY=" + getMmsProxy() + ", "
                        + "PORT=" + getMmsProxyPort() + "] to be first");
            }
        }

        /**
         * Try to set the APN to be CURRENT in its database table
         */
        private void setCurrentInDatabase() {
            synchronized (this) {
                if (mCurrent > 0) {
                    // Already current
                    return;
                }
                mCurrent = 1;
            }
            LogUtil.d(LogUtil.BUGLE_TAG, "Set APN @" + mRowId + " to be CURRENT in local db");
            final SQLiteDatabase database = ApnDatabase.getApnDatabase().getWritableDatabase();
            database.beginTransaction();
            try {
                // clear the previous current=1 apn
                // we don't clear current=2 apn since it is manually selected by user
                // and we should not override it.
                database.update(ApnDatabase.APN_TABLE, CURRENT_NULL_VALUE,
                        CLEAR_UPDATE_SELECTION, CLEAR_UPDATE_SELECTION_ARGS);
                // set this one to be current (1)
                database.update(ApnDatabase.APN_TABLE, CURRENT_SET_VALUE, SET_UPDATE_SELECTION,
                        new String[] { Long.toString(mRowId) });
                database.setTransactionSuccessful();
            } finally {
                database.endTransaction();
            }
        }

        public boolean equals(final BaseApn other) {
            if (other == null) {
                return false;
            }
            return mBase.equals(other);
        }
    }

    /**
     * APN_TYPE_ALL is a special type to indicate that this APN entry can
     * service all data connections.
     */
    public static final String APN_TYPE_ALL = "*";
    /** APN type for MMS traffic */
    public static final String APN_TYPE_MMS = "mms";

    private static final String[] APN_PROJECTION_SYSTEM = {
            Telephony.Carriers.TYPE,
            Telephony.Carriers.MMSC,
            Telephony.Carriers.MMSPROXY,
            Telephony.Carriers.MMSPORT,
    };
    private static final String[] APN_PROJECTION_LOCAL = {
            Telephony.Carriers.TYPE,
            Telephony.Carriers.MMSC,
            Telephony.Carriers.MMSPROXY,
            Telephony.Carriers.MMSPORT,
            Telephony.Carriers.CURRENT,
            Telephony.Carriers._ID,
    };
    private static final int COLUMN_TYPE         = 0;
    private static final int COLUMN_MMSC         = 1;
    private static final int COLUMN_MMSPROXY     = 2;
    private static final int COLUMN_MMSPORT      = 3;
    private static final int COLUMN_CURRENT      = 4;
    private static final int COLUMN_ID           = 5;

    private static final String SELECTION_APN = Telephony.Carriers.APN + "=?";
    private static final String SELECTION_CURRENT = Telephony.Carriers.CURRENT + " IS NOT NULL";
    private static final String SELECTION_NUMERIC = Telephony.Carriers.NUMERIC + "=?";
    private static final String ORDER_BY = Telephony.Carriers.CURRENT + " DESC";

    private final Context mContext;

    // Cached APNs for subIds
    private final SparseArray<List<ApnSettingsLoader.Apn>> mApnsCache;

    public BugleApnSettingsLoader(final Context context) {
        mContext = context;
        mApnsCache = new SparseArray<>();
    }

    @Override
    public List<ApnSettingsLoader.Apn> get(final String apnName) {
        final int subId = PhoneUtils.getDefault().getEffectiveSubId(
                ParticipantData.DEFAULT_SELF_SUB_ID);
        List<ApnSettingsLoader.Apn> apns;
        boolean didLoad = false;
        synchronized (this) {
            apns = mApnsCache.get(subId);
            if (apns == null) {
                apns = new ArrayList<>();
                mApnsCache.put(subId, apns);
                loadLocked(subId, apnName, apns);
                didLoad = true;
            }
        }
        if (didLoad) {
            LogUtil.i(LogUtil.BUGLE_TAG, "Loaded " + apns.size() + " APNs");
        }
        return apns;
    }

    private void loadLocked(final int subId, final String apnName, final List<Apn> apns) {
        // Try Gservices first
        loadFromGservices(apns);
        if (apns.size() > 0) {
            return;
        }
        // Try system APN table
        loadFromSystem(subId, apnName, apns);
        if (apns.size() > 0) {
            return;
        }
        // Try local APN table
        loadFromLocalDatabase(apnName, apns);
        if (apns.size() <= 0) {
            LogUtil.w(LogUtil.BUGLE_TAG, "Failed to load any APN");
        }
    }

    /**
     * Load from Gservices if APN setting is set in Gservices
     *
     * @param apns the list used to return results
     */
    private void loadFromGservices(final List<Apn> apns) {
        final BugleGservices gservices = BugleGservices.get();
        final String mmsc = gservices.getString(BugleGservicesKeys.MMS_MMSC, null);
        if (TextUtils.isEmpty(mmsc)) {
            return;
        }
        LogUtil.i(LogUtil.BUGLE_TAG, "Loading APNs from gservices");
        final String proxy = gservices.getString(BugleGservicesKeys.MMS_PROXY_ADDRESS, null);
        final int port = gservices.getInt(BugleGservicesKeys.MMS_PROXY_PORT, -1);
        final Apn apn = BaseApn.from("mms", mmsc, proxy, Integer.toString(port));
        if (apn != null) {
            apns.add(apn);
        }
    }

    /**
     * Load matching APNs from telephony provider.
     * We try different combinations of the query to work around some platform quirks.
     *
     * @param subId the SIM subId
     * @param apnName the APN name to match
     * @param apns the list used to return results
     */
    private void loadFromSystem(final int subId, final String apnName, final List<Apn> apns) {
        Uri uri;
        if (subId != MmsManager.DEFAULT_SUB_ID) {
            uri = Uri.withAppendedPath(Telephony.Carriers.CONTENT_URI, "/subId/" + subId);
        } else {
            uri = Telephony.Carriers.CONTENT_URI;
        }
        Cursor cursor = null;
        try {
            for (; ; ) {
                // Try different combinations of queries. Some would work on some platforms.
                // So we query each combination until we find one returns non-empty result.
                cursor = querySystem(uri, true/*checkCurrent*/, apnName);
                if (cursor != null) {
                    break;
                }
                cursor = querySystem(uri, false/*checkCurrent*/, apnName);
                if (cursor != null) {
                    break;
                }
                cursor = querySystem(uri, true/*checkCurrent*/, null/*apnName*/);
                if (cursor != null) {
                    break;
                }
                cursor = querySystem(uri, false/*checkCurrent*/, null/*apnName*/);
                break;
            }
        } catch (final SecurityException e) {
            // Can't access platform APN table, return directly
            return;
        }
        if (cursor == null) {
            return;
        }
        try {
            if (cursor.moveToFirst()) {
                final ApnSettingsLoader.Apn apn = BaseApn.from(
                        cursor.getString(COLUMN_TYPE),
                        cursor.getString(COLUMN_MMSC),
                        cursor.getString(COLUMN_MMSPROXY),
                        cursor.getString(COLUMN_MMSPORT));
                if (apn != null) {
                    apns.add(apn);
                }
            }
        } finally {
            cursor.close();
        }
    }

    /**
     * Query system APN table
     *
     * @param uri The APN query URL to use
     * @param checkCurrent If add "CURRENT IS NOT NULL" condition
     * @param apnName The optional APN name for query condition
     * @return A cursor of the query result. If a cursor is returned as not null, it is
     *         guaranteed to contain at least one row.
     */
    private Cursor querySystem(final Uri uri, final boolean checkCurrent, String apnName) {
        LogUtil.i(LogUtil.BUGLE_TAG, "Loading APNs from system, "
                + "checkCurrent=" + checkCurrent + " apnName=" + apnName);
        final StringBuilder selectionBuilder = new StringBuilder();
        String[] selectionArgs = null;
        if (checkCurrent) {
            selectionBuilder.append(SELECTION_CURRENT);
        }
        apnName = trimWithNullCheck(apnName);
        if (!TextUtils.isEmpty(apnName)) {
            if (selectionBuilder.length() > 0) {
                selectionBuilder.append(" AND ");
            }
            selectionBuilder.append(SELECTION_APN);
            selectionArgs = new String[] { apnName };
        }
        try {
            final Cursor cursor = SqliteWrapper.query(
                    mContext,
                    mContext.getContentResolver(),
                    uri,
                    APN_PROJECTION_SYSTEM,
                    selectionBuilder.toString(),
                    selectionArgs,
                    null/*sortOrder*/);
            if (cursor == null || cursor.getCount() < 1) {
                if (cursor != null) {
                    cursor.close();
                }
                LogUtil.w(LogUtil.BUGLE_TAG, "Query " + uri + " with apn " + apnName + " and "
                        + (checkCurrent ? "checking CURRENT" : "not checking CURRENT")
                        + " returned empty");
                return null;
            }
            return cursor;
        } catch (final SQLiteException e) {
            LogUtil.w(LogUtil.BUGLE_TAG, "APN table query exception: " + e);
        } catch (final SecurityException e) {
            LogUtil.w(LogUtil.BUGLE_TAG, "Platform restricts APN table access: " + e);
            throw e;
        }
        return null;
    }

    /**
     * Load matching APNs from local APN table.
     * We try both using the APN name and not using the APN name.
     *
     * @param apnName the APN name
     * @param apns the list of results to return
     */
    private void loadFromLocalDatabase(final String apnName, final List<Apn> apns) {
        LogUtil.i(LogUtil.BUGLE_TAG, "Loading APNs from local APN table");
        final SQLiteDatabase database = ApnDatabase.getApnDatabase().getWritableDatabase();
        final String mccMnc = PhoneUtils.getMccMncString(PhoneUtils.getDefault().getMccMnc());
        Cursor cursor = null;
        cursor = queryLocalDatabase(database, mccMnc, apnName);
        if (cursor == null) {
            cursor = queryLocalDatabase(database, mccMnc, null/*apnName*/);
        }
        if (cursor == null) {
            LogUtil.w(LogUtil.BUGLE_TAG, "Could not find any APN in local table");
            return;
        }
        try {
            while (cursor.moveToNext()) {
                final Apn apn = DatabaseApn.from(apns,
                        cursor.getString(COLUMN_TYPE),
                        cursor.getString(COLUMN_MMSC),
                        cursor.getString(COLUMN_MMSPROXY),
                        cursor.getString(COLUMN_MMSPORT),
                        cursor.getLong(COLUMN_ID),
                        cursor.getInt(COLUMN_CURRENT));
                if (apn != null) {
                    apns.add(apn);
                }
            }
        } finally {
            cursor.close();
        }
    }

    /**
     * Make a query of local APN table based on MCC/MNC and APN name, sorted by CURRENT
     * column in descending order
     *
     * @param db the local database
     * @param numeric the MCC/MNC string
     * @param apnName the optional APN name to match
     * @return the cursor of the query, null if no result
     */
    private static Cursor queryLocalDatabase(final SQLiteDatabase db, final String numeric,
            final String apnName) {
        final String selection;
        final String[] selectionArgs;
        if (TextUtils.isEmpty(apnName)) {
            selection = SELECTION_NUMERIC;
            selectionArgs = new String[] { numeric };
        } else {
            selection = SELECTION_NUMERIC + " AND " + SELECTION_APN;
            selectionArgs = new String[] { numeric, apnName };
        }
        Cursor cursor = null;
        try {
            cursor = db.query(ApnDatabase.APN_TABLE, APN_PROJECTION_LOCAL, selection, selectionArgs,
                    null/*groupBy*/, null/*having*/, ORDER_BY, null/*limit*/);
        } catch (final SQLiteException e) {
            LogUtil.w(LogUtil.BUGLE_TAG, "Local APN table does not exist. Try rebuilding.", e);
            ApnDatabase.forceBuildAndLoadApnTables();
            cursor = db.query(ApnDatabase.APN_TABLE, APN_PROJECTION_LOCAL, selection, selectionArgs,
                    null/*groupBy*/, null/*having*/, ORDER_BY, null/*limit*/);
        }
        if (cursor == null || cursor.getCount() < 1) {
            if (cursor != null) {
                cursor.close();
            }
            LogUtil.w(LogUtil.BUGLE_TAG, "Query local APNs with apn " + apnName
                    + " returned empty");
            return null;
        }
        return cursor;
    }

    private static String trimWithNullCheck(final String value) {
        return value != null ? value.trim() : null;
    }

    /**
     * Trim leading zeros from IPv4 address strings
     * Our base libraries will interpret that as octel..
     * Must leave non v4 addresses and host names alone.
     * For example, 192.168.000.010 -> 192.168.0.10
     *
     * @param addr a string representing an ip addr
     * @return a string propertly trimmed
     */
    private static String trimV4AddrZeros(final String addr) {
        if (addr == null) {
            return null;
        }
        final String[] octets = addr.split("\\.");
        if (octets.length != 4) {
            return addr;
        }
        final StringBuilder builder = new StringBuilder(16);
        String result = null;
        for (int i = 0; i < 4; i++) {
            try {
                if (octets[i].length() > 3) {
                    return addr;
                }
                builder.append(Integer.parseInt(octets[i]));
            } catch (final NumberFormatException e) {
                return addr;
            }
            if (i < 3) {
                builder.append('.');
            }
        }
        result = builder.toString();
        return result;
    }

    /**
     * Check if the APN contains the APN type we want
     *
     * @param types The string encodes a list of supported types
     * @param requestType The type we want
     * @return true if the input types string contains the requestType
     */
    public static boolean isValidApnType(final String types, final String requestType) {
        // If APN type is unspecified, assume APN_TYPE_ALL.
        if (TextUtils.isEmpty(types)) {
            return true;
        }
        for (final String t : types.split(",")) {
            if (t.equals(requestType) || t.equals(APN_TYPE_ALL)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the ID of first APN to try
     */
    public static String getFirstTryApn(final SQLiteDatabase database, final String mccMnc) {
        String key = null;
        Cursor cursor = null;
        try {
            cursor = queryLocalDatabase(database, mccMnc, null/*apnName*/);
            if (cursor.moveToFirst()) {
                key = cursor.getString(ApnDatabase.COLUMN_ID);
            }
        } catch (final Exception e) {
            // Nothing to do
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return key;
    }
}
