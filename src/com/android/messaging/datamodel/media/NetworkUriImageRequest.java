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

import com.android.messaging.Factory;
import com.android.messaging.util.Assert;
import com.android.messaging.util.ContentType;
import com.android.messaging.util.LogUtil;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

/**
 * Serves network content URI based image requests.
 */
public class NetworkUriImageRequest<D extends UriImageRequestDescriptor> extends
        ImageRequest<D> {

    public NetworkUriImageRequest(Context context, D descriptor) {
        super(context, descriptor);
        mOrientation = android.media.ExifInterface.ORIENTATION_UNDEFINED;
    }

    @Override
    protected InputStream getInputStreamForResource() throws FileNotFoundException {
        Assert.isNotMainThread();
        // Since we need to have an open urlConnection to get the stream, but we don't want to keep
        // that connection open. There is no good way to perform this method.
        return null;
    }

    @Override
    protected boolean isGif() throws FileNotFoundException {
        Assert.isNotMainThread();

        HttpURLConnection connection = null;
        try {
            final URL url = new URL(mDescriptor.uri.toString());
            connection = (HttpURLConnection) url.openConnection();
            connection.connect();
            if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                return ContentType.IMAGE_GIF.equalsIgnoreCase(connection.getContentType());
            }
        } catch (MalformedURLException e) {
            LogUtil.e(LogUtil.BUGLE_TAG,
                    "MalformedUrl for image with url: "
                            + mDescriptor.uri.toString(), e);
        } catch (IOException e) {
            LogUtil.e(LogUtil.BUGLE_TAG,
                    "IOException trying to get inputStream for image with url: "
                            + mDescriptor.uri.toString(), e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return false;
    }

    @Override
    public Bitmap loadBitmapInternal() {
        Assert.isNotMainThread();

        Bitmap bitmap = null;
        HttpURLConnection connection = null;
        try {
            final URL url = new URL(mDescriptor.uri.toString());
            connection = (HttpURLConnection) url.openConnection();
            connection.setDoInput(true);
            connection.connect();
            if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                try (InputStream stream = connection.getInputStream()) {
                    bitmap = BitmapFactory.decodeStream(stream);
                }
            }
        } catch (MalformedURLException e) {
            LogUtil.e(LogUtil.BUGLE_TAG,
                    "MalformedUrl for image with url: " + mDescriptor.uri, e);
        } catch (final OutOfMemoryError e) {
            LogUtil.e(LogUtil.BUGLE_TAG,
                    "OutOfMemoryError for image with url: " + mDescriptor.uri, e);
            Factory.get().reclaimMemory();
        } catch (IOException e) {
            LogUtil.e(LogUtil.BUGLE_TAG,
                    "IOException trying to get inputStream for image with url: "
                            + mDescriptor.uri, e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return bitmap;
    }
}
