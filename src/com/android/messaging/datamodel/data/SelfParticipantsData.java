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

import android.database.Cursor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import androidx.collection.ArrayMap;

/**
 * A class that contains the list of all self participants potentially involved in a conversation.
 * This class contains both active/inactive self entries when there is multi-SIM support.
 */
public class SelfParticipantsData {
    /**
     * The map from self participant ids to self-participant data entries in the participants table.
     * This includes both active, inactive and default (with subId ==
     * {@link ParticipantData#DEFAULT_SELF_SUB_ID}) subscriptions.
     */
    private final ArrayMap<String, ParticipantData> mSelfParticipantMap;

    public SelfParticipantsData() {
        mSelfParticipantMap = new ArrayMap<String, ParticipantData>();
    }

    public void bind(final Cursor cursor) {
        mSelfParticipantMap.clear();
        if (cursor != null) {
            while (cursor.moveToNext()) {
                final ParticipantData newParticipant = ParticipantData.getFromCursor(cursor);
                mSelfParticipantMap.put(newParticipant.getId(), newParticipant);
            }
        }
    }

    /**
     * Gets the list of self participants for all subscriptions.
     * @param activeOnly if set, returns active self entries only (i.e. those with SIMs plugged in).
     */
    public List<ParticipantData> getSelfParticipants(final boolean activeOnly) {
        List<ParticipantData> list = new ArrayList<>();
        for (final ParticipantData self : mSelfParticipantMap.values()) {
            if (!activeOnly || self.isActiveSubscription()) {
                list.add(self);
            }
        }
        list.sort(Comparator.comparingInt(ParticipantData::getSlotId));
        return list;
    }

    /**
     * Gets the self participant corresponding to the given self id.
     */
    ParticipantData getSelfParticipantById(final String selfId) {
        return mSelfParticipantMap.get(selfId);
    }

    /**
     * Returns if a given self id represents the default self.
     */
    boolean isDefaultSelf(final String selfId) {
        final ParticipantData self = getSelfParticipantById(selfId);
        return self == null ? false : self.getSubId() == ParticipantData.DEFAULT_SELF_SUB_ID;
    }

    public int getSelfParticipantsCountExcludingDefault(final boolean activeOnly) {
        int count = 0;
        for (final ParticipantData self : mSelfParticipantMap.values()) {
            if (!self.isDefaultSelf() && (!activeOnly || self.isActiveSubscription())) {
                count++;
            }
        }
        return count;
    }

    public ParticipantData getDefaultSelfParticipant() {
        for (final ParticipantData self : mSelfParticipantMap.values()) {
            if (self.isDefaultSelf()) {
                return self;
            }
        }
        return null;
    }

    boolean isLoaded() {
        return !mSelfParticipantMap.isEmpty();
    }
}
