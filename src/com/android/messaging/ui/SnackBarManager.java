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
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.View.OnAttachStateChangeListener;
import android.view.View.OnTouchListener;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewPropertyAnimator;
import android.view.ViewTreeObserver.OnGlobalLayoutListener;
import android.view.WindowManager;
import android.widget.PopupWindow;
import android.widget.PopupWindow.OnDismissListener;

import com.android.messaging.Factory;
import com.android.messaging.R;
import com.android.messaging.ui.SnackBar.Placement;
import com.android.messaging.ui.SnackBar.SnackBarListener;
import com.android.messaging.util.AccessibilityUtil;
import com.android.messaging.util.Assert;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.TextUtil;
import com.android.messaging.util.UiUtils;
import com.google.common.base.Joiner;

import java.util.List;

public class SnackBarManager {

    private static SnackBarManager sInstance;

    public static SnackBarManager get() {
        if (sInstance == null) {
            synchronized (SnackBarManager.class) {
                if (sInstance == null) {
                    sInstance = new SnackBarManager();
                }
            }
        }
        return sInstance;
    }

    private final Runnable mDismissRunnable = new Runnable() {
        @Override
        public void run() {
            dismiss();
        }
    };

    private final OnTouchListener mDismissOnTouchListener = new OnTouchListener() {
        @Override
        public boolean onTouch(final View view, final MotionEvent event) {
            // Dismiss the {@link SnackBar} but don't consume the event.
            dismiss();
            return false;
        }
    };

    private final SnackBarListener mDismissOnUserTapListener = new SnackBarListener() {
        @Override
        public void onActionClick() {
            dismiss();
        }
    };

    private final OnAttachStateChangeListener mAttachStateChangeListener =
            new OnAttachStateChangeListener() {
                @Override
                public void onViewDetachedFromWindow(View v) {
                    // Dismiss the PopupWindow and clear SnackBarManager state.
                    mHideHandler.removeCallbacks(mDismissRunnable);
                    mPopupWindow.dismiss();

                    mCurrentSnackBar = null;
                    mNextSnackBar = null;
                    mIsCurrentlyDismissing = false;
                }

                @Override
                public void onViewAttachedToWindow(View v) {}
            };

    private final int mTranslationDurationMs;
    private final Handler mHideHandler;

    private SnackBar mCurrentSnackBar;
    private SnackBar mLatestSnackBar;
    private SnackBar mNextSnackBar;
    private boolean mIsCurrentlyDismissing;
    private PopupWindow mPopupWindow;

    private SnackBarManager() {
        mTranslationDurationMs = Factory.get().getApplicationContext().getResources().getInteger(
                R.integer.snackbar_translation_duration_ms);
        mHideHandler = new Handler();
    }

    public SnackBar getLatestSnackBar() {
        return mLatestSnackBar;
    }

    public SnackBar.Builder newBuilder(final View parentView) {
        return new SnackBar.Builder(this, parentView);
    }

    /**
     * The given snackBar is not guaranteed to be shown. If the previous snackBar is animating away,
     * and another snackBar is requested to show after this one, this snackBar will be skipped.
     */
    public void show(final SnackBar snackBar) {
        Assert.notNull(snackBar);

        if (mCurrentSnackBar != null) {
            LogUtil.d(LogUtil.BUGLE_TAG, "Showing snack bar, but currentSnackBar was not null.");

            // Dismiss the current snack bar. That will cause the next snack bar to be shown on
            // completion.
            mNextSnackBar = snackBar;
            mLatestSnackBar = snackBar;
            dismiss();
            return;
        }

        mCurrentSnackBar = snackBar;
        mLatestSnackBar = snackBar;

        // We want to know when either button was tapped so we can dismiss.
        snackBar.setListener(mDismissOnUserTapListener);

        // Cancel previous dismisses & set dismiss for the delay time.
        mHideHandler.removeCallbacks(mDismissRunnable);
        mHideHandler.postDelayed(mDismissRunnable, snackBar.getDuration());

        snackBar.setEnabled(false);

        // For some reason, the addView function does not respect layoutParams.
        // We need to explicitly set it first here.
        final View rootView = snackBar.getRootView();

        if (LogUtil.isLoggable(LogUtil.BUGLE_TAG, LogUtil.DEBUG)) {
            LogUtil.d(LogUtil.BUGLE_TAG, "Showing snack bar: " + snackBar);
        }
        // Measure the snack bar root view so we know how much to translate by.
        measureSnackBar(snackBar);
        mPopupWindow = new PopupWindow(snackBar.getContext());
        mPopupWindow.setWidth(LayoutParams.MATCH_PARENT);
        mPopupWindow.setHeight(LayoutParams.WRAP_CONTENT);
        mPopupWindow.setBackgroundDrawable(null);
        mPopupWindow.setContentView(rootView);
        final Placement placement = snackBar.getPlacement();
        if (placement == null) {
            mPopupWindow.showAtLocation(
                    snackBar.getParentView(), Gravity.BOTTOM | Gravity.START,
                    0, getScreenBottomOffset(snackBar));
        } else {
            final View anchorView = placement.getAnchorView();

            // You'd expect PopupWindow.showAsDropDown to ensure the popup moves with the anchor
            // view, which it does for scrolling, but not layout changes, so we have to manually
            // update while the snackbar is showing
            final OnGlobalLayoutListener listener = new OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    mPopupWindow.update(anchorView, 0, getRelativeOffset(snackBar),
                            anchorView.getWidth(), LayoutParams.WRAP_CONTENT);
                }
            };
            anchorView.getViewTreeObserver().addOnGlobalLayoutListener(listener);
            mPopupWindow.setOnDismissListener(new OnDismissListener() {
                @Override
                public void onDismiss() {
                    anchorView.getViewTreeObserver().removeOnGlobalLayoutListener(listener);
                }
            });
            mPopupWindow.showAsDropDown(anchorView, 0, getRelativeOffset(snackBar));
        }

        snackBar.getParentView().addOnAttachStateChangeListener(mAttachStateChangeListener);

        // Animate the toast bar into view.
        placeSnackBarOffScreen(snackBar);
        animateSnackBarOnScreen(snackBar).withEndAction(new Runnable() {
            @Override
            public void run() {
                mCurrentSnackBar.setEnabled(true);
                makeCurrentSnackBarDismissibleOnTouch();
                // Fire an accessibility event as needed
                String snackBarText = snackBar.getMessageText();
                if (!TextUtils.isEmpty(snackBarText) &&
                        TextUtils.getTrimmedLength(snackBarText) > 0) {
                    snackBarText = snackBarText.trim();
                    final String snackBarActionText = snackBar.getActionLabel();
                    if (!TextUtil.isAllWhitespace(snackBarActionText)) {
                        snackBarText = Joiner.on(", ").join(snackBarText, snackBarActionText);
                    }
                    AccessibilityUtil.announceForAccessibilityCompat(snackBar.getSnackBarView(),
                            null /*accessibilityManager*/, snackBarText);
                }
            }
        });

        // Animate any interaction views out of the way.
        animateInteractionsOnShow(snackBar);
    }

    /**
     * Dismisses the current toast that is showing. If there is a toast waiting to be shown, that
     * toast will be shown when the current one has been dismissed.
     */
    public void dismiss() {
        mHideHandler.removeCallbacks(mDismissRunnable);

        if (mCurrentSnackBar == null || mIsCurrentlyDismissing) {
            return;
        }

        final SnackBar snackBar = mCurrentSnackBar;

        LogUtil.d(LogUtil.BUGLE_TAG, "Dismissing snack bar.");
        mIsCurrentlyDismissing = true;

        snackBar.setEnabled(false);

        // Animate the toast bar down.
        final View rootView = snackBar.getRootView();
        animateSnackBarOffScreen(snackBar).withEndAction(new Runnable() {
            @Override
            public void run() {
                rootView.setVisibility(View.GONE);
                try {
                    mPopupWindow.dismiss();
                } catch (IllegalArgumentException e) {
                    // PopupWindow.dismiss() will fire an IllegalArgumentException if the activity
                    // has already ended while we were animating
                }
                snackBar.getParentView()
                        .removeOnAttachStateChangeListener(mAttachStateChangeListener);

                mCurrentSnackBar = null;
                mIsCurrentlyDismissing = false;

                // Show the next toast if one is waiting.
                if (mNextSnackBar != null) {
                    final SnackBar localNextSnackBar = mNextSnackBar;
                    mNextSnackBar = null;
                    show(localNextSnackBar);
                }
            }
        });

        // Animate any interaction views back.
        animateInteractionsOnDismiss(snackBar);
    }

    private void makeCurrentSnackBarDismissibleOnTouch() {
        // Set touching on the entire view, the {@link SnackBar} itself, as
        // well as the button's dismiss the toast.
        mCurrentSnackBar.getRootView().setOnTouchListener(mDismissOnTouchListener);
        mCurrentSnackBar.getSnackBarView().setOnTouchListener(mDismissOnTouchListener);
    }

    private void measureSnackBar(final SnackBar snackBar) {
        final View rootView = snackBar.getRootView();
        final Point displaySize = new Point();
        getWindowManager(snackBar.getContext()).getDefaultDisplay().getSize(displaySize);
        final int widthSpec = ViewGroup.getChildMeasureSpec(
                MeasureSpec.makeMeasureSpec(displaySize.x, MeasureSpec.EXACTLY),
                0, LayoutParams.MATCH_PARENT);
        final int heightSpec = ViewGroup.getChildMeasureSpec(
                MeasureSpec.makeMeasureSpec(displaySize.y, MeasureSpec.EXACTLY),
                0, LayoutParams.WRAP_CONTENT);
        rootView.measure(widthSpec, heightSpec);
    }

    private void placeSnackBarOffScreen(final SnackBar snackBar) {
        final View rootView = snackBar.getRootView();
        final View snackBarView = snackBar.getSnackBarView();
        snackBarView.setTranslationY(rootView.getMeasuredHeight());
    }

    private ViewPropertyAnimator animateSnackBarOnScreen(final SnackBar snackBar) {
        final View snackBarView = snackBar.getSnackBarView();
        return normalizeAnimator(snackBarView.animate()).translationX(0).translationY(0);
    }

    private ViewPropertyAnimator animateSnackBarOffScreen(final SnackBar snackBar) {
        final View rootView = snackBar.getRootView();
        final View snackBarView = snackBar.getSnackBarView();
        return normalizeAnimator(snackBarView.animate()).translationY(rootView.getHeight());
    }

    private void animateInteractionsOnShow(final SnackBar snackBar) {
        final List<SnackBarInteraction> interactions = snackBar.getInteractions();
        for (final SnackBarInteraction interaction : interactions) {
            if (interaction != null) {
                final ViewPropertyAnimator animator = interaction.animateOnSnackBarShow(snackBar);
                if (animator != null) {
                    normalizeAnimator(animator);
                }
            }
        }
    }

    private void animateInteractionsOnDismiss(final SnackBar snackBar) {
        final List<SnackBarInteraction> interactions = snackBar.getInteractions();
        for (final SnackBarInteraction interaction : interactions) {
            if (interaction != null) {
                final ViewPropertyAnimator animator =
                        interaction.animateOnSnackBarDismiss(snackBar);
                if (animator != null) {
                    normalizeAnimator(animator);
                }
            }
        }
    }

    private ViewPropertyAnimator normalizeAnimator(final ViewPropertyAnimator animator) {
        return animator
                .setInterpolator(UiUtils.DEFAULT_INTERPOLATOR)
                .setDuration(mTranslationDurationMs);
    }

    private WindowManager getWindowManager(final Context context) {
        return (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    /**
     * Get the offset from the bottom of the screen where the snack bar should be placed.
     */
    private int getScreenBottomOffset(final SnackBar snackBar) {
        final WindowManager windowManager = getWindowManager(snackBar.getContext());
        final DisplayMetrics displayMetrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(displayMetrics);

        final int screenHeight = displayMetrics.heightPixels;

        // The navigation bar is included in the space for the popup window, so we have to
        // offset by the size of the navigation bar
        final Rect displayRect = new Rect();
        snackBar.getParentView().getRootView().getWindowVisibleDisplayFrame(displayRect);
        return screenHeight - displayRect.bottom;
    }

    private int getRelativeOffset(final SnackBar snackBar) {
        final Placement placement = snackBar.getPlacement();
        Assert.notNull(placement);
        final View anchorView = placement.getAnchorView();
        if (placement.getAnchorAbove()) {
            return -snackBar.getRootView().getMeasuredHeight() - anchorView.getHeight();
        } else {
            // Use the default dropdown positioning
            return 0;
        }
    }
}
