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
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Telephony;
import android.provider.Telephony.Mms;
import android.provider.Telephony.Sms;
import android.provider.Telephony.Threads;
import android.telephony.SmsMessage;
import android.text.TextUtils;
import android.text.util.Rfc822Token;
import android.text.util.Rfc822Tokenizer;

import com.android.messaging.Factory;
import com.android.messaging.R;
import com.android.messaging.datamodel.MediaScratchFileProvider;
import com.android.messaging.datamodel.action.DownloadMmsAction;
import com.android.messaging.datamodel.action.SendMessageAction;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.datamodel.data.MessagePartData;
import com.android.messaging.mmslib.InvalidHeaderValueException;
import com.android.messaging.mmslib.MmsException;
import com.android.messaging.mmslib.SqliteWrapper;
import com.android.messaging.mmslib.pdu.CharacterSets;
import com.android.messaging.mmslib.pdu.EncodedStringValue;
import com.android.messaging.mmslib.pdu.GenericPdu;
import com.android.messaging.mmslib.pdu.NotificationInd;
import com.android.messaging.mmslib.pdu.PduBody;
import com.android.messaging.mmslib.pdu.PduComposer;
import com.android.messaging.mmslib.pdu.PduHeaders;
import com.android.messaging.mmslib.pdu.PduParser;
import com.android.messaging.mmslib.pdu.PduPart;
import com.android.messaging.mmslib.pdu.PduPersister;
import com.android.messaging.mmslib.pdu.RetrieveConf;
import com.android.messaging.mmslib.pdu.SendConf;
import com.android.messaging.mmslib.pdu.SendReq;
import com.android.messaging.sms.SmsSender.SendResult;
import com.android.messaging.util.Assert;
import com.android.messaging.util.BugleGservices;
import com.android.messaging.util.BugleGservicesKeys;
import com.android.messaging.util.BuglePrefs;
import com.android.messaging.util.ContentType;
import com.android.messaging.util.DebugUtils;
import com.android.messaging.util.EmailAddress;
import com.android.messaging.util.ImageUtils;
import com.android.messaging.util.ImageUtils.ImageResizer;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.MediaMetadataRetrieverWrapper;
import com.android.messaging.util.PhoneUtils;
import com.google.common.base.Joiner;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Utils for sending sms/mms messages.
 */
public class MmsUtils {
    private static final String TAG = LogUtil.BUGLE_TAG;

    public static final boolean DEFAULT_DELIVERY_REPORT_MODE  = false;
    public static final boolean DEFAULT_READ_REPORT_MODE = false;
    public static final long DEFAULT_EXPIRY_TIME_IN_SECONDS = 7 * 24 * 60 * 60;
    public static final int DEFAULT_PRIORITY = PduHeaders.PRIORITY_NORMAL;

    public static final int MAX_SMS_RETRY = 3;

    /**
     * MMS request succeeded
     */
    public static final int MMS_REQUEST_SUCCEEDED = 0;
    /**
     * MMS request failed with a transient error and can be retried automatically
     */
    public static final int MMS_REQUEST_AUTO_RETRY = 1;
    /**
     * MMS request failed with an error and can be retried manually
     */
    public static final int MMS_REQUEST_MANUAL_RETRY = 2;
    /**
     * MMS request failed with a specific error and should not be retried
     */
    public static final int MMS_REQUEST_NO_RETRY = 3;

    public static final String getRequestStatusDescription(final int status) {
        switch (status) {
            case MMS_REQUEST_SUCCEEDED:
                return "SUCCEEDED";
            case MMS_REQUEST_AUTO_RETRY:
                return "AUTO_RETRY";
            case MMS_REQUEST_MANUAL_RETRY:
                return "MANUAL_RETRY";
            case MMS_REQUEST_NO_RETRY:
                return "NO_RETRY";
            default:
                return String.valueOf(status) + " (check MmsUtils)";
        }
    }

    public static final int PDU_HEADER_VALUE_UNDEFINED = 0;

    private static final int DEFAULT_DURATION = 5000; //ms

    // amount of space to leave in a MMS for text and overhead.
    private static final int MMS_MAX_SIZE_SLOP = 1024;
    public static final long INVALID_TIMESTAMP = 0L;
    private static String[] sNoSubjectStrings;

    public static class MmsInfo {
        public Uri mUri;
        public int mMessageSize;
        public PduBody mPduBody;
    }

    // Sync all remote messages apart from drafts
    private static final String REMOTE_SMS_SELECTION = String.format(
            Locale.US,
            "(%s IN (%d, %d, %d, %d, %d))",
            Sms.TYPE,
            Sms.MESSAGE_TYPE_INBOX,
            Sms.MESSAGE_TYPE_OUTBOX,
            Sms.MESSAGE_TYPE_QUEUED,
            Sms.MESSAGE_TYPE_FAILED,
            Sms.MESSAGE_TYPE_SENT);

    private static final String REMOTE_MMS_SELECTION = String.format(
            Locale.US,
            "((%s IN (%d, %d, %d, %d)) AND (%s IN (%d, %d, %d)))",
            Mms.MESSAGE_BOX,
            Mms.MESSAGE_BOX_INBOX,
            Mms.MESSAGE_BOX_OUTBOX,
            Mms.MESSAGE_BOX_SENT,
            Mms.MESSAGE_BOX_FAILED,
            Mms.MESSAGE_TYPE,
            PduHeaders.MESSAGE_TYPE_SEND_REQ,
            PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND,
            PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF);

    /**
     * Type selection for importing sms messages.
     *
     * @return The SQL selection for importing sms messages
     */
    public static String getSmsTypeSelectionSql() {
        return REMOTE_SMS_SELECTION;
    }

    /**
     * Type selection for importing mms messages.
     *
     * @return The SQL selection for importing mms messages. This selects the message type,
     * not including the selection on timestamp.
     */
    public static String getMmsTypeSelectionSql() {
        return REMOTE_MMS_SELECTION;
    }

    // SMIL spec: http://www.w3.org/TR/SMIL3

    private static final String sSmilImagePart =
            "<par dur=\"" + DEFAULT_DURATION + "ms\">" +
                "<img src=\"%s\" region=\"Image\" />" +
            "</par>";

    private static final String sSmilVideoPart =
            "<par dur=\"%2$dms\">" +
                "<video src=\"%1$s\" dur=\"%2$dms\" region=\"Image\" />" +
            "</par>";

    private static final String sSmilAudioPart =
            "<par dur=\"%2$dms\">" +
                    "<audio src=\"%1$s\" dur=\"%2$dms\" />" +
            "</par>";

    private static final String sSmilTextPart =
            "<par dur=\"" + DEFAULT_DURATION + "ms\">" +
                "<text src=\"%s\" region=\"Text\" />" +
            "</par>";

    private static final String sSmilPart =
            "<par dur=\"" + DEFAULT_DURATION + "ms\">" +
                "<ref src=\"%s\" />" +
            "</par>";

    private static final String sSmilTextOnly =
            "<smil>" +
                "<head>" +
                    "<layout>" +
                        "<root-layout/>" +
                        "<region id=\"Text\" top=\"0\" left=\"0\" "
                          + "height=\"100%%\" width=\"100%%\"/>" +
                    "</layout>" +
                "</head>" +
                "<body>" +
                       "%s" +  // constructed body goes here
                "</body>" +
            "</smil>";

    private static final String sSmilVisualAttachmentsOnly =
            "<smil>" +
                "<head>" +
                    "<layout>" +
                        "<root-layout/>" +
                        "<region id=\"Image\" fit=\"meet\" top=\"0\" left=\"0\" "
                          + "height=\"100%%\" width=\"100%%\"/>" +
                    "</layout>" +
                "</head>" +
                "<body>" +
                       "%s" +  // constructed body goes here
                "</body>" +
            "</smil>";

    private static final String sSmilVisualAttachmentsWithText =
            "<smil>" +
                "<head>" +
                    "<layout>" +
                        "<root-layout/>" +
                        "<region id=\"Image\" fit=\"meet\" top=\"0\" left=\"0\" "
                          + "height=\"80%%\" width=\"100%%\"/>" +
                        "<region id=\"Text\" top=\"80%%\" left=\"0\" height=\"20%%\" "
                          + "width=\"100%%\"/>" +
                    "</layout>" +
                "</head>" +
                "<body>" +
                       "%s" +  // constructed body goes here
                "</body>" +
            "</smil>";

    private static final String sSmilNonVisualAttachmentsOnly =
            "<smil>" +
                "<head>" +
                    "<layout>" +
                        "<root-layout/>" +
                    "</layout>" +
                "</head>" +
                "<body>" +
                       "%s" +  // constructed body goes here
                "</body>" +
            "</smil>";

    private static final String sSmilNonVisualAttachmentsWithText = sSmilTextOnly;

    public static final String MMS_DUMP_PREFIX = "mmsdump-";
    public static final String SMS_DUMP_PREFIX = "smsdump-";

    public static final int MIN_VIDEO_BYTES_PER_SECOND = 4 * 1024;
    public static final int MIN_IMAGE_BYTE_SIZE = 16 * 1024;
    public static final int MAX_VIDEO_ATTACHMENT_COUNT = 1;

    public static MmsInfo makePduBody(final Context context, final MessageData message,
            final int subId) {
        final PduBody pb = new PduBody();

        // Compute data size requirements for this message: count up images and total size of
        // non-image attachments.
        int totalLength = 0;
        int countImage = 0;
        for (final MessagePartData part : message.getParts()) {
            if (part.isAttachment()) {
                final String contentType = part.getContentType();
                if (ContentType.isImageType(contentType)) {
                    countImage++;
                } else if (ContentType.isVCardType(contentType)) {
                    totalLength += getDataLength(context, part.getContentUri());
                } else {
                    totalLength += getMediaFileSize(part.getContentUri());
                }
            }
        }
        final long minSize = countImage * MIN_IMAGE_BYTE_SIZE;
        final int byteBudget = MmsConfig.get(subId).getMaxMessageSize() - totalLength
                - MMS_MAX_SIZE_SLOP;
        final double budgetFactor =
                minSize > 0 ? Math.max(1.0, byteBudget / ((double) minSize)) : 1;
        final int bytesPerImage = (int) (budgetFactor * MIN_IMAGE_BYTE_SIZE);
        final int widthLimit = MmsConfig.get(subId).getMaxImageWidth();
        final int heightLimit = MmsConfig.get(subId).getMaxImageHeight();

        // Actually add the attachments, shrinking images appropriately.
        int index = 0;
        totalLength = 0;
        boolean hasVisualAttachment = false;
        boolean hasNonVisualAttachment = false;
        boolean hasText = false;
        final StringBuilder smilBody = new StringBuilder();
        for (final MessagePartData part : message.getParts()) {
            String srcName;
            if (part.isAttachment()) {
                String contentType = part.getContentType();
                final String extension = ContentType.getExtensionFromMimeType(contentType);
                if (ContentType.isImageType(contentType)) {
                    if (extension != null) {
                        srcName = String.format("image%06d.%s", index, extension);
                    } else {
                        // There's a good chance that if we selected the image from our media picker
                        // the content type is image/*. Fix the content type here for gifs so that
                        // we only need to open the input stream once. All other gif vs static image
                        // checks will only have to do a string comparison which is much cheaper.
                        final boolean isGif = ImageUtils.isGif(contentType, part.getContentUri());
                        contentType = isGif ? ContentType.IMAGE_GIF : contentType;
                        srcName = String.format(isGif ? "image%06d.gif" : "image%06d.jpg", index);
                    }
                    smilBody.append(String.format(sSmilImagePart, srcName));
                    totalLength += addPicturePart(context, pb, index, part,
                            widthLimit, heightLimit, bytesPerImage, srcName, contentType);
                    hasVisualAttachment = true;
                } else if (ContentType.isVideoType(contentType)) {
                    srcName = String.format("video%06d.%s", index,
                            extension != null ? extension : "mp4");
                    final int length = addVideoPart(context, pb, part, srcName);
                    totalLength += length;
                    smilBody.append(String.format(sSmilVideoPart, srcName,
                            getMediaDurationMs(context, part, DEFAULT_DURATION)));
                    hasVisualAttachment = true;
                } else if (ContentType.isVCardType(contentType)) {
                    srcName = String.format("contact%06d.vcf", index);
                    totalLength += addVCardPart(context, pb, part, srcName);
                    smilBody.append(String.format(sSmilPart, srcName));
                    hasNonVisualAttachment = true;
                } else if (ContentType.isAudioType(contentType)) {
                    srcName = String.format("recording%06d.%s",
                            index, extension != null ? extension : "amr");
                    totalLength += addOtherPart(context, pb, part, srcName);
                    final int duration = getMediaDurationMs(context, part, -1);
                    Assert.isTrue(duration != -1);
                    smilBody.append(String.format(sSmilAudioPart, srcName, duration));
                    hasNonVisualAttachment = true;
                } else {
                    srcName = String.format("other%06d.dat", index);
                    totalLength += addOtherPart(context, pb, part, srcName);
                    smilBody.append(String.format(sSmilPart, srcName));
                }
                index++;
            }
            if (!TextUtils.isEmpty(part.getText())) {
                hasText = true;
            }
        }

        if (hasText) {
            final String srcName = String.format("text.%06d.txt", index);
            final String text = message.getMessageText();
            totalLength += addTextPart(context, pb, text, srcName);

            // Append appropriate SMIL to the body.
            smilBody.append(String.format(sSmilTextPart, srcName));
        }

        final String smilTemplate = getSmilTemplate(hasVisualAttachment,
                hasNonVisualAttachment, hasText);
        addSmilPart(pb, smilTemplate, smilBody.toString());

        final MmsInfo mmsInfo = new MmsInfo();
        mmsInfo.mPduBody = pb;
        mmsInfo.mMessageSize = totalLength;

        return mmsInfo;
    }

    private static int getMediaDurationMs(final Context context, final MessagePartData part,
            final int defaultDurationMs) {
        Assert.notNull(context);
        Assert.notNull(part);
        Assert.isTrue(ContentType.isAudioType(part.getContentType()) ||
                ContentType.isVideoType(part.getContentType()));

        final MediaMetadataRetrieverWrapper retriever = new MediaMetadataRetrieverWrapper();
        try {
            retriever.setDataSource(part.getContentUri());
            return retriever.extractInteger(
                    MediaMetadataRetriever.METADATA_KEY_DURATION, defaultDurationMs);
        } catch (final IOException e) {
            LogUtil.i(LogUtil.BUGLE_TAG, "Error extracting duration from " + part.getContentUri(), e);
            return defaultDurationMs;
        } finally {
            retriever.release();
        }
    }

    private static void setPartContentLocationAndId(final PduPart part, final String srcName) {
        // Set Content-Location.
        part.setContentLocation(srcName.getBytes());

        // Set Content-Id.
        final int index = srcName.lastIndexOf(".");
        final String contentId = (index == -1) ? srcName : srcName.substring(0, index);
        part.setContentId(contentId.getBytes());
    }

    private static int addTextPart(final Context context, final PduBody pb,
            final String text, final String srcName) {
        final PduPart part = new PduPart();

        // Set Charset if it's a text media.
        part.setCharset(CharacterSets.UTF_8);

        // Set Content-Type.
        part.setContentType(ContentType.TEXT_PLAIN.getBytes());

        // Set Content-Location.
        setPartContentLocationAndId(part, srcName);

        part.setData(text.getBytes());

        pb.addPart(part);

        return part.getData().length;
    }

    private static int addPicturePart(final Context context, final PduBody pb, final int index,
            final MessagePartData messagePart, int widthLimit, int heightLimit,
            final int maxPartSize, final String srcName, final String contentType) {
        final Uri imageUri = messagePart.getContentUri();
        final int width = messagePart.getWidth();
        final int height = messagePart.getHeight();

        // Swap the width and height limits to match the orientation of the image so we scale the
        // picture as little as possible.
        if ((height > width) != (heightLimit > widthLimit)) {
            final int temp = widthLimit;
            widthLimit = heightLimit;
            heightLimit = temp;
        }

        final int orientation = ImageUtils.getOrientation(context, imageUri);
        int imageSize = getDataLength(context, imageUri);
        if (imageSize <= 0) {
            LogUtil.e(TAG, "Can't get image", new Exception());
            return 0;
        }

        if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
            LogUtil.v(TAG, "addPicturePart size: " + imageSize + " width: "
                    + width + " widthLimit: " + widthLimit
                    + " height: " + height
                    + " heightLimit: " + heightLimit);
        }

        PduPart part;
        // Check if we're already within the limits - in which case we don't need to resize.
        // The size can be zero here, even when the media has content. See the comment in
        // MediaModel.initMediaSize. Sometimes it'll compute zero and it's costly to read the
        // whole stream to compute the size. When we call getResizedImageAsPart(), we'll correctly
        // set the size.
        if (imageSize <= maxPartSize &&
                width <= widthLimit &&
                height <= heightLimit &&
                (orientation == android.media.ExifInterface.ORIENTATION_UNDEFINED ||
                orientation == android.media.ExifInterface.ORIENTATION_NORMAL)) {
            if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
                LogUtil.v(TAG, "addPicturePart - already sized");
            }
            part = new PduPart();
            part.setDataUri(imageUri);
            part.setContentType(contentType.getBytes());
        } else {
            part = getResizedImageAsPart(widthLimit, heightLimit, maxPartSize,
                    width, height, orientation, imageUri, context, contentType);
            if (part == null) {
                final OutOfMemoryError e = new OutOfMemoryError();
                LogUtil.e(TAG, "Can't resize image: not enough memory?", e);
                throw e;
            }
            imageSize = part.getData().length;
        }

        setPartContentLocationAndId(part, srcName);

        pb.addPart(index, part);

        if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
            LogUtil.v(TAG, "addPicturePart size: " + imageSize);
        }

        return imageSize;
    }

    private static void addPartForUri(final Context context, final PduBody pb,
            final String srcName, final Uri uri, final String contentType) {
        final PduPart part = new PduPart();
        part.setDataUri(uri);
        part.setContentType(contentType.getBytes());

        setPartContentLocationAndId(part, srcName);

        pb.addPart(part);
    }

    private static int addVCardPart(final Context context, final PduBody pb,
            final MessagePartData messagePart, final String srcName) {
        final Uri vcardUri = messagePart.getContentUri();
        final String contentType = messagePart.getContentType();
        final int vcardSize = getDataLength(context, vcardUri);
        if (vcardSize <= 0) {
            LogUtil.e(TAG, "Can't get vcard", new Exception());
            return 0;
        }

        addPartForUri(context, pb, srcName, vcardUri, contentType);

        if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
            LogUtil.v(TAG, "addVCardPart size: " + vcardSize);
        }

        return vcardSize;
    }

    /**
     * Add video part recompressing video if necessary.  If recompression fails, part is not
     * added.
     */
    private static int addVideoPart(final Context context, final PduBody pb,
            final MessagePartData messagePart, final String srcName) {
        final Uri attachmentUri = messagePart.getContentUri();
        String contentType = messagePart.getContentType();

        if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
            LogUtil.v(TAG, "addPart attachmentUrl: " + attachmentUri.toString());
        }

        if (TextUtils.isEmpty(contentType)) {
            contentType = ContentType.VIDEO_3G2;
        }

        addPartForUri(context, pb, srcName, attachmentUri, contentType);
        return (int) getMediaFileSize(attachmentUri);
    }

    private static int addOtherPart(final Context context, final PduBody pb,
            final MessagePartData messagePart, final String srcName) {
        final Uri attachmentUri = messagePart.getContentUri();
        final String contentType = messagePart.getContentType();

        if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
            LogUtil.v(TAG, "addPart attachmentUrl: " + attachmentUri.toString());
        }

        final int dataSize = (int) getMediaFileSize(attachmentUri);

        addPartForUri(context, pb, srcName, attachmentUri, contentType);

        return dataSize;
    }

    private static void addSmilPart(final PduBody pb, final String smilTemplate,
            final String smilBody) {
        final PduPart smilPart = new PduPart();
        smilPart.setContentId("smil".getBytes());
        smilPart.setContentLocation("smil.xml".getBytes());
        smilPart.setContentType(ContentType.APP_SMIL.getBytes());
        final String smil = String.format(smilTemplate, smilBody);
        smilPart.setData(smil.getBytes());
        pb.addPart(0, smilPart);
    }

    private static String getSmilTemplate(final boolean hasVisualAttachments,
            final boolean hasNonVisualAttachments, final boolean hasText) {
        if (hasVisualAttachments) {
            return hasText ? sSmilVisualAttachmentsWithText : sSmilVisualAttachmentsOnly;
        }
        if (hasNonVisualAttachments) {
            return hasText ? sSmilNonVisualAttachmentsWithText : sSmilNonVisualAttachmentsOnly;
        }
        return sSmilTextOnly;
    }

    private static int getDataLength(final Context context, final Uri uri) {
        InputStream is = null;
        try {
            is = context.getContentResolver().openInputStream(uri);
            try {
                return is == null ? 0 : is.available();
            } catch (final IOException e) {
                LogUtil.e(TAG, "getDataLength couldn't stream: " + uri, e);
            }
        } catch (final FileNotFoundException e) {
            LogUtil.e(TAG, "getDataLength couldn't open: " + uri, e);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (final IOException e) {
                    LogUtil.e(TAG, "getDataLength couldn't close: " + uri, e);
                }
            }
        }
        return 0;
    }

    /**
     * Returns {@code true} if group mms is turned on,
     * {@code false} otherwise.
     *
     * For the group mms feature to be enabled, the following must be true:
     *  1. the feature is enabled in mms_config.xml (currently on by default)
     *  2. the feature is enabled in the SMS settings page
     *
     * @return true if group mms is supported
     */
    public static boolean groupMmsEnabled(final int subId) {
        final Context context = Factory.get().getApplicationContext();
        final Resources resources = context.getResources();
        final BuglePrefs prefs = BuglePrefs.getSubscriptionPrefs(subId);
        final String groupMmsKey = resources.getString(R.string.group_mms_pref_key);
        final boolean groupMmsEnabledDefault = resources.getBoolean(R.bool.group_mms_pref_default);
        final boolean groupMmsPrefOn = prefs.getBoolean(groupMmsKey, groupMmsEnabledDefault);
        return MmsConfig.get(subId).getGroupMmsEnabled() && groupMmsPrefOn;
    }

    /**
     * Get a version of this image resized to fit the given dimension and byte-size limits. Note
     * that the content type of the resulting PduPart may not be the same as the content type of
     * this UriImage; always call {@link PduPart#getContentType()} to get the new content type.
     *
     * @param widthLimit The width limit, in pixels
     * @param heightLimit The height limit, in pixels
     * @param byteLimit The binary size limit, in bytes
     * @param width The image width, in pixels
     * @param height The image height, in pixels
     * @param orientation Orientation constant from ExifInterface for rotating or flipping the
     *                    image
     * @param imageUri Uri to the image data
     * @param context Needed to open the image
     * @return A new PduPart containing the resized image data
     */
    private static PduPart getResizedImageAsPart(final int widthLimit,
            final int heightLimit, final int byteLimit, final int width, final int height,
            final int orientation, final Uri imageUri, final Context context, final String contentType) {
        final PduPart part = new PduPart();

        final byte[] data = ImageResizer.getResizedImageData(width, height, orientation,
                widthLimit, heightLimit, byteLimit, imageUri, context, contentType);
        if (data == null) {
            if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
                LogUtil.v(TAG, "Resize image failed.");
            }
            return null;
        }

        part.setData(data);
        // Any static images will be compressed into a jpeg
        final String contentTypeOfResizedImage = ImageUtils.isGif(contentType, imageUri)
                ? ContentType.IMAGE_GIF : ContentType.IMAGE_JPEG;
        part.setContentType(contentTypeOfResizedImage.getBytes());

        return part;
    }

    /**
     * Get media file size
     */
    public static long getMediaFileSize(final Uri uri) {
        final Context context = Factory.get().getApplicationContext();
        AssetFileDescriptor fd = null;
        try {
            fd = context.getContentResolver().openAssetFileDescriptor(uri, "r");
            if (fd != null) {
                return fd.getParcelFileDescriptor().getStatSize();
            }
        } catch (final FileNotFoundException e) {
            LogUtil.e(TAG, "MmsUtils.getMediaFileSize: cound not find media file: " + e, e);
        } finally {
            if (fd != null) {
                try {
                    fd.close();
                } catch (final IOException e) {
                    LogUtil.e(TAG, "MmsUtils.getMediaFileSize: failed to close " + e, e);
                }
            }
        }
        return 0L;
    }

    // Code for extracting the actual phone numbers for the participants in a conversation,
    // given a thread id.

    private static final Uri ALL_THREADS_URI =
            Threads.CONTENT_URI.buildUpon().appendQueryParameter("simple", "true").build();

    private static final String[] RECIPIENTS_PROJECTION = {
        Threads._ID,
        Threads.RECIPIENT_IDS
    };

    private static final int RECIPIENT_IDS  = 1;

    public static List<String> getRecipientsByThread(final long threadId) {
        final String spaceSepIds = getRawRecipientIdsForThread(threadId);
        if (!TextUtils.isEmpty(spaceSepIds)) {
            final Context context = Factory.get().getApplicationContext();
            return getAddresses(context, spaceSepIds);
        }
        return null;
    }

    // NOTE: There are phones on which you can't get the recipients from the thread id for SMS
    // until you have a message in the conversation!
    public static String getRawRecipientIdsForThread(final long threadId) {
        if (threadId <= 0) {
            return null;
        }
        final Context context = Factory.get().getApplicationContext();
        final ContentResolver cr = context.getContentResolver();
        final Cursor thread = cr.query(
                ALL_THREADS_URI,
                RECIPIENTS_PROJECTION, "_id=?", new String[] { String.valueOf(threadId) }, null);
        if (thread != null) {
            try {
                if (thread.moveToFirst()) {
                    // recipientIds will be a space-separated list of ids into the
                    // canonical addresses table.
                    return thread.getString(RECIPIENT_IDS);
                }
            } finally {
                thread.close();
            }
        }
        return null;
    }

    private static final Uri SINGLE_CANONICAL_ADDRESS_URI =
            Uri.parse("content://mms-sms/canonical-address");

    private static List<String> getAddresses(final Context context, final String spaceSepIds) {
        final List<String> numbers = new ArrayList<String>();
        final String[] ids = spaceSepIds.split(" ");
        for (final String id : ids) {
            long longId;

            try {
                longId = Long.parseLong(id);
                if (longId < 0) {
                    LogUtil.e(TAG, "MmsUtils.getAddresses: invalid id " + longId);
                    continue;
                }
            } catch (final NumberFormatException ex) {
                LogUtil.e(TAG, "MmsUtils.getAddresses: invalid id. " + ex, ex);
                // skip this id
                continue;
            }

            // TODO: build a single query where we get all the addresses at once.
            Cursor c = null;
            try {
                c = context.getContentResolver().query(
                        ContentUris.withAppendedId(SINGLE_CANONICAL_ADDRESS_URI, longId),
                        null, null, null, null);
            } catch (final Exception e) {
                LogUtil.e(TAG, "MmsUtils.getAddresses: query failed for id " + longId, e);
            }
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        final String number = c.getString(0);
                        if (!TextUtils.isEmpty(number)) {
                            numbers.add(number);
                        } else {
                            LogUtil.w(TAG, "Canonical MMS/SMS address is empty for id: " + longId);
                        }
                    }
                } finally {
                    c.close();
                }
            }
        }
        if (numbers.isEmpty()) {
            LogUtil.w(TAG, "No MMS addresses found from ids string [" + spaceSepIds + "]");
        }
        return numbers;
    }

    // Get telephony SMS thread ID
    public static long getOrCreateSmsThreadId(final Context context, final String dest) {
        // use destinations to determine threadId
        final Set<String> recipients = new HashSet<String>();
        recipients.add(dest);
        try {
            return MmsSmsUtils.Threads.getOrCreateThreadId(context, recipients);
        } catch (final IllegalArgumentException e) {
            LogUtil.e(TAG, "MmsUtils: getting thread id failed: " + e);
            return -1;
        }
    }

    // Get telephony SMS thread ID
    public static long getOrCreateThreadId(final Context context, final List<String> dests) {
        if (dests == null || dests.size() == 0) {
            return -1;
        }
        // use destinations to determine threadId
        final Set<String> recipients = new HashSet<String>(dests);
        try {
            return MmsSmsUtils.Threads.getOrCreateThreadId(context, recipients);
        } catch (final IllegalArgumentException e) {
            LogUtil.e(TAG, "MmsUtils: getting thread id failed: " + e);
            return -1;
        }
    }

    /**
     * Add an SMS to the given URI with thread_id specified.
     *
     * @param resolver the content resolver to use
     * @param uri the URI to add the message to
     * @param subId subId for the receiving sim
     * @param address the address of the sender
     * @param body the body of the message
     * @param subject the psuedo-subject of the message
     * @param date the timestamp for the message
     * @param read true if the message has been read, false if not
     * @param threadId the thread_id of the message
     * @return the URI for the new message
     */
    private static Uri addMessageToUri(final ContentResolver resolver,
            final Uri uri, final int subId, final String address, final String body,
            final String subject, final Long date, final boolean read, final boolean seen,
            final int status, final int type, final long threadId) {
        final ContentValues values = new ContentValues(7);

        values.put(Telephony.Sms.ADDRESS, address);
        if (date != null) {
            values.put(Telephony.Sms.DATE, date);
        }
        values.put(Telephony.Sms.READ, read ? 1 : 0);
        values.put(Telephony.Sms.SEEN, seen ? 1 : 0);
        values.put(Telephony.Sms.SUBJECT, subject);
        values.put(Telephony.Sms.BODY, body);
        values.put(Telephony.Sms.SUBSCRIPTION_ID, subId);
        if (status != Telephony.Sms.STATUS_NONE) {
            values.put(Telephony.Sms.STATUS, status);
        }
        if (type != Telephony.Sms.MESSAGE_TYPE_ALL) {
            values.put(Telephony.Sms.TYPE, type);
        }
        if (threadId != -1L) {
            values.put(Telephony.Sms.THREAD_ID, threadId);
        }
        return resolver.insert(uri, values);
    }

    // Insert an SMS message to telephony
    public static Uri insertSmsMessage(final Context context, final Uri uri, final int subId,
            final String dest, final String text, final long timestamp, final int status,
            final int type, final long threadId) {
        Uri response = null;
        try {
            response = addMessageToUri(context.getContentResolver(), uri, subId, dest,
                    text, null /* subject */, timestamp, true /* read */,
                    true /* seen */, status, type, threadId);
            if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
                LogUtil.d(TAG, "Mmsutils: Inserted SMS message into telephony (type = " + type + ")"
                        + ", uri: " + response);
            }
        } catch (final SQLiteException e) {
            LogUtil.e(TAG, "MmsUtils: persist sms message failure " + e, e);
        } catch (final IllegalArgumentException e) {
            LogUtil.e(TAG, "MmsUtils: persist sms message failure " + e, e);
        }
        return response;
    }

    // Update SMS message type in telephony; returns true if it succeeded.
    public static boolean updateSmsMessageSendingStatus(final Context context, final Uri uri,
            final int type, final long date) {
        try {
            final ContentResolver resolver = context.getContentResolver();
            final ContentValues values = new ContentValues(2);

            values.put(Telephony.Sms.TYPE, type);
            values.put(Telephony.Sms.DATE, date);
            final int cnt = resolver.update(uri, values, null, null);
            if (cnt == 1) {
                if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
                    LogUtil.d(TAG, "Mmsutils: Updated sending SMS " + uri + "; type = " + type
                            + ", date = " + date + " (millis since epoch)");
                }
                return true;
            }
        } catch (final SQLiteException e) {
            LogUtil.e(TAG, "MmsUtils: update sms message failure " + e, e);
        } catch (final IllegalArgumentException e) {
            LogUtil.e(TAG, "MmsUtils: update sms message failure " + e, e);
        }
        return false;
    }

    // Persist a sent MMS message in telephony
    private static Uri insertSendReq(final Context context, final GenericPdu pdu, final int subId,
            final String subPhoneNumber) {
        final PduPersister persister = PduPersister.getPduPersister(context);
        Uri uri = null;
        try {
            // Persist the PDU
            uri = persister.persist(
                    pdu,
                    Mms.Sent.CONTENT_URI,
                    subId,
                    subPhoneNumber,
                    null/*preOpenedFiles*/);
            // Update mms table to reflect sent messages are always seen and read
            final ContentValues values = new ContentValues(1);
            values.put(Mms.READ, 1);
            values.put(Mms.SEEN, 1);
            SqliteWrapper.update(context, context.getContentResolver(), uri, values, null, null);
        } catch (final MmsException e) {
            LogUtil.e(TAG, "MmsUtils: persist mms sent message failure " + e, e);
        }
        return uri;
    }

    // Persist a received MMS message in telephony
    public static Uri insertReceivedMmsMessage(final Context context,
            final RetrieveConf retrieveConf, final int subId, final String subPhoneNumber,
            final long receivedTimestampInSeconds, final long expiry, final String transactionId) {
        final PduPersister persister = PduPersister.getPduPersister(context);
        Uri uri = null;
        try {
            uri = persister.persist(
                    retrieveConf,
                    Mms.Inbox.CONTENT_URI,
                    subId,
                    subPhoneNumber,
                    null/*preOpenedFiles*/);

            final ContentValues values = new ContentValues(3);
            // Update mms table with local time instead of PDU time
            values.put(Mms.DATE, receivedTimestampInSeconds);
            // Also update the transaction id and the expiry from NotificationInd so that
            // wap push dedup would work even after the wap push is deleted.
            values.put(Mms.TRANSACTION_ID, transactionId);
            values.put(Mms.EXPIRY, expiry);
            SqliteWrapper.update(context, context.getContentResolver(), uri, values, null, null);
            if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
                LogUtil.d(TAG, "MmsUtils: Inserted MMS message into telephony, uri: " + uri);
            }
        } catch (final MmsException e) {
            LogUtil.e(TAG, "MmsUtils: persist mms received message failure " + e, e);
            // Just returns empty uri to RetrieveMmsRequest, which triggers a permanent failure
        } catch (final SQLiteException e) {
            LogUtil.e(TAG, "MmsUtils: update mms received message failure " + e, e);
            // Time update failure is ignored.
        }
        return uri;
    }

    // Update MMS message type in telephony; returns true if it succeeded.
    public static boolean updateMmsMessageSendingStatus(final Context context, final Uri uri,
            final int box, final long timestampInMillis) {
        try {
            final ContentResolver resolver = context.getContentResolver();
            final ContentValues values = new ContentValues();

            final long timestampInSeconds = timestampInMillis / 1000L;
            values.put(Telephony.Mms.MESSAGE_BOX, box);
            values.put(Telephony.Mms.DATE, timestampInSeconds);
            final int cnt = resolver.update(uri, values, null, null);
            if (cnt == 1) {
                if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
                    LogUtil.d(TAG, "Mmsutils: Updated sending MMS " + uri + "; box = " + box
                            + ", date = " + timestampInSeconds + " (secs since epoch)");
                }
                return true;
            }
        } catch (final SQLiteException e) {
            LogUtil.e(TAG, "MmsUtils: update mms message failure " + e, e);
        } catch (final IllegalArgumentException e) {
            LogUtil.e(TAG, "MmsUtils: update mms message failure " + e, e);
        }
        return false;
    }

    /**
     * Parse values from a received sms message
     *
     * @param context
     * @param msgs The received sms message content
     * @param error The received sms error
     * @return Parsed values from the message
     */
    public static ContentValues parseReceivedSmsMessage(
            final Context context, final SmsMessage[] msgs, final int error) {
        final SmsMessage sms = msgs[0];
        final ContentValues values = new ContentValues();

        values.put(Sms.ADDRESS, sms.getDisplayOriginatingAddress());
        values.put(Sms.BODY, buildMessageBodyFromPdus(msgs));
        if (MmsUtils.hasSmsDateSentColumn()) {
            // TODO:: The boxing here seems unnecessary.
            values.put(Sms.DATE_SENT, Long.valueOf(sms.getTimestampMillis()));
        }
        values.put(Sms.PROTOCOL, sms.getProtocolIdentifier());
        if (sms.getPseudoSubject().length() > 0) {
            values.put(Sms.SUBJECT, sms.getPseudoSubject());
        }
        values.put(Sms.REPLY_PATH_PRESENT, sms.isReplyPathPresent() ? 1 : 0);
        values.put(Sms.SERVICE_CENTER, sms.getServiceCenterAddress());
        // Error code
        values.put(Sms.ERROR_CODE, error);

        return values;
    }

    // Some providers send formfeeds in their messages. Convert those formfeeds to newlines.
    private static String replaceFormFeeds(final String s) {
        return s == null ? "" : s.replace('\f', '\n');
    }

    // Parse the message body from message PDUs
    private static String buildMessageBodyFromPdus(final SmsMessage[] msgs) {
        if (msgs.length == 1) {
            // There is only one part, so grab the body directly.
            return replaceFormFeeds(msgs[0].getDisplayMessageBody());
        } else {
            // Build up the body from the parts.
            final StringBuilder body = new StringBuilder();
            for (final SmsMessage msg : msgs) {
                try {
                    // getDisplayMessageBody() can NPE if mWrappedMessage inside is null.
                    body.append(msg.getDisplayMessageBody());
                } catch (final NullPointerException e) {
                    // Nothing to do
                }
            }
            return replaceFormFeeds(body.toString());
        }
    }

    // Parse the message date
    public static Long getMessageDate(final SmsMessage sms, long now) {
        // Use now for the timestamp to avoid confusion with clock
        // drift between the handset and the SMSC.
        // Check to make sure the system is giving us a non-bogus time.
        final Calendar buildDate = new GregorianCalendar(2011, 8, 18);    // 18 Sep 2011
        final Calendar nowDate = new GregorianCalendar();
        nowDate.setTimeInMillis(now);
        if (nowDate.before(buildDate)) {
            // It looks like our system clock isn't set yet because the current time right now
            // is before an arbitrary time we made this build. Instead of inserting a bogus
            // receive time in this case, use the timestamp of when the message was sent.
            now = sms.getTimestampMillis();
        }
        return now;
    }

    /**
     * cleanseMmsSubject will take a subject that's says, "<Subject: no subject>", and return
     * a null string. Otherwise it will return the original subject string.
     * @param resources So the function can grab string resources
     * @param subject the raw subject
     * @return
     */
    public static String cleanseMmsSubject(final Resources resources, final String subject) {
        if (TextUtils.isEmpty(subject)) {
            return null;
        }
        if (sNoSubjectStrings == null) {
            sNoSubjectStrings =
                    resources.getStringArray(R.array.empty_subject_strings);
        }
        for (final String noSubjectString : sNoSubjectStrings) {
            if (subject.equalsIgnoreCase(noSubjectString)) {
                return null;
            }
        }
        return subject;
    }

    /**
     * @return Whether to auto retrieve MMS
     */
    public static boolean allowMmsAutoRetrieve(final int subId) {
        final Context context = Factory.get().getApplicationContext();
        final Resources resources = context.getResources();
        final BuglePrefs prefs = BuglePrefs.getSubscriptionPrefs(subId);
        final boolean autoRetrieve = prefs.getBoolean(
                resources.getString(R.string.auto_retrieve_mms_pref_key),
                resources.getBoolean(R.bool.auto_retrieve_mms_pref_default));
        if (autoRetrieve) {
            final boolean autoRetrieveInRoaming = prefs.getBoolean(
                    resources.getString(R.string.auto_retrieve_mms_when_roaming_pref_key),
                    resources.getBoolean(R.bool.auto_retrieve_mms_when_roaming_pref_default));
            final PhoneUtils phoneUtils = PhoneUtils.get(subId);
            if ((autoRetrieveInRoaming && phoneUtils.isDataRoamingEnabled())
                    || !phoneUtils.isRoaming()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parse the message row id from a message Uri.
     *
     * @param messageUri The input Uri
     * @return The message row id if valid, otherwise -1
     */
    public static long parseRowIdFromMessageUri(final Uri messageUri) {
        try {
            if (messageUri != null) {
                return ContentUris.parseId(messageUri);
            }
        } catch (final UnsupportedOperationException e) {
            // Nothing to do
        } catch (final NumberFormatException e) {
            // Nothing to do
        }
        return -1;
    }

    public static SmsMessage getSmsMessageFromDeliveryReport(final Intent intent) {
        final byte[] pdu = intent.getByteArrayExtra("pdu");
        final String format = intent.getStringExtra("format");
        return SmsMessage.createFromPdu(pdu, format);
    }

    /**
     * Update the status and date_sent column of sms message in telephony provider
     *
     * @param smsMessageUri
     * @param status
     * @param timeSentInMillis
     */
    public static void updateSmsStatusAndDateSent(final Uri smsMessageUri, final int status,
            final long timeSentInMillis) {
        if (smsMessageUri == null) {
            return;
        }
        final ContentValues values = new ContentValues();
        values.put(Sms.STATUS, status);
        if (MmsUtils.hasSmsDateSentColumn()) {
            values.put(Sms.DATE_SENT, timeSentInMillis);
        }
        final ContentResolver resolver = Factory.get().getApplicationContext().getContentResolver();
        resolver.update(smsMessageUri, values, null/*where*/, null/*selectionArgs*/);
    }

    /**
     * Get the SQL selection statement for matching messages with media.
     *
     * Example for MMS part table:
     * "((ct LIKE 'image/%')
     *   OR (ct LIKE 'video/%')
     *   OR (ct LIKE 'audio/%')
     *   OR (ct='application/ogg'))
     *
     * @param contentTypeColumn The content-type column name
     * @return The SQL selection statement for matching media types: image, video, audio
     */
    public static String getMediaTypeSelectionSql(final String contentTypeColumn) {
        return String.format(
                Locale.US,
                "((%s LIKE '%s') OR (%s LIKE '%s') OR (%s LIKE '%s') OR (%s='%s'))",
                contentTypeColumn,
                "image/%",
                contentTypeColumn,
                "video/%",
                contentTypeColumn,
                "audio/%",
                contentTypeColumn,
                ContentType.AUDIO_OGG);
    }

    // Max number of operands per SQL query for deleting SMS messages
    public static final int MAX_IDS_PER_QUERY = 128;

    /**
     * Delete MMS messages with media parts.
     *
     * Because the telephony provider constraints, we can't use JOIN and delete messages in one
     * shot. We have to do a query first and then batch delete the messages based on IDs.
     *
     * @return The count of messages deleted.
     */
    public static int deleteMediaMessages() {
        // Do a query first
        //
        // The WHERE clause has two parts:
        // The first part is to select the exact same types of MMS messages as when we import them
        // (so that we don't delete messages that are not in local database)
        // The second part is to select MMS with media parts, including image, video and audio
        final String selection = String.format(
                Locale.US,
                "%s AND (%s IN (SELECT %s FROM part WHERE %s))",
                getMmsTypeSelectionSql(),
                Mms._ID,
                Mms.Part.MSG_ID,
                getMediaTypeSelectionSql(Mms.Part.CONTENT_TYPE));
        final ContentResolver resolver = Factory.get().getApplicationContext().getContentResolver();
        final Cursor cursor = resolver.query(Mms.CONTENT_URI,
                new String[]{ Mms._ID },
                selection,
                null/*selectionArgs*/,
                null/*sortOrder*/);
        int deleted = 0;
        if (cursor != null) {
            final long[] messageIds = new long[cursor.getCount()];
            try {
                int i = 0;
                while (cursor.moveToNext()) {
                    messageIds[i++] = cursor.getLong(0);
                }
            } finally {
                cursor.close();
            }
            final int totalIds = messageIds.length;
            if (totalIds > 0) {
                // Batch delete the messages using IDs
                // We don't want to send all IDs at once since there is a limit on SQL statement
                for (int start = 0; start < totalIds; start += MAX_IDS_PER_QUERY) {
                    final int end = Math.min(start + MAX_IDS_PER_QUERY, totalIds); // excluding
                    final int count = end - start;
                    final String batchSelection = String.format(
                            Locale.US,
                            "%s IN %s",
                            Mms._ID,
                            getSqlInOperand(count));
                    final String[] batchSelectionArgs =
                            getSqlInOperandArgs(messageIds, start, count);
                    final int deletedForBatch = resolver.delete(
                            Mms.CONTENT_URI,
                            batchSelection,
                            batchSelectionArgs);
                    if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
                        LogUtil.d(TAG, "deleteMediaMessages: deleting IDs = "
                                + Joiner.on(',').skipNulls().join(batchSelectionArgs)
                                + ", deleted = " + deletedForBatch);
                    }
                    deleted += deletedForBatch;
                }
            }
        }
        return deleted;
    }

    /**
     * Get the (?,?,...) thing for the SQL IN operator by a count
     *
     * @param count
     * @return
     */
    public static String getSqlInOperand(final int count) {
        if (count <= 0) {
            return null;
        }
        final StringBuilder sb = new StringBuilder();
        sb.append("(?");
        for (int i = 0; i < count - 1; i++) {
            sb.append(",?");
        }
        sb.append(")");
        return sb.toString();
    }

    /**
     * Get the args for SQL IN operator from a long ID array
     *
     * @param ids The original long id array
     * @param start Start of the ids to fill the args
     * @param count Number of ids to pack
     * @return The long array with the id args
     */
    private static String[] getSqlInOperandArgs(
            final long[] ids, final int start, final int count) {
        if (count <= 0) {
            return null;
        }
        final String[] args = new String[count];
        for (int i = 0; i < count; i++) {
            args[i] = Long.toString(ids[start + i]);
        }
        return args;
    }

    /**
     * Delete SMS and MMS messages that are earlier than a specific timestamp
     *
     * @param cutOffTimestampInMillis The cut-off timestamp
     * @return Total number of messages deleted.
     */
    public static int deleteMessagesOlderThan(final long cutOffTimestampInMillis) {
        int deleted = 0;
        final ContentResolver resolver = Factory.get().getApplicationContext().getContentResolver();
        // Delete old SMS
        final String smsSelection = String.format(
                Locale.US,
                "%s AND (%s<=%d)",
                getSmsTypeSelectionSql(),
                Sms.DATE,
                cutOffTimestampInMillis);
        deleted += resolver.delete(Sms.CONTENT_URI, smsSelection, null/*selectionArgs*/);
        // Delete old MMS
        final String mmsSelection = String.format(
                Locale.US,
                "%s AND (%s<=%d)",
                getMmsTypeSelectionSql(),
                Mms.DATE,
                cutOffTimestampInMillis / 1000L);
        deleted += resolver.delete(Mms.CONTENT_URI, mmsSelection, null/*selectionArgs*/);
        return deleted;
    }

    /**
     * Update the read status of SMS/MMS messages by thread and timestamp
     *
     * @param threadId The thread of sms/mms to change
     * @param timestampInMillis Change the status before this timestamp
     */
    public static void updateSmsReadStatus(final long threadId, final long timestampInMillis) {
        final ContentResolver resolver = Factory.get().getApplicationContext().getContentResolver();
        final ContentValues values = new ContentValues();
        values.put("read", 1);
        values.put("seen", 1); /* If you read it you saw it */
        final String smsSelection = String.format(
                Locale.US,
                "%s=%d AND %s<=%d AND %s=0",
                Sms.THREAD_ID,
                threadId,
                Sms.DATE,
                timestampInMillis,
                Sms.READ);
        resolver.update(
                Sms.CONTENT_URI,
                values,
                smsSelection,
                null/*selectionArgs*/);
        final String mmsSelection = String.format(
                Locale.US,
                "%s=%d AND %s<=%d AND %s=0",
                Mms.THREAD_ID,
                threadId,
                Mms.DATE,
                timestampInMillis / 1000L,
                Mms.READ);
        resolver.update(
                Mms.CONTENT_URI,
                values,
                mmsSelection,
                null/*selectionArgs*/);
    }

    /**
     * Update the read status of a single MMS message by its URI
     *
     * @param mmsUri
     * @param read
     */
    public static void updateReadStatusForMmsMessage(final Uri mmsUri, final boolean read) {
        final ContentResolver resolver = Factory.get().getApplicationContext().getContentResolver();
        final ContentValues values = new ContentValues();
        values.put(Mms.READ, read ? 1 : 0);
        resolver.update(mmsUri, values, null/*where*/, null/*selectionArgs*/);
    }

    public static class AttachmentInfo {
        public String mUrl;
        public String mContentType;
        public int mWidth;
        public int mHeight;
    }

    /**
     * Convert byte array to Java String using a charset name
     *
     * @param bytes
     * @param charsetName
     * @return
     */
    public static String bytesToString(final byte[] bytes, final String charsetName) {
        if (bytes == null) {
            return null;
        }
        try {
            return new String(bytes, charsetName);
        } catch (final UnsupportedEncodingException e) {
            LogUtil.e(TAG, "MmsUtils.bytesToString: " + e, e);
            return new String(bytes);
        }
    }

    /**
     * Convert a Java String to byte array using a charset name
     *
     * @param string
     * @param charsetName
     * @return
     */
    public static byte[] stringToBytes(final String string, final String charsetName) {
        if (string == null) {
            return null;
        }
        try {
            return string.getBytes(charsetName);
        } catch (final UnsupportedEncodingException e) {
            LogUtil.e(TAG, "MmsUtils.stringToBytes: " + e, e);
            return string.getBytes();
        }
    }

    private static final String[] TEST_DATE_SENT_PROJECTION = new String[] { Sms.DATE_SENT };
    private static Boolean sHasSmsDateSentColumn = null;
    /**
     * Check if date_sent column exists on ICS and above devices. We need to do a test
     * query to figure that out since on some ICS+ devices, somehow the date_sent column does
     * not exist. http://b/17629135 tracks the associated compliance test.
     *
     * @return Whether "date_sent" column exists in sms table
     */
    public static boolean hasSmsDateSentColumn() {
        if (sHasSmsDateSentColumn == null) {
            Cursor cursor = null;
            try {
                final Context context = Factory.get().getApplicationContext();
                final ContentResolver resolver = context.getContentResolver();
                cursor = SqliteWrapper.query(
                        context,
                        resolver,
                        Sms.CONTENT_URI,
                        TEST_DATE_SENT_PROJECTION,
                        null/*selection*/,
                        null/*selectionArgs*/,
                        Sms.DATE_SENT + " ASC LIMIT 1");
                sHasSmsDateSentColumn = true;
            } catch (final SQLiteException e) {
                LogUtil.w(TAG, "date_sent in sms table does not exist", e);
                sHasSmsDateSentColumn = false;
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        return sHasSmsDateSentColumn;
    }

    private static final String[] TEST_CARRIERS_PROJECTION =
            new String[] { Telephony.Carriers.MMSC };
    private static Boolean sUseSystemApn = null;
    /**
     * Check if we can access the APN data in the Telephony provider. Access was restricted in
     * JB MR1 (and some JB MR2) devices. If we can't access the APN, we have to fall back and use
     * a private table in our own app.
     *
     * @return Whether we can access the system APN table
     */
    public static boolean useSystemApnTable() {
        if (sUseSystemApn == null) {
            Cursor cursor = null;
            try {
                final Context context = Factory.get().getApplicationContext();
                final ContentResolver resolver = context.getContentResolver();
                cursor = SqliteWrapper.query(
                        context,
                        resolver,
                        Telephony.Carriers.CONTENT_URI,
                        TEST_CARRIERS_PROJECTION,
                        null/*selection*/,
                        null/*selectionArgs*/,
                        null);
                sUseSystemApn = true;
            } catch (final SecurityException e) {
                LogUtil.w(TAG, "Can't access system APN, using internal table", e);
                sUseSystemApn = false;
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        return sUseSystemApn;
    }

    // For the internal debugger only
    public static void setUseSystemApnTable(final boolean turnOn) {
        if (!turnOn) {
            // We're not turning on to the system table. Instead, we're using our internal table.

            // We're turning on local APNs on a device where we wouldn't normally have the
            // local APN table. Build it here.

            final SQLiteDatabase database = ApnDatabase.getApnDatabase().getWritableDatabase();

            // Do we already have the table?
            Cursor cursor = null;
            try {
                cursor = database.query(ApnDatabase.APN_TABLE,
                        ApnDatabase.APN_PROJECTION,
                        null, null, null, null, null, null);
            } catch (final Exception e) {
                // Apparently there's no table, create it now.
                ApnDatabase.forceBuildAndLoadApnTables();
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        sUseSystemApn = turnOn;
    }

    /**
     * Checks if we should dump sms, based on both the setting and the global debug
     * flag
     *
     * @return if dump sms is enabled
     */
    public static boolean isDumpSmsEnabled() {
        if (!DebugUtils.isDebugEnabled()) {
            return false;
        }
        return getDumpSmsOrMmsPref(R.string.dump_sms_pref_key, R.bool.dump_sms_pref_default);
    }

    /**
     * Checks if we should dump mms, based on both the setting and the global debug
     * flag
     *
     * @return if dump mms is enabled
     */
    public static boolean isDumpMmsEnabled() {
        if (!DebugUtils.isDebugEnabled()) {
            return false;
        }
        return getDumpSmsOrMmsPref(R.string.dump_mms_pref_key, R.bool.dump_mms_pref_default);
    }

    /**
     * Load the value of dump sms or mms setting preference
     */
    private static boolean getDumpSmsOrMmsPref(final int prefKeyRes, final int defaultKeyRes) {
        final Context context = Factory.get().getApplicationContext();
        final Resources resources = context.getResources();
        final BuglePrefs prefs = BuglePrefs.getApplicationPrefs();
        final String key = resources.getString(prefKeyRes);
        final boolean defaultValue = resources.getBoolean(defaultKeyRes);
        return prefs.getBoolean(key, defaultValue);
    }

    public static final Uri MMS_PART_CONTENT_URI = Uri.parse("content://mms/part");

    /**
     * Load MMS from telephony
     *
     * @param mmsUri The MMS pdu Uri
     * @return A memory copy of the MMS pdu including parts (but not addresses)
     */
    public static DatabaseMessages.MmsMessage loadMms(final Uri mmsUri) {
        final Context context = Factory.get().getApplicationContext();
        final ContentResolver resolver = context.getContentResolver();
        DatabaseMessages.MmsMessage mms = null;
        Cursor cursor = null;
        // Load pdu first
        try {
            cursor = SqliteWrapper.query(context, resolver,
                    mmsUri,
                    DatabaseMessages.MmsMessage.getProjection(),
                    null/*selection*/, null/*selectionArgs*/, null/*sortOrder*/);
            if (cursor != null && cursor.moveToFirst()) {
                mms = DatabaseMessages.MmsMessage.get(cursor);
            }
        } catch (final SQLiteException e) {
            LogUtil.e(TAG, "MmsLoader: query pdu failure: " + e, e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        if (mms == null) {
            return null;
        }
        // Load parts except SMIL
        // TODO: we may need to load SMIL part in the future.
        final long rowId = MmsUtils.parseRowIdFromMessageUri(mmsUri);
        final String selection = String.format(
                Locale.US,
                "%s != '%s' AND %s = ?",
                Mms.Part.CONTENT_TYPE,
                ContentType.APP_SMIL,
                Mms.Part.MSG_ID);
        cursor = null;
        try {
            cursor = SqliteWrapper.query(context, resolver,
                    MMS_PART_CONTENT_URI,
                    DatabaseMessages.MmsPart.PROJECTION,
                    selection,
                    new String[] { Long.toString(rowId) },
                    null/*sortOrder*/);
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    mms.addPart(DatabaseMessages.MmsPart.get(cursor, true/*loadMedia*/));
                }
            }
        } catch (final SQLiteException e) {
            LogUtil.e(TAG, "MmsLoader: query parts failure: " + e, e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return mms;
    }

    /**
     * Get the sender of an MMS message
     *
     * @param recipients The recipient list of the message
     * @param mmsUri The pdu uri of the MMS
     * @return The sender phone number of the MMS
     */
    public static String getMmsSender(final List<String> recipients, final String mmsUri) {
        final Context context = Factory.get().getApplicationContext();
        // We try to avoid the database query.
        // If this is a 1v1 conv., then the other party is the sender
        if (recipients != null && recipients.size() == 1) {
            return recipients.get(0);
        }
        // Otherwise, we have to query the MMS addr table for sender address
        // This should only be done for a received group mms message
        final Cursor cursor = SqliteWrapper.query(
                context,
                context.getContentResolver(),
                Uri.withAppendedPath(Uri.parse(mmsUri), "addr"),
                new String[] { Mms.Addr.ADDRESS, Mms.Addr.CHARSET },
                Mms.Addr.TYPE + "=" + PduHeaders.FROM,
                null/*selectionArgs*/,
                null/*sortOrder*/);
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    return DatabaseMessages.MmsAddr.get(cursor);
                }
            } finally {
                cursor.close();
            }
        }
        return null;
    }

    public static int bugleStatusForMms(final boolean isOutgoing, final boolean isNotification,
            final int messageBox) {
        int bugleStatus = MessageData.BUGLE_STATUS_UNKNOWN;
        // For a message we sync either
        if (isOutgoing) {
            if (messageBox == Mms.MESSAGE_BOX_OUTBOX || messageBox == Mms.MESSAGE_BOX_FAILED) {
                // Not sent counts as failed and available for manual resend
                bugleStatus = MessageData.BUGLE_STATUS_OUTGOING_FAILED;
            } else {
                // Otherwise outgoing message is complete
                bugleStatus = MessageData.BUGLE_STATUS_OUTGOING_COMPLETE;
            }
        } else if (isNotification) {
            // Incoming MMS notifications we sync count as failed and available for manual download
            bugleStatus = MessageData.BUGLE_STATUS_INCOMING_YET_TO_MANUAL_DOWNLOAD;
        } else {
            // Other incoming MMS messages are complete
            bugleStatus = MessageData.BUGLE_STATUS_INCOMING_COMPLETE;
        }
        return bugleStatus;
    }

    public static MessageData createMmsMessage(final DatabaseMessages.MmsMessage mms,
            final String conversationId, final String participantId, final String selfId,
            final int bugleStatus) {
        Assert.notNull(mms);
        final boolean isNotification = (mms.mMmsMessageType ==
                PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND);
        final int rawMmsStatus = (bugleStatus < MessageData.BUGLE_STATUS_FIRST_INCOMING
                ? mms.mRetrieveStatus : mms.mResponseStatus);

        final MessageData message = MessageData.createMmsMessage(mms.getUri(),
                participantId, selfId, conversationId, isNotification, bugleStatus,
                mms.mContentLocation, mms.mTransactionId, mms.mPriority, mms.mSubject,
                mms.mSeen, mms.mRead, mms.getSize(), rawMmsStatus,
                mms.mExpiryInMillis, mms.mSentTimestampInMillis, mms.mTimestampInMillis);

        for (final DatabaseMessages.MmsPart part : mms.mParts) {
            final MessagePartData messagePart = MmsUtils.createMmsMessagePart(part);
            // Import media and text parts (skip SMIL and others)
            if (messagePart != null) {
                message.addPart(messagePart);
            }
        }

        if (!message.getParts().iterator().hasNext()) {
            message.addPart(MessagePartData.createEmptyMessagePart());
        }

        return message;
    }

    public static MessagePartData createMmsMessagePart(final DatabaseMessages.MmsPart part) {
        MessagePartData messagePart = null;
        if (part.isText()) {
            final int mmsTextLengthLimit =
                    BugleGservices.get().getInt(BugleGservicesKeys.MMS_TEXT_LIMIT,
                            BugleGservicesKeys.MMS_TEXT_LIMIT_DEFAULT);
            String text = part.mText;
            if (text != null && text.length() > mmsTextLengthLimit) {
                // Limit the text to a reasonable value. We ran into a situation where a vcard
                // with a photo was sent as plain text. The massive amount of text caused the
                // app to hang, ANR, and eventually crash in native text code.
                text = text.substring(0, mmsTextLengthLimit);
            }
            messagePart = MessagePartData.createTextMessagePart(text);
        } else if (part.isMedia()) {
            messagePart = MessagePartData.createMediaMessagePart(part.mContentType,
                    part.getDataUri(), MessagePartData.UNSPECIFIED_SIZE,
                    MessagePartData.UNSPECIFIED_SIZE);
        }
        return messagePart;
    }

    public static class StatusPlusUri {
        // The request status to be as the result of the operation
        // e.g. MMS_REQUEST_MANUAL_RETRY
        public final int status;
        // The raw telephony status
        public final int rawStatus;
        // The raw telephony URI
        public final Uri uri;
        // The operation result code from system api invocation (sent by system)
        // or mapped from internal exception (sent by app)
        public final int resultCode;

        public StatusPlusUri(final int status, final int rawStatus, final Uri uri) {
            this.status = status;
            this.rawStatus = rawStatus;
            this.uri = uri;
            resultCode = MessageData.UNKNOWN_RESULT_CODE;
        }

        public StatusPlusUri(final int status, final int rawStatus, final Uri uri,
                final int resultCode) {
            this.status = status;
            this.rawStatus = rawStatus;
            this.uri = uri;
            this.resultCode = resultCode;
        }
    }

    public static class SendReqResp {
        public SendReq mSendReq;
        public SendConf mSendConf;

        public SendReqResp(final SendReq sendReq, final SendConf sendConf) {
            mSendReq = sendReq;
            mSendConf = sendConf;
        }
    }

    /**
     * Returned when sending/downloading MMS via platform APIs. In that case, we have to wait to
     * receive the pending intent to determine status.
     */
    public static final StatusPlusUri STATUS_PENDING = new StatusPlusUri(-1, -1, null);

    public static StatusPlusUri downloadMmsMessage(final Context context, final Uri notificationUri,
            final int subId, final String subPhoneNumber, final String transactionId,
            final String contentLocation, final boolean autoDownload,
            final long receivedTimestampInSeconds, final long expiry, Bundle extras) {
        if (TextUtils.isEmpty(contentLocation)) {
            LogUtil.e(TAG, "MmsUtils: Download from empty content location URL");
            return new StatusPlusUri(
                    MMS_REQUEST_NO_RETRY, MessageData.RAW_TELEPHONY_STATUS_UNDEFINED, null);
        }
        int status = MMS_REQUEST_MANUAL_RETRY;
        try {
            RetrieveConf retrieveConf = null;
            if (DebugUtils.isDebugEnabled() &&
                    MediaScratchFileProvider
                            .isMediaScratchSpaceUri(Uri.parse(contentLocation))) {
                if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
                    LogUtil.d(TAG, "MmsUtils: Reading MMS from dump file: " + contentLocation);
                }
                final String fileName = Uri.parse(contentLocation).getPathSegments().get(1);
                final byte[] data = DebugUtils.receiveFromDumpFile(fileName);
                retrieveConf = receiveFromDumpFile(data);
            } else {
                if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
                    LogUtil.d(TAG, "MmsUtils: Downloading MMS via MMS lib API; notification "
                            + "message: " + notificationUri);
                }
                if (subId < 0) {
                    LogUtil.e(TAG, "MmsUtils: Incoming MMS came from unknown SIM");
                    throw new MmsFailureException(MMS_REQUEST_NO_RETRY,
                            "Message from unknown SIM");
                }

                if (extras == null) {
                    extras = new Bundle();
                }
                extras.putParcelable(DownloadMmsAction.EXTRA_NOTIFICATION_URI, notificationUri);
                extras.putInt(DownloadMmsAction.EXTRA_SUB_ID, subId);
                extras.putString(DownloadMmsAction.EXTRA_SUB_PHONE_NUMBER, subPhoneNumber);
                extras.putString(DownloadMmsAction.EXTRA_TRANSACTION_ID, transactionId);
                extras.putString(DownloadMmsAction.EXTRA_CONTENT_LOCATION, contentLocation);
                extras.putBoolean(DownloadMmsAction.EXTRA_AUTO_DOWNLOAD, autoDownload);
                extras.putLong(DownloadMmsAction.EXTRA_RECEIVED_TIMESTAMP,
                        receivedTimestampInSeconds);
                extras.putLong(DownloadMmsAction.EXTRA_EXPIRY, expiry);

                MmsSender.downloadMms(context, subId, contentLocation, extras);
                return STATUS_PENDING; // Download happens asynchronously; no status to return
            }
            return insertDownloadedMessageAndSendResponse(context, notificationUri, subId,
                    subPhoneNumber, transactionId, contentLocation, autoDownload,
                    receivedTimestampInSeconds, expiry, retrieveConf);

        } catch (final MmsFailureException e) {
            LogUtil.e(TAG, "MmsUtils: failed to download message " + notificationUri, e);
            status = e.retryHint;
        } catch (final InvalidHeaderValueException e) {
            LogUtil.e(TAG, "MmsUtils: failed to download message " + notificationUri, e);
        }
        return new StatusPlusUri(status, PDU_HEADER_VALUE_UNDEFINED, null);
    }

    public static StatusPlusUri insertDownloadedMessageAndSendResponse(final Context context,
            final Uri notificationUri, final int subId, final String subPhoneNumber,
            final String transactionId, final String contentLocation,
            final boolean autoDownload, final long receivedTimestampInSeconds,
            final long expiry, final RetrieveConf retrieveConf) {
        final byte[] notificationTransactionId = stringToBytes(transactionId, "UTF-8");
        Uri messageUri = null;
        final int status;
        final int retrieveStatus = retrieveConf.getRetrieveStatus();

        if (retrieveStatus == PduHeaders.RETRIEVE_STATUS_OK) {
            status = MMS_REQUEST_SUCCEEDED;
        } else if (retrieveStatus >= PduHeaders.RETRIEVE_STATUS_ERROR_TRANSIENT_FAILURE &&
                retrieveStatus < PduHeaders.RETRIEVE_STATUS_ERROR_PERMANENT_FAILURE) {
            status = MMS_REQUEST_AUTO_RETRY;
        } else {
            // else not meant to retry download
            status = MMS_REQUEST_NO_RETRY;
            LogUtil.e(TAG, "MmsUtils: failed to retrieve message; retrieveStatus: "
                    + retrieveStatus);
        }
        final ContentValues values = new ContentValues(1);
        values.put(Mms.RETRIEVE_STATUS, retrieveConf.getRetrieveStatus());
        SqliteWrapper.update(context, context.getContentResolver(),
                notificationUri, values, null, null);

        if (status == MMS_REQUEST_SUCCEEDED) {
            // Send response of the notification
            if (autoDownload) {
                sendNotifyResponseForMmsDownload(
                        context,
                        subId,
                        notificationTransactionId,
                        contentLocation,
                        PduHeaders.STATUS_RETRIEVED);
            } else {
                sendAcknowledgeForMmsDownload(
                        context, subId, retrieveConf.getTransactionId(), contentLocation);
            }

            // Insert downloaded message into telephony
            final Uri inboxUri = MmsUtils.insertReceivedMmsMessage(context, retrieveConf, subId,
                    subPhoneNumber, receivedTimestampInSeconds, expiry, transactionId);
            messageUri = ContentUris.withAppendedId(Mms.CONTENT_URI, ContentUris.parseId(inboxUri));
        }
        // Do nothing for MMS_REQUEST_AUTO_RETRY and MMS_REQUEST_NO_RETRY.

        return new StatusPlusUri(status, retrieveStatus, messageUri);
    }

    /**
     * Send response for MMS download - catches and ignores errors
     */
    public static void sendNotifyResponseForMmsDownload(final Context context, final int subId,
            final byte[] transactionId, final String contentLocation, final int status) {
        try {
            if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
                LogUtil.d(TAG, "MmsUtils: Sending M-NotifyResp.ind for received MMS, status: "
                        + String.format("0x%X", status));
            }
            if (contentLocation == null) {
                LogUtil.w(TAG, "MmsUtils: Can't send NotifyResp; contentLocation is null");
                return;
            }
            if (transactionId == null) {
                LogUtil.w(TAG, "MmsUtils: Can't send NotifyResp; transaction id is null");
                return;
            }
            MmsSender.sendNotifyResponseForMmsDownload(
                    context, subId, transactionId, contentLocation, status);
        } catch (final MmsFailureException e) {
            LogUtil.e(TAG, "sendNotifyResponseForMmsDownload: failed to retrieve message " + e, e);
        } catch (final InvalidHeaderValueException e) {
            LogUtil.e(TAG, "sendNotifyResponseForMmsDownload: failed to retrieve message " + e, e);
        }
    }

    /**
     * Send acknowledge for mms download - catched and ignores errors
     */
    public static void sendAcknowledgeForMmsDownload(final Context context, final int subId,
            final byte[] transactionId, final String contentLocation) {
        try {
            if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
                LogUtil.d(TAG, "MmsUtils: Sending M-Acknowledge.ind for received MMS");
            }
            if (contentLocation == null) {
                LogUtil.w(TAG, "MmsUtils: Can't send AckInd; contentLocation is null");
                return;
            }
            if (transactionId == null) {
                LogUtil.w(TAG, "MmsUtils: Can't send AckInd; transaction id is null");
                return;
            }
            MmsSender.sendAcknowledgeForMmsDownload(context, subId, transactionId, contentLocation);
        } catch (final MmsFailureException e) {
            LogUtil.e(TAG, "sendAcknowledgeForMmsDownload: failed to retrieve message " + e, e);
        } catch (final InvalidHeaderValueException e) {
            LogUtil.e(TAG, "sendAcknowledgeForMmsDownload: failed to retrieve message " + e, e);
        }
    }

    /**
     * Try parsing a PDU without knowing the carrier. This is useful for importing
     * MMS or storing draft when carrier info is not available
     *
     * @param data The PDU data
     * @return Parsed PDU, null if failed to parse
     */
    private static GenericPdu parsePduForAnyCarrier(final byte[] data) {
        GenericPdu pdu = null;
        try {
            pdu = (new PduParser(data, true/*parseContentDisposition*/)).parse();
        } catch (final RuntimeException e) {
            LogUtil.d(TAG, "parsePduForAnyCarrier: Failed to parse PDU with content disposition",
                    e);
        }
        if (pdu == null) {
            try {
                pdu = (new PduParser(data, false/*parseContentDisposition*/)).parse();
            } catch (final RuntimeException e) {
                LogUtil.d(TAG,
                        "parsePduForAnyCarrier: Failed to parse PDU without content disposition",
                        e);
            }
        }
        return pdu;
    }

    private static RetrieveConf receiveFromDumpFile(final byte[] data) throws MmsFailureException {
        final GenericPdu pdu = parsePduForAnyCarrier(data);
        if (pdu == null || !(pdu instanceof RetrieveConf)) {
            LogUtil.e(TAG, "receiveFromDumpFile: Parsing retrieved PDU failure");
            throw new MmsFailureException(MMS_REQUEST_MANUAL_RETRY, "Failed reading dump file");
        }
        return (RetrieveConf) pdu;
    }

    public static StatusPlusUri sendMmsMessage(final Context context, final int subId,
            final Uri messageUri, final Bundle extras) {
        int status = MMS_REQUEST_MANUAL_RETRY;
        int rawStatus = MessageData.RAW_TELEPHONY_STATUS_UNDEFINED;
        final PduPersister persister = PduPersister.getPduPersister(context);
        try {
            final SendReq sendReq = (SendReq) persister.load(messageUri);
            if (sendReq == null) {
                LogUtil.w(TAG, "MmsUtils: Sending MMS was deleted; uri = " + messageUri);
                return new StatusPlusUri(MMS_REQUEST_NO_RETRY,
                        MessageData.RAW_TELEPHONY_STATUS_UNDEFINED, messageUri);
            }
            if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
                LogUtil.d(TAG, String.format("MmsUtils: Sending MMS, message uri: %s", messageUri));
            }
            extras.putInt(SendMessageAction.KEY_SUB_ID, subId);
            MmsSender.sendMms(context, subId, messageUri, sendReq, extras);
            return STATUS_PENDING;
        } catch (final MmsFailureException e) {
            status = e.retryHint;
            rawStatus = e.rawStatus;
            LogUtil.e(TAG, "MmsUtils: failed to send message " + e, e);
        } catch (final InvalidHeaderValueException e) {
            LogUtil.e(TAG, "MmsUtils: failed to send message " + e, e);
        } catch (final IllegalArgumentException e) {
            LogUtil.e(TAG, "MmsUtils: invalid message to send " + e, e);
        } catch (final MmsException e) {
            LogUtil.e(TAG, "MmsUtils: failed to send message " + e, e);
        }
        // If we get here, some exception occurred
        return new StatusPlusUri(status, rawStatus, messageUri);
    }

    public static StatusPlusUri updateSentMmsMessageStatus(final Context context,
            final Uri messageUri, final SendConf sendConf) {
        final int status;
        final int respStatus = sendConf.getResponseStatus();

        final ContentValues values = new ContentValues(2);
        values.put(Mms.RESPONSE_STATUS, respStatus);
        final byte[] messageId = sendConf.getMessageId();
        if (messageId != null && messageId.length > 0) {
            values.put(Mms.MESSAGE_ID, PduPersister.toIsoString(messageId));
        }
        SqliteWrapper.update(context, context.getContentResolver(),
                messageUri, values, null, null);
        if (respStatus == PduHeaders.RESPONSE_STATUS_OK) {
            status = MMS_REQUEST_SUCCEEDED;
        } else if (respStatus >= PduHeaders.RESPONSE_STATUS_ERROR_TRANSIENT_FAILURE
                && respStatus < PduHeaders.RESPONSE_STATUS_ERROR_PERMANENT_FAILURE) {
            // Only RESPONSE_STATUS_ERROR_TRANSIENT_FAILURE and RESPONSE_STATUS_ERROR_TRANSIENT
            // _NETWORK_PROBLEM are used in the M-Send.conf. But for others transient failures
            // including reserved values for future purposes, it should work same as transient
            // failure always. (OMA-MMS-ENC-V1_2, 7.2.37. X-Mms-Response-Status field)
            status = MMS_REQUEST_AUTO_RETRY;
        } else {
            // else permanent failure
            status = MMS_REQUEST_MANUAL_RETRY;
            LogUtil.e(TAG, "MmsUtils: failed to send message; respStatus = "
                    + String.format("0x%X", respStatus));
        }
        return new StatusPlusUri(status, respStatus, messageUri);
    }

    public static void clearMmsStatus(final Context context, final Uri uri) {
        // Messaging application can leave invalid values in STATUS field of M-Notification.ind
        // messages.  Take this opportunity to clear it.
        // Downloading status just kept in local db and not reflected into telephony.
        final ContentValues values = new ContentValues(1);
        values.putNull(Mms.STATUS);
        SqliteWrapper.update(context, context.getContentResolver(),
                    uri, values, null, null);
    }

    // Selection for dedup algorithm:
    // ((m_type=NOTIFICATION_IND) OR (m_type=RETRIEVE_CONF)) AND (exp>NOW)) AND (t_id=xxxxxx)
    // i.e. If it is NotificationInd or RetrieveConf and not expired
    //      AND transaction id is the input id
    private static final String DUP_NOTIFICATION_QUERY_SELECTION =
            "((" + Mms.MESSAGE_TYPE + "=?) OR (" + Mms.MESSAGE_TYPE + "=?)) AND ("
                    + Mms.EXPIRY + ">?) AND (" + Mms.TRANSACTION_ID + "=?)";

    private static final int MAX_RETURN = 32;
    private static String[] getDupNotifications(final Context context, final NotificationInd nInd) {
        final byte[] rawTransactionId = nInd.getTransactionId();
        if (rawTransactionId != null) {
            // dedup algorithm
            String selection = DUP_NOTIFICATION_QUERY_SELECTION;
            final long nowSecs = System.currentTimeMillis() / 1000;
            String[] selectionArgs = new String[] {
                    Integer.toString(PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND),
                    Integer.toString(PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF),
                    Long.toString(nowSecs),
                    new String(rawTransactionId)
            };

            Cursor cursor = null;
            try {
                cursor = SqliteWrapper.query(
                        context, context.getContentResolver(),
                        Mms.CONTENT_URI, new String[] { Mms._ID },
                        selection, selectionArgs, null);
                final int dupCount = cursor.getCount();
                if (dupCount > 0) {
                    // We already received the same notification before.
                    // Don't want to return too many dups. It is only for debugging.
                    final int returnCount = dupCount < MAX_RETURN ? dupCount : MAX_RETURN;
                    final String[] dups = new String[returnCount];
                    for (int i = 0; cursor.moveToNext() && i < returnCount; i++) {
                        dups[i] = cursor.getString(0);
                    }
                    return dups;
                }
            } catch (final SQLiteException e) {
                LogUtil.e(TAG, "query failure: " + e, e);
            } finally {
                cursor.close();
            }
        }
        return null;
    }

    /**
     * Try parse the address using RFC822 format. If it fails to parse, then return the
     * original address
     *
     * @param address The MMS ind sender address to parse
     * @return The real address. If in RFC822 format, returns the correct email.
     */
    private static String parsePotentialRfc822EmailAddress(final String address) {
        if (address == null || !address.contains("@") || !address.contains("<")) {
            return address;
        }
        final Rfc822Token[] tokens = Rfc822Tokenizer.tokenize(address);
        if (tokens != null && tokens.length > 0) {
            for (final Rfc822Token token : tokens) {
                if (token != null && !TextUtils.isEmpty(token.getAddress())) {
                    return token.getAddress();
                }
            }
        }
        return address;
    }

    public static DatabaseMessages.MmsMessage processReceivedPdu(final Context context,
            final byte[] pushData, final int subId, final String subPhoneNumber) {
        // Parse data

        // Insert placeholder row to telephony and local db
        // Get raw PDU push-data from the message and parse it
        final PduParser parser = new PduParser(pushData,
                MmsConfig.get(subId).getSupportMmsContentDisposition());
        final GenericPdu pdu = parser.parse();

        if (null == pdu) {
            LogUtil.e(TAG, "Invalid PUSH data");
            return null;
        }

        final PduPersister p = PduPersister.getPduPersister(context);
        final int type = pdu.getMessageType();

        Uri messageUri = null;
        switch (type) {
            case PduHeaders.MESSAGE_TYPE_DELIVERY_IND:
            case PduHeaders.MESSAGE_TYPE_READ_ORIG_IND: {
                // TODO: Should this be commented out?
//                threadId = findThreadId(context, pdu, type);
//                if (threadId == -1) {
//                    // The associated SendReq isn't found, therefore skip
//                    // processing this PDU.
//                    break;
//                }

//                Uri uri = p.persist(pdu, Inbox.CONTENT_URI, true,
//                        MessagingPreferenceActivity.getIsGroupMmsEnabled(mContext), null);
//                // Update thread ID for ReadOrigInd & DeliveryInd.
//                ContentValues values = new ContentValues(1);
//                values.put(Mms.THREAD_ID, threadId);
//                SqliteWrapper.update(mContext, cr, uri, values, null, null);
                LogUtil.w(TAG, "Received unsupported WAP Push, type=" + type);
                break;
            }
            case PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND: {
                final NotificationInd nInd = (NotificationInd) pdu;

                if (MmsConfig.get(subId).getTransIdEnabled()) {
                    final byte [] contentLocationTemp = nInd.getContentLocation();
                    if ('=' == contentLocationTemp[contentLocationTemp.length - 1]) {
                        final byte [] transactionIdTemp = nInd.getTransactionId();
                        final byte [] contentLocationWithId =
                                new byte [contentLocationTemp.length
                                                                  + transactionIdTemp.length];
                        System.arraycopy(contentLocationTemp, 0, contentLocationWithId,
                                0, contentLocationTemp.length);
                        System.arraycopy(transactionIdTemp, 0, contentLocationWithId,
                                contentLocationTemp.length, transactionIdTemp.length);
                        nInd.setContentLocation(contentLocationWithId);
                    }
                }
                final String[] dups = getDupNotifications(context, nInd);
                if (dups == null) {
                    // TODO: Do we handle Rfc822 Email Addresses?
                    //final String contentLocation =
                    //        MmsUtils.bytesToString(nInd.getContentLocation(), "UTF-8");
                    //final byte[] transactionId = nInd.getTransactionId();
                    //final long messageSize = nInd.getMessageSize();
                    //final long expiry = nInd.getExpiry();
                    //final String transactionIdString =
                    //        MmsUtils.bytesToString(transactionId, "UTF-8");

                    //final EncodedStringValue fromEncoded = nInd.getFrom();
                    // An mms ind received from email address will have from address shown as
                    // "John Doe <johndoe@foobar.com>" but the actual received message will only
                    // have the email address. So let's try to parse the RFC822 format to get the
                    // real email. Otherwise we will create two conversations for the MMS
                    // notification and the actual MMS message if auto retrieve is disabled.
                    //final String from = parsePotentialRfc822EmailAddress(
                    //        fromEncoded != null ? fromEncoded.getString() : null);

                    Uri inboxUri = null;
                    try {
                        inboxUri = p.persist(pdu, Mms.Inbox.CONTENT_URI, subId, subPhoneNumber,
                                null);
                        messageUri = ContentUris.withAppendedId(Mms.CONTENT_URI,
                                ContentUris.parseId(inboxUri));
                    } catch (final MmsException e) {
                        LogUtil.e(TAG, "Failed to save the data from PUSH: type=" + type, e);
                    }
                } else {
                    LogUtil.w(TAG, "Received WAP Push is a dup: " + Joiner.on(',').join(dups));
                    if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
                        LogUtil.w(TAG, "Dup Transaction Id=" + new String(nInd.getTransactionId()));
                    }
                }
                break;
            }
            default:
                LogUtil.e(TAG, "Received unrecognized WAP Push, type=" + type);
        }

        DatabaseMessages.MmsMessage mms = null;
        if (messageUri != null) {
            mms = MmsUtils.loadMms(messageUri);
        }
        return mms;
    }

    public static Uri insertSendingMmsMessage(final Context context, final List<String> recipients,
            final MessageData content, final int subId, final String subPhoneNumber,
            final long timestamp) {
        final SendReq sendReq = createMmsSendReq(
                context, subId, recipients.toArray(new String[recipients.size()]), content,
                DEFAULT_DELIVERY_REPORT_MODE,
                DEFAULT_READ_REPORT_MODE,
                DEFAULT_EXPIRY_TIME_IN_SECONDS,
                DEFAULT_PRIORITY,
                timestamp);
        Uri messageUri = null;
        if (sendReq != null) {
            final Uri outboxUri = MmsUtils.insertSendReq(context, sendReq, subId, subPhoneNumber);
            if (outboxUri != null) {
                messageUri = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI,
                        ContentUris.parseId(outboxUri));
                if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
                    LogUtil.d(TAG, "Mmsutils: Inserted sending MMS message into telephony, uri: "
                            + outboxUri);
                }
            } else {
                LogUtil.e(TAG, "insertSendingMmsMessage: failed to persist message into telephony");
            }
        }
        return messageUri;
    }

    public static MessageData readSendingMmsMessage(final Uri messageUri,
            final String conversationId, final String participantId, final String selfId) {
        MessageData message = null;
        if (messageUri != null) {
            final DatabaseMessages.MmsMessage mms = MmsUtils.loadMms(messageUri);

            // Make sure that the message has not been deleted from the Telephony DB
            if (mms != null) {
                // Transform the message
                message = MmsUtils.createMmsMessage(mms, conversationId, participantId, selfId,
                        MessageData.BUGLE_STATUS_OUTGOING_RESENDING);
            }
        }
        return message;
    }

    /**
     * Create an MMS message with subject, text and image
     *
     * @return Both the M-Send.req and the M-Send.conf for processing in the caller
     * @throws MmsException
     */
    private static SendReq createMmsSendReq(final Context context, final int subId,
            final String[] recipients, final MessageData message,
            final boolean requireDeliveryReport, final boolean requireReadReport,
            final long expiryTime, final int priority, final long timestampMillis) {
        Assert.notNull(context);
        if (recipients == null || recipients.length < 1) {
            throw new IllegalArgumentException("MMS sendReq no recipient");
        }

        // Make a copy so we don't propagate changes to recipients to outside of this method
        final String[] recipientsCopy = new String[recipients.length];
        // Don't send phone number as is since some received phone number is malformed
        // for sending. We need to strip the separators.
        for (int i = 0; i < recipients.length; i++) {
            final String recipient = recipients[i];
            if (EmailAddress.isValidEmail(recipients[i])) {
                // Don't do stripping for emails
                recipientsCopy[i] = recipient;
            } else {
                recipientsCopy[i] = stripPhoneNumberSeparators(recipient);
            }
        }

        SendReq sendReq = null;
        try {
            sendReq = createSendReq(context, subId, recipientsCopy,
                    message, requireDeliveryReport,
                    requireReadReport, expiryTime, priority, timestampMillis);
        } catch (final InvalidHeaderValueException e) {
            LogUtil.e(TAG, "InvalidHeaderValue creating sendReq PDU");
        } catch (final OutOfMemoryError e) {
            LogUtil.e(TAG, "Out of memory error creating sendReq PDU");
        }
        return sendReq;
    }

    /**
     * Stripping out the invalid characters in a phone number before sending
     * MMS. We only keep alphanumeric and '*', '#', '+'.
     */
    private static String stripPhoneNumberSeparators(final String phoneNumber) {
        if (phoneNumber == null) {
            return null;
        }
        final int len = phoneNumber.length();
        final StringBuilder ret = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            final char c = phoneNumber.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '+' || c == '*' || c == '#') {
                ret.append(c);
            }
        }
        return ret.toString();
    }

    /**
     * Create M-Send.req for the MMS message to be sent.
     *
     * @return the M-Send.req
     * @throws InvalidHeaderValueException if there is any error in parsing the input
     */
    static SendReq createSendReq(final Context context, final int subId,
            final String[] recipients, final MessageData message,
            final boolean requireDeliveryReport,
            final boolean requireReadReport, final long expiryTime, final int priority,
            final long timestampMillis)
            throws InvalidHeaderValueException {
        final SendReq req = new SendReq();
        // From, per spec
        final String lineNumber = PhoneUtils.get(subId).getCanonicalForSelf(true/*allowOverride*/);
        if (!TextUtils.isEmpty(lineNumber)) {
            req.setFrom(new EncodedStringValue(lineNumber));
        }
        // To
        final EncodedStringValue[] encodedNumbers = EncodedStringValue.encodeStrings(recipients);
        if (encodedNumbers != null) {
            req.setTo(encodedNumbers);
        }
        // Subject
        if (!TextUtils.isEmpty(message.getMmsSubject())) {
            req.setSubject(new EncodedStringValue(message.getMmsSubject()));
        }
        // Date
        req.setDate(timestampMillis / 1000L);
        // Body
        final MmsInfo bodyInfo = MmsUtils.makePduBody(context, message, subId);
        req.setBody(bodyInfo.mPduBody);
        // Message size
        req.setMessageSize(bodyInfo.mMessageSize);
        // Message class
        req.setMessageClass(PduHeaders.MESSAGE_CLASS_PERSONAL_STR.getBytes());
        // Expiry
        req.setExpiry(expiryTime);
        // Priority
        req.setPriority(priority);
        // Delivery report
        req.setDeliveryReport(requireDeliveryReport ? PduHeaders.VALUE_YES : PduHeaders.VALUE_NO);
        // Read report
        req.setReadReport(requireReadReport ? PduHeaders.VALUE_YES : PduHeaders.VALUE_NO);
        return req;
    }

    public static boolean isDeliveryReportRequired(final int subId) {
        if (!MmsConfig.get(subId).getSMSDeliveryReportsEnabled()) {
            return false;
        }
        final Context context = Factory.get().getApplicationContext();
        final Resources res = context.getResources();
        final BuglePrefs prefs = BuglePrefs.getSubscriptionPrefs(subId);
        final String deliveryReportKey = res.getString(R.string.delivery_reports_pref_key);
        final boolean defaultValue = res.getBoolean(R.bool.delivery_reports_pref_default);
        return prefs.getBoolean(deliveryReportKey, defaultValue);
    }

    public static int sendSmsMessage(final String recipient, final String messageText,
            final Uri requestUri, final int subId,
            final String smsServiceCenter, final boolean requireDeliveryReport) {
        final Context context = Factory.get().getApplicationContext();
        int status = MMS_REQUEST_MANUAL_RETRY;
        try {
            // Send a single message
            final SendResult result = SmsSender.sendMessage(
                    context,
                    subId,
                    recipient,
                    messageText,
                    smsServiceCenter,
                    requireDeliveryReport,
                    requestUri);
            if (!result.hasPending()) {
                // not timed out, check failures
                final int failureLevel = result.getHighestFailureLevel();
                switch (failureLevel) {
                    case SendResult.FAILURE_LEVEL_NONE:
                        status = MMS_REQUEST_SUCCEEDED;
                        break;
                    case SendResult.FAILURE_LEVEL_TEMPORARY:
                        status = MMS_REQUEST_AUTO_RETRY;
                        LogUtil.e(TAG, "MmsUtils: SMS temporary failure");
                        break;
                    case SendResult.FAILURE_LEVEL_PERMANENT:
                        LogUtil.e(TAG, "MmsUtils: SMS permanent failure");
                        break;
                }
            } else {
                // Timed out
                LogUtil.e(TAG, "MmsUtils: sending SMS timed out");
            }
        } catch (final Exception e) {
            LogUtil.e(TAG, "MmsUtils: failed to send SMS " + e, e);
        }
        return status;
    }

    /**
     * Delete SMS and MMS messages in a particular thread
     *
     * @return the number of messages deleted
     */
    public static int deleteThread(final long threadId, final long cutOffTimestampInMillis) {
        final ContentResolver resolver = Factory.get().getApplicationContext().getContentResolver();
        final Uri threadUri = ContentUris.withAppendedId(Telephony.Threads.CONTENT_URI, threadId);
        if (cutOffTimestampInMillis < Long.MAX_VALUE) {
            return resolver.delete(threadUri, Sms.DATE + "<=?",
                    new String[] { Long.toString(cutOffTimestampInMillis) });
        } else {
            return resolver.delete(threadUri, null /* smsSelection */, null /* selectionArgs */);
        }
    }

    /**
     * Delete single SMS and MMS message
     *
     * @return number of rows deleted (should be 1 or 0)
     */
    public static int deleteMessage(final Uri messageUri) {
        final ContentResolver resolver = Factory.get().getApplicationContext().getContentResolver();
        return resolver.delete(messageUri, null /* selection */, null /* selectionArgs */);
    }

    public static byte[] createDebugNotificationInd(final String fileName) {
        byte[] pduData = null;
        try {
            final Context context = Factory.get().getApplicationContext();
            // Load the message file
            final byte[] data = DebugUtils.receiveFromDumpFile(fileName);
            final RetrieveConf retrieveConf = receiveFromDumpFile(data);
            // Create the notification
            final NotificationInd notification = new NotificationInd();
            final long expiry = System.currentTimeMillis() / 1000 + 600;
            notification.setTransactionId(fileName.getBytes());
            notification.setMmsVersion(retrieveConf.getMmsVersion());
            notification.setFrom(retrieveConf.getFrom());
            notification.setSubject(retrieveConf.getSubject());
            notification.setExpiry(expiry);
            notification.setMessageSize(data.length);
            notification.setMessageClass(retrieveConf.getMessageClass());

            final Uri.Builder builder = MediaScratchFileProvider.getUriBuilder();
            builder.appendPath(fileName);
            final Uri contentLocation = builder.build();
            notification.setContentLocation(contentLocation.toString().getBytes());

            // Serialize
            pduData = new PduComposer(context, notification).make();
            if (pduData == null || pduData.length < 1) {
                throw new IllegalArgumentException("Empty or zero length PDU data");
            }
        } catch (final MmsFailureException e) {
            // Nothing to do
        } catch (final InvalidHeaderValueException e) {
            // Nothing to do
        }
        return pduData;
    }

    public static int mapRawStatusToErrorResourceId(final int bugleStatus, final int rawStatus) {
        int stringResId = R.string.message_status_send_failed;
        switch (rawStatus) {
            case PduHeaders.RESPONSE_STATUS_ERROR_SERVICE_DENIED:
            case PduHeaders.RESPONSE_STATUS_ERROR_PERMANENT_SERVICE_DENIED:
            //case PduHeaders.RESPONSE_STATUS_ERROR_PERMANENT_REPLY_CHARGING_LIMITATIONS_NOT_MET:
            //case PduHeaders.RESPONSE_STATUS_ERROR_PERMANENT_REPLY_CHARGING_REQUEST_NOT_ACCEPTED:
            //case PduHeaders.RESPONSE_STATUS_ERROR_PERMANENT_REPLY_CHARGING_FORWARDING_DENIED:
            //case PduHeaders.RESPONSE_STATUS_ERROR_PERMANENT_REPLY_CHARGING_NOT_SUPPORTED:
            //case PduHeaders.RESPONSE_STATUS_ERROR_PERMANENT_ADDRESS_HIDING_NOT_SUPPORTED:
            //case PduHeaders.RESPONSE_STATUS_ERROR_PERMANENT_LACK_OF_PREPAID:
                stringResId = R.string.mms_failure_outgoing_service;
                break;
            case PduHeaders.RESPONSE_STATUS_ERROR_SENDING_ADDRESS_UNRESOLVED:
            case PduHeaders.RESPONSE_STATUS_ERROR_TRANSIENT_SENDNG_ADDRESS_UNRESOLVED:
            case PduHeaders.RESPONSE_STATUS_ERROR_PERMANENT_SENDING_ADDRESS_UNRESOLVED:
                stringResId = R.string.mms_failure_outgoing_address;
                break;
            case PduHeaders.RESPONSE_STATUS_ERROR_MESSAGE_FORMAT_CORRUPT:
            case PduHeaders.RESPONSE_STATUS_ERROR_PERMANENT_MESSAGE_FORMAT_CORRUPT:
                stringResId = R.string.mms_failure_outgoing_corrupt;
                break;
            case PduHeaders.RESPONSE_STATUS_ERROR_CONTENT_NOT_ACCEPTED:
            case PduHeaders.RESPONSE_STATUS_ERROR_PERMANENT_CONTENT_NOT_ACCEPTED:
                stringResId = R.string.mms_failure_outgoing_content;
                break;
            case PduHeaders.RESPONSE_STATUS_ERROR_UNSUPPORTED_MESSAGE:
            //case PduHeaders.RESPONSE_STATUS_ERROR_MESSAGE_NOT_FOUND:
            //case PduHeaders.RESPONSE_STATUS_ERROR_TRANSIENT_MESSAGE_NOT_FOUND:
                stringResId = R.string.mms_failure_outgoing_unsupported;
                break;
            case MessageData.RAW_TELEPHONY_STATUS_MESSAGE_TOO_BIG:
                stringResId = R.string.mms_failure_outgoing_too_large;
                break;
        }
        return stringResId;
    }

    /**
     * Dump the raw MMS data into a file
     *
     * @param rawPdu The raw pdu data
     * @param pdu The parsed pdu, used to construct a dump file name
     */
    public static void dumpPdu(final byte[] rawPdu, final GenericPdu pdu) {
        if (rawPdu == null || rawPdu.length < 1) {
            return;
        }
        final String dumpFileName = MmsUtils.MMS_DUMP_PREFIX + getDumpFileId(pdu);
        final File dumpFile = DebugUtils.getDebugFile(dumpFileName, true);
        if (dumpFile != null) {
            try {
                final FileOutputStream fos = new FileOutputStream(dumpFile);
                final BufferedOutputStream bos = new BufferedOutputStream(fos);
                try {
                    bos.write(rawPdu);
                    bos.flush();
                } finally {
                    bos.close();
                }
                DebugUtils.ensureReadable(dumpFile);
            } catch (final IOException e) {
                LogUtil.e(TAG, "dumpPdu: " + e, e);
            }
        }
    }

    /**
     * Get the dump file id based on the parsed PDU
     * 1. Use message id if not empty
     * 2. Use transaction id if message id is empty
     * 3. If all above is empty, use random UUID
     *
     * @param pdu the parsed PDU
     * @return the id of the dump file
     */
    private static String getDumpFileId(final GenericPdu pdu) {
        String fileId = null;
        if (pdu != null && pdu instanceof RetrieveConf) {
            final RetrieveConf retrieveConf = (RetrieveConf) pdu;
            if (retrieveConf.getMessageId() != null) {
                fileId = new String(retrieveConf.getMessageId());
            } else if (retrieveConf.getTransactionId() != null) {
                fileId = new String(retrieveConf.getTransactionId());
            }
        }
        if (TextUtils.isEmpty(fileId)) {
            fileId = UUID.randomUUID().toString();
        }
        return fileId;
    }
}
