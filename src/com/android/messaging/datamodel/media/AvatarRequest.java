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
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.media.ExifInterface;
import android.net.Uri;

import com.android.messaging.R;
import com.android.messaging.util.Assert;
import com.android.messaging.util.AvatarUriUtil;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.UriUtil;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public class AvatarRequest extends UriImageRequest<AvatarRequestDescriptor> {
    private static Bitmap sDefaultPersonBitmap;
    private static Bitmap sDefaultPersonBitmapLarge;

    public AvatarRequest(final Context context,
            final AvatarRequestDescriptor descriptor) {
        super(context, descriptor);
    }

    @Override
    protected InputStream getInputStreamForResource() throws FileNotFoundException {
        if (UriUtil.isLocalResourceUri(mDescriptor.uri)) {
            return super.getInputStreamForResource();
        } else {
            final Uri primaryUri = AvatarUriUtil.getPrimaryUri(mDescriptor.uri);
            Assert.isTrue(UriUtil.isLocalResourceUri(primaryUri));
            return mContext.getContentResolver().openInputStream(primaryUri);
        }
    }

    /**
     * We can load multiple types of images for avatars depending on the uri. The uri should be
     * built by {@link com.android.messaging.util.AvatarUriUtil} which will decide on
     * what uri to build based on the available profile photo and name. Here we will check if the
     * image is a local resource (ie profile photo uri), if the resource isn't a local one we will
     * generate a tile with the first letter of the name.
     */
    @Override
    protected ImageResource loadMediaInternal(List<MediaRequest<ImageResource>> chainedTasks)
            throws IOException {
        Assert.isNotMainThread();
        String avatarType = AvatarUriUtil.getAvatarType(mDescriptor.uri);
        Bitmap bitmap = null;
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        final boolean isLocalResourceUri = UriUtil.isLocalResourceUri(mDescriptor.uri) ||
                AvatarUriUtil.TYPE_LOCAL_RESOURCE_URI.equals(avatarType);
        if (isLocalResourceUri) {
            try {
                ImageResource imageResource = super.loadMediaInternal(chainedTasks);
                bitmap = imageResource.getBitmap();
                orientation = imageResource.mOrientation;
            } catch (Exception ex) {
                // If we encountered any exceptions trying to load the local avatar resource,
                // fall back to generated avatar.
                LogUtil.w(LogUtil.BUGLE_IMAGE_TAG, "AvatarRequest: failed to load local avatar " +
                        "resource, switching to fallback rendering", ex);
            }
        }

        final int width = mDescriptor.desiredWidth;
        final int height = mDescriptor.desiredHeight;
        // Check to see if we already got the bitmap. If not get a fallback avatar
        if (bitmap == null) {
            Uri generatedUri = mDescriptor.uri;
            if (isLocalResourceUri) {
                // If we are here, we just failed to load the local resource. Use the fallback Uri
                // if possible.
                generatedUri = AvatarUriUtil.getFallbackUri(mDescriptor.uri);
                if (generatedUri == null) {
                    // No fallback Uri was provided, use the default avatar.
                    generatedUri = AvatarUriUtil.DEFAULT_BACKGROUND_AVATAR;
                }
            }

            avatarType = AvatarUriUtil.getAvatarType(generatedUri);
            if (AvatarUriUtil.TYPE_LETTER_TILE_URI.equals(avatarType)) {
                final String name = AvatarUriUtil.getName(generatedUri);
                bitmap = renderLetterTile(name, width, height);
            } else {
                bitmap = renderDefaultAvatar(width, height);
            }
        }
        return new DecodedImageResource(getKey(), bitmap, orientation);
    }

    private Bitmap renderDefaultAvatar(final int width, final int height) {
        final Bitmap bitmap = getBitmapPool().createOrReuseBitmap(width, height,
                getBackgroundColor());
        final Canvas canvas = new Canvas(bitmap);

        if (sDefaultPersonBitmap == null) {
            final BitmapDrawable defaultPerson = (BitmapDrawable) mContext.getResources()
                    .getDrawable(R.drawable.ic_person_light);
            sDefaultPersonBitmap = defaultPerson.getBitmap();
        }
        if (sDefaultPersonBitmapLarge == null) {
            final BitmapDrawable largeDefaultPerson = (BitmapDrawable) mContext.getResources()
                    .getDrawable(R.drawable.ic_person_light_large);
            sDefaultPersonBitmapLarge = largeDefaultPerson.getBitmap();
        }

        Bitmap defaultPerson = null;
        if (mDescriptor.isWearBackground) {
            final BitmapDrawable wearDefaultPerson = (BitmapDrawable) mContext.getResources()
                    .getDrawable(R.drawable.ic_person_wear);
            defaultPerson = wearDefaultPerson.getBitmap();
        } else {
            final boolean isLargeDefault = (width > sDefaultPersonBitmap.getWidth()) ||
                    (height > sDefaultPersonBitmap.getHeight());
            defaultPerson =
                    isLargeDefault ? sDefaultPersonBitmapLarge : sDefaultPersonBitmap;
        }

        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Matrix matrix = new Matrix();
        final RectF source = new RectF(0, 0, defaultPerson.getWidth(), defaultPerson.getHeight());
        final RectF dest = new RectF(0, 0, width, height);
        matrix.setRectToRect(source, dest, Matrix.ScaleToFit.FILL);

        canvas.drawBitmap(defaultPerson, matrix, paint);

        return bitmap;
    }

    private Bitmap renderLetterTile(final String name, final int width, final int height) {
        final float halfWidth = width / 2;
        final float halfHeight = height / 2;
        final int minOfWidthAndHeight = Math.min(width, height);
        final Bitmap bitmap = getBitmapPool().createOrReuseBitmap(width, height,
                getBackgroundColor());
        final Resources resources = mContext.getResources();
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setColor(resources.getColor(R.color.letter_tile_font_color));
        final float letterToTileRatio = resources.getFraction(R.fraction.letter_to_tile_ratio, 1, 1);
        paint.setTextSize(letterToTileRatio * minOfWidthAndHeight);

        final String firstCharString = name.substring(0, 1).toUpperCase();
        final Rect textBound = new Rect();
        paint.getTextBounds(firstCharString, 0, 1, textBound);

        final Canvas canvas = new Canvas(bitmap);
        final float xOffset = halfWidth - textBound.centerX();
        final float yOffset = halfHeight - textBound.centerY();
        canvas.drawText(firstCharString, xOffset, yOffset, paint);

        return bitmap;
    }

    private int getBackgroundColor() {
        return mContext.getResources().getColor(R.color.primary_color);
    }

    @Override
    public int getCacheId() {
        return BugleMediaCacheManager.AVATAR_IMAGE_CACHE;
    }
}
