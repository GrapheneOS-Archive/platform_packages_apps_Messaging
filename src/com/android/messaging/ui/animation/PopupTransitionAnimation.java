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

package com.android.messaging.ui.animation;

import android.animation.RectEvaluator;
import android.animation.TypeEvaluator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.Transformation;
import android.widget.PopupWindow;

import com.android.messaging.util.LogUtil;
import com.android.messaging.util.ThreadUtil;
import com.android.messaging.util.UiUtils;

/**
 * Animates viewToAnimate from startRect to the place where it is in the layout,  viewToAnimate
 * should be in its final destination location before startAfterLayoutComplete is called.
 * viewToAnimate will be drawn scaled and offset in a popupWindow.
 * This class handles the case where the viewToAnimate moves during the animation
 */
public class PopupTransitionAnimation extends Animation {
    /** The view we're animating */
    private final View mViewToAnimate;

    /** The rect to start the slide in animation from */
    private final Rect mStartRect;

    /** The rect of the currently animated view */
    private Rect mCurrentRect;

    /** The rect that we're animating to.  This can change during the animation */
    private final Rect mDestRect;

    /** The bounds of the popup in window coordinates.  Does not include notification bar */
    private final Rect mPopupRect;

    /** The bounds of the action bar in window coordinates.  We clip the popup to below this */
    private final Rect mActionBarRect;

    /** Interpolates between the start and end rect for every animation tick */
    private final TypeEvaluator<Rect> mRectEvaluator;

    /** The popup window that holds contains the animating view */
    private PopupWindow mPopupWindow;

    /** The layout root for the popup which is where the animated view is rendered */
    private View mPopupRoot;

    /** The action bar's view */
    private final View mActionBarView;

    private Runnable mOnStartCallback;
    private Runnable mOnStopCallback;

    public PopupTransitionAnimation(final Rect startRect, final View viewToAnimate) {
        mViewToAnimate = viewToAnimate;
        mStartRect = startRect;
        mCurrentRect = new Rect(mStartRect);
        mDestRect = new Rect();
        mPopupRect = new Rect();
        mActionBarRect = new Rect();
        mActionBarView = viewToAnimate.getRootView().findViewById(
                androidx.appcompat.R.id.action_bar);
        mRectEvaluator = new RectEvaluator();
        setDuration(UiUtils.MEDIAPICKER_TRANSITION_DURATION);
        setInterpolator(UiUtils.DEFAULT_INTERPOLATOR);
        setAnimationListener(new AnimationListener() {
            @Override
            public void onAnimationStart(final Animation animation) {
                if (mOnStartCallback != null) {
                    mOnStartCallback.run();
                }
                mEvents.append("oAS,");
            }

            @Override
            public void onAnimationEnd(final Animation animation) {
                if (mOnStopCallback != null) {
                    mOnStopCallback.run();
                }
                dismiss();
                mEvents.append("oAE,");
            }

            @Override
            public void onAnimationRepeat(final Animation animation) {
            }
        });
    }

    private final StringBuilder mEvents = new StringBuilder();
    private final Runnable mCleanupRunnable = new Runnable() {
        @Override
        public void run() {
            LogUtil.w(LogUtil.BUGLE_TAG, "PopupTransitionAnimation: " + mEvents);
        }
    };

    /**
     * Ensures the animation is ready before starting the animation.
     * viewToAnimate must first be layed out so we know where we will animate to
     */
    public void startAfterLayoutComplete() {
        // We want layout to occur, and then we immediately animate it in, so hide it initially to
        // reduce jank on the first frame
        mViewToAnimate.setVisibility(View.INVISIBLE);
        mViewToAnimate.setAlpha(0);

        final Runnable startAnimation = new Runnable() {
            boolean mRunComplete = false;
            boolean mFirstTry = true;

            @Override
            public void run() {
                if (mRunComplete) {
                    return;
                }

                mViewToAnimate.getGlobalVisibleRect(mDestRect);
                // In Android views which are visible but haven't computed their size yet have a
                // size of 1x1 because anything with a size of 0x0 is considered hidden.  We can't
                // start the animation until after the size is greater than 1x1
                if (mDestRect.width() <= 1 || mDestRect.height() <= 1) {
                    // Layout hasn't occurred yet
                    if (!mFirstTry) {
                        // Give up if this is not the first try, since layout change still doesn't
                        // yield a size for the view. This is likely because the media picker is
                        // full screen so there's no space left for the animated view. We give up
                        // on animation, but need to make sure the view that was initially
                        // hidden is re-shown.
                        mViewToAnimate.setAlpha(1);
                        mViewToAnimate.setVisibility(View.VISIBLE);
                    } else {
                        mFirstTry = false;
                        UiUtils.doOnceAfterLayoutChange(mViewToAnimate, this);
                    }
                    return;
                }

                mRunComplete = true;
                mViewToAnimate.startAnimation(PopupTransitionAnimation.this);
                mViewToAnimate.invalidate();
                // http://b/20856505: The PopupWindow sometimes does not get dismissed.
                ThreadUtil.getMainThreadHandler().postDelayed(mCleanupRunnable, getDuration() * 2);
            }
        };

        startAnimation.run();
    }

    public PopupTransitionAnimation setOnStartCallback(final Runnable onStart) {
        mOnStartCallback = onStart;
        return this;
    }

    public PopupTransitionAnimation setOnStopCallback(final Runnable onStop) {
        mOnStopCallback = onStop;
        return this;
    }

    @Override
    protected void applyTransformation(final float interpolatedTime, final Transformation t) {
        if (mPopupWindow == null) {
            initPopupWindow();
        }
        // Update mDestRect as it may have moved during the animation
        mPopupRect.set(UiUtils.getMeasuredBoundsOnScreen(mPopupRoot));
        mActionBarRect.set(UiUtils.getMeasuredBoundsOnScreen(mActionBarView));
        computeDestRect();

        // Update currentRect to the new animated coordinates, and request mPopupRoot to redraw
        // itself at the new coordinates
        mCurrentRect = mRectEvaluator.evaluate(interpolatedTime, mStartRect, mDestRect);
        mPopupRoot.invalidate();

        if (interpolatedTime >= 0.98) {
            mEvents.append("aT").append(interpolatedTime).append(',');
        }
        if (interpolatedTime == 1) {
            dismiss();
        }
    }

    private void dismiss() {
        mEvents.append("d,");
        mViewToAnimate.setAlpha(1);
        mViewToAnimate.setVisibility(View.VISIBLE);
        // Delay dismissing the popup window to let mViewToAnimate draw under it and reduce the
        // flash
        ThreadUtil.getMainThreadHandler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    mPopupWindow.dismiss();
                } catch (IllegalArgumentException e) {
                    // PopupWindow.dismiss() will fire an IllegalArgumentException if the activity
                    // has already ended while we were animating
                }
                ThreadUtil.getMainThreadHandler().removeCallbacks(mCleanupRunnable);
            }
        });
    }

    @Override
    public boolean willChangeBounds() {
        return false;
    }

    /**
     * Computes mDestRect (the position in window space of the placeholder view that we should
     * animate to).  Some frames during the animation fail to compute getGlobalVisibleRect, so use
     * the last known values in that case
     */
    private void computeDestRect() {
        final int prevTop = mDestRect.top;
        final int prevLeft = mDestRect.left;
        final int prevRight = mDestRect.right;
        final int prevBottom = mDestRect.bottom;

        if (!getViewScreenMeasureRect(mViewToAnimate, mDestRect)) {
            mDestRect.top = prevTop;
            mDestRect.left = prevLeft;
            mDestRect.bottom = prevBottom;
            mDestRect.right = prevRight;
        }
    }

    /**
     * Sets up the PopupWindow that the view will animate in.  Animating the size and position of a
     * popup can be choppy, so instead we make the popup fill the entire space of the screen, and
     * animate the position of viewToAnimate within the popup using a Transformation
     */
    private void initPopupWindow() {
        mPopupRoot = new View(mViewToAnimate.getContext()) {
            @Override
            protected void onDraw(final Canvas canvas) {
                canvas.save();
                canvas.clipRect(getLeft(), mActionBarRect.bottom - mPopupRect.top, getRight(),
                        getBottom());
                canvas.drawColor(Color.TRANSPARENT);
                final float previousAlpha = mViewToAnimate.getAlpha();
                mViewToAnimate.setAlpha(1);
                // The view's global position includes the notification bar height, but
                // the popup window may or may not cover the notification bar (depending on screen
                // rotation, IME status etc.), so we need to compensate for this difference by
                // offseting vertically.
                canvas.translate(mCurrentRect.left, mCurrentRect.top - mPopupRect.top);

                final float viewWidth = mViewToAnimate.getWidth();
                final float viewHeight = mViewToAnimate.getHeight();
                if (viewWidth > 0 && viewHeight > 0) {
                    canvas.scale(mCurrentRect.width() / viewWidth,
                            mCurrentRect.height() / viewHeight);
                }
                canvas.clipRect(0, 0, mCurrentRect.width(), mCurrentRect.height());
                if (!mPopupRect.isEmpty()) {
                    // HACK: Layout is unstable until mPopupRect is non-empty.
                    mViewToAnimate.draw(canvas);
                }
                mViewToAnimate.setAlpha(previousAlpha);
                canvas.restore();
            }
        };
        mPopupWindow = new PopupWindow(mViewToAnimate.getContext());
        mPopupWindow.setBackgroundDrawable(null);
        mPopupWindow.setContentView(mPopupRoot);
        mPopupWindow.setWidth(ViewGroup.LayoutParams.MATCH_PARENT);
        mPopupWindow.setHeight(ViewGroup.LayoutParams.MATCH_PARENT);
        mPopupWindow.setTouchable(false);
        // We must pass a non-zero value for the y offset, or else the system resets the status bar
        // color to black (M only) during the animation. The actual position of the window (and
        // the animated view inside it) are still correct, regardless of what we pass for the y
        // parameter (e.g. 1 and 100 both work). Not entirely sure why this works.
        mPopupWindow.showAtLocation(mViewToAnimate, Gravity.TOP, 0, 1);
    }

    private static boolean getViewScreenMeasureRect(final View view, final Rect outRect) {
        outRect.set(UiUtils.getMeasuredBoundsOnScreen(view));
        return !outRect.isEmpty();
    }
}
