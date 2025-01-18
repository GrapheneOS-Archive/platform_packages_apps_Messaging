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

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.Html;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TextAppearanceSpan;
import android.text.style.URLSpan;

import com.android.messaging.Factory;
import com.android.messaging.R;
import com.android.messaging.datamodel.data.ConversationListItemData;
import com.android.messaging.datamodel.data.ConversationMessageData;
import com.android.messaging.datamodel.data.ConversationParticipantsData;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.datamodel.data.MessagePartData;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.datamodel.media.VideoThumbnailRequest;
import com.android.messaging.sms.MmsUtils;
import com.android.messaging.ui.UIIntents;
import com.android.messaging.util.Assert;
import com.android.messaging.util.AvatarUriUtil;
import com.android.messaging.util.BugleGservices;
import com.android.messaging.util.BugleGservicesKeys;
import com.android.messaging.util.ContentType;
import com.android.messaging.util.ConversationIdSet;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.NotificationChannelUtil;
import com.android.messaging.util.PendingIntentConstants;
import com.android.messaging.util.UriUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationCompat.Builder;
import androidx.core.app.NotificationCompat.WearableExtender;
import androidx.core.app.NotificationManagerCompat;

/**
 * Notification building class for conversation messages.
 *
 * Message Notifications are built in several stages with several utility classes.
 * 1) Perform a database query and fill a data structure with information on messages and
 *    conversations which need to be notified.
 * 2) Based on the data structure choose an appropriate NotificationState subclass to
 *    represent all the notifications.
 *    -- For one or more messages in one conversation: MultiMessageNotificationState.
 *    -- For multiple messages in multiple conversations: MultiConversationNotificationState
 *
 *  A three level structure is used to coalesce the data from the database. From bottom to top:
 *  1) NotificationLineInfo - A single message that needs to be notified.
 *  2) ConversationLineInfo - A list of NotificationLineInfo in a single conversation.
 *  3) ConversationInfoList - A list of ConversationLineInfo and the total number of messages.
 *
 *  The createConversationInfoList function performs the query and creates the data structure.
 */
public abstract class MessageNotificationState extends NotificationState {
    // Logging
    static final String TAG = LogUtil.BUGLE_NOTIFICATIONS_TAG;
    private static final int MAX_MESSAGES_IN_WEARABLE_PAGE = 20;

    private static final int MAX_CHARACTERS_IN_GROUP_NAME = 30;

    private static final int REPLY_INTENT_REQUEST_CODE_OFFSET = 0;
    private static final int NUM_EXTRA_REQUEST_CODES_NEEDED = 1;
    protected String mTickerSender = null;
    protected CharSequence mTickerText = null;
    protected String mTitle = null;
    protected CharSequence mContent = null;
    protected Uri mAttachmentUri = null;
    protected String mAttachmentType = null;

    @Override
    protected Uri getAttachmentUri() {
        return mAttachmentUri;
    }

    @Override
    protected String getAttachmentType() {
        return mAttachmentType;
    }

    @Override
    public int getIcon() {
        return R.drawable.ic_sms_light;
    }

    @Override
    public int getPriority() {
        // Returning PRIORITY_HIGH causes L to put up a HUD notification. Without it, the ticker
        // isn't displayed.
        return Notification.PRIORITY_HIGH;
    }

    /**
     * Base class for single notification events for messages. Multiple of these
     * may be grouped into a single conversation.
     */
    static class NotificationLineInfo {

        final int mNotificationType;

        NotificationLineInfo() {
            mNotificationType = BugleNotifications.LOCAL_SMS_NOTIFICATION;
        }

        NotificationLineInfo(final int notificationType) {
            mNotificationType = notificationType;
        }
    }

    /**
     * Information on a single chat message which should be shown in a notification.
     */
    static class MessageLineInfo extends NotificationLineInfo {
        final CharSequence mText;
        Uri mAttachmentUri;
        String mAttachmentType;
        final String mAuthorFullName;
        final String mAuthorFirstName;
        boolean mIsManualDownloadNeeded;
        final String mMessageId;

        MessageLineInfo(final boolean isGroup, final String authorFullName,
                final String authorFirstName, final CharSequence text, final Uri attachmentUrl,
                final String attachmentType, final boolean isManualDownloadNeeded,
                final String messageId) {
            super(BugleNotifications.LOCAL_SMS_NOTIFICATION);
            mAuthorFullName = authorFullName;
            mAuthorFirstName = authorFirstName;
            mText = text;
            mAttachmentUri = attachmentUrl;
            mAttachmentType = attachmentType;
            mIsManualDownloadNeeded = isManualDownloadNeeded;
            mMessageId = messageId;
        }
    }

    /**
     * Information on all the notification messages within a single conversation.
     */
    static class ConversationLineInfo {
        // Conversation id of the latest message in the notification for this merged conversation.
        final String mConversationId;

        // True if this represents a group conversation.
        final boolean mIsGroup;

        // Name of the group conversation if available.
        final String mGroupConversationName;

        // True if this conversation's recipients includes one or more email address(es)
        // (see ConversationColumns.INCLUDE_EMAIL_ADDRESS)
        final boolean mIncludeEmailAddress;

        // Timestamp of the latest message
        final long mReceivedTimestamp;

        // Self participant id.
        final String mSelfParticipantId;

        // List of individual line notifications to be parsed later.
        final List<NotificationLineInfo> mLineInfos;

        // Total number of messages. Might be different that mLineInfos.size() as the number of
        // line infos is capped.
        int mTotalMessageCount;

        // Custom ringtone if set
        final String mRingtoneUri;

        // Should notification be enabled for this conversation?
        final boolean mNotificationEnabled;

        // Should notifications vibrate for this conversation?
        final boolean mNotificationVibrate;

        // Avatar uri of sender
        final Uri mAvatarUri;

        // Contact uri of sender
        final Uri mContactUri;

        // Subscription id.
        final int mSubId;

        // Number of participants
        final int mParticipantCount;

        public ConversationLineInfo(final String conversationId,
                final boolean isGroup,
                final String groupConversationName,
                final boolean includeEmailAddress,
                final long receivedTimestamp,
                final String selfParticipantId,
                final String ringtoneUri,
                final boolean notificationEnabled,
                final boolean notificationVibrate,
                final Uri avatarUri,
                final Uri contactUri,
                final int subId,
                final int participantCount) {
            mConversationId = conversationId;
            mIsGroup = isGroup;
            mGroupConversationName = groupConversationName;
            mIncludeEmailAddress = includeEmailAddress;
            mReceivedTimestamp = receivedTimestamp;
            mSelfParticipantId = selfParticipantId;
            mLineInfos = new ArrayList<NotificationLineInfo>();
            mTotalMessageCount = 0;
            mRingtoneUri = ringtoneUri;
            mAvatarUri = avatarUri;
            mContactUri = contactUri;
            mNotificationEnabled = notificationEnabled;
            mNotificationVibrate = notificationVibrate;
            mSubId = subId;
            mParticipantCount = participantCount;
        }

        public int getLatestMessageNotificationType() {
            final MessageLineInfo messageLineInfo = getLatestMessageLineInfo();
            if (messageLineInfo == null) {
                return BugleNotifications.LOCAL_SMS_NOTIFICATION;
            }
            return messageLineInfo.mNotificationType;
        }

        public String getLatestMessageId() {
            final MessageLineInfo messageLineInfo = getLatestMessageLineInfo();
            if (messageLineInfo == null) {
                return null;
            }
            return messageLineInfo.mMessageId;
        }

        public boolean getDoesLatestMessageNeedDownload() {
            final MessageLineInfo messageLineInfo = getLatestMessageLineInfo();
            if (messageLineInfo == null) {
                return false;
            }
            return messageLineInfo.mIsManualDownloadNeeded;
        }

        private MessageLineInfo getLatestMessageLineInfo() {
            // The latest message is stored at index zero of the message line infos.
            if (mLineInfos.size() > 0 && mLineInfos.get(0) instanceof MessageLineInfo) {
                return (MessageLineInfo) mLineInfos.get(0);
            }
            return null;
        }
    }

    /**
     * Information on all the notification messages across all conversations.
     */
    public static class ConversationInfoList {
        final int mMessageCount;
        final List<ConversationLineInfo> mConvInfos;
        public ConversationInfoList(final int count, final List<ConversationLineInfo> infos) {
            mMessageCount = count;
            mConvInfos = infos;
        }
    }

    final ConversationInfoList mConvList;
    private long mLatestReceivedTimestamp;

    private static ConversationIdSet makeConversationIdSet(final ConversationInfoList convList) {
        ConversationIdSet set = null;
        if (convList != null && convList.mConvInfos != null && convList.mConvInfos.size() > 0) {
            set = new ConversationIdSet();
            for (final ConversationLineInfo info : convList.mConvInfos) {
                    set.add(info.mConversationId);
            }
        }
        return set;
    }

    protected MessageNotificationState(final ConversationInfoList convList) {
        super(makeConversationIdSet(convList));
        mConvList = convList;
        mType = PendingIntentConstants.SMS_NOTIFICATION_ID;
        mLatestReceivedTimestamp = Long.MIN_VALUE;
        if (convList != null) {
            for (final ConversationLineInfo info : convList.mConvInfos) {
                mLatestReceivedTimestamp = Math.max(mLatestReceivedTimestamp,
                        info.mReceivedTimestamp);
            }
        }
    }

    @Override
    public long getLatestReceivedTimestamp() {
        return mLatestReceivedTimestamp;
    }

    @Override
    public int getNumRequestCodesNeeded() {
        // Get additional request codes for the Reply PendingIntent (wearables only)
        // and the DND PendingIntent.
        return super.getNumRequestCodesNeeded() + NUM_EXTRA_REQUEST_CODES_NEEDED;
    }

    private int getBaseExtraRequestCode() {
        return mBaseRequestCode + super.getNumRequestCodesNeeded();
    }

    public int getReplyIntentRequestCode() {
        return getBaseExtraRequestCode() + REPLY_INTENT_REQUEST_CODE_OFFSET;
    }

    @Override
    public PendingIntent getClearIntent() {
        return UIIntents.get().getPendingIntentForClearingNotifications(
                    Factory.get().getApplicationContext(),
                    BugleNotifications.UPDATE_MESSAGES,
                    mConversationIds,
                    getClearIntentRequestCode());
    }

    /**
     * Notification for multiple messages in at least 2 different conversations.
     */
    public static class MultiConversationNotificationState extends MessageNotificationState {

        public final List<MessageNotificationState>
                mChildren = new ArrayList<MessageNotificationState>();

        public MultiConversationNotificationState(
                final ConversationInfoList convList, final MessageNotificationState state) {
            super(convList);
            mAttachmentUri = null;
            mAttachmentType = null;

            // Pull the ticker title/text from the single notification
            mTickerSender = state.getTitle();
            mTitle = Factory.get().getApplicationContext().getResources().getQuantityString(
                    R.plurals.notification_new_messages,
                    convList.mMessageCount, convList.mMessageCount);
            mTickerText = state.mContent;

            // Create child notifications for each conversation,
            // which will be displayed (only) on a wearable device.
            for (int i = 0; i < convList.mConvInfos.size(); i++) {
                final ConversationLineInfo convInfo = convList.mConvInfos.get(i);
                if (!(convInfo.mLineInfos.get(0) instanceof MessageLineInfo)) {
                    continue;
                }
                setPeopleForConversation(convInfo.mConversationId);
                final ConversationInfoList list = new ConversationInfoList(
                        convInfo.mTotalMessageCount, Collections.singletonList(convInfo));
                mChildren.add(new BundledMessageNotificationState(list, i));
            }
        }

        @Override
        public int getIcon() {
            return R.drawable.ic_sms_multi_light;
        }

        @Override
        protected NotificationCompat.Style build(final Builder builder) {
            builder.setContentTitle(mTitle);
            NotificationCompat.InboxStyle inboxStyle = null;
            inboxStyle = new NotificationCompat.InboxStyle(builder);

            final Context context = Factory.get().getApplicationContext();
            // enumeration_comma is defined as ", "
            final String separator = context.getString(R.string.enumeration_comma);
            final StringBuilder senders = new StringBuilder();
            long when = 0;
            for (int i = 0; i < mConvList.mConvInfos.size(); i++) {
                final ConversationLineInfo convInfo = mConvList.mConvInfos.get(i);
                if (convInfo.mReceivedTimestamp > when) {
                    when = convInfo.mReceivedTimestamp;
                }
                String sender;
                CharSequence text;
                final NotificationLineInfo lineInfo = convInfo.mLineInfos.get(0);
                final MessageLineInfo messageLineInfo = (MessageLineInfo) lineInfo;
                if (convInfo.mIsGroup) {
                    sender = (convInfo.mGroupConversationName.length() >
                            MAX_CHARACTERS_IN_GROUP_NAME) ?
                                    truncateGroupMessageName(convInfo.mGroupConversationName)
                                    : convInfo.mGroupConversationName;
                } else {
                    sender = messageLineInfo.mAuthorFullName;
                }
                text = messageLineInfo.mText;
                mAttachmentUri = messageLineInfo.mAttachmentUri;
                mAttachmentType = messageLineInfo.mAttachmentType;

                inboxStyle.addLine(BugleNotifications.formatInboxMessage(
                        sender, text, mAttachmentUri, mAttachmentType));
                if (sender != null) {
                    if (senders.length() > 0) {
                        senders.append(separator);
                    }
                    senders.append(sender);
                }
            }
            // for collapsed state
            mContent = senders;
            builder.setContentText(senders)
                .setTicker(getTicker())
                .setWhen(when);

            return inboxStyle;
        }
    }

    /**
     * Truncate group conversation name to be displayed in the notifications. This either truncates
     * the entire group name or finds the last comma in the available length and truncates the name
     * at that point
     */
    private static String truncateGroupMessageName(final String conversationName) {
        int endIndex = MAX_CHARACTERS_IN_GROUP_NAME;
        for (int i = MAX_CHARACTERS_IN_GROUP_NAME; i >= 0; i--) {
            // The dividing marker should stay consistent with ConversationListItemData.DIVIDER_TEXT
            if (conversationName.charAt(i) == ',') {
                endIndex = i;
                break;
            }
        }
        return conversationName.substring(0, endIndex) + '\u2026';
    }

    /**
     * Notification for multiple messages in a single conversation. Also used if there is a single
     * message in a single conversation.
     */
    public static class MultiMessageNotificationState extends MessageNotificationState {

        public MultiMessageNotificationState(final ConversationInfoList convList) {
            super(convList);
            // This conversation has been accepted.
            final ConversationLineInfo convInfo = convList.mConvInfos.get(0);
            setAvatarUrlsForConversation(convInfo.mConversationId);
            setPeopleForConversation(convInfo.mConversationId);

            final Context context = Factory.get().getApplicationContext();
            MessageLineInfo messageInfo = (MessageLineInfo) convInfo.mLineInfos.get(0);
            // attached photo
            mAttachmentUri = messageInfo.mAttachmentUri;
            mAttachmentType = messageInfo.mAttachmentType;
            mContent = messageInfo.mText;

            if (mAttachmentUri != null) {
                // The default attachment type is an image, since that's what was originally
                // supported. When there's no content type, assume it's an image.
                int message = R.string.notification_picture;
                if (ContentType.isAudioType(mAttachmentType)) {
                    message = R.string.notification_audio;
                } else if (ContentType.isVideoType(mAttachmentType)) {
                    message = R.string.notification_video;
                } else if (ContentType.isVCardType(mAttachmentType)) {
                    message = R.string.notification_vcard;
                }
                final String attachment = context.getString(message);
                final SpannableStringBuilder spanBuilder = new SpannableStringBuilder();
                if (!TextUtils.isEmpty(mContent)) {
                    spanBuilder.append(mContent).append(System.getProperty("line.separator"));
                }
                final int start = spanBuilder.length();
                spanBuilder.append(attachment);
                spanBuilder.setSpan(new StyleSpan(Typeface.ITALIC), start, spanBuilder.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                mContent = spanBuilder;
            }
            if (convInfo.mIsGroup) {
                // When the message is part of a group, the sender's first name
                // is prepended to the message, but not for the ticker message.
                mTickerText = mContent;
                mTickerSender = messageInfo.mAuthorFullName;
                // append the bold name to the front of the message
                mContent = BugleNotifications.buildSpaceSeparatedMessage(
                        messageInfo.mAuthorFullName, mContent, mAttachmentUri,
                        mAttachmentType);
                mTitle = convInfo.mGroupConversationName;
            } else {
                // No matter how many messages there are, since this is a 1:1, just
                // get the author full name from the first one.
                messageInfo = (MessageLineInfo) convInfo.mLineInfos.get(0);
                mTitle = messageInfo.mAuthorFullName;
            }
        }

        @Override
        protected NotificationCompat.Style build(final Builder builder) {
            builder.setContentTitle(mTitle)
                .setTicker(getTicker());

            NotificationCompat.Style notifStyle = null;
            final ConversationLineInfo convInfo = mConvList.mConvInfos.get(0);
            final List<NotificationLineInfo> lineInfos = convInfo.mLineInfos;
            final int messageCount = lineInfos.size();
            // At this point, all the messages come from the same conversation. We need to load
            // the sender's avatar and then finish building the notification on a callback.

            builder.setContentText(mContent);   // for collapsed state

            if (messageCount == 1) {
                final boolean shouldShowImage = ContentType.isImageType(mAttachmentType)
                        || (ContentType.isVideoType(mAttachmentType)
                        && VideoThumbnailRequest.shouldShowIncomingVideoThumbnails());
                if (mAttachmentUri != null && shouldShowImage) {
                    // Show "Picture" as the content
                    final MessageLineInfo messageLineInfo = (MessageLineInfo) lineInfos.get(0);
                    String authorFirstName = messageLineInfo.mAuthorFirstName;

                    // For the collapsed state, just show "picture" unless this is a
                    // group conversation. If it's a group, show the sender name and
                    // "picture".
                    final CharSequence tickerTag =
                            BugleNotifications.formatAttachmentTag(authorFirstName,
                                    mAttachmentType);
                    // For 1:1 notifications don't show first name in the notification, but
                    // do show it in the ticker text
                    CharSequence pictureTag = tickerTag;
                    if (!convInfo.mIsGroup) {
                        authorFirstName = null;
                        pictureTag = BugleNotifications.formatAttachmentTag(authorFirstName,
                                mAttachmentType);
                    }
                    builder.setContentText(pictureTag);
                    builder.setTicker(tickerTag);

                    notifStyle = new NotificationCompat.BigPictureStyle(builder)
                        .setSummaryText(BugleNotifications.formatInboxMessage(
                                authorFirstName,
                                null, null,
                                null));  // expanded state, just show sender
                } else {
                    notifStyle = new NotificationCompat.BigTextStyle(builder)
                    .bigText(mContent);
                }
            } else {
                // We've got multiple messages for the same sender.
                // Starting with the oldest new message, display the full text of each message.
                // Begin a line for each subsequent message.
                final SpannableStringBuilder buf = new SpannableStringBuilder();

                for (int i = lineInfos.size() - 1; i >= 0; --i) {
                    final NotificationLineInfo info = lineInfos.get(i);
                    final MessageLineInfo messageLineInfo = (MessageLineInfo) info;
                    mAttachmentUri = messageLineInfo.mAttachmentUri;
                    mAttachmentType = messageLineInfo.mAttachmentType;
                    CharSequence text = messageLineInfo.mText;
                    if (!TextUtils.isEmpty(text) || mAttachmentUri != null) {
                        if (convInfo.mIsGroup) {
                            // append the bold name to the front of the message
                            text = BugleNotifications.buildSpaceSeparatedMessage(
                                    messageLineInfo.mAuthorFullName, text, mAttachmentUri,
                                    mAttachmentType);
                        } else {
                            text = BugleNotifications.buildSpaceSeparatedMessage(
                                    null, text, mAttachmentUri, mAttachmentType);
                        }
                        buf.append(text);
                        if (i > 0) {
                            buf.append('\n');
                        }
                    }
                }

                // Show a single notification -- big style with the text of all the messages
                notifStyle = new NotificationCompat.BigTextStyle(builder).bigText(buf);
            }
            builder.setWhen(convInfo.mReceivedTimestamp);
            return notifStyle;
        }

    }

    private static boolean firstNameUsedMoreThanOnce(
            final HashMap<String, Integer> map, final String firstName) {
        if (map == null) {
            return false;
        }
        if (firstName == null) {
            return false;
        }
        final Integer count = map.get(firstName);
        if (count != null) {
            return count > 1;
        } else {
            return false;
        }
    }

    private static HashMap<String, Integer> scanFirstNames(final String conversationId) {
        final Context context = Factory.get().getApplicationContext();
        final Uri uri =
                MessagingContentProvider.buildConversationParticipantsUri(conversationId);
        final ConversationParticipantsData participantsData = new ConversationParticipantsData();

        try (final Cursor participantsCursor = context.getContentResolver().query(
                    uri, ParticipantData.ParticipantsQuery.PROJECTION, null, null, null)) {
            participantsData.bind(participantsCursor);
        }

        final Iterator<ParticipantData> iter = participantsData.iterator();

        final HashMap<String, Integer> firstNames = new HashMap<String, Integer>();
        boolean seenSelf = false;
        while (iter.hasNext()) {
            final ParticipantData participant = iter.next();
            // Make sure we only add the self participant once
            if (participant.isSelf()) {
                if (seenSelf) {
                    continue;
                } else {
                    seenSelf = true;
                }
            }

            final String firstName = participant.getFirstName();
            if (firstName == null) {
                continue;
            }

            final int currentCount = firstNames.containsKey(firstName)
                    ? firstNames.get(firstName)
                    : 0;
            firstNames.put(firstName, currentCount + 1);
        }
        return firstNames;
    }

    // Essentially, we're building a list of the past 20 messages for this conversation to display
    // on the wearable.
    public static Notification buildConversationPageForWearable(final String conversationId,
            int participantCount) {
        final Context context = Factory.get().getApplicationContext();

        // Limit the number of messages to show. We just want enough to provide context for the
        // notification. Fetch one more than we need, so we can tell if there are more messages
        // before the one we're showing.
        // TODO: in the query, a multipart message will contain a row for each part.
        // We might need a smarter GROUP_BY. On the other hand, we might want to show each of the
        // parts as separate messages on the wearable.
        final int limit = MAX_MESSAGES_IN_WEARABLE_PAGE + 1;

        final List<CharSequence> messages = new ArrayList<>();
        boolean hasSeenMessagesBeforeNotification = false;
        Cursor convMessageCursor = null;
        try {
            final DatabaseWrapper db = DataModel.get().getDatabase();

            final String[] queryArgs = { conversationId };
            final String convPageSql = ConversationMessageData.getWearableQuerySql() + " LIMIT " +
                    limit;
            convMessageCursor = db.rawQuery(
                    convPageSql,
                    queryArgs);

            if (convMessageCursor == null || !convMessageCursor.moveToFirst()) {
                return null;
            }
            final ConversationMessageData convMessageData =
                    new ConversationMessageData();

            final HashMap<String, Integer> firstNames = scanFirstNames(conversationId);
            do {
                convMessageData.bind(convMessageCursor);

                final String authorFullName = convMessageData.getSenderFullName();
                final String authorFirstName = convMessageData.getSenderFirstName();
                String text = convMessageData.getText();

                final boolean isSmsPushNotification = convMessageData.getIsMmsNotification();

                // if auto-download was off to show a message to tap to download the message. We
                // might need to get that working again.
                if (isSmsPushNotification && text != null) {
                    text = convertHtmlAndStripUrls(text).toString();
                }
                // Skip messages without any content
                if (TextUtils.isEmpty(text) && !convMessageData.hasAttachments()) {
                    continue;
                }
                // Track whether there are messages prior to the one(s) shown in the notification.
                if (convMessageData.getIsSeen()) {
                    hasSeenMessagesBeforeNotification = true;
                }

                final boolean usedMoreThanOnce = firstNameUsedMoreThanOnce(
                        firstNames, authorFirstName);
                String displayName = usedMoreThanOnce ? authorFullName : authorFirstName;
                if (TextUtils.isEmpty(displayName)) {
                    if (convMessageData.getIsIncoming()) {
                        displayName = convMessageData.getSenderDisplayDestination();
                        if (TextUtils.isEmpty(displayName)) {
                            displayName = context.getString(R.string.unknown_sender);
                        }
                    } else {
                        displayName = context.getString(R.string.unknown_self_participant);
                    }
                }

                Uri attachmentUri = null;
                String attachmentType = null;
                final List<MessagePartData> attachments = convMessageData.getAttachments();
                for (final MessagePartData messagePartData : attachments) {
                    // Look for the first attachment that's not the text piece.
                    if (!messagePartData.isText()) {
                        attachmentUri = messagePartData.getContentUri();
                        attachmentType = messagePartData.getContentType();
                        break;
                    }
                }

                final CharSequence message = BugleNotifications.buildSpaceSeparatedMessage(
                        displayName, text, attachmentUri, attachmentType);
                messages.add(message);

            } while (convMessageCursor.moveToNext());
        } finally {
            if (convMessageCursor != null) {
                convMessageCursor.close();
            }
        }

        // If there is no conversation history prior to what is already visible in the main
        // notification, there's no need to include the conversation log, too.
        final int maxMessagesInNotification = getMaxMessagesInConversationNotification();
        if (!hasSeenMessagesBeforeNotification && messages.size() <= maxMessagesInNotification) {
            return null;
        }

        final SpannableStringBuilder bigText = new SpannableStringBuilder();
        // There is at least 1 message prior to the first one that we're going to show.
        // Indicate this by inserting an ellipsis at the beginning of the conversation log.
        if (convMessageCursor.getCount() == limit) {
            bigText.append(context.getString(R.string.ellipsis) + "\n\n");
            if (messages.size() > MAX_MESSAGES_IN_WEARABLE_PAGE) {
                messages.remove(messages.size() - 1);
            }
        }
        // Messages are sorted in descending timestamp order, so iterate backwards
        // to get them back in ascending order for display purposes.
        for (int i = messages.size() - 1; i >= 0; --i) {
            bigText.append(messages.get(i));
            if (i > 0) {
                bigText.append("\n\n");
            }
        }
        ++participantCount;     // Add in myself

        if (participantCount > 2) {
            final SpannableString statusText = new SpannableString(
                    context.getResources().getQuantityString(R.plurals.wearable_participant_count,
                            participantCount, participantCount));
            statusText.setSpan(new ForegroundColorSpan(context.getResources().getColor(
                    R.color.wearable_notification_participants_count)), 0, statusText.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            bigText.append("\n\n").append(statusText);
        }

        final NotificationCompat.Builder notifBuilder = new NotificationCompat.Builder(context, NotificationChannelUtil.CONVERSATIONS);
        final NotificationCompat.Style notifStyle =
                new NotificationCompat.BigTextStyle(notifBuilder).bigText(bigText);
        notifBuilder.setStyle(notifStyle);

        final WearableExtender wearableExtender = new WearableExtender();
        wearableExtender.setStartScrollBottom(true);
        notifBuilder.extend(wearableExtender);

        return notifBuilder.build();
    }

    /**
     * Notification for one or more messages in a single conversation, which is bundled together
     * with notifications for other conversations on a wearable device.
     */
    public static class BundledMessageNotificationState extends MultiMessageNotificationState {
        public int mGroupOrder;
        public BundledMessageNotificationState(final ConversationInfoList convList,
                final int groupOrder) {
            super(convList);
            mGroupOrder = groupOrder;
        }
    }

    /**
     * Performs a query on the database.
     */
    private static ConversationInfoList createConversationInfoList() {
        // Map key is conversation id. We use LinkedHashMap to ensure that entries are iterated in
        // the same order they were originally added. We scan unseen messages from newest to oldest,
        // so the corresponding conversations are added in that order, too.
        final Map<String, ConversationLineInfo> convLineInfos = new LinkedHashMap<>();
        int messageCount = 0;

        Cursor convMessageCursor = null;
        try {
            final Context context = Factory.get().getApplicationContext();
            final DatabaseWrapper db = DataModel.get().getDatabase();

            convMessageCursor = db.rawQuery(
                    ConversationMessageData.getNotificationQuerySql(),
                    null);

            if (convMessageCursor != null && convMessageCursor.moveToFirst()) {
                if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
                    LogUtil.v(TAG, "MessageNotificationState: Found unseen message notifications.");
                }
                final ConversationMessageData convMessageData =
                        new ConversationMessageData();

                HashMap<String, Integer> firstNames = null;
                String conversationIdForFirstNames = null;
                String groupConversationName = null;
                final int maxMessages = getMaxMessagesInConversationNotification();

                do {
                    convMessageData.bind(convMessageCursor);

                    // First figure out if this is a valid message.
                    String authorFullName = convMessageData.getSenderFullName();
                    String authorFirstName = convMessageData.getSenderFirstName();
                    final String messageText = convMessageData.getText();

                    final String convId = convMessageData.getConversationId();
                    final String messageId = convMessageData.getMessageId();

                    CharSequence text = messageText;
                    final boolean isManualDownloadNeeded = convMessageData.getIsMmsNotification();
                    if (isManualDownloadNeeded) {
                        // Don't try and convert the text from html if it's sms and not a sms push
                        // notification.
                        Assert.equals(MessageData.BUGLE_STATUS_INCOMING_YET_TO_MANUAL_DOWNLOAD,
                                convMessageData.getStatus());
                        text = context.getResources().getString(
                                R.string.message_title_manual_download);
                    }
                    ConversationLineInfo currConvInfo = convLineInfos.get(convId);
                    if (currConvInfo == null) {
                        final ConversationListItemData convData =
                                ConversationListItemData.getExistingConversation(db, convId);
                        if (!convData.getNotificationEnabled()) {
                            // Skip conversations that have notifications disabled.
                            continue;
                        }
                        final int subId = BugleDatabaseOperations.getSelfSubscriptionId(db,
                                convData.getSelfId());
                        groupConversationName = convData.getName();
                        final Uri avatarUri = AvatarUriUtil.createAvatarUri(
                                convMessageData.getSenderProfilePhotoUri(),
                                convMessageData.getSenderFullName(),
                                convMessageData.getSenderNormalizedDestination(),
                                convMessageData.getSenderContactLookupKey());
                        currConvInfo = new ConversationLineInfo(convId,
                                convData.getIsGroup(),
                                groupConversationName,
                                convData.getIncludeEmailAddress(),
                                convMessageData.getReceivedTimeStamp(),
                                convData.getSelfId(),
                                convData.getNotificationSoundUri(),
                                convData.getNotificationEnabled(),
                                convData.getNotifiationVibrate(),
                                avatarUri,
                                convMessageData.getSenderContactLookupUri(),
                                subId,
                                convData.getParticipantCount());
                        convLineInfos.put(convId, currConvInfo);
                    }
                    // Prepare the message line
                    if (currConvInfo.mTotalMessageCount < maxMessages) {
                        if (currConvInfo.mIsGroup) {
                            if (authorFirstName == null) {
                                // authorFullName might be null as well. In that case, we won't
                                // show an author. That is better than showing all the group
                                // names again on the 2nd line.
                                authorFirstName = authorFullName;
                            }
                        } else {
                            // don't recompute this if we don't need to
                            if (!TextUtils.equals(conversationIdForFirstNames, convId)) {
                                firstNames = scanFirstNames(convId);
                                conversationIdForFirstNames = convId;
                            }
                            if (firstNames != null) {
                                final Integer count = firstNames.get(authorFirstName);
                                if (count != null && count > 1) {
                                    authorFirstName = authorFullName;
                                }
                            }

                            if (authorFullName == null) {
                                authorFullName = groupConversationName;
                            }
                            if (authorFirstName == null) {
                                authorFirstName = groupConversationName;
                            }
                        }
                        final String subjectText = MmsUtils.cleanseMmsSubject(
                                context.getResources(),
                                convMessageData.getMmsSubject());
                        if (!TextUtils.isEmpty(subjectText)) {
                            final String subjectLabel =
                                    context.getString(R.string.subject_label);
                            final SpannableStringBuilder spanBuilder =
                                    new SpannableStringBuilder();

                            spanBuilder.append(context.getString(R.string.notification_subject,
                                    subjectLabel, subjectText));
                            spanBuilder.setSpan(new TextAppearanceSpan(
                                    context, R.style.NotificationSubjectText), 0,
                                    subjectLabel.length(),
                                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                            if (!TextUtils.isEmpty(text)) {
                                // Now add the actual message text below the subject header.
                                spanBuilder.append(System.getProperty("line.separator") + text);
                            }
                            text = spanBuilder;
                        }
                        // If we've got attachments, find the best one. If one of the messages is
                        // a photo, save the url so we'll display a big picture notification.
                        // Otherwise, show the first one we find.
                        Uri attachmentUri = null;
                        String attachmentType = null;
                        final MessagePartData messagePartData =
                                getMostInterestingAttachment(convMessageData);
                        if (messagePartData != null) {
                            attachmentUri = messagePartData.getContentUri();
                            attachmentType = messagePartData.getContentType();
                        }
                        currConvInfo.mLineInfos.add(new MessageLineInfo(currConvInfo.mIsGroup,
                                authorFullName, authorFirstName, text,
                                attachmentUri, attachmentType, isManualDownloadNeeded, messageId));
                    }
                    messageCount++;
                    currConvInfo.mTotalMessageCount++;
                } while (convMessageCursor.moveToNext());
            }
        } finally {
            if (convMessageCursor != null) {
                convMessageCursor.close();
            }
        }
        if (convLineInfos.isEmpty()) {
            return null;
        } else {
            return new ConversationInfoList(messageCount,
                    new LinkedList<>(convLineInfos.values()));
        }
    }

    /**
     * Scans all the attachments for a message and returns the most interesting one that we'll
     * show in a notification. By order of importance, in case there are multiple attachments:
     *      1- an image (because we can show the image as a BigPictureNotification)
     *      2- a video (because we can show a video frame as a BigPictureNotification)
     *      3- a vcard
     *      4- an audio attachment
     * @return MessagePartData for the most interesting part. Can be null.
     */
    private static MessagePartData getMostInterestingAttachment(
            final ConversationMessageData convMessageData) {
        final List<MessagePartData> attachments = convMessageData.getAttachments();

        MessagePartData imagePart = null;
        MessagePartData audioPart = null;
        MessagePartData vcardPart = null;
        MessagePartData videoPart = null;

        // 99.99% of the time there will be 0 or 1 part, since receiving slideshows is so
        // uncommon.

        // Remember the first of each type of part.
        for (final MessagePartData messagePartData : attachments) {
            if (messagePartData.isImage() && imagePart == null) {
                imagePart = messagePartData;
            }
            if (messagePartData.isVideo() && videoPart == null) {
                videoPart = messagePartData;
            }
            if (messagePartData.isVCard() && vcardPart == null) {
                vcardPart = messagePartData;
            }
            if (messagePartData.isAudio() && audioPart == null) {
                audioPart = messagePartData;
            }
        }
        if (imagePart != null) {
            return imagePart;
        } else if (videoPart != null) {
            return videoPart;
        } else if (audioPart != null) {
            return audioPart;
        } else if (vcardPart != null) {
            return vcardPart;
        }
        return null;
    }

    private static int getMaxMessagesInConversationNotification() {
        if (!BugleNotifications.isWearCompanionAppInstalled()) {
            return BugleGservices.get().getInt(
                    BugleGservicesKeys.MAX_MESSAGES_IN_CONVERSATION_NOTIFICATION,
                    BugleGservicesKeys.MAX_MESSAGES_IN_CONVERSATION_NOTIFICATION_DEFAULT);
        }
        return BugleGservices.get().getInt(
                BugleGservicesKeys.MAX_MESSAGES_IN_CONVERSATION_NOTIFICATION_WITH_WEARABLE,
                BugleGservicesKeys.MAX_MESSAGES_IN_CONVERSATION_NOTIFICATION_WITH_WEARABLE_DEFAULT);
    }

    /**
     * Scans the database for messages that need to go into notifications. Creates the appropriate
     * MessageNotificationState depending on if there are multiple senders, or
     * messages from one sender.
     * @return NotificationState for the notification created.
     */
    public static NotificationState getNotificationState() {
        MessageNotificationState state = null;
        final ConversationInfoList convList = createConversationInfoList();

        if (convList == null || convList.mConvInfos.size() == 0) {
            if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
                LogUtil.v(TAG, "MessageNotificationState: No unseen notifications");
            }
        } else {
            final ConversationLineInfo convInfo = convList.mConvInfos.get(0);
            state = new MultiMessageNotificationState(convList);

            if (convList.mConvInfos.size() > 1) {
                // We've got notifications across multiple conversations. Pass in the notification
                // we just built of the most recent notification so we can use that to show the
                // user the new message in the ticker.
                state = new MultiConversationNotificationState(convList, state);
            } else {
                // For now, only show avatars for notifications for a single conversation.
                if (convInfo.mAvatarUri != null) {
                    if (state.mParticipantAvatarsUris == null) {
                        state.mParticipantAvatarsUris = new ArrayList<Uri>(1);
                    }
                    state.mParticipantAvatarsUris.add(convInfo.mAvatarUri);
                }
                if (convInfo.mContactUri != null) {
                    if (state.mParticipantContactUris == null) {
                        state.mParticipantContactUris = new ArrayList<Uri>(1);
                    }
                    state.mParticipantContactUris.add(convInfo.mContactUri);
                }
            }
        }
        if (state != null && LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
            LogUtil.v(TAG, "MessageNotificationState: Notification state created"
                    + ", title = "
                    + (state.mTickerSender != null ? state.mTickerSender : state.mTitle)
                    + ", content = "
                    + (state.mTickerText != null ? state.mTickerText : state.mContent));
        }
        return state;
    }

    protected String getTitle() {
        return mTitle;
    }

    @Override
    public int getLatestMessageNotificationType() {
        // This function is called to determine whether the most recent notification applies
        // to an sms conversation or a hangout conversation. We have different ringtone/vibrate
        // settings for both types of conversations.
        if (mConvList.mConvInfos.size() > 0) {
            final ConversationLineInfo convInfo = mConvList.mConvInfos.get(0);
            return convInfo.getLatestMessageNotificationType();
        }
        return BugleNotifications.LOCAL_SMS_NOTIFICATION;
    }

    @Override
    public String getRingtoneUri() {
        if (mConvList.mConvInfos.size() > 0) {
            return mConvList.mConvInfos.get(0).mRingtoneUri;
        }
        return null;
    }

    @Override
    public boolean getNotificationVibrate() {
        if (mConvList.mConvInfos.size() > 0) {
            return mConvList.mConvInfos.get(0).mNotificationVibrate;
        }
        return false;
    }

    protected CharSequence getTicker() {
        return BugleNotifications.buildColonSeparatedMessage(
                mTickerSender != null ? mTickerSender : mTitle,
                mTickerText != null ? mTickerText : mContent,
                null,
                null);
    }

    private static CharSequence convertHtmlAndStripUrls(final String s) {
        final Spanned text = Html.fromHtml(s);
        if (text instanceof Spannable) {
            stripUrls((Spannable) text);
        }
        return text;
    }

    // Since we don't want to show URLs in notifications, a function
    // to remove them in place.
    private static void stripUrls(final Spannable text) {
        final URLSpan[] spans = text.getSpans(0, text.length(), URLSpan.class);
        for (final URLSpan span : spans) {
            text.removeSpan(span);
        }
    }

    /*
    private static void updateAlertStatusMessages(final long thresholdDeltaMs) {
        // TODO may need this when supporting error notifications
        final EsDatabaseHelper helper = EsDatabaseHelper.getDatabaseHelper();
        final ContentValues values = new ContentValues();
        final long nowMicros = System.currentTimeMillis() * 1000;
        values.put(MessageColumns.ALERT_STATUS, "1");
        final String selection =
                MessageColumns.ALERT_STATUS + "=0 AND (" +
                MessageColumns.STATUS + "=" + EsProvider.MESSAGE_STATUS_FAILED_TO_SEND + " OR (" +
                MessageColumns.STATUS + "!=" + EsProvider.MESSAGE_STATUS_ON_SERVER + " AND " +
                MessageColumns.TIMESTAMP + "+" + thresholdDeltaMs*1000 + "<" + nowMicros + ")) ";

        final int updateCount = helper.getWritableDatabaseWrapper().update(
                EsProvider.MESSAGES_TABLE,
                values,
                selection,
                null);
        if (updateCount > 0) {
            EsConversationsData.notifyConversationsChanged();
        }
    }*/

    static CharSequence applyWarningTextColor(final Context context,
            final CharSequence text) {
        if (text == null) {
            return null;
        }
        final SpannableStringBuilder spanBuilder = new SpannableStringBuilder();
        spanBuilder.append(text);
        spanBuilder.setSpan(new ForegroundColorSpan(context.getResources().getColor(
                R.color.notification_warning_color)), 0, text.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        return spanBuilder;
    }

    /**
     * Check for failed messages and post notifications as needed.
     * TODO: Rewrite this as a NotificationState.
     */
    public static void checkFailedMessages() {
        final DatabaseWrapper db = DataModel.get().getDatabase();

        final Cursor messageDataCursor = db.query(DatabaseHelper.MESSAGES_TABLE,
            MessageData.getProjection(),
            FailedMessageQuery.FAILED_MESSAGES_WHERE_CLAUSE,
            null /*selectionArgs*/,
            null /*groupBy*/,
            null /*having*/,
            FailedMessageQuery.FAILED_ORDER_BY);

        try {
            final Context context = Factory.get().getApplicationContext();
            final Resources resources = context.getResources();
            final NotificationManagerCompat notificationManager =
                    NotificationManagerCompat.from(context);
            if (messageDataCursor != null) {
                final MessageData messageData = new MessageData();

                final HashSet<String> conversationsWithFailedMessages = new HashSet<String>();

                // track row ids in case we want to display something that requires this
                // information
                final ArrayList<Integer> failedMessages = new ArrayList<Integer>();

                int cursorPosition = -1;
                final long when = 0;

                messageDataCursor.moveToPosition(-1);
                while (messageDataCursor.moveToNext()) {
                    messageData.bind(messageDataCursor);

                    final String conversationId = messageData.getConversationId();
                    if (DataModel.get().isNewMessageObservable(conversationId)) {
                        // Don't post a system notification for an observable conversation
                        // because we already show an angry red annotation in the conversation
                        // itself or in the conversation preview snippet.
                        continue;
                    }

                    cursorPosition = messageDataCursor.getPosition();
                    failedMessages.add(cursorPosition);
                    conversationsWithFailedMessages.add(conversationId);
                }

                if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
                    LogUtil.d(TAG, "Found " + failedMessages.size() + " failed messages");
                }
                if (failedMessages.size() > 0) {
                    final NotificationCompat.Builder builder =
                            new NotificationCompat.Builder(context);

                    CharSequence line1;
                    CharSequence line2;
                    final boolean isRichContent = false;
                    ConversationIdSet conversationIds = null;
                    PendingIntent destinationIntent;
                    if (failedMessages.size() == 1) {
                        messageDataCursor.moveToPosition(cursorPosition);
                        messageData.bind(messageDataCursor);
                        final String conversationId =  messageData.getConversationId();

                        // We have a single conversation, go directly to that conversation.
                        destinationIntent = UIIntents.get()
                                .getPendingIntentForConversationActivity(context,
                                        conversationId,
                                        null /*draft*/);

                        conversationIds = ConversationIdSet.createSet(conversationId);

                        final String failedMessgeSnippet = messageData.getMessageText();
                        int failureStringId;
                        if (messageData.getStatus() ==
                                MessageData.BUGLE_STATUS_INCOMING_DOWNLOAD_FAILED) {
                            failureStringId =
                                    R.string.notification_download_failures_line1_singular;
                        } else {
                            failureStringId = R.string.notification_send_failures_line1_singular;
                        }
                        line1 = resources.getString(failureStringId);
                        line2 = failedMessgeSnippet;
                        // Set rich text for non-SMS messages or MMS push notification messages
                        // which we generate locally with rich text
                        // TODO- fix this
//                        if (messageData.isMmsInd()) {
//                            isRichContent = true;
//                        }
                    } else {
                        // We have notifications for multiple conversation, go to the conversation
                        // list.
                        destinationIntent = UIIntents.get()
                            .getPendingIntentForConversationListActivity(context);

                        int line1StringId;
                        int line2PluralsId;
                        if (messageData.getStatus() ==
                                MessageData.BUGLE_STATUS_INCOMING_DOWNLOAD_FAILED) {
                            line1StringId =
                                    R.string.notification_download_failures_line1_plural;
                            line2PluralsId = R.plurals.notification_download_failures;
                        } else {
                            line1StringId = R.string.notification_send_failures_line1_plural;
                            line2PluralsId = R.plurals.notification_send_failures;
                        }
                        line1 = resources.getString(line1StringId);
                        line2 = resources.getQuantityString(
                                line2PluralsId,
                                conversationsWithFailedMessages.size(),
                                failedMessages.size(),
                                conversationsWithFailedMessages.size());
                    }
                    line1 = applyWarningTextColor(context, line1);
                    line2 = applyWarningTextColor(context, line2);

                    final PendingIntent pendingIntentForDelete =
                            UIIntents.get().getPendingIntentForClearingNotifications(
                                    context,
                                    BugleNotifications.UPDATE_ERRORS,
                                    conversationIds,
                                    0);

                    builder
                        .setContentTitle(line1)
                        .setTicker(line1)
                        .setWhen(when > 0 ? when : System.currentTimeMillis())
                        .setSmallIcon(R.drawable.ic_failed_light)
                        .setDeleteIntent(pendingIntentForDelete)
                        .setContentIntent(destinationIntent)
                        .setSound(UriUtil.getUriForResourceId(context, R.raw.message_failure));
                    if (isRichContent && !TextUtils.isEmpty(line2)) {
                        final NotificationCompat.InboxStyle inboxStyle =
                                new NotificationCompat.InboxStyle(builder);
                        if (line2 != null) {
                            inboxStyle.addLine(Html.fromHtml(line2.toString()));
                        }
                        builder.setStyle(inboxStyle);
                    } else {
                        builder.setContentText(line2);
                    }

                    if (builder != null) {
                        notificationManager.notify(
                                BugleNotifications.buildNotificationTag(
                                        PendingIntentConstants.MSG_SEND_ERROR, null),
                                PendingIntentConstants.MSG_SEND_ERROR,
                                builder.build());
                    }
                } else {
                    notificationManager.cancel(
                            BugleNotifications.buildNotificationTag(
                                    PendingIntentConstants.MSG_SEND_ERROR, null),
                            PendingIntentConstants.MSG_SEND_ERROR);
                }
            }
        } finally {
            if (messageDataCursor != null) {
                messageDataCursor.close();
            }
        }
    }
}
