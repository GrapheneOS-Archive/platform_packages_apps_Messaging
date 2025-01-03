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

package com.android.messaging.widget;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import com.android.messaging.R;
import com.android.messaging.datamodel.MessagingContentProvider;
import com.android.messaging.datamodel.data.ConversationMessageData;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.datamodel.data.MessagePartData;
import com.android.messaging.datamodel.media.ImageResource;
import com.android.messaging.datamodel.media.MediaRequest;
import com.android.messaging.datamodel.media.MediaResourceManager;
import com.android.messaging.datamodel.media.MessagePartImageRequestDescriptor;
import com.android.messaging.datamodel.media.MessagePartVideoThumbnailRequestDescriptor;
import com.android.messaging.datamodel.media.UriImageRequestDescriptor;
import com.android.messaging.datamodel.media.VideoThumbnailRequest;
import com.android.messaging.sms.MmsUtils;
import com.android.messaging.ui.UIIntents;
import com.android.messaging.util.AvatarUriUtil;
import com.android.messaging.util.Dates;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.OsUtil;
import com.android.messaging.util.PhoneUtils;

import java.util.List;

public class WidgetConversationService extends RemoteViewsService {
    private static final String TAG = LogUtil.BUGLE_WIDGET_TAG;

    private static final int IMAGE_ATTACHMENT_SIZE = 400;

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
            LogUtil.v(TAG, "onGetViewFactory intent: " + intent);
        }
        return new WidgetConversationFactory(getApplicationContext(), intent);
    }

    /**
     * Remote Views Factory for the conversation widget.
     */
    private static class WidgetConversationFactory extends BaseWidgetFactory {
        private ImageResource mImageResource;
        private String mConversationId;

        public WidgetConversationFactory(Context context, Intent intent) {
            super(context, intent);

            mConversationId = intent.getStringExtra(UIIntents.UI_INTENT_EXTRA_CONVERSATION_ID);
            if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
                LogUtil.v(TAG, "BugleFactory intent: " + intent + "widget id: " + mAppWidgetId);
            }
            mIconSize = (int) context.getResources()
                    .getDimension(R.dimen.contact_icon_view_normal_size);
        }

        @Override
        public void onCreate() {
            if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
                LogUtil.v(TAG, "onCreate");
            }
            super.onCreate();

            // If the conversation for this widget has been removed, we want to update the widget to
            // "Tap to configure" mode.
            if (!WidgetConversationProvider.isWidgetConfigured(mAppWidgetId)) {
                WidgetConversationProvider.rebuildWidget(mContext, mAppWidgetId);
            }
        }

        @Override
        protected Cursor doQuery() {
            if (TextUtils.isEmpty(mConversationId)) {
                LogUtil.w(TAG, "doQuery no conversation id");
                return null;
            }
            final Uri uri = MessagingContentProvider.buildConversationMessagesUri(mConversationId);
            if (uri != null) {
                LogUtil.w(TAG, "doQuery uri: " + uri.toString());
            }
            return mContext.getContentResolver().query(uri,
                    ConversationMessageData.getProjection(),
                    null,       // where
                    null,       // selection args
                    null        // sort order
                    );
        }

        /**
         * @return the {@link RemoteViews} for a specific position in the list.
         */
        @Override
        public RemoteViews getViewAt(final int originalPosition) {
            synchronized (sWidgetLock) {
                // "View more messages" view.
                if (mCursor == null
                        || (mShouldShowViewMore && originalPosition == 0)) {
                    return getViewMoreItemsView();
                }
                // The message cursor is in reverse order for performance reasons.
                final int position = getCount() - originalPosition - 1;
                if (!mCursor.moveToPosition(position)) {
                    // If we ever fail to move to a position, return the "View More messages"
                    // view.
                    LogUtil.w(TAG, "Failed to move to position: " + position);
                    return getViewMoreItemsView();
                }

                final ConversationMessageData message = new ConversationMessageData();
                message.bind(mCursor);

                // Inflate and fill out the remote view
                final RemoteViews remoteViews = new RemoteViews(
                        mContext.getPackageName(), message.getIsIncoming() ?
                                R.layout.widget_message_item_incoming :
                                    R.layout.widget_message_item_outgoing);

                final boolean hasUnreadMessages = false; //!message.getIsRead();

                // Date
                remoteViews.setTextViewText(R.id.date, boldifyIfUnread(
                        Dates.getWidgetTimeString(message.getReceivedTimeStamp(),
                                false /*abbreviated*/),
                        hasUnreadMessages));

                // On click intent.
                final Intent intent = UIIntents.get().getIntentForConversationActivity(mContext,
                        mConversationId, null /* draft */);

                // Attachments
                int attachmentStringId = 0;
                remoteViews.setViewVisibility(R.id.attachmentFrame, View.GONE);

                int scrollToPosition = originalPosition;
                final int cursorCount = mCursor.getCount();
                if (cursorCount > MAX_ITEMS_TO_SHOW) {
                    scrollToPosition += cursorCount - MAX_ITEMS_TO_SHOW;
                }
                if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
                    LogUtil.v(TAG, "getViewAt position: " + originalPosition +
                            " computed position: " + position +
                            " scrollToPosition: " + scrollToPosition +
                            " cursorCount: " + cursorCount +
                            " MAX_ITEMS_TO_SHOW: " + MAX_ITEMS_TO_SHOW);
                }

                intent.putExtra(UIIntents.UI_INTENT_EXTRA_MESSAGE_POSITION, scrollToPosition);
                if (message.hasAttachments()) {
                    final List<MessagePartData> attachments = message.getAttachments();
                    for (MessagePartData part : attachments) {
                        final boolean videoWithThumbnail = part.isVideo()
                                && (VideoThumbnailRequest.shouldShowIncomingVideoThumbnails()
                                || !message.getIsIncoming());
                        if (part.isImage() || videoWithThumbnail) {
                            final Uri uri = part.getContentUri();
                            remoteViews.setViewVisibility(R.id.attachmentFrame, View.VISIBLE);
                            remoteViews.setViewVisibility(R.id.playButton, part.isVideo() ?
                                    View.VISIBLE : View.GONE);
                            remoteViews.setImageViewBitmap(R.id.attachment,
                                    getAttachmentBitmap(part));
                            intent.putExtra(UIIntents.UI_INTENT_EXTRA_ATTACHMENT_URI ,
                                    uri.toString());
                            intent.putExtra(UIIntents.UI_INTENT_EXTRA_ATTACHMENT_TYPE ,
                                    part.getContentType());
                            break;
                        } else if (part.isVideo()) {
                            attachmentStringId = R.string.conversation_list_snippet_video;
                            break;
                        }
                        if (part.isAudio()) {
                            attachmentStringId = R.string.conversation_list_snippet_audio_clip;
                            break;
                        }
                        if (part.isVCard()) {
                            attachmentStringId = R.string.conversation_list_snippet_vcard;
                            break;
                        }
                    }
                }

                remoteViews.setOnClickFillInIntent(message.getIsIncoming() ?
                        R.id.widget_message_item_incoming :
                            R.id.widget_message_item_outgoing,
                        intent);

                // Avatar
                final Bundle options = mAppWidgetManager.getAppWidgetOptions(mAppWidgetId);
                if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
                    LogUtil.v(TAG, "getViewAt BugleWidgetProvider.WIDGET_SIZE_KEY: " +
                            options.getInt(BugleWidgetProvider.WIDGET_SIZE_KEY));
                }

                boolean includeAvatar = options.getInt(BugleWidgetProvider.WIDGET_SIZE_KEY)
                        == BugleWidgetProvider.SIZE_LARGE;

                // Show the avatar (and shadow) when grande size, otherwise hide it.
                remoteViews.setViewVisibility(R.id.avatarView, includeAvatar ?
                        View.VISIBLE : View.GONE);
                remoteViews.setViewVisibility(R.id.avatarShadow, includeAvatar ?
                        View.VISIBLE : View.GONE);

                final Uri avatarUri = AvatarUriUtil.createAvatarUri(
                        message.getSenderProfilePhotoUri(),
                        message.getSenderFullName(),
                        message.getSenderNormalizedDestination(),
                        message.getSenderContactLookupKey());

                remoteViews.setImageViewBitmap(R.id.avatarView, includeAvatar ?
                        getAvatarBitmap(avatarUri) : null);

                String text = message.getText();
                if (attachmentStringId != 0) {
                    final String attachment = mContext.getString(attachmentStringId);
                    if (!TextUtils.isEmpty(text)) {
                        text += '\n' + attachment;
                    } else {
                        text = attachment;
                    }
                }

                remoteViews.setViewVisibility(R.id.message, View.VISIBLE);
                updateViewContent(text, message, remoteViews);

                return remoteViews;
            }
        }

        // updateViewContent figures out what to show in the message and date fields based on
        // the message status. This code came from ConversationMessageView.updateViewContent, but
        // had to be simplified to work with our simple widget list item.
        // updateViewContent also builds the accessibility content description for the list item.
        private void updateViewContent(final String messageText,
                final ConversationMessageData message,
                final RemoteViews remoteViews) {
            int titleResId = -1;
            int statusResId = -1;
            boolean showInRed = false;
            String statusText = null;
            switch(message.getStatus()) {
                case MessageData.BUGLE_STATUS_INCOMING_AUTO_DOWNLOADING:
                case MessageData.BUGLE_STATUS_INCOMING_MANUAL_DOWNLOADING:
                case MessageData.BUGLE_STATUS_INCOMING_RETRYING_AUTO_DOWNLOAD:
                case MessageData.BUGLE_STATUS_INCOMING_RETRYING_MANUAL_DOWNLOAD:
                    titleResId = R.string.message_title_downloading;
                    statusResId = R.string.message_status_downloading;
                    break;

                case MessageData.BUGLE_STATUS_INCOMING_YET_TO_MANUAL_DOWNLOAD:
                    if (!OsUtil.isSecondaryUser()) {
                        titleResId = R.string.message_title_manual_download;
                        statusResId = R.string.message_status_download;
                    }
                    break;

                case MessageData.BUGLE_STATUS_INCOMING_EXPIRED_OR_NOT_AVAILABLE:
                    if (!OsUtil.isSecondaryUser()) {
                        titleResId = R.string.message_title_download_failed;
                        statusResId = R.string.message_status_download_error;
                        showInRed = true;
                    }
                    break;

                case MessageData.BUGLE_STATUS_INCOMING_DOWNLOAD_FAILED:
                    if (!OsUtil.isSecondaryUser()) {
                        titleResId = R.string.message_title_download_failed;
                        statusResId = R.string.message_status_download;
                        showInRed = true;
                    }
                    break;

                case MessageData.BUGLE_STATUS_OUTGOING_YET_TO_SEND:
                case MessageData.BUGLE_STATUS_OUTGOING_SENDING:
                    statusResId = R.string.message_status_sending;
                    break;

                case MessageData.BUGLE_STATUS_OUTGOING_RESENDING:
                case MessageData.BUGLE_STATUS_OUTGOING_AWAITING_RETRY:
                    statusResId = R.string.message_status_send_retrying;
                    break;

                case MessageData.BUGLE_STATUS_OUTGOING_FAILED_EMERGENCY_NUMBER:
                    statusResId = R.string.message_status_send_failed_emergency_number;
                    showInRed = true;
                    break;

                case MessageData.BUGLE_STATUS_OUTGOING_FAILED:
                    // don't show the error state unless we're the default sms app
                    if (PhoneUtils.getDefault().isDefaultSmsApp()) {
                        statusResId = MmsUtils.mapRawStatusToErrorResourceId(
                                message.getStatus(), message.getRawTelephonyStatus());
                        showInRed = true;
                        break;
                    }
                    // FALL THROUGH HERE

                case MessageData.BUGLE_STATUS_OUTGOING_COMPLETE:
                case MessageData.BUGLE_STATUS_OUTGOING_DELIVERED:
                case MessageData.BUGLE_STATUS_INCOMING_COMPLETE:
                default:
                    if (!message.getCanClusterWithNextMessage()) {
                        statusText = Dates.getWidgetTimeString(message.getReceivedTimeStamp(),
                                false /*abbreviated*/).toString();
                    }
                    break;
            }

            // Build the content description while we're populating the various fields.
            final StringBuilder description = new StringBuilder();
            final String separator = mContext.getString(R.string.enumeration_comma);
            // Sender information
            final boolean hasPlainTextMessage = !(TextUtils.isEmpty(message.getText()));
            if (message.getIsIncoming()) {
                int senderResId = hasPlainTextMessage
                    ? R.string.incoming_text_sender_content_description
                    : R.string.incoming_sender_content_description;
                description.append(mContext.getString(senderResId, message.getSenderDisplayName()));
            } else {
                int senderResId = hasPlainTextMessage
                    ? R.string.outgoing_text_sender_content_description
                    : R.string.outgoing_sender_content_description;
                description.append(mContext.getString(senderResId));
            }

            final boolean titleVisible = (titleResId >= 0);
            if (titleVisible) {
                final String titleText = mContext.getString(titleResId);
                remoteViews.setTextViewText(R.id.message, titleText);

                final String mmsInfoText = mContext.getString(
                        R.string.mms_info,
                        Formatter.formatFileSize(mContext, message.getSmsMessageSize()),
                        DateUtils.formatDateTime(
                                mContext,
                                message.getMmsExpiry(),
                                DateUtils.FORMAT_SHOW_DATE |
                                DateUtils.FORMAT_SHOW_TIME |
                                DateUtils.FORMAT_NUMERIC_DATE |
                                DateUtils.FORMAT_NO_YEAR));
                remoteViews.setTextViewText(R.id.date, mmsInfoText);
                description.append(separator);
                description.append(mmsInfoText);
            } else if (!TextUtils.isEmpty(messageText)) {
                remoteViews.setTextViewText(R.id.message, messageText);
                description.append(separator);
                description.append(messageText);
            } else {
                remoteViews.setViewVisibility(R.id.message, View.GONE);
            }

            final String subjectText = MmsUtils.cleanseMmsSubject(mContext.getResources(),
                    message.getMmsSubject());
            if (!TextUtils.isEmpty(subjectText)) {
                description.append(separator);
                description.append(subjectText);
            }

            if (statusResId >= 0) {
                statusText = mContext.getString(statusResId);
                final Spannable colorStr = new SpannableString(statusText);
                if (showInRed) {
                    colorStr.setSpan(new ForegroundColorSpan(
                            mContext.getResources().getColor(R.color.timestamp_text_failed)),
                            0, statusText.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                remoteViews.setTextViewText(R.id.date, colorStr);
                description.append(separator);
                description.append(colorStr);
            } else {
                description.append(separator);
                description.append(Dates.getWidgetTimeString(message.getReceivedTimeStamp(),
                        false /*abbreviated*/));
            }

            if (message.hasAttachments()) {
                final List<MessagePartData> attachments = message.getAttachments();
                int stringId;
                for (MessagePartData part : attachments) {
                    if (part.isImage()) {
                        stringId = R.string.conversation_list_snippet_picture;
                    } else if (part.isVideo()) {
                        stringId = R.string.conversation_list_snippet_video;
                    } else if (part.isAudio()) {
                        stringId = R.string.conversation_list_snippet_audio_clip;
                    } else if (part.isVCard()) {
                        stringId = R.string.conversation_list_snippet_vcard;
                    } else {
                        stringId = 0;
                    }
                    if (stringId > 0) {
                        description.append(separator);
                        description.append(mContext.getString(stringId));
                    }
                }
            }
            remoteViews.setContentDescription(message.getIsIncoming() ?
                    R.id.widget_message_item_incoming :
                        R.id.widget_message_item_outgoing, description);
        }

        private Bitmap getAttachmentBitmap(final MessagePartData part) {
            UriImageRequestDescriptor descriptor;
            if (part.isImage()) {
                descriptor = new MessagePartImageRequestDescriptor(part,
                        IMAGE_ATTACHMENT_SIZE, // desiredWidth
                        IMAGE_ATTACHMENT_SIZE,  // desiredHeight
                        true // isStatic
                        );
            } else if (part.isVideo()) {
                descriptor = new MessagePartVideoThumbnailRequestDescriptor(part);
            } else {
                return null;
            }

            final MediaRequest<ImageResource> imageRequest =
                    descriptor.buildSyncMediaRequest(mContext);
            final ImageResource imageResource =
                    MediaResourceManager.get().requestMediaResourceSync(imageRequest);
            if (imageResource != null && imageResource.getBitmap() != null) {
                setImageResource(imageResource);
                return Bitmap.createBitmap(imageResource.getBitmap());
            } else {
                releaseImageResource();
                return null;
            }
        }

        /**
         * @return the "View more messages" view. When the user taps this item, they're
         * taken to the conversation in Bugle.
         */
        @Override
        protected RemoteViews getViewMoreItemsView() {
            if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
                LogUtil.v(TAG, "getViewMoreConversationsView");
            }
            final RemoteViews view = new RemoteViews(mContext.getPackageName(),
                    R.layout.widget_loading);
            view.setTextViewText(
                    R.id.loading_text, mContext.getText(R.string.view_more_messages));

            // Tapping this "More messages" item should take us to the conversation.
            final Intent intent = UIIntents.get().getIntentForConversationActivity(mContext,
                    mConversationId, null /* draft */);
            view.setOnClickFillInIntent(R.id.widget_loading, intent);
            return view;
        }

        @Override
        public RemoteViews getLoadingView() {
            final RemoteViews view = new RemoteViews(mContext.getPackageName(),
                    R.layout.widget_loading);
            view.setTextViewText(
                    R.id.loading_text, mContext.getText(R.string.loading_messages));
            return view;
        }

        @Override
        public int getViewTypeCount() {
            return 3;   // Number of different list items that can be returned -
                        // 1- incoming list item
                        // 2- outgoing list item
                        // 3- more items list item
        }

        @Override
        protected int getMainLayoutId() {
            return R.layout.widget_conversation;
        }

        private void setImageResource(final ImageResource resource) {
            if (mImageResource != resource) {
                // Clear out any information for what is currently used
                releaseImageResource();
                mImageResource = resource;
            }
        }

        private void releaseImageResource() {
            if (mImageResource != null) {
                mImageResource.release();
            }
            mImageResource = null;
        }
    }

}
