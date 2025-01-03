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
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.messaging.R;
import com.android.messaging.datamodel.data.ParticipantListItemData;

import androidx.core.text.BidiFormatter;
import androidx.core.text.TextDirectionHeuristicsCompat;

/**
 * View for individual participant in blocked participants list.
 *
 * Unblocks participant when clicked.
 */
public class BlockedParticipantListItemView extends LinearLayout {
    private TextView mNameTextView;
    private ContactIconView mContactIconView;
    private ParticipantListItemData mData;

    public BlockedParticipantListItemView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mNameTextView = findViewById(R.id.name);
        mContactIconView = findViewById(R.id.contact_icon);
        setOnClickListener(v -> mData.unblock(getContext()));
    }

    public void bind(final ParticipantListItemData data) {
        mData = data;
        final BidiFormatter bidiFormatter = BidiFormatter.getInstance();
        mNameTextView.setText(bidiFormatter.unicodeWrap(
                data.getDisplayName(), TextDirectionHeuristicsCompat.LTR));
        mContactIconView.setImageResourceUri(data.getAvatarUri(), data.getContactId(),
                    data.getLookupKey(), data.getNormalizedDestination());
        mNameTextView.setText(data.getDisplayName());
    }
}
