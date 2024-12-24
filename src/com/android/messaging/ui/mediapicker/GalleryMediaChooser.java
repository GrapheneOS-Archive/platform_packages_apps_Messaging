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

package com.android.messaging.ui.mediapicker;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MergeCursor;
import androidx.appcompat.app.ActionBar;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import com.android.messaging.Factory;
import com.android.messaging.R;
import com.android.messaging.datamodel.data.GalleryGridItemData;
import com.android.messaging.datamodel.data.MediaPickerData;
import com.android.messaging.datamodel.data.MessagePartData;
import com.android.messaging.datamodel.data.MediaPickerData.MediaPickerDataListener;
import com.android.messaging.datamodel.data.PendingAttachmentData;
import com.android.messaging.ui.UIIntents;
import com.android.messaging.ui.mediapicker.DocumentImagePicker.SelectionListener;
import com.android.messaging.util.Assert;
import com.android.messaging.util.OsUtil;

/**
 * Chooser which allows the user to select one or more existing images or videos or audios.
 */
class GalleryMediaChooser extends MediaChooser implements
        GalleryGridView.GalleryGridViewListener, MediaPickerDataListener {
    private final GalleryGridAdapter mAdapter;
    private GalleryGridView mGalleryGridView;
    private View mMissingPermissionView;

    /** Handles picking a media from the document picker. */
    private DocumentImagePicker mDocumentImagePicker;

    GalleryMediaChooser(final MediaPicker mediaPicker) {
        super(mediaPicker);
        mAdapter = new GalleryGridAdapter(Factory.get().getApplicationContext(), null);
        mDocumentImagePicker = new DocumentImagePicker(mMediaPicker,
                new SelectionListener() {
                    @Override
                    public void onDocumentSelected(final PendingAttachmentData data) {
                        if (mBindingRef.isBound()) {
                            mMediaPicker.dispatchPendingItemAdded(data);
                        }
                    }
                });
    }

    @Override
    public int getSupportedMediaTypes() {
        return (MediaPicker.MEDIA_TYPE_IMAGE
                | MediaPicker.MEDIA_TYPE_VIDEO
                | MediaPicker.MEDIA_TYPE_AUDIO);
    }

    @Override
    public View destroyView() {
        mGalleryGridView.setAdapter(null);
        mAdapter.setHostInterface(null);
        // The loader is started only if startMediaPickerDataLoader() is called
        if (hasStoragePermissions()) {
            mBindingRef.getData().destroyLoader(MediaPickerData.GALLERY_MEDIA_LOADER);
        }
        return super.destroyView();
    }

    @Override
    public int getIconResource() {
        return R.drawable.ic_image_light;
    }

    @Override
    public int getIconDescriptionResource() {
        return R.string.mediapicker_galleryChooserDescription;
    }

    @Override
    public boolean canSwipeDown() {
        return mGalleryGridView.canSwipeDown();
    }

    @Override
    public void onItemSelected(final MessagePartData item) {
        mMediaPicker.dispatchItemsSelected(item, !mGalleryGridView.isMultiSelectEnabled());
    }

    @Override
    public void onItemUnselected(final MessagePartData item) {
        mMediaPicker.dispatchItemUnselected(item);
    }

    @Override
    public void onConfirmSelection() {
        // The user may only confirm if multiselect is enabled.
        Assert.isTrue(mGalleryGridView.isMultiSelectEnabled());
        mMediaPicker.dispatchConfirmItemSelection();
    }

    @Override
    public void onUpdate() {
        mMediaPicker.invalidateOptionsMenu();
    }

    @Override
    public void onCreateOptionsMenu(final MenuInflater inflater, final Menu menu) {
        if (mView != null) {
            mGalleryGridView.onCreateOptionsMenu(inflater, menu);
        }
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        return (mView != null) ? mGalleryGridView.onOptionsItemSelected(item) : false;
    }

    @Override
    protected View createView(final ViewGroup container) {
        final LayoutInflater inflater = getLayoutInflater();
        final View view = inflater.inflate(
                R.layout.mediapicker_gallery_chooser,
                container /* root */,
                false /* attachToRoot */);

        mGalleryGridView = (GalleryGridView) view.findViewById(R.id.gallery_grid_view);
        mAdapter.setHostInterface(mGalleryGridView);
        mGalleryGridView.setAdapter(mAdapter);
        mGalleryGridView.setHostInterface(this);
        mGalleryGridView.setDraftMessageDataModel(mMediaPicker.getDraftMessageDataModel());
        if (hasStoragePermissions()) {
            startMediaPickerDataLoader();
        }

        mMissingPermissionView = view.findViewById(R.id.missing_permission_view);
        updateForPermissionState(hasStoragePermissions());
        return view;
    }

    @Override
    int getActionBarTitleResId() {
        return R.string.mediapicker_gallery_title;
    }

    @Override
    public void onDocumentPickerItemClicked() {
        // Launch an external picker to pick item from document picker as attachment.
        mDocumentImagePicker.launchPicker();
    }

    @Override
    void updateActionBar(final ActionBar actionBar) {
        super.updateActionBar(actionBar);
        if (mGalleryGridView == null) {
            return;
        }
        final int selectionCount = mGalleryGridView.getSelectionCount();
        if (selectionCount > 0 && mGalleryGridView.isMultiSelectEnabled()) {
            actionBar.setTitle(getContext().getResources().getString(
                    R.string.mediapicker_gallery_title_selection,
                    selectionCount));
        }
    }

    @Override
    public void onMediaPickerDataUpdated(final MediaPickerData mediaPickerData, final Object data,
            final int loaderId) {
        mBindingRef.ensureBound(mediaPickerData);
        Assert.equals(MediaPickerData.GALLERY_MEDIA_LOADER, loaderId);
        Cursor rawCursor = null;
        if (data instanceof Cursor) {
            rawCursor = (Cursor) data;
        }
        // Before delivering the cursor, wrap around the local gallery cursor
        // with an extra item for document picker integration in the front.
        final MatrixCursor specialItemsCursor =
                new MatrixCursor(GalleryGridItemData.SPECIAL_ITEM_COLUMNS);
        specialItemsCursor.addRow(new Object[] { GalleryGridItemData.ID_DOCUMENT_PICKER_ITEM });
        final MergeCursor cursor =
                new MergeCursor(new Cursor[] { specialItemsCursor, rawCursor });
        mAdapter.swapCursor(cursor);
    }

    @Override
    public void onResume() {
        if (hasStoragePermissions()) {
            // Work around a bug in MediaStore where cursors querying the Files provider don't get
            // updated for changes to Images.Media or Video.Media.
            startMediaPickerDataLoader();
        }
    }

    @Override
    protected void setSelected(final boolean selected) {
        super.setSelected(selected);
        if (selected && !hasStoragePermissions()) {
            mMediaPicker.requestPermissions(
                    new String[] { Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO },
                    MediaPicker.GALLERY_PERMISSION_REQUEST_CODE);
        }
    }

    private void startMediaPickerDataLoader() {
        mBindingRef
                .getData()
                .startLoader(MediaPickerData.GALLERY_MEDIA_LOADER, mBindingRef, null, this);
    }

    @Override
    protected void onRequestPermissionsResult(
            final int requestCode, final String permissions[], final int[] grantResults) {
        if (requestCode == MediaPicker.GALLERY_PERMISSION_REQUEST_CODE) {
            final boolean permissionGranted = grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (permissionGranted) {
                startMediaPickerDataLoader();
            }
            updateForPermissionState(permissionGranted);
        }
    }

    private void updateForPermissionState(final boolean granted) {
        // onRequestPermissionsResult can sometimes get called before createView().
        if (mGalleryGridView == null) {
            return;
        }

        mGalleryGridView.setVisibility(granted ? View.VISIBLE : View.GONE);
        mMissingPermissionView.setVisibility(granted ? View.GONE : View.VISIBLE);
    }

    @Override
    protected void onActivityResult(
            final int requestCode, final int resultCode, final Intent data) {
        if (requestCode == UIIntents.REQUEST_PICK_MEDIA_FROM_DOCUMENT_PICKER
                && resultCode == Activity.RESULT_OK) {
            mDocumentImagePicker.onActivityResult(requestCode, resultCode, data);
        }
    }

    private boolean hasStoragePermissions() {
        return OsUtil.hasReadImagesPermission() || OsUtil.hasReadVideoPermission() || OsUtil.hasReadAudioPermission();
    }
}
