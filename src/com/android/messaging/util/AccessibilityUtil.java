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
package com.android.messaging.util;

import android.content.Context;
import android.content.res.Resources;
import android.text.TextUtils;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

import com.android.messaging.Factory;
import com.android.messaging.R;

import javax.annotation.Nullable;

public class AccessibilityUtil {
    public static String sContentDescriptionDivider;

    public static boolean isTouchExplorationEnabled(final Context context) {
        final AccessibilityManager accessibilityManager = (AccessibilityManager)
                context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        return accessibilityManager.isTouchExplorationEnabled();
    }

    public static StringBuilder appendContentDescription(final Context context,
            final StringBuilder contentDescription, final String val) {
        if (sContentDescriptionDivider == null) {
            sContentDescriptionDivider =
                    context.getResources().getString(R.string.enumeration_comma);
        }
        if (contentDescription.length() != 0) {
            contentDescription.append(sContentDescriptionDivider);
        }
        contentDescription.append(val);
        return contentDescription;
    }

    public static void announceForAccessibilityCompat(
            final View view, @Nullable final AccessibilityManager accessibilityManager,
            final int textResourceId) {
        final String text = Factory.get().getApplicationContext().getResources().getString(
                textResourceId);
        announceForAccessibilityCompat(view, accessibilityManager, text);
    }

    public static void announceForAccessibilityCompat(
            final View view, @Nullable AccessibilityManager accessibilityManager,
            final CharSequence text) {
        final Context context = view.getContext().getApplicationContext();
        if (accessibilityManager == null) {
            accessibilityManager = (AccessibilityManager) context.getSystemService(
                    Context.ACCESSIBILITY_SERVICE);
        }

        if (!accessibilityManager.isEnabled()) {
            return;
        }

        // Construct an accessibility event with the minimum recommended
        // attributes. An event without a class name or package may be dropped.
        final AccessibilityEvent event = new AccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT);
        event.getText().add(text);
        event.setEnabled(view.isEnabled());
        event.setClassName(view.getClass().getName());
        event.setPackageName(context.getPackageName());
        event.setSource(view);

        // Sends the event directly through the accessibility manager. If we only supported SDK 14+
        // we could have done:
        // getParent().requestSendAccessibilityEvent(this, event);
        accessibilityManager.sendAccessibilityEvent(event);
    }

    /**
     * Check to see if the current layout is Right-to-Left. This check is only supported for
     * API 17+.
     * For earlier versions, this method will just return false.
     * @return boolean Boolean indicating whether the currently locale is RTL.
     */
    public static boolean isLayoutRtl(final View view) {
        return View.LAYOUT_DIRECTION_RTL == view.getLayoutDirection();
    }

    public static String getVocalizedPhoneNumber(final Resources res, final String phoneNumber) {
        if (TextUtils.isEmpty(phoneNumber)) {
            return "";
        }
        final StringBuilder vocalizedPhoneNumber = new StringBuilder();
        for (final char c : phoneNumber.toCharArray()) {
            getVocalizedNumber(res, c, vocalizedPhoneNumber);
        }
        return vocalizedPhoneNumber.toString();
    }

    public static void getVocalizedNumber(final Resources res, final char c,
            final StringBuilder builder) {
        switch (c) {
            case '0':
                builder.append(res.getString(R.string.content_description_for_number_zero));
                builder.append(" ");
                return;
            case '1':
                builder.append(res.getString(R.string.content_description_for_number_one));
                builder.append(" ");
                return;
            case '2':
                builder.append(res.getString(R.string.content_description_for_number_two));
                builder.append(" ");
                return;
            case '3':
                builder.append(res.getString(R.string.content_description_for_number_three));
                builder.append(" ");
                return;
            case '4':
                builder.append(res.getString(R.string.content_description_for_number_four));
                builder.append(" ");
                return;
            case '5':
                builder.append(res.getString(R.string.content_description_for_number_five));
                builder.append(" ");
                return;
            case '6':
                builder.append(res.getString(R.string.content_description_for_number_six));
                builder.append(" ");
                return;
            case '7':
                builder.append(res.getString(R.string.content_description_for_number_seven));
                builder.append(" ");
                return;
            case '8':
                builder.append(res.getString(R.string.content_description_for_number_eight));
                builder.append(" ");
                return;
            case '9':
                builder.append(res.getString(R.string.content_description_for_number_nine));
                builder.append(" ");
                return;
            default:
                builder.append(c);
                return;
        }
    }
}
