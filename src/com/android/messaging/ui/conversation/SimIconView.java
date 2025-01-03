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

import android.content.Context;
import android.graphics.Outline;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;

import com.android.messaging.ui.ContactIconView;

/**
 * Shows SIM avatar icon in the SIM switcher / Self-send button.
 */
public class SimIconView extends ContactIconView {
    public SimIconView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setOval(0, 0, v.getWidth(), v.getHeight());
            }
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (isClickable()) {
            return super.onTouchEvent(event);
        }
        return true;
    }

    @Override
    protected void maybeInitializeOnClickListener() {
        // TODO: SIM icon view shouldn't consume or handle clicks, but it should if
        // this is the send button for the only SIM in the device or if MSIM is not supported.
    }
}
