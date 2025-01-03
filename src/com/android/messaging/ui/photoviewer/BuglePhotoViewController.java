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

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.BaseColumns;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import com.android.ex.photo.PhotoViewController;
import com.android.ex.photo.adapters.PhotoPagerAdapter;
import com.android.ex.photo.loaders.PhotoBitmapLoaderInterface.BitmapResult;
import com.android.messaging.R;
import com.android.messaging.datamodel.ConversationImagePartsView.PhotoViewQuery;
import com.android.messaging.datamodel.MediaScratchFileProvider;
import com.android.messaging.ui.conversation.ConversationFragment;
import com.android.messaging.util.Dates;
import com.android.messaging.util.LogUtil;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;
import androidx.loader.content.Loader;

/**
 * Customizations for the photoviewer to display conversation images in full screen.
 */
public class BuglePhotoViewController extends PhotoViewController {
    private static final String TAG = "BuglePhotoViewController";

    private MenuItem mShareItem;
    private MenuItem mSaveItem;

    public BuglePhotoViewController(final ActivityInterface activity) {
        super(activity);
    }

    @Override
    public Loader<BitmapResult> onCreateBitmapLoader(
            final int id, final Bundle args, final String uri) {
        switch (id) {
            case BITMAP_LOADER_AVATAR:
            case BITMAP_LOADER_THUMBNAIL:
            case BITMAP_LOADER_PHOTO:
                return new BuglePhotoBitmapLoader(getActivity().getContext(), uri);
            default:
                LogUtil.e(LogUtil.BUGLE_TAG,
                        "Photoviewer unable to open bitmap loader with unknown id: " + id);
                return null;
        }
    }

    @Override
    public void updateActionBar() {
        final Cursor cursor = getCursorAtProperPosition();

        if (mSaveItem == null || cursor == null) {
            // Load not finished, called from framework code before ready
            return;
        }
        // Show the name as the title
        mActionBarTitle = cursor.getString(PhotoViewQuery.INDEX_SENDER_FULL_NAME);
        if (TextUtils.isEmpty(mActionBarTitle)) {
            // If the name is not known, fall back to the phone number
            mActionBarTitle = cursor.getString(PhotoViewQuery.INDEX_DISPLAY_DESTINATION);
        }

        // Show the timestamp as the subtitle
        final long receivedTimestamp = cursor.getLong(PhotoViewQuery.INDEX_RECEIVED_TIMESTAMP);
        mActionBarSubtitle = Dates.getMessageTimeString(receivedTimestamp).toString();

        setActionBarTitles(getActivity().getActionBarInterface());
        mSaveItem.setVisible(!isTempFile());

        updateShareItem();
    }

    private void updateShareItem() {
        final PhotoPagerAdapter adapter = getAdapter();
        final Cursor cursor = getCursorAtProperPosition();
        if (mShareItem == null || adapter == null || cursor == null) {
            // Not enough stuff loaded to update the share action
            return;
        }
        mShareItem.setVisible(!isTempFile());
    }

    /**
     * Checks whether the current photo is a temp file.  A temp file can be deleted at any time, so
     * we need to disable share and save options because the file may no longer be there.
     */
    private boolean isTempFile() {
        final Cursor cursor = getCursorAtProperPosition();
        final Uri photoUri = Uri.parse(getAdapter().getPhotoUri(cursor));
        return MediaScratchFileProvider.isMediaScratchSpaceUri(photoUri);
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        ((Activity) getActivity()).getMenuInflater().inflate(R.menu.photo_view_menu, menu);

        mShareItem = menu.findItem(R.id.action_share);
        updateShareItem();

        mSaveItem = menu.findItem(R.id.action_save);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(final Menu menu) {
        return !mIsEmpty;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.action_save) {
            final PhotoPagerAdapter adapter = getAdapter();
            final Cursor cursor = getCursorAtProperPosition();
            if (cursor == null) {
                final Context context = getActivity().getContext();
                final String error = context.getResources().getQuantityString(
                        R.plurals.attachment_save_error, 1, 1);
                Toast.makeText(context, error, Toast.LENGTH_SHORT).show();
                return true;
            }
            final String photoUri = adapter.getPhotoUri(cursor);
            new ConversationFragment.SaveAttachmentTask(((Activity) getActivity()),
                    Uri.parse(photoUri), adapter.getContentType(cursor)).executeOnThreadPool();
            return true;
        } else if (itemId == R.id.action_share) {
            handleShare();
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    private void handleShare() {
        PhotoPagerAdapter adapter = getAdapter();
        Cursor cursor = getCursorAtProperPosition();
        String photoUri = adapter.getPhotoUri(cursor);
        String contentType = adapter.getContentType(cursor);

        Uri uri = Uri.parse(photoUri);
        if (ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
            Uri contentUri = fileUriToContentUri(getActivity().getContext(), uri);
            if (contentUri == null) {
                Log.e(TAG, "fileUriToContentUri failed for " + uri);
                return;
            }
            Log.d(TAG, "converted " + uri + " to " + contentUri);
            uri = contentUri;
        }
        var intent = new Intent(Intent.ACTION_SEND);
        intent.setType(contentType);
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ((Activity) getActivity()).startActivity(Intent.createChooser(intent, null));
    }

    @Nullable
    private static Uri fileUriToContentUri(Context context, Uri uri) {
        String path = uri.getPath();
        ContentResolver resolver = context.getContentResolver();
        String[] projection = { BaseColumns._ID };
        String selection = MediaStore.MediaColumns.DATA + "=?";
        String[] selectionArgs = { path };
        String volume = MediaStore.VOLUME_EXTERNAL;
        Uri volumeUri = MediaStore.Files.getContentUri(volume);
        try (Cursor c = resolver.query(volumeUri, projection, selection, selectionArgs, null)) {
            if (c == null || !c.moveToFirst()) {
                return null;
            }
            long id = c.getLong(0);
            return MediaStore.Files.getContentUri(volume, id);
        }
    }

    @Override
    public PhotoPagerAdapter createPhotoPagerAdapter(final Context context,
            final FragmentManager fm, final Cursor c, final float maxScale) {
        return new BuglePhotoPageAdapter(context, fm, c, maxScale, mDisplayThumbsFullScreen);
    }
}
