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

package com.android.messaging.ui.conversation;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.util.StateSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.View.OnLayoutChangeListener;
import android.view.ViewGroupOverlay;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.messaging.R;
import com.android.messaging.datamodel.data.ConversationMessageData;
import com.android.messaging.ui.ConversationDrawables;
import com.android.messaging.util.Dates;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.AdapterDataObserver;
import androidx.recyclerview.widget.RecyclerView.ViewHolder;

/**
 * Adds a "fast-scroll" bar to the conversation RecyclerView that shows the current position within
 * the conversation and allows quickly moving to another position by dragging the scrollbar thumb
 * up or down. As the thumb is dragged, we show a floating bubble alongside it that shows the
 * date/time of the first visible message at the current position.
 */
public class ConversationFastScroller extends RecyclerView.OnScrollListener implements
        OnLayoutChangeListener, RecyclerView.OnItemTouchListener {

    /**
     * Creates a {@link ConversationFastScroller} instance, attached to the provided
     * {@link RecyclerView}.
     *
     * @param rv the conversation RecyclerView
     * @param position where the scrollbar should appear (either {@code POSITION_RIGHT_SIDE} or
     *            {@code POSITION_LEFT_SIDE})
     * @return a new ConversationFastScroller, or {@code null} if fast-scrolling is not supported
     *         (the feature requires Jellybean MR2 or newer)
     */
    public static ConversationFastScroller addTo(RecyclerView rv, int position) {
        return new ConversationFastScroller(rv, position);
    }

    public static final int POSITION_RIGHT_SIDE = 0;
    public static final int POSITION_LEFT_SIDE = 1;

    private static final int MIN_PAGES_TO_ENABLE = 7;
    private static final int SHOW_ANIMATION_DURATION_MS = 150;
    private static final int HIDE_ANIMATION_DURATION_MS = 300;
    private static final int HIDE_DELAY_MS = 1500;

    private final Context mContext;
    private final RecyclerView mRv;
    private final ViewGroupOverlay mOverlay;
    private final ImageView mTrackImageView;
    private final ImageView mThumbImageView;
    private final TextView mPreviewTextView;

    private final int mTrackWidth;
    private final int mThumbHeight;
    private final int mPreviewHeight;
    private final int mPreviewMinWidth;
    private final int mPreviewMarginTop;
    private final int mPreviewMarginLeftRight;
    private final int mTouchSlop;

    private final Rect mContainer = new Rect();
    private final Handler mHandler = new Handler();

    // Whether to render the scrollbar on the right side (otherwise it'll be on the left).
    private final boolean mPosRight;

    // Whether the scrollbar is currently visible (it may still be animating).
    private boolean mVisible = false;

    // Whether we are waiting to hide the scrollbar (i.e. scrolling has stopped).
    private boolean mPendingHide = false;

    // Whether the user is currently dragging the thumb up or down.
    private boolean mDragging = false;

    // Animations responsible for hiding the scrollbar & preview. May be null.
    private AnimatorSet mHideAnimation;
    private ObjectAnimator mHidePreviewAnimation;

    private final Runnable mHideTrackRunnable = new Runnable() {
        @Override
        public void run() {
            hide(true /* animate */);
            mPendingHide = false;
        }
    };

    private ConversationFastScroller(RecyclerView rv, int position) {
        mContext = rv.getContext();
        mRv = rv;
        mRv.addOnLayoutChangeListener(this);
        mRv.addOnScrollListener(this);
        mRv.addOnItemTouchListener(this);
        mRv.getAdapter().registerAdapterDataObserver(new AdapterDataObserver() {
            @Override
            public void onChanged() {
                updateScrollPos();
            }
        });
        mPosRight = (position == POSITION_RIGHT_SIDE);

        // Cache the dimensions we'll need during layout
        final Resources res = mContext.getResources();
        mTrackWidth = res.getDimensionPixelSize(R.dimen.fastscroll_track_width);
        mThumbHeight = res.getDimensionPixelSize(R.dimen.fastscroll_thumb_height);
        mPreviewHeight = res.getDimensionPixelSize(R.dimen.fastscroll_preview_height);
        mPreviewMinWidth = res.getDimensionPixelSize(R.dimen.fastscroll_preview_min_width);
        mPreviewMarginTop = res.getDimensionPixelOffset(R.dimen.fastscroll_preview_margin_top);
        mPreviewMarginLeftRight = res.getDimensionPixelOffset(
                R.dimen.fastscroll_preview_margin_left_right);
        mTouchSlop = res.getDimensionPixelOffset(R.dimen.fastscroll_touch_slop);

        final LayoutInflater inflator = LayoutInflater.from(mContext);
        mTrackImageView = (ImageView) inflator.inflate(R.layout.fastscroll_track, null);
        mThumbImageView = (ImageView) inflator.inflate(R.layout.fastscroll_thumb, null);
        mPreviewTextView = (TextView) inflator.inflate(R.layout.fastscroll_preview, null);

        refreshConversationThemeColor();

        // Add the fast scroll views to the overlay, so they are rendered above the list
        mOverlay = rv.getOverlay();
        mOverlay.add(mTrackImageView);
        mOverlay.add(mThumbImageView);
        mOverlay.add(mPreviewTextView);

        hide(false /* animate */);
        mPreviewTextView.setAlpha(0f);
    }

    public void refreshConversationThemeColor() {
        mPreviewTextView.setBackground(
                ConversationDrawables.get().getFastScrollPreviewDrawable(mPosRight));

        final StateListDrawable drawable = new StateListDrawable();
        drawable.addState(new int[]{ android.R.attr.state_pressed },
                ConversationDrawables.get().getFastScrollThumbDrawable(true /* pressed */));
        drawable.addState(StateSet.WILD_CARD,
                ConversationDrawables.get().getFastScrollThumbDrawable(false /* pressed */));
        mThumbImageView.setImageDrawable(drawable);
    }

    @Override
    public void onScrollStateChanged(final RecyclerView view, final int newState) {
        if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
            // Only show the scrollbar once the user starts scrolling
            if (!mVisible && isEnabled()) {
                show();
            }
            cancelAnyPendingHide();
        } else if (newState == RecyclerView.SCROLL_STATE_IDLE && !mDragging) {
            // Hide the scrollbar again after scrolling stops
            hideAfterDelay();
        }
    }

    private boolean isEnabled() {
        final int range = mRv.computeVerticalScrollRange();
        final int extent = mRv.computeVerticalScrollExtent();

        if (range == 0 || extent == 0) {
            return false; // Conversation isn't long enough to scroll
        }
        // Only enable scrollbars for conversations long enough that they would require several
        // flings to scroll through.
        final float pages = (float) range / extent;
        return (pages > MIN_PAGES_TO_ENABLE);
    }

    private void show() {
        if (mHideAnimation != null && mHideAnimation.isRunning()) {
            mHideAnimation.cancel();
        }
        // Slide the scrollbar in from the side
        ObjectAnimator trackSlide = ObjectAnimator.ofFloat(mTrackImageView, View.TRANSLATION_X, 0);
        ObjectAnimator thumbSlide = ObjectAnimator.ofFloat(mThumbImageView, View.TRANSLATION_X, 0);
        AnimatorSet animation = new AnimatorSet();
        animation.playTogether(trackSlide, thumbSlide);
        animation.setDuration(SHOW_ANIMATION_DURATION_MS);
        animation.start();

        mVisible = true;
        updateScrollPos();
    }

    private void hideAfterDelay() {
        cancelAnyPendingHide();
        mHandler.postDelayed(mHideTrackRunnable, HIDE_DELAY_MS);
        mPendingHide = true;
    }

    private void cancelAnyPendingHide() {
        if (mPendingHide) {
            mHandler.removeCallbacks(mHideTrackRunnable);
        }
    }

    private void hide(boolean animate) {
        final int hiddenTranslationX = mPosRight ? mTrackWidth : -mTrackWidth;
        if (animate) {
            // Slide the scrollbar off to the side
            ObjectAnimator trackSlide = ObjectAnimator.ofFloat(mTrackImageView, View.TRANSLATION_X,
                    hiddenTranslationX);
            ObjectAnimator thumbSlide = ObjectAnimator.ofFloat(mThumbImageView, View.TRANSLATION_X,
                    hiddenTranslationX);
            mHideAnimation = new AnimatorSet();
            mHideAnimation.playTogether(trackSlide, thumbSlide);
            mHideAnimation.setDuration(HIDE_ANIMATION_DURATION_MS);
            mHideAnimation.start();
        } else {
            mTrackImageView.setTranslationX(hiddenTranslationX);
            mThumbImageView.setTranslationX(hiddenTranslationX);
        }

        mVisible = false;
    }

    private void showPreview() {
        if (mHidePreviewAnimation != null && mHidePreviewAnimation.isRunning()) {
            mHidePreviewAnimation.cancel();
        }
        mPreviewTextView.setAlpha(1f);
    }

    private void hidePreview() {
        mHidePreviewAnimation = ObjectAnimator.ofFloat(mPreviewTextView, View.ALPHA, 0f);
        mHidePreviewAnimation.setDuration(HIDE_ANIMATION_DURATION_MS);
        mHidePreviewAnimation.start();
    }

    @Override
    public void onScrolled(final RecyclerView view, final int dx, final int dy) {
        updateScrollPos();
    }

    private void updateScrollPos() {
        if (!mVisible) {
            return;
        }
        final int verticalScrollLength = mContainer.height() - mThumbHeight;
        final int verticalScrollStart = mContainer.top + mThumbHeight / 2;

        final float scrollRatio = computeScrollRatio();
        final int thumbCenterY = verticalScrollStart + (int)(verticalScrollLength * scrollRatio);
        layoutThumb(thumbCenterY);

        if (mDragging) {
            updatePreviewText();
            layoutPreview(thumbCenterY);
        }
    }

    /**
     * Returns the current position in the conversation, as a value between 0 and 1, inclusive.
     * The top of the conversation is 0, the bottom is 1, the exact middle is 0.5, and so on.
     */
    private float computeScrollRatio() {
        final int range = mRv.computeVerticalScrollRange();
        final int extent = mRv.computeVerticalScrollExtent();
        int offset = mRv.computeVerticalScrollOffset();

        if (range == 0 || extent == 0) {
            // If the conversation doesn't scroll, we're at the bottom.
            return 1.0f;
        }
        final int scrollRange = range - extent;
        offset = Math.min(offset, scrollRange);
        return offset / (float) scrollRange;
    }

    private void updatePreviewText() {
        final LinearLayoutManager lm = (LinearLayoutManager) mRv.getLayoutManager();
        final int pos = lm.findFirstVisibleItemPosition();
        if (pos == RecyclerView.NO_POSITION) {
            return;
        }
        final ViewHolder vh = mRv.findViewHolderForAdapterPosition(pos);
        if (vh == null) {
            // This can happen if the messages update while we're dragging the thumb.
            return;
        }
        final ConversationMessageView messageView = (ConversationMessageView) vh.itemView;
        final ConversationMessageData messageData = messageView.getData();
        final long timestamp = messageData.getReceivedTimeStamp();
        final CharSequence timestampText = Dates.getFastScrollPreviewTimeString(timestamp);
        mPreviewTextView.setText(timestampText);
    }

    @Override
    public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
        if (!mVisible) {
            return false;
        }
        // If the user presses down on the scroll thumb, we'll start intercepting events from the
        // RecyclerView so we can handle the move events while they're dragging it up/down.
        final int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if (isInsideThumb(e.getX(), e.getY())) {
                    startDrag();
                    return true;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (mDragging) {
                    return true;
                }
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                if (mDragging) {
                    cancelDrag();
                }
                return false;
        }
        return false;
    }

    private boolean isInsideThumb(float x, float y) {
        final int hitTargetLeft = mThumbImageView.getLeft() - mTouchSlop;
        final int hitTargetRight = mThumbImageView.getRight() + mTouchSlop;

        if (x < hitTargetLeft || x > hitTargetRight) {
            return false;
        }
        if (y < mThumbImageView.getTop() || y > mThumbImageView.getBottom()) {
            return false;
        }
        return true;
    }

    @Override
    public void onTouchEvent(RecyclerView rv, MotionEvent e) {
        if (!mDragging) {
            return;
        }
        final int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_MOVE:
                handleDragMove(e.getY());
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                cancelDrag();
                break;
        }
    }

    @Override
    public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {
    }

    private void startDrag() {
        mDragging = true;
        mThumbImageView.setPressed(true);
        updateScrollPos();
        showPreview();
        cancelAnyPendingHide();
    }

    private void handleDragMove(float y) {
        final int verticalScrollLength = mContainer.height() - mThumbHeight;
        final int verticalScrollStart = mContainer.top + (mThumbHeight / 2);

        // Convert the desired position from px to a scroll position in the conversation.
        float dragScrollRatio = (y - verticalScrollStart) / verticalScrollLength;
        dragScrollRatio = Math.max(dragScrollRatio, 0.0f);
        dragScrollRatio = Math.min(dragScrollRatio, 1.0f);

        // Scroll the RecyclerView to a new position.
        final int itemCount = mRv.getAdapter().getItemCount();
        final int itemPos = (int)((itemCount - 1) * dragScrollRatio);
        mRv.scrollToPosition(itemPos);
    }

    private void cancelDrag() {
        mDragging = false;
        mThumbImageView.setPressed(false);
        hidePreview();
        hideAfterDelay();
    }

    @Override
    public void onLayoutChange(View v, int left, int top, int right, int bottom,
            int oldLeft, int oldTop, int oldRight, int oldBottom) {
        if (!mVisible) {
            hide(false /* animate */);
        }
        // The container is the size of the RecyclerView that's visible on screen. We have to
        // exclude the top padding, because it's usually hidden behind the conversation action bar.
        mContainer.set(left, top + mRv.getPaddingTop(), right, bottom);
        layoutTrack();
        updateScrollPos();
    }

    private void layoutTrack() {
        int trackHeight = Math.max(0, mContainer.height());
        int widthMeasureSpec = MeasureSpec.makeMeasureSpec(mTrackWidth, MeasureSpec.EXACTLY);
        int heightMeasureSpec = MeasureSpec.makeMeasureSpec(trackHeight, MeasureSpec.EXACTLY);
        mTrackImageView.measure(widthMeasureSpec, heightMeasureSpec);

        int left = mPosRight ? (mContainer.right - mTrackWidth) : mContainer.left;
        int top = mContainer.top;
        int right = mPosRight ? mContainer.right : (mContainer.left + mTrackWidth);
        int bottom = mContainer.bottom;
        mTrackImageView.layout(left, top, right, bottom);
    }

    private void layoutThumb(int centerY) {
        int widthMeasureSpec = MeasureSpec.makeMeasureSpec(mTrackWidth, MeasureSpec.EXACTLY);
        int heightMeasureSpec = MeasureSpec.makeMeasureSpec(mThumbHeight, MeasureSpec.EXACTLY);
        mThumbImageView.measure(widthMeasureSpec, heightMeasureSpec);

        int left = mPosRight ? (mContainer.right - mTrackWidth) : mContainer.left;
        int top = centerY - (mThumbImageView.getHeight() / 2);
        int right = mPosRight ? mContainer.right : (mContainer.left + mTrackWidth);
        int bottom = top + mThumbHeight;
        mThumbImageView.layout(left, top, right, bottom);
    }

    private void layoutPreview(int centerY) {
        int widthMeasureSpec = MeasureSpec.makeMeasureSpec(mContainer.width(), MeasureSpec.AT_MOST);
        int heightMeasureSpec = MeasureSpec.makeMeasureSpec(mPreviewHeight, MeasureSpec.EXACTLY);
        mPreviewTextView.measure(widthMeasureSpec, heightMeasureSpec);

        // Ensure that the preview bubble is at least as wide as it is tall
        if (mPreviewTextView.getMeasuredWidth() < mPreviewMinWidth) {
            widthMeasureSpec = MeasureSpec.makeMeasureSpec(mPreviewMinWidth, MeasureSpec.EXACTLY);
            mPreviewTextView.measure(widthMeasureSpec, heightMeasureSpec);
        }
        final int previewMinY = mContainer.top + mPreviewMarginTop;

        final int left, right;
        if (mPosRight) {
            right = mContainer.right - mTrackWidth - mPreviewMarginLeftRight;
            left = right - mPreviewTextView.getMeasuredWidth();
        } else {
            left = mContainer.left + mTrackWidth + mPreviewMarginLeftRight;
            right = left + mPreviewTextView.getMeasuredWidth();
        }

        int bottom = centerY;
        int top = bottom - mPreviewTextView.getMeasuredHeight();
        if (top < previewMinY) {
            top = previewMinY;
            bottom = top + mPreviewTextView.getMeasuredHeight();
        }
        mPreviewTextView.layout(left, top, right, bottom);
    }
}
