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

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroupOverlay;
import android.view.ViewOverlay;
import android.widget.FrameLayout;

import com.android.messaging.R;
import com.android.messaging.util.ImageUtils;
import com.android.messaging.util.UiUtils;

import androidx.core.view.ViewCompat;

/**
 * <p>
 * Shows a vertical "explode" animation for any view inside a view group (e.g. views inside a
 * ListView). During the animation, a snapshot is taken for the view to the animated and
 * presented in a popup window or view overlay on top of the original view group. The background
 * of the view (a highlight) vertically expands (explodes) during the animation.
 * </p>
 * <p>
 * The exact implementation of the animation depends on platform API level. For JB_MR2 and later,
 * the implementation utilizes ViewOverlay to perform highly performant overlay animations; for
 * older API levels, the implementation falls back to using a full screen popup window to stage
 * the animation.
 * </p>
 * <p>
 * To start this animation, call {@link #startAnimationForView(ViewGroup, View, View, boolean, int)}
 * </p>
 */
public class ViewGroupItemVerticalExplodeAnimation {
    /**
     * Starts a vertical explode animation for a given view situated in a given container.
     *
     * @param container the container of the view which determines the explode animation's final
     *        size
     * @param viewToAnimate the view to be animated. The view will be highlighted by the explode
     *        highlight, which expands from the size of the view to the size of the container.
     * @param animationStagingView the view that stages the animation. Since viewToAnimate may be
     *        removed from the view tree during the animation, we need a view that'll be alive
     *        for the duration of the animation so that the animation won't get cancelled.
     * @param snapshotView whether a snapshot of the view to animate is needed.
     */
    public static void startAnimationForView(final ViewGroup container, final View viewToAnimate,
            final View animationStagingView, final boolean snapshotView, final int duration) {
        if (viewToAnimate.getContext() instanceof Activity) {
            new ViewExplodeAnimationJellyBeanMR2(viewToAnimate, container, snapshotView, duration)
                .startAnimation();
        }
    }

    private static class ViewExplodeAnimationJellyBeanMR2 {
        private final View mViewToAnimate;
        private final ViewGroup mContainer;
        private final View mSnapshot;
        private final Bitmap mViewBitmap;
        private final int mDuration;

        public ViewExplodeAnimationJellyBeanMR2(final View viewToAnimate, final ViewGroup container,
                final boolean snapshotView, final int duration) {
            mViewToAnimate = viewToAnimate;
            mContainer = container;
            mDuration = duration;
            if (snapshotView) {
                mViewBitmap = snapshotView(viewToAnimate);
                mSnapshot = new View(viewToAnimate.getContext());
            } else {
                mSnapshot = null;
                mViewBitmap = null;
            }
        }

        public void startAnimation() {
            final Context context = mViewToAnimate.getContext();
            final Resources resources = context.getResources();
            final View decorView = ((Activity) context).getWindow().getDecorView();
            final ViewOverlay viewOverlay = decorView.getOverlay();
            if (viewOverlay instanceof ViewGroupOverlay) {
                final ViewGroupOverlay overlay = (ViewGroupOverlay) viewOverlay;

                // Add a shadow layer to the overlay.
                final FrameLayout shadowContainerLayer = new FrameLayout(context);
                final Drawable oldBackground = mViewToAnimate.getBackground();
                final Rect containerRect = UiUtils.getMeasuredBoundsOnScreen(mContainer);
                final Rect decorRect = UiUtils.getMeasuredBoundsOnScreen(decorView);
                // Position the container rect relative to the decor rect since the decor rect
                // defines whether the view overlay will be positioned.
                containerRect.offset(-decorRect.left, -decorRect.top);
                shadowContainerLayer.setLeft(containerRect.left);
                shadowContainerLayer.setTop(containerRect.top);
                shadowContainerLayer.setBottom(containerRect.bottom);
                shadowContainerLayer.setRight(containerRect.right);
                shadowContainerLayer.setBackgroundColor(resources.getColor(
                        R.color.open_conversation_animation_background_shadow));
                // Per design request, temporarily clear out the background of the item content
                // to not show any ripple effects during animation.
                if (!(oldBackground instanceof ColorDrawable)) {
                    mViewToAnimate.setBackground(null);
                }
                overlay.add(shadowContainerLayer);

                // Add a expand layer and position it with in the shadow background, so it can
                // be properly clipped to the container bounds during the animation.
                final View expandLayer = new View(context);
                final int elevation = resources.getDimensionPixelSize(
                        R.dimen.explode_animation_highlight_elevation);
                final Rect viewRect = UiUtils.getMeasuredBoundsOnScreen(mViewToAnimate);
                // Frame viewRect from screen space to containerRect space.
                viewRect.offset(-containerRect.left - decorRect.left,
                        -containerRect.top - decorRect.top);
                // Since the expand layer expands at the same rate above and below, we need to
                // compute the expand scale using the bigger of the top/bottom distances.
                final int expandLayerHalfHeight = viewRect.height() / 2;
                final int topDist = viewRect.top;
                final int bottomDist = containerRect.height() - viewRect.bottom;
                final float scale = expandLayerHalfHeight == 0 ? 1 :
                        ((float) Math.max(topDist, bottomDist) + expandLayerHalfHeight) /
                        expandLayerHalfHeight;
                // Position the expand layer initially to exactly match the animated item.
                shadowContainerLayer.addView(expandLayer);
                expandLayer.setLeft(viewRect.left);
                expandLayer.setTop(viewRect.top);
                expandLayer.setBottom(viewRect.bottom);
                expandLayer.setRight(viewRect.right);
                expandLayer.setBackgroundColor(resources.getColor(
                        R.color.conversation_background));
                ViewCompat.setElevation(expandLayer, elevation);

                // Conditionally stage the snapshot in the overlay.
                if (mSnapshot != null) {
                    shadowContainerLayer.addView(mSnapshot);
                    mSnapshot.setLeft(viewRect.left);
                    mSnapshot.setTop(viewRect.top);
                    mSnapshot.setBottom(viewRect.bottom);
                    mSnapshot.setRight(viewRect.right);
                    mSnapshot.setBackground(new BitmapDrawable(resources, mViewBitmap));
                    ViewCompat.setElevation(mSnapshot, elevation);
                }

                // Apply a scale animation to scale to full screen.
                expandLayer.animate().scaleY(scale)
                    .setDuration(mDuration)
                    .setInterpolator(UiUtils.EASE_IN_INTERPOLATOR)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            // Clean up the views added to overlay on animation finish.
                            overlay.remove(shadowContainerLayer);
                            mViewToAnimate.setBackground(oldBackground);
                            if (mViewBitmap != null) {
                                mViewBitmap.recycle();
                            }
                        }
                });
            }
        }
    }

    /**
     * Take a snapshot of the given review, return a Bitmap object that's owned by the caller.
     */
    static Bitmap snapshotView(final View view) {
        // Save the content of the view into a bitmap.
        final Bitmap viewBitmap = Bitmap.createBitmap(view.getWidth(),
                view.getHeight(), Bitmap.Config.ARGB_8888);
        // Strip the view of its background when taking a snapshot so that things like touch
        // feedback don't get accidentally snapshotted.
        final Drawable viewBackground = view.getBackground();
        ImageUtils.setBackgroundDrawableOnView(view, null);
        view.draw(new Canvas(viewBitmap));
        ImageUtils.setBackgroundDrawableOnView(view, viewBackground);
        return viewBitmap;
    }
}
