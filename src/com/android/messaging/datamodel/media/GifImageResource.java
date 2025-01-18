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
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.media.ExifInterface;

import com.android.messaging.util.Assert;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.gif.GifDrawable;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutionException;

public class GifImageResource extends ImageResource {
    private GifDrawable mGifDrawable;

    private GifImageResource(String key, GifDrawable gifDrawable) {
        // GIF does not support exif tags
        super(key, ExifInterface.ORIENTATION_NORMAL);
        mGifDrawable = gifDrawable;
    }

    public static GifImageResource createGifImageResource(Context context, String key, InputStream inputStream) throws IOException {
        byte[] gifData;
        try (inputStream) {
            gifData = inputStream.readAllBytes();
        }
        GifDrawable gifDrawable;
        try {
            gifDrawable = Glide.with(context).asGif().load(gifData).submit().get();
        } catch (ExecutionException | InterruptedException e) {
            throw new IOException(e);
        }
        return new GifImageResource(key, gifDrawable);
    }

    @Override
    public Drawable getDrawable(Resources resources) {
        return mGifDrawable.getCurrent();
    }

    @Override
    public Bitmap getBitmap() {
        Assert.fail("GetBitmap() should never be called on a gif.");
        return null;
    }

    @Override
    public byte[] getBytes() {
        Assert.fail("GetBytes() should never be called on a gif.");
        return null;
    }

    @Override
    public Bitmap reuseBitmap() {
        return null;
    }

    @Override
    public boolean supportsBitmapReuse() {
        // FrameSequenceDrawable a.) takes two bitmaps and thus does not fit into the current
        // bitmap pool architecture b.) will rarely use bitmaps from one FrameSequenceDrawable to
        // the next that are the same sizes since they are used by attachments.
        return false;
    }

    @Override
    public int getMediaSize() {
        Assert.fail("GifImageResource should not be used by a media cache");
        // Only used by the media cache, which this does not use.
        return 0;
    }

    @Override
    public boolean isCacheable() {
        return false;
    }

    @Override
    protected void close() {
        acquireLock();
        try {
            mGifDrawable = null;
        } finally {
            releaseLock();
        }
    }

}
