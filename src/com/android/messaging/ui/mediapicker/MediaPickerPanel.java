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

import android.content.Context;
import android.content.res.Resources;
import android.os.Handler;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.Transformation;
import android.widget.LinearLayout;

import com.android.messaging.R;
import com.android.messaging.ui.PagingAwareViewPager;
import com.android.messaging.util.UiUtils;

/**
 * Custom layout panel which makes the MediaPicker animations seamless and synchronized
 * Designed to be very specific to the MediaPicker's usage
 */
public class MediaPickerPanel extends ViewGroup {
    /**
     * The window of time in which we might to decide to reinterpret the intent of a gesture.
     */
    private static final long TOUCH_RECAPTURE_WINDOW_MS = 500L;

    // The two view components to layout
    private LinearLayout mTabStrip;
    private boolean mFullScreenOnly;
    private PagingAwareViewPager mViewPager;

    /**
     * True if the MediaPicker is full screen or animating into it
     */
    private boolean mFullScreen;

    /**
     * True if the MediaPicker is open at all
     */
    private boolean mExpanded;

    /**
     * The current desired height of the MediaPicker.  This property may be animated and the
     * measure pass uses it to determine what size the components are.
     */
    private int mCurrentDesiredHeight;

    private final Handler mHandler = new Handler();

    /**
     * The media picker for dispatching events to the MediaPicker's listener
     */
    private MediaPicker mMediaPicker;

    /**
     * The computed default "half-screen" height of the view pager in px
     */
    private final int mDefaultViewPagerHeight;

    /**
     * The action bar height used to compute the padding on the view pager when it's full screen.
     */
    private final int mActionBarHeight;

    private TouchHandler mTouchHandler;

    static final int PAGE_NOT_SET = -1;

    public MediaPickerPanel(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        // Cache the computed dimension
        mDefaultViewPagerHeight = getResources().getDimensionPixelSize(
                R.dimen.mediapicker_default_chooser_height);
        mActionBarHeight = getResources().getDimensionPixelSize(R.dimen.action_bar_height);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mTabStrip = (LinearLayout) findViewById(R.id.mediapicker_tabstrip);
        mViewPager = (PagingAwareViewPager) findViewById(R.id.mediapicker_view_pager);
        mTouchHandler = new TouchHandler();
        setOnTouchListener(mTouchHandler);
        mViewPager.setOnTouchListener(mTouchHandler);

        // Make sure full screen mode is updated in landscape mode change when the panel is open.
        addOnLayoutChangeListener(new OnLayoutChangeListener() {
            private boolean mLandscapeMode = UiUtils.isLandscapeMode();

            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                final boolean newLandscapeMode = UiUtils.isLandscapeMode();
                if (mLandscapeMode != newLandscapeMode) {
                    mLandscapeMode = newLandscapeMode;
                    if (mExpanded) {
                        setExpanded(mExpanded, false /* animate */, mViewPager.getCurrentItem(),
                                true /* force */);
                    }
                }
            }
        });
    }

    @Override
    protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
        int requestedHeight = MeasureSpec.getSize(heightMeasureSpec);
        if (mMediaPicker.getChooserShowsActionBarInFullScreen()) {
            requestedHeight -= mActionBarHeight;
        }
        int desiredHeight = Math.min(mCurrentDesiredHeight, requestedHeight);
        if (mExpanded && desiredHeight == 0) {
            // If we want to be shown, we have to have a non-0 height.  Returning a height of 0 will
            // cause the framework to abort the animation from 0, so we must always have some
            // height once we start expanding
            desiredHeight = 1;
        } else if (!mExpanded && desiredHeight == 0) {
            mViewPager.setVisibility(View.GONE);
            mViewPager.setAdapter(null);
        }

        measureChild(mTabStrip, widthMeasureSpec, heightMeasureSpec);

        int tabStripHeight;
        if (requiresFullScreen()) {
            // Ensure that the tab strip is always visible, even in full screen.
            tabStripHeight = mTabStrip.getMeasuredHeight();
        } else {
            // Slide out the tab strip at the end of the animation to full screen.
            tabStripHeight = Math.min(mTabStrip.getMeasuredHeight(),
                    requestedHeight - desiredHeight);
        }

        // If we are animating and have an interim desired height, use the default height. We can't
        // take the max here as on some devices the mDefaultViewPagerHeight may be too big in
        // landscape mode after animation.
        final int tabAdjustedDesiredHeight = desiredHeight - tabStripHeight;
        final int viewPagerHeight =
                tabAdjustedDesiredHeight <= 1 ? mDefaultViewPagerHeight : tabAdjustedDesiredHeight;

        int viewPagerHeightMeasureSpec = MeasureSpec.makeMeasureSpec(
                viewPagerHeight, MeasureSpec.EXACTLY);
        measureChild(mViewPager, widthMeasureSpec, viewPagerHeightMeasureSpec);
        setMeasuredDimension(mViewPager.getMeasuredWidth(), desiredHeight);
    }

    @Override
    protected void onLayout(final boolean changed, final int left, final int top, final int right,
            final int bottom) {
        int y = top;
        final int width = right - left;

        final int viewPagerHeight = mViewPager.getMeasuredHeight();
        mViewPager.layout(0, y, width, y + viewPagerHeight);
        y += viewPagerHeight;

        mTabStrip.layout(0, y, width, y + mTabStrip.getMeasuredHeight());
    }

    void onChooserChanged() {
        if (mFullScreen) {
            setDesiredHeight(getDesiredHeight(), true);
        }
    }

    void setFullScreenOnly(boolean fullScreenOnly) {
        mFullScreenOnly = fullScreenOnly;
    }

    boolean isFullScreen() {
        return mFullScreen;
    }

    void setMediaPicker(final MediaPicker mediaPicker) {
        mMediaPicker = mediaPicker;
    }

    /**
     * Get the desired height of the media picker panel for when the panel is not in motion (i.e.
     * not being dragged by the user).
     */
    private int getDesiredHeight() {
        if (mFullScreen) {
            int fullHeight = getContext().getResources().getDisplayMetrics().heightPixels;
            if (isAttachedToWindow()) {
                // When we're attached to the window, we can get an accurate height, not necessary
                // on older API level devices because they don't include the action bar height
                View composeContainer =
                        getRootView().findViewById(R.id.conversation_and_compose_container);
                if (composeContainer != null) {
                    // protect against composeContainer having been unloaded already
                    fullHeight -= UiUtils.getMeasuredBoundsOnScreen(composeContainer).top;
                }
            }
            if (mMediaPicker.getChooserShowsActionBarInFullScreen()) {
                return fullHeight - mActionBarHeight;
            } else {
                return fullHeight;
            }
        } else if (mExpanded) {
            return LayoutParams.WRAP_CONTENT;
        } else {
            return 0;
        }
    }

    private void setupViewPager(final int startingPage) {
        mViewPager.setVisibility(View.VISIBLE);
        if (startingPage >= 0 && startingPage < mMediaPicker.getPagerAdapter().getCount()) {
            mViewPager.setAdapter(mMediaPicker.getPagerAdapter());
            mViewPager.setCurrentItem(startingPage);
        }
        updateViewPager();
    }

    /**
     * Expand the media picker panel. Since we always set the pager adapter to null when the panel
     * is collapsed, we need to restore the adapter and the starting page.
     * @param expanded expanded or collapsed
     * @param animate need animation
     * @param startingPage the desired selected page to start
     */
    void setExpanded(final boolean expanded, final boolean animate, final int startingPage) {
        setExpanded(expanded, animate, startingPage, false /* force */);
    }

    private void setExpanded(final boolean expanded, final boolean animate, final int startingPage,
            final boolean force) {
        if (expanded == mExpanded && !force) {
            return;
        }
        mFullScreen = false;
        mExpanded = expanded;
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                setDesiredHeight(getDesiredHeight(), animate);
            }
        });
        if (expanded) {
            setupViewPager(startingPage);
            mMediaPicker.dispatchOpened();
        } else {
            mMediaPicker.dispatchDismissed();
        }

        // Call setFullScreenView() when we are in landscape mode so it can go full screen as
        // soon as it is expanded.
        if (expanded && requiresFullScreen()) {
            setFullScreenView(true, animate);
        }
    }

    private boolean requiresFullScreen() {
        return mFullScreenOnly || UiUtils.isLandscapeMode();
    }

    private void setDesiredHeight(int height, final boolean animate) {
        final int startHeight = mCurrentDesiredHeight;
        if (height == LayoutParams.WRAP_CONTENT) {
            height = measureHeight();
        }
        clearAnimation();
        if (animate) {
            final int deltaHeight = height - startHeight;
            final Animation animation = new Animation() {
                @Override
                protected void applyTransformation(final float interpolatedTime,
                        final Transformation t) {
                    mCurrentDesiredHeight = (int) (startHeight + deltaHeight * interpolatedTime);
                    requestLayout();
                }

                @Override
                public boolean willChangeBounds() {
                    return true;
                }
            };
            animation.setDuration(UiUtils.MEDIAPICKER_TRANSITION_DURATION);
            animation.setInterpolator(UiUtils.EASE_OUT_INTERPOLATOR);
            startAnimation(animation);
        } else {
            mCurrentDesiredHeight = height;
        }
        requestLayout();
    }

    /**
     * @return The minimum total height of the view
     */
    private int measureHeight() {
        final int measureSpec = MeasureSpec.makeMeasureSpec(Integer.MAX_VALUE, MeasureSpec.AT_MOST);
        measureChild(mTabStrip, measureSpec, measureSpec);
        return mDefaultViewPagerHeight + mTabStrip.getMeasuredHeight();
    }

    /**
     * Enters or leaves full screen view
     *
     * @param fullScreen True to enter full screen view, false to leave
     * @param animate    True to animate the transition
     */
    void setFullScreenView(final boolean fullScreen, final boolean animate) {
        if (fullScreen == mFullScreen) {
            return;
        }

        if (requiresFullScreen() && !fullScreen) {
            setExpanded(false /* expanded */, true /* animate */, PAGE_NOT_SET);
            return;
        }
        mFullScreen = fullScreen;
        setDesiredHeight(getDesiredHeight(), animate);
        mMediaPicker.dispatchFullScreen(mFullScreen);
        updateViewPager();
    }

    /**
     * ViewPager should have its paging disabled when in full screen mode.
     */
    private void updateViewPager() {
        mViewPager.setPagingEnabled(!mFullScreen);
    }

    @Override
    public boolean onInterceptTouchEvent(final MotionEvent ev) {
        return mTouchHandler.onInterceptTouchEvent(ev) || super.onInterceptTouchEvent(ev);
    }

    /**
     * Helper class to handle touch events and swipe gestures
     */
    private class TouchHandler implements OnTouchListener {
        /**
         * The height of the view when the touch press started
         */
        private int mDownHeight = -1;

        /**
         * True if the panel moved at all (changed height) during the drag
         */
        private boolean mMoved = false;

        // The threshold constants converted from DP to px
        private final float mFlingThresholdPx;
        private final float mBigFlingThresholdPx;

        // The system defined pixel size to determine when a movement is considered a drag.
        private final int mTouchSlop;

        /**
         * A copy of the MotionEvent that started the drag/swipe gesture
         */
        private MotionEvent mDownEvent;

        /**
         * Whether we are currently moving down. We may not be able to move down in full screen
         * mode when the child view can swipe down (such as a list view).
         */
        private boolean mMovedDown = false;

        /**
         * Indicates whether the child view contained in the panel can swipe down at the beginning
         * of the drag event (i.e. the initial down). The MediaPanel can contain
         * scrollable children such as a list view / grid view. If the child view can swipe down,
         * We want to let the child view handle the scroll first instead of handling it ourselves.
         */
        private boolean mCanChildViewSwipeDown = false;

        /**
         * Necessary direction ratio for a fling to be considered in one direction this prevents
         * horizontal swipes with small vertical components from triggering vertical swipe actions
         */
        private static final float DIRECTION_RATIO = 1.1f;

        TouchHandler() {
            final Resources resources = getContext().getResources();
            final ViewConfiguration configuration = ViewConfiguration.get(getContext());
            mFlingThresholdPx = resources.getDimensionPixelSize(
                    R.dimen.mediapicker_fling_threshold);
            mBigFlingThresholdPx = resources.getDimensionPixelSize(
                    R.dimen.mediapicker_big_fling_threshold);
            mTouchSlop = configuration.getScaledTouchSlop();
        }

        /**
         * The media picker panel may contain scrollable children such as a GridView, which eats
         * all touch events before we get to it. Therefore, we'd like to intercept these events
         * before the children to determine if we should handle swiping down in full screen mode.
         * In non-full screen mode, we should handle all vertical scrolling events and leave
         * horizontal scrolling to the view pager.
         */
        public boolean onInterceptTouchEvent(final MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // Never capture the initial down, so that the children may handle it
                    // as well. Let the touch handler know about the down event as well.
                    mTouchHandler.onTouch(MediaPickerPanel.this, ev);

                    // Ask the MediaPicker whether the contained view can be swiped down.
                    // We record the value at the start of the drag to decide the swiping mode
                    // for the entire motion.
                    mCanChildViewSwipeDown = mMediaPicker.canSwipeDownChooser();
                    return false;

                case MotionEvent.ACTION_MOVE: {
                    if (mMediaPicker.isChooserHandlingTouch()) {
                        if (shouldAllowRecaptureTouch(ev)) {
                            mMediaPicker.stopChooserTouchHandling();
                            mViewPager.setPagingEnabled(true);
                            return false;
                        }
                        // If the chooser is claiming ownership on all touch events, then we
                        // shouldn't try to handle them (neither should the view pager).
                        mViewPager.setPagingEnabled(false);
                        return false;
                    } else if (mCanChildViewSwipeDown) {
                        // Never capture event if the child view can swipe down.
                        return false;
                    } else if (!mFullScreen && mMoved) {
                        // When we are not fullscreen, we own any vertical drag motion.
                        return true;
                    } else if (mMovedDown) {
                        // We are currently handling the down swipe ourselves, so always
                        // capture this event.
                        return true;
                    } else {
                        // The current interaction mode is undetermined, so always let the
                        // touch handler know about this event. However, don't capture this
                        // event so the child may handle it as well.
                        mTouchHandler.onTouch(MediaPickerPanel.this, ev);

                        // Capture the touch event from now on if we are handling the drag.
                        return mFullScreen ? mMovedDown : mMoved;
                    }
                }
            }
            return false;
        }

        /**
         * Determine whether we think the user is actually trying to expand or slide despite the
         * fact that they touched first on a chooser that captured the input.
         */
        private boolean shouldAllowRecaptureTouch(MotionEvent ev) {
            final long elapsedMs = ev.getEventTime() - ev.getDownTime();
            if (mDownEvent == null || elapsedMs == 0 || elapsedMs > TOUCH_RECAPTURE_WINDOW_MS) {
                // Either we don't have info to decide or it's been long enough that we no longer
                // want to reinterpret user intent.
                return false;
            }
            final float dx = ev.getRawX() - mDownEvent.getRawX();
            final float dy = ev.getRawY() - mDownEvent.getRawY();
            final float dt = elapsedMs / 1000.0f;
            final float maxAbsDelta = Math.max(Math.abs(dx), Math.abs(dy));
            final float velocity = maxAbsDelta / dt;
            return velocity > mFlingThresholdPx;
        }

        @Override
        public boolean onTouch(final View view, final MotionEvent motionEvent) {
            switch (motionEvent.getAction()) {
                case MotionEvent.ACTION_UP: {
                    if (!mMoved || mDownEvent == null) {
                        return false;
                    }
                    final float dx = motionEvent.getRawX() - mDownEvent.getRawX();
                    final float dy = motionEvent.getRawY() - mDownEvent.getRawY();

                    final float dt =
                            (motionEvent.getEventTime() - mDownEvent.getEventTime()) / 1000.0f;
                    final float yVelocity = dy / dt;

                    boolean handled = false;

                    // Vertical swipe occurred if the direction is as least mostly in the y
                    // component and has the required velocity (px/sec)
                    if ((dx == 0 || (Math.abs(dy) / Math.abs(dx)) > DIRECTION_RATIO) &&
                            Math.abs(yVelocity) > mFlingThresholdPx) {
                        if (yVelocity < 0 && mExpanded) {
                            setFullScreenView(true, true);
                            handled = true;
                        } else if (yVelocity > 0) {
                            if (mFullScreen && yVelocity < mBigFlingThresholdPx) {
                                setFullScreenView(false, true);
                            } else {
                                setExpanded(false, true, PAGE_NOT_SET);
                            }
                            handled = true;
                        }
                    }

                    if (!handled) {
                        // If they didn't swipe enough, animate back to resting state
                        setDesiredHeight(getDesiredHeight(), true);
                    }
                    resetState();
                    break;
                }
                case MotionEvent.ACTION_DOWN: {
                    mDownHeight = getHeight();
                    mDownEvent = MotionEvent.obtain(motionEvent);
                    // If we are here and care about the return value (i.e. this is not called
                    // from onInterceptTouchEvent), then presumably no children view in the panel
                    // handles the down event. We'd like to handle future ACTION_MOVE events, so
                    // always claim ownership on this event so it doesn't fall through and gets
                    // cancelled by the framework.
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (mDownEvent == null) {
                        return mMoved;
                    }

                    final float dx = mDownEvent.getRawX() - motionEvent.getRawX();
                    final float dy = mDownEvent.getRawY() - motionEvent.getRawY();
                    // Don't act if the move is mostly horizontal
                    if (Math.abs(dy) > mTouchSlop &&
                            (Math.abs(dy) / Math.abs(dx)) > DIRECTION_RATIO) {
                        setDesiredHeight((int) (mDownHeight + dy), false);
                        mMoved = true;
                        if (dy < -mTouchSlop) {
                            mMovedDown = true;
                        }
                    }
                    return mMoved;
                }

            }
            return mMoved;
        }

        private void resetState() {
            mDownEvent = null;
            mDownHeight = -1;
            mMoved = false;
            mMovedDown = false;
            mCanChildViewSwipeDown = false;
            updateViewPager();
        }
    }
}

