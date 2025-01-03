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

import android.content.ContentValues;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.provider.Telephony;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;

import com.android.messaging.R;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

/**
 * Default implementation of APN settings loader
 */
class DefaultApnSettingsLoader implements ApnSettingsLoader {
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
     * An in-memory implementation of an APN. These APNs are organized into an in-memory list.
     * The order of the list can be changed by the setSuccess method.
     */
    private static class MemoryApn implements Apn {
        /**
         * Create an in-memory APN loaded from resources
         *
         * @param apns the in-memory APN list
         * @param typesIn the APN type field
         * @param mmscIn the APN mmsc field
         * @param proxyIn the APN mmsproxy field
         * @param portIn the APN mmsport field
         * @return an in-memory APN instance, null if there is invalid parameter
         */
        public static MemoryApn from(final List<Apn> apns, final String typesIn,
                final String mmscIn, final String proxyIn, final String portIn) {
            if (apns == null) {
                return null;
            }
            final BaseApn base = BaseApn.from(typesIn, mmscIn, proxyIn, portIn);
            if (base == null) {
                return null;
            }
            for (final Apn apn : apns) {
                if (apn instanceof MemoryApn && ((MemoryApn) apn).equals(base)) {
                    return null;
                }
            }
            return new MemoryApn(apns, base);
        }

        private final List<Apn> mApns;
        private final BaseApn mBase;

        public MemoryApn(final List<Apn> apns, final BaseApn base) {
            mApns = apns;
            mBase = base;
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
                Log.d(MmsService.TAG, "Set APN ["
                        + "MMSC=" + getMmsc() + ", "
                        + "PROXY=" + getMmsProxy() + ", "
                        + "PORT=" + getMmsProxyPort() + "] to be first");
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

    private static final String[] APN_PROJECTION = {
            Telephony.Carriers.TYPE,
            Telephony.Carriers.MMSC,
            Telephony.Carriers.MMSPROXY,
            Telephony.Carriers.MMSPORT,
    };
    private static final int COLUMN_TYPE         = 0;
    private static final int COLUMN_MMSC         = 1;
    private static final int COLUMN_MMSPROXY     = 2;
    private static final int COLUMN_MMSPORT      = 3;

    private static final String APN_MCC = "mcc";
    private static final String APN_MNC = "mnc";
    private static final String APN_APN = "apn";
    private static final String APN_TYPE = "type";
    private static final String APN_MMSC = "mmsc";
    private static final String APN_MMSPROXY = "mmsproxy";
    private static final String APN_MMSPORT = "mmsport";

    private final Context mContext;

    // Cached APNs for subIds
    private final SparseArray<List<Apn>> mApnsCache;

    DefaultApnSettingsLoader(final Context context) {
        mContext = context;
        mApnsCache = new SparseArray<>();
    }

    @Override
    public List<Apn> get(final String apnName) {
        final int subId = Utils.getEffectiveSubscriptionId(MmsManager.DEFAULT_SUB_ID);
        List<Apn> apns;
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
            Log.i(MmsService.TAG, "Loaded " + apns.size() + " APNs");
        }
        return apns;
    }

    private void loadLocked(final int subId, final String apnName, final List<Apn> apns) {
        // Try system APN table first
        loadFromSystem(subId, apnName, apns);
        if (apns.size() > 0) {
            return;
        }
        // Try loading from apns.xml in resources
        loadFromResources(subId, apnName, apns);
        if (apns.size() > 0) {
            return;
        }
        // Try resources but without APN name
        loadFromResources(subId, null/*apnName*/, apns);
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
        if (Utils.supportMSim() && subId != MmsManager.DEFAULT_SUB_ID) {
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
                final Apn apn = BaseApn.from(
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
        Log.i(MmsService.TAG, "Loading APNs from system, "
                + "checkCurrent=" + checkCurrent + " apnName=" + apnName);
        final StringBuilder selectionBuilder = new StringBuilder();
        String[] selectionArgs = null;
        if (checkCurrent) {
            selectionBuilder.append(Telephony.Carriers.CURRENT).append(" IS NOT NULL");
        }
        apnName = trimWithNullCheck(apnName);
        if (!TextUtils.isEmpty(apnName)) {
            if (selectionBuilder.length() > 0) {
                selectionBuilder.append(" AND ");
            }
            selectionBuilder.append(Telephony.Carriers.APN).append("=?");
            selectionArgs = new String[] { apnName };
        }
        try {
            final Cursor cursor = mContext.getContentResolver().query(
                    uri,
                    APN_PROJECTION,
                    selectionBuilder.toString(),
                    selectionArgs,
                    null/*sortOrder*/);
            if (cursor == null || cursor.getCount() < 1) {
                if (cursor != null) {
                    cursor.close();
                }
                Log.w(MmsService.TAG, "Query " + uri + " with apn " + apnName + " and "
                        + (checkCurrent ? "checking CURRENT" : "not checking CURRENT")
                        + " returned empty");
                return null;
            }
            return cursor;
        } catch (final SQLiteException e) {
            Log.w(MmsService.TAG, "APN table query exception: " + e);
        } catch (final SecurityException e) {
            Log.w(MmsService.TAG, "Platform restricts APN table access: " + e);
            throw e;
        }
        return null;
    }

    /**
     * Find matching APNs using builtin APN list resource
     *
     * @param subId the SIM subId
     * @param apnName the APN name to match
     * @param apns the list for returning results
     */
    private void loadFromResources(final int subId, final String apnName, final List<Apn> apns) {
        Log.i(MmsService.TAG, "Loading APNs from resources, apnName=" + apnName);
        final int[] mccMnc = Utils.getMccMnc(mContext, subId);
        if (mccMnc[0] == 0) {
            Log.w(MmsService.TAG, "Can not get valid mcc/mnc from system");
            return;
        }
        // MCC/MNC is good, loading/querying APNs from XML
        XmlResourceParser xml = null;
        try {
            xml = mContext.getResources().getXml(R.xml.apns);
            new ApnsXmlParser(xml, new ApnsXmlParser.ApnProcessor() {
                @Override
                public void process(ContentValues apnValues) {
                    final String mcc = trimWithNullCheck(apnValues.getAsString(APN_MCC));
                    final String mnc = trimWithNullCheck(apnValues.getAsString(APN_MNC));
                    final String apn = trimWithNullCheck(apnValues.getAsString(APN_APN));
                    try {
                        if (mccMnc[0] == Integer.parseInt(mcc) &&
                                mccMnc[1] == Integer.parseInt(mnc) &&
                                (TextUtils.isEmpty(apnName) || apnName.equalsIgnoreCase(apn))) {
                            final String type = apnValues.getAsString(APN_TYPE);
                            final String mmsc = apnValues.getAsString(APN_MMSC);
                            final String mmsproxy = apnValues.getAsString(APN_MMSPROXY);
                            final String mmsport = apnValues.getAsString(APN_MMSPORT);
                            final Apn newApn = MemoryApn.from(apns, type, mmsc, mmsproxy, mmsport);
                            if (newApn != null) {
                                apns.add(newApn);
                            }
                        }
                    } catch (final NumberFormatException e) {
                        // Ignore
                    }
                }
            }).parse();
        } catch (final Resources.NotFoundException e) {
            Log.w(MmsService.TAG, "Can not get apns.xml " + e);
        } finally {
            if (xml != null) {
                xml.close();
            }
        }
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
}
