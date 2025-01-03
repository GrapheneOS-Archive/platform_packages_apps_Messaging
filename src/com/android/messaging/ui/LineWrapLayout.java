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
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.android.messaging.util.UiUtils;

import java.util.ArrayList;

/**
* A line-wrapping flow layout. Arranges children in horizontal flow, packing as many
* child views as possible on each line. When the current line does not
* have enough horizontal space, the layout continues on the next line.
*/
public class LineWrapLayout extends ViewGroup {
    public LineWrapLayout(Context context) {
        this(context, null);
    }

    public LineWrapLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int startPadding = UiUtils.getPaddingStart(this);
        final int endPadding = UiUtils.getPaddingEnd(this);
        final int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        final int widthSize = MeasureSpec.getSize(widthMeasureSpec) - startPadding - endPadding;
        final boolean isFixedSize = (widthMode == MeasureSpec.EXACTLY);

        int height = 0;

        int childCount = getChildCount();
        int childWidthSpec = MeasureSpec.makeMeasureSpec(widthSize, MeasureSpec.AT_MOST);

        int x = startPadding;
        int currLineWidth = 0;
        int currLineHeight = 0;
        int maxLineWidth = 0;

        for (int i = 0; i < childCount; i++) {
            View currChild = getChildAt(i);
            if (currChild.getVisibility() == GONE) {
                continue;
            }
            var layoutParams = (FrameLayout.LayoutParams) currChild.getLayoutParams();
            int startMargin = layoutParams.getMarginStart();
            int endMargin = layoutParams.getMarginEnd();
            currChild.measure(childWidthSpec, MeasureSpec.UNSPECIFIED);
            int childMeasuredWidth = currChild.getMeasuredWidth() + startMargin + endMargin;
            int childMeasuredHeight = currChild.getMeasuredHeight() + layoutParams.topMargin +
                    layoutParams.bottomMargin;

            if ((x + childMeasuredWidth) > widthSize) {
                // New line. Update the overall height and reset trackers.
                height += currLineHeight;
                currLineHeight = 0;
                x = startPadding;
                currLineWidth = 0;
                startMargin = 0;
            }

            x += childMeasuredWidth;
            currLineWidth += childMeasuredWidth;
            currLineHeight = Math.max(currLineHeight, childMeasuredHeight);
            maxLineWidth = Math.max(currLineWidth, maxLineWidth);
        }
        // And account for the height of the last line.
        height += currLineHeight;

        int width = isFixedSize ? widthSize : maxLineWidth;
        setMeasuredDimension(width + startPadding + endPadding,
                height + getPaddingTop() + getPaddingBottom());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        final int startPadding = UiUtils.getPaddingStart(this);
        final int endPadding = UiUtils.getPaddingEnd(this);
        int width = getWidth() - startPadding - endPadding;
        int y = getPaddingTop();
        int x = startPadding;
        int childCount = getChildCount();

        int currLineHeight = 0;

        // Do a dry-run first to get the line heights.
        final ArrayList<Integer> lineHeights = new ArrayList<Integer>();
        for (int i = 0; i < childCount; i++) {
            View currChild = getChildAt(i);
            if (currChild.getVisibility() == GONE) {
                continue;
            }
            var layoutParams = (FrameLayout.LayoutParams) currChild.getLayoutParams();
            int childWidth = currChild.getMeasuredWidth();
            int childHeight = currChild.getMeasuredHeight();
            int startMargin = layoutParams.getMarginStart();
            int endMargin = layoutParams.getMarginEnd();

            if ((x + childWidth + startMargin + endMargin) > width) {
                // new line
                lineHeights.add(currLineHeight);
                currLineHeight = 0;
                x = startPadding;
                startMargin = 0;
            }
            currLineHeight = Math.max(currLineHeight, childHeight + layoutParams.topMargin +
                    layoutParams.bottomMargin);
            x += childWidth + startMargin + endMargin;
        }
        // Add the last line height.
        lineHeights.add(currLineHeight);

        // Now perform the actual layout.
        x = startPadding;
        currLineHeight = 0;
        int lineIndex = 0;
        for (int i = 0; i < childCount; i++) {
            View currChild = getChildAt(i);
            if (currChild.getVisibility() == GONE) {
                continue;
            }
            var layoutParams = (FrameLayout.LayoutParams) currChild.getLayoutParams();
            int childWidth = currChild.getMeasuredWidth();
            int childHeight = currChild.getMeasuredHeight();
            int startMargin = layoutParams.getMarginStart();
            int endMargin = layoutParams.getMarginEnd();

            if ((x + childWidth + startMargin + endMargin) > width) {
                // new line
                y += currLineHeight;
                currLineHeight = 0;
                x = startPadding;
                startMargin = 0;
                lineIndex++;
            }
            final int startPositionX = x + startMargin;
            int startPositionY = y + layoutParams.topMargin;    // default to top gravity
            final int majorGravity = layoutParams.gravity & Gravity.VERTICAL_GRAVITY_MASK;
            if (majorGravity != Gravity.TOP && lineHeights.size() > lineIndex) {
                final int lineHeight = lineHeights.get(lineIndex);
                switch (majorGravity) {
                    case Gravity.BOTTOM:
                        startPositionY = y + lineHeight - childHeight - layoutParams.bottomMargin;
                        break;

                    case Gravity.CENTER_VERTICAL:
                        startPositionY = y + (lineHeight - childHeight) / 2;
                        break;
                }
            }

            if (getResources().getConfiguration()
                    .getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
                currChild.layout(width - startPositionX - childWidth, startPositionY,
                        width - startPositionX, startPositionY + childHeight);
            } else {
                currChild.layout(startPositionX, startPositionY, startPositionX + childWidth,
                        startPositionY + childHeight);
            }
            currLineHeight = Math.max(currLineHeight, childHeight + layoutParams.topMargin +
                    layoutParams.bottomMargin);
            x += childWidth + startMargin + endMargin;
        }
    }

    @Override
    protected FrameLayout.LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        return new FrameLayout.LayoutParams(p);
    }

    @Override
    public FrameLayout.LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new FrameLayout.LayoutParams(getContext(), attrs);
    }

    @Override
    protected FrameLayout.LayoutParams generateDefaultLayoutParams() {
        return new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
    }
}
