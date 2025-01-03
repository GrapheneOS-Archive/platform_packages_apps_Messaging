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

package com.android.messaging.datamodel.action;

import android.content.Context;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.provider.Telephony;
import android.text.TextUtils;

import com.android.messaging.Factory;
import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.MessagingContentProvider;
import com.android.messaging.datamodel.SyncManager;
import com.android.messaging.datamodel.data.ConversationListItemData;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.datamodel.data.MessagePartData;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.sms.MmsUtils;
import com.android.messaging.util.Assert;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.PhoneUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Action used to convert a draft message to an outgoing message. Its writes SMS messages to
 * the telephony db, but {@link SendMessageAction} is responsible for inserting MMS message into
 * the telephony DB. The latter also does the actual sending of the message in the background.
 * The latter is also responsible for re-sending a failed message.
 */
public class InsertNewMessageAction extends Action implements Parcelable {
    private static final String TAG = LogUtil.BUGLE_DATAMODEL_TAG;

    private static long sLastSentMessageTimestamp = -1;

    /**
     * Insert message (no listener)
     */
    public static void insertNewMessage(final MessageData message) {
        final InsertNewMessageAction action = new InsertNewMessageAction(message);
        action.start();
    }

    /**
     * Insert message (no listener) with a given non-default subId.
     */
    public static void insertNewMessage(final MessageData message, final int subId) {
        Assert.isFalse(subId == ParticipantData.DEFAULT_SELF_SUB_ID);
        final InsertNewMessageAction action = new InsertNewMessageAction(message, subId);
        action.start();
    }

    /**
     * Insert message (no listener)
     */
    public static void insertNewMessage(final int subId, final String recipients,
            final String messageText, final String subject) {
        final InsertNewMessageAction action = new InsertNewMessageAction(
                subId, recipients, messageText, subject);
        action.start();
    }

    public static long getLastSentMessageTimestamp() {
        return sLastSentMessageTimestamp;
    }

    private static final String KEY_SUB_ID = "sub_id";
    private static final String KEY_MESSAGE = "message";
    private static final String KEY_RECIPIENTS = "recipients";
    private static final String KEY_MESSAGE_TEXT = "message_text";
    private static final String KEY_SUBJECT_TEXT = "subject_text";

    private InsertNewMessageAction(final MessageData message) {
        this(message, ParticipantData.DEFAULT_SELF_SUB_ID);
        actionParameters.putParcelable(KEY_MESSAGE, message);
    }

    private InsertNewMessageAction(final MessageData message, final int subId) {
        super();
        actionParameters.putParcelable(KEY_MESSAGE, message);
        actionParameters.putInt(KEY_SUB_ID, subId);
    }

    private InsertNewMessageAction(final int subId, final String recipients,
            final String messageText, final String subject) {
        super();
        if (TextUtils.isEmpty(recipients) || TextUtils.isEmpty(messageText)) {
            Assert.fail("InsertNewMessageAction: Can't have empty recipients or message");
        }
        actionParameters.putInt(KEY_SUB_ID, subId);
        actionParameters.putString(KEY_RECIPIENTS, recipients);
        actionParameters.putString(KEY_MESSAGE_TEXT, messageText);
        actionParameters.putString(KEY_SUBJECT_TEXT, subject);
    }

    /**
     * Add message to database in pending state and queue actual sending
     */
    @Override
    protected Object executeAction() {
        MessageData message = actionParameters.getParcelable(KEY_MESSAGE);
        if (message == null) {
            LogUtil.i(TAG, "InsertNewMessageAction: Creating MessageData with provided data");
            message = createMessage();
            if (message == null) {
                LogUtil.w(TAG, "InsertNewMessageAction: Could not create MessageData");
                return null;
            }
        }
        final DatabaseWrapper db = DataModel.get().getDatabase();
        final String conversationId = message.getConversationId();

        final ParticipantData self = getSelf(db, conversationId, message);
        if (self == null) {
            return null;
        }
        message.bindSelfId(self.getId());
        // If the user taps the Send button before the conversation draft is created/loaded by
        // ReadDraftDataAction (maybe the action service thread was busy), the MessageData may not
        // have the participant id set. It should be equal to the self id, so we'll use that.
        if (message.getParticipantId() == null) {
            message.bindParticipantId(self.getId());
        }

        final long timestamp = System.currentTimeMillis();
        final ArrayList<String> recipients =
                BugleDatabaseOperations.getRecipientsForConversation(db, conversationId);
        if (recipients.size() < 1) {
            LogUtil.w(TAG, "InsertNewMessageAction: message recipients is empty");
            return null;
        }
        final int subId = self.getSubId();
        LogUtil.i(TAG, "InsertNewMessageAction: inserting new message for subId " + subId);
        actionParameters.putInt(KEY_SUB_ID, subId);

        // TODO: Work out whether to send with SMS or MMS (taking into account recipients)?
        final boolean isSms = (message.getProtocol() == MessageData.PROTOCOL_SMS);
        if (isSms) {
            String sendingConversationId = conversationId;
            if (recipients.size() > 1) {
                // Broadcast SMS - put message in "fake conversation" before farming out to real 1:1
                final long laterTimestamp = timestamp + 1;
                // Send a single message
                insertBroadcastSmsMessage(conversationId, message, subId,
                        laterTimestamp, recipients);

                sendingConversationId = null;
            }

            for (final String recipient : recipients) {
                // Start actual sending
                insertSendingSmsMessage(message, subId, recipient,
                        timestamp, sendingConversationId);
            }

            // Can now clear draft from conversation (deleting attachments if necessary)
            BugleDatabaseOperations.updateDraftMessageData(db, conversationId,
                    null /* message */, BugleDatabaseOperations.UPDATE_MODE_CLEAR_DRAFT);
        } else {
            final long timestampRoundedToSecond = 1000 * ((timestamp + 500) / 1000);
            // Write place holder message directly referencing parts from the draft
            final MessageData messageToSend = insertSendingMmsMessage(conversationId,
                    message, timestampRoundedToSecond);

            // Can now clear draft from conversation (preserving attachments which are now
            // referenced by messageToSend)
            BugleDatabaseOperations.updateDraftMessageData(db, conversationId,
                    messageToSend, BugleDatabaseOperations.UPDATE_MODE_CLEAR_DRAFT);
        }
        MessagingContentProvider.notifyConversationListChanged();
        ProcessPendingMessagesAction.scheduleProcessPendingMessagesAction(false, this);

        return message;
    }

    private ParticipantData getSelf(
            final DatabaseWrapper db, final String conversationId, final MessageData message) {
        ParticipantData self;
        // Check if we are asked to bind to a non-default subId. This is directly passed in from
        // the UI thread so that the sub id may be locked as soon as the user clicks on the Send
        // button.
        final int requestedSubId = actionParameters.getInt(
                KEY_SUB_ID, ParticipantData.DEFAULT_SELF_SUB_ID);
        if (requestedSubId != ParticipantData.DEFAULT_SELF_SUB_ID) {
            self = BugleDatabaseOperations.getOrCreateSelf(db, requestedSubId);
        } else {
            String selfId = message.getSelfId();
            if (selfId == null) {
                // The conversation draft provides no self id hint, meaning that 1) conversation
                // self id was not loaded AND 2) the user didn't pick a SIM from the SIM selector.
                // In this case, use the conversation's self id.
                final ConversationListItemData conversation =
                        ConversationListItemData.getExistingConversation(db, conversationId);
                if (conversation != null) {
                    selfId = conversation.getSelfId();
                } else {
                    LogUtil.w(LogUtil.BUGLE_DATAMODEL_TAG, "Conversation " + conversationId +
                            "already deleted before sending draft message " +
                            message.getMessageId() + ". Aborting InsertNewMessageAction.");
                    return null;
                }
            }

            // We do not use SubscriptionManager.DEFAULT_SUB_ID for sending a message, so we need
            // to bind the message to the system default subscription if it's unbound.
            final ParticipantData unboundSelf = BugleDatabaseOperations.getExistingParticipant(
                    db, selfId);
            if (unboundSelf.getSubId() == ParticipantData.DEFAULT_SELF_SUB_ID) {
                final int defaultSubId = PhoneUtils.getDefault().getDefaultSmsSubscriptionId();
                self = BugleDatabaseOperations.getOrCreateSelf(db, defaultSubId);
            } else {
                self = unboundSelf;
            }
        }
        return self;
    }

    /** Create MessageData using KEY_RECIPIENTS, KEY_MESSAGE_TEXT and KEY_SUBJECT */
    private MessageData createMessage() {
        // First find the thread id for this list of participants.
        final String recipientsList = actionParameters.getString(KEY_RECIPIENTS);
        final String messageText = actionParameters.getString(KEY_MESSAGE_TEXT);
        final String subjectText = actionParameters.getString(KEY_SUBJECT_TEXT);
        final int subId = actionParameters.getInt(
                KEY_SUB_ID, ParticipantData.DEFAULT_SELF_SUB_ID);

        final ArrayList<ParticipantData> participants = new ArrayList<>();
        for (final String recipient : recipientsList.split(",")) {
            participants.add(ParticipantData.getFromRawPhoneBySimLocale(recipient, subId));
        }
        if (participants.size() == 0) {
            Assert.fail("InsertNewMessage: Empty participants");
            return null;
        }

        final DatabaseWrapper db = DataModel.get().getDatabase();
        BugleDatabaseOperations.sanitizeConversationParticipants(participants);
        final ArrayList<String> recipients =
                BugleDatabaseOperations.getRecipientsFromConversationParticipants(participants);
        if (recipients.size() == 0) {
            Assert.fail("InsertNewMessage: Empty recipients");
            return null;
        }

        final long threadId = MmsUtils.getOrCreateThreadId(Factory.get().getApplicationContext(),
                recipients);

        if (threadId < 0) {
            Assert.fail("InsertNewMessage: Couldn't get threadId in SMS db for these recipients: "
                    + recipients.toString());
            // TODO: How do we fail the action?
            return null;
        }

        final String conversationId = BugleDatabaseOperations.getOrCreateConversation(db, threadId,
                false, participants, false, false, null);

        final ParticipantData self = BugleDatabaseOperations.getOrCreateSelf(db, subId);

        if (TextUtils.isEmpty(subjectText)) {
            return MessageData.createDraftSmsMessage(conversationId, self.getId(), messageText);
        } else {
            return MessageData.createDraftMmsMessage(conversationId, self.getId(), messageText,
                    subjectText);
        }
    }

    private void insertBroadcastSmsMessage(final String conversationId,
            final MessageData message, final int subId, final long laterTimestamp,
            final ArrayList<String> recipients) {
        if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
            LogUtil.v(TAG, "InsertNewMessageAction: Inserting broadcast SMS message "
                    + message.getMessageId());
        }
        final Context context = Factory.get().getApplicationContext();
        final DatabaseWrapper db = DataModel.get().getDatabase();

        // Inform sync that message is being added at timestamp
        final SyncManager syncManager = DataModel.get().getSyncManager();
        syncManager.onNewMessageInserted(laterTimestamp);

        final long threadId = BugleDatabaseOperations.getThreadId(db, conversationId);
        final String address = TextUtils.join(" ", recipients);

        final String messageText = message.getMessageText();
        // Insert message into telephony database sms message table
        final Uri messageUri = MmsUtils.insertSmsMessage(context,
                Telephony.Sms.CONTENT_URI,
                subId,
                address,
                messageText,
                laterTimestamp,
                Telephony.Sms.STATUS_COMPLETE,
                Telephony.Sms.MESSAGE_TYPE_SENT, threadId);
        if (messageUri != null && !TextUtils.isEmpty(messageUri.toString())) {
            db.beginTransaction();
            try {
                message.updateSendingMessage(conversationId, messageUri, laterTimestamp);
                message.markMessageSent(laterTimestamp);

                BugleDatabaseOperations.insertNewMessageInTransaction(db, message);

                BugleDatabaseOperations.updateConversationMetadataInTransaction(db,
                        conversationId, message.getMessageId(), laterTimestamp,
                        false /* senderBlocked */, false /* shouldAutoSwitchSelfId */);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }

            if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
                LogUtil.d(TAG, "InsertNewMessageAction: Inserted broadcast SMS message "
                        + message.getMessageId() + ", uri = " + message.getSmsMessageUri());
            }
            MessagingContentProvider.notifyMessagesChanged(conversationId);
            MessagingContentProvider.notifyPartsChanged();
        } else {
            // Ignore error as we only really care about the individual messages?
            LogUtil.e(TAG,
                    "InsertNewMessageAction: No uri for broadcast SMS " + message.getMessageId()
                    + " inserted into telephony DB");
        }
    }

    /**
     * Insert SMS messaging into our database and telephony db.
     */
    private MessageData insertSendingSmsMessage(final MessageData content, final int subId,
            final String recipient, final long timestamp, final String sendingConversationId) {
        sLastSentMessageTimestamp = timestamp;

        final Context context = Factory.get().getApplicationContext();

        // Inform sync that message is being added at timestamp
        final SyncManager syncManager = DataModel.get().getSyncManager();
        syncManager.onNewMessageInserted(timestamp);

        final DatabaseWrapper db = DataModel.get().getDatabase();

        // Send a single message
        long threadId;
        String conversationId;
        if (sendingConversationId == null) {
            // For 1:1 message generated sending broadcast need to look up threadId+conversationId
            threadId = MmsUtils.getOrCreateSmsThreadId(context, recipient);
            conversationId = BugleDatabaseOperations.getOrCreateConversationFromRecipient(
                    db, threadId, false /* sender blocked */,
                    ParticipantData.getFromRawPhoneBySimLocale(recipient, subId));
        } else {
            // Otherwise just look up threadId
            threadId = BugleDatabaseOperations.getThreadId(db, sendingConversationId);
            conversationId = sendingConversationId;
        }

        final String messageText = content.getMessageText();

        // Insert message into telephony database sms message table
        final Uri messageUri = MmsUtils.insertSmsMessage(context,
                Telephony.Sms.CONTENT_URI,
                subId,
                recipient,
                messageText,
                timestamp,
                Telephony.Sms.STATUS_NONE,
                Telephony.Sms.MESSAGE_TYPE_SENT, threadId);

        MessageData message = null;
        if (messageUri != null && !TextUtils.isEmpty(messageUri.toString())) {
            db.beginTransaction();
            try {
                message = MessageData.createDraftSmsMessage(conversationId,
                        content.getSelfId(), messageText);
                message.updateSendingMessage(conversationId, messageUri, timestamp);

                BugleDatabaseOperations.insertNewMessageInTransaction(db, message);

                // Do not update the conversation summary to reflect autogenerated 1:1 messages
                if (sendingConversationId != null) {
                    BugleDatabaseOperations.updateConversationMetadataInTransaction(db,
                            conversationId, message.getMessageId(), timestamp,
                            false /* senderBlocked */, false /* shouldAutoSwitchSelfId */);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }

            if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
                LogUtil.d(TAG, "InsertNewMessageAction: Inserted SMS message "
                        + message.getMessageId() + " (uri = " + message.getSmsMessageUri()
                        + ", timestamp = " + message.getReceivedTimeStamp() + ")");
            }
            MessagingContentProvider.notifyMessagesChanged(conversationId);
            MessagingContentProvider.notifyPartsChanged();
        } else {
            LogUtil.e(TAG, "InsertNewMessageAction: No uri for SMS inserted into telephony DB");
        }

        return message;
    }

    /**
     * Insert MMS messaging into our database.
     */
    private MessageData insertSendingMmsMessage(final String conversationId,
            final MessageData message, final long timestamp) {
        final DatabaseWrapper db = DataModel.get().getDatabase();
        db.beginTransaction();
        final List<MessagePartData> attachmentsUpdated = new ArrayList<>();
        try {
            sLastSentMessageTimestamp = timestamp;

            // Insert "draft" message as placeholder until the final message is written to
            // the telephony db
            message.updateSendingMessage(conversationId, null/*messageUri*/, timestamp);

            // No need to inform SyncManager as message currently has no Uri...
            BugleDatabaseOperations.insertNewMessageInTransaction(db, message);

            BugleDatabaseOperations.updateConversationMetadataInTransaction(db,
                    conversationId, message.getMessageId(), timestamp,
                    false /* senderBlocked */, false /* shouldAutoSwitchSelfId */);

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
            LogUtil.d(TAG, "InsertNewMessageAction: Inserted MMS message "
                    + message.getMessageId() + " (timestamp = " + timestamp + ")");
        }
        MessagingContentProvider.notifyMessagesChanged(conversationId);
        MessagingContentProvider.notifyPartsChanged();

        return message;
    }

    private InsertNewMessageAction(final Parcel in) {
        super(in);
    }

    public static final Parcelable.Creator<InsertNewMessageAction> CREATOR
            = new Parcelable.Creator<InsertNewMessageAction>() {
        @Override
        public InsertNewMessageAction createFromParcel(final Parcel in) {
            return new InsertNewMessageAction(in);
        }

        @Override
        public InsertNewMessageAction[] newArray(final int size) {
            return new InsertNewMessageAction[size];
        }
    };

    @Override
    public void writeToParcel(final Parcel parcel, final int flags) {
        writeActionToParcel(parcel, flags);
    }
}
