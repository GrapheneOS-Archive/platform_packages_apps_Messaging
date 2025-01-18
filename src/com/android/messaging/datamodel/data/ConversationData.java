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

import android.app.LoaderManager;
import android.content.Context;
import android.content.Loader;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;

import com.android.messaging.Factory;
import com.android.messaging.R;
import com.android.messaging.datamodel.BoundCursorLoader;
import com.android.messaging.datamodel.BugleNotifications;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseHelper.ParticipantColumns;
import com.android.messaging.datamodel.MessagingContentProvider;
import com.android.messaging.datamodel.action.DeleteConversationAction;
import com.android.messaging.datamodel.action.DeleteMessageAction;
import com.android.messaging.datamodel.action.InsertNewMessageAction;
import com.android.messaging.datamodel.action.RedownloadMmsAction;
import com.android.messaging.datamodel.action.ResendMessageAction;
import com.android.messaging.datamodel.action.UpdateConversationArchiveStatusAction;
import com.android.messaging.datamodel.binding.BindableData;
import com.android.messaging.datamodel.binding.Binding;
import com.android.messaging.datamodel.binding.BindingBase;
import com.android.messaging.datamodel.data.SubscriptionListData.SubscriptionListEntry;
import com.android.messaging.sms.MmsSmsUtils;
import com.android.messaging.sms.MmsUtils;
import com.android.messaging.util.Assert;
import com.android.messaging.util.Assert.RunsOnMainThread;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.PhoneUtils;
import com.android.messaging.widget.WidgetConversationProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import androidx.annotation.Nullable;

public class ConversationData extends BindableData {

    private static final String TAG = "bugle_datamodel";
    private static final String BINDING_ID = "bindingId";
    private static final long LAST_MESSAGE_TIMESTAMP_NaN = -1;
    private static final int MESSAGE_COUNT_NaN = -1;

    /**
     * Takes a conversation id and a list of message ids and computes the positions
     * for each message.
     */
    public List<Integer> getPositions(final String conversationId, final List<Long> ids) {
        final ArrayList<Integer> result = new ArrayList<Integer>();

        if (ids.isEmpty()) {
            return result;
        }

        final Cursor c = new ConversationData.ReversedCursor(
                DataModel.get().getDatabase().rawQuery(
                        ConversationMessageData.getConversationMessageIdsQuerySql(),
                        new String [] { conversationId }));
        if (c != null) {
            try {
                final Set<Long> idsSet = new HashSet<Long>(ids);
                if (c.moveToLast()) {
                    do {
                        final long messageId = c.getLong(0);
                        if (idsSet.contains(messageId)) {
                            result.add(c.getPosition());
                        }
                    } while (c.moveToPrevious());
                }
            } finally {
                c.close();
            }
        }
        Collections.sort(result);
        return result;
    }

    public interface ConversationDataListener {
        public void onConversationMessagesCursorUpdated(ConversationData data, Cursor cursor,
                @Nullable ConversationMessageData newestMessage, boolean isSync);
        public void onConversationMetadataUpdated(ConversationData data);
        public void closeConversation(String conversationId);
        public void onConversationParticipantDataLoaded(ConversationData data);
        public void onSubscriptionListDataLoaded(ConversationData data);
    }

    private static class ReversedCursor extends CursorWrapper {
        final int mCount;

        public ReversedCursor(final Cursor cursor) {
            super(cursor);
            mCount = cursor.getCount();
        }

        @Override
        public boolean moveToPosition(final int position) {
            return super.moveToPosition(mCount - position - 1);
        }

        @Override
        public int getPosition() {
            return mCount - super.getPosition() - 1;
        }

        @Override
        public boolean isAfterLast() {
            return super.isBeforeFirst();
        }

        @Override
        public boolean isBeforeFirst() {
            return super.isAfterLast();
        }

        @Override
        public boolean isFirst() {
            return super.isLast();
        }

        @Override
        public boolean isLast() {
            return super.isFirst();
        }

        @Override
        public boolean move(final int offset) {
            return super.move(-offset);
        }

        @Override
        public boolean moveToFirst() {
            return super.moveToLast();
        }

        @Override
        public boolean moveToLast() {
            return super.moveToFirst();
        }

        @Override
        public boolean moveToNext() {
            return super.moveToPrevious();
        }

        @Override
        public boolean moveToPrevious() {
            return super.moveToNext();
        }
    }

    /**
     * A trampoline class so that we can inherit from LoaderManager.LoaderCallbacks multiple times.
     */
    private class MetadataLoaderCallbacks implements LoaderManager.LoaderCallbacks<Cursor> {
        @Override
        public Loader<Cursor> onCreateLoader(final int id, final Bundle args) {
            Assert.equals(CONVERSATION_META_DATA_LOADER, id);
            Loader<Cursor> loader = null;

            final String bindingId = args.getString(BINDING_ID);
            // Check if data still bound to the requesting ui element
            if (isBound(bindingId)) {
                final Uri uri =
                        MessagingContentProvider.buildConversationMetadataUri(mConversationId);
                loader = new BoundCursorLoader(bindingId, mContext, uri,
                        ConversationListItemData.PROJECTION, null, null, null);
            } else {
                LogUtil.w(TAG, "Creating messages loader after unbinding mConversationId = " +
                        mConversationId);
            }
            return loader;
        }

        @Override
        public void onLoadFinished(final Loader<Cursor> generic, final Cursor data) {
            final BoundCursorLoader loader = (BoundCursorLoader) generic;

            // Check if data still bound to the requesting ui element
            if (isBound(loader.getBindingId())) {
                if (data.moveToNext()) {
                    Assert.isTrue(data.getCount() == 1);
                    mConversationMetadata.bind(data);
                    mListeners.onConversationMetadataUpdated(ConversationData.this);
                } else {
                    // Close the conversation, no meta data means conversation was deleted
                    LogUtil.w(TAG, "Meta data loader returned nothing for mConversationId = " +
                            mConversationId);
                    mListeners.closeConversation(mConversationId);
                    // Notify the widget the conversation is deleted so it can go into its
                    // configure state.
                    WidgetConversationProvider.notifyConversationDeleted(
                            Factory.get().getApplicationContext(),
                            mConversationId);
                }
            } else {
                LogUtil.w(TAG, "Meta data loader finished after unbinding mConversationId = " +
                        mConversationId);
            }
        }

        @Override
        public void onLoaderReset(final Loader<Cursor> generic) {
            final BoundCursorLoader loader = (BoundCursorLoader) generic;

            // Check if data still bound to the requesting ui element
            if (isBound(loader.getBindingId())) {
                // Clear the conversation meta data
                mConversationMetadata = new ConversationListItemData();
                mListeners.onConversationMetadataUpdated(ConversationData.this);
            } else {
                LogUtil.w(TAG, "Meta data loader reset after unbinding mConversationId = " +
                        mConversationId);
            }
        }
    }

    /**
     * A trampoline class so that we can inherit from LoaderManager.LoaderCallbacks multiple times.
     */
    private class MessagesLoaderCallbacks implements LoaderManager.LoaderCallbacks<Cursor> {
        @Override
        public Loader<Cursor> onCreateLoader(final int id, final Bundle args) {
            Assert.equals(CONVERSATION_MESSAGES_LOADER, id);
            Loader<Cursor> loader = null;

            final String bindingId = args.getString(BINDING_ID);
            // Check if data still bound to the requesting ui element
            if (isBound(bindingId)) {
                final Uri uri =
                        MessagingContentProvider.buildConversationMessagesUri(mConversationId);
                loader = new BoundCursorLoader(bindingId, mContext, uri,
                        ConversationMessageData.getProjection(), null, null, null);
                mLastMessageTimestamp = LAST_MESSAGE_TIMESTAMP_NaN;
                mMessageCount = MESSAGE_COUNT_NaN;
            } else {
                LogUtil.w(TAG, "Creating messages loader after unbinding mConversationId = " +
                        mConversationId);
            }
            return loader;
        }

        @Override
        public void onLoadFinished(final Loader<Cursor> generic, final Cursor rawData) {
            final BoundCursorLoader loader = (BoundCursorLoader) generic;

            // Check if data still bound to the requesting ui element
            if (isBound(loader.getBindingId())) {
                // Check if we have a new message, or if we had a message sync.
                ConversationMessageData newMessage = null;
                boolean isSync = false;
                Cursor data = null;
                if (rawData != null) {
                    // Note that the cursor is sorted DESC so here we reverse it.
                    // This is a performance issue (improvement) for large cursors.
                    data = new ReversedCursor(rawData);

                    final int messageCountOld = mMessageCount;
                    mMessageCount = data.getCount();
                    final ConversationMessageData lastMessage = getLastMessage(data);
                    if (lastMessage != null) {
                        final long lastMessageTimestampOld = mLastMessageTimestamp;
                        mLastMessageTimestamp = lastMessage.getReceivedTimeStamp();
                        final String lastMessageIdOld = mLastMessageId;
                        mLastMessageId = lastMessage.getMessageId();
                        if (TextUtils.equals(lastMessageIdOld, mLastMessageId) &&
                                messageCountOld < mMessageCount) {
                            // Last message stays the same (no incoming message) but message
                            // count increased, which means there has been a message sync.
                            isSync = true;
                        } else if (messageCountOld != MESSAGE_COUNT_NaN && // Ignore initial load
                                mLastMessageTimestamp != LAST_MESSAGE_TIMESTAMP_NaN &&
                                mLastMessageTimestamp > lastMessageTimestampOld) {
                            newMessage = lastMessage;
                        }
                    } else {
                        mLastMessageTimestamp = LAST_MESSAGE_TIMESTAMP_NaN;
                    }
                } else {
                    mMessageCount = MESSAGE_COUNT_NaN;
                }

                mListeners.onConversationMessagesCursorUpdated(ConversationData.this, data,
                        newMessage, isSync);
            } else {
                LogUtil.w(TAG, "Messages loader finished after unbinding mConversationId = " +
                        mConversationId);
            }
        }

        @Override
        public void onLoaderReset(final Loader<Cursor> generic) {
            final BoundCursorLoader loader = (BoundCursorLoader) generic;

            // Check if data still bound to the requesting ui element
            if (isBound(loader.getBindingId())) {
                mListeners.onConversationMessagesCursorUpdated(ConversationData.this, null, null,
                        false);
                mLastMessageTimestamp = LAST_MESSAGE_TIMESTAMP_NaN;
                mMessageCount = MESSAGE_COUNT_NaN;
            } else {
                LogUtil.w(TAG, "Messages loader reset after unbinding mConversationId = " +
                        mConversationId);
            }
        }

        private ConversationMessageData getLastMessage(final Cursor cursor) {
            if (cursor != null && cursor.getCount() > 0) {
                final int position = cursor.getPosition();
                if (cursor.moveToLast()) {
                    final ConversationMessageData messageData = new ConversationMessageData();
                    messageData.bind(cursor);
                    cursor.move(position);
                    return messageData;
                }
            }
            return null;
        }
    }

    /**
     * A trampoline class so that we can inherit from LoaderManager.LoaderCallbacks multiple times.
     */
    private class ParticipantLoaderCallbacks implements LoaderManager.LoaderCallbacks<Cursor> {
        @Override
        public Loader<Cursor> onCreateLoader(final int id, final Bundle args) {
            Assert.equals(PARTICIPANT_LOADER, id);
            Loader<Cursor> loader = null;

            final String bindingId = args.getString(BINDING_ID);
            // Check if data still bound to the requesting ui element
            if (isBound(bindingId)) {
                final Uri uri =
                        MessagingContentProvider.buildConversationParticipantsUri(mConversationId);
                loader = new BoundCursorLoader(bindingId, mContext, uri,
                        ParticipantData.ParticipantsQuery.PROJECTION, null, null, null);
            } else {
                LogUtil.w(TAG, "Creating participant loader after unbinding mConversationId = " +
                        mConversationId);
            }
            return loader;
        }

        @Override
        public void onLoadFinished(final Loader<Cursor> generic, final Cursor data) {
            final BoundCursorLoader loader = (BoundCursorLoader) generic;

            // Check if data still bound to the requesting ui element
            if (isBound(loader.getBindingId())) {
                mParticipantData.bind(data);
                mListeners.onConversationParticipantDataLoaded(ConversationData.this);
            } else {
                LogUtil.w(TAG, "Participant loader finished after unbinding mConversationId = " +
                        mConversationId);
            }
        }

        @Override
        public void onLoaderReset(final Loader<Cursor> generic) {
            final BoundCursorLoader loader = (BoundCursorLoader) generic;

            // Check if data still bound to the requesting ui element
            if (isBound(loader.getBindingId())) {
                mParticipantData.bind(null);
            } else {
                LogUtil.w(TAG, "Participant loader reset after unbinding mConversationId = " +
                        mConversationId);
            }
        }
    }

    /**
     * A trampoline class so that we can inherit from LoaderManager.LoaderCallbacks multiple times.
     */
    private class SelfParticipantLoaderCallbacks implements LoaderManager.LoaderCallbacks<Cursor> {
        @Override
        public Loader<Cursor> onCreateLoader(final int id, final Bundle args) {
            Assert.equals(SELF_PARTICIPANT_LOADER, id);
            Loader<Cursor> loader = null;

            final String bindingId = args.getString(BINDING_ID);
            // Check if data still bound to the requesting ui element
            if (isBound(bindingId)) {
                loader = new BoundCursorLoader(bindingId, mContext,
                        MessagingContentProvider.PARTICIPANTS_URI,
                        ParticipantData.ParticipantsQuery.PROJECTION,
                        ParticipantColumns.SUB_ID + " <> ?",
                        new String[] { String.valueOf(ParticipantData.OTHER_THAN_SELF_SUB_ID) },
                        null);
            } else {
                LogUtil.w(TAG, "Creating self loader after unbinding mConversationId = " +
                        mConversationId);
            }
            return loader;
        }

        @Override
        public void onLoadFinished(final Loader<Cursor> generic, final Cursor data) {
            final BoundCursorLoader loader = (BoundCursorLoader) generic;

            // Check if data still bound to the requesting ui element
            if (isBound(loader.getBindingId())) {
                mSelfParticipantsData.bind(data);
                mSubscriptionListData.bind(mSelfParticipantsData.getSelfParticipants(true));
                mListeners.onSubscriptionListDataLoaded(ConversationData.this);
            } else {
                LogUtil.w(TAG, "Self loader finished after unbinding mConversationId = " +
                        mConversationId);
            }
        }

        @Override
        public void onLoaderReset(final Loader<Cursor> generic) {
            final BoundCursorLoader loader = (BoundCursorLoader) generic;

            // Check if data still bound to the requesting ui element
            if (isBound(loader.getBindingId())) {
                mSelfParticipantsData.bind(null);
            } else {
                LogUtil.w(TAG, "Self loader reset after unbinding mConversationId = " +
                        mConversationId);
            }
        }
    }

    private final ConversationDataEventDispatcher mListeners;
    private final MetadataLoaderCallbacks mMetadataLoaderCallbacks;
    private final MessagesLoaderCallbacks mMessagesLoaderCallbacks;
    private final ParticipantLoaderCallbacks mParticipantsLoaderCallbacks;
    private final SelfParticipantLoaderCallbacks mSelfParticipantLoaderCallbacks;
    private final Context mContext;
    private final String mConversationId;
    private final ConversationParticipantsData mParticipantData;
    private final SelfParticipantsData mSelfParticipantsData;
    private ConversationListItemData mConversationMetadata;
    private final SubscriptionListData mSubscriptionListData;
    private LoaderManager mLoaderManager;
    private long mLastMessageTimestamp = LAST_MESSAGE_TIMESTAMP_NaN;
    private int mMessageCount = MESSAGE_COUNT_NaN;
    private String mLastMessageId;

    public ConversationData(final Context context, final ConversationDataListener listener,
            final String conversationId) {
        Assert.isTrue(conversationId != null);
        mContext = context;
        mConversationId = conversationId;
        mMetadataLoaderCallbacks = new MetadataLoaderCallbacks();
        mMessagesLoaderCallbacks = new MessagesLoaderCallbacks();
        mParticipantsLoaderCallbacks = new ParticipantLoaderCallbacks();
        mSelfParticipantLoaderCallbacks = new SelfParticipantLoaderCallbacks();
        mParticipantData = new ConversationParticipantsData();
        mConversationMetadata = new ConversationListItemData();
        mSelfParticipantsData = new SelfParticipantsData();
        mSubscriptionListData = new SubscriptionListData(context);

        mListeners = new ConversationDataEventDispatcher();
        mListeners.add(listener);
    }

    @RunsOnMainThread
    public void addConversationDataListener(final ConversationDataListener listener) {
        Assert.isMainThread();
        mListeners.add(listener);
    }

    public String getConversationName() {
        return mConversationMetadata.getName();
    }

    public boolean getIsArchived() {
        return mConversationMetadata.getIsArchived();
    }

    public String getIcon() {
        return mConversationMetadata.getIcon();
    }

    public String getConversationId() {
        return mConversationId;
    }

    public void setFocus() {
        DataModel.get().setFocusedConversation(mConversationId);
        // As we are loading the conversation assume the user has read the messages...
        // Do this late though so that it doesn't get in the way of other actions
        BugleNotifications.markMessagesAsRead(mConversationId);
    }

    public void unsetFocus() {
        DataModel.get().setFocusedConversation(null);
    }

    public boolean isFocused() {
        return isBound() && DataModel.get().isFocusedConversation(mConversationId);
    }

    private static final int CONVERSATION_META_DATA_LOADER = 1;
    private static final int CONVERSATION_MESSAGES_LOADER = 2;
    private static final int PARTICIPANT_LOADER = 3;
    private static final int SELF_PARTICIPANT_LOADER = 4;

    public void init(final LoaderManager loaderManager,
            final BindingBase<ConversationData> binding) {
        // Remember the binding id so that loader callbacks can check if data is still bound
        // to same ui component
        final Bundle args = new Bundle();
        args.putString(BINDING_ID, binding.getBindingId());
        mLoaderManager = loaderManager;
        mLoaderManager.initLoader(CONVERSATION_META_DATA_LOADER, args, mMetadataLoaderCallbacks);
        mLoaderManager.initLoader(CONVERSATION_MESSAGES_LOADER, args, mMessagesLoaderCallbacks);
        mLoaderManager.initLoader(PARTICIPANT_LOADER, args, mParticipantsLoaderCallbacks);
        mLoaderManager.initLoader(SELF_PARTICIPANT_LOADER, args, mSelfParticipantLoaderCallbacks);
    }

    @Override
    protected void unregisterListeners() {
        mListeners.clear();
        // Make sure focus has moved away from this conversation
        // TODO: May false trigger if destroy happens after "new" conversation is focused.
        //        Assert.isTrue(!DataModel.get().isFocusedConversation(mConversationId));

        // This could be null if we bind but the caller doesn't init the BindableData
        if (mLoaderManager != null) {
            mLoaderManager.destroyLoader(CONVERSATION_META_DATA_LOADER);
            mLoaderManager.destroyLoader(CONVERSATION_MESSAGES_LOADER);
            mLoaderManager.destroyLoader(PARTICIPANT_LOADER);
            mLoaderManager.destroyLoader(SELF_PARTICIPANT_LOADER);
            mLoaderManager = null;
        }
    }

    /**
     * Gets the default self participant in the participant table (NOT the conversation's self).
     * This is available as soon as self participant data is loaded.
     */
    public ParticipantData getDefaultSelfParticipant() {
        return mSelfParticipantsData.getDefaultSelfParticipant();
    }

    public List<ParticipantData> getSelfParticipants(final boolean activeOnly) {
        return mSelfParticipantsData.getSelfParticipants(activeOnly);
    }

    public int getSelfParticipantsCountExcludingDefault(final boolean activeOnly) {
        return mSelfParticipantsData.getSelfParticipantsCountExcludingDefault(activeOnly);
    }

    public ParticipantData getSelfParticipantById(final String selfId) {
        return mSelfParticipantsData.getSelfParticipantById(selfId);
    }

    /**
     * For a 1:1 conversation return the other (not self) participant (else null)
     */
    public ParticipantData getOtherParticipant() {
        return mParticipantData.getOtherParticipant();
    }

    /**
     * Return true once the participants are loaded
     */
    public boolean getParticipantsLoaded() {
        return mParticipantData.isLoaded();
    }

    public void sendMessage(final BindingBase<ConversationData> binding,
            final MessageData message) {
        Assert.isTrue(TextUtils.equals(mConversationId, message.getConversationId()));
        Assert.isTrue(binding.getData() == this);

        if (message.getSelfId() == null) {
            InsertNewMessageAction.insertNewMessage(message);
        } else {
            final int systemDefaultSubId = PhoneUtils.getDefault().getDefaultSmsSubscriptionId();
            if (systemDefaultSubId != ParticipantData.DEFAULT_SELF_SUB_ID &&
                    mSelfParticipantsData.isDefaultSelf(message.getSelfId())) {
                // Lock the sub selection to the system default SIM as soon as the user clicks on
                // the send button to avoid races between this and when InsertNewMessageAction is
                // actually executed on the data model thread, during which the user can potentially
                // change the system default SIM in Settings.
                InsertNewMessageAction.insertNewMessage(message, systemDefaultSubId);
            } else {
                InsertNewMessageAction.insertNewMessage(message);
            }
        }
    }

    public void downloadMessage(final BindingBase<ConversationData> binding,
            final String messageId) {
        Assert.isTrue(binding.getData() == this);
        Assert.notNull(messageId);
        RedownloadMmsAction.redownloadMessage(messageId);
    }

    public void resendMessage(final BindingBase<ConversationData> binding, final String messageId) {
        Assert.isTrue(binding.getData() == this);
        Assert.notNull(messageId);
        ResendMessageAction.resendMessage(messageId);
    }

    public void deleteMessage(final BindingBase<ConversationData> binding, final String messageId) {
        Assert.isTrue(binding.getData() == this);
        Assert.notNull(messageId);
        DeleteMessageAction.deleteMessage(messageId);
    }

    public void deleteConversation(final Binding<ConversationData> binding) {
        Assert.isTrue(binding.getData() == this);
        // If possible use timestamp of last message shown to delete only messages user is aware of
        if (mConversationMetadata == null) {
            DeleteConversationAction.deleteConversation(mConversationId,
                    System.currentTimeMillis());
        } else {
            mConversationMetadata.deleteConversation();
        }
    }

    public void archiveConversation(final BindingBase<ConversationData> binding) {
        Assert.isTrue(binding.getData() == this);
        UpdateConversationArchiveStatusAction.archiveConversation(mConversationId);
    }

    public void unarchiveConversation(final BindingBase<ConversationData> binding) {
        Assert.isTrue(binding.getData() == this);
        UpdateConversationArchiveStatusAction.unarchiveConversation(mConversationId);
    }

    public ConversationParticipantsData getParticipants() {
        return mParticipantData;
    }

    /**
     * Returns a dialable phone number for the participant if we are in a 1-1 conversation.
     * @return the participant phone number, or null if the phone number is not valid or if there
     *         are more than one participant.
     */
    public String getParticipantPhoneNumber() {
        final ParticipantData participant = this.getOtherParticipant();
        if (participant != null) {
            final String phoneNumber = participant.getSendDestination();
            if (!TextUtils.isEmpty(phoneNumber) && MmsSmsUtils.isPhoneNumber(phoneNumber)) {
                return phoneNumber;
            }
        }
        return null;
    }

    /**
     * Create a message to be forwarded from an existing message.
     */
    public MessageData createForwardedMessage(final ConversationMessageData message) {
        final MessageData forwardedMessage = new MessageData();

        final String originalSubject =
                MmsUtils.cleanseMmsSubject(mContext.getResources(), message.getMmsSubject());
        if (!TextUtils.isEmpty(originalSubject)) {
            forwardedMessage.setMmsSubject(
                    mContext.getResources().getString(R.string.message_fwd, originalSubject));
        }

        for (final MessagePartData part : message.getParts()) {
            MessagePartData forwardedPart;

            // Depending on the part type, if it is text, we can directly create a text part;
            // if it is attachment, then we need to create a pending attachment data out of it, so
            // that we may persist the attachment locally in the scratch folder when the user picks
            // a conversation to forward to.
            if (part.isText()) {
                forwardedPart = MessagePartData.createTextMessagePart(part.getText());
            } else {
                final PendingAttachmentData pendingAttachmentData = PendingAttachmentData
                        .createPendingAttachmentData(part.getContentType(), part.getContentUri());
                forwardedPart = pendingAttachmentData;
            }
            forwardedMessage.addPart(forwardedPart);
        }
        return forwardedMessage;
    }

    public int getNumberOfParticipantsExcludingSelf() {
        return mParticipantData.getNumberOfParticipantsExcludingSelf();
    }

    /**
     * Returns {@link com.android.messaging.datamodel.data.SubscriptionListData
     * .SubscriptionListEntry} for a given self participant so UI can display SIM-related info
     * (icon, name etc.) for multi-SIM.
     */
    public SubscriptionListEntry getSubscriptionEntryForSelfParticipant(
            final String selfParticipantId, final boolean excludeDefault) {
        return getSubscriptionEntryForSelfParticipant(selfParticipantId, excludeDefault,
                mSubscriptionListData, mSelfParticipantsData);
    }

    /**
     * Returns {@link com.android.messaging.datamodel.data.SubscriptionListData
     * .SubscriptionListEntry} for a given self participant so UI can display SIM-related info
     * (icon, name etc.) for multi-SIM.
     */
    public static SubscriptionListEntry getSubscriptionEntryForSelfParticipant(
            final String selfParticipantId, final boolean excludeDefault,
            final SubscriptionListData subscriptionListData,
            final SelfParticipantsData selfParticipantsData) {
        // SIM indicators are shown in the UI only if:
        // 1. The device has had multiple *active* subscriptions. AND
        // 2. The message's subscription is active.
        if (selfParticipantsData.getSelfParticipantsCountExcludingDefault(true) > 1) {
            return subscriptionListData.getActiveSubscriptionEntryBySelfId(selfParticipantId,
                    excludeDefault);
        }
        return null;
    }

    public SubscriptionListData getSubscriptionListData() {
        return mSubscriptionListData;
    }

    /**
     * A placeholder implementation of {@link ConversationDataListener} so that subclasses may opt
     * to implement some, but not all, of the interface methods.
     */
    public static class SimpleConversationDataListener implements ConversationDataListener {

        @Override
        public void onConversationMessagesCursorUpdated(final ConversationData data, final Cursor cursor,
                @Nullable
                        final
                ConversationMessageData newestMessage, final boolean isSync) {}

        @Override
        public void onConversationMetadataUpdated(final ConversationData data) {}

        @Override
        public void closeConversation(final String conversationId) {}

        @Override
        public void onConversationParticipantDataLoaded(final ConversationData data) {}

        @Override
        public void onSubscriptionListDataLoaded(final ConversationData data) {}

    }

    private class ConversationDataEventDispatcher
            extends ArrayList<ConversationDataListener>
            implements ConversationDataListener {

        @Override
        public void onConversationMessagesCursorUpdated(final ConversationData data, final Cursor cursor,
                @Nullable
                        final ConversationMessageData newestMessage, final boolean isSync) {
            for (final ConversationDataListener listener : this) {
                listener.onConversationMessagesCursorUpdated(data, cursor, newestMessage, isSync);
            }
        }

        @Override
        public void onConversationMetadataUpdated(final ConversationData data) {
            for (final ConversationDataListener listener : this) {
                listener.onConversationMetadataUpdated(data);
            }
        }

        @Override
        public void closeConversation(final String conversationId) {
            for (final ConversationDataListener listener : this) {
                listener.closeConversation(conversationId);
            }
        }

        @Override
        public void onConversationParticipantDataLoaded(final ConversationData data) {
            for (final ConversationDataListener listener : this) {
                listener.onConversationParticipantDataLoaded(data);
            }
        }

        @Override
        public void onSubscriptionListDataLoaded(final ConversationData data) {
            for (final ConversationDataListener listener : this) {
                listener.onSubscriptionListDataLoaded(data);
            }
        }
    }
}
