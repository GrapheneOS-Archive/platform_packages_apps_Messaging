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
package com.android.messaging.ui.conversationlist;

import android.content.Context;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLayoutChangeListener;
import android.view.View.OnLongClickListener;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.messaging.Factory;
import com.android.messaging.R;
import com.android.messaging.annotation.VisibleForAnimation;
import com.android.messaging.datamodel.MessagingContentProvider;
import com.android.messaging.datamodel.action.UpdateConversationArchiveStatusAction;
import com.android.messaging.datamodel.data.ConversationListItemData;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.datamodel.media.UriImageRequestDescriptor;
import com.android.messaging.sms.MmsUtils;
import com.android.messaging.ui.AsyncImageView;
import com.android.messaging.ui.AudioAttachmentView;
import com.android.messaging.ui.ContactIconView;
import com.android.messaging.ui.SnackBar;
import com.android.messaging.ui.SnackBarInteraction;
import com.android.messaging.util.Assert;
import com.android.messaging.util.ContentType;
import com.android.messaging.util.ImageUtils;
import com.android.messaging.util.PhoneUtils;
import com.android.messaging.util.Typefaces;
import com.android.messaging.util.UiUtils;
import com.android.messaging.util.UriUtil;

import java.util.List;

import androidx.core.text.BidiFormatter;
import androidx.core.text.TextDirectionHeuristicsCompat;

/**
 * The view for a single entry in a conversation list.
 */
public class ConversationListItemView extends FrameLayout implements OnClickListener,
        OnLongClickListener, OnLayoutChangeListener {
    static final int UNREAD_SNIPPET_LINE_COUNT = 3;
    static final int NO_UNREAD_SNIPPET_LINE_COUNT = 1;
    private int mListItemReadColor;
    private int mListItemUnreadColor;
    private Typeface mListItemReadTypeface;
    private Typeface mListItemUnreadTypeface;
    private static String sPlusOneString;
    private static String sPlusNString;

    public interface HostInterface {
        boolean isConversationSelected(final String conversationId);
        void onConversationClicked(final ConversationListItemData conversationListItemData,
                boolean isLongClick, final ConversationListItemView conversationView);
        boolean isSwipeAnimatable();
        List<SnackBarInteraction> getSnackBarInteractions();
        void startFullScreenPhotoViewer(final Uri initialPhoto, final Rect initialPhotoBounds,
                final Uri photosUri);
        void startFullScreenVideoViewer(final Uri videoUri);
        boolean isSelectionMode();
    }

    private final OnClickListener fullScreenPreviewClickListener = new OnClickListener() {
        @Override
        public void onClick(final View v) {
            final String previewType = mData.getShowDraft() ?
                    mData.getDraftPreviewContentType() : mData.getPreviewContentType();
            Assert.isTrue(ContentType.isImageType(previewType) ||
                    ContentType.isVideoType(previewType));

            final Uri previewUri = mData.getShowDraft() ?
                    mData.getDraftPreviewUri() : mData.getPreviewUri();
            if (ContentType.isImageType(previewType)) {
                final Uri imagesUri = mData.getShowDraft() ?
                        MessagingContentProvider.buildDraftImagesUri(mData.getConversationId()) :
                        MessagingContentProvider
                                .buildConversationImagesUri(mData.getConversationId());
                final Rect previewImageBounds = UiUtils.getMeasuredBoundsOnScreen(v);
                mHostInterface.startFullScreenPhotoViewer(
                        previewUri, previewImageBounds, imagesUri);
            } else {
                mHostInterface.startFullScreenVideoViewer(previewUri);
            }
        }
    };

    private final ConversationListItemData mData;

    private int mAnimatingCount;
    private ViewGroup mSwipeableContainer;
    private ViewGroup mCrossSwipeBackground;
    private ViewGroup mSwipeableContent;
    private TextView mConversationNameView;
    private ImageView mWorkProfileIconView;
    private TextView mSnippetTextView;
    private TextView mSubjectTextView;
    private TextView mTimestampTextView;
    private ContactIconView mContactIconView;
    private ImageView mContactCheckmarkView;
    private ImageView mNotificationBellView;
    private ImageView mFailedStatusIconView;
    private ImageView mCrossSwipeArchiveLeftImageView;
    private ImageView mCrossSwipeArchiveRightImageView;
    private AsyncImageView mImagePreviewView;
    private AudioAttachmentView mAudioAttachmentView;
    private HostInterface mHostInterface;

    public ConversationListItemView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        mData = new ConversationListItemData();
        final Resources res = context.getResources();
    }

    @Override
    protected void onFinishInflate() {
        mSwipeableContainer = (ViewGroup) findViewById(R.id.swipeableContainer);
        mCrossSwipeBackground = (ViewGroup) findViewById(R.id.crossSwipeBackground);
        mSwipeableContent = (ViewGroup) findViewById(R.id.swipeableContent);
        mConversationNameView = (TextView) findViewById(R.id.conversation_name);
        mSnippetTextView = (TextView) findViewById(R.id.conversation_snippet);
        mSubjectTextView = (TextView) findViewById(R.id.conversation_subject);
        mWorkProfileIconView = (ImageView) findViewById(R.id.work_profile_icon);
        mTimestampTextView = (TextView) findViewById(R.id.conversation_timestamp);
        mContactIconView = (ContactIconView) findViewById(R.id.conversation_icon);
        mContactCheckmarkView = (ImageView) findViewById(R.id.conversation_checkmark);
        mNotificationBellView = (ImageView) findViewById(R.id.conversation_notification_bell);
        mFailedStatusIconView = (ImageView) findViewById(R.id.conversation_failed_status_icon);
        mCrossSwipeArchiveLeftImageView = (ImageView) findViewById(R.id.crossSwipeArchiveIconLeft);
        mCrossSwipeArchiveRightImageView =
                (ImageView) findViewById(R.id.crossSwipeArchiveIconRight);
        mImagePreviewView = (AsyncImageView) findViewById(R.id.conversation_image_preview);
        mAudioAttachmentView = (AudioAttachmentView) findViewById(R.id.audio_attachment_view);
        mConversationNameView.addOnLayoutChangeListener(this);
        mSnippetTextView.addOnLayoutChangeListener(this);

        final Resources resources = getContext().getResources();
        mListItemReadColor = resources.getColor(R.color.conversation_list_item_read);
        mListItemUnreadColor = resources.getColor(R.color.conversation_list_item_unread);

        mListItemReadTypeface = Typefaces.getRobotoNormal();
        mListItemUnreadTypeface = Typefaces.getRobotoBold();

        setTransitionGroup(true);
    }

    @Override
    public void onLayoutChange(final View v, final int left, final int top, final int right,
            final int bottom, final int oldLeft, final int oldTop, final int oldRight,
            final int oldBottom) {
        if (v == mConversationNameView) {
            setConversationName();
        } else if (v == mSnippetTextView) {
            setSnippet();
        } else if (v == mSubjectTextView) {
            setSubject();
        }
    }

    private void setWorkProfileIcon() {
        mWorkProfileIconView.setVisibility(mData.isEnterprise() ? View.VISIBLE : View.GONE);
    }

    private void setConversationName() {
        if (mData.getIsRead() || mData.getShowDraft()) {
            mConversationNameView.setTextColor(mListItemReadColor);
            mConversationNameView.setTypeface(mListItemReadTypeface);
        } else {
            mConversationNameView.setTextColor(mListItemUnreadColor);
            mConversationNameView.setTypeface(mListItemUnreadTypeface);
        }

        final String conversationName = mData.getName();

        // For group conversations, ellipsize the group members that do not fit
        final CharSequence ellipsizedName = UiUtils.commaEllipsize(
                conversationName,
                mConversationNameView.getPaint(),
                mConversationNameView.getMeasuredWidth(),
                getPlusOneString(),
                getPlusNString());
        // RTL : To format conversation name if it happens to be phone number.
        final BidiFormatter bidiFormatter = BidiFormatter.getInstance();
        final String bidiFormattedName = bidiFormatter.unicodeWrap(
                ellipsizedName.toString(),
                TextDirectionHeuristicsCompat.LTR);

        mConversationNameView.setText(bidiFormattedName);
    }

    private static String getPlusOneString() {
        if (sPlusOneString == null) {
            sPlusOneString =  Factory.get().getApplicationContext().getResources()
                    .getString(R.string.plus_one);
        }
        return sPlusOneString;
    }

    private static String getPlusNString() {
        if (sPlusNString == null) {
            sPlusNString =  Factory.get().getApplicationContext().getResources()
                    .getString(R.string.plus_n);
        }
        return sPlusNString;
    }

    private void setSubject() {
        final String subjectText = mData.getShowDraft() ?
                mData.getDraftSubject() :
                    MmsUtils.cleanseMmsSubject(getContext().getResources(), mData.getSubject());
        if (!TextUtils.isEmpty(subjectText)) {
            final String subjectPrepend = getResources().getString(R.string.subject_label);
            mSubjectTextView.setText(TextUtils.concat(subjectPrepend, subjectText));
            mSubjectTextView.setVisibility(VISIBLE);
        } else {
            mSubjectTextView.setVisibility(GONE);
        }
    }

    private void setSnippet() {
        mSnippetTextView.setText(getSnippetText());
    }

    // Resource Ids of content descriptions prefixes for different message status.
    private static final int [][][] sPrimaryContentDescriptions = {
        // 1:1 conversation
        {
            // Incoming message
            {
                R.string.one_on_one_incoming_failed_message_prefix,
                R.string.one_on_one_incoming_successful_message_prefix
            },
            // Outgoing message
            {
                R.string.one_on_one_outgoing_failed_message_prefix,
                R.string.one_on_one_outgoing_successful_message_prefix,
                R.string.one_on_one_outgoing_draft_message_prefix,
                R.string.one_on_one_outgoing_sending_message_prefix,
            }
        },

        // Group conversation
        {
            // Incoming message
            {
                R.string.group_incoming_failed_message_prefix,
                R.string.group_incoming_successful_message_prefix,
            },
            // Outgoing message
            {
                R.string.group_outgoing_failed_message_prefix,
                R.string.group_outgoing_successful_message_prefix,
                R.string.group_outgoing_draft_message_prefix,
                R.string.group_outgoing_sending_message_prefix,
            }
        }
    };

    // Resource Id of the secondary part of the content description for an edge case of a message
    // which is in both draft status and failed status.
    private static final int sSecondaryContentDescription =
                                        R.string.failed_message_content_description;

    // 1:1 versus group
    private static final int CONV_TYPE_ONE_ON_ONE_INDEX = 0;
    private static final int CONV_TYPE_ONE_GROUP_INDEX = 1;
    // Direction
    private static final int DIRECTION_INCOMING_INDEX = 0;
    private static final int DIRECTION_OUTGOING_INDEX = 1;
    // Message status
    private static final int MESSAGE_STATUS_FAILED_INDEX = 0;
    private static final int MESSAGE_STATUS_SUCCESSFUL_INDEX = 1;
    private static final int MESSAGE_STATUS_DRAFT_INDEX = 2;
    private static final int MESSAGE_STATUS_SENDING_INDEX = 3;

    private static final int WIDTH_FOR_ACCESSIBLE_CONVERSATION_NAME = 600;

    public static String buildContentDescription(final Resources resources,
            final ConversationListItemData data, final TextPaint conversationNameViewPaint) {
        int messageStatusIndex;
        boolean outgoingSnippet = data.getIsMessageTypeOutgoing() || data.getShowDraft();
        if (outgoingSnippet) {
            if (data.getShowDraft()) {
                messageStatusIndex = MESSAGE_STATUS_DRAFT_INDEX;
            } else if (data.getIsSendRequested()) {
                messageStatusIndex = MESSAGE_STATUS_SENDING_INDEX;
            } else {
                messageStatusIndex = data.getIsFailedStatus() ? MESSAGE_STATUS_FAILED_INDEX
                        : MESSAGE_STATUS_SUCCESSFUL_INDEX;
            }
        } else {
            messageStatusIndex = data.getIsFailedStatus() ? MESSAGE_STATUS_FAILED_INDEX
                    : MESSAGE_STATUS_SUCCESSFUL_INDEX;
        }

        int resId = sPrimaryContentDescriptions
                [data.getIsGroup() ? CONV_TYPE_ONE_GROUP_INDEX : CONV_TYPE_ONE_ON_ONE_INDEX]
                [outgoingSnippet ? DIRECTION_OUTGOING_INDEX : DIRECTION_INCOMING_INDEX]
                [messageStatusIndex];

        final String snippetText = data.getShowDraft() ?
                data.getDraftSnippetText() : data.getSnippetText();

        final String conversationName = data.getName();
        String senderOrConvName = outgoingSnippet ? conversationName : data.getSnippetSenderName();

        String primaryContentDescription = resources.getString(resId, senderOrConvName,
                snippetText == null ? "" : snippetText,
                data.getFormattedTimestamp(),
                // This is used only for incoming group messages
                conversationName);
        String contentDescription = primaryContentDescription;

        // An edge case : for an outgoing message, it might be in both draft status and
        // failed status.
        if (outgoingSnippet && data.getShowDraft() && data.getIsFailedStatus()) {
            StringBuilder contentDescriptionBuilder = new StringBuilder();
            contentDescriptionBuilder.append(primaryContentDescription);

            String secondaryContentDescription =
                    resources.getString(sSecondaryContentDescription);
            contentDescriptionBuilder.append(" ");
            contentDescriptionBuilder.append(secondaryContentDescription);
            contentDescription = contentDescriptionBuilder.toString();
        }
        return contentDescription;
    }

    /**
     * Fills in the data associated with this view.
     *
     * @param cursor The cursor from a ConversationList that this view is in, pointing to its
     * entry.
     */
    public void bind(final Cursor cursor, final HostInterface hostInterface) {
        // Update our UI model
        mHostInterface = hostInterface;
        mData.bind(cursor);

        resetAnimatingState();

        mSwipeableContainer.setOnClickListener(this);
        mSwipeableContainer.setOnLongClickListener(this);

        final Resources resources = getContext().getResources();

        int color;
        final int maxLines;
        final Typeface typeface;
        final int typefaceStyle = mData.getShowDraft() ? Typeface.ITALIC : Typeface.NORMAL;
        final String snippetText = getSnippetText();

        if (mData.getIsRead() || mData.getShowDraft()) {
            maxLines = TextUtils.isEmpty(snippetText) ? 0 : NO_UNREAD_SNIPPET_LINE_COUNT;
            color = mListItemReadColor;
            typeface = mListItemReadTypeface;
        } else {
            maxLines = TextUtils.isEmpty(snippetText) ? 0 : UNREAD_SNIPPET_LINE_COUNT;
            color = mListItemUnreadColor;
            typeface = mListItemUnreadTypeface;
        }

        mSnippetTextView.setMaxLines(maxLines);
        mSnippetTextView.setTextColor(color);
        mSnippetTextView.setTypeface(typeface, typefaceStyle);
        mSubjectTextView.setTextColor(color);
        mSubjectTextView.setTypeface(typeface, typefaceStyle);

        setSnippet();
        setConversationName();
        setSubject();
        setWorkProfileIcon();
        setContentDescription(buildContentDescription(resources, mData,
                mConversationNameView.getPaint()));

        final boolean isDefaultSmsApp = PhoneUtils.getDefault().isDefaultSmsApp();
        // don't show the error state unless we're the default sms app
        if (mData.getIsFailedStatus() && isDefaultSmsApp) {
            mTimestampTextView.setTextColor(resources.getColor(R.color.conversation_list_error));
            mTimestampTextView.setTypeface(mListItemReadTypeface, typefaceStyle);
            int failureMessageId = R.string.message_status_download_failed;
            if (mData.getIsMessageTypeOutgoing()) {
                failureMessageId = MmsUtils.mapRawStatusToErrorResourceId(mData.getMessageStatus(),
                        mData.getMessageRawTelephonyStatus());
            }
            mTimestampTextView.setText(resources.getString(failureMessageId));
        } else if (mData.getShowDraft()
                || mData.getMessageStatus() == MessageData.BUGLE_STATUS_OUTGOING_DRAFT
                // also check for unknown status which we get because sometimes the conversation
                // row is left with a latest_message_id of a no longer existing message and
                // therefore the join values come back as null (or in this case zero).
                || mData.getMessageStatus() == MessageData.BUGLE_STATUS_UNKNOWN) {
            mTimestampTextView.setTextColor(mListItemReadColor);
            mTimestampTextView.setTypeface(mListItemReadTypeface, typefaceStyle);
            mTimestampTextView.setText(resources.getString(
                    R.string.conversation_list_item_view_draft_message));
         } else {
            mTimestampTextView.setTextColor(mListItemReadColor);
            mTimestampTextView.setTypeface(mListItemReadTypeface, typefaceStyle);
            final String formattedTimestamp = mData.getFormattedTimestamp();
            if (mData.getIsSendRequested()) {
                mTimestampTextView.setText(R.string.message_status_sending);
            } else {
                mTimestampTextView.setText(formattedTimestamp);
            }
        }

        final boolean isSelected = mHostInterface.isConversationSelected(mData.getConversationId());
        setSelected(isSelected);
        Uri iconUri = null;
        int contactIconVisibility = GONE;
        int checkmarkVisiblity = GONE;
        int failStatusVisiblity = GONE;
        if (isSelected) {
            checkmarkVisiblity = VISIBLE;
        } else {
            contactIconVisibility = VISIBLE;
            // Only show the fail icon if it is not a group conversation.
            // And also require that we be the default sms app.
            if (mData.getIsFailedStatus() && !mData.getIsGroup() && isDefaultSmsApp) {
                failStatusVisiblity = VISIBLE;
            }
        }
        if (mData.getIcon() != null) {
            iconUri = Uri.parse(mData.getIcon());
        }
        mContactIconView.setImageResourceUri(iconUri, mData.getParticipantContactId(),
                mData.getParticipantLookupKey(), mData.getOtherParticipantNormalizedDestination());
        mContactIconView.setVisibility(contactIconVisibility);
        mContactIconView.setOnLongClickListener(this);
        mContactIconView.setClickable(!mHostInterface.isSelectionMode());
        mContactIconView.setLongClickable(!mHostInterface.isSelectionMode());

        mContactCheckmarkView.setVisibility(checkmarkVisiblity);
        mFailedStatusIconView.setVisibility(failStatusVisiblity);

        final Uri previewUri = mData.getShowDraft() ?
                mData.getDraftPreviewUri() : mData.getPreviewUri();
        final String previewContentType = mData.getShowDraft() ?
                mData.getDraftPreviewContentType() : mData.getPreviewContentType();
        OnClickListener previewClickListener = null;
        Uri previewImageUri = null;
        int previewImageVisibility = GONE;
        int audioPreviewVisiblity = GONE;
        if (previewUri != null && !TextUtils.isEmpty(previewContentType)) {
            if (ContentType.isAudioType(previewContentType)) {
                boolean incoming = !(mData.getShowDraft() || mData.getIsMessageTypeOutgoing());
                mAudioAttachmentView.bind(previewUri, incoming, false);
                audioPreviewVisiblity = VISIBLE;
            } else if (ContentType.isVideoType(previewContentType)) {
                previewImageUri = UriUtil.getUriForResourceId(
                        getContext(), R.drawable.ic_preview_play);
                previewClickListener = fullScreenPreviewClickListener;
                previewImageVisibility = VISIBLE;
            } else if (ContentType.isImageType(previewContentType)) {
                previewImageUri = previewUri;
                previewClickListener = fullScreenPreviewClickListener;
                previewImageVisibility = VISIBLE;
            }
        }

        final int imageSize = resources.getDimensionPixelSize(
                R.dimen.conversation_list_image_preview_size);
        mImagePreviewView.setImageResourceId(
                new UriImageRequestDescriptor(previewImageUri, imageSize, imageSize,
                        true /* allowCompression */, false /* isStatic */, false /*cropToCircle*/,
                        ImageUtils.DEFAULT_CIRCLE_BACKGROUND_COLOR /* circleBackgroundColor */,
                        ImageUtils.DEFAULT_CIRCLE_STROKE_COLOR /* circleStrokeColor */));
        mImagePreviewView.setOnLongClickListener(this);
        mImagePreviewView.setVisibility(previewImageVisibility);
        mImagePreviewView.setOnClickListener(previewClickListener);
        mAudioAttachmentView.setOnLongClickListener(this);
        mAudioAttachmentView.setVisibility(audioPreviewVisiblity);

        final int notificationBellVisiblity = mData.getNotificationEnabled() ? GONE : VISIBLE;
        mNotificationBellView.setVisibility(notificationBellVisiblity);
    }

    public boolean isSwipeAnimatable() {
        return mHostInterface.isSwipeAnimatable();
    }

    @VisibleForAnimation
    public float getSwipeTranslationX() {
        return mSwipeableContainer.getTranslationX();
    }

    @VisibleForAnimation
    public void setSwipeTranslationX(final float translationX) {
        mSwipeableContainer.setTranslationX(translationX);
        if (translationX == 0) {
            mCrossSwipeBackground.setVisibility(View.GONE);
            mCrossSwipeArchiveLeftImageView.setVisibility(GONE);
            mCrossSwipeArchiveRightImageView.setVisibility(GONE);

            mSwipeableContainer.setBackgroundColor(Color.TRANSPARENT);
        } else {
            mCrossSwipeBackground.setVisibility(View.VISIBLE);
            if (translationX > 0) {
                mCrossSwipeArchiveLeftImageView.setVisibility(VISIBLE);
                mCrossSwipeArchiveRightImageView.setVisibility(GONE);
            } else {
                mCrossSwipeArchiveLeftImageView.setVisibility(GONE);
                mCrossSwipeArchiveRightImageView.setVisibility(VISIBLE);
            }
            mSwipeableContainer.setBackgroundResource(R.drawable.swipe_shadow_drag);
        }
    }

    public void onSwipeComplete() {
        final String conversationId = mData.getConversationId();
        UpdateConversationArchiveStatusAction.archiveConversation(conversationId);

        final Runnable undoRunnable = new Runnable() {
            @Override
            public void run() {
                UpdateConversationArchiveStatusAction.unarchiveConversation(conversationId);
            }
        };
        final String message = getResources().getString(R.string.archived_toast_message, 1);
        UiUtils.showSnackBar(getContext(), getRootView(), message, undoRunnable,
                SnackBar.Action.SNACK_BAR_UNDO,
                mHostInterface.getSnackBarInteractions());
    }

    private void setShortAndLongClickable(final boolean clickable) {
        setClickable(clickable);
        setLongClickable(clickable);
    }

    private void resetAnimatingState() {
        mAnimatingCount = 0;
        setShortAndLongClickable(true);
        setSwipeTranslationX(0);
    }

    /**
     * Notifies this view that it is undergoing animation. This view should disable its click
     * targets.
     *
     * The animating counter is used to reset the swipe controller when the counter becomes 0. A
     * positive counter also makes the view not clickable.
     */
    public final void setAnimating(final boolean animating) {
        final int oldAnimatingCount = mAnimatingCount;
        if (animating) {
            mAnimatingCount++;
        } else {
            mAnimatingCount--;
            if (mAnimatingCount < 0) {
                mAnimatingCount = 0;
            }
        }

        if (mAnimatingCount == 0) {
            // New count is 0. All animations ended.
            setShortAndLongClickable(true);
        } else if (oldAnimatingCount == 0) {
            // New count is > 0. Waiting for some animations to end.
            setShortAndLongClickable(false);
        }
    }

    public boolean isAnimating() {
        return mAnimatingCount > 0;
    }

    /**
     * {@inheritDoc} from OnClickListener
     */
    @Override
    public void onClick(final View v) {
        processClick(v, false);
    }

    /**
     * {@inheritDoc} from OnLongClickListener
     */
    @Override
    public boolean onLongClick(final View v) {
        return processClick(v, true);
    }

    private boolean processClick(final View v, final boolean isLongClick) {
        Assert.isTrue(v == mSwipeableContainer || v == mContactIconView || v == mImagePreviewView);
        Assert.notNull(mData.getName());

        if (mHostInterface != null) {
            mHostInterface.onConversationClicked(mData, isLongClick, this);
            return true;
        }
        return false;
    }

    public View getSwipeableContent() {
        return mSwipeableContent;
    }

    public View getContactIconView() {
        return mContactIconView;
    }

    private String getSnippetText() {
        String snippetText = mData.getShowDraft() ?
                mData.getDraftSnippetText() : mData.getSnippetText();
        final String previewContentType = mData.getShowDraft() ?
                mData.getDraftPreviewContentType() : mData.getPreviewContentType();
        if (TextUtils.isEmpty(snippetText)) {
            Resources resources = getResources();
            // Use the attachment type as a snippet so the preview doesn't look odd
            if (ContentType.isAudioType(previewContentType)) {
                snippetText = resources.getString(R.string.conversation_list_snippet_audio_clip);
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
}
