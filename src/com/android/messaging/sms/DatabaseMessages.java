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

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.provider.Telephony.Mms;
import android.provider.Telephony.Sms;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.MimeTypeMap;

import com.android.messaging.Factory;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.datamodel.media.VideoThumbnailRequest;
import com.android.messaging.mmslib.pdu.CharacterSets;
import com.android.messaging.util.ContentType;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.MediaMetadataRetrieverWrapper;
import com.android.messaging.util.PhoneUtils;
import com.google.common.collect.Lists;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

/**
 * Class contains various SMS/MMS database entities from telephony provider
 */
public class DatabaseMessages {
    private static final String TAG = LogUtil.BUGLE_TAG;

    public abstract static class DatabaseMessage {
        public abstract int getProtocol();
        public abstract String getUri();
        public abstract long getTimestampInMillis();

        @Override
        public boolean equals(final Object other) {
            if (other == null || !(other instanceof DatabaseMessage)) {
                return false;
            }
            final DatabaseMessage otherDbMsg = (DatabaseMessage) other;
            // No need to check timestamp since we only need this when we compare
            // messages at the same timestamp
            return TextUtils.equals(getUri(), otherDbMsg.getUri());
        }

        @Override
        public int hashCode() {
            // No need to check timestamp since we only need this when we compare
            // messages at the same timestamp
            return getUri().hashCode();
        }
    }

    /**
     * SMS message
     */
    public static class SmsMessage extends DatabaseMessage implements Parcelable {
        private static int sIota = 0;
        public static final int INDEX_ID = sIota++;
        public static final int INDEX_TYPE = sIota++;
        public static final int INDEX_ADDRESS = sIota++;
        public static final int INDEX_BODY = sIota++;
        public static final int INDEX_DATE = sIota++;
        public static final int INDEX_THREAD_ID = sIota++;
        public static final int INDEX_STATUS = sIota++;
        public static final int INDEX_READ = sIota++;
        public static final int INDEX_SEEN = sIota++;
        public static final int INDEX_DATE_SENT = sIota++;
        public static final int INDEX_SUB_ID = sIota++;

        private static String[] sProjection;

        public static String[] getProjection() {
            if (sProjection == null) {
                sProjection = new String[] {
                        Sms._ID,
                        Sms.TYPE,
                        Sms.ADDRESS,
                        Sms.BODY,
                        Sms.DATE,
                        Sms.THREAD_ID,
                        Sms.STATUS,
                        Sms.READ,
                        Sms.SEEN,
                        Sms.DATE_SENT,
                        Sms.SUBSCRIPTION_ID,
                    };
            }

            return sProjection;
        }

        public String mUri;
        public String mAddress;
        public String mBody;
        private long mRowId;
        public long mTimestampInMillis;
        public long mTimestampSentInMillis;
        public int mType;
        public long mThreadId;
        public int mStatus;
        public boolean mRead;
        public boolean mSeen;
        public int mSubId;

        private SmsMessage() {
        }

        /**
         * Load from a cursor of a query that returns the SMS to import
         *
         * @param cursor
         */
        private void load(final Cursor cursor) {
            mRowId = cursor.getLong(INDEX_ID);
            mAddress = cursor.getString(INDEX_ADDRESS);
            mBody = cursor.getString(INDEX_BODY);
            mTimestampInMillis = cursor.getLong(INDEX_DATE);
            mTimestampSentInMillis = cursor.getLong(INDEX_DATE_SENT);
            mType = cursor.getInt(INDEX_TYPE);
            mThreadId = cursor.getLong(INDEX_THREAD_ID);
            mStatus = cursor.getInt(INDEX_STATUS);
            mRead = cursor.getInt(INDEX_READ) == 0 ? false : true;
            mSeen = cursor.getInt(INDEX_SEEN) == 0 ? false : true;
            mUri = ContentUris.withAppendedId(Sms.CONTENT_URI, mRowId).toString();
            mSubId = PhoneUtils.getDefault().getSubIdFromTelephony(cursor, INDEX_SUB_ID);
        }

        /**
         * Get a new SmsMessage by loading from the cursor of a query
         * that returns the SMS to import
         *
         * @param cursor
         * @return
         */
        public static SmsMessage get(final Cursor cursor) {
            final SmsMessage msg = new SmsMessage();
            msg.load(cursor);
            return msg;
        }

        @Override
        public String getUri() {
            return mUri;
        }

        public int getSubId() {
            return mSubId;
        }

        @Override
        public int getProtocol() {
            return MessageData.PROTOCOL_SMS;
        }

        @Override
        public long getTimestampInMillis() {
            return mTimestampInMillis;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        private SmsMessage(final Parcel in) {
            mUri = in.readString();
            mRowId = in.readLong();
            mTimestampInMillis = in.readLong();
            mTimestampSentInMillis = in.readLong();
            mType = in.readInt();
            mThreadId = in.readLong();
            mStatus = in.readInt();
            mRead = in.readInt() != 0;
            mSeen = in.readInt() != 0;
            mSubId = in.readInt();

            // SMS specific
            mAddress = in.readString();
            mBody = in.readString();
        }

        public static final Parcelable.Creator<SmsMessage> CREATOR
                = new Parcelable.Creator<SmsMessage>() {
            @Override
            public SmsMessage createFromParcel(final Parcel in) {
                return new SmsMessage(in);
            }

            @Override
            public SmsMessage[] newArray(final int size) {
                return new SmsMessage[size];
            }
        };

        @Override
        public void writeToParcel(final Parcel out, final int flags) {
            out.writeString(mUri);
            out.writeLong(mRowId);
            out.writeLong(mTimestampInMillis);
            out.writeLong(mTimestampSentInMillis);
            out.writeInt(mType);
            out.writeLong(mThreadId);
            out.writeInt(mStatus);
            out.writeInt(mRead ? 1 : 0);
            out.writeInt(mSeen ? 1 : 0);
            out.writeInt(mSubId);

            // SMS specific
            out.writeString(mAddress);
            out.writeString(mBody);
        }
    }

    /**
     * MMS message
     */
    public static class MmsMessage extends DatabaseMessage implements Parcelable {
        private static int sIota = 0;
        public static final int INDEX_ID = sIota++;
        public static final int INDEX_MESSAGE_BOX = sIota++;
        public static final int INDEX_SUBJECT = sIota++;
        public static final int INDEX_SUBJECT_CHARSET = sIota++;
        public static final int INDEX_MESSAGE_SIZE = sIota++;
        public static final int INDEX_DATE = sIota++;
        public static final int INDEX_DATE_SENT = sIota++;
        public static final int INDEX_THREAD_ID = sIota++;
        public static final int INDEX_PRIORITY = sIota++;
        public static final int INDEX_STATUS = sIota++;
        public static final int INDEX_READ = sIota++;
        public static final int INDEX_SEEN = sIota++;
        public static final int INDEX_CONTENT_LOCATION = sIota++;
        public static final int INDEX_TRANSACTION_ID = sIota++;
        public static final int INDEX_MESSAGE_TYPE = sIota++;
        public static final int INDEX_EXPIRY = sIota++;
        public static final int INDEX_RESPONSE_STATUS = sIota++;
        public static final int INDEX_RETRIEVE_STATUS = sIota++;
        public static final int INDEX_SUB_ID = sIota++;

        private static String[] sProjection;

        public static String[] getProjection() {
            if (sProjection == null) {
                String[] projection = new String[] {
                    Mms._ID,
                    Mms.MESSAGE_BOX,
                    Mms.SUBJECT,
                    Mms.SUBJECT_CHARSET,
                    Mms.MESSAGE_SIZE,
                    Mms.DATE,
                    Mms.DATE_SENT,
                    Mms.THREAD_ID,
                    Mms.PRIORITY,
                    Mms.STATUS,
                    Mms.READ,
                    Mms.SEEN,
                    Mms.CONTENT_LOCATION,
                    Mms.TRANSACTION_ID,
                    Mms.MESSAGE_TYPE,
                    Mms.EXPIRY,
                    Mms.RESPONSE_STATUS,
                    Mms.RETRIEVE_STATUS,
                    Mms.SUBSCRIPTION_ID,
                };

                sProjection = projection;
            }

            return sProjection;
        }

        public String mUri;
        private long mRowId;
        public int mType;
        public String mSubject;
        public int mSubjectCharset;
        private long mSize;
        public long mTimestampInMillis;
        public long mSentTimestampInMillis;
        public long mThreadId;
        public int mPriority;
        public int mStatus;
        public boolean mRead;
        public boolean mSeen;
        public String mContentLocation;
        public String mTransactionId;
        public int mMmsMessageType;
        public long mExpiryInMillis;
        public int mSubId;
        public String mSender;
        public int mResponseStatus;
        public int mRetrieveStatus;

        public List<MmsPart> mParts = Lists.newArrayList();
        private boolean mPartsProcessed = false;

        private MmsMessage() {
        }

        /**
         * Load from a cursor of a query that returns the MMS to import
         *
         * @param cursor
         */
        public void load(final Cursor cursor) {
            mRowId = cursor.getLong(INDEX_ID);
            mType = cursor.getInt(INDEX_MESSAGE_BOX);
            mSubject = cursor.getString(INDEX_SUBJECT);
            mSubjectCharset = cursor.getInt(INDEX_SUBJECT_CHARSET);
            if (!TextUtils.isEmpty(mSubject)) {
                // PduPersister stores the subject using ISO_8859_1
                // Let's load it using that encoding and convert it back to its original
                // See PduPersister.persist and PduPersister.toIsoString
                // (Refer to bug b/11162476)
                mSubject = getDecodedString(
                        getStringBytes(mSubject, CharacterSets.ISO_8859_1), mSubjectCharset);
            }
            mSize = cursor.getLong(INDEX_MESSAGE_SIZE);
            // MMS db times are in seconds
            mTimestampInMillis = cursor.getLong(INDEX_DATE) * 1000;
            mSentTimestampInMillis = cursor.getLong(INDEX_DATE_SENT) * 1000;
            mThreadId = cursor.getLong(INDEX_THREAD_ID);
            mPriority = cursor.getInt(INDEX_PRIORITY);
            mStatus = cursor.getInt(INDEX_STATUS);
            mRead = cursor.getInt(INDEX_READ) == 0 ? false : true;
            mSeen = cursor.getInt(INDEX_SEEN) == 0 ? false : true;
            mContentLocation = cursor.getString(INDEX_CONTENT_LOCATION);
            mTransactionId = cursor.getString(INDEX_TRANSACTION_ID);
            mMmsMessageType = cursor.getInt(INDEX_MESSAGE_TYPE);
            mExpiryInMillis = cursor.getLong(INDEX_EXPIRY) * 1000;
            mResponseStatus = cursor.getInt(INDEX_RESPONSE_STATUS);
            mRetrieveStatus = cursor.getInt(INDEX_RETRIEVE_STATUS);
            // Clear all parts in case we reuse this object
            mParts.clear();
            mPartsProcessed = false;
            mUri = ContentUris.withAppendedId(Mms.CONTENT_URI, mRowId).toString();
            mSubId = PhoneUtils.getDefault().getSubIdFromTelephony(cursor, INDEX_SUB_ID);
        }

        /**
         * Get a new MmsMessage by loading from the cursor of a query
         * that returns the MMS to import
         *
         * @param cursor
         * @return
         */
        public static MmsMessage get(final Cursor cursor) {
            final MmsMessage msg = new MmsMessage();
            msg.load(cursor);
            return msg;
        }
        /**
         * Add a loaded MMS part
         *
         * @param part
         */
        public void addPart(final MmsPart part) {
            mParts.add(part);
        }

        public List<MmsPart> getParts() {
            return mParts;
        }

        public long getSize() {
            if (!mPartsProcessed) {
                processParts();
            }
            return mSize;
        }

        /**
         * Process loaded MMS parts to obtain the combined text, the combined attachment url,
         * the combined content type and the combined size.
         */
        private void processParts() {
            if (mPartsProcessed) {
                return;
            }
            mPartsProcessed = true;
            // Remember the width and height of the first media part
            // These are needed when building attachment list
            long sizeOfParts = 0L;
            for (final MmsPart part : mParts) {
                sizeOfParts += part.mSize;
            }
            if (mSize <= 0) {
                mSize = mSubject != null ? mSubject.getBytes().length : 0L;
                mSize += sizeOfParts;
            }
        }

        @Override
        public String getUri() {
            return mUri;
        }

        public long getId() {
            return mRowId;
        }

        public int getSubId() {
            return mSubId;
        }

        @Override
        public int getProtocol() {
            return MessageData.PROTOCOL_MMS;
        }

        @Override
        public long getTimestampInMillis() {
            return mTimestampInMillis;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public void setSender(final String sender) {
            mSender = sender;
        }

        private MmsMessage(final Parcel in) {
            mUri = in.readString();
            mRowId = in.readLong();
            mTimestampInMillis = in.readLong();
            mSentTimestampInMillis = in.readLong();
            mType = in.readInt();
            mThreadId = in.readLong();
            mStatus = in.readInt();
            mRead = in.readInt() != 0;
            mSeen = in.readInt() != 0;
            mSubId = in.readInt();

            // MMS specific
            mSubject = in.readString();
            mContentLocation = in.readString();
            mTransactionId = in.readString();
            mSender = in.readString();

            mSize = in.readLong();
            mExpiryInMillis = in.readLong();

            mSubjectCharset = in.readInt();
            mPriority = in.readInt();
            mMmsMessageType = in.readInt();
            mResponseStatus = in.readInt();
            mRetrieveStatus = in.readInt();

            final int nParts = in.readInt();
            mParts = new ArrayList<MmsPart>();
            mPartsProcessed = false;
            for (int i = 0; i < nParts; i++) {
                mParts.add((MmsPart) in.readParcelable(getClass().getClassLoader()));
            }
        }

        public static final Parcelable.Creator<MmsMessage> CREATOR
                = new Parcelable.Creator<MmsMessage>() {
            @Override
            public MmsMessage createFromParcel(final Parcel in) {
                return new MmsMessage(in);
            }

            @Override
            public MmsMessage[] newArray(final int size) {
                return new MmsMessage[size];
            }
        };

        @Override
        public void writeToParcel(final Parcel out, final int flags) {
            out.writeString(mUri);
            out.writeLong(mRowId);
            out.writeLong(mTimestampInMillis);
            out.writeLong(mSentTimestampInMillis);
            out.writeInt(mType);
            out.writeLong(mThreadId);
            out.writeInt(mStatus);
            out.writeInt(mRead ? 1 : 0);
            out.writeInt(mSeen ? 1 : 0);
            out.writeInt(mSubId);

            out.writeString(mSubject);
            out.writeString(mContentLocation);
            out.writeString(mTransactionId);
            out.writeString(mSender);

            out.writeLong(mSize);
            out.writeLong(mExpiryInMillis);

            out.writeInt(mSubjectCharset);
            out.writeInt(mPriority);
            out.writeInt(mMmsMessageType);
            out.writeInt(mResponseStatus);
            out.writeInt(mRetrieveStatus);

            out.writeInt(mParts.size());
            for (final MmsPart part : mParts) {
                out.writeParcelable(part, 0);
            }
        }
    }

    /**
     * Part of an MMS message
     */
    public static class MmsPart implements Parcelable {
        public static final String[] PROJECTION = new String[] {
            Mms.Part._ID,
            Mms.Part.MSG_ID,
            Mms.Part.CHARSET,
            Mms.Part.CONTENT_TYPE,
            Mms.Part.TEXT,
        };
        private static int sIota = 0;
        public static final int INDEX_ID = sIota++;
        public static final int INDEX_MSG_ID = sIota++;
        public static final int INDEX_CHARSET = sIota++;
        public static final int INDEX_CONTENT_TYPE = sIota++;
        public static final int INDEX_TEXT = sIota++;

        public String mUri;
        public long mRowId;
        public long mMessageId;
        public String mContentType;
        public String mText;
        public int mCharset;
        private int mWidth;
        private int mHeight;
        public long mSize;

        private MmsPart() {
        }

        /**
         * Load from a cursor of a query that returns the MMS part to import
         *
         * @param cursor
         */
        public void load(final Cursor cursor, final boolean loadMedia) {
            mRowId = cursor.getLong(INDEX_ID);
            mMessageId = cursor.getLong(INDEX_MSG_ID);
            mContentType = cursor.getString(INDEX_CONTENT_TYPE);
            mText = cursor.getString(INDEX_TEXT);
            mCharset = cursor.getInt(INDEX_CHARSET);
            mWidth = 0;
            mHeight = 0;
            mSize = 0;
            if (isMedia()) {
                // For importing we don't load media since performance is critical
                // For loading when we receive mms, we do load media to get enough
                // information of the media file
                if (loadMedia) {
                    if (ContentType.isImageType(mContentType)) {
                        loadImage();
                    } else if (ContentType.isVideoType(mContentType)) {
                        loadVideo();
                    } // No need to load audio for parsing
                    mSize = MmsUtils.getMediaFileSize(getDataUri());
                }
            } else {
                // Load text if not media type
                loadText();
            }
            mUri = Uri.withAppendedPath(Mms.CONTENT_URI, cursor.getString(INDEX_ID)).toString();
        }

        /**
         * Get content type from file extension
         */
        private static String extractContentType(final Context context, final Uri uri) {
            final String path = uri.getPath();
            final MimeTypeMap mimeTypeMap = MimeTypeMap.getSingleton();
            String extension = MimeTypeMap.getFileExtensionFromUrl(path);
            if (TextUtils.isEmpty(extension)) {
                // getMimeTypeFromExtension() doesn't handle spaces in filenames nor can it handle
                // urlEncoded strings. Let's try one last time at finding the extension.
                final int dotPos = path.lastIndexOf('.');
                if (0 <= dotPos) {
                    extension = path.substring(dotPos + 1);
                }
            }
            return mimeTypeMap.getMimeTypeFromExtension(extension);
        }

        /**
         * Get text of a text part
         */
        private void loadText() {
            byte[] data = null;
            if (isEmbeddedTextType()) {
                // Embedded text, get from the "text" column
                if (!TextUtils.isEmpty(mText)) {
                    data = getStringBytes(mText, mCharset);
                }
            } else {
                // Not embedded, load from disk
                final ContentResolver resolver =
                        Factory.get().getApplicationContext().getContentResolver();
                final Uri uri = getDataUri();
                InputStream is = null;
                final ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try {
                    is = resolver.openInputStream(uri);
                    final byte[] buffer = new byte[256];
                    int len = is.read(buffer);
                    while (len >= 0) {
                        baos.write(buffer, 0, len);
                        len = is.read(buffer);
                    }
                } catch (final IOException e) {
                    LogUtil.e(TAG,
                            "DatabaseMessages.MmsPart: loading text from file failed: " + e, e);
                } finally {
                    if (is != null) {
                        try {
                            is.close();
                        } catch (final IOException e) {
                            LogUtil.e(TAG, "DatabaseMessages.MmsPart: close file failed: " + e, e);
                        }
                    }
                }
                data = baos.toByteArray();
            }
            if (data != null && data.length > 0) {
                mSize = data.length;
                mText = getDecodedString(data, mCharset);
            }
        }

        /**
         * Load image file of an image part and parse the dimensions and type
         */
        private void loadImage() {
            final Context context = Factory.get().getApplicationContext();
            final ContentResolver resolver = context.getContentResolver();
            final Uri uri = getDataUri();
            // We have to get the width and height of the image -- they're needed when adding
            // an attachment in bugle.
            InputStream is = null;
            try {
                is = resolver.openInputStream(uri);
                final BitmapFactory.Options opt = new BitmapFactory.Options();
                opt.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(is, null, opt);
                mContentType = opt.outMimeType;
                mWidth = opt.outWidth;
                mHeight = opt.outHeight;
                if (TextUtils.isEmpty(mContentType)) {
                    // BitmapFactory couldn't figure out the image type. That's got to be a bad
                    // sign, but see if we can figure it out from the file extension.
                    mContentType = extractContentType(context, uri);
                }
            } catch (final FileNotFoundException e) {
                LogUtil.e(TAG, "DatabaseMessages.MmsPart.loadImage: file not found", e);
            } finally {
                if (is != null) {
                    try {
                        is.close();
                    } catch (final IOException e) {
                        Log.e(TAG, "IOException caught while closing stream", e);
                    }
                }
            }
        }

        /**
         * Load video file of a video part and parse the dimensions and type
         */
        private void loadVideo() {
            // This is a coarse check, and should not be applied to outgoing messages. However,
            // currently, this does not cause any problems.
            if (!VideoThumbnailRequest.shouldShowIncomingVideoThumbnails()) {
                return;
            }
            final Uri uri = getDataUri();
            final MediaMetadataRetrieverWrapper retriever = new MediaMetadataRetrieverWrapper();
            try {
                retriever.setDataSource(uri);
                // FLAG: This inadvertently fixes a problem with phone receiving audio
                // messages on some carrier. We should handle this in a less accidental way so that
                // we don't break it again. (The carrier changes the content type in the wrapper
                // in-transit from audio/mp4 to video/3gpp without changing the data)
                // Also note: There is a bug in some OEM device where mmr returns
                // video/ffmpeg for image files.  That shouldn't happen here but be aware.
                mContentType =
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
                final Bitmap bitmap = retriever.getFrameAtTime(-1);
                if (bitmap != null) {
                    mWidth = bitmap.getWidth();
                    mHeight = bitmap.getHeight();
                } else {
                    // Get here if it's not actually video (see above)
                    LogUtil.i(LogUtil.BUGLE_TAG, "loadVideo: Got null bitmap from " + uri);
                }
            } catch (IOException e) {
                LogUtil.i(LogUtil.BUGLE_TAG, "Error extracting metadata from " + uri, e);
            } finally {
                retriever.release();
            }
        }

        /**
         * Get media file size
         */
        private long getMediaFileSize() {
            final Context context = Factory.get().getApplicationContext();
            final Uri uri = getDataUri();
            AssetFileDescriptor fd = null;
            try {
                fd = context.getContentResolver().openAssetFileDescriptor(uri, "r");
                if (fd != null) {
                    return fd.getParcelFileDescriptor().getStatSize();
                }
            } catch (final FileNotFoundException e) {
                LogUtil.e(TAG, "DatabaseMessages.MmsPart: cound not find media file: " + e, e);
            } finally {
                if (fd != null) {
                    try {
                        fd.close();
                    } catch (final IOException e) {
                        LogUtil.e(TAG, "DatabaseMessages.MmsPart: failed to close " + e, e);
                    }
                }
            }
            return 0L;
        }

        /**
         * @return If the type is a text type that stores text embedded (i.e. in db table)
         */
        private boolean isEmbeddedTextType() {
            return ContentType.TEXT_PLAIN.equals(mContentType)
                    || ContentType.APP_SMIL.equals(mContentType)
                    || ContentType.TEXT_HTML.equals(mContentType);
        }

        /**
         * Get an instance of the MMS part from the part table cursor
         *
         * @param cursor
         * @param loadMedia Whether to load the media file of the part
         * @return
         */
        public static MmsPart get(final Cursor cursor, final boolean loadMedia) {
            final MmsPart part = new MmsPart();
            part.load(cursor, loadMedia);
            return part;
        }

        public boolean isText() {
            return ContentType.TEXT_PLAIN.equals(mContentType)
                    || ContentType.TEXT_HTML.equals(mContentType)
                    || ContentType.APP_WAP_XHTML.equals(mContentType);
        }

        public boolean isMedia() {
            return ContentType.isImageType(mContentType)
                    || ContentType.isVideoType(mContentType)
                    || ContentType.isAudioType(mContentType)
                    || ContentType.isVCardType(mContentType);
        }

        public boolean isImage() {
            return ContentType.isImageType(mContentType);
        }

        public Uri getDataUri() {
            return Uri.parse("content://mms/part/" + mRowId);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        private MmsPart(final Parcel in) {
            mUri = in.readString();
            mRowId = in.readLong();
            mMessageId = in.readLong();
            mContentType = in.readString();
            mText = in.readString();
            mCharset = in.readInt();
            mWidth = in.readInt();
            mHeight = in.readInt();
            mSize = in.readLong();
        }

        public static final Parcelable.Creator<MmsPart> CREATOR
                = new Parcelable.Creator<MmsPart>() {
            @Override
            public MmsPart createFromParcel(final Parcel in) {
                return new MmsPart(in);
            }

            @Override
            public MmsPart[] newArray(final int size) {
                return new MmsPart[size];
            }
        };

        @Override
        public void writeToParcel(final Parcel out, final int flags) {
            out.writeString(mUri);
            out.writeLong(mRowId);
            out.writeLong(mMessageId);
            out.writeString(mContentType);
            out.writeString(mText);
            out.writeInt(mCharset);
            out.writeInt(mWidth);
            out.writeInt(mHeight);
            out.writeLong(mSize);
        }
    }

    /**
     * This class provides the same DatabaseMessage interface over a local SMS db message
     */
    public static class LocalDatabaseMessage extends DatabaseMessage implements Parcelable {
        private final int mProtocol;
        private final String mUri;
        private final long mTimestamp;
        private final long mLocalId;
        private final String mConversationId;

        public LocalDatabaseMessage(final long localId, final int protocol, final String uri,
                final long timestamp, final String conversationId) {
            mLocalId = localId;
            mProtocol = protocol;
            mUri = uri;
            mTimestamp = timestamp;
            mConversationId = conversationId;
        }

        @Override
        public int getProtocol() {
            return mProtocol;
        }

        @Override
        public long getTimestampInMillis() {
            return mTimestamp;
        }

        @Override
        public String getUri() {
            return mUri;
        }

        public long getLocalId() {
            return mLocalId;
        }

        public String getConversationId() {
            return mConversationId;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        private LocalDatabaseMessage(final Parcel in) {
            mUri = in.readString();
            mConversationId = in.readString();
            mLocalId = in.readLong();
            mTimestamp = in.readLong();
            mProtocol = in.readInt();
        }

        public static final Parcelable.Creator<LocalDatabaseMessage> CREATOR
                = new Parcelable.Creator<LocalDatabaseMessage>() {
            @Override
            public LocalDatabaseMessage createFromParcel(final Parcel in) {
                return new LocalDatabaseMessage(in);
            }

            @Override
            public LocalDatabaseMessage[] newArray(final int size) {
                return new LocalDatabaseMessage[size];
            }
        };

        @Override
        public void writeToParcel(final Parcel out, final int flags) {
            out.writeString(mUri);
            out.writeString(mConversationId);
            out.writeLong(mLocalId);
            out.writeLong(mTimestamp);
            out.writeInt(mProtocol);
        }
    }

    /**
     * Address for MMS message
     */
    public static class MmsAddr {
        public static final String[] PROJECTION = new String[] {
            Mms.Addr.ADDRESS,
            Mms.Addr.CHARSET,
        };
        private static int sIota = 0;
        public static final int INDEX_ADDRESS = sIota++;
        public static final int INDEX_CHARSET = sIota++;

        public static String get(final Cursor cursor) {
            final int charset = cursor.getInt(INDEX_CHARSET);
            // PduPersister stores the addresses using ISO_8859_1
            // Let's load it using that encoding and convert it back to its original
            // See PduPersister.persistAddress
            return getDecodedString(
                    getStringBytes(cursor.getString(INDEX_ADDRESS), CharacterSets.ISO_8859_1),
                    charset);
        }
    }

    /**
     * Decoded string by character set
     */
    public static String getDecodedString(final byte[] data, final int charset)  {
        if (CharacterSets.ANY_CHARSET == charset) {
            return new String(data); // system default encoding.
        } else {
            try {
                final String name = CharacterSets.getMimeName(charset);
                return new String(data, name);
            } catch (final UnsupportedEncodingException e) {
                try {
                    return new String(data, CharacterSets.MIMENAME_ISO_8859_1);
                } catch (final UnsupportedEncodingException exception) {
                    return new String(data); // system default encoding.
                }
            }
        }
    }

    /**
     * Unpack a given String into a byte[].
     */
    public static byte[] getStringBytes(final String data, final int charset) {
        if (CharacterSets.ANY_CHARSET == charset) {
            return data.getBytes();
        } else {
            try {
                final String name = CharacterSets.getMimeName(charset);
                return data.getBytes(name);
            } catch (final UnsupportedEncodingException e) {
                return data.getBytes();
            }
        }
    }
}
