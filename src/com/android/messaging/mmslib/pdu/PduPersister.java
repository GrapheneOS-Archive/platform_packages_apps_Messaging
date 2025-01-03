/*
 * Copyright (C) 2007-2008 Esmertec AG.
 * Copyright (C) 2007-2008 The Android Open Source Project
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

package com.android.messaging.mmslib.pdu;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.Telephony.Mms;
import android.provider.Telephony.Mms.Addr;
import android.provider.Telephony.Mms.Part;
import android.provider.Telephony.MmsSms;
import android.provider.Telephony.MmsSms.PendingMessages;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.messaging.mmslib.InvalidHeaderValueException;
import com.android.messaging.mmslib.MmsException;
import com.android.messaging.mmslib.SqliteWrapper;
import com.android.messaging.mmslib.util.DownloadDrmHelper;
import com.android.messaging.mmslib.util.DrmConvertSession;
import com.android.messaging.mmslib.util.PduCache;
import com.android.messaging.mmslib.util.PduCacheEntry;
import com.android.messaging.sms.MmsSmsUtils;
import com.android.messaging.util.Assert;
import com.android.messaging.util.ContentType;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.UriUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;

import androidx.collection.ArrayMap;
import androidx.collection.SimpleArrayMap;

/**
 * This class is the high-level manager of PDU storage.
 */
public class PduPersister {
    private static final String TAG = "PduPersister";
    private static final boolean LOCAL_LOGV = false;

    /**
     * The uri of temporary drm objects.
     */
    public static final String TEMPORARY_DRM_OBJECT_URI =
            "content://mms/" + Long.MAX_VALUE + "/part";

    /**
     * Indicate that we transiently failed to process a MM.
     */
    public static final int PROC_STATUS_TRANSIENT_FAILURE = 1;

    /**
     * Indicate that we permanently failed to process a MM.
     */
    public static final int PROC_STATUS_PERMANENTLY_FAILURE = 2;

    /**
     * Indicate that we have successfully processed a MM.
     */
    public static final int PROC_STATUS_COMPLETED = 3;

    public static final String BEGIN_VCARD = "BEGIN:VCARD";

    private static PduPersister sPersister;

    private static final PduCache PDU_CACHE_INSTANCE;

    private static final int[] ADDRESS_FIELDS = new int[]{
            PduHeaders.BCC,
            PduHeaders.CC,
            PduHeaders.FROM,
            PduHeaders.TO
    };

    public static final String[] PDU_PROJECTION = new String[]{
            Mms._ID,
            Mms.MESSAGE_BOX,
            Mms.THREAD_ID,
            Mms.RETRIEVE_TEXT,
            Mms.SUBJECT,
            Mms.CONTENT_LOCATION,
            Mms.CONTENT_TYPE,
            Mms.MESSAGE_CLASS,
            Mms.MESSAGE_ID,
            Mms.RESPONSE_TEXT,
            Mms.TRANSACTION_ID,
            Mms.CONTENT_CLASS,
            Mms.DELIVERY_REPORT,
            Mms.MESSAGE_TYPE,
            Mms.MMS_VERSION,
            Mms.PRIORITY,
            Mms.READ_REPORT,
            Mms.READ_STATUS,
            Mms.REPORT_ALLOWED,
            Mms.RETRIEVE_STATUS,
            Mms.STATUS,
            Mms.DATE,
            Mms.DELIVERY_TIME,
            Mms.EXPIRY,
            Mms.MESSAGE_SIZE,
            Mms.SUBJECT_CHARSET,
            Mms.RETRIEVE_TEXT_CHARSET,
            Mms.READ,
            Mms.SEEN,
    };

    public static final int PDU_COLUMN_ID                    = 0;
    public static final int PDU_COLUMN_MESSAGE_BOX           = 1;
    public static final int PDU_COLUMN_THREAD_ID             = 2;
    public static final int PDU_COLUMN_RETRIEVE_TEXT         = 3;
    public static final int PDU_COLUMN_SUBJECT               = 4;
    public static final int PDU_COLUMN_CONTENT_LOCATION      = 5;
    public static final int PDU_COLUMN_CONTENT_TYPE          = 6;
    public static final int PDU_COLUMN_MESSAGE_CLASS         = 7;
    public static final int PDU_COLUMN_MESSAGE_ID            = 8;
    public static final int PDU_COLUMN_RESPONSE_TEXT         = 9;
    public static final int PDU_COLUMN_TRANSACTION_ID        = 10;
    public static final int PDU_COLUMN_CONTENT_CLASS         = 11;
    public static final int PDU_COLUMN_DELIVERY_REPORT       = 12;
    public static final int PDU_COLUMN_MESSAGE_TYPE          = 13;
    public static final int PDU_COLUMN_MMS_VERSION           = 14;
    public static final int PDU_COLUMN_PRIORITY              = 15;
    public static final int PDU_COLUMN_READ_REPORT           = 16;
    public static final int PDU_COLUMN_READ_STATUS           = 17;
    public static final int PDU_COLUMN_REPORT_ALLOWED        = 18;
    public static final int PDU_COLUMN_RETRIEVE_STATUS       = 19;
    public static final int PDU_COLUMN_STATUS                = 20;
    public static final int PDU_COLUMN_DATE                  = 21;
    public static final int PDU_COLUMN_DELIVERY_TIME         = 22;
    public static final int PDU_COLUMN_EXPIRY                = 23;
    public static final int PDU_COLUMN_MESSAGE_SIZE          = 24;
    public static final int PDU_COLUMN_SUBJECT_CHARSET       = 25;
    public static final int PDU_COLUMN_RETRIEVE_TEXT_CHARSET = 26;
    public static final int PDU_COLUMN_READ                  = 27;
    public static final int PDU_COLUMN_SEEN                  = 28;

    private static final String[] PART_PROJECTION = new String[] {
            Part._ID,
            Part.CHARSET,
            Part.CONTENT_DISPOSITION,
            Part.CONTENT_ID,
            Part.CONTENT_LOCATION,
            Part.CONTENT_TYPE,
            Part.FILENAME,
            Part.NAME,
            Part.TEXT
    };

    private static final int PART_COLUMN_ID                  = 0;
    private static final int PART_COLUMN_CHARSET             = 1;
    private static final int PART_COLUMN_CONTENT_DISPOSITION = 2;
    private static final int PART_COLUMN_CONTENT_ID          = 3;
    private static final int PART_COLUMN_CONTENT_LOCATION    = 4;
    private static final int PART_COLUMN_CONTENT_TYPE        = 5;
    private static final int PART_COLUMN_FILENAME            = 6;
    private static final int PART_COLUMN_NAME                = 7;
    private static final int PART_COLUMN_TEXT                = 8;

    private static final SimpleArrayMap<Uri, Integer> MESSAGE_BOX_MAP;

    // These map are used for convenience in persist() and load().
    private static final SparseIntArray CHARSET_COLUMN_INDEX_MAP;

    private static final SparseIntArray ENCODED_STRING_COLUMN_INDEX_MAP;

    private static final SparseIntArray TEXT_STRING_COLUMN_INDEX_MAP;

    private static final SparseIntArray OCTET_COLUMN_INDEX_MAP;

    private static final SparseIntArray LONG_COLUMN_INDEX_MAP;

    private static final SparseArray<String> CHARSET_COLUMN_NAME_MAP;

    private static final SparseArray<String> ENCODED_STRING_COLUMN_NAME_MAP;

    private static final SparseArray<String> TEXT_STRING_COLUMN_NAME_MAP;

    private static final SparseArray<String> OCTET_COLUMN_NAME_MAP;

    private static final SparseArray<String> LONG_COLUMN_NAME_MAP;

    static {
        MESSAGE_BOX_MAP = new SimpleArrayMap<Uri, Integer>();
        MESSAGE_BOX_MAP.put(Mms.Inbox.CONTENT_URI, Mms.MESSAGE_BOX_INBOX);
        MESSAGE_BOX_MAP.put(Mms.Sent.CONTENT_URI, Mms.MESSAGE_BOX_SENT);
        MESSAGE_BOX_MAP.put(Mms.Draft.CONTENT_URI, Mms.MESSAGE_BOX_DRAFTS);
        MESSAGE_BOX_MAP.put(Mms.Outbox.CONTENT_URI, Mms.MESSAGE_BOX_OUTBOX);

        CHARSET_COLUMN_INDEX_MAP = new SparseIntArray();
        CHARSET_COLUMN_INDEX_MAP.put(PduHeaders.SUBJECT, PDU_COLUMN_SUBJECT_CHARSET);
        CHARSET_COLUMN_INDEX_MAP.put(PduHeaders.RETRIEVE_TEXT, PDU_COLUMN_RETRIEVE_TEXT_CHARSET);

        CHARSET_COLUMN_NAME_MAP = new SparseArray<String>();
        CHARSET_COLUMN_NAME_MAP.put(PduHeaders.SUBJECT, Mms.SUBJECT_CHARSET);
        CHARSET_COLUMN_NAME_MAP.put(PduHeaders.RETRIEVE_TEXT, Mms.RETRIEVE_TEXT_CHARSET);

        // Encoded string field code -> column index/name map.
        ENCODED_STRING_COLUMN_INDEX_MAP = new SparseIntArray();
        ENCODED_STRING_COLUMN_INDEX_MAP.put(PduHeaders.RETRIEVE_TEXT, PDU_COLUMN_RETRIEVE_TEXT);
        ENCODED_STRING_COLUMN_INDEX_MAP.put(PduHeaders.SUBJECT, PDU_COLUMN_SUBJECT);

        ENCODED_STRING_COLUMN_NAME_MAP = new SparseArray<String>();
        ENCODED_STRING_COLUMN_NAME_MAP.put(PduHeaders.RETRIEVE_TEXT, Mms.RETRIEVE_TEXT);
        ENCODED_STRING_COLUMN_NAME_MAP.put(PduHeaders.SUBJECT, Mms.SUBJECT);

        // Text string field code -> column index/name map.
        TEXT_STRING_COLUMN_INDEX_MAP = new SparseIntArray();
        TEXT_STRING_COLUMN_INDEX_MAP.put(PduHeaders.CONTENT_LOCATION, PDU_COLUMN_CONTENT_LOCATION);
        TEXT_STRING_COLUMN_INDEX_MAP.put(PduHeaders.CONTENT_TYPE, PDU_COLUMN_CONTENT_TYPE);
        TEXT_STRING_COLUMN_INDEX_MAP.put(PduHeaders.MESSAGE_CLASS, PDU_COLUMN_MESSAGE_CLASS);
        TEXT_STRING_COLUMN_INDEX_MAP.put(PduHeaders.MESSAGE_ID, PDU_COLUMN_MESSAGE_ID);
        TEXT_STRING_COLUMN_INDEX_MAP.put(PduHeaders.RESPONSE_TEXT, PDU_COLUMN_RESPONSE_TEXT);
        TEXT_STRING_COLUMN_INDEX_MAP.put(PduHeaders.TRANSACTION_ID, PDU_COLUMN_TRANSACTION_ID);

        TEXT_STRING_COLUMN_NAME_MAP = new SparseArray<String>();
        TEXT_STRING_COLUMN_NAME_MAP.put(PduHeaders.CONTENT_LOCATION, Mms.CONTENT_LOCATION);
        TEXT_STRING_COLUMN_NAME_MAP.put(PduHeaders.CONTENT_TYPE, Mms.CONTENT_TYPE);
        TEXT_STRING_COLUMN_NAME_MAP.put(PduHeaders.MESSAGE_CLASS, Mms.MESSAGE_CLASS);
        TEXT_STRING_COLUMN_NAME_MAP.put(PduHeaders.MESSAGE_ID, Mms.MESSAGE_ID);
        TEXT_STRING_COLUMN_NAME_MAP.put(PduHeaders.RESPONSE_TEXT, Mms.RESPONSE_TEXT);
        TEXT_STRING_COLUMN_NAME_MAP.put(PduHeaders.TRANSACTION_ID, Mms.TRANSACTION_ID);

        // Octet field code -> column index/name map.
        OCTET_COLUMN_INDEX_MAP = new SparseIntArray();
        OCTET_COLUMN_INDEX_MAP.put(PduHeaders.CONTENT_CLASS, PDU_COLUMN_CONTENT_CLASS);
        OCTET_COLUMN_INDEX_MAP.put(PduHeaders.DELIVERY_REPORT, PDU_COLUMN_DELIVERY_REPORT);
        OCTET_COLUMN_INDEX_MAP.put(PduHeaders.MESSAGE_TYPE, PDU_COLUMN_MESSAGE_TYPE);
        OCTET_COLUMN_INDEX_MAP.put(PduHeaders.MMS_VERSION, PDU_COLUMN_MMS_VERSION);
        OCTET_COLUMN_INDEX_MAP.put(PduHeaders.PRIORITY, PDU_COLUMN_PRIORITY);
        OCTET_COLUMN_INDEX_MAP.put(PduHeaders.READ_REPORT, PDU_COLUMN_READ_REPORT);
        OCTET_COLUMN_INDEX_MAP.put(PduHeaders.READ_STATUS, PDU_COLUMN_READ_STATUS);
        OCTET_COLUMN_INDEX_MAP.put(PduHeaders.REPORT_ALLOWED, PDU_COLUMN_REPORT_ALLOWED);
        OCTET_COLUMN_INDEX_MAP.put(PduHeaders.RETRIEVE_STATUS, PDU_COLUMN_RETRIEVE_STATUS);
        OCTET_COLUMN_INDEX_MAP.put(PduHeaders.STATUS, PDU_COLUMN_STATUS);

        OCTET_COLUMN_NAME_MAP = new SparseArray<String>();
        OCTET_COLUMN_NAME_MAP.put(PduHeaders.CONTENT_CLASS, Mms.CONTENT_CLASS);
        OCTET_COLUMN_NAME_MAP.put(PduHeaders.DELIVERY_REPORT, Mms.DELIVERY_REPORT);
        OCTET_COLUMN_NAME_MAP.put(PduHeaders.MESSAGE_TYPE, Mms.MESSAGE_TYPE);
        OCTET_COLUMN_NAME_MAP.put(PduHeaders.MMS_VERSION, Mms.MMS_VERSION);
        OCTET_COLUMN_NAME_MAP.put(PduHeaders.PRIORITY, Mms.PRIORITY);
        OCTET_COLUMN_NAME_MAP.put(PduHeaders.READ_REPORT, Mms.READ_REPORT);
        OCTET_COLUMN_NAME_MAP.put(PduHeaders.READ_STATUS, Mms.READ_STATUS);
        OCTET_COLUMN_NAME_MAP.put(PduHeaders.REPORT_ALLOWED, Mms.REPORT_ALLOWED);
        OCTET_COLUMN_NAME_MAP.put(PduHeaders.RETRIEVE_STATUS, Mms.RETRIEVE_STATUS);
        OCTET_COLUMN_NAME_MAP.put(PduHeaders.STATUS, Mms.STATUS);

        // Long field code -> column index/name map.
        LONG_COLUMN_INDEX_MAP = new SparseIntArray();
        LONG_COLUMN_INDEX_MAP.put(PduHeaders.DATE, PDU_COLUMN_DATE);
        LONG_COLUMN_INDEX_MAP.put(PduHeaders.DELIVERY_TIME, PDU_COLUMN_DELIVERY_TIME);
        LONG_COLUMN_INDEX_MAP.put(PduHeaders.EXPIRY, PDU_COLUMN_EXPIRY);
        LONG_COLUMN_INDEX_MAP.put(PduHeaders.MESSAGE_SIZE, PDU_COLUMN_MESSAGE_SIZE);

        LONG_COLUMN_NAME_MAP = new SparseArray<String>();
        LONG_COLUMN_NAME_MAP.put(PduHeaders.DATE, Mms.DATE);
        LONG_COLUMN_NAME_MAP.put(PduHeaders.DELIVERY_TIME, Mms.DELIVERY_TIME);
        LONG_COLUMN_NAME_MAP.put(PduHeaders.EXPIRY, Mms.EXPIRY);
        LONG_COLUMN_NAME_MAP.put(PduHeaders.MESSAGE_SIZE, Mms.MESSAGE_SIZE);

        PDU_CACHE_INSTANCE = PduCache.getInstance();
    }

    private final Context mContext;

    private final ContentResolver mContentResolver;

    private PduPersister(final Context context) {
        mContext = context;
        mContentResolver = context.getContentResolver();
    }

    /** Get(or create if not exist) an instance of PduPersister */
    public static PduPersister getPduPersister(final Context context) {
        if ((sPersister == null) || !context.equals(sPersister.mContext)) {
            sPersister = new PduPersister(context);
        }
        if (LOCAL_LOGV) {
            LogUtil.v(TAG, "PduPersister getPduPersister");
        }

        return sPersister;
    }

    private void setEncodedStringValueToHeaders(
            final Cursor c, final int columnIndex,
            final PduHeaders headers, final int mapColumn) {
        final String s = c.getString(columnIndex);
        if ((s != null) && (s.length() > 0)) {
            final int charsetColumnIndex = CHARSET_COLUMN_INDEX_MAP.get(mapColumn);
            final int charset = c.getInt(charsetColumnIndex);
            final EncodedStringValue value = new EncodedStringValue(
                    charset, getBytes(s));
            headers.setEncodedStringValue(value, mapColumn);
        }
    }

    private void setTextStringToHeaders(
            final Cursor c, final int columnIndex,
            final PduHeaders headers, final int mapColumn) {
        final String s = c.getString(columnIndex);
        if (s != null) {
            headers.setTextString(getBytes(s), mapColumn);
        }
    }

    private void setOctetToHeaders(
            final Cursor c, final int columnIndex,
            final PduHeaders headers, final int mapColumn) throws InvalidHeaderValueException {
        if (!c.isNull(columnIndex)) {
            final int b = c.getInt(columnIndex);
            headers.setOctet(b, mapColumn);
        }
    }

    private void setLongToHeaders(
            final Cursor c, final int columnIndex,
            final PduHeaders headers, final int mapColumn) {
        if (!c.isNull(columnIndex)) {
            final long l = c.getLong(columnIndex);
            headers.setLongInteger(l, mapColumn);
        }
    }

    private Integer getIntegerFromPartColumn(final Cursor c, final int columnIndex) {
        if (!c.isNull(columnIndex)) {
            return c.getInt(columnIndex);
        }
        return null;
    }

    private byte[] getByteArrayFromPartColumn(final Cursor c, final int columnIndex) {
        if (!c.isNull(columnIndex)) {
            return getBytes(c.getString(columnIndex));
        }
        return null;
    }

    private PduPart[] loadParts(final long msgId) throws MmsException {
        final Cursor c = SqliteWrapper.query(mContext, mContentResolver,
                Uri.parse("content://mms/" + msgId + "/part"),
                PART_PROJECTION, null, null, null);

        PduPart[] parts = null;

        try {
            if ((c == null) || (c.getCount() == 0)) {
                if (LOCAL_LOGV) {
                    LogUtil.v(TAG, "loadParts(" + msgId + "): no part to load.");
                }
                return null;
            }

            final int partCount = c.getCount();
            int partIdx = 0;
            parts = new PduPart[partCount];
            while (c.moveToNext()) {
                final PduPart part = new PduPart();
                final Integer charset = getIntegerFromPartColumn(
                        c, PART_COLUMN_CHARSET);
                if (charset != null) {
                    part.setCharset(charset);
                }

                final byte[] contentDisposition = getByteArrayFromPartColumn(
                        c, PART_COLUMN_CONTENT_DISPOSITION);
                if (contentDisposition != null) {
                    part.setContentDisposition(contentDisposition);
                }

                final byte[] contentId = getByteArrayFromPartColumn(
                        c, PART_COLUMN_CONTENT_ID);
                if (contentId != null) {
                    part.setContentId(contentId);
                }

                final byte[] contentLocation = getByteArrayFromPartColumn(
                        c, PART_COLUMN_CONTENT_LOCATION);
                if (contentLocation != null) {
                    part.setContentLocation(contentLocation);
                }

                final byte[] contentType = getByteArrayFromPartColumn(
                        c, PART_COLUMN_CONTENT_TYPE);
                if (contentType != null) {
                    part.setContentType(contentType);
                } else {
                    throw new MmsException("Content-Type must be set.");
                }

                final byte[] fileName = getByteArrayFromPartColumn(
                        c, PART_COLUMN_FILENAME);
                if (fileName != null) {
                    part.setFilename(fileName);
                }

                final byte[] name = getByteArrayFromPartColumn(
                        c, PART_COLUMN_NAME);
                if (name != null) {
                    part.setName(name);
                }

                // Construct a Uri for this part.
                final long partId = c.getLong(PART_COLUMN_ID);
                final Uri partURI = Uri.parse("content://mms/part/" + partId);
                part.setDataUri(partURI);

                // For images/audio/video, we won't keep their data in Part
                // because their renderer accept Uri as source.
                final String type = toIsoString(contentType);
                if (!ContentType.isImageType(type)
                        && !ContentType.isAudioType(type)
                        && !ContentType.isVideoType(type)) {
                    final ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    InputStream is = null;

                    // Store simple string values directly in the database instead of an
                    // external file.  This makes the text searchable and retrieval slightly
                    // faster.
                    if (ContentType.TEXT_PLAIN.equals(type) || ContentType.APP_SMIL.equals(type)
                            || ContentType.TEXT_HTML.equals(type)) {
                        final String text = c.getString(PART_COLUMN_TEXT);
                        final byte[] blob = new EncodedStringValue(
                                charset != null ? charset : CharacterSets.DEFAULT_CHARSET,
                                text != null ? text : "")
                                .getTextString();
                        baos.write(blob, 0, blob.length);
                    } else {

                        try {
                            is = mContentResolver.openInputStream(partURI);

                            final byte[] buffer = new byte[256];
                            int len = is.read(buffer);
                            while (len >= 0) {
                                baos.write(buffer, 0, len);
                                len = is.read(buffer);
                            }
                        } catch (final IOException e) {
                            Log.e(TAG, "Failed to load part data", e);
                            c.close();
                            throw new MmsException(e);
                        } finally {
                            if (is != null) {
                                try {
                                    is.close();
                                } catch (final IOException e) {
                                    Log.e(TAG, "Failed to close stream", e);
                                } // Ignore
                            }
                        }
                    }
                    part.setData(baos.toByteArray());
                }
                parts[partIdx++] = part;
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }

        return parts;
    }

    private void loadAddress(final long msgId, final PduHeaders headers) {
        final Cursor c = SqliteWrapper.query(mContext, mContentResolver,
                Uri.parse("content://mms/" + msgId + "/addr"),
                new String[]{Addr.ADDRESS, Addr.CHARSET, Addr.TYPE},
                null, null, null);

        if (c != null) {
            try {
                while (c.moveToNext()) {
                    final String addr = c.getString(0);
                    if (!TextUtils.isEmpty(addr)) {
                        final int addrType = c.getInt(2);
                        switch (addrType) {
                            case PduHeaders.FROM:
                                headers.setEncodedStringValue(
                                        new EncodedStringValue(c.getInt(1), getBytes(addr)),
                                        addrType);
                                break;
                            case PduHeaders.TO:
                            case PduHeaders.CC:
                            case PduHeaders.BCC:
                                headers.appendEncodedStringValue(
                                        new EncodedStringValue(c.getInt(1), getBytes(addr)),
                                        addrType);
                                break;
                            default:
                                Log.e(TAG, "Unknown address type: " + addrType);
                                break;
                        }
                    }
                }
            } finally {
                c.close();
            }
        }
    }

    /**
     * Load a PDU from a given cursor
     *
     * @param c The cursor
     * @return A parsed PDU from the database row
     */
    public GenericPdu load(final Cursor c) throws MmsException {
        final PduHeaders headers = new PduHeaders();
        final long msgId = c.getLong(PDU_COLUMN_ID);
        // Fill in the headers from the PDU columns
        loadHeadersFromCursor(c, headers);
        // Load address information of the MM.
        loadAddress(msgId, headers);
        // Load parts for the PDU body
        final int msgType = headers.getOctet(PduHeaders.MESSAGE_TYPE);
        final PduBody body = loadBody(msgId, msgType);
        return createPdu(msgType, headers, body);
    }

    /**
     * Load a PDU from storage by given Uri.
     *
     * @param uri            The Uri of the PDU to be loaded.
     * @return A generic PDU object, it may be cast to dedicated PDU.
     * @throws MmsException Failed to load some fields of a PDU.
     */
    public GenericPdu load(final Uri uri) throws MmsException {
        GenericPdu pdu = null;
        PduCacheEntry cacheEntry = null;
        int msgBox = 0;
        final long threadId = -1;
        try {
            synchronized (PDU_CACHE_INSTANCE) {
                if (PDU_CACHE_INSTANCE.isUpdating(uri)) {
                    if (LOCAL_LOGV) {
                        LogUtil.v(TAG, "load: " + uri + " blocked by isUpdating()");
                    }
                    try {
                        PDU_CACHE_INSTANCE.wait();
                    } catch (final InterruptedException e) {
                        Log.e(TAG, "load: ", e);
                    }
                }

                // Check if the pdu is already loaded
                cacheEntry = PDU_CACHE_INSTANCE.get(uri);
                if (cacheEntry != null) {
                    return cacheEntry.getPdu();
                }

                // Tell the cache to indicate to other callers that this item
                // is currently being updated.
                PDU_CACHE_INSTANCE.setUpdating(uri, true);
            }

            final Cursor c = SqliteWrapper.query(mContext, mContentResolver, uri,
                    PDU_PROJECTION, null, null, null);
            final PduHeaders headers = new PduHeaders();
            final long msgId = ContentUris.parseId(uri);

            try {
                if ((c == null) || (c.getCount() != 1) || !c.moveToFirst()) {
                    return null;  // MMS not found
                }

                msgBox = c.getInt(PDU_COLUMN_MESSAGE_BOX);
                //threadId = c.getLong(PDU_COLUMN_THREAD_ID);
                loadHeadersFromCursor(c, headers);
            } finally {
                if (c != null) {
                    c.close();
                }
            }

            // Check whether 'msgId' has been assigned a valid value.
            if (msgId == -1L) {
                throw new MmsException("Error! ID of the message: -1.");
            }

            // Load address information of the MM.
            loadAddress(msgId, headers);

            final int msgType = headers.getOctet(PduHeaders.MESSAGE_TYPE);
            final PduBody body = loadBody(msgId, msgType);
            pdu = createPdu(msgType, headers, body);
        } finally {
            synchronized (PDU_CACHE_INSTANCE) {
                if (pdu != null) {
                    Assert.isNull(PDU_CACHE_INSTANCE.get(uri), "Pdu exists for " + uri);
                    // Update the cache entry with the real info
                    cacheEntry = new PduCacheEntry(pdu, msgBox, threadId);
                    PDU_CACHE_INSTANCE.put(uri, cacheEntry);
                }
                PDU_CACHE_INSTANCE.setUpdating(uri, false);
                PDU_CACHE_INSTANCE.notifyAll(); // tell anybody waiting on this entry to go ahead
            }
        }
        return pdu;
    }

    private void loadHeadersFromCursor(final Cursor c, final PduHeaders headers)
            throws InvalidHeaderValueException {
        for (int i = ENCODED_STRING_COLUMN_INDEX_MAP.size(); --i >= 0; ) {
            setEncodedStringValueToHeaders(
                    c, ENCODED_STRING_COLUMN_INDEX_MAP.valueAt(i), headers,
                    ENCODED_STRING_COLUMN_INDEX_MAP.keyAt(i));
        }
        for (int i = TEXT_STRING_COLUMN_INDEX_MAP.size(); --i >= 0; ) {
            setTextStringToHeaders(
                    c, TEXT_STRING_COLUMN_INDEX_MAP.valueAt(i), headers,
                    TEXT_STRING_COLUMN_INDEX_MAP.keyAt(i));
        }
        for (int i = OCTET_COLUMN_INDEX_MAP.size(); --i >= 0; ) {
            setOctetToHeaders(
                    c, OCTET_COLUMN_INDEX_MAP.valueAt(i), headers,
                    OCTET_COLUMN_INDEX_MAP.keyAt(i));
        }
        for (int i = LONG_COLUMN_INDEX_MAP.size(); --i >= 0; ) {
            setLongToHeaders(
                    c, LONG_COLUMN_INDEX_MAP.valueAt(i), headers,
                    LONG_COLUMN_INDEX_MAP.keyAt(i));
        }
    }

    private GenericPdu createPdu(final int msgType, final PduHeaders headers, final PduBody body)
            throws MmsException {
        switch (msgType) {
            case PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND:
                return new NotificationInd(headers);
            case PduHeaders.MESSAGE_TYPE_DELIVERY_IND:
                return new DeliveryInd(headers);
            case PduHeaders.MESSAGE_TYPE_READ_ORIG_IND:
                return new ReadOrigInd(headers);
            case PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF:
                return new RetrieveConf(headers, body);
            case PduHeaders.MESSAGE_TYPE_SEND_REQ:
                return new SendReq(headers, body);
            case PduHeaders.MESSAGE_TYPE_ACKNOWLEDGE_IND:
                return new AcknowledgeInd(headers);
            case PduHeaders.MESSAGE_TYPE_NOTIFYRESP_IND:
                return new NotifyRespInd(headers);
            case PduHeaders.MESSAGE_TYPE_READ_REC_IND:
                return new ReadRecInd(headers);
            case PduHeaders.MESSAGE_TYPE_SEND_CONF:
            case PduHeaders.MESSAGE_TYPE_FORWARD_REQ:
            case PduHeaders.MESSAGE_TYPE_FORWARD_CONF:
            case PduHeaders.MESSAGE_TYPE_MBOX_STORE_REQ:
            case PduHeaders.MESSAGE_TYPE_MBOX_STORE_CONF:
            case PduHeaders.MESSAGE_TYPE_MBOX_VIEW_REQ:
            case PduHeaders.MESSAGE_TYPE_MBOX_VIEW_CONF:
            case PduHeaders.MESSAGE_TYPE_MBOX_UPLOAD_REQ:
            case PduHeaders.MESSAGE_TYPE_MBOX_UPLOAD_CONF:
            case PduHeaders.MESSAGE_TYPE_MBOX_DELETE_REQ:
            case PduHeaders.MESSAGE_TYPE_MBOX_DELETE_CONF:
            case PduHeaders.MESSAGE_TYPE_MBOX_DESCR:
            case PduHeaders.MESSAGE_TYPE_DELETE_REQ:
            case PduHeaders.MESSAGE_TYPE_DELETE_CONF:
            case PduHeaders.MESSAGE_TYPE_CANCEL_REQ:
            case PduHeaders.MESSAGE_TYPE_CANCEL_CONF:
                throw new MmsException(
                        "Unsupported PDU type: " + Integer.toHexString(msgType));

            default:
                throw new MmsException(
                        "Unrecognized PDU type: " + Integer.toHexString(msgType));
        }
    }

    private PduBody loadBody(final long msgId, final int msgType) throws MmsException {
        final PduBody body = new PduBody();

        // For PDU which type is M_retrieve.conf or Send.req, we should
        // load multiparts and put them into the body of the PDU.
        if ((msgType == PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF)
                || (msgType == PduHeaders.MESSAGE_TYPE_SEND_REQ)) {
            final PduPart[] parts = loadParts(msgId);
            if (parts != null) {
                final int partsNum = parts.length;
                for (int i = 0; i < partsNum; i++) {
                    body.addPart(parts[i]);
                }
            }
        }

        return body;
    }

    private void persistAddress(
            final long msgId, final int type, final EncodedStringValue[] array) {
        final ContentValues values = new ContentValues(3);

        for (final EncodedStringValue addr : array) {
            values.clear(); // Clear all values first.
            values.put(Addr.ADDRESS, toIsoString(addr.getTextString()));
            values.put(Addr.CHARSET, addr.getCharacterSet());
            values.put(Addr.TYPE, type);

            final Uri uri = Uri.parse("content://mms/" + msgId + "/addr");
            SqliteWrapper.insert(mContext, mContentResolver, uri, values);
        }
    }

    private static String getPartContentType(final PduPart part) {
        return part.getContentType() == null ? null : toIsoString(part.getContentType());
    }

    private static void getValues(final PduPart part, final ContentValues values) {
        byte[] bytes = part.getFilename();
        if (bytes != null) {
            values.put(Part.FILENAME, new String(bytes));
        }

        bytes = part.getName();
        if (bytes != null) {
            values.put(Part.NAME, new String(bytes));
        }

        bytes = part.getContentDisposition();
        if (bytes != null) {
            values.put(Part.CONTENT_DISPOSITION, toIsoString(bytes));
        }

        bytes = part.getContentId();
        if (bytes != null) {
            values.put(Part.CONTENT_ID, toIsoString(bytes));
        }

        bytes = part.getContentLocation();
        if (bytes != null) {
            values.put(Part.CONTENT_LOCATION, toIsoString(bytes));
        }
    }

    public Uri persistPart(final PduPart part, final long msgId,
            final Map<Uri, InputStream> preOpenedFiles) throws MmsException {
        final Uri uri = Uri.parse("content://mms/" + msgId + "/part");
        final ContentValues values = new ContentValues(8);

        final int charset = part.getCharset();
        if (charset != 0) {
            values.put(Part.CHARSET, charset);
        }

        String contentType = getPartContentType(part);
        final byte[] data = part.getData();

        if (LOCAL_LOGV) {
            LogUtil.v(TAG, "PduPersister.persistPart part: " + uri + " contentType: " +
                    contentType);
        }

        if (contentType != null) {
            // There is no "image/jpg" in Android (and it's an invalid mimetype).
            // Change it to "image/jpeg"
            if (ContentType.IMAGE_JPG.equals(contentType)) {
                contentType = ContentType.IMAGE_JPEG;
            }

            // On somes phones, a vcard comes in as text/plain instead of text/v-card.
            // Fix it if necessary.
            if (ContentType.TEXT_PLAIN.equals(contentType) && data != null) {
                // There might be a more efficient way to just check the beginning of the string
                // without encoding the whole thing, but we're concerned that with various
                // characters sets, just comparing the byte data to BEGIN_VCARD would not be
                // reliable.
                final String encodedDataString = new EncodedStringValue(charset, data).getString();
                if (encodedDataString != null && encodedDataString.startsWith(BEGIN_VCARD)) {
                    contentType = ContentType.TEXT_VCARD;
                    part.setContentType(contentType.getBytes());
                    if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
                        LogUtil.d(TAG, "PduPersister.persistPart part: " + uri + " contentType: " +
                                contentType + " changing to vcard");
                    }
                }
            }

            values.put(Part.CONTENT_TYPE, contentType);
            // To ensure the SMIL part is always the first part.
            if (ContentType.APP_SMIL.equals(contentType)) {
                values.put(Part.SEQ, -1);
            }
        } else {
            throw new MmsException("MIME type of the part must be set.");
        }

        getValues(part, values);

        Uri res = null;

        try {
            res = SqliteWrapper.insert(mContext, mContentResolver, uri, values);
        } catch (IllegalStateException e) {
            // Currently the MMS provider throws an IllegalStateException when it's out of space
            LogUtil.e(TAG, "SqliteWrapper.insert threw: ", e);
        }

        if (res == null) {
            throw new MmsException("Failed to persist part, return null.");
        }

        persistData(part, res, contentType, preOpenedFiles);
        // After successfully store the data, we should update
        // the dataUri of the part.
        part.setDataUri(res);

        return res;
    }

    /**
     * Save data of the part into storage. The source data may be given
     * by a byte[] or a Uri. If it's a byte[], directly save it
     * into storage, otherwise load source data from the dataUri and then
     * save it. If the data is an image, we may scale down it according
     * to user preference.
     *
     * @param part           The PDU part which contains data to be saved.
     * @param uri            The URI of the part.
     * @param contentType    The MIME type of the part.
     * @param preOpenedFiles if not null, a map of preopened InputStreams for the parts.
     * @throws MmsException Cannot find source data or error occurred
     *                      while saving the data.
     */
    private void persistData(final PduPart part, final Uri uri,
            final String contentType, final Map<Uri, InputStream> preOpenedFiles)
            throws MmsException {
        OutputStream os = null;
        InputStream is = null;
        DrmConvertSession drmConvertSession = null;
        Uri dataUri = null;
        String path = null;

        try {
            final byte[] data = part.getData();
            final int charset = part.getCharset();
            if (ContentType.TEXT_PLAIN.equals(contentType)
                    || ContentType.APP_SMIL.equals(contentType)
                    || ContentType.TEXT_HTML.equals(contentType)) {
                // Some phone could send MMS with a text part having empty data
                // Let's just skip those parts.
                // EncodedStringValue() throws NPE if data is empty
                if (data != null) {
                    final ContentValues cv = new ContentValues();
                    cv.put(Mms.Part.TEXT, new EncodedStringValue(charset, data).getString());
                    if (mContentResolver.update(uri, cv, null, null) != 1) {
                        throw new MmsException("unable to update " + uri.toString());
                    }
                }
            } else {
                final boolean isDrm = DownloadDrmHelper.isDrmConvertNeeded(contentType);
                if (isDrm) {
                    if (uri != null) {
                        try {
                            path = convertUriToPath(mContext, uri);
                            if (LOCAL_LOGV) {
                                LogUtil.v(TAG, "drm uri: " + uri + " path: " + path);
                            }
                            final File f = new File(path);
                            final long len = f.length();
                            if (LOCAL_LOGV) {
                                LogUtil.v(TAG, "drm path: " + path + " len: " + len);
                            }
                            if (len > 0) {
                                // we're not going to re-persist and re-encrypt an already
                                // converted drm file
                                return;
                            }
                        } catch (final Exception e) {
                            Log.e(TAG, "Can't get file info for: " + part.getDataUri(), e);
                        }
                    }
                    // We haven't converted the file yet, start the conversion
                    drmConvertSession = DrmConvertSession.open(mContext, contentType);
                    if (drmConvertSession == null) {
                        throw new MmsException("Mimetype " + contentType +
                                " can not be converted.");
                    }
                }
                // uri can look like:
                // content://mms/part/98
                os = mContentResolver.openOutputStream(uri);
                if (os == null) {
                    throw new MmsException("Failed to create output stream on " + uri);
                }
                if (data == null) {
                    dataUri = part.getDataUri();
                    if ((dataUri == null) || (dataUri.equals(uri))) {
                        Log.w(TAG, "Can't find data for this part.");
                        return;
                    }
                    // dataUri can look like:
                    // content://com.google.android.gallery3d.provider/picasa/item/5720646660183715
                    if (preOpenedFiles != null && preOpenedFiles.containsKey(dataUri)) {
                        is = preOpenedFiles.get(dataUri);
                    }
                    if (is == null) {
                        is = mContentResolver.openInputStream(dataUri);
                    }
                    if (is == null) {
                        throw new MmsException("Failed to create input stream on " + dataUri);
                    }
                    if (LOCAL_LOGV) {
                        LogUtil.v(TAG, "Saving data to: " + uri);
                    }

                    final byte[] buffer = new byte[8192];
                    for (int len = 0; (len = is.read(buffer)) != -1; ) {
                        if (!isDrm) {
                            os.write(buffer, 0, len);
                        } else {
                            final byte[] convertedData = drmConvertSession.convert(buffer, len);
                            if (convertedData != null) {
                                os.write(convertedData, 0, convertedData.length);
                            } else {
                                throw new MmsException("Error converting drm data.");
                            }
                        }
                    }
                } else {
                    if (LOCAL_LOGV) {
                        LogUtil.v(TAG, "Saving data to: " + uri);
                    }
                    if (!isDrm) {
                        os.write(data);
                    } else {
                        dataUri = uri;
                        final byte[] convertedData = drmConvertSession.convert(data, data.length);
                        if (convertedData != null) {
                            os.write(convertedData, 0, convertedData.length);
                        } else {
                            throw new MmsException("Error converting drm data.");
                        }
                    }
                }
            }
        } catch (final SQLiteException e) {
            Log.e(TAG, "Failed with SQLiteException.", e);
            throw new MmsException(e);
        } catch (final FileNotFoundException e) {
            Log.e(TAG, "Failed to open Input/Output stream.", e);
            throw new MmsException(e);
        } catch (final IOException e) {
            Log.e(TAG, "Failed to read/write data.", e);
            throw new MmsException(e);
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (final IOException e) {
                    Log.e(TAG, "IOException while closing: " + os, e);
                } // Ignore
            }
            if (is != null) {
                try {
                    is.close();
                } catch (final IOException e) {
                    Log.e(TAG, "IOException while closing: " + is, e);
                } // Ignore
            }
            if (drmConvertSession != null) {
                drmConvertSession.close(path);

                // Reset the permissions on the encrypted part file so everyone has only read
                // permission.
                final File f = new File(path);
                final ContentValues values = new ContentValues(0);
                SqliteWrapper.update(mContext, mContentResolver,
                        Uri.parse("content://mms/resetFilePerm/" + f.getName()),
                        values, null, null);
            }
        }
    }

    /**
     * This method expects uri in the following format
     *     content://media/<table_name>/<row_index> (or)
     *     file://sdcard/test.mp4
     *     http://test.com/test.mp4
     *
     * Here <table_name> shall be "video" or "audio" or "images"
     * <row_index> the index of the content in given table
     */
    public static String convertUriToPath(final Context context, final Uri uri) {
        String path = null;
        if (null != uri) {
            final String scheme = uri.getScheme();
            if (null == scheme || scheme.equals("") || UriUtil.isFileUri(uri)) {
                path = uri.getPath();

            } else if (scheme.equals("http")) {
                path = uri.toString();

            } else if (scheme.equals(ContentResolver.SCHEME_CONTENT)) {
                final String[] projection = new String[] {MediaStore.MediaColumns.DATA};
                Cursor cursor = null;
                try {
                    cursor = context.getContentResolver().query(uri, projection, null,
                            null, null);
                    if (null == cursor || 0 == cursor.getCount() || !cursor.moveToFirst()) {
                        throw new IllegalArgumentException("Given Uri could not be found" +
                                " in media store");
                    }
                    final int pathIndex =
                            cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA);
                    path = cursor.getString(pathIndex);
                } catch (final SQLiteException e) {
                    throw new IllegalArgumentException("Given Uri is not formatted in a way " +
                            "so that it can be found in media store.");
                } finally {
                    if (null != cursor) {
                        cursor.close();
                    }
                }
            } else {
                throw new IllegalArgumentException("Given Uri scheme is not supported");
            }
        }
        return path;
    }

    private void updateAddress(
            final long msgId, final int type, final EncodedStringValue[] array) {
        // Delete old address information and then insert new ones.
        SqliteWrapper.delete(mContext, mContentResolver,
                Uri.parse("content://mms/" + msgId + "/addr"),
                Addr.TYPE + "=" + type, null);

        persistAddress(msgId, type, array);
    }

    /**
     * Update headers of a SendReq.
     *
     * @param uri The PDU which need to be updated.
     * @param pdu New headers.
     * @throws MmsException Bad URI or updating failed.
     */
    public void updateHeaders(final Uri uri, final SendReq sendReq) {
        synchronized (PDU_CACHE_INSTANCE) {
            // If the cache item is getting updated, wait until it's done updating before
            // purging it.
            if (PDU_CACHE_INSTANCE.isUpdating(uri)) {
                if (LOCAL_LOGV) {
                    LogUtil.v(TAG, "updateHeaders: " + uri + " blocked by isUpdating()");
                }
                try {
                    PDU_CACHE_INSTANCE.wait();
                } catch (final InterruptedException e) {
                    Log.e(TAG, "updateHeaders: ", e);
                }
            }
        }
        PDU_CACHE_INSTANCE.purge(uri);

        final ContentValues values = new ContentValues(10);
        final byte[] contentType = sendReq.getContentType();
        if (contentType != null) {
            values.put(Mms.CONTENT_TYPE, toIsoString(contentType));
        }

        final long date = sendReq.getDate();
        if (date != -1) {
            values.put(Mms.DATE, date);
        }

        final int deliveryReport = sendReq.getDeliveryReport();
        if (deliveryReport != 0) {
            values.put(Mms.DELIVERY_REPORT, deliveryReport);
        }

        final long expiry = sendReq.getExpiry();
        if (expiry != -1) {
            values.put(Mms.EXPIRY, expiry);
        }

        final byte[] msgClass = sendReq.getMessageClass();
        if (msgClass != null) {
            values.put(Mms.MESSAGE_CLASS, toIsoString(msgClass));
        }

        final int priority = sendReq.getPriority();
        if (priority != 0) {
            values.put(Mms.PRIORITY, priority);
        }

        final int readReport = sendReq.getReadReport();
        if (readReport != 0) {
            values.put(Mms.READ_REPORT, readReport);
        }

        final byte[] transId = sendReq.getTransactionId();
        if (transId != null) {
            values.put(Mms.TRANSACTION_ID, toIsoString(transId));
        }

        final EncodedStringValue subject = sendReq.getSubject();
        if (subject != null) {
            values.put(Mms.SUBJECT, toIsoString(subject.getTextString()));
            values.put(Mms.SUBJECT_CHARSET, subject.getCharacterSet());
        } else {
            values.put(Mms.SUBJECT, "");
        }

        final long messageSize = sendReq.getMessageSize();
        if (messageSize > 0) {
            values.put(Mms.MESSAGE_SIZE, messageSize);
        }

        final PduHeaders headers = sendReq.getPduHeaders();
        final HashSet<String> recipients = new HashSet<String>();
        for (final int addrType : ADDRESS_FIELDS) {
            EncodedStringValue[] array = null;
            if (addrType == PduHeaders.FROM) {
                final EncodedStringValue v = headers.getEncodedStringValue(addrType);
                if (v != null) {
                    array = new EncodedStringValue[1];
                    array[0] = v;
                }
            } else {
                array = headers.getEncodedStringValues(addrType);
            }

            if (array != null) {
                final long msgId = ContentUris.parseId(uri);
                updateAddress(msgId, addrType, array);
                if (addrType == PduHeaders.TO) {
                    for (final EncodedStringValue v : array) {
                        if (v != null) {
                            recipients.add(v.getString());
                        }
                    }
                }
            }
        }
        if (!recipients.isEmpty()) {
            final long threadId = MmsSmsUtils.Threads.getOrCreateThreadId(mContext, recipients);
            values.put(Mms.THREAD_ID, threadId);
        }

        SqliteWrapper.update(mContext, mContentResolver, uri, values, null, null);
    }


    private void updatePart(final Uri uri, final PduPart part,
            final Map<Uri, InputStream> preOpenedFiles)
            throws MmsException {
        final ContentValues values = new ContentValues(7);

        final int charset = part.getCharset();
        if (charset != 0) {
            values.put(Part.CHARSET, charset);
        }

        String contentType = null;
        if (part.getContentType() != null) {
            contentType = toIsoString(part.getContentType());
            values.put(Part.CONTENT_TYPE, contentType);
        } else {
            throw new MmsException("MIME type of the part must be set.");
        }

        getValues(part, values);

        SqliteWrapper.update(mContext, mContentResolver, uri, values, null, null);

        // Only update the data when:
        // 1. New binary data supplied or
        // 2. The Uri of the part is different from the current one.
        if ((part.getData() != null)
                || (!uri.equals(part.getDataUri()))) {
            persistData(part, uri, contentType, preOpenedFiles);
        }
    }

    /**
     * Update all parts of a PDU.
     *
     * @param uri            The PDU which need to be updated.
     * @param body           New message body of the PDU.
     * @param preOpenedFiles if not null, a map of preopened InputStreams for the parts.
     * @throws MmsException Bad URI or updating failed.
     */
    public void updateParts(final Uri uri, final PduBody body,
            final Map<Uri, InputStream> preOpenedFiles)
            throws MmsException {
        try {
            PduCacheEntry cacheEntry;
            synchronized (PDU_CACHE_INSTANCE) {
                if (PDU_CACHE_INSTANCE.isUpdating(uri)) {
                    if (LOCAL_LOGV) {
                        LogUtil.v(TAG, "updateParts: " + uri + " blocked by isUpdating()");
                    }
                    try {
                        PDU_CACHE_INSTANCE.wait();
                    } catch (final InterruptedException e) {
                        Log.e(TAG, "updateParts: ", e);
                    }
                    cacheEntry = PDU_CACHE_INSTANCE.get(uri);
                    if (cacheEntry != null) {
                        ((MultimediaMessagePdu) cacheEntry.getPdu()).setBody(body);
                    }
                }
                // Tell the cache to indicate to other callers that this item
                // is currently being updated.
                PDU_CACHE_INSTANCE.setUpdating(uri, true);
            }

            final ArrayList<PduPart> toBeCreated = new ArrayList<PduPart>();
            final ArrayMap<Uri, PduPart> toBeUpdated = new ArrayMap<Uri, PduPart>();

            final int partsNum = body.getPartsNum();
            final StringBuilder filter = new StringBuilder().append('(');
            for (int i = 0; i < partsNum; i++) {
                final PduPart part = body.getPart(i);
                final Uri partUri = part.getDataUri();
                if ((partUri == null) || TextUtils.isEmpty(partUri.getAuthority())
                        || !partUri.getAuthority().startsWith("mms")) {
                    toBeCreated.add(part);
                } else {
                    toBeUpdated.put(partUri, part);

                    // Don't use 'i > 0' to determine whether we should append
                    // 'AND' since 'i = 0' may be skipped in another branch.
                    if (filter.length() > 1) {
                        filter.append(" AND ");
                    }

                    filter.append(Part._ID);
                    filter.append("!=");
                    DatabaseUtils.appendEscapedSQLString(filter, partUri.getLastPathSegment());
                }
            }
            filter.append(')');

            final long msgId = ContentUris.parseId(uri);

            // Remove the parts which doesn't exist anymore.
            SqliteWrapper.delete(mContext, mContentResolver,
                    Uri.parse(Mms.CONTENT_URI + "/" + msgId + "/part"),
                    filter.length() > 2 ? filter.toString() : null, null);

            // Create new parts which didn't exist before.
            for (final PduPart part : toBeCreated) {
                persistPart(part, msgId, preOpenedFiles);
            }

            // Update the modified parts.
            for (final Map.Entry<Uri, PduPart> e : toBeUpdated.entrySet()) {
                updatePart(e.getKey(), e.getValue(), preOpenedFiles);
            }
        } finally {
            synchronized (PDU_CACHE_INSTANCE) {
                PDU_CACHE_INSTANCE.setUpdating(uri, false);
                PDU_CACHE_INSTANCE.notifyAll();
            }
        }
    }

    /**
     * Persist a PDU object to specific location in the storage.
     *
     * @param pdu             The PDU object to be stored.
     * @param uri             Where to store the given PDU object.
     * @param subId           Subscription id associated with this message.
     * @param subPhoneNumber TODO
     * @param preOpenedFiles  if not null, a map of preopened InputStreams for the parts.
     * @return A Uri which can be used to access the stored PDU.
     */
    public Uri persist(final GenericPdu pdu, final Uri uri, final int subId,
            final String subPhoneNumber, final Map<Uri, InputStream> preOpenedFiles)
            throws MmsException {
        if (uri == null) {
            throw new MmsException("Uri may not be null.");
        }
        long msgId = -1;
        try {
            msgId = ContentUris.parseId(uri);
        } catch (final NumberFormatException e) {
            // the uri ends with "inbox" or something else like that
        }
        final boolean existingUri = msgId != -1;

        if (!existingUri && MESSAGE_BOX_MAP.get(uri) == null) {
            throw new MmsException(
                    "Bad destination, must be one of "
                            + "content://mms/inbox, content://mms/sent, "
                            + "content://mms/drafts, content://mms/outbox, "
                            + "content://mms/temp."
            );
        }
        synchronized (PDU_CACHE_INSTANCE) {
            // If the cache item is getting updated, wait until it's done updating before
            // purging it.
            if (PDU_CACHE_INSTANCE.isUpdating(uri)) {
                if (LOCAL_LOGV) {
                    LogUtil.v(TAG, "persist: " + uri + " blocked by isUpdating()");
                }
                try {
                    PDU_CACHE_INSTANCE.wait();
                } catch (final InterruptedException e) {
                    Log.e(TAG, "persist1: ", e);
                }
            }
        }
        PDU_CACHE_INSTANCE.purge(uri);

        final PduHeaders header = pdu.getPduHeaders();
        PduBody body = null;
        ContentValues values = new ContentValues();

        // Mark new messages as seen in the telephony database so that we don't have to
        // do a global "set all messages as seen" since that occasionally seems to be
        // problematic (i.e. very slow).  See bug 18189471.
        values.put(Mms.SEEN, 1);

        //Set<Entry<Integer, String>> set;

        for (int i = ENCODED_STRING_COLUMN_NAME_MAP.size(); --i >= 0; ) {
            final int field = ENCODED_STRING_COLUMN_NAME_MAP.keyAt(i);
            final EncodedStringValue encodedString = header.getEncodedStringValue(field);
            if (encodedString != null) {
                final String charsetColumn = CHARSET_COLUMN_NAME_MAP.get(field);
                values.put(ENCODED_STRING_COLUMN_NAME_MAP.valueAt(i),
                        toIsoString(encodedString.getTextString()));
                values.put(charsetColumn, encodedString.getCharacterSet());
            }
        }

        for (int i = TEXT_STRING_COLUMN_NAME_MAP.size(); --i >= 0; ) {
            final byte[] text = header.getTextString(TEXT_STRING_COLUMN_NAME_MAP.keyAt(i));
            if (text != null) {
                values.put(TEXT_STRING_COLUMN_NAME_MAP.valueAt(i), toIsoString(text));
            }
        }

        for (int i = OCTET_COLUMN_NAME_MAP.size(); --i >= 0; ) {
            final int b = header.getOctet(OCTET_COLUMN_NAME_MAP.keyAt(i));
            if (b != 0) {
                values.put(OCTET_COLUMN_NAME_MAP.valueAt(i), b);
            }
        }

        for (int i = LONG_COLUMN_NAME_MAP.size(); --i >= 0; ) {
            final long l = header.getLongInteger(LONG_COLUMN_NAME_MAP.keyAt(i));
            if (l != -1L) {
                values.put(LONG_COLUMN_NAME_MAP.valueAt(i), l);
            }
        }

        final SparseArray<EncodedStringValue[]> addressMap =
                new SparseArray<EncodedStringValue[]>(ADDRESS_FIELDS.length);
        // Save address information.
        for (final int addrType : ADDRESS_FIELDS) {
            EncodedStringValue[] array = null;
            if (addrType == PduHeaders.FROM) {
                final EncodedStringValue v = header.getEncodedStringValue(addrType);
                if (v != null) {
                    array = new EncodedStringValue[1];
                    array[0] = v;
                }
            } else {
                array = header.getEncodedStringValues(addrType);
            }
            addressMap.put(addrType, array);
        }

        final HashSet<String> recipients = new HashSet<String>();
        final int msgType = pdu.getMessageType();
        // Here we only allocate thread ID for M-Notification.ind,
        // M-Retrieve.conf and M-Send.req.
        // Some of other PDU types may be allocated a thread ID outside
        // this scope.
        if ((msgType == PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND)
                || (msgType == PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF)
                || (msgType == PduHeaders.MESSAGE_TYPE_SEND_REQ)) {
            switch (msgType) {
                case PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND:
                case PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF:
                    loadRecipients(PduHeaders.FROM, recipients, addressMap);

                    // For received messages (whether group MMS is enabled or not) we want to
                    // associate this message with the thread composed of all the recipients
                    // EXCLUDING our own number. This includes the person who sent the message
                    // (the FROM field above) in addition to the other people the message was
                    // addressed TO (or CC fields to address group messaging compatibility issues
                    // with devices that place numbers in this field). Typically our own number is
                    // in the TO/CC field so we have to remove it in checkAndLoadToCcRecipients.
                    checkAndLoadToCcRecipients(recipients, addressMap, subPhoneNumber);
                    break;
                case PduHeaders.MESSAGE_TYPE_SEND_REQ:
                    loadRecipients(PduHeaders.TO, recipients, addressMap);
                    break;
            }
            long threadId = -1L;
            if (!recipients.isEmpty()) {
                // Given all the recipients associated with this message, find (or create) the
                // correct thread.
                threadId = MmsSmsUtils.Threads.getOrCreateThreadId(mContext, recipients);
            } else {
                LogUtil.w(TAG, "PduPersister.persist No recipients; persisting PDU to thread: "
                        + threadId);
            }
            values.put(Mms.THREAD_ID, threadId);
        }

        // Save parts first to avoid inconsistent message is loaded
        // while saving the parts.
        final long placeholderId = System.currentTimeMillis(); // Placeholder ID of the msg.

        // Figure out if this PDU is a text-only message
        boolean textOnly = true;

        // Get body if the PDU is a RetrieveConf or SendReq.
        if (pdu instanceof MultimediaMessagePdu) {
            body = ((MultimediaMessagePdu) pdu).getBody();
            // Start saving parts if necessary.
            if (body != null) {
                final int partsNum = body.getPartsNum();
                if (LOCAL_LOGV) {
                    LogUtil.v(TAG, "PduPersister.persist partsNum: " + partsNum);
                }
                if (partsNum > 2) {
                    // For a text-only message there will be two parts: 1-the SMIL, 2-the text.
                    // Down a few lines below we're checking to make sure we've only got SMIL or
                    // text. We also have to check then we don't have more than two parts.
                    // Otherwise, a slideshow with two text slides would be marked as textOnly.
                    textOnly = false;
                }
                for (int i = 0; i < partsNum; i++) {
                    final PduPart part = body.getPart(i);
                    persistPart(part, placeholderId, preOpenedFiles);

                    // If we've got anything besides text/plain or SMIL part, then we've got
                    // an mms message with some other type of attachment.
                    final String contentType = getPartContentType(part);
                    if (LOCAL_LOGV) {
                        LogUtil.v(TAG, "PduPersister.persist part: " + i + " contentType: " +
                                contentType);
                    }
                    if (contentType != null && !ContentType.APP_SMIL.equals(contentType)
                            && !ContentType.TEXT_PLAIN.equals(contentType)) {
                        textOnly = false;
                    }
                }
            }
        }
        // Record whether this mms message is a simple plain text or not. This is a hint for the
        // UI.
        values.put(Mms.TEXT_ONLY, textOnly ? 1 : 0);
        values.put(Mms.SUBSCRIPTION_ID, subId);

        Uri res = null;
        if (existingUri) {
            res = uri;
            SqliteWrapper.update(mContext, mContentResolver, res, values, null, null);
        } else {
            res = SqliteWrapper.insert(mContext, mContentResolver, uri, values);
            if (res == null) {
                throw new MmsException("persist() failed: return null.");
            }
            // Get the real ID of the PDU and update all parts which were
            // saved with the placeholder ID.
            msgId = ContentUris.parseId(res);
        }

        values = new ContentValues(1);
        values.put(Part.MSG_ID, msgId);
        SqliteWrapper.update(mContext, mContentResolver,
                Uri.parse("content://mms/" + placeholderId + "/part"),
                values, null, null);
        // We should return the longest URI of the persisted PDU, for
        // example, if input URI is "content://mms/inbox" and the _ID of
        // persisted PDU is '8', we should return "content://mms/inbox/8"
        // instead of "content://mms/8".
        // TODO: Should the MmsProvider be responsible for this???
        if (!existingUri) {
            res = Uri.parse(uri + "/" + msgId);
        }

        // Save address information.
        for (final int addrType : ADDRESS_FIELDS) {
            final EncodedStringValue[] array = addressMap.get(addrType);
            if (array != null) {
                persistAddress(msgId, addrType, array);
            }
        }

        return res;
    }

    /**
     * For a given address type, extract the recipients from the headers.
     *
     * @param addressType     can be PduHeaders.FROM or PduHeaders.TO
     * @param recipients      a HashSet that is loaded with the recipients from the FROM or TO
     *                        headers
     * @param addressMap      a HashMap of the addresses from the ADDRESS_FIELDS header
     */
    private void loadRecipients(final int addressType, final HashSet<String> recipients,
            final SparseArray<EncodedStringValue[]> addressMap) {
        final EncodedStringValue[] array = addressMap.get(addressType);
        if (array == null) {
            return;
        }
        for (final EncodedStringValue v : array) {
            if (v != null) {
                final String number = v.getString();
                if (!recipients.contains(number)) {
                    // Only add numbers which aren't already included.
                    recipients.add(number);
                }
            }
        }
    }

    /**
     * For a given address type, extract the recipients from the headers.
     *
     * @param recipients      a HashSet that is loaded with the recipients from the FROM or TO
     *                        headers
     * @param addressMap      a HashMap of the addresses from the ADDRESS_FIELDS header
     * @param selfNumber      self phone number
     */
    private void checkAndLoadToCcRecipients(final HashSet<String> recipients,
            final SparseArray<EncodedStringValue[]> addressMap, final String selfNumber) {
        final EncodedStringValue[] arrayTo = addressMap.get(PduHeaders.TO);
        final EncodedStringValue[] arrayCc = addressMap.get(PduHeaders.CC);
        final ArrayList<String> numbers = new ArrayList<String>();
        if (arrayTo != null) {
            for (final EncodedStringValue v : arrayTo) {
                if (v != null) {
                    numbers.add(v.getString());
                }
            }
        }
        if (arrayCc != null) {
            for (final EncodedStringValue v : arrayCc) {
                if (v != null) {
                    numbers.add(v.getString());
                }
            }
        }

        // If selfNumber is unavailable and there is only a single address in all TO and CC, we can
        // skip adding it into recipients as assuming it is my own phone number.
        final boolean isSelfNumberUnavailable = TextUtils.isEmpty(selfNumber);
        if (isSelfNumberUnavailable && numbers.size() == 1) {
            return;
        }

        for (final String number : numbers) {
            // Only add numbers which aren't my own number.
            if (isSelfNumberUnavailable || !PhoneNumberUtils.compare(number, selfNumber)) {
                if (!recipients.contains(number)) {
                    // Only add numbers which aren't already included.
                    recipients.add(number);
                }
            }
        }
    }

    /**
     * Move a PDU object from one location to another.
     *
     * @param from Specify the PDU object to be moved.
     * @param to   The destination location, should be one of the following:
     *             "content://mms/inbox", "content://mms/sent",
     *             "content://mms/drafts", "content://mms/outbox",
     *             "content://mms/trash".
     * @return New Uri of the moved PDU.
     * @throws MmsException Error occurred while moving the message.
     */
    public Uri move(final Uri from, final Uri to) throws MmsException {
        // Check whether the 'msgId' has been assigned a valid value.
        final long msgId = ContentUris.parseId(from);
        if (msgId == -1L) {
            throw new MmsException("Error! ID of the message: -1.");
        }

        // Get corresponding int value of destination box.
        final Integer msgBox = MESSAGE_BOX_MAP.get(to);
        if (msgBox == null) {
            throw new MmsException(
                    "Bad destination, must be one of "
                            + "content://mms/inbox, content://mms/sent, "
                            + "content://mms/drafts, content://mms/outbox, "
                            + "content://mms/temp."
            );
        }

        final ContentValues values = new ContentValues(1);
        values.put(Mms.MESSAGE_BOX, msgBox);
        SqliteWrapper.update(mContext, mContentResolver, from, values, null, null);
        return ContentUris.withAppendedId(to, msgId);
    }

    /**
     * Wrap a byte[] into a String.
     */
    public static String toIsoString(final byte[] bytes) {
        try {
            return new String(bytes, CharacterSets.MIMENAME_ISO_8859_1);
        } catch (final UnsupportedEncodingException e) {
            // Impossible to reach here!
            Log.e(TAG, "ISO_8859_1 must be supported!", e);
            return "";
        }
    }

    /**
     * Unpack a given String into a byte[].
     */
    public static byte[] getBytes(final String data) {
        try {
            return data.getBytes(CharacterSets.MIMENAME_ISO_8859_1);
        } catch (final UnsupportedEncodingException e) {
            // Impossible to reach here!
            Log.e(TAG, "ISO_8859_1 must be supported!", e);
            return new byte[0];
        }
    }

    /**
     * Remove all objects in the temporary path.
     */
    public void release() {
        final Uri uri = Uri.parse(TEMPORARY_DRM_OBJECT_URI);
        SqliteWrapper.delete(mContext, mContentResolver, uri, null, null);
    }

    /**
     * Find all messages to be sent or downloaded before certain time.
     */
    public Cursor getPendingMessages(final long dueTime) {
        final Uri.Builder uriBuilder = PendingMessages.CONTENT_URI.buildUpon();
        uriBuilder.appendQueryParameter("protocol", "mms");

        final String selection = PendingMessages.ERROR_TYPE + " < ?"
                + " AND " + PendingMessages.DUE_TIME + " <= ?";

        final String[] selectionArgs = new String[] {
                String.valueOf(MmsSms.ERR_TYPE_GENERIC_PERMANENT),
                String.valueOf(dueTime)
        };

        return SqliteWrapper.query(mContext, mContentResolver,
                uriBuilder.build(), null, selection, selectionArgs,
                PendingMessages.DUE_TIME);
    }
}
