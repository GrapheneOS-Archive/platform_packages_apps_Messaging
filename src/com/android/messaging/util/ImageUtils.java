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

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader.TileMode;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;

import com.android.messaging.Factory;
import com.android.messaging.datamodel.MediaScratchFileProvider;
import com.android.messaging.datamodel.MessagingContentProvider;
import com.android.messaging.datamodel.media.ImageRequest;
import com.android.messaging.util.Assert.DoesNotRunOnMainThread;
import com.android.messaging.util.exif.ExifInterface;
import com.google.common.annotations.VisibleForTesting;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import androidx.annotation.Nullable;

public class ImageUtils {
    private static final String TAG = LogUtil.BUGLE_TAG;
    private static final int MAX_OOM_COUNT = 1;
    private static final byte[] GIF87_HEADER = "GIF87a".getBytes(Charset.forName("US-ASCII"));
    private static final byte[] GIF89_HEADER = "GIF89a".getBytes(Charset.forName("US-ASCII"));

    // Used for drawBitmapWithCircleOnCanvas.
    // Default color is transparent for both circle background and stroke.
    public static final int DEFAULT_CIRCLE_BACKGROUND_COLOR = 0;
    public static final int DEFAULT_CIRCLE_STROKE_COLOR = 0;

    private static volatile ImageUtils sInstance;

    public static ImageUtils get() {
        if (sInstance == null) {
            synchronized (ImageUtils.class) {
                if (sInstance == null) {
                    sInstance = new ImageUtils();
                }
            }
        }
        return sInstance;
    }

    @VisibleForTesting
    public static void set(final ImageUtils imageUtils) {
        sInstance = imageUtils;
    }

    /**
     * Transforms a bitmap into a byte array.
     *
     * @param quality Value between 0 and 100 that the compressor uses to discern what quality the
     *                resulting bytes should be
     * @param bitmap Bitmap to convert into bytes
     * @return byte array of bitmap
     */
    public static byte[] bitmapToBytes(final Bitmap bitmap, final int quality)
            throws OutOfMemoryError {
        boolean done = false;
        int oomCount = 0;
        byte[] imageBytes = null;
        while (!done) {
            try {
                final ByteArrayOutputStream os = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, os);
                imageBytes = os.toByteArray();
                done = true;
            } catch (final OutOfMemoryError e) {
                LogUtil.w(TAG, "OutOfMemory converting bitmap to bytes.");
                oomCount++;
                if (oomCount <= MAX_OOM_COUNT) {
                    Factory.get().reclaimMemory();
                } else {
                    done = true;
                    LogUtil.w(TAG, "Failed to convert bitmap to bytes. Out of Memory.");
                }
                throw e;
            }
        }
        return imageBytes;
    }

    /**
     * Given the source bitmap and a canvas, draws the bitmap through a circular
     * mask. Only draws a circle with diameter equal to the destination width.
     *
     * @param bitmap The source bitmap to draw.
     * @param canvas The canvas to draw it on.
     * @param source The source bound of the bitmap.
     * @param dest The destination bound on the canvas.
     * @param bitmapPaint Optional Paint object for the bitmap
     * @param fillBackground when set, fill the circle with backgroundColor
     * @param strokeColor draw a border outside the circle with strokeColor
     */
    public static void drawBitmapWithCircleOnCanvas(final Bitmap bitmap, final Canvas canvas,
            final RectF source, final RectF dest, @Nullable Paint bitmapPaint,
            final boolean fillBackground, final int backgroundColor, int strokeColor) {
        // Draw bitmap through shader first.
        final BitmapShader shader = new BitmapShader(bitmap, TileMode.CLAMP, TileMode.CLAMP);
        final Matrix matrix = new Matrix();

        // Fit bitmap to bounds.
        matrix.setRectToRect(source, dest, Matrix.ScaleToFit.CENTER);

        shader.setLocalMatrix(matrix);

        if (bitmapPaint == null) {
            bitmapPaint = new Paint();
        }

        bitmapPaint.setAntiAlias(true);
        if (fillBackground) {
            bitmapPaint.setColor(backgroundColor);
            canvas.drawCircle(dest.centerX(), dest.centerX(), dest.width() / 2f, bitmapPaint);
        }

        bitmapPaint.setShader(shader);
        canvas.drawCircle(dest.centerX(), dest.centerX(), dest.width() / 2f, bitmapPaint);
        bitmapPaint.setShader(null);

        if (strokeColor != 0) {
            final Paint stroke = new Paint();
            stroke.setAntiAlias(true);
            stroke.setColor(strokeColor);
            stroke.setStyle(Paint.Style.STROKE);
            final float strokeWidth = 6f;
            stroke.setStrokeWidth(strokeWidth);
            canvas.drawCircle(dest.centerX(),
                    dest.centerX(),
                    dest.width() / 2f - stroke.getStrokeWidth() / 2f,
                    stroke);
        }
    }

    /**
     * Sets a drawable to the background of a view. setBackgroundDrawable() is deprecated since
     * JB and replaced by setBackground().
     */
    public static void setBackgroundDrawableOnView(final View view, final Drawable drawable) {
        view.setBackground(drawable);
    }

    /**
     * Based on the input bitmap bounds given by BitmapFactory.Options, compute the required
     * sub-sampling size for loading a scaled down version of the bitmap to the required size
     * @param options a BitmapFactory.Options instance containing the bounds info of the bitmap
     * @param reqWidth the desired width of the bitmap. Can be ImageRequest.UNSPECIFIED_SIZE.
     * @param reqHeight the desired height of the bitmap.  Can be ImageRequest.UNSPECIFIED_SIZE.
     * @return
     */
    public int calculateInSampleSize(
            final BitmapFactory.Options options, final int reqWidth, final int reqHeight) {
        // Raw height and width of image
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        final boolean checkHeight = reqHeight != ImageRequest.UNSPECIFIED_SIZE;
        final boolean checkWidth = reqWidth != ImageRequest.UNSPECIFIED_SIZE;
        if ((checkHeight && height > reqHeight) ||
                (checkWidth && width > reqWidth)) {

            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            // Calculate the largest inSampleSize value that is a power of 2 and keeps both
            // height and width larger than the requested height and width.
            while ((!checkHeight || (halfHeight / inSampleSize) > reqHeight)
                    && (!checkWidth || (halfWidth / inSampleSize) > reqWidth)) {
                inSampleSize *= 2;
            }
        }

        return inSampleSize;
    }

    private static final String[] MEDIA_CONTENT_PROJECTION = new String[] {
        MediaStore.MediaColumns.MIME_TYPE
    };

    private static final int INDEX_CONTENT_TYPE = 0;

    @DoesNotRunOnMainThread
    public static String getContentType(final ContentResolver cr, final Uri uri) {
        // Figure out the content type of media.
        String contentType = null;
        Cursor cursor = null;
        if (UriUtil.isMediaStoreUri(uri)) {
            try {
                cursor = cr.query(uri, MEDIA_CONTENT_PROJECTION, null, null, null);

                if (cursor != null && cursor.moveToFirst()) {
                    contentType = cursor.getString(INDEX_CONTENT_TYPE);
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        if (contentType == null) {
            // Last ditch effort to get the content type. Look at the file extension.
            contentType = ContentType.getContentTypeFromExtension(uri.toString(),
                    ContentType.IMAGE_UNSPECIFIED);
        }
        return contentType;
    }

    /**
     * @param context Android context
     * @param uri Uri to the image data
     * @return The exif orientation value for the image in the specified uri
     */
    public static int getOrientation(final Context context, final Uri uri) {
        try {
            return getOrientation(context.getContentResolver().openInputStream(uri));
        } catch (FileNotFoundException e) {
            LogUtil.e(TAG, "getOrientation couldn't open: " + uri, e);
        }
        return android.media.ExifInterface.ORIENTATION_UNDEFINED;
    }

    /**
     * @param inputStream The stream to the image file.  Closed on completion
     * @return The exif orientation value for the image in the specified stream
     */
    public static int getOrientation(final InputStream inputStream) {
        int orientation = android.media.ExifInterface.ORIENTATION_UNDEFINED;
        if (inputStream != null) {
            try {
                final ExifInterface exifInterface = new ExifInterface();
                exifInterface.readExif(inputStream);
                final Integer orientationValue =
                        exifInterface.getTagIntValue(ExifInterface.TAG_ORIENTATION);
                if (orientationValue != null) {
                    orientation = orientationValue.intValue();
                }
            } catch (IOException e) {
                // If the image if GIF, PNG, or missing exif header, just use the defaults
            } finally {
                try {
                    if (inputStream != null) {
                        inputStream.close();
                    }
                } catch (IOException e) {
                    LogUtil.e(TAG, "getOrientation error closing input stream", e);
                }
            }
        }
        return orientation;
    }

    /**
     * Returns whether the resource is a GIF image.
     */
    public static boolean isGif(String contentType, Uri contentUri) {
        if (TextUtils.equals(contentType, ContentType.IMAGE_GIF)) {
            return true;
        }
        if (ContentType.isImageType(contentType)) {
            try {
                ContentResolver contentResolver = Factory.get().getApplicationContext()
                        .getContentResolver();
                InputStream inputStream = contentResolver.openInputStream(contentUri);
                return ImageUtils.isGif(inputStream);
            } catch (Exception e) {
                LogUtil.w(TAG, "Could not open GIF input stream", e);
            }
        }
        // Assume anything with a non-image content type is not a GIF
        return false;
    }

    /**
     * @param inputStream The stream to the image file. Closed on completion
     * @return Whether the image stream represents a GIF
     */
    public static boolean isGif(InputStream inputStream) {
        if (inputStream != null) {
            try {
                byte[] gifHeaderBytes = new byte[6];
                int value = inputStream.read(gifHeaderBytes, 0, 6);
                if (value == 6) {
                    return Arrays.equals(gifHeaderBytes, GIF87_HEADER)
                            || Arrays.equals(gifHeaderBytes, GIF89_HEADER);
                }
            } catch (IOException e) {
                return false;
            } finally {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
        return false;
    }

    /**
     * Read an image and compress it to particular max dimensions and size.
     * Used to ensure images can fit in an MMS.
     * TODO: This uses memory very inefficiently as it processes the whole image as a unit
     *  (rather than slice by slice) but system JPEG functions do not support slicing and dicing.
     */
    public static class ImageResizer {

        /**
         * The quality parameter which is used to compress JPEG images.
         */
        private static final int IMAGE_COMPRESSION_QUALITY = 95;
        /**
         * The minimum quality parameter which is used to compress JPEG images.
         */
        private static final int MINIMUM_IMAGE_COMPRESSION_QUALITY = 50;

        /**
         * Minimum factor to reduce quality value
         */
        private static final double QUALITY_SCALE_DOWN_RATIO = 0.85f;

        /**
         * Maximum passes through the resize loop before failing permanently
         */
        private static final int NUMBER_OF_RESIZE_ATTEMPTS = 6;

        /**
         * Amount to scale down the picture when it doesn't fit
         */
        private static final float MIN_SCALE_DOWN_RATIO = 0.75f;

        /**
         * When computing sampleSize target scaling of no more than this ratio
         */
        private static final float MAX_TARGET_SCALE_FACTOR = 1.5f;


        // Current sample size for subsampling image during initial decode
        private int mSampleSize;
        // Current bitmap holding initial decoded source image
        private Bitmap mDecoded;
        // If scaling is needed this holds the scaled bitmap (else should equal mDecoded)
        private Bitmap mScaled;
        // Current JPEG compression quality to use when compressing image
        private int mQuality;
        // Current factor to scale down decoded image before compressing
        private float mScaleFactor;
        // Flag keeping track of whether cache memory has been reclaimed
        private boolean mHasReclaimedMemory;

        // Initial size of the image (typically provided but can be UNSPECIFIED_SIZE)
        private int mWidth;
        private int mHeight;
        // Orientation params of image as read from EXIF data
        private final ExifInterface.OrientationParams mOrientationParams;
        // Matrix to undo orientation and scale at the same time
        private final Matrix mMatrix;
        // Size limit as provided by MMS library
        private final int mWidthLimit;
        private final int mHeightLimit;
        private final int mByteLimit;
        //  Uri from which to read source image
        private final Uri mUri;
        // Application context
        private final Context mContext;
        // Cached value of bitmap factory options
        private final BitmapFactory.Options mOptions;
        private final String mContentType;

        private final int mMemoryClass;

        /**
         * Return resized (compressed) image (else null)
         *
         * @param width The width of the image (if known)
         * @param height The height of the image (if known)
         * @param orientation The orientation of the image as an ExifInterface constant
         * @param widthLimit The width limit, in pixels
         * @param heightLimit The height limit, in pixels
         * @param byteLimit The binary size limit, in bytes
         * @param uri Uri to the image data
         * @param context Needed to open the image
         * @param contentType of image
         * @return encoded image meeting size requirements else null
         */
        public static byte[] getResizedImageData(final int width, final int height,
                final int orientation, final int widthLimit, final int heightLimit,
                final int byteLimit, final Uri uri, final Context context,
                final String contentType) {
            final ImageResizer resizer = new ImageResizer(width, height, orientation,
                    widthLimit, heightLimit, byteLimit, uri, context, contentType);
            return resizer.resize();
        }

        /**
         * Create and initialize an image resizer
         */
        private ImageResizer(final int width, final int height, final int orientation,
                final int widthLimit, final int heightLimit, final int byteLimit, final Uri uri,
                final Context context, final String contentType) {
            mWidth = width;
            mHeight = height;
            mOrientationParams = ExifInterface.getOrientationParams(orientation);
            mMatrix = new Matrix();
            mWidthLimit = widthLimit;
            mHeightLimit = heightLimit;
            mByteLimit = byteLimit;
            mUri = uri;
            mWidth = width;
            mContext = context;
            mQuality = IMAGE_COMPRESSION_QUALITY;
            mScaleFactor = 1.0f;
            mHasReclaimedMemory = false;
            mOptions = new BitmapFactory.Options();
            mOptions.inScaled = false;
            mOptions.inDensity = 0;
            mOptions.inTargetDensity = 0;
            mOptions.inSampleSize = 1;
            mOptions.inJustDecodeBounds = false;
            mOptions.inMutable = false;
            final ActivityManager am =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            mMemoryClass = Math.max(16, am.getMemoryClass());
            mContentType = contentType;
        }

        /**
         * Try to compress the image
         *
         * @return encoded image meeting size requirements else null
         */
        private byte[] resize() {
            return ImageUtils.isGif(mContentType, mUri) ? resizeGifImage() : resizeStaticImage();
        }

        private byte[] resizeGifImage() {
            byte[] bytesToReturn = null;
            final String inputFilePath;
            if (MediaScratchFileProvider.isMediaScratchSpaceUri(mUri)) {
                inputFilePath = MediaScratchFileProvider.getFileFromUri(mUri).getAbsolutePath();
            } else {
                if (!UriUtil.isFileUri(mUri)) {
                    Assert.fail("Expected a GIF file uri, but actual uri = " + mUri.toString());
                }
                inputFilePath = mUri.getPath();
            }

            if (GifTranscoder.canBeTranscoded(mWidth, mHeight)) {
                // Needed to perform the transcoding so that the gif can continue to play in the
                // conversation while the sending is taking place
                final Uri tmpUri = MediaScratchFileProvider.buildMediaScratchSpaceUri("gif");
                final File outputFile = MediaScratchFileProvider.getFileFromUri(tmpUri);
                final String outputFilePath = outputFile.getAbsolutePath();

                final boolean success =
                        GifTranscoder.transcode(mContext, inputFilePath, outputFilePath);
                if (success) {
                    try {
                        bytesToReturn = Files.readAllBytes(outputFile.toPath());
                    } catch (IOException e) {
                        LogUtil.e(TAG, "readAllBytes failed for " + outputFilePath, e);
                    }
                }

                // Need to clean up the new file created to compress the gif
                mContext.getContentResolver().delete(tmpUri, null, null);
            } else {
                // We don't want to transcode the gif because its image dimensions would be too
                // small so just return the bytes of the original gif
                try {
                    bytesToReturn = Files.readAllBytes(Path.of(inputFilePath));
                } catch (IOException e) {
                    LogUtil.e(TAG, "readAllBytes failed for " + inputFilePath, e);
                }
            }

            return bytesToReturn;
        }

        private byte[] resizeStaticImage() {
            if (!ensureImageSizeSet()) {
                // Cannot read image size
                return null;
            }
            // Find incoming image size
            if (!canBeCompressed()) {
                return null;
            }

            //  Decode image - if out of memory - reclaim memory and retry
            try {
                for (int attempts = 0; attempts < NUMBER_OF_RESIZE_ATTEMPTS; attempts++) {
                    final byte[] encoded = recodeImage(attempts);

                    // Only return data within the limit
                    if (encoded != null && encoded.length <= mByteLimit) {
                        return encoded;
                    } else {
                        final int currentSize = (encoded == null ? 0 : encoded.length);
                        updateRecodeParameters(currentSize);
                    }
                }
            } catch (final FileNotFoundException e) {
                LogUtil.e(TAG, "File disappeared during resizing");
            } finally {
                // Release all bitmaps
                if (mScaled != null && mScaled != mDecoded) {
                    mScaled.recycle();
                }
                if (mDecoded != null) {
                    mDecoded.recycle();
                }
            }
            return null;
        }

        /**
         * Ensure that the width and height of the source image are known
         * @return flag indicating whether size is known
         */
        private boolean ensureImageSizeSet() {
            if (mWidth == MessagingContentProvider.UNSPECIFIED_SIZE ||
                    mHeight == MessagingContentProvider.UNSPECIFIED_SIZE) {
                // First get the image data (compressed)
                final ContentResolver cr = mContext.getContentResolver();
                InputStream inputStream = null;
                // Find incoming image size
                try {
                    mOptions.inJustDecodeBounds = true;
                    inputStream = cr.openInputStream(mUri);
                    BitmapFactory.decodeStream(inputStream, null, mOptions);

                    mWidth = mOptions.outWidth;
                    mHeight = mOptions.outHeight;
                    mOptions.inJustDecodeBounds = false;

                    return true;
                } catch (final FileNotFoundException e) {
                    LogUtil.e(TAG, "Could not open file corresponding to uri " + mUri, e);
                } catch (final NullPointerException e) {
                    LogUtil.e(TAG, "NPE trying to open the uri " + mUri, e);
                } finally {
                    if (inputStream != null) {
                        try {
                            inputStream.close();
                        } catch (final IOException e) {
                            // Nothing to do
                        }
                    }
                }

                return false;
            }
            return true;
        }

        /**
         * Choose an initial subsamplesize that ensures the decoded image is no more than
         * MAX_TARGET_SCALE_FACTOR bigger than largest supported image and that it is likely to
         * compress to smaller than the target size (assuming compression down to 1 bit per pixel).
         * @return whether the image can be down subsampled
         */
        private boolean canBeCompressed() {
            final boolean logv = LogUtil.isLoggable(LogUtil.BUGLE_IMAGE_TAG, LogUtil.VERBOSE);

            int imageHeight = mHeight;
            int imageWidth = mWidth;

            // Assume can use half working memory to decode the initial image (4 bytes per pixel)
            final int workingMemoryPixelLimit = (mMemoryClass * 1024 * 1024 / 8);
            // Target 1 bits per pixel in final compressed image
            final int finalSizePixelLimit = mByteLimit * 8;
            // When choosing to halve the resolution - only do so the image will still be too big
            // after scaling by MAX_TARGET_SCALE_FACTOR
            final int heightLimitWithSlop = (int) (mHeightLimit * MAX_TARGET_SCALE_FACTOR);
            final int widthLimitWithSlop = (int) (mWidthLimit * MAX_TARGET_SCALE_FACTOR);
            final int pixelLimitWithSlop = (int) (finalSizePixelLimit *
                    MAX_TARGET_SCALE_FACTOR * MAX_TARGET_SCALE_FACTOR);
            final int pixelLimit = Math.min(pixelLimitWithSlop, workingMemoryPixelLimit);

            int sampleSize = 1;
            boolean fits = (imageHeight < heightLimitWithSlop &&
                    imageWidth < widthLimitWithSlop &&
                    imageHeight * imageWidth < pixelLimit);

            // Compare sizes to compute sub-sampling needed
            while (!fits) {
                sampleSize = sampleSize * 2;
                // Note that recodeImage may try using mSampleSize * 2. Hence we use the factor of 4
                if (sampleSize >= (Integer.MAX_VALUE / 4)) {
                    LogUtil.w(LogUtil.BUGLE_IMAGE_TAG, String.format(
                            "Cannot resize image: widthLimit=%d heightLimit=%d byteLimit=%d " +
                            "imageWidth=%d imageHeight=%d", mWidthLimit, mHeightLimit, mByteLimit,
                            mWidth, mHeight));
                    Assert.fail("Image cannot be resized"); // http://b/18926934
                    return false;
                }
                if (logv) {
                    LogUtil.v(LogUtil.BUGLE_IMAGE_TAG,
                            "computeInitialSampleSize: Increasing sampleSize to " + sampleSize
                            + " as h=" + imageHeight + " vs " + heightLimitWithSlop
                            + " w=" + imageWidth  + " vs " +  widthLimitWithSlop
                            + " p=" + imageHeight * imageWidth + " vs " + pixelLimit);
                }
                imageHeight = mHeight / sampleSize;
                imageWidth = mWidth / sampleSize;
                fits = (imageHeight < heightLimitWithSlop &&
                        imageWidth < widthLimitWithSlop &&
                        imageHeight * imageWidth < pixelLimit);
            }

            if (logv) {
                LogUtil.v(LogUtil.BUGLE_IMAGE_TAG,
                        "computeInitialSampleSize: Initial sampleSize " + sampleSize
                        + " for h=" + imageHeight + " vs " + heightLimitWithSlop
                        + " w=" + imageWidth  + " vs " +  widthLimitWithSlop
                        + " p=" + imageHeight * imageWidth + " vs " + pixelLimit);
            }

            mSampleSize = sampleSize;
            return true;
        }

        /**
         * Recode the image from initial Uri to encoded JPEG
         * @param attempt Attempt number
         * @return encoded image
         */
        private byte[] recodeImage(final int attempt) throws FileNotFoundException {
            byte[] encoded = null;
            try {
                final ContentResolver cr = mContext.getContentResolver();
                final boolean logv = LogUtil.isLoggable(LogUtil.BUGLE_IMAGE_TAG, LogUtil.VERBOSE);
                if (logv) {
                    LogUtil.v(LogUtil.BUGLE_IMAGE_TAG, "getResizedImageData: attempt=" + attempt
                            + " limit (w=" + mWidthLimit + " h=" + mHeightLimit + ") quality="
                            + mQuality + " scale=" + mScaleFactor + " sampleSize=" + mSampleSize);
                }
                if (mScaled == null) {
                    if (mDecoded == null) {
                        mOptions.inSampleSize = mSampleSize;
                        try (final InputStream inputStream = cr.openInputStream(mUri)) {
                            mDecoded = BitmapFactory.decodeStream(inputStream, null, mOptions);
                        } catch (IOException e) {
                            // Ignore
                        }
                        if (mDecoded == null) {
                            if (logv) {
                                LogUtil.v(LogUtil.BUGLE_IMAGE_TAG,
                                        "getResizedImageData: got empty decoded bitmap");
                            }
                            return null;
                        }
                    }
                    if (logv) {
                        LogUtil.v(LogUtil.BUGLE_IMAGE_TAG, "getResizedImageData: decoded w,h="
                                + mDecoded.getWidth() + "," + mDecoded.getHeight());
                    }
                    // Make sure to scale the decoded image if dimension is not within limit
                    final int decodedWidth = mDecoded.getWidth();
                    final int decodedHeight = mDecoded.getHeight();
                    if (decodedWidth > mWidthLimit || decodedHeight > mHeightLimit) {
                        final float minScaleFactor = Math.max(
                                mWidthLimit == 0 ? 1.0f :
                                    (float) decodedWidth / (float) mWidthLimit,
                                    mHeightLimit == 0 ? 1.0f :
                                        (float) decodedHeight / (float) mHeightLimit);
                        if (mScaleFactor < minScaleFactor) {
                            mScaleFactor = minScaleFactor;
                        }
                    }
                    if (mScaleFactor > 1.0 || mOrientationParams.rotation != 0) {
                        mMatrix.reset();
                        mMatrix.postRotate(mOrientationParams.rotation);
                        mMatrix.postScale(mOrientationParams.scaleX / mScaleFactor,
                                mOrientationParams.scaleY / mScaleFactor);
                        mScaled = Bitmap.createBitmap(mDecoded, 0, 0, decodedWidth, decodedHeight,
                                mMatrix, false /* filter */);
                        if (mScaled == null) {
                            if (logv) {
                                LogUtil.v(LogUtil.BUGLE_IMAGE_TAG,
                                        "getResizedImageData: got empty scaled bitmap");
                            }
                            return null;
                        }
                        if (logv) {
                            LogUtil.v(LogUtil.BUGLE_IMAGE_TAG, "getResizedImageData: scaled w,h="
                                    + mScaled.getWidth() + "," + mScaled.getHeight());
                        }
                    } else {
                        mScaled = mDecoded;
                    }
                }
                // Now encode it at current quality
                encoded = ImageUtils.bitmapToBytes(mScaled, mQuality);
                if (encoded != null && logv) {
                    LogUtil.v(LogUtil.BUGLE_IMAGE_TAG,
                            "getResizedImageData: Encoded down to " + encoded.length + "@"
                                    + mScaled.getWidth() + "/" + mScaled.getHeight() + "~"
                                    + mQuality);
                }
            } catch (final OutOfMemoryError e) {
                LogUtil.w(LogUtil.BUGLE_IMAGE_TAG,
                        "getResizedImageData - image too big (OutOfMemoryError), will try "
                                + " with smaller scale factor");
                // fall through and keep trying with more compression
            }
            return encoded;
        }

        /**
         * When image recode fails this method updates compression parameters for the next attempt
         * @param currentSize encoded image size (will be 0 if OOM)
         */
        private void updateRecodeParameters(final int currentSize) {
            final boolean logv = LogUtil.isLoggable(LogUtil.BUGLE_IMAGE_TAG, LogUtil.VERBOSE);
            // Only return data within the limit
            if (currentSize > 0 &&
                    mQuality > MINIMUM_IMAGE_COMPRESSION_QUALITY) {
                // First if everything succeeded but failed to hit target size
                // Try quality proportioned to sqrt of size over size limit
                mQuality = Math.max(MINIMUM_IMAGE_COMPRESSION_QUALITY,
                        Math.min((int) (mQuality * Math.sqrt((1.0 * mByteLimit) / currentSize)),
                                (int) (mQuality * QUALITY_SCALE_DOWN_RATIO)));
                if (logv) {
                    LogUtil.v(LogUtil.BUGLE_IMAGE_TAG,
                            "getResizedImageData: Retrying at quality " + mQuality);
                }
            } else if (currentSize > 0 &&
                    mScaleFactor < 2.0 * MIN_SCALE_DOWN_RATIO * MIN_SCALE_DOWN_RATIO) {
                // JPEG compression failed to hit target size - need smaller image
                // First try scaling by a little (< factor of 2) just so long resulting scale down
                // ratio is still significantly bigger than next subsampling step
                // i.e. mScaleFactor/MIN_SCALE_DOWN_RATIO (new scaling factor) <
                //       2.0 / MIN_SCALE_DOWN_RATIO (arbitrary limit)
                mQuality = IMAGE_COMPRESSION_QUALITY;
                mScaleFactor = mScaleFactor / MIN_SCALE_DOWN_RATIO;
                if (logv) {
                    LogUtil.v(LogUtil.BUGLE_IMAGE_TAG,
                            "getResizedImageData: Retrying at scale " + mScaleFactor);
                }
                // Release scaled bitmap to trigger rescaling
                if (mScaled != null && mScaled != mDecoded) {
                    mScaled.recycle();
                }
                mScaled = null;
            } else if (currentSize <= 0 && !mHasReclaimedMemory) {
                // Then before we subsample try cleaning up our cached memory
                Factory.get().reclaimMemory();
                mHasReclaimedMemory = true;
                if (logv) {
                    LogUtil.v(LogUtil.BUGLE_IMAGE_TAG,
                            "getResizedImageData: Retrying after reclaiming memory ");
                }
            } else {
                // Last resort - subsample image by another factor of 2 and try again
                mSampleSize = mSampleSize * 2;
                mQuality = IMAGE_COMPRESSION_QUALITY;
                mScaleFactor = 1.0f;
                if (logv) {
                    LogUtil.v(LogUtil.BUGLE_IMAGE_TAG,
                            "getResizedImageData: Retrying at sampleSize " + mSampleSize);
                }
                // Release all bitmaps to trigger subsampling
                if (mScaled != null && mScaled != mDecoded) {
                    mScaled.recycle();
                }
                mScaled = null;
                if (mDecoded != null) {
                    mDecoded.recycle();
                    mDecoded = null;
                }
            }
        }
    }

    /**
     * Scales and center-crops a bitmap to the size passed in and returns the new bitmap.
     *
     * @param source Bitmap to scale and center-crop
     * @param newWidth destination width
     * @param newHeight destination height
     * @return Bitmap scaled and center-cropped bitmap
     */
    public static Bitmap scaleCenterCrop(final Bitmap source, final int newWidth,
            final int newHeight) {
        final int sourceWidth = source.getWidth();
        final int sourceHeight = source.getHeight();

        // Compute the scaling factors to fit the new height and width, respectively.
        // To cover the final image, the final scaling will be the bigger
        // of these two.
        final float xScale = (float) newWidth / sourceWidth;
        final float yScale = (float) newHeight / sourceHeight;
        final float scale = Math.max(xScale, yScale);

        // Now get the size of the source bitmap when scaled
        final float scaledWidth = scale * sourceWidth;
        final float scaledHeight = scale * sourceHeight;

        // Let's find out the upper left coordinates if the scaled bitmap
        // should be centered in the new size give by the parameters
        final float left = (newWidth - scaledWidth) / 2;
        final float top = (newHeight - scaledHeight) / 2;

        // The target rectangle for the new, scaled version of the source bitmap will now
        // be
        final RectF targetRect = new RectF(left, top, left + scaledWidth, top + scaledHeight);

        // Finally, we create a new bitmap of the specified size and draw our new,
        // scaled bitmap onto it.
        final Bitmap dest = Bitmap.createBitmap(newWidth, newHeight, source.getConfig());
        final Canvas canvas = new Canvas(dest);
        canvas.drawBitmap(source, null, targetRect, null);

        return dest;
    }

    /**
     *  The drawable can be a Nine-Patch. If we directly use the same drawable instance for each
     *  drawable of different sizes, then the drawable sizes would interfere with each other. The
     *  solution here is to create a new drawable instance for every time with the SAME
     *  ConstantState (i.e. sharing the same common state such as the bitmap, so that we don't have
     *  to recreate the bitmap resource), and apply the different properties on top (nine-patch
     *  size and color tint).
     *
     *  TODO: we are creating new drawable instances here, but there are optimizations that
     *  can be made. For example, message bubbles shouldn't need the mutate() call and the
     *  play/pause buttons shouldn't need to create new drawable from the constant state.
     */
    public static Drawable getTintedDrawable(final Context context, final Drawable drawable,
            final int color) {
        // For some reason occassionally drawables on JB has a null constant state
        final Drawable.ConstantState constantStateDrawable = drawable.getConstantState();
        final Drawable retDrawable = (constantStateDrawable != null)
                ? constantStateDrawable.newDrawable(context.getResources()).mutate()
                : drawable;
        retDrawable.setColorFilter(color, PorterDuff.Mode.SRC_ATOP);
        return retDrawable;
    }

    /**
     * Decodes image resource header and returns the image size.
     */
    public static Rect decodeImageBounds(final Context context, final Uri imageUri) {
        final ContentResolver cr = context.getContentResolver();
        try {
            final InputStream inputStream = cr.openInputStream(imageUri);
            if (inputStream != null) {
                try {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeStream(inputStream, null, options);
                    return new Rect(0, 0, options.outWidth, options.outHeight);
                } finally {
                    try {
                        inputStream.close();
                    } catch (IOException e) {
                        // Do nothing.
                    }
                }
            }
        } catch (FileNotFoundException e) {
            LogUtil.e(TAG, "Couldn't open input stream for uri = " + imageUri);
        }
        return new Rect(0, 0, ImageRequest.UNSPECIFIED_SIZE, ImageRequest.UNSPECIFIED_SIZE);
    }
}
