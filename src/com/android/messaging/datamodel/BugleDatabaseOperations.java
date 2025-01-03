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

package com.android.messaging.datamodel;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDoneException;
import android.database.sqlite.SQLiteStatement;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;

import com.android.messaging.Factory;
import com.android.messaging.datamodel.DatabaseHelper.ConversationColumns;
import com.android.messaging.datamodel.DatabaseHelper.ConversationParticipantsColumns;
import com.android.messaging.datamodel.DatabaseHelper.MessageColumns;
import com.android.messaging.datamodel.DatabaseHelper.PartColumns;
import com.android.messaging.datamodel.DatabaseHelper.ParticipantColumns;
import com.android.messaging.datamodel.ParticipantRefresh.ConversationParticipantsQuery;
import com.android.messaging.datamodel.data.ConversationListItemData;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.datamodel.data.MessagePartData;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.sms.MmsUtils;
import com.android.messaging.ui.UIIntents;
import com.android.messaging.util.Assert;
import com.android.messaging.util.Assert.DoesNotRunOnMainThread;
import com.android.messaging.util.AvatarUriUtil;
import com.android.messaging.util.ContentType;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.PhoneUtils;
import com.android.messaging.util.UriUtil;
import com.android.messaging.widget.WidgetConversationProvider;
import com.google.common.annotations.VisibleForTesting;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import javax.annotation.Nullable;

import androidx.collection.ArrayMap;
import androidx.collection.SimpleArrayMap;


/**
 * This class manages updating our local database
 */
public class BugleDatabaseOperations {

    private static final String TAG = LogUtil.BUGLE_DATABASE_TAG;

    // Global cache of phone numbers -> participant id mapping since this call is expensive.
    private static final ArrayMap<String, String> sNormalizedPhoneNumberToParticipantIdCache =
            new ArrayMap<String, String>();

    /**
     * Convert list of recipient strings (email/phone number) into list of ConversationParticipants
     *
     * @param recipients The recipient list
     * @param refSubId The subId used to normalize phone numbers in the recipients
     */
    static ArrayList<ParticipantData> getConversationParticipantsFromRecipients(
            final List<String> recipients, final int refSubId) {
        // Generate a list of partially formed participants
        final ArrayList<ParticipantData> participants = new
                ArrayList<ParticipantData>();

        if (recipients != null) {
            for (final String recipient : recipients) {
                participants.add(ParticipantData.getFromRawPhoneBySimLocale(recipient, refSubId));
            }
        }
        return participants;
    }

    /**
     * Sanitize a given list of conversation participants by de-duping and stripping out self
     * phone number in group conversation.
     */
    @DoesNotRunOnMainThread
    public static void sanitizeConversationParticipants(final List<ParticipantData> participants) {
        Assert.isNotMainThread();
        if (participants.size() > 0) {
            // First remove redundant phone numbers
            final HashSet<String> recipients = new HashSet<String>();
            for (int i = participants.size() - 1; i >= 0; i--) {
                final String recipient = participants.get(i).getNormalizedDestination();
                if (!recipients.contains(recipient)) {
                    recipients.add(recipient);
                } else {
                    participants.remove(i);
                }
            }
            if (participants.size() > 1) {
                // Remove self phone number from group conversation.
                final HashSet<String> selfNumbers =
                        PhoneUtils.getDefault().getNormalizedSelfNumbers();
                int removed = 0;
                // Do this two-pass scan to avoid unnecessary memory allocation.
                // Prescan to count the self numbers in the list
                for (final ParticipantData p : participants) {
                    if (selfNumbers.contains(p.getNormalizedDestination())) {
                        removed++;
                    }
                }
                // If all are self numbers, maybe that's what the user wants, just leave
                // the participants as is. Otherwise, do another scan to remove self numbers.
                if (removed < participants.size()) {
                    for (int i = participants.size() - 1; i >= 0; i--) {
                        final String recipient = participants.get(i).getNormalizedDestination();
                        if (selfNumbers.contains(recipient)) {
                            participants.remove(i);
                        }
                    }
                }
            }
        }
    }

    /**
     * Convert list of ConversationParticipants into recipient strings (email/phone number)
     */
    @DoesNotRunOnMainThread
    public static ArrayList<String> getRecipientsFromConversationParticipants(
            final List<ParticipantData> participants) {
        Assert.isNotMainThread();
        // First find the thread id for this list of participants.
        final ArrayList<String> recipients = new ArrayList<String>();

        for (final ParticipantData participant : participants) {
            recipients.add(participant.getSendDestination());
        }
        return recipients;
    }

    /**
     * Get or create a conversation based on the message's thread id
     *
     * NOTE: There are phones on which you can't get the recipients from the thread id for SMS
     * until you have a message, so use getOrCreateConversationFromRecipient instead.
     *
     * TODO: Should this be in MMS/SMS code?
     *
     * @param db the database
     * @param threadId The message's thread
     * @param senderBlocked Flag whether sender of message is in blocked people list
     * @param refSubId The reference subId for canonicalize phone numbers
     * @return conversationId
     */
    @DoesNotRunOnMainThread
    public static String getOrCreateConversationFromThreadId(final DatabaseWrapper db,
            final long threadId, final boolean senderBlocked, final int refSubId) {
        Assert.isNotMainThread();
        final List<String> recipients = MmsUtils.getRecipientsByThread(threadId);
        final ArrayList<ParticipantData> participants =
                getConversationParticipantsFromRecipients(recipients, refSubId);

        return getOrCreateConversation(db, threadId, senderBlocked, participants, false, false,
                null);
    }

    /**
     * Get or create a conversation based on provided recipient
     *
     * @param db the database
     * @param threadId The message's thread
     * @param senderBlocked Flag whether sender of message is in blocked people list
     * @param recipient recipient for thread
     * @return conversationId
     */
    @DoesNotRunOnMainThread
    public static String getOrCreateConversationFromRecipient(final DatabaseWrapper db,
            final long threadId, final boolean senderBlocked, final ParticipantData recipient) {
        Assert.isNotMainThread();
        final ArrayList<ParticipantData> recipients = new ArrayList<>(1);
        recipients.add(recipient);
        return getOrCreateConversation(db, threadId, senderBlocked, recipients, false, false, null);
    }

    /**
     * Get or create a conversation based on provided participants
     *
     * @param db the database
     * @param threadId The message's thread
     * @param archived Flag whether the conversation should be created archived
     * @param participants list of conversation participants
     * @param noNotification If notification should be disabled
     * @param noVibrate If vibrate on notification should be disabled
     * @param soundUri If there is custom sound URI
     * @return a conversation id
     */
    @DoesNotRunOnMainThread
    public static String getOrCreateConversation(final DatabaseWrapper db, final long threadId,
            final boolean archived, final ArrayList<ParticipantData> participants,
            boolean noNotification, boolean noVibrate, String soundUri) {
        Assert.isNotMainThread();

        // Check to see if this conversation is already in out local db cache
        String conversationId = BugleDatabaseOperations.getExistingConversation(db, threadId,
                false);

        if (conversationId == null) {
            final String conversationName = ConversationListItemData.generateConversationName(
                    participants);

            // Create the conversation with the default self participant which always maps to
            // the system default subscription.
            final ParticipantData self = ParticipantData.getSelfParticipant(
                    ParticipantData.DEFAULT_SELF_SUB_ID);

            db.beginTransaction();
            try {
                // Look up the "self" participantId (creating if necessary)
                final String selfId =
                        BugleDatabaseOperations.getOrCreateParticipantInTransaction(db, self);
                // Create a new conversation
                conversationId = BugleDatabaseOperations.createConversationInTransaction(
                        db, threadId, conversationName, selfId, participants, archived,
                        noNotification, noVibrate, soundUri);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }

        return conversationId;
    }

    /**
     * Get a conversation from the local DB based on the message's thread id.
     *
     * @param dbWrapper     The database
     * @param threadId      The message's thread in the SMS database
     * @param senderBlocked Flag whether sender of message is in blocked people list
     * @return The existing conversation id or null
     */
    @VisibleForTesting
    @DoesNotRunOnMainThread
    public static String getExistingConversation(final DatabaseWrapper dbWrapper,
            final long threadId, final boolean senderBlocked) {
        Assert.isNotMainThread();
        String conversationId = null;

        Cursor cursor = null;
        try {
            // Look for an existing conversation in the db with this thread id
            cursor = dbWrapper.rawQuery("SELECT " + ConversationColumns._ID
                            + " FROM " + DatabaseHelper.CONVERSATIONS_TABLE
                            + " WHERE " + ConversationColumns.SMS_THREAD_ID + "=" + threadId,
                    null);

            if (cursor.moveToFirst()) {
                Assert.isTrue(cursor.getCount() == 1);
                conversationId = cursor.getString(0);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return conversationId;
    }

    /**
     * Get the thread id for an existing conversation from the local DB.
     *
     * @param dbWrapper The database
     * @param conversationId The conversation to look up thread for
     * @return The thread id. Returns -1 if the conversation was not found or if it was found
     * but the thread column was NULL.
     */
    @DoesNotRunOnMainThread
    public static long getThreadId(final DatabaseWrapper dbWrapper, final String conversationId) {
        Assert.isNotMainThread();
        long threadId = -1;

        Cursor cursor = null;
        try {
            cursor = dbWrapper.query(DatabaseHelper.CONVERSATIONS_TABLE,
                    new String[] { ConversationColumns.SMS_THREAD_ID },
                    ConversationColumns._ID + " =?",
                    new String[] { conversationId },
                    null, null, null);

            if (cursor.moveToFirst()) {
                Assert.isTrue(cursor.getCount() == 1);
                if (!cursor.isNull(0)) {
                    threadId = cursor.getLong(0);
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return threadId;
    }

    @DoesNotRunOnMainThread
    public static boolean isBlockedDestination(final DatabaseWrapper db, final String destination) {
        Assert.isNotMainThread();
        return isBlockedParticipant(db, destination, ParticipantColumns.NORMALIZED_DESTINATION);
    }

    static boolean isBlockedParticipant(final DatabaseWrapper db, final String participantId) {
        return isBlockedParticipant(db, participantId, ParticipantColumns._ID);
    }

    static boolean isBlockedParticipant(final DatabaseWrapper db, final String value,
            final String column) {
        Cursor cursor = null;
        try {
            cursor = db.query(DatabaseHelper.PARTICIPANTS_TABLE,
                    new String[] { ParticipantColumns.BLOCKED },
                    column + "=? AND " + ParticipantColumns.SUB_ID + "=?",
                    new String[] { value,
                    Integer.toString(ParticipantData.OTHER_THAN_SELF_SUB_ID) },
                    null, null, null);

            Assert.inRange(cursor.getCount(), 0, 1);
            if (cursor.moveToFirst()) {
                return cursor.getInt(0) == 1;
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return false;  // if there's no row, it's not blocked :-)
    }

    /**
     * Create a conversation in the local DB based on the message's thread id.
     *
     * It's up to the caller to make sure that this is all inside a transaction.  It will return
     * null if it's not in the local DB.
     *
     * @param dbWrapper     The database
     * @param threadId      The message's thread
     * @param selfId        The selfId to make default for this conversation
     * @param archived      Flag whether the conversation should be created archived
     * @param noNotification If notification should be disabled
     * @param noVibrate     If vibrate on notification should be disabled
     * @param soundUri      The customized sound
     * @return The existing conversation id or new conversation id
     */
    static String createConversationInTransaction(final DatabaseWrapper dbWrapper,
            final long threadId, final String conversationName, final String selfId,
            final List<ParticipantData> participants, final boolean archived,
            boolean noNotification, boolean noVibrate, String soundUri) {
        // We want conversation and participant creation to be atomic
        Assert.isTrue(dbWrapper.getDatabase().inTransaction());
        boolean hasEmailAddress = false;
        for (final ParticipantData participant : participants) {
            Assert.isTrue(!participant.isSelf());
            if (participant.isEmail()) {
                hasEmailAddress = true;
            }
        }

        // TODO : Conversations state - normal vs. archived

        // Insert a new local conversation for this thread id
        final ContentValues values = new ContentValues();
        values.put(ConversationColumns.SMS_THREAD_ID, threadId);
        // Start with conversation hidden - sending a message or saving a draft will change that
        values.put(ConversationColumns.SORT_TIMESTAMP, 0L);
        values.put(ConversationColumns.CURRENT_SELF_ID, selfId);
        values.put(ConversationColumns.PARTICIPANT_COUNT, participants.size());
        values.put(ConversationColumns.INCLUDE_EMAIL_ADDRESS, (hasEmailAddress ? 1 : 0));
        if (archived) {
            values.put(ConversationColumns.ARCHIVE_STATUS, 1);
        }
        if (noNotification) {
            values.put(ConversationColumns.NOTIFICATION_ENABLED, 0);
        }
        if (noVibrate) {
            values.put(ConversationColumns.NOTIFICATION_VIBRATION, 0);
        }
        if (!TextUtils.isEmpty(soundUri)) {
            values.put(ConversationColumns.NOTIFICATION_SOUND_URI, soundUri);
        }

        fillParticipantData(values, participants);

        final long conversationRowId = dbWrapper.insert(DatabaseHelper.CONVERSATIONS_TABLE, null,
                values);

        Assert.isTrue(conversationRowId != -1);
        if (conversationRowId == -1) {
            LogUtil.e(TAG, "BugleDatabaseOperations : failed to insert conversation into table");
            return null;
        }

        final String conversationId = Long.toString(conversationRowId);

        // Make sure that participants are added for this conversation
        for (final ParticipantData participant : participants) {
            // TODO: Use blocking information
            addParticipantToConversation(dbWrapper, participant, conversationId);
        }

        // Now fully resolved participants available can update conversation name / avatar.
        // b/16437575: We cannot use the participants directly, but instead have to call
        // getParticipantsForConversation() to retrieve the actual participants. This is needed
        // because the call to addParticipantToConversation() won't fill up the ParticipantData
        // if the participant already exists in the participant table. For example, say you have
        // an existing conversation with John. Now if you create a new group conversation with
        // Jeff & John with only their phone numbers, then when we try to add John's number to the
        // group conversation, we see that he's already in the participant table, therefore we
        // short-circuit any steps to actually fill out the ParticipantData for John other than
        // just returning his participant id. Eventually, the ParticipantData we have is still the
        // raw data with just the phone number. getParticipantsForConversation(), on the other
        // hand, will fill out all the info for each participant from the participants table.
        updateConversationNameAndAvatarInTransaction(dbWrapper, conversationId,
                getParticipantsForConversation(dbWrapper, conversationId));

        return conversationId;
    }

    private static void fillParticipantData(final ContentValues values,
            final List<ParticipantData> participants) {
        if (participants != null && !participants.isEmpty()) {
            final Uri avatarUri = AvatarUriUtil.createAvatarUri(participants);
            values.put(ConversationColumns.ICON, avatarUri.toString());

            long contactId;
            String lookupKey;
            String destination;
            if (participants.size() == 1) {
                final ParticipantData firstParticipant = participants.get(0);
                contactId = firstParticipant.getContactId();
                lookupKey = firstParticipant.getLookupKey();
                destination = firstParticipant.getNormalizedDestination();
            } else {
                contactId = 0;
                lookupKey = null;
                destination = null;
            }

            values.put(ConversationColumns.PARTICIPANT_CONTACT_ID, contactId);
            values.put(ConversationColumns.PARTICIPANT_LOOKUP_KEY, lookupKey);
            values.put(ConversationColumns.OTHER_PARTICIPANT_NORMALIZED_DESTINATION, destination);
        }
    }

    /**
     * Delete conversation and associated messages/parts
     */
    @DoesNotRunOnMainThread
    public static boolean deleteConversation(final DatabaseWrapper dbWrapper,
            final String conversationId, final long cutoffTimestamp) {
        Assert.isNotMainThread();
        dbWrapper.beginTransaction();
        boolean conversationDeleted = false;
        boolean conversationMessagesDeleted = false;
        try {
            // Delete existing messages
            if (cutoffTimestamp == Long.MAX_VALUE) {
                // Delete parts and messages
                dbWrapper.delete(DatabaseHelper.MESSAGES_TABLE,
                        MessageColumns.CONVERSATION_ID + "=?", new String[] { conversationId });
                conversationMessagesDeleted = true;
            } else {
                // Delete all messages prior to the cutoff
                dbWrapper.delete(DatabaseHelper.MESSAGES_TABLE,
                        MessageColumns.CONVERSATION_ID + "=? AND "
                                + MessageColumns.RECEIVED_TIMESTAMP + "<=?",
                                new String[] { conversationId, Long.toString(cutoffTimestamp) });

                // Delete any draft message. The delete above may not always include the draft,
                // because under certain scenarios (e.g. sending messages in progress), the draft
                // timestamp can be larger than the cutoff time, which is generally the conversation
                // sort timestamp. Because of how the sms/mms provider works on some newer
                // devices, it's important that we never delete all the messages in a conversation
                // without also deleting the conversation itself (see b/20262204 for details).
                dbWrapper.delete(DatabaseHelper.MESSAGES_TABLE,
                        MessageColumns.STATUS + "=? AND " + MessageColumns.CONVERSATION_ID + "=?",
                        new String[] {
                            Integer.toString(MessageData.BUGLE_STATUS_OUTGOING_DRAFT),
                            conversationId
                        });

                // Check to see if there are any messages left in the conversation
                final long count = dbWrapper.queryNumEntries(DatabaseHelper.MESSAGES_TABLE,
                        MessageColumns.CONVERSATION_ID + "=?", new String[] { conversationId });
                conversationMessagesDeleted = (count == 0);

                // Log detail information if there are still messages left in the conversation
                if (!conversationMessagesDeleted) {
                    final long maxTimestamp =
                            getConversationMaxTimestamp(dbWrapper, conversationId);
                    LogUtil.w(TAG, "BugleDatabaseOperations:"
                            + " cannot delete all messages in a conversation"
                            + ", after deletion: count=" + count
                            + ", max timestamp=" + maxTimestamp
                            + ", cutoff timestamp=" + cutoffTimestamp);
                }
            }

            if (conversationMessagesDeleted) {
                // Delete conversation row
                final int count = dbWrapper.delete(DatabaseHelper.CONVERSATIONS_TABLE,
                        ConversationColumns._ID + "=?", new String[] { conversationId });
                conversationDeleted = (count > 0);
            }
            dbWrapper.setTransactionSuccessful();
        } finally {
            dbWrapper.endTransaction();
        }
        return conversationDeleted;
    }

    private static final String MAX_RECEIVED_TIMESTAMP =
            "MAX(" + MessageColumns.RECEIVED_TIMESTAMP + ")";
    /**
     * Get the max received timestamp of a conversation's messages
     */
    private static long getConversationMaxTimestamp(final DatabaseWrapper dbWrapper,
            final String conversationId) {
        final Cursor cursor = dbWrapper.query(
                DatabaseHelper.MESSAGES_TABLE,
                new String[]{ MAX_RECEIVED_TIMESTAMP },
                MessageColumns.CONVERSATION_ID + "=?",
                new String[]{ conversationId },
                null, null, null);
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    return cursor.getLong(0);
                }
            } finally {
                cursor.close();
            }
        }
        return 0;
    }

    @DoesNotRunOnMainThread
    public static void updateConversationMetadataInTransaction(final DatabaseWrapper dbWrapper,
            final String conversationId, final String messageId, final long latestTimestamp,
            final boolean keepArchived, final String smsServiceCenter,
            final boolean shouldAutoSwitchSelfId) {
        Assert.isNotMainThread();
        Assert.isTrue(dbWrapper.getDatabase().inTransaction());

        final ContentValues values = new ContentValues();
        values.put(ConversationColumns.LATEST_MESSAGE_ID, messageId);
        values.put(ConversationColumns.SORT_TIMESTAMP, latestTimestamp);
        if (!TextUtils.isEmpty(smsServiceCenter)) {
            values.put(ConversationColumns.SMS_SERVICE_CENTER, smsServiceCenter);
        }

        // When the conversation gets updated with new messages, unarchive the conversation unless
        // the sender is blocked, or we have been told to keep it archived.
        if (!keepArchived) {
            values.put(ConversationColumns.ARCHIVE_STATUS, 0);
        }

        final MessageData message = readMessage(dbWrapper, messageId);
        addSnippetTextAndPreviewToContentValues(message, false /* showDraft */, values);

        if (shouldAutoSwitchSelfId) {
            addSelfIdAutoSwitchInfoToContentValues(dbWrapper, message, conversationId, values);
        }

        // Conversation always exists as this method is called from ActionService only after
        // reading and if necessary creating the conversation.
        updateConversationRow(dbWrapper, conversationId, values);

        if (shouldAutoSwitchSelfId) {
            // Normally, the draft message compose UI trusts its UI state for providing up-to-date
            // conversation self id. Therefore, notify UI through local broadcast receiver about
            // this external change so the change can be properly reflected.
            UIIntents.get().broadcastConversationSelfIdChange(dbWrapper.getContext(),
                    conversationId, getConversationSelfId(dbWrapper, conversationId));
        }
    }

    @DoesNotRunOnMainThread
    public static void updateConversationMetadataInTransaction(final DatabaseWrapper db,
            final String conversationId, final String messageId, final long latestTimestamp,
            final boolean keepArchived, final boolean shouldAutoSwitchSelfId) {
        Assert.isNotMainThread();
        updateConversationMetadataInTransaction(
                db, conversationId, messageId, latestTimestamp, keepArchived, null,
                shouldAutoSwitchSelfId);
    }

    @DoesNotRunOnMainThread
    public static void updateConversationArchiveStatusInTransaction(final DatabaseWrapper dbWrapper,
            final String conversationId, final boolean isArchived) {
        Assert.isNotMainThread();
        Assert.isTrue(dbWrapper.getDatabase().inTransaction());
        final ContentValues values = new ContentValues();
        values.put(ConversationColumns.ARCHIVE_STATUS, isArchived ? 1 : 0);
        updateConversationRowIfExists(dbWrapper, conversationId, values);
    }

    static void addSnippetTextAndPreviewToContentValues(final MessageData message,
            final boolean showDraft, final ContentValues values) {
        values.put(ConversationColumns.SHOW_DRAFT, showDraft ? 1 : 0);
        values.put(ConversationColumns.SNIPPET_TEXT, message.getMessageText());
        values.put(ConversationColumns.SUBJECT_TEXT, message.getMmsSubject());

        String type = null;
        String uriString = null;
        for (final MessagePartData part : message.getParts()) {
            if (part.isAttachment() &&
                    ContentType.isConversationListPreviewableType(part.getContentType())) {
                uriString = part.getContentUri().toString();
                type = part.getContentType();
                break;
            }
        }
        values.put(ConversationColumns.PREVIEW_CONTENT_TYPE, type);
        values.put(ConversationColumns.PREVIEW_URI, uriString);
    }

    /**
     * Adds self-id auto switch info for a conversation if the last message has a different
     * subscription than the conversation's.
     * @return true if self id will need to be changed, false otherwise.
     */
    static boolean addSelfIdAutoSwitchInfoToContentValues(final DatabaseWrapper dbWrapper,
            final MessageData message, final String conversationId, final ContentValues values) {
        // Only auto switch conversation self for incoming messages.
        if (!message.getIsIncoming()) {
            return false;
        }

        final String conversationSelfId = getConversationSelfId(dbWrapper, conversationId);
        final String messageSelfId = message.getSelfId();

        if (conversationSelfId == null || messageSelfId == null) {
            return false;
        }

        // Get the sub IDs in effect for both the message and the conversation and compare them:
        // 1. If message is unbound (using default sub id), then the message was sent with
        //    pre-MSIM support. Don't auto-switch because we don't know the subscription for the
        //    message.
        // 2. If message is bound,
        //    i. If conversation is unbound, use the system default sub id as its effective sub.
        //    ii. If conversation is bound, use its subscription directly.
        //    Compare the message sub id with the conversation's effective sub id. If they are
        //    different, auto-switch the conversation to the message's sub.
        final ParticipantData conversationSelf = getExistingParticipant(dbWrapper,
                conversationSelfId);
        final ParticipantData messageSelf = getExistingParticipant(dbWrapper, messageSelfId);
        if (!messageSelf.isActiveSubscription()) {
            // Don't switch if the message subscription is no longer active.
            return false;
        }
        final int messageSubId = messageSelf.getSubId();
        if (messageSubId == ParticipantData.DEFAULT_SELF_SUB_ID) {
            return false;
        }

        final int conversationEffectiveSubId =
                PhoneUtils.getDefault().getEffectiveSubId(conversationSelf.getSubId());

        if (conversationEffectiveSubId != messageSubId) {
            return addConversationSelfIdToContentValues(dbWrapper, messageSelf.getId(), values);
        }
        return false;
    }

    /**
     * Adds conversation self id updates to ContentValues given. This performs check on the selfId
     * to ensure it's valid and active.
     * @return true if self id will need to be changed, false otherwise.
     */
    static boolean addConversationSelfIdToContentValues(final DatabaseWrapper dbWrapper,
            final String selfId, final ContentValues values) {
        // Make sure the selfId passed in is valid and active.
        final String selection = ParticipantColumns._ID + "=? AND " +
                ParticipantColumns.SIM_SLOT_ID + "<>?";
        Cursor cursor = null;
        try {
            cursor = dbWrapper.query(DatabaseHelper.PARTICIPANTS_TABLE,
                    new String[] { ParticipantColumns._ID }, selection,
                    new String[] { selfId, String.valueOf(ParticipantData.INVALID_SLOT_ID) },
                    null, null, null);

            if (cursor != null && cursor.getCount() > 0) {
                values.put(ConversationColumns.CURRENT_SELF_ID, selfId);
                return true;
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return false;
    }

    private static void updateConversationDraftSnippetAndPreviewInTransaction(
            final DatabaseWrapper dbWrapper, final String conversationId,
            final MessageData draftMessage) {
        Assert.isTrue(dbWrapper.getDatabase().inTransaction());

        long sortTimestamp = 0L;
        Cursor cursor = null;
        try {
            // Check to find the latest message in the conversation
            cursor = dbWrapper.query(DatabaseHelper.MESSAGES_TABLE,
                    REFRESH_CONVERSATION_MESSAGE_PROJECTION,
                    MessageColumns.CONVERSATION_ID + "=?",
                    new String[]{conversationId}, null, null,
                    MessageColumns.RECEIVED_TIMESTAMP + " DESC", "1" /* limit */);

            if (cursor.moveToFirst()) {
                sortTimestamp = cursor.getLong(1);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }


        final ContentValues values = new ContentValues();
        if (draftMessage == null || !draftMessage.hasContent()) {
            values.put(ConversationColumns.SHOW_DRAFT, 0);
            values.put(ConversationColumns.DRAFT_SNIPPET_TEXT, "");
            values.put(ConversationColumns.DRAFT_SUBJECT_TEXT, "");
            values.put(ConversationColumns.DRAFT_PREVIEW_CONTENT_TYPE, "");
            values.put(ConversationColumns.DRAFT_PREVIEW_URI, "");
        } else {
            sortTimestamp = Math.max(sortTimestamp, draftMessage.getReceivedTimeStamp());
            values.put(ConversationColumns.SHOW_DRAFT, 1);
            values.put(ConversationColumns.DRAFT_SNIPPET_TEXT, draftMessage.getMessageText());
            values.put(ConversationColumns.DRAFT_SUBJECT_TEXT, draftMessage.getMmsSubject());
            String type = null;
            String uriString = null;
            for (final MessagePartData part : draftMessage.getParts()) {
                if (part.isAttachment() &&
                        ContentType.isConversationListPreviewableType(part.getContentType())) {
                    uriString = part.getContentUri().toString();
                    type = part.getContentType();
                    break;
                }
            }
            values.put(ConversationColumns.DRAFT_PREVIEW_CONTENT_TYPE, type);
            values.put(ConversationColumns.DRAFT_PREVIEW_URI, uriString);
        }
        values.put(ConversationColumns.SORT_TIMESTAMP, sortTimestamp);
        // Called in transaction after reading conversation row
        updateConversationRow(dbWrapper, conversationId, values);
    }

    @DoesNotRunOnMainThread
    public static boolean updateConversationRowIfExists(final DatabaseWrapper dbWrapper,
            final String conversationId, final ContentValues values) {
        Assert.isNotMainThread();
        return updateRowIfExists(dbWrapper, DatabaseHelper.CONVERSATIONS_TABLE,
                ConversationColumns._ID, conversationId, values);
    }

    @DoesNotRunOnMainThread
    public static void updateConversationRow(final DatabaseWrapper dbWrapper,
            final String conversationId, final ContentValues values) {
        Assert.isNotMainThread();
        final boolean exists = updateConversationRowIfExists(dbWrapper, conversationId, values);
        Assert.isTrue(exists);
    }

    @DoesNotRunOnMainThread
    public static boolean updateMessageRowIfExists(final DatabaseWrapper dbWrapper,
            final String messageId, final ContentValues values) {
        Assert.isNotMainThread();
        return updateRowIfExists(dbWrapper, DatabaseHelper.MESSAGES_TABLE, MessageColumns._ID,
                messageId, values);
    }

    @DoesNotRunOnMainThread
    public static void updateMessageRow(final DatabaseWrapper dbWrapper,
            final String messageId, final ContentValues values) {
        Assert.isNotMainThread();
        final boolean exists = updateMessageRowIfExists(dbWrapper, messageId, values);
        Assert.isTrue(exists);
    }

    @DoesNotRunOnMainThread
    public static boolean updatePartRowIfExists(final DatabaseWrapper dbWrapper,
            final String partId, final ContentValues values) {
        Assert.isNotMainThread();
        return updateRowIfExists(dbWrapper, DatabaseHelper.PARTS_TABLE, PartColumns._ID,
                partId, values);
    }

    /**
     * Returns the default conversation name based on its participants.
     */
    private static String getDefaultConversationName(final List<ParticipantData> participants) {
        return ConversationListItemData.generateConversationName(participants);
    }

    /**
     * Updates a given conversation's name based on its participants.
     */
    @DoesNotRunOnMainThread
    public static void updateConversationNameAndAvatarInTransaction(
            final DatabaseWrapper dbWrapper, final String conversationId) {
        Assert.isNotMainThread();
        Assert.isTrue(dbWrapper.getDatabase().inTransaction());

        final ArrayList<ParticipantData> participants =
                getParticipantsForConversation(dbWrapper, conversationId);
        updateConversationNameAndAvatarInTransaction(dbWrapper, conversationId, participants);
    }

    /**
     * Updates a given conversation's name based on its participants.
     */
    private static void updateConversationNameAndAvatarInTransaction(
            final DatabaseWrapper dbWrapper, final String conversationId,
            final List<ParticipantData> participants) {
        Assert.isTrue(dbWrapper.getDatabase().inTransaction());

        final ContentValues values = new ContentValues();
        values.put(ConversationColumns.NAME,
                getDefaultConversationName(participants));

        // Fill in IS_ENTERPRISE.
        final boolean hasAnyEnterpriseContact =
                ConversationListItemData.hasAnyEnterpriseContact(participants);
        values.put(ConversationColumns.IS_ENTERPRISE, hasAnyEnterpriseContact);

        fillParticipantData(values, participants);

        // Used by background thread when refreshing conversation so conversation could be deleted.
        updateConversationRowIfExists(dbWrapper, conversationId, values);

        WidgetConversationProvider.notifyConversationRenamed(Factory.get().getApplicationContext(),
                conversationId);
    }

    /**
     * Updates a given conversation's self id.
     */
    @DoesNotRunOnMainThread
    public static void updateConversationSelfIdInTransaction(
            final DatabaseWrapper dbWrapper, final String conversationId, final String selfId) {
        Assert.isNotMainThread();
        Assert.isTrue(dbWrapper.getDatabase().inTransaction());
        final ContentValues values = new ContentValues();
        if (addConversationSelfIdToContentValues(dbWrapper, selfId, values)) {
            updateConversationRowIfExists(dbWrapper, conversationId, values);
        }
    }

    @DoesNotRunOnMainThread
    public static String getConversationSelfId(final DatabaseWrapper dbWrapper,
            final String conversationId) {
        Assert.isNotMainThread();
        Cursor cursor = null;
        try {
            cursor = dbWrapper.query(DatabaseHelper.CONVERSATIONS_TABLE,
                    new String[] { ConversationColumns.CURRENT_SELF_ID },
                    ConversationColumns._ID + "=?",
                    new String[] { conversationId },
                    null, null, null);
            Assert.inRange(cursor.getCount(), 0, 1);
            if (cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    /**
     * Frees up memory associated with phone number to participant id matching.
     */
    @DoesNotRunOnMainThread
    public static void clearParticipantIdCache() {
        Assert.isNotMainThread();
        synchronized (sNormalizedPhoneNumberToParticipantIdCache) {
            sNormalizedPhoneNumberToParticipantIdCache.clear();
        }
    }

    @DoesNotRunOnMainThread
    public static ArrayList<String> getRecipientsForConversation(final DatabaseWrapper dbWrapper,
            final String conversationId) {
        Assert.isNotMainThread();
        final ArrayList<ParticipantData> participants =
                getParticipantsForConversation(dbWrapper, conversationId);

        final ArrayList<String> recipients = new ArrayList<String>();
        for (final ParticipantData participant : participants) {
            recipients.add(participant.getSendDestination());
        }

        return recipients;
    }

    @DoesNotRunOnMainThread
    public static String getSmsServiceCenterForConversation(final DatabaseWrapper dbWrapper,
            final String conversationId) {
        Assert.isNotMainThread();
        Cursor cursor = null;
        try {
            cursor = dbWrapper.query(DatabaseHelper.CONVERSATIONS_TABLE,
                    new String[] { ConversationColumns.SMS_SERVICE_CENTER },
                    ConversationColumns._ID + "=?",
                    new String[] { conversationId },
                    null, null, null);
            Assert.inRange(cursor.getCount(), 0, 1);
            if (cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    @DoesNotRunOnMainThread
    public static ParticipantData getExistingParticipant(final DatabaseWrapper dbWrapper,
            final String participantId) {
        Assert.isNotMainThread();
        ParticipantData participant = null;
        Cursor cursor = null;
        try {
            cursor = dbWrapper.query(DatabaseHelper.PARTICIPANTS_TABLE,
                    ParticipantData.ParticipantsQuery.PROJECTION,
                    ParticipantColumns._ID + " =?",
                    new String[] { participantId }, null, null, null);
            Assert.inRange(cursor.getCount(), 0, 1);
            if (cursor.moveToFirst()) {
                participant = ParticipantData.getFromCursor(cursor);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return participant;
    }

    public static int getSelfSubscriptionId(final DatabaseWrapper dbWrapper,
            final String selfParticipantId) {
        final ParticipantData selfParticipant = BugleDatabaseOperations.getExistingParticipant(
                dbWrapper, selfParticipantId);
        if (selfParticipant != null) {
            Assert.isTrue(selfParticipant.isSelf());
            return selfParticipant.getSubId();
        }
        return ParticipantData.DEFAULT_SELF_SUB_ID;
    }

    @VisibleForTesting
    @DoesNotRunOnMainThread
    public static ArrayList<ParticipantData> getParticipantsForConversation(
            final DatabaseWrapper dbWrapper, final String conversationId) {
        Assert.isNotMainThread();
        final ArrayList<ParticipantData> participants =
                new ArrayList<ParticipantData>();
        Cursor cursor = null;
        try {
            cursor = dbWrapper.query(DatabaseHelper.PARTICIPANTS_TABLE,
                    ParticipantData.ParticipantsQuery.PROJECTION,
                    ParticipantColumns._ID + " IN ( " + "SELECT "
                            + ConversationParticipantsColumns.PARTICIPANT_ID + " AS "
                            + ParticipantColumns._ID
                            + " FROM " + DatabaseHelper.CONVERSATION_PARTICIPANTS_TABLE
                            + " WHERE " + ConversationParticipantsColumns.CONVERSATION_ID + " =? )",
                            new String[] { conversationId }, null, null, null);

            while (cursor.moveToNext()) {
                participants.add(ParticipantData.getFromCursor(cursor));
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return participants;
    }

    @DoesNotRunOnMainThread
    public static MessageData readMessage(final DatabaseWrapper dbWrapper, final String messageId) {
        Assert.isNotMainThread();
        final MessageData message = readMessageData(dbWrapper, messageId);
        if (message != null) {
            readMessagePartsData(dbWrapper, message, false);
        }
        return message;
    }

    @VisibleForTesting
    static MessagePartData readMessagePartData(final DatabaseWrapper dbWrapper,
            final String partId) {
        MessagePartData messagePartData = null;
        Cursor cursor = null;
        try {
            cursor = dbWrapper.query(DatabaseHelper.PARTS_TABLE,
                    MessagePartData.getProjection(), PartColumns._ID + "=?",
                    new String[] { partId }, null, null, null);
            Assert.inRange(cursor.getCount(), 0, 1);
            if (cursor.moveToFirst()) {
                messagePartData = MessagePartData.createFromCursor(cursor);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return messagePartData;
    }

    @DoesNotRunOnMainThread
    public static MessageData readMessageData(final DatabaseWrapper dbWrapper,
            final Uri smsMessageUri) {
        Assert.isNotMainThread();
        MessageData message = null;
        Cursor cursor = null;
        try {
            cursor = dbWrapper.query(DatabaseHelper.MESSAGES_TABLE,
                    MessageData.getProjection(), MessageColumns.SMS_MESSAGE_URI + "=?",
                    new String[] { smsMessageUri.toString() }, null, null, null);
            Assert.inRange(cursor.getCount(), 0, 1);
            if (cursor.moveToFirst()) {
                message = new MessageData();
                message.bind(cursor);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return message;
    }

    @DoesNotRunOnMainThread
    public static MessageData readMessageData(final DatabaseWrapper dbWrapper,
            final String messageId) {
        Assert.isNotMainThread();
        MessageData message = null;
        Cursor cursor = null;
        try {
            cursor = dbWrapper.query(DatabaseHelper.MESSAGES_TABLE,
                    MessageData.getProjection(), MessageColumns._ID + "=?",
                    new String[] { messageId }, null, null, null);
            Assert.inRange(cursor.getCount(), 0, 1);
            if (cursor.moveToFirst()) {
                message = new MessageData();
                message.bind(cursor);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return message;
    }

    /**
     * Read all the parts for a message
     * @param dbWrapper database
     * @param message read parts for this message
     * @param checkAttachmentFilesExist check each attachment file and only include if file exists
     */
    private static void readMessagePartsData(final DatabaseWrapper dbWrapper,
            final MessageData message, final boolean checkAttachmentFilesExist) {
        final ContentResolver contentResolver =
                Factory.get().getApplicationContext().getContentResolver();
        Cursor cursor = null;
        try {
            cursor = dbWrapper.query(DatabaseHelper.PARTS_TABLE,
                    MessagePartData.getProjection(), PartColumns.MESSAGE_ID + "=?",
                    new String[] { message.getMessageId() }, null, null, null);
            while (cursor.moveToNext()) {
                final MessagePartData messagePartData = MessagePartData.createFromCursor(cursor);
                if (checkAttachmentFilesExist && messagePartData.isAttachment() &&
                        !UriUtil.isBugleAppResource(messagePartData.getContentUri())) {
                    try {
                        // Test that the file exists before adding the attachment to the draft
                        final ParcelFileDescriptor fileDescriptor =
                                contentResolver.openFileDescriptor(
                                        messagePartData.getContentUri(), "r");
                        if (fileDescriptor != null) {
                            fileDescriptor.close();
                            message.addPart(messagePartData);
                        }
                    } catch (final IOException e) {
                        // The attachment's temp storage no longer exists, just ignore the file
                    } catch (final SecurityException e) {
                        // Likely thrown by openFileDescriptor due to an expired access grant.
                        if (LogUtil.isLoggable(LogUtil.BUGLE_TAG, LogUtil.DEBUG)) {
                            LogUtil.d(LogUtil.BUGLE_TAG, "uri: " + messagePartData.getContentUri());
                        }
                    }
                } else {
                    message.addPart(messagePartData);
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /**
     * Write a message part to our local database
     *
     * @param dbWrapper     The database
     * @param messagePart   The message part to insert
     * @return The row id of the newly inserted part
     */
    static String insertNewMessagePartInTransaction(final DatabaseWrapper dbWrapper,
            final MessagePartData messagePart, final String conversationId) {
        Assert.isTrue(dbWrapper.getDatabase().inTransaction());
        Assert.isTrue(!TextUtils.isEmpty(messagePart.getMessageId()));

        // Insert a new part row
        final SQLiteStatement insert = messagePart.getInsertStatement(dbWrapper, conversationId);
        final long rowNumber = insert.executeInsert();

        Assert.inRange(rowNumber, 0, Long.MAX_VALUE);
        final String partId = Long.toString(rowNumber);

        // Update the part id
        messagePart.updatePartId(partId);

        return partId;
    }

    /**
     * Insert a message and its parts into the table
     */
    @DoesNotRunOnMainThread
    public static void insertNewMessageInTransaction(final DatabaseWrapper dbWrapper,
            final MessageData message) {
        Assert.isNotMainThread();
        Assert.isTrue(dbWrapper.getDatabase().inTransaction());

        // Insert message row
        final SQLiteStatement insert = message.getInsertStatement(dbWrapper);
        final long rowNumber = insert.executeInsert();

        Assert.inRange(rowNumber, 0, Long.MAX_VALUE);
        final String messageId = Long.toString(rowNumber);
        message.updateMessageId(messageId);
        //  Insert new parts
        for (final MessagePartData messagePart : message.getParts()) {
            messagePart.updateMessageId(messageId);
            insertNewMessagePartInTransaction(dbWrapper, messagePart, message.getConversationId());
        }
    }

    /**
     * Update a message and add its parts into the table
     */
    @DoesNotRunOnMainThread
    public static void updateMessageInTransaction(final DatabaseWrapper dbWrapper,
            final MessageData message) {
        Assert.isNotMainThread();
        Assert.isTrue(dbWrapper.getDatabase().inTransaction());
        final String messageId = message.getMessageId();
        // Check message still exists (sms sync or delete might have purged it)
        final MessageData current = BugleDatabaseOperations.readMessage(dbWrapper, messageId);
        if (current != null) {
            // Delete existing message parts)
            deletePartsForMessage(dbWrapper, message.getMessageId());
            //  Insert new parts
            for (final MessagePartData messagePart : message.getParts()) {
                messagePart.updatePartId(null);
                messagePart.updateMessageId(message.getMessageId());
                insertNewMessagePartInTransaction(dbWrapper, messagePart,
                        message.getConversationId());
            }
            //  Update message row
            final ContentValues values = new ContentValues();
            message.populate(values);
            updateMessageRowIfExists(dbWrapper, message.getMessageId(), values);
        }
    }

    @DoesNotRunOnMainThread
    public static void updateMessageAndPartsInTransaction(final DatabaseWrapper dbWrapper,
            final MessageData message, final List<MessagePartData> partsToUpdate) {
        Assert.isNotMainThread();
        Assert.isTrue(dbWrapper.getDatabase().inTransaction());
        final ContentValues values = new ContentValues();
        for (final MessagePartData messagePart : partsToUpdate) {
            values.clear();
            messagePart.populate(values);
            updatePartRowIfExists(dbWrapper, messagePart.getPartId(), values);
        }
        values.clear();
        message.populate(values);
        updateMessageRowIfExists(dbWrapper, message.getMessageId(), values);
    }

    /**
     * Delete all parts for a message
     */
    static void deletePartsForMessage(final DatabaseWrapper dbWrapper,
            final String messageId) {
        final int cnt = dbWrapper.delete(DatabaseHelper.PARTS_TABLE,
                PartColumns.MESSAGE_ID + " =?",
                new String[] { messageId });
        Assert.inRange(cnt, 0, Integer.MAX_VALUE);
    }

    /**
     * Delete one message and update the conversation (if necessary).
     *
     * @return number of rows deleted (should be 1 or 0).
     */
    @DoesNotRunOnMainThread
    public static int deleteMessage(final DatabaseWrapper dbWrapper, final String messageId) {
        Assert.isNotMainThread();
        dbWrapper.beginTransaction();
        try {
            // Read message to find out which conversation it is in
            final MessageData message = BugleDatabaseOperations.readMessage(dbWrapper, messageId);

            int count = 0;
            if (message != null) {
                final String conversationId = message.getConversationId();
                // Delete message
                count = dbWrapper.delete(DatabaseHelper.MESSAGES_TABLE,
                        MessageColumns._ID + "=?", new String[] { messageId });

                if (!deleteConversationIfEmptyInTransaction(dbWrapper, conversationId)) {
                    // TODO: Should we leave the conversation sort timestamp alone?
                    refreshConversationMetadataInTransaction(dbWrapper, conversationId,
                            false/* shouldAutoSwitchSelfId */, false/*archived*/);
                }
            }
            dbWrapper.setTransactionSuccessful();
            return count;
        } finally {
            dbWrapper.endTransaction();
        }
    }

    /**
     * Deletes the conversation if there are zero non-draft messages left.
     * <p>
     * This is necessary because the telephony database has a trigger that deletes threads after
     * their last message is deleted. We need to ensure that if a thread goes away, we also delete
     * the conversation in Bugle. We don't store draft messages in telephony, so we ignore those
     * when querying for the # of messages in the conversation.
     *
     * @return true if the conversation was deleted
     */
    @DoesNotRunOnMainThread
    public static boolean deleteConversationIfEmptyInTransaction(final DatabaseWrapper dbWrapper,
            final String conversationId) {
        Assert.isNotMainThread();
        Assert.isTrue(dbWrapper.getDatabase().inTransaction());
        Cursor cursor = null;
        try {
            // TODO: The refreshConversationMetadataInTransaction method below uses this
            // same query; maybe they should share this logic?

            // Check to see if there are any (non-draft) messages in the conversation
            cursor = dbWrapper.query(DatabaseHelper.MESSAGES_TABLE,
                    REFRESH_CONVERSATION_MESSAGE_PROJECTION,
                    MessageColumns.CONVERSATION_ID + "=? AND " +
                    MessageColumns.STATUS + "!=" + MessageData.BUGLE_STATUS_OUTGOING_DRAFT,
                    new String[] { conversationId }, null, null,
                    MessageColumns.RECEIVED_TIMESTAMP + " DESC", "1" /* limit */);
            if (cursor.getCount() == 0) {
                dbWrapper.delete(DatabaseHelper.CONVERSATIONS_TABLE,
                        ConversationColumns._ID + "=?", new String[] { conversationId });
                LogUtil.i(TAG,
                        "BugleDatabaseOperations: Deleted empty conversation " + conversationId);
                return true;
            } else {
                return false;
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private static final String[] REFRESH_CONVERSATION_MESSAGE_PROJECTION = new String[] {
        MessageColumns._ID,
        MessageColumns.RECEIVED_TIMESTAMP,
        MessageColumns.SENDER_PARTICIPANT_ID
    };

    /**
     * Update conversation snippet, timestamp and optionally self id to match latest message in
     * conversation.
     */
    @DoesNotRunOnMainThread
    public static void refreshConversationMetadataInTransaction(final DatabaseWrapper dbWrapper,
            final String conversationId, final boolean shouldAutoSwitchSelfId,
            boolean keepArchived) {
        Assert.isNotMainThread();
        Assert.isTrue(dbWrapper.getDatabase().inTransaction());
        Cursor cursor = null;
        try {
            // Check to see if there are any (non-draft) messages in the conversation
            cursor = dbWrapper.query(DatabaseHelper.MESSAGES_TABLE,
                    REFRESH_CONVERSATION_MESSAGE_PROJECTION,
                    MessageColumns.CONVERSATION_ID + "=? AND " +
                    MessageColumns.STATUS + "!=" + MessageData.BUGLE_STATUS_OUTGOING_DRAFT,
                    new String[] { conversationId }, null, null,
                    MessageColumns.RECEIVED_TIMESTAMP + " DESC", "1" /* limit */);

            if (cursor.moveToFirst()) {
                // Refresh latest message in conversation
                final String latestMessageId = cursor.getString(0);
                final long latestMessageTimestamp = cursor.getLong(1);
                final String senderParticipantId = cursor.getString(2);
                final boolean senderBlocked = isBlockedParticipant(dbWrapper, senderParticipantId);
                updateConversationMetadataInTransaction(dbWrapper, conversationId,
                        latestMessageId, latestMessageTimestamp, senderBlocked || keepArchived,
                        shouldAutoSwitchSelfId);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /**
     * When moving/removing an existing message update conversation metadata if necessary
     * @param dbWrapper      db wrapper
     * @param conversationId conversation to modify
     * @param messageId      message that is leaving the conversation
     * @param shouldAutoSwitchSelfId should we try to auto-switch the conversation's self-id as a
     *        result of this call when we see a new latest message?
     * @param keepArchived   should we keep the conversation archived despite refresh
     */
    @DoesNotRunOnMainThread
    public static void maybeRefreshConversationMetadataInTransaction(
            final DatabaseWrapper dbWrapper, final String conversationId, final String messageId,
            final boolean shouldAutoSwitchSelfId, final boolean keepArchived) {
        Assert.isNotMainThread();
        boolean refresh = true;
        if (!TextUtils.isEmpty(messageId)) {
            refresh = false;
            // Look for an existing conversation in the db with this conversation id
            Cursor cursor = null;
            try {
                cursor = dbWrapper.query(DatabaseHelper.CONVERSATIONS_TABLE,
                        new String[] { ConversationColumns.LATEST_MESSAGE_ID },
                        ConversationColumns._ID + "=?",
                        new String[] { conversationId },
                        null, null, null);
                Assert.inRange(cursor.getCount(), 0, 1);
                if (cursor.moveToFirst()) {
                    refresh = TextUtils.equals(cursor.getString(0), messageId);
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        if (refresh) {
            // TODO: I think it is okay to delete the conversation if it is empty...
            refreshConversationMetadataInTransaction(dbWrapper, conversationId,
                    shouldAutoSwitchSelfId, keepArchived);
        }
    }



    // SQL statement to query latest message if for particular conversation
    private static final String QUERY_CONVERSATIONS_LATEST_MESSAGE_SQL = "SELECT "
            + ConversationColumns.LATEST_MESSAGE_ID + " FROM " + DatabaseHelper.CONVERSATIONS_TABLE
            + " WHERE " + ConversationColumns._ID + "=? LIMIT 1";

    /**
     * Note this is not thread safe so callers need to make sure they own the wrapper + statements
     * while they call this and use the returned value.
     */
    @DoesNotRunOnMainThread
    public static SQLiteStatement getQueryConversationsLatestMessageStatement(
            final DatabaseWrapper db, final String conversationId) {
        Assert.isNotMainThread();
        final SQLiteStatement query = db.getStatementInTransaction(
                DatabaseWrapper.INDEX_QUERY_CONVERSATIONS_LATEST_MESSAGE,
                QUERY_CONVERSATIONS_LATEST_MESSAGE_SQL);
        query.clearBindings();
        query.bindString(1, conversationId);
        return query;
    }

    // SQL statement to query latest message if for particular conversation
    private static final String QUERY_MESSAGES_LATEST_MESSAGE_SQL = "SELECT "
            + MessageColumns._ID + " FROM " + DatabaseHelper.MESSAGES_TABLE
            + " WHERE " + MessageColumns.CONVERSATION_ID + "=? ORDER BY "
            + MessageColumns.RECEIVED_TIMESTAMP + " DESC LIMIT 1";

    /**
     * Note this is not thread safe so callers need to make sure they own the wrapper + statements
     * while they call this and use the returned value.
     */
    @DoesNotRunOnMainThread
    public static SQLiteStatement getQueryMessagesLatestMessageStatement(
            final DatabaseWrapper db, final String conversationId) {
        Assert.isNotMainThread();
        final SQLiteStatement query = db.getStatementInTransaction(
                DatabaseWrapper.INDEX_QUERY_MESSAGES_LATEST_MESSAGE,
                QUERY_MESSAGES_LATEST_MESSAGE_SQL);
        query.clearBindings();
        query.bindString(1, conversationId);
        return query;
    }

    /**
     * Update conversation metadata if necessary
     * @param dbWrapper      db wrapper
     * @param conversationId conversation to modify
     * @param shouldAutoSwitchSelfId should we try to auto-switch the conversation's self-id as a
     *                               result of this call when we see a new latest message?
     * @param keepArchived if the conversation should be kept archived
     */
    @DoesNotRunOnMainThread
    public static void maybeRefreshConversationMetadataInTransaction(
            final DatabaseWrapper dbWrapper, final String conversationId,
            final boolean shouldAutoSwitchSelfId, boolean keepArchived) {
        Assert.isNotMainThread();
        String currentLatestMessageId = null;
        String latestMessageId = null;
        try {
            final SQLiteStatement currentLatestMessageIdSql =
                    getQueryConversationsLatestMessageStatement(dbWrapper, conversationId);
            currentLatestMessageId = currentLatestMessageIdSql.simpleQueryForString();

            final SQLiteStatement latestMessageIdSql =
                    getQueryMessagesLatestMessageStatement(dbWrapper, conversationId);
            latestMessageId = latestMessageIdSql.simpleQueryForString();
        } catch (final SQLiteDoneException e) {
            LogUtil.e(TAG, "BugleDatabaseOperations: Query for latest message failed", e);
        }

        if (TextUtils.isEmpty(currentLatestMessageId) ||
                !TextUtils.equals(currentLatestMessageId, latestMessageId)) {
            refreshConversationMetadataInTransaction(dbWrapper, conversationId,
                    shouldAutoSwitchSelfId, keepArchived);
        }
    }

    static boolean getConversationExists(final DatabaseWrapper dbWrapper,
            final String conversationId) {
        // Look for an existing conversation in the db with this conversation id
        Cursor cursor = null;
        try {
            cursor = dbWrapper.query(DatabaseHelper.CONVERSATIONS_TABLE,
                    new String[] { /* No projection */},
                    ConversationColumns._ID + "=?",
                    new String[] { conversationId },
                    null, null, null);
            return cursor.getCount() == 1;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /** Preserve parts in message but clear the stored draft */
    public static final int UPDATE_MODE_CLEAR_DRAFT = 1;
    /** Add the message as a draft */
    public static final int UPDATE_MODE_ADD_DRAFT = 2;

    /**
     * Update draft message for specified conversation
     * @param dbWrapper       local database (wrapped)
     * @param conversationId  conversation to update
     * @param message         Optional message to preserve attachments for (either as draft or for
     *                        sending)
     * @param updateMode      either {@link #UPDATE_MODE_CLEAR_DRAFT} or
     *                        {@link #UPDATE_MODE_ADD_DRAFT}
     * @return message id of newly written draft (else null)
     */
    @DoesNotRunOnMainThread
    public static String updateDraftMessageData(final DatabaseWrapper dbWrapper,
            final String conversationId, @Nullable final MessageData message,
            final int updateMode) {
        Assert.isNotMainThread();
        Assert.notNull(conversationId);
        Assert.inRange(updateMode, UPDATE_MODE_CLEAR_DRAFT, UPDATE_MODE_ADD_DRAFT);
        String messageId = null;
        Cursor cursor = null;
        dbWrapper.beginTransaction();
        try {
            // Find all draft parts for the current conversation
            final SimpleArrayMap<Uri, MessagePartData> currentDraftParts = new SimpleArrayMap<>();
            cursor = dbWrapper.query(DatabaseHelper.DRAFT_PARTS_VIEW,
                    MessagePartData.getProjection(),
                    MessageColumns.CONVERSATION_ID + " =?",
                    new String[] { conversationId }, null, null, null);
            while (cursor.moveToNext()) {
                final MessagePartData part = MessagePartData.createFromCursor(cursor);
                if (part.isAttachment()) {
                    currentDraftParts.put(part.getContentUri(), part);
                }
            }
            // Optionally, preserve attachments for "message"
            final boolean conversationExists = getConversationExists(dbWrapper, conversationId);
            if (message != null && conversationExists) {
                for (final MessagePartData part : message.getParts()) {
                    if (part.isAttachment()) {
                        currentDraftParts.remove(part.getContentUri());
                    }
                }
            }

            // Delete orphan content
            for (int index = 0; index < currentDraftParts.size(); index++) {
                final MessagePartData part = currentDraftParts.valueAt(index);
                part.destroySync();
            }

            // Delete existing draft (cascade deletes parts)
            dbWrapper.delete(DatabaseHelper.MESSAGES_TABLE,
                    MessageColumns.STATUS + "=? AND " + MessageColumns.CONVERSATION_ID + "=?",
                    new String[] {
                        Integer.toString(MessageData.BUGLE_STATUS_OUTGOING_DRAFT),
                        conversationId
                    });

            // Write new draft
            if (updateMode == UPDATE_MODE_ADD_DRAFT && message != null
                    && message.hasContent() && conversationExists) {
                Assert.equals(MessageData.BUGLE_STATUS_OUTGOING_DRAFT,
                        message.getStatus());

                // Now add draft to message table
                insertNewMessageInTransaction(dbWrapper, message);
                messageId = message.getMessageId();
            }

            if (conversationExists) {
                updateConversationDraftSnippetAndPreviewInTransaction(
                        dbWrapper, conversationId, message);

                if (message != null && message.getSelfId() != null) {
                    updateConversationSelfIdInTransaction(dbWrapper, conversationId,
                            message.getSelfId());
                }
            }

            dbWrapper.setTransactionSuccessful();
        } finally {
            dbWrapper.endTransaction();
            if (cursor != null) {
                cursor.close();
            }
        }
        if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
            LogUtil.v(TAG,
                    "Updated draft message " + messageId + " for conversation " + conversationId);
        }
        return messageId;
    }

    /**
     * Read the first draft message associated with this conversation.
     * If none present create an empty (sms) draft message.
     */
    @DoesNotRunOnMainThread
    public static MessageData readDraftMessageData(final DatabaseWrapper dbWrapper,
            final String conversationId, final String conversationSelfId) {
        Assert.isNotMainThread();
        MessageData message = null;
        Cursor cursor = null;
        dbWrapper.beginTransaction();
        try {
            cursor = dbWrapper.query(DatabaseHelper.MESSAGES_TABLE,
                    MessageData.getProjection(),
                    MessageColumns.STATUS + "=? AND " + MessageColumns.CONVERSATION_ID + "=?",
                    new String[] {
                        Integer.toString(MessageData.BUGLE_STATUS_OUTGOING_DRAFT),
                        conversationId
                    }, null, null, null);
            Assert.inRange(cursor.getCount(), 0, 1);
            if (cursor.moveToFirst()) {
                message = new MessageData();
                message.bindDraft(cursor, conversationSelfId);
                readMessagePartsData(dbWrapper, message, true);
                // Disconnect draft parts from DB
                for (final MessagePartData part : message.getParts()) {
                    part.updatePartId(null);
                    part.updateMessageId(null);
                }
                message.updateMessageId(null);
            }
            dbWrapper.setTransactionSuccessful();
        } finally {
            dbWrapper.endTransaction();
            if (cursor != null) {
                cursor.close();
            }
        }
        return message;
    }

    // Internal
    private static void addParticipantToConversation(final DatabaseWrapper dbWrapper,
            final ParticipantData participant, final String conversationId) {
        final String participantId = getOrCreateParticipantInTransaction(dbWrapper, participant);
        Assert.notNull(participantId);

        // Add the participant to the conversation participants table
        final ContentValues values = new ContentValues();
        values.put(ConversationParticipantsColumns.CONVERSATION_ID, conversationId);
        values.put(ConversationParticipantsColumns.PARTICIPANT_ID, participantId);
        dbWrapper.insert(DatabaseHelper.CONVERSATION_PARTICIPANTS_TABLE, null, values);
    }

    /**
     * Get string used as canonical recipient for participant cache for sub id
     */
    private static String getCanonicalRecipientFromSubId(final int subId) {
        return "SELF(" + subId + ")";
    }

    /**
     * Maps from a sub id or phone number to a participant id if there is one.
     *
     * @return If the participant is available in our cache, or the DB, this returns the
     * participant id for the given subid/phone number.  Otherwise it returns null.
     */
    @VisibleForTesting
    private static String getParticipantId(final DatabaseWrapper dbWrapper,
            final int subId, final String canonicalRecipient) {
        // First check our memory cache for the participant Id
        String participantId;
        synchronized (sNormalizedPhoneNumberToParticipantIdCache) {
            participantId = sNormalizedPhoneNumberToParticipantIdCache.get(canonicalRecipient);
        }

        if (participantId != null) {
            return participantId;
        }

        // This code will only be executed for incremental additions.
        Cursor cursor = null;
        try {
            if (subId != ParticipantData.OTHER_THAN_SELF_SUB_ID) {
                // Now look for an existing participant in the db with this sub id.
                cursor = dbWrapper.query(DatabaseHelper.PARTICIPANTS_TABLE,
                        new String[] {ParticipantColumns._ID},
                        ParticipantColumns.SUB_ID + "=?",
                        new String[] { Integer.toString(subId) }, null, null, null);
            } else {
                // Look for existing participant with this normalized phone number and no subId.
                cursor = dbWrapper.query(DatabaseHelper.PARTICIPANTS_TABLE,
                        new String[] {ParticipantColumns._ID},
                        ParticipantColumns.NORMALIZED_DESTINATION + "=? AND "
                                + ParticipantColumns.SUB_ID + "=?",
                                new String[] {canonicalRecipient, Integer.toString(subId)},
                                null, null, null);
            }

            if (cursor.moveToFirst()) {
                // TODO Is this assert correct for multi-sim where a new sim was put in?
                Assert.isTrue(cursor.getCount() == 1);

                // We found an existing participant in the database
                participantId = cursor.getString(0);

                synchronized (sNormalizedPhoneNumberToParticipantIdCache) {
                    // Add it to the cache for next time
                    sNormalizedPhoneNumberToParticipantIdCache.put(canonicalRecipient,
                            participantId);
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return participantId;
    }

    @DoesNotRunOnMainThread
    public static ParticipantData getOrCreateSelf(final DatabaseWrapper dbWrapper,
            final int subId) {
        Assert.isNotMainThread();
        ParticipantData participant = null;
        dbWrapper.beginTransaction();
        try {
            final ParticipantData shell = ParticipantData.getSelfParticipant(subId);
            final String participantId = getOrCreateParticipantInTransaction(dbWrapper, shell);
            participant = getExistingParticipant(dbWrapper, participantId);
            dbWrapper.setTransactionSuccessful();
        } finally {
            dbWrapper.endTransaction();
        }
        return participant;
    }

    /**
     * Lookup and if necessary create a new participant
     * @param dbWrapper      Database wrapper
     * @param participant    Participant to find/create
     * @return participantId ParticipantId for existing or newly created participant
     */
    @DoesNotRunOnMainThread
    public static String getOrCreateParticipantInTransaction(final DatabaseWrapper dbWrapper,
            final ParticipantData participant) {
        Assert.isNotMainThread();
        Assert.isTrue(dbWrapper.getDatabase().inTransaction());
        int subId = ParticipantData.OTHER_THAN_SELF_SUB_ID;
        String participantId = null;
        String canonicalRecipient = null;
        if (participant.isSelf()) {
            subId = participant.getSubId();
            canonicalRecipient = getCanonicalRecipientFromSubId(subId);
        } else {
            canonicalRecipient = participant.getNormalizedDestination();
        }
        Assert.notNull(canonicalRecipient);
        participantId = getParticipantId(dbWrapper, subId, canonicalRecipient);

        if (participantId != null) {
            return participantId;
        }

        if (!participant.isContactIdResolved()) {
            // Refresh participant's name and avatar with matching contact in CP2.
            ParticipantRefresh.refreshParticipant(dbWrapper, participant);
        }

        // Insert the participant into the participants table
        final ContentValues values = participant.toContentValues();
        final long participantRow = dbWrapper.insert(DatabaseHelper.PARTICIPANTS_TABLE, null,
                values);
        participantId = Long.toString(participantRow);
        Assert.notNull(canonicalRecipient);

        synchronized (sNormalizedPhoneNumberToParticipantIdCache) {
            // Now that we've inserted it, add it to our cache
            sNormalizedPhoneNumberToParticipantIdCache.put(canonicalRecipient, participantId);
        }

        return participantId;
    }

    @DoesNotRunOnMainThread
    public static void updateDestination(final DatabaseWrapper dbWrapper,
            final String destination, final boolean blocked) {
        Assert.isNotMainThread();
        final ContentValues values = new ContentValues();
        values.put(ParticipantColumns.BLOCKED, blocked ? 1 : 0);
        dbWrapper.update(DatabaseHelper.PARTICIPANTS_TABLE, values,
                ParticipantColumns.NORMALIZED_DESTINATION + "=? AND " +
                        ParticipantColumns.SUB_ID + "=?",
                new String[] { destination, Integer.toString(
                        ParticipantData.OTHER_THAN_SELF_SUB_ID) });
    }

    @DoesNotRunOnMainThread
    public static String getConversationFromOtherParticipantDestination(
            final DatabaseWrapper db, final String otherDestination) {
        Assert.isNotMainThread();
        Cursor cursor = null;
        try {
            cursor = db.query(DatabaseHelper.CONVERSATIONS_TABLE,
                    new String[] { ConversationColumns._ID },
                    ConversationColumns.OTHER_PARTICIPANT_NORMALIZED_DESTINATION + "=?",
                    new String[] { otherDestination }, null, null, null);
            Assert.inRange(cursor.getCount(), 0, 1);
            if (cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }


    /**
     * Get a list of conversations that contain any of participants specified.
     */
    private static HashSet<String> getConversationsForParticipants(
            final ArrayList<String> participantIds) {
        final DatabaseWrapper db = DataModel.get().getDatabase();
        final HashSet<String> conversationIds = new HashSet<String>();

        final String selection = ConversationParticipantsColumns.PARTICIPANT_ID + "=?";
        for (final String participantId : participantIds) {
            final String[] selectionArgs = new String[] { participantId };
            final Cursor cursor = db.query(DatabaseHelper.CONVERSATION_PARTICIPANTS_TABLE,
                    ConversationParticipantsQuery.PROJECTION,
                    selection, selectionArgs, null, null, null);

            if (cursor != null) {
                try {
                    while (cursor.moveToNext()) {
                        final String conversationId = cursor.getString(
                                ConversationParticipantsQuery.INDEX_CONVERSATION_ID);
                        conversationIds.add(conversationId);
                    }
                } finally {
                    cursor.close();
                }
            }
        }

        return conversationIds;
    }

    /**
     * Refresh conversation names/avatars based on a list of participants that are changed.
     */
    @DoesNotRunOnMainThread
    public static void refreshConversationsForParticipants(final ArrayList<String> participants) {
        Assert.isNotMainThread();
        final HashSet<String> conversationIds = getConversationsForParticipants(participants);
        if (conversationIds.size() > 0) {
            for (final String conversationId : conversationIds) {
                refreshConversation(conversationId);
            }

            MessagingContentProvider.notifyConversationListChanged();
            if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
                LogUtil.v(TAG, "Number of conversations refreshed:" + conversationIds.size());
            }
        }
    }

    /**
     * Refresh conversation names/avatars based on a changed participant.
     */
    @DoesNotRunOnMainThread
    public static void refreshConversationsForParticipant(final String participantId) {
        Assert.isNotMainThread();
        final ArrayList<String> participantList = new ArrayList<String>(1);
        participantList.add(participantId);
        refreshConversationsForParticipants(participantList);
    }

    /**
     * Refresh one conversation.
     */
    private static void refreshConversation(final String conversationId) {
        final DatabaseWrapper db = DataModel.get().getDatabase();

        db.beginTransaction();
        try {
            BugleDatabaseOperations.updateConversationNameAndAvatarInTransaction(db,
                    conversationId);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        MessagingContentProvider.notifyParticipantsChanged(conversationId);
        MessagingContentProvider.notifyMessagesChanged(conversationId);
        MessagingContentProvider.notifyConversationMetadataChanged(conversationId);
    }

    @DoesNotRunOnMainThread
    public static boolean updateRowIfExists(final DatabaseWrapper db, final String table,
            final String rowKey, final String rowId, final ContentValues values) {
        Assert.isNotMainThread();
        final StringBuilder sb = new StringBuilder();
        final ArrayList<String> whereValues = new ArrayList<String>(values.size() + 1);
        whereValues.add(rowId);

        for (final String key : values.keySet()) {
            if (sb.length() > 0) {
                sb.append(" OR ");
            }
            final Object value = values.get(key);
            sb.append(key);
            if (value != null) {
                sb.append(" IS NOT ?");
                whereValues.add(value.toString());
            } else {
                sb.append(" IS NOT NULL");
            }
        }

        final String whereClause = rowKey + "=?" + " AND (" + sb.toString() + ")";
        final String [] whereValuesArray = whereValues.toArray(new String[whereValues.size()]);
        final int count = db.update(table, values, whereClause, whereValuesArray);
        if (count > 1) {
            LogUtil.w(LogUtil.BUGLE_TAG, "Updated more than 1 row " + count + "; " + table +
                    " for " + rowKey + " = " + rowId + " (deleted?)");
        }
        Assert.inRange(count, 0, 1);
        return (count >= 0);
    }
}
