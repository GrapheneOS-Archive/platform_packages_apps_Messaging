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

package com.android.messaging.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import androidx.annotation.Nullable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.widget.ImageView;

import com.android.messaging.R;
import com.android.messaging.datamodel.binding.Binding;
import com.android.messaging.datamodel.binding.BindingBase;
import com.android.messaging.datamodel.media.BindableMediaRequest;
import com.android.messaging.datamodel.media.GifImageResource;
import com.android.messaging.datamodel.media.ImageRequest;
import com.android.messaging.datamodel.media.ImageRequestDescriptor;
import com.android.messaging.datamodel.media.ImageResource;
import com.android.messaging.datamodel.media.MediaRequest;
import com.android.messaging.datamodel.media.MediaResourceManager;
import com.android.messaging.datamodel.media.MediaResourceManager.MediaResourceLoadListener;
import com.android.messaging.util.Assert;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.ThreadUtil;
import com.android.messaging.util.UiUtils;
import com.bumptech.glide.load.resource.gif.GifDrawable;
import com.google.common.annotations.VisibleForTesting;

import java.util.HashSet;

/**
 * An ImageView used to asynchronously request an image from MediaResourceManager and render it.
 */
public class AsyncImageView extends ImageView implements MediaResourceLoadListener<ImageResource> {
    private static final String TAG = LogUtil.BUGLE_DATAMODEL_TAG;
    // 100ms delay before disposing the image in case the AsyncImageView is re-added to the UI
    private static final int DISPOSE_IMAGE_DELAY = 100;

    // AsyncImageView has a 1-1 binding relationship with an ImageRequest instance that requests
    // the image from the MediaResourceManager. Since the request is done asynchronously, we
    // want to make sure the image view is always bound to the latest image request that it
    // issues, so that when the image is loaded, the ImageRequest (which extends BindableData)
    // will be able to figure out whether the binding is still valid and whether the loaded image
    // should be delivered to the AsyncImageView via onMediaResourceLoaded() callback.
    @VisibleForTesting
    public final Binding<BindableMediaRequest<ImageResource>> mImageRequestBinding;

    /** True if we want the image to fade in when it loads */
    private boolean mFadeIn;

    /** True if we want the image to reveal (scale) when it loads. When set to true, this
     * will take precedence over {@link #mFadeIn} */
    private final boolean mReveal;

    // The corner radius for drawing rounded corners around bitmap. The default value is zero
    // (no rounded corners)
    private final int mCornerRadius;
    private final Path mRoundedCornerClipPath;
    private int mClipPathWidth;
    private int mClipPathHeight;

    // A placeholder drawable that takes the spot of the image when it's loading. The default
    // setting is null (no placeholder).
    private final Drawable mPlaceholderDrawable;
    protected ImageResource mImageResource;
    private final Runnable mDisposeRunnable = new Runnable() {
        @Override
        public void run() {
            if (mImageRequestBinding.isBound()) {
                mDetachedRequestDescriptor = (ImageRequestDescriptor)
                        mImageRequestBinding.getData().getDescriptor();
            }
            unbindView();
            releaseImageResource();
        }
    };

    private AsyncImageViewDelayLoader mDelayLoader;
    private ImageRequestDescriptor mDetachedRequestDescriptor;

    public AsyncImageView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        mImageRequestBinding = BindingBase.createBinding(this);
        final TypedArray attr = context.obtainStyledAttributes(attrs, R.styleable.AsyncImageView,
                0, 0);
        mFadeIn = attr.getBoolean(R.styleable.AsyncImageView_fadeIn, true);
        mReveal = attr.getBoolean(R.styleable.AsyncImageView_reveal, false);
        mPlaceholderDrawable = attr.getDrawable(R.styleable.AsyncImageView_placeholderDrawable);
        mCornerRadius = attr.getDimensionPixelSize(R.styleable.AsyncImageView_cornerRadius, 0);
        mRoundedCornerClipPath = new Path();

        attr.recycle();
    }

    /**
     * The main entrypoint for AsyncImageView to load image resource given an ImageRequestDescriptor
     * @param descriptor the request descriptor, or null if no image should be displayed
     */
    public void setImageResourceId(@Nullable final ImageRequestDescriptor descriptor) {
        final String requestKey = (descriptor == null) ? null : descriptor.getKey();
        if (mImageRequestBinding.isBound()) {
            if (TextUtils.equals(mImageRequestBinding.getData().getKey(), requestKey)) {
                // Don't re-request the bitmap if the new request is for the same resource.
                return;
            }
            unbindView();
        } else {
            mDetachedRequestDescriptor = null;
        }
        setImage(null);
        resetTransientViewStates();
        if (!TextUtils.isEmpty(requestKey)) {
            maybeSetupPlaceholderDrawable(descriptor);
            final BindableMediaRequest<ImageResource> imageRequest =
                    descriptor.buildAsyncMediaRequest(getContext(), this);
            requestImage(imageRequest);
        }
    }

    /**
     * Sets a delay loader that centrally manages image request delay loading logic.
     */
    public void setDelayLoader(final AsyncImageViewDelayLoader delayLoader) {
        Assert.isTrue(mDelayLoader == null);
        mDelayLoader = delayLoader;
    }

    /**
     * Called by the delay loader when we can resume image loading.
     */
    public void resumeLoading() {
        Assert.notNull(mDelayLoader);
        Assert.isTrue(mImageRequestBinding.isBound());
        MediaResourceManager.get().requestMediaResourceAsync(mImageRequestBinding.getData());
    }

    /**
     * Setup the placeholder drawable if:
     * 1. There's an image to be loaded AND
     * 2. We are given a placeholder drawable AND
     * 3. The descriptor provided us with source width and height.
     */
    private void maybeSetupPlaceholderDrawable(final ImageRequestDescriptor descriptor) {
        if (!TextUtils.isEmpty(descriptor.getKey()) && mPlaceholderDrawable != null) {
            if (descriptor.sourceWidth != ImageRequest.UNSPECIFIED_SIZE &&
                descriptor.sourceHeight != ImageRequest.UNSPECIFIED_SIZE) {
                // Set a transparent inset drawable to the foreground so it will mimick the final
                // size of the image, and use the background to show the actual placeholder
                // drawable.
                setImageDrawable(PlaceholderInsetDrawable.fromDrawable(
                        new ColorDrawable(Color.TRANSPARENT),
                        descriptor.sourceWidth, descriptor.sourceHeight));
            }
            setBackground(mPlaceholderDrawable);
        }
    }

    protected void setImage(final ImageResource resource) {
        setImage(resource, false /* isCached */);
    }

    protected void setImage(final ImageResource resource, final boolean isCached) {
        // Switch reference to the new ImageResource. Make sure we release the current
        // resource and addRef() on the new resource so that the underlying bitmaps don't
        // get leaked or get recycled by the bitmap cache.
        releaseImageResource();
        // Ensure that any pending dispose runnables get removed.
        ThreadUtil.getMainThreadHandler().removeCallbacks(mDisposeRunnable);
        // The drawable may require work to get if its a static object so try to only make this call
        // once.
        final Drawable drawable = (resource != null) ? resource.getDrawable(getResources()) : null;
        if (drawable != null) {
            mImageResource = resource;
            mImageResource.addRef();
            setImageDrawable(drawable);
            if (drawable instanceof GifDrawable) {
                ((GifDrawable) drawable).start();
            }

            if (getVisibility() == VISIBLE) {
                if (mReveal) {
                    setVisibility(INVISIBLE);
                    UiUtils.revealOrHideViewWithAnimation(this, VISIBLE, null);
                } else if (mFadeIn && !isCached) {
                    // Hide initially to avoid flash.
                    setAlpha(0F);
                    animate().alpha(1F).start();
                }
            }

            if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
                if (mImageResource instanceof GifImageResource) {
                    LogUtil.v(TAG, "setImage size unknown -- it's a GIF");
                } else {
                    LogUtil.v(TAG, "setImage size: " + mImageResource.getMediaSize() +
                            " width: " + mImageResource.getBitmap().getWidth() +
                            " heigh: " + mImageResource.getBitmap().getHeight());
                }
            }
        }
        invalidate();
    }

    private void requestImage(final BindableMediaRequest<ImageResource> request) {
        mImageRequestBinding.bind(request);
        if (mDelayLoader == null || !mDelayLoader.isDelayLoadingImage()) {
            MediaResourceManager.get().requestMediaResourceAsync(request);
        } else {
            mDelayLoader.registerView(this);
        }
    }

    @Override
    public void onMediaResourceLoaded(final MediaRequest<ImageResource> request,
            final ImageResource resource, final boolean isCached) {
        if (mImageResource != resource) {
            setImage(resource, isCached);
        }
    }

    @Override
    public void onMediaResourceLoadError(
            final MediaRequest<ImageResource> request, final Exception exception) {
        // Media load failed, unbind and reset bitmap to default.
        unbindView();
        setImage(null);
    }

    private void releaseImageResource() {
        final Drawable drawable = getDrawable();
        if (drawable instanceof GifDrawable) {
            ((GifDrawable) drawable).stop();
        }
        if (mImageResource != null) {
            mImageResource.release();
            mImageResource = null;
        }
        setImageDrawable(null);
        setBackground(null);

        // Must be recycled after the GifDrawable is removed from the ImageView
        // Otherwise, we'll get an exception
        if (drawable instanceof GifDrawable) {
            ((GifDrawable) drawable).recycle();
        }
    }

    /**
     * Resets transient view states (eg. alpha, animations) before rebinding/reusing the view.
     */
    private void resetTransientViewStates() {
        clearAnimation();
        setAlpha(1F);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // If it was recently removed, then cancel disposing, we're still using it.
        ThreadUtil.getMainThreadHandler().removeCallbacks(mDisposeRunnable);

        // When the image view gets detached and immediately re-attached, any fade-in animation
        // will be terminated, leaving the view in a semi-transparent state. Make sure we restore
        // alpha when the view is re-attached.
        if (mFadeIn) {
            setAlpha(1F);
        }

        // Check whether we are in a simple reuse scenario: detached from window, and reattached
        // later without rebinding. This may be done by containers such as the RecyclerView to
        // reuse the views. In this case, we would like to rebind the original image request.
        if (!mImageRequestBinding.isBound() && mDetachedRequestDescriptor != null) {
            setImageResourceId(mDetachedRequestDescriptor);
        }
        mDetachedRequestDescriptor = null;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // Dispose the bitmap, but if an AysncImageView is removed from the window, then quickly
        // re-added, we shouldn't dispose, so wait a short time before disposing
        ThreadUtil.getMainThreadHandler().postDelayed(mDisposeRunnable, DISPOSE_IMAGE_DELAY);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        // The base implementation does not honor the minimum sizes. We try to to honor it here.

        final int measuredWidth = getMeasuredWidth();
        final int measuredHeight = getMeasuredHeight();
        if (measuredWidth >= getMinimumWidth() || measuredHeight >= getMinimumHeight()) {
            // We are ok if either of the minimum sizes is honored. Note that satisfying both the
            // sizes may not be possible, depending on the aspect ratio of the image and whether
            // a maximum size has been specified. This implementation only tries to handle the case
            // where both the minimum sizes are not being satisfied.
            return;
        }

        if (!getAdjustViewBounds()) {
            // The base implementation is reasonable in this case. If the view bounds cannot be
            // changed, it is not possible to satisfy the minimum sizes anyway.
            return;
        }

        final int widthSpecMode = MeasureSpec.getMode(widthMeasureSpec);
        final int heightSpecMode = MeasureSpec.getMode(heightMeasureSpec);
        if (widthSpecMode == MeasureSpec.EXACTLY && heightSpecMode == MeasureSpec.EXACTLY) {
            // The base implementation is reasonable in this case.
            return;
        }

        int width = measuredWidth;
        int height = measuredHeight;
        // Get the minimum sizes that will honor other constraints as well.
        final int minimumWidth = resolveSize(
                getMinimumWidth(), getMaxWidth(), widthMeasureSpec);
        final int minimumHeight = resolveSize(
                getMinimumHeight(), getMaxHeight(), heightMeasureSpec);
        final float aspectRatio = measuredWidth / (float) measuredHeight;
        if (aspectRatio == 0) {
            // If the image is (close to) infinitely high, there is not much we can do.
            return;
        }

        if (width < minimumWidth) {
            height = resolveSize((int) (minimumWidth / aspectRatio),
                    getMaxHeight(), heightMeasureSpec);
            width = (int) (height * aspectRatio);
        }

        if (height < minimumHeight) {
            width = resolveSize((int) (minimumHeight * aspectRatio),
                    getMaxWidth(), widthMeasureSpec);
            height = (int) (width / aspectRatio);
        }

        setMeasuredDimension(width, height);
    }

    private static int resolveSize(int desiredSize, int maxSize, int measureSpec) {
        final int specMode = MeasureSpec.getMode(measureSpec);
        final int specSize =  MeasureSpec.getSize(measureSpec);
        switch(specMode) {
            case MeasureSpec.UNSPECIFIED:
                return Math.min(desiredSize, maxSize);

            case MeasureSpec.AT_MOST:
                return Math.min(Math.min(desiredSize, specSize), maxSize);

            default:
                Assert.fail("Unreachable");
                return specSize;
        }
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        if (mCornerRadius > 0) {
            final int currentWidth = this.getWidth();
            final int currentHeight = this.getHeight();
            if (mClipPathWidth != currentWidth || mClipPathHeight != currentHeight) {
                final RectF rect = new RectF(0, 0, currentWidth, currentHeight);
                mRoundedCornerClipPath.reset();
                mRoundedCornerClipPath.addRoundRect(rect, mCornerRadius, mCornerRadius,
                        Path.Direction.CW);
                mClipPathWidth = currentWidth;
                mClipPathHeight = currentHeight;
            }

            final int saveCount = canvas.getSaveCount();
            canvas.save();
            canvas.clipPath(mRoundedCornerClipPath);
            super.onDraw(canvas);
            canvas.restoreToCount(saveCount);
        } else {
            super.onDraw(canvas);
        }
    }

    private void unbindView() {
        if (mImageRequestBinding.isBound()) {
            mImageRequestBinding.unbind();
            if (mDelayLoader != null) {
                mDelayLoader.unregisterView(this);
            }
        }
    }

    /**
     * As a performance optimization, the consumer of the AsyncImageView may opt to delay loading
     * the image when it's busy doing other things (such as when a list view is scrolling). In
     * order to do this, the consumer can create a new AsyncImageViewDelayLoader instance to be
     * shared among all relevant AsyncImageViews (through setDelayLoader() method), and call
     * onStartDelayLoading() and onStopDelayLoading() to start and stop delay loading, respectively.
     */
    public static class AsyncImageViewDelayLoader {
        private boolean mShouldDelayLoad;
        private final HashSet<AsyncImageView> mAttachedViews;

        public AsyncImageViewDelayLoader() {
            mAttachedViews = new HashSet<AsyncImageView>();
        }

        private void registerView(final AsyncImageView view) {
            mAttachedViews.add(view);
        }

        private void unregisterView(final AsyncImageView view) {
            mAttachedViews.remove(view);
        }

        public boolean isDelayLoadingImage() {
            return mShouldDelayLoad;
        }

        /**
         * Called by the consumer of this view to delay loading images
         */
        public void onDelayLoading() {
            // Don't need to explicitly tell the AsyncImageView to stop loading since
            // ImageRequests are not cancellable.
            mShouldDelayLoad = true;
        }

        /**
         * Called by the consumer of this view to resume loading images
         */
        public void onResumeLoading() {
            if (mShouldDelayLoad) {
                mShouldDelayLoad = false;

                // Notify all attached views to resume loading.
                for (final AsyncImageView view : mAttachedViews) {
                    view.resumeLoading();
                }
                mAttachedViews.clear();
            }
        }
    }
}
