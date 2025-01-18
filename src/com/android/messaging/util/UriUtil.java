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

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.text.TextUtils;

import com.android.messaging.Factory;
import com.android.messaging.datamodel.GalleryBoundCursorLoader;
import com.android.messaging.datamodel.MediaScratchFileProvider;
import com.android.messaging.util.Assert.DoesNotRunOnMainThread;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.Arrays;
import java.util.HashSet;

import androidx.annotation.NonNull;

public class UriUtil {
    private static final String SCHEME_SMS = "sms";
    private static final String SCHEME_SMSTO = "smsto";
    private static final String SCHEME_MMS = "mms";
    private static final String SCHEME_MMSTO = "smsto";
    public static final HashSet<String> SMS_MMS_SCHEMES = new HashSet<String>(
        Arrays.asList(SCHEME_SMS, SCHEME_MMS, SCHEME_SMSTO, SCHEME_MMSTO));
    private static final String SCHEME_HTTP = "http";
    private static final String SCHEME_HTTPS = "https";

    public static final String SCHEME_BUGLE = "bugle";
    public static final HashSet<String> SUPPORTED_SCHEME = new HashSet<String>(
        Arrays.asList(ContentResolver.SCHEME_ANDROID_RESOURCE,
            ContentResolver.SCHEME_CONTENT,
            ContentResolver.SCHEME_FILE,
            SCHEME_BUGLE));

    public static final String SCHEME_TEL = "tel:";

    /**
     * Get a Uri representation of the file path of a resource file.
     */
    public static Uri getUriForResourceFile(final String path) {
        return TextUtils.isEmpty(path) ? null : Uri.fromFile(new File(path));
    }

    /**
     * Extract the path from a file:// Uri, or null if the uri is of other scheme.
     */
    public static String getFilePathFromUri(final Uri uri) {
        if (!isFileUri(uri)) {
            return null;
        }
        return uri.getPath();
    }

    /**
     * Returns whether the given Uri is local or remote.
     */
    public static boolean isLocalResourceUri(final Uri uri) {
        final String scheme = uri.getScheme();
        return TextUtils.equals(scheme, ContentResolver.SCHEME_ANDROID_RESOURCE) ||
                TextUtils.equals(scheme, ContentResolver.SCHEME_CONTENT) ||
                TextUtils.equals(scheme, ContentResolver.SCHEME_FILE);
    }

    /**
     * Returns whether the given Uri is part of Bugle's app package
     */
    public static boolean isBugleAppResource(final Uri uri) {
        final String scheme = uri.getScheme();
        return TextUtils.equals(scheme, ContentResolver.SCHEME_ANDROID_RESOURCE);
    }

    /** Returns whether the given Uri is a file. */
    public static boolean isFileUri(final Uri uri) {
        return uri != null &&
                uri.getScheme() != null &&
                uri.getScheme().trim().toLowerCase().contains(ContentResolver.SCHEME_FILE);
    }

    /**
     * Constructs an android.resource:// uri for the given resource id.
     */
    public static Uri getUriForResourceId(final Context context, final int resId) {
        return new Uri.Builder()
                .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .authority(context.getPackageName())
                .appendPath(String.valueOf(resId))
                .build();
    }

    /**
     * Returns whether the given Uri string is local.
     */
    public static boolean isLocalUri(@NonNull final Uri uri) {
        Assert.notNull(uri);
        return SUPPORTED_SCHEME.contains(uri.getScheme());
    }

    private static final String MEDIA_STORE_URI_KLP = "com.android.providers.media.documents";

    /**
     * Check if a URI is from the MediaStore
     */
    public static boolean isMediaStoreUri(final Uri uri) {
        final String uriAuthority = uri.getAuthority();
        return TextUtils.equals(ContentResolver.SCHEME_CONTENT, uri.getScheme())
                && (TextUtils.equals(MediaStore.AUTHORITY, uriAuthority) ||
                // KK changed the media store authority name
                TextUtils.equals(MEDIA_STORE_URI_KLP, uriAuthority));
    }

    /**
     * Gets the content:// style URI for the given MediaStore row Id in the files table on the
     * external volume.
     *
     * @param id the MediaStore row Id to get the URI for
     * @return the URI to the files table on the external storage.
     */
    public static Uri getContentUriForMediaStoreId(final long id) {
        return MediaStore.Files.getContentUri(
                GalleryBoundCursorLoader.MEDIA_SCANNER_VOLUME_EXTERNAL, id);
    }

    /**
     * Gets the size in bytes for the content uri. Currently we only support content in the
     * scratch space.
     */
    @DoesNotRunOnMainThread
    public static long getContentSize(final Uri uri) {
        Assert.isNotMainThread();
        if (isLocalResourceUri(uri)) {
            ParcelFileDescriptor pfd = null;
            try {
                pfd = Factory.get().getApplicationContext()
                        .getContentResolver().openFileDescriptor(uri, "r");
                return Math.max(pfd.getStatSize(), 0);
            } catch (final FileNotFoundException e) {
                LogUtil.e(LogUtil.BUGLE_TAG, "Error getting content size", e);
            } finally {
                if (pfd != null) {
                    try {
                        pfd.close();
                    } catch (final IOException e) {
                        // Do nothing.
                    }
                }
            }
        } else {
            Assert.fail("Unsupported uri type!");
        }
        return 0;
    }

    /** @return duration in milliseconds or 0 if not able to determine */
    public static int getMediaDurationMs(final Uri uri) {
        final MediaMetadataRetrieverWrapper retriever = new MediaMetadataRetrieverWrapper();
        try {
            retriever.setDataSource(uri);
            return retriever.extractInteger(MediaMetadataRetriever.METADATA_KEY_DURATION, 0);
        } catch (final IOException e) {
            LogUtil.e(LogUtil.BUGLE_TAG, "Unable extract duration from media file: " + uri, e);
            return 0;
        } finally {
            retriever.release();
        }
    }

    /**
     * Persist a piece of content from the given input stream, byte by byte to the scratch
     * directory.
     * @return the output Uri if the operation succeeded, or null if failed.
     */
    @DoesNotRunOnMainThread
    public static Uri persistContentToScratchSpace(final InputStream inputStream) {
        final Context context = Factory.get().getApplicationContext();
        final Uri scratchSpaceUri = MediaScratchFileProvider.buildMediaScratchSpaceUri(null);
        return copyContent(context, inputStream, scratchSpaceUri);
    }

    /**
     * Persist a piece of content from the given sourceUri, byte by byte to the scratch
     * directory.
     * @return the output Uri if the operation succeeded, or null if failed.
     */
    @DoesNotRunOnMainThread
    public static Uri persistContentToScratchSpace(final Uri sourceUri) {
        InputStream inputStream = null;
        final Context context = Factory.get().getApplicationContext();
        try {
            if (UriUtil.isLocalResourceUri(sourceUri)) {
                inputStream = context.getContentResolver().openInputStream(sourceUri);
            } else {
                // The content is remote. Download it.
                inputStream = getInputStreamFromRemoteUri(sourceUri);
                if (inputStream == null) {
                    return null;
                }
            }
            return persistContentToScratchSpace(inputStream);
        } catch (final Exception ex) {
            LogUtil.e(LogUtil.BUGLE_TAG, "Error while retrieving media ", ex);
            return null;
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (final IOException e) {
                    LogUtil.e(LogUtil.BUGLE_TAG, "error trying to close the inputStream", e);
                }
            }
        }
    }

    @DoesNotRunOnMainThread
    private static InputStream getInputStreamFromRemoteUri(final Uri sourceUri)
            throws IOException {
        if (isRemoteUri(sourceUri)) {
            final URL url = new URL(sourceUri.toString());
            final URLConnection ucon = url.openConnection();
            return new BufferedInputStream(ucon.getInputStream());
        } else {
            return null;
        }
    }

    private static boolean isRemoteUri(final Uri sourceUri) {
        return sourceUri.getScheme().equals(SCHEME_HTTP)
            || sourceUri.getScheme().equals(SCHEME_HTTPS);
    }

    /**
     * Persist a piece of content from the given input stream, byte by byte to the specified
     * directory.
     * @return the output Uri if the operation succeeded, or null if failed.
     */
    @DoesNotRunOnMainThread
    public static Uri persistContent(
            final InputStream inputStream, final File outputDir, final String contentType) {
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            LogUtil.e(LogUtil.BUGLE_TAG, "Error creating " + outputDir.getAbsolutePath());
            return null;
        }

        final Context context = Factory.get().getApplicationContext();
        try {
            final Uri targetUri = Uri.fromFile(FileUtil.getNewFile(outputDir, contentType));
            return copyContent(context, inputStream, targetUri);
        } catch (final IOException e) {
            LogUtil.e(LogUtil.BUGLE_TAG, "Error creating file in " + outputDir.getAbsolutePath());
            return null;
        }
    }

    /**
     * Persist a piece of content from the given sourceUri, byte by byte to the
     * specified output directory.
     * @return the output Uri if the operation succeeded, or null if failed.
     */
    @DoesNotRunOnMainThread
    public static Uri persistContent(
            final Uri sourceUri, final File outputDir, final String contentType) {
        InputStream inputStream = null;
        final Context context = Factory.get().getApplicationContext();
        try {
            if (UriUtil.isLocalResourceUri(sourceUri)) {
                inputStream = context.getContentResolver().openInputStream(sourceUri);
            } else {
                // The content is remote. Download it.
                inputStream = getInputStreamFromRemoteUri(sourceUri);
                if (inputStream == null) {
                    return null;
                }
            }
            return persistContent(inputStream, outputDir, contentType);
        } catch (final Exception ex) {
            LogUtil.e(LogUtil.BUGLE_TAG, "Error while retrieving media ", ex);
            return null;
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (final IOException e) {
                    LogUtil.e(LogUtil.BUGLE_TAG, "error trying to close the inputStream", e);
                }
            }
        }
    }

    /** @return uri of target file, or null on error */
    @DoesNotRunOnMainThread
    private static Uri copyContent(
            final Context context, final InputStream inputStream, final Uri targetUri) {
        Assert.isNotMainThread();
        OutputStream outputStream = null;
        try {
            outputStream = context.getContentResolver().openOutputStream(targetUri);
            inputStream.transferTo(outputStream);
        } catch (final Exception ex) {
            LogUtil.e(LogUtil.BUGLE_TAG, "Error while copying content ", ex);
            return null;
        } finally {
            if (outputStream != null) {
                try {
                    outputStream.flush();
                } catch (final IOException e) {
                    LogUtil.e(LogUtil.BUGLE_TAG, "error trying to flush the outputStream", e);
                    return null;
                } finally {
                    try {
                        outputStream.close();
                    } catch (final IOException e) {
                        // Do nothing.
                    }
                }
            }
        }
        return targetUri;
    }

    public static boolean isSmsMmsUri(final Uri uri) {
        return uri != null && SMS_MMS_SCHEMES.contains(uri.getScheme());
    }

    /**
     * Extract recipient destinations from Uri of form SCHEME:destination[,destination]?otherstuff
     * where SCHEME is one of the supported sms/mms schemes.
     *
     * @param uri sms/mms uri
     * @return a comma-separated list of recipient destinations or null.
     */
    public static String parseRecipientsFromSmsMmsUri(final Uri uri) {
        if (!isSmsMmsUri(uri)) {
            return null;
        }
        final String[] parts = uri.getSchemeSpecificPart().split("\\?");
        if (TextUtils.isEmpty(parts[0])) {
            return null;
        }
        // replaceUnicodeDigits will replace digits typed in other languages (i.e. Egyptian) with
        // the usual ascii equivalents.
        return TextUtil.replaceUnicodeDigits(parts[0]).replace(';', ',');
    }

    /**
     * Return the length of the file to which contentUri refers
     *
     * @param contentUri URI for the file of which we want the length
     * @return Length of the file or AssetFileDescriptor.UNKNOWN_LENGTH
     */
    public static long getUriContentLength(final Uri contentUri) {
        final Context context = Factory.get().getApplicationContext();
        AssetFileDescriptor afd = null;
        try {
            afd = context.getContentResolver().openAssetFileDescriptor(contentUri, "r");
            return afd.getLength();
        } catch (final FileNotFoundException e) {
            LogUtil.w(LogUtil.BUGLE_TAG, "Failed to query length of " + contentUri);
        } finally {
            if (afd != null) {
                try {
                    afd.close();
                } catch (final IOException e) {
                    LogUtil.w(LogUtil.BUGLE_TAG, "Failed to close afd for " + contentUri);
                }
            }
        }
        return AssetFileDescriptor.UNKNOWN_LENGTH;
    }

    /** @return string representation of URI or null if URI was null */
    public static String stringFromUri(final Uri uri) {
        return uri == null ? null : uri.toString();
    }

    /** @return URI created from string or null if string was null or empty */
    public static Uri uriFromString(final String uriString) {
        return TextUtils.isEmpty(uriString) ? null : Uri.parse(uriString);
     }
}
