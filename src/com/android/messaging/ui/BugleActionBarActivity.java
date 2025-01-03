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

import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;

import com.android.messaging.R;
import com.android.messaging.util.BugleActivityUtil;
import com.android.messaging.util.ImeUtil;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.UiUtils;

import java.util.HashSet;
import java.util.Set;

/**
 * Base class for app activities that use an action bar. Responsible for logging/telemetry/other
 * needs that will be common for all activities.  We can break out the common code if/when we need
 * a version that doesn't use an actionbar.
 */
public class BugleActionBarActivity extends AppCompatActivity implements ImeUtil.ImeStateHost {
    // Tracks the list of observers opting in for IME state change.
    private final Set<ImeUtil.ImeStateObserver> mImeStateObservers = new HashSet<>();

    // Tracks the soft keyboard display state
    private boolean mImeOpen;

    // The ActionMode that represents the modal contextual action bar, using our own implementation
    // rather than the built in contextual action bar to reduce jank
    private CustomActionMode mActionMode;

    // The menu for the action bar
    private Menu mActionBarMenu;

    // Used to determine if a onDisplayHeightChanged was due to the IME opening or rotation of the
    // device
    private int mLastScreenHeight;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (UiUtils.redirectToPermissionCheckIfNeeded(this)) {
            return;
        }

        mLastScreenHeight = getResources().getDisplayMetrics().heightPixels;
        if (LogUtil.isLoggable(LogUtil.BUGLE_TAG, LogUtil.VERBOSE)) {
            LogUtil.v(LogUtil.BUGLE_TAG, this.getLocalClassName() + ".onCreate");
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (LogUtil.isLoggable(LogUtil.BUGLE_TAG, LogUtil.VERBOSE)) {
            LogUtil.v(LogUtil.BUGLE_TAG, this.getLocalClassName() + ".onStart");
        }
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        if (LogUtil.isLoggable(LogUtil.BUGLE_TAG, LogUtil.VERBOSE)) {
            LogUtil.v(LogUtil.BUGLE_TAG, this.getLocalClassName() + ".onRestart");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (LogUtil.isLoggable(LogUtil.BUGLE_TAG, LogUtil.VERBOSE)) {
            LogUtil.v(LogUtil.BUGLE_TAG, this.getLocalClassName() + ".onResume");
        }
        BugleActivityUtil.onActivityResume(this, BugleActionBarActivity.this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (LogUtil.isLoggable(LogUtil.BUGLE_TAG, LogUtil.VERBOSE)) {
            LogUtil.v(LogUtil.BUGLE_TAG, this.getLocalClassName() + ".onPause");
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (LogUtil.isLoggable(LogUtil.BUGLE_TAG, LogUtil.VERBOSE)) {
            LogUtil.v(LogUtil.BUGLE_TAG, this.getLocalClassName() + ".onStop");
        }
    }

    private boolean mDestroyed;

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mDestroyed = true;
    }

    public boolean getIsDestroyed() {
        return mDestroyed;
    }

    @Override
    public void onDisplayHeightChanged(final int heightSpecification) {
        int screenHeight = getResources().getDisplayMetrics().heightPixels;

        if (screenHeight != mLastScreenHeight) {
            // Appears to be an orientation change, don't fire ime updates
            mLastScreenHeight = screenHeight;
            LogUtil.v(LogUtil.BUGLE_TAG, this.getLocalClassName() + ".onDisplayHeightChanged " +
                    " screenHeight: " + screenHeight + " lastScreenHeight: " + mLastScreenHeight +
                    " Skipped, appears to be orientation change.");
            return;
        }
        final ActionBar actionBar = getSupportActionBar();
        if (actionBar != null && actionBar.isShowing()) {
            screenHeight -= actionBar.getHeight();
        }
        final int height = View.MeasureSpec.getSize(heightSpecification);

        final boolean imeWasOpen = mImeOpen;
        mImeOpen = screenHeight - height > 100;

        if (LogUtil.isLoggable(LogUtil.BUGLE_TAG, LogUtil.VERBOSE)) {
            LogUtil.v(LogUtil.BUGLE_TAG, this.getLocalClassName() + ".onDisplayHeightChanged " +
                    "imeWasOpen: " + imeWasOpen + " mImeOpen: " + mImeOpen + " screenHeight: " +
                    screenHeight + " height: " + height);
        }

        if (imeWasOpen != mImeOpen) {
            for (final ImeUtil.ImeStateObserver observer : mImeStateObservers) {
                observer.onImeStateChanged(mImeOpen);
            }
        }
    }

    @Override
    public void registerImeStateObserver(final ImeUtil.ImeStateObserver observer) {
        mImeStateObservers.add(observer);
    }

    @Override
    public void unregisterImeStateObserver(final ImeUtil.ImeStateObserver observer) {
        mImeStateObservers.remove(observer);
    }

    @Override
    public boolean isImeOpen() {
        return mImeOpen;
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        mActionBarMenu = menu;
        if (mActionMode != null &&
                mActionMode.getCallback().onCreateActionMode(mActionMode, menu)) {
            return true;
        }
        return false;
    }

    @Override
    public boolean onPrepareOptionsMenu(final Menu menu) {
        mActionBarMenu = menu;
        if (mActionMode != null &&
                mActionMode.getCallback().onPrepareActionMode(mActionMode, menu)) {
            return true;
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem menuItem) {
        if (mActionMode != null &&
                mActionMode.getCallback().onActionItemClicked(mActionMode, menuItem)) {
            return true;
        }

        switch (menuItem.getItemId()) {
            case android.R.id.home:
                if (mActionMode != null) {
                    dismissActionMode();
                    return true;
                }
        }
        return super.onOptionsItemSelected(menuItem);
    }

    @Override
    public ActionMode startActionMode(final ActionMode.Callback callback) {
        mActionMode = new CustomActionMode(callback);
        supportInvalidateOptionsMenu();
        invalidateActionBar();
        return mActionMode;
    }

    public void dismissActionMode() {
        if (mActionMode != null) {
            mActionMode.finish();
            mActionMode = null;
            invalidateActionBar();
        }
    }

    public ActionMode getActionMode() {
        return mActionMode;
    }

    protected ActionMode.Callback getActionModeCallback() {
        if (mActionMode == null) {
            return null;
        }

        return mActionMode.getCallback();
    }

    /**
     * Receives and handles action bar invalidation request from sub-components of this activity.
     *
     * <p>Normally actions have sole control over the action bar, but in order to support seamless
     * transitions for components such as the full screen media picker, we have to let it take over
     * the action bar and then restore its state afterwards</p>
     *
     * <p>If a fragment does anything that may change the action bar, it should call this method
     * and then it is this method's responsibility to figure out which component "controls" the
     * action bar and delegate the updating of the action bar to that component</p>
     */
    public final void invalidateActionBar() {
        if (mActionMode != null) {
            mActionMode.updateActionBar(getSupportActionBar());
        } else {
            updateActionBar(getSupportActionBar());
        }
    }

    protected void updateActionBar(final ActionBar actionBar) {
        actionBar.setHomeAsUpIndicator(null);
    }

    /**
     * Custom ActionMode implementation which allows us to just replace the contents of the main
     * action bar rather than overlay over it
     */
    private class CustomActionMode extends ActionMode {
        private CharSequence mTitle;
        private CharSequence mSubtitle;
        private View mCustomView;
        private final Callback mCallback;

        public CustomActionMode(final Callback callback) {
            mCallback = callback;
        }

        @Override
        public void setTitle(final CharSequence title) {
            mTitle = title;
        }

        @Override
        public void setTitle(final int resId) {
            mTitle = getResources().getString(resId);
        }

        @Override
        public void setSubtitle(final CharSequence subtitle) {
            mSubtitle = subtitle;
        }

        @Override
        public void setSubtitle(final int resId) {
            mSubtitle = getResources().getString(resId);
        }

        @Override
        public void setCustomView(final View view) {
            mCustomView = view;
        }

        @Override
        public void invalidate() {
            invalidateActionBar();
        }

        @Override
        public void finish() {
            mActionMode = null;
            mCallback.onDestroyActionMode(this);
            supportInvalidateOptionsMenu();
            invalidateActionBar();
        }

        @Override
        public Menu getMenu() {
            return mActionBarMenu;
        }

        @Override
        public CharSequence getTitle() {
            return mTitle;
        }

        @Override
        public CharSequence getSubtitle() {
            return mSubtitle;
        }

        @Override
        public View getCustomView() {
            return mCustomView;
        }

        @Override
        public MenuInflater getMenuInflater() {
            return BugleActionBarActivity.this.getMenuInflater();
        }

        public Callback getCallback() {
            return mCallback;
        }

        public void updateActionBar(final ActionBar actionBar) {
            actionBar.setDisplayOptions(ActionBar.DISPLAY_HOME_AS_UP);
            actionBar.setDisplayShowTitleEnabled(false);
            actionBar.setDisplayShowCustomEnabled(false);
            mActionMode.getCallback().onPrepareActionMode(mActionMode, mActionBarMenu);
            actionBar.setBackgroundDrawable(new ColorDrawable(
                    getResources().getColor(R.color.contextual_action_bar_background_color)));
            actionBar.setHomeAsUpIndicator(R.drawable.ic_cancel_small_dark);
            actionBar.show();
        }
    }
}
