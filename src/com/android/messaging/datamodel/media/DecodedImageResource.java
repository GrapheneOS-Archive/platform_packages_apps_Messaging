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

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;

import com.android.messaging.ui.OrientedBitmapDrawable;
import com.android.messaging.util.Assert;
import com.android.messaging.util.Assert.DoesNotRunOnMainThread;
import com.android.messaging.util.ImageUtils;
import com.android.messaging.util.LogUtil;

import java.util.List;


/**
 * Container class for holding a bitmap resource used by the MediaResourceManager. This resource
 * can both be cached (albeit not very storage-efficiently) and directly used by the UI.
 */
public class DecodedImageResource extends ImageResource {
    private static final int BITMAP_QUALITY = 100;
    private static final int COMPRESS_QUALITY = 50;

    private Bitmap mBitmap;
    private final int mOrientation;
    private boolean mCacheable = true;

    public DecodedImageResource(final String key, final Bitmap bitmap, int orientation) {
        super(key, orientation);
        mBitmap = bitmap;
        mOrientation = orientation;
    }

    /**
     * Gets the contained bitmap.
     */
    @Override
    public Bitmap getBitmap() {
        acquireLock();
        try {
            return mBitmap;
        } finally {
            releaseLock();
        }
    }

    /**
     * Attempt to reuse the bitmap in the image resource and repurpose it for something else.
     * After this, the image resource will relinquish ownership on the bitmap resource so that
     * it doesn't try to recycle it when getting closed.
     */
    @Override
    public Bitmap reuseBitmap() {
        acquireLock();
        try {
            assertSingularRefCount();
            final Bitmap retBitmap = mBitmap;
            mBitmap = null;
            return retBitmap;
        } finally {
            releaseLock();
        }
    }

    @Override
    public boolean supportsBitmapReuse() {
        return true;
    }

    @Override
    public byte[] getBytes() {
        acquireLock();
        try {
            return ImageUtils.bitmapToBytes(mBitmap, BITMAP_QUALITY);
        } catch (final Exception e) {
            LogUtil.e(LogUtil.BUGLE_TAG, "Error trying to get the bitmap bytes " + e);
        } finally {
            releaseLock();
        }
        return null;
    }

    /**
     * Gets the orientation of the image as one of the ExifInterface.ORIENTATION_* constants
     */
    @Override
    public int getOrientation() {
        return mOrientation;
    }

    @Override
    public int getMediaSize() {
        acquireLock();
        try {
            Assert.notNull(mBitmap);
            return mBitmap.getAllocationByteCount();
        } finally {
            releaseLock();
        }
    }

    @Override
    protected void close() {
        acquireLock();
        try {
            if (mBitmap != null) {
                mBitmap.recycle();
                mBitmap = null;
            }
        } finally {
            releaseLock();
        }
    }

    @Override
    public Drawable getDrawable(Resources resources) {
        acquireLock();
        try {
            Assert.notNull(mBitmap);
            return OrientedBitmapDrawable.create(getOrientation(), resources, mBitmap);
        } finally {
            releaseLock();
        }
    }

    @Override
    boolean isCacheable() {
        return mCacheable;
    }

    public void setCacheable(final boolean cacheable) {
        mCacheable = cacheable;
    }

    @SuppressWarnings("unchecked")
    @Override
    MediaRequest<? extends RefCountedMediaResource> getMediaEncodingRequest(
            final MediaRequest<? extends RefCountedMediaResource> originalRequest) {
        Assert.isFalse(isEncoded());
        if (getBitmap().hasAlpha()) {
            // We can't compress images with alpha, as JPEG encoding doesn't support this.
            return null;
        }
        return new EncodeImageRequest((MediaRequest<ImageResource>) originalRequest);
    }

    /**
     * A MediaRequest that encodes the contained image resource.
     */
    private class EncodeImageRequest implements MediaRequest<ImageResource> {
        private final MediaRequest<ImageResource> mOriginalImageRequest;

        public EncodeImageRequest(MediaRequest<ImageResource> originalImageRequest) {
            mOriginalImageRequest = originalImageRequest;
            // Hold a ref onto the encoded resource before the request finishes.
            DecodedImageResource.this.addRef();
        }

        @Override
        public String getKey() {
            return DecodedImageResource.this.getKey();
        }

        @Override
        @DoesNotRunOnMainThread
        public ImageResource loadMediaBlocking(List<MediaRequest<ImageResource>> chainedRequests)
                throws Exception {
            Assert.isNotMainThread();
            acquireLock();
            Bitmap scaledBitmap = null;
            try {
                Bitmap bitmap = getBitmap();
                Assert.isFalse(bitmap.hasAlpha());
                final int bitmapWidth = bitmap.getWidth();
                final int bitmapHeight = bitmap.getHeight();
                // The original bitmap was loaded using sub-sampling which was fast in terms of
                // loading speed, but not optimized for caching, encoding and rendering (since
                // bitmap resizing to fit the UI image views happens on the UI thread and should
                // be avoided if possible). Therefore, try to resize the bitmap to the exact desired
                // size before compressing it.
                if (bitmapWidth > 0 && bitmapHeight > 0 &&
                        mOriginalImageRequest instanceof ImageRequest<?>) {
                    final ImageRequestDescriptor descriptor =
                            ((ImageRequest<?>) mOriginalImageRequest).getDescriptor();
                    final float targetScale = Math.max(
                            (float) descriptor.desiredWidth / bitmapWidth,
                            (float) descriptor.desiredHeight / bitmapHeight);
                    final int targetWidth = (int) (bitmapWidth * targetScale);
                    final int targetHeight = (int) (bitmapHeight * targetScale);
                    // Only try to scale down the image to the desired size.
                    if (targetScale < 1.0f && targetWidth > 0 && targetHeight > 0 &&
                            targetWidth != bitmapWidth && targetHeight != bitmapHeight) {
                        scaledBitmap = bitmap =
                                Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, false);
                    }
                }
                byte[] encodedBytes = ImageUtils.bitmapToBytes(bitmap, COMPRESS_QUALITY);
                return new EncodedImageResource(getKey(), encodedBytes, getOrientation());
            } catch (Exception ex) {
                // Something went wrong during bitmap compression, fall back to just using the
                // original bitmap.
                LogUtil.e(LogUtil.BUGLE_IMAGE_TAG, "Error compressing bitmap", ex);
                return DecodedImageResource.this;
            } finally {
                if (scaledBitmap != null && scaledBitmap != getBitmap()) {
                    scaledBitmap.recycle();
                    scaledBitmap = null;
                }
                releaseLock();
                release();
            }
        }

        @Override
        public MediaCache<ImageResource> getMediaCache() {
            return mOriginalImageRequest.getMediaCache();
        }

        @Override
        public int getCacheId() {
            return mOriginalImageRequest.getCacheId();
        }

        @Override
        public int getRequestType() {
            return REQUEST_ENCODE_MEDIA;
        }

        @Override
        public MediaRequestDescriptor<ImageResource> getDescriptor() {
            return mOriginalImageRequest.getDescriptor();
        }
    }
}
