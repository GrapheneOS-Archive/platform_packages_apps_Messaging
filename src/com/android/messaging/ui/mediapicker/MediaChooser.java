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

import android.app.FragmentManager;
import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;

import com.android.messaging.R;
import com.android.messaging.datamodel.binding.ImmutableBindingRef;
import com.android.messaging.datamodel.data.DraftMessageData.DraftMessageSubscriptionDataProvider;
import com.android.messaging.datamodel.data.MediaPickerData;
import com.android.messaging.ui.BasePagerViewHolder;
import com.android.messaging.util.Assert;

import androidx.appcompat.app.ActionBar;

abstract class MediaChooser extends BasePagerViewHolder
        implements DraftMessageSubscriptionDataProvider {
    /** The media picker that the chooser is hosted in */
    protected final MediaPicker mMediaPicker;

    /** Referencing the main media picker binding to perform data loading */
    protected final ImmutableBindingRef<MediaPickerData> mBindingRef;

    /** True if this is the selected chooser */
    protected boolean mSelected;

    /** True if this chooser is open */
    protected boolean mOpen;

    /** The button to show in the tab strip */
    private ImageButton mTabButton;

    /** Used by subclasses to indicate that no loader is required from the data model in order for
     * this chooser to function.
     */
    public static final int NO_LOADER_REQUIRED = -1;

    /**
     * Initializes a new instance of the Chooser class
     * @param mediaPicker The media picker that the chooser is hosted in
     */
    MediaChooser(final MediaPicker mediaPicker) {
        Assert.notNull(mediaPicker);
        mMediaPicker = mediaPicker;
        mBindingRef = mediaPicker.getMediaPickerDataBinding();
        mSelected = false;
    }

    protected void setSelected(final boolean selected) {
        mSelected = selected;
        if (selected) {
            // If we're selected, it must be open
            mOpen = true;
        }
        if (mTabButton != null) {
            mTabButton.setSelected(selected);
            mTabButton.setAlpha(selected ? 1 : 0.5f);
        }
    }

    ImageButton getTabButton() {
        return mTabButton;
    }

    void onCreateTabButton(final LayoutInflater inflater, final ViewGroup parent) {
        mTabButton = (ImageButton) inflater.inflate(
                R.layout.mediapicker_tab_button,
                parent,
                false /* addToParent */);
        mTabButton.setImageResource(getIconResource());
        mTabButton.setContentDescription(
                inflater.getContext().getResources().getString(getIconDescriptionResource()));
        setSelected(mSelected);
        mTabButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View view) {
                mMediaPicker.selectChooser(MediaChooser.this);
            }
        });
    }

    protected Context getContext() {
        return mMediaPicker.getActivity();
    }

    protected FragmentManager getFragmentManager() {
        return mMediaPicker.getChildFragmentManager();
    }

    protected LayoutInflater getLayoutInflater() {
        return LayoutInflater.from(getContext());
    }

    /** Allows the chooser to handle full screen change */
    void onFullScreenChanged(final boolean fullScreen) {}

    /** Allows the chooser to handle the chooser being opened or closed */
    void onOpenedChanged(final boolean open) {
        mOpen = open;
    }

    /** @return The bit field of media types that this chooser can pick */
    public abstract int getSupportedMediaTypes();

    /** @return The resource id of the icon for the chooser */
    abstract int getIconResource();

    /** @return The resource id of the string to use for the accessibility text of the icon */
    abstract int getIconDescriptionResource();

    /**
     * Sets up the action bar to show the current state of the full-screen chooser
     * @param actionBar The action bar to populate
     */
    void updateActionBar(final ActionBar actionBar) {
        final int actionBarTitleResId = getActionBarTitleResId();
        if (actionBarTitleResId == 0) {
            actionBar.hide();
        } else {
            actionBar.setCustomView(null);
            actionBar.setDisplayOptions(ActionBar.DISPLAY_SHOW_TITLE);
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.show();
            // Use X instead of <- in the action bar
            actionBar.setHomeAsUpIndicator(R.drawable.ic_remove_small_light);
            actionBar.setTitle(actionBarTitleResId);
        }
    }

    /**
     * Returns the resource Id used for the action bar title.
     */
    abstract int getActionBarTitleResId();

    /**
     * Throws an exception if the media chooser object doesn't require data support.
     */
    public void onDataUpdated(final Object data, final int loaderId) {
        throw new IllegalStateException();
    }

    /**
     * Called by the MediaPicker to determine whether this panel can be swiped down further. If
     * not, then a swipe down gestured will be captured by the MediaPickerPanel to shrink the
     * entire panel.
     */
    public boolean canSwipeDown() {
        return false;
    }

    /**
     * Typically the media picker is closed when the IME is opened, but this allows the chooser to
     * specify that showing the IME is okay while the chooser is up
     */
    public boolean canShowIme() {
        return false;
    }

    public boolean onBackPressed() {
        return false;
    }

    public void onCreateOptionsMenu(final MenuInflater inflater, final Menu menu) {
    }

    public boolean onOptionsItemSelected(final MenuItem item) {
        return false;
    }

    public void setThemeColor(final int color) {
    }

    /**
     * Returns true if the chooser is owning any incoming touch events, so that the media picker
     * panel won't process it and slide the panel.
     */
    public boolean isHandlingTouch() {
        return false;
    }

    public void stopTouchHandling() {
    }

    protected void onActivityResult(
            final int requestCode, final int resultCode, final Intent data) {}

    @Override
    public int getConversationSelfSubId() {
        return mMediaPicker.getConversationSelfSubId();
    }

    /** Optional activity life-cycle methods to be overridden by subclasses */
    public void onPause() { }
    public void onResume() { }
    protected void onRequestPermissionsResult(
            final int requestCode, final String permissions[], final int[] grantResults) { }
}
