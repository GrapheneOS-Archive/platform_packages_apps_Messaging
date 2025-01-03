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
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import com.android.messaging.R;
import com.android.messaging.datamodel.MessagingContentProvider;
import com.android.messaging.datamodel.data.ConversationListData;
import com.android.messaging.datamodel.data.ConversationListItemData;
import com.android.messaging.sms.MmsUtils;
import com.android.messaging.ui.UIIntents;
import com.android.messaging.ui.conversationlist.ConversationListItemView;
import com.android.messaging.util.ContentType;
import com.android.messaging.util.Dates;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.PhoneUtils;

public class WidgetConversationListService extends RemoteViewsService {
    private static final String TAG = LogUtil.BUGLE_WIDGET_TAG;

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
            LogUtil.v(TAG, "onGetViewFactory intent: " + intent);
        }
        return new WidgetConversationListFactory(getApplicationContext(), intent);
    }

    /**
     * Remote Views Factory for Bugle Widget.
     */
    private static class WidgetConversationListFactory extends BaseWidgetFactory {

        public WidgetConversationListFactory(Context context, Intent intent) {
            super(context, intent);
        }

        @Override
        protected Cursor doQuery() {
            return  mContext.getContentResolver().query(MessagingContentProvider.CONVERSATIONS_URI,
                    ConversationListItemData.PROJECTION,
                    ConversationListData.WHERE_NOT_ARCHIVED,
                    null,       // selection args
                    ConversationListData.SORT_ORDER);
        }

        /**
         * @return the {@link RemoteViews} for a specific position in the list.
         */
        @Override
        public RemoteViews getViewAt(int position) {
            if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
                LogUtil.v(TAG, "getViewAt position: " + position);
            }
            synchronized (sWidgetLock) {
                // "View more conversations" view.
                if (mCursor == null
                        || (mShouldShowViewMore && position >= getItemCount())) {
                    return getViewMoreItemsView();
                }

                if (!mCursor.moveToPosition(position)) {
                    // If we ever fail to move to a position, return the "View More conversations"
                    // view.
                    LogUtil.w(TAG, "Failed to move to position: " + position);
                    return getViewMoreItemsView();
                }

                final ConversationListItemData conv = new ConversationListItemData();
                conv.bind(mCursor);

                // Inflate and fill out the remote view
                final RemoteViews remoteViews = new RemoteViews(
                        mContext.getPackageName(), R.layout.widget_conversation_list_item);

                final boolean hasUnreadMessages = !conv.getIsRead();
                final Resources resources = mContext.getResources();
                final boolean isDefaultSmsApp = PhoneUtils.getDefault().isDefaultSmsApp();

                final String timeStamp = conv.getIsSendRequested() ?
                        resources.getString(R.string.message_status_sending) :
                            Dates.getWidgetTimeString(conv.getTimestamp(), true /*abbreviated*/)
                                .toString();
                // Date/Timestamp or Sending or Error state -- all shown in the date item
                remoteViews.setTextViewText(R.id.date,
                        boldifyIfUnread(timeStamp, hasUnreadMessages));

                // From
                remoteViews.setTextViewText(R.id.from,
                        boldifyIfUnread(conv.getName(), hasUnreadMessages));

                // Notifications turned off mini-bell icon
                remoteViews.setViewVisibility(R.id.conversation_notification_bell,
                        conv.getNotificationEnabled() ? View.GONE : View.VISIBLE);

                // On click intent.
                final Intent intent = UIIntents.get().getIntentForConversationActivity(mContext,
                        conv.getConversationId(), null /* draft */);

                remoteViews.setOnClickFillInIntent(R.id.widget_conversation_list_item, intent);

                // Avatar
                final Bundle options = mAppWidgetManager.getAppWidgetOptions(mAppWidgetId);
                if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
                    LogUtil.v(TAG, "getViewAt BugleWidgetProvider.WIDGET_SIZE_KEY: " +
                            options.getInt(BugleWidgetProvider.WIDGET_SIZE_KEY));
                }

                boolean includeAvatar = options.getInt(BugleWidgetProvider.WIDGET_SIZE_KEY) ==
                        BugleWidgetProvider.SIZE_LARGE;

                // Show the avatar when grande size, otherwise hide it.
                remoteViews.setViewVisibility(R.id.avatarView, includeAvatar ?
                        View.VISIBLE : View.GONE);

                Uri iconUri = null;
                if (conv.getIcon() != null) {
                    iconUri = Uri.parse(conv.getIcon());
                }
                remoteViews.setImageViewBitmap(R.id.avatarView, includeAvatar ?
                        getAvatarBitmap(iconUri) : null);

                // Error
                // Only show the fail icon if it is not a group conversation.
                // And also require that we be the default sms app.
                final boolean showError =
                        conv.getIsFailedStatus() && !conv.getIsGroup() && isDefaultSmsApp;
                final boolean showDraft = conv.getShowDraft() && isDefaultSmsApp;
                remoteViews.setViewVisibility(R.id.conversation_failed_status_icon,
                        showError && includeAvatar ?
                        View.VISIBLE : View.GONE);

                if (showError || showDraft) {
                    remoteViews.setViewVisibility(R.id.snippet, View.GONE);
                    remoteViews.setViewVisibility(R.id.errorBlock, View.VISIBLE);
                    remoteViews.setTextViewText(R.id.errorSnippet, getSnippetText(conv));

                    if (showDraft) {
                        // Show italicized "Draft" on third line
                        final String text = resources.getString(
                                R.string.conversation_list_item_view_draft_message);
                        SpannableStringBuilder builder = new SpannableStringBuilder(text);
                        builder.setSpan(new StyleSpan(Typeface.ITALIC), 0, text.length(),
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        builder.setSpan(new ForegroundColorSpan(
                                    resources.getColor(R.color.widget_text_color)),
                                0, text.length(),
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        remoteViews.setTextViewText(R.id.errorText, builder);
                    } else {
                        // Show error message on third line
                       int failureMessageId = R.string.message_status_download_failed;
                        if (conv.getIsMessageTypeOutgoing()) {
                            failureMessageId = MmsUtils.mapRawStatusToErrorResourceId(
                                    conv.getMessageStatus(),
                                    conv.getMessageRawTelephonyStatus());
                        }
                        remoteViews.setTextViewText(R.id.errorText,
                                resources.getString(failureMessageId));
                    }
                } else {
                    remoteViews.setViewVisibility(R.id.errorBlock, View.GONE);
                    remoteViews.setViewVisibility(R.id.snippet, View.VISIBLE);
                    remoteViews.setTextViewText(R.id.snippet,
                            boldifyIfUnread(getSnippetText(conv), hasUnreadMessages));
                }

                // Set the accessibility TalkBack text
                remoteViews.setContentDescription(R.id.widget_conversation_list_item,
                        ConversationListItemView.buildContentDescription(mContext.getResources(),
                                conv, new TextPaint()));

                return remoteViews;
            }
        }

        private String getSnippetText(final ConversationListItemData conv) {
            String snippetText = conv.getShowDraft() ?
                    conv.getDraftSnippetText() : conv.getSnippetText();
            final String previewContentType = conv.getShowDraft() ?
                    conv.getDraftPreviewContentType() : conv.getPreviewContentType();
            if (TextUtils.isEmpty(snippetText)) {
                Resources resources = mContext.getResources();
                // Use the attachment type as a snippet so the preview doesn't look odd
                if (ContentType.isAudioType(previewContentType)) {
                    snippetText = resources.getString(
                            R.string.conversation_list_snippet_audio_clip);
                } else if (ContentType.isImageType(previewContentType)) {
                    snippetText = resources.getString(R.string.conversation_list_snippet_picture);
                } else if (ContentType.isVideoType(previewContentType)) {
                    snippetText = resources.getString(R.string.conversation_list_snippet_video);
                } else if (ContentType.isVCardType(previewContentType)) {
                    snippetText = resources.getString(R.string.conversation_list_snippet_vcard);
                }
            }
            return snippetText;
        }

        /**
         * @return the "View more conversations" view. When the user taps this item, they're
         * taken to the Bugle's conversation list.
         */
        @Override
        protected RemoteViews getViewMoreItemsView() {
            if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
                LogUtil.v(TAG, "getViewMoreItemsView");
            }
            final RemoteViews view = new RemoteViews(mContext.getPackageName(),
                    R.layout.widget_loading);
            view.setTextViewText(
                    R.id.loading_text, mContext.getText(R.string.view_more_conversations));

            // Tapping this "More conversations" item should take us to the ConversationList.
            // However, the list view is primed with an intent to go to the Conversation activity.
            // Each normal conversation list item sets the fill-in intent with the
            // ConversationId for that particular conversation. In other words, the only place
            // we can go is the ConversationActivity. We add an extra here to tell the
            // ConversationActivity to really take us to the ConversationListActivity.
            final Intent intent = new Intent();
            intent.putExtra(UIIntents.UI_INTENT_EXTRA_GOTO_CONVERSATION_LIST, true);
            view.setOnClickFillInIntent(R.id.widget_loading, intent);
            return view;
        }

        @Override
        public RemoteViews getLoadingView() {
            RemoteViews view = new RemoteViews(mContext.getPackageName(), R.layout.widget_loading);
            view.setTextViewText(
                    R.id.loading_text, mContext.getText(R.string.loading_conversations));
            return view;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        protected int getMainLayoutId() {
            return R.layout.widget_conversation_list;
        }
    }

}
