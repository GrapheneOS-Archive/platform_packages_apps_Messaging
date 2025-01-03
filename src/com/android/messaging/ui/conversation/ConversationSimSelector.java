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
import android.text.TextUtils;

import com.android.messaging.Factory;
import com.android.messaging.R;
import com.android.messaging.datamodel.data.SubscriptionListData;
import com.android.messaging.datamodel.data.SubscriptionListData.SubscriptionListEntry;
import com.android.messaging.ui.conversation.SimSelectorView.SimSelectorViewListener;
import com.android.messaging.util.AccessibilityUtil;
import com.android.messaging.util.ThreadUtil;

import androidx.core.util.Pair;

/**
 * Manages showing/hiding the SIM selector in conversation.
 */
abstract class ConversationSimSelector extends ConversationInput {
    private SimSelectorView mSimSelectorView;
    private Pair<Boolean /* show */, Boolean /* animate */> mPendingShow;
    private boolean mDataReady;
    private String mSelectedSimText;

    public ConversationSimSelector(ConversationInputBase baseHost) {
        super(baseHost, false);
    }

    public void onSubscriptionListDataLoaded(final SubscriptionListData subscriptionListData) {
        ensureSimSelectorView();
        mSimSelectorView.bind(subscriptionListData);
        mDataReady = subscriptionListData != null && subscriptionListData.hasData();
        if (mPendingShow != null && mDataReady) {
            final boolean show = mPendingShow.first;
            final boolean animate = mPendingShow.second;
            ThreadUtil.getMainThreadHandler().post(new Runnable() {
                @Override
                public void run() {
                    // This will No-Op if we are no longer attached to the host.
                    mConversationInputBase.showHideInternal(ConversationSimSelector.this,
                            show, animate);
                }
            });
            mPendingShow = null;
        }
    }

    private void announcedSelectedSim() {
        final Context context = Factory.get().getApplicationContext();
        if (AccessibilityUtil.isTouchExplorationEnabled(context) &&
                !TextUtils.isEmpty(mSelectedSimText)) {
            AccessibilityUtil.announceForAccessibilityCompat(
                    mSimSelectorView, null,
                    context.getString(R.string.selected_sim_content_message, mSelectedSimText));
        }
    }

    public void setSelected(final SubscriptionListEntry subEntry) {
        mSelectedSimText = subEntry == null ? null : subEntry.displayName;
    }

    @Override
    public boolean show(boolean animate) {
        announcedSelectedSim();
        return showHide(true, animate);
    }

    @Override
    public boolean hide(boolean animate) {
        return showHide(false, animate);
    }

    private boolean showHide(final boolean show, final boolean animate) {
        if (mDataReady) {
            mSimSelectorView.showOrHide(show, animate);
            return mSimSelectorView.isOpen() == show;
        } else {
            mPendingShow = Pair.create(show, animate);
            return false;
        }
    }

    private void ensureSimSelectorView() {
        if (mSimSelectorView == null) {
            // Grab the SIM selector view from the host. This class assumes ownership of it.
            mSimSelectorView = getSimSelectorView();
            mSimSelectorView.setItemLayoutId(getSimSelectorItemLayoutId());
            mSimSelectorView.setListener(new SimSelectorViewListener() {

                @Override
                public void onSimSelectorVisibilityChanged(boolean visible) {
                    onVisibilityChanged(visible);
                }

                @Override
                public void onSimItemClicked(SubscriptionListEntry item) {
                    selectSim(item);
                }
            });
        }
    }

    protected abstract SimSelectorView getSimSelectorView();
    protected abstract void selectSim(final SubscriptionListEntry item);
    protected abstract int getSimSelectorItemLayoutId();

}
