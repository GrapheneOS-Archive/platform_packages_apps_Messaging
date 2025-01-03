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
package com.android.messaging.datamodel.data;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import com.android.messaging.R;
import com.android.messaging.util.Assert;
import com.android.messaging.util.AvatarUriUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * This is a UI facing data model component that holds a list of
 * {@link SubscriptionListData.SubscriptionListEntry}'s, one for each *active* subscriptions.
 *
 * This is used to:
 * 1) Show a list of SIMs in the SIM Selector
 * 2) Show the currently selected SIM in the compose message view
 * 3) Show SIM indicators on conversation message views
 *
 * It builds on top of SelfParticipantsData and performs additional logic such as determining
 * the set of icons to use for the individual Subs.
 */
public class SubscriptionListData {
    /**
     * Represents a single sub that backs UI.
     */
    public static class SubscriptionListEntry {
        public final String selfParticipantId;
        public final Uri iconUri;
        public final Uri selectedIconUri;
        public final String displayName;
        public final int displayColor;
        public final String displayDestination;

        private SubscriptionListEntry(final String selfParticipantId, final Uri iconUri,
                final Uri selectedIconUri, final String displayName, final int displayColor,
                final String displayDestination) {
            this.selfParticipantId = selfParticipantId;
            this.iconUri = iconUri;
            this.selectedIconUri = selectedIconUri;
            this.displayName = displayName;
            this.displayColor = displayColor;
            this.displayDestination = displayDestination;
        }

        static SubscriptionListEntry fromSelfParticipantData(
                final ParticipantData selfParticipantData, final Context context) {
            Assert.isTrue(selfParticipantData.isSelf());
            Assert.isTrue(selfParticipantData.isActiveSubscription());
            final int slotId = selfParticipantData.getDisplaySlotId();
            final String iconIdentifier = String.format(Locale.getDefault(), "%d", slotId);
            final String subscriptionName = selfParticipantData.getSubscriptionName();
            final String displayName = TextUtils.isEmpty(subscriptionName) ?
                    context.getString(R.string.sim_slot_identifier, Integer.toString(slotId)) :
                    subscriptionName;
            return new SubscriptionListEntry(selfParticipantData.getId(),
                    AvatarUriUtil.createAvatarUri(selfParticipantData, iconIdentifier,
                            false /* selected */, false /* incoming */),
                    AvatarUriUtil.createAvatarUri(selfParticipantData, iconIdentifier,
                            true /* selected */, false /* incoming */),
                    displayName, selfParticipantData.getSubscriptionColor(),
                    selfParticipantData.getDisplayDestination());
        }
    }

    private final List<SubscriptionListEntry> mEntriesExcludingDefault;
    private SubscriptionListEntry mDefaultEntry;
    private final Context mContext;

    public SubscriptionListData(final Context context) {
        mEntriesExcludingDefault = new ArrayList<SubscriptionListEntry>();
        mContext = context;
    }

    public void bind(final List<ParticipantData> subs) {
        mEntriesExcludingDefault.clear();
        mDefaultEntry = null;
        for (final ParticipantData sub : subs) {
            final SubscriptionListEntry entry =
                    SubscriptionListEntry.fromSelfParticipantData(sub, mContext);
            if (!sub.isDefaultSelf()) {
                mEntriesExcludingDefault.add(entry);
            } else {
                mDefaultEntry = entry;
            }
        }
    }

    public List<SubscriptionListEntry> getActiveSubscriptionEntriesExcludingDefault() {
        return mEntriesExcludingDefault;
    }

    public SubscriptionListEntry getActiveSubscriptionEntryBySelfId(final String selfId,
            final boolean excludeDefault) {
        if (mDefaultEntry != null && TextUtils.equals(mDefaultEntry.selfParticipantId, selfId)) {
            return excludeDefault ? null : mDefaultEntry;
        }

        for (final SubscriptionListEntry entry : mEntriesExcludingDefault) {
            if (TextUtils.equals(entry.selfParticipantId, selfId)) {
                return entry;
            }
        }
        return null;
    }

    public boolean hasData() {
        return !mEntriesExcludingDefault.isEmpty() || mDefaultEntry != null;
    }
}
