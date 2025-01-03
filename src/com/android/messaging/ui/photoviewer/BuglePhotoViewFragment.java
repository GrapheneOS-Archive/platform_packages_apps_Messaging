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

import android.content.Intent;
import android.graphics.drawable.Drawable;
import androidx.loader.content.Loader;

import com.android.ex.photo.PhotoViewCallbacks;
import com.android.ex.photo.fragments.PhotoViewFragment;
import com.android.ex.photo.loaders.PhotoBitmapLoaderInterface.BitmapResult;
import com.bumptech.glide.load.resource.gif.GifDrawable;

public class BuglePhotoViewFragment extends PhotoViewFragment {

    /** Public no-arg constructor for allowing the framework to handle orientation changes */
    public BuglePhotoViewFragment() {
        // Do nothing.
    }

    public static PhotoViewFragment newInstance(Intent intent, int position,
            boolean onlyShowSpinner) {
        final PhotoViewFragment f = new BuglePhotoViewFragment();
        initializeArguments(intent, position, onlyShowSpinner, f);
        return f;
    }

    @Override
    public void onLoadFinished(Loader<BitmapResult> loader, BitmapResult result) {
        super.onLoadFinished(loader, result);
        // Need to check for the first time when we load the photos
        if (PhotoViewCallbacks.BITMAP_LOADER_PHOTO == loader.getId()
                && result.status == BitmapResult.STATUS_SUCCESS
                && mCallback.isFragmentActive(this)) {
            startGif();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        startGif();
    }

    @Override
    public void onPause() {
        stopGif();
        super.onPause();
    }

    @Override
    public void onViewActivated() {
        super.onViewActivated();
        startGif();
    }

    @Override
    public void resetViews() {
        super.resetViews();
        stopGif();
    }

    private void stopGif() {
        final Drawable drawable = getDrawable();
        if (drawable != null && drawable instanceof GifDrawable) {
            ((GifDrawable) drawable).stop();
        }
    }

    private void startGif() {
        final Drawable drawable = getDrawable();
        if (drawable != null && drawable instanceof GifDrawable) {
            ((GifDrawable) drawable).start();
        }
    }
}
