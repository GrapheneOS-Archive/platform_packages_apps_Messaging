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
package com.android.messaging.datamodel.media;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import com.android.messaging.datamodel.data.MessagePartData;
import com.android.messaging.datamodel.media.PoolableImageCache.ReusableImageResourcePool;
import com.android.messaging.util.Assert;
import com.android.messaging.util.ImageUtils;
import com.android.messaging.util.exif.ExifInterface;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Base class that serves an image request for resolving, retrieving and decoding bitmap resources.
 *
 * Subclasses may choose to load images from different medium, such as from the file system or
 * from the local content resolver, by overriding the abstract getInputStreamForResource() method.
 */
public abstract class ImageRequest<D extends ImageRequestDescriptor>
        implements MediaRequest<ImageResource> {
    public static final int UNSPECIFIED_SIZE = MessagePartData.UNSPECIFIED_SIZE;

    protected final Context mContext;
    protected final D mDescriptor;
    protected int mOrientation;

    /**
     * Creates a new image request with the given descriptor.
     */
    public ImageRequest(final Context context, final D descriptor) {
        mContext = context;
        mDescriptor = descriptor;
    }

    /**
     * Gets a key that uniquely identify the underlying image resource to be loaded (e.g. Uri or
     * file path).
     */
    @Override
    public String getKey() {
        return mDescriptor.getKey();
    }

    /**
     * Returns the image request descriptor attached to this request.
     */
    @Override
    public D getDescriptor() {
        return mDescriptor;
    }

    @Override
    public int getRequestType() {
        return MediaRequest.REQUEST_LOAD_MEDIA;
    }

    /**
     * Allows sub classes to specify that they want us to call getBitmapForResource rather than
     * getInputStreamForResource
     */
    protected boolean hasBitmapObject() {
        return false;
    }

    protected Bitmap getBitmapForResource() throws IOException {
        return null;
    }

    /**
     * Retrieves an input stream from which image resource could be loaded.
     * @throws FileNotFoundException
     */
    protected abstract InputStream getInputStreamForResource() throws FileNotFoundException;

    /**
     * Loads the image resource. This method is final; to override the media loading behavior
     * the subclass should override {@link #loadMediaInternal(List)}
     */
    @Override
    public final ImageResource loadMediaBlocking(List<MediaRequest<ImageResource>> chainedTask)
            throws IOException {
        Assert.isNotMainThread();
        final ImageResource loadedResource = loadMediaInternal(chainedTask);
        return postProcessOnBitmapResourceLoaded(loadedResource);
    }

    protected ImageResource loadMediaInternal(List<MediaRequest<ImageResource>> chainedTask)
            throws IOException {
        if (!mDescriptor.isStatic() && isGif()) {
            return GifImageResource.createGifImageResource(mContext, getKey(), getInputStreamForResource());
        } else {
            final Bitmap loadedBitmap = loadBitmapInternal();
            if (loadedBitmap == null) {
                throw new RuntimeException("failed decoding bitmap");
            }
            return new DecodedImageResource(getKey(), loadedBitmap, mOrientation);
        }
    }

    protected boolean isGif() throws FileNotFoundException {
        return ImageUtils.isGif(getInputStreamForResource());
    }

    /**
     * The internal routine for loading the image. The caller may optionally provide the width
     * and height of the source image if known so that we don't need to manually decode those.
     */
    protected Bitmap loadBitmapInternal() throws IOException {

        final boolean unknownSize = mDescriptor.sourceWidth == UNSPECIFIED_SIZE ||
                mDescriptor.sourceHeight == UNSPECIFIED_SIZE;

        // If the ImageRequest has a Bitmap object rather than a stream, there's little to do here
        if (hasBitmapObject()) {
            final Bitmap bitmap = getBitmapForResource();
            if (bitmap != null && unknownSize) {
                mDescriptor.updateSourceDimensions(bitmap.getWidth(), bitmap.getHeight());
            }
            return bitmap;
        }

        mOrientation = ImageUtils.getOrientation(getInputStreamForResource());

        final BitmapFactory.Options options = PoolableImageCache.getBitmapOptionsForPool(
                false /* scaled */, 0 /* inputDensity */, 0 /* targetDensity */);
        // First, check dimensions of the bitmap if not already known.
        if (unknownSize) {
            final InputStream inputStream = getInputStreamForResource();
            if (inputStream != null) {
                try {
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeStream(inputStream, null, options);
                    // This is called when dimensions of image were unknown to allow db update
                    if (ExifInterface.getOrientationParams(mOrientation).invertDimensions) {
                        mDescriptor.updateSourceDimensions(options.outHeight, options.outWidth);
                    } else {
                        mDescriptor.updateSourceDimensions(options.outWidth, options.outHeight);
                    }
                } finally {
                    inputStream.close();
                }
            } else {
                throw new FileNotFoundException();
            }
        } else {
            options.outWidth = mDescriptor.sourceWidth;
            options.outHeight = mDescriptor.sourceHeight;
        }

        // Calculate inSampleSize
        options.inSampleSize = ImageUtils.get().calculateInSampleSize(options,
                mDescriptor.desiredWidth, mDescriptor.desiredHeight);
        Assert.isTrue(options.inSampleSize > 0);

        // Reopen the input stream and actually decode the bitmap. The initial
        // BitmapFactory.decodeStream() reads the header portion of the bitmap stream and leave
        // the input stream at the last read position. Since this input stream doesn't support
        // mark() and reset(), the only viable way to reload the input stream is to re-open it.
        // Alternatively, we could decode the bitmap into a byte array first and act on the byte
        // array, but that also means the entire bitmap (for example a 10MB image from the gallery)
        // without downsampling will have to be loaded into memory up front, which we don't want
        // as it gives a much bigger possibility of OOM when handling big images. Therefore, the
        // solution here is to close and reopen the bitmap input stream.
        // For inline images the size is cached in DB and this hit is only taken once per image
        final InputStream inputStream = getInputStreamForResource();
        if (inputStream != null) {
            try {
                options.inJustDecodeBounds = false;

                // Actually decode the bitmap, optionally using the bitmap pool.
                final ReusableImageResourcePool bitmapPool = getBitmapPool();
                if (bitmapPool == null) {
                    return BitmapFactory.decodeStream(inputStream, null, options);
                } else {
                    final int sampledWidth = (options.outWidth + options.inSampleSize - 1) /
                            options.inSampleSize;
                    final int sampledHeight = (options.outHeight + options.inSampleSize - 1) /
                            options.inSampleSize;
                    return bitmapPool.decodeSampledBitmapFromInputStream(
                            inputStream, options, sampledWidth, sampledHeight);
                }
            } finally {
                inputStream.close();
            }
        } else {
            throw new FileNotFoundException();
        }
    }

    private ImageResource postProcessOnBitmapResourceLoaded(final ImageResource loadedResource) {
        if (mDescriptor.cropToCircle && loadedResource instanceof DecodedImageResource) {
            final int width = mDescriptor.desiredWidth;
            final int height = mDescriptor.desiredHeight;
            final Bitmap sourceBitmap = loadedResource.getBitmap();
            final Bitmap targetBitmap = getBitmapPool().createOrReuseBitmap(width, height);
            final RectF dest = new RectF(0, 0, width, height);
            final RectF source = new RectF(0, 0, sourceBitmap.getWidth(), sourceBitmap.getHeight());
            final int backgroundColor = mDescriptor.circleBackgroundColor;
            final int strokeColor = mDescriptor.circleStrokeColor;
            ImageUtils.drawBitmapWithCircleOnCanvas(sourceBitmap, new Canvas(targetBitmap), source,
                    dest, null, backgroundColor == 0 ? false : true /* fillBackground */,
                            backgroundColor, strokeColor);
            return new DecodedImageResource(getKey(), targetBitmap,
                    loadedResource.getOrientation());
        }
        return loadedResource;
    }

    /**
     * Returns the bitmap pool for this image request.
     */
    protected ReusableImageResourcePool getBitmapPool() {
        return MediaCacheManager.get().getOrCreateBitmapPoolForCache(getCacheId());
    }

    @SuppressWarnings("unchecked")
    @Override
    public MediaCache<ImageResource> getMediaCache() {
        return (MediaCache<ImageResource>) MediaCacheManager.get().getOrCreateMediaCacheById(
                getCacheId());
    }

    /**
     * Returns the cache id. Subclasses may override this to use a different cache.
     */
    @Override
    public int getCacheId() {
        return BugleMediaCacheManager.DEFAULT_IMAGE_CACHE;
    }
}
