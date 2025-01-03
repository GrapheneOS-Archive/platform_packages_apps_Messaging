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
package com.android.messaging.ui.photoviewer;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import androidx.loader.content.AsyncTaskLoader;

import com.android.ex.photo.PhotoViewController;
import com.android.ex.photo.loaders.PhotoBitmapLoaderInterface;
import com.android.ex.photo.loaders.PhotoBitmapLoaderInterface.BitmapResult;
import com.android.messaging.datamodel.media.ImageRequestDescriptor;
import com.android.messaging.datamodel.media.ImageResource;
import com.android.messaging.datamodel.media.MediaRequest;
import com.android.messaging.datamodel.media.MediaResourceManager;
import com.android.messaging.datamodel.media.UriImageRequestDescriptor;
import com.android.messaging.util.ImageUtils;
import com.bumptech.glide.load.resource.gif.GifDrawable;

/**
 * Loader for the bitmap of a photo.
 */
public class BuglePhotoBitmapLoader extends AsyncTaskLoader<BitmapResult>
        implements PhotoBitmapLoaderInterface {
    private String mPhotoUri;
    private ImageResource mImageResource;
    // The drawable that is currently "in use" and being presented to the user. This drawable
    // should never exist without the image resource backing it.
    private Drawable mDrawable;

    public BuglePhotoBitmapLoader(Context context, String photoUri) {
        super(context);
        mPhotoUri = photoUri;
    }

    @Override
    public void setPhotoUri(String photoUri) {
        mPhotoUri = photoUri;
    }

    @Override
    public BitmapResult loadInBackground() {
        final BitmapResult result = new BitmapResult();
        final Context context = getContext();
        if (context != null && mPhotoUri != null) {
            final ImageRequestDescriptor descriptor =
                    new UriImageRequestDescriptor(Uri.parse(mPhotoUri),
                            PhotoViewController.sMaxPhotoSize, PhotoViewController.sMaxPhotoSize,
                            true /* allowCompression */, false /* isStatic */,
                            false /* cropToCircle */,
                            ImageUtils.DEFAULT_CIRCLE_BACKGROUND_COLOR /* circleBackgroundColor */,
                            ImageUtils.DEFAULT_CIRCLE_STROKE_COLOR /* circleStrokeColor */);
            final MediaRequest<ImageResource> imageRequest =
                    descriptor.buildSyncMediaRequest(context);
            final ImageResource imageResource =
                    MediaResourceManager.get().requestMediaResourceSync(imageRequest);
            if (imageResource != null) {
                setImageResource(imageResource);
                result.status = BitmapResult.STATUS_SUCCESS;
                result.drawable = mImageResource.getDrawable(context.getResources());
            } else {
                releaseImageResource();
                result.status = BitmapResult.STATUS_EXCEPTION;
            }
        } else {
            result.status = BitmapResult.STATUS_EXCEPTION;
        }
        return result;
    }

    /**
     * Called when there is new data to deliver to the client. The super class will take care of
     * delivering it; the implementation here just adds a little more logic.
     */
    @Override
    public void deliverResult(BitmapResult result) {
        final Drawable drawable = result != null ? result.drawable : null;
        if (isReset()) {
            // An async query came in while the loader is stopped. We don't need the result.
            releaseDrawable(drawable);
            return;
        }

        // We are now going to display this drawable so set to mDrawable
        mDrawable = drawable;

        if (isStarted()) {
            // If the Loader is currently started, we can immediately deliver its results.
            super.deliverResult(result);
        }
    }

    /**
     * Handles a request to start the Loader.
     */
    @Override
    protected void onStartLoading() {
        if (mDrawable != null) {
            // If we currently have a result available, deliver it
            // immediately.
            final BitmapResult result = new BitmapResult();
            result.status = BitmapResult.STATUS_SUCCESS;
            result.drawable = mDrawable;
            deliverResult(result);
        }

        if (takeContentChanged() || (mImageResource == null)) {
            // If the data has changed since the last time it was loaded
            // or is not currently available, start a load.
            forceLoad();
        }
    }

    /**
     * Handles a request to stop the Loader.
     */
    @Override
    protected void onStopLoading() {
        // Attempt to cancel the current load task if possible.
        cancelLoad();
    }

    /**
     * Handles a request to cancel a load.
     */
    @Override
    public void onCanceled(BitmapResult result) {
        super.onCanceled(result);

        // At this point we can release the resources associated with 'drawable' if needed.
        if (result != null) {
            releaseDrawable(result.drawable);
        }
    }

    /**
     * Handles a request to completely reset the Loader.
     */
    @Override
    protected void onReset() {
        super.onReset();

        // Ensure the loader is stopped
        onStopLoading();

        releaseImageResource();
    }

    private void releaseDrawable(Drawable drawable) {
        if (drawable != null && drawable instanceof GifDrawable) {
            ((GifDrawable) drawable).recycle();
        }

    }

    private void setImageResource(final ImageResource resource) {
        if (mImageResource != resource) {
            // Clear out any information for what is currently used
            releaseImageResource();
            mImageResource = resource;
            // No need to add ref since a ref is already reserved as a result of
            // requestMediaResourceSync.
        }
    }

    private void releaseImageResource() {
        // If we are getting rid of the imageResource backing the drawable, we must also
        // destroy the drawable before releasing it.
        releaseDrawable(mDrawable);
        mDrawable = null;

        if (mImageResource != null) {
            mImageResource.release();
        }
        mImageResource = null;
    }
}