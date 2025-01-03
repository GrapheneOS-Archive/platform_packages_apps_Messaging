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
import android.content.res.Resources;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.Html;
import android.text.InputFilter;
import android.text.InputFilter.LengthFilter;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.util.AttributeSet;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.messaging.Factory;
import com.android.messaging.R;
import com.android.messaging.datamodel.binding.Binding;
import com.android.messaging.datamodel.binding.BindingBase;
import com.android.messaging.datamodel.binding.ImmutableBindingRef;
import com.android.messaging.datamodel.data.ConversationData;
import com.android.messaging.datamodel.data.ConversationData.ConversationDataListener;
import com.android.messaging.datamodel.data.ConversationData.SimpleConversationDataListener;
import com.android.messaging.datamodel.data.DraftMessageData;
import com.android.messaging.datamodel.data.DraftMessageData.CheckDraftForSendTask;
import com.android.messaging.datamodel.data.DraftMessageData.CheckDraftTaskCallback;
import com.android.messaging.datamodel.data.DraftMessageData.DraftMessageDataListener;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.datamodel.data.MessagePartData;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.datamodel.data.PendingAttachmentData;
import com.android.messaging.datamodel.data.SubscriptionListData.SubscriptionListEntry;
import com.android.messaging.sms.MmsConfig;
import com.android.messaging.ui.AttachmentPreview;
import com.android.messaging.ui.BugleActionBarActivity;
import com.android.messaging.ui.PlainTextEditText;
import com.android.messaging.ui.conversation.ConversationInputManager.ConversationInputSink;
import com.android.messaging.util.AccessibilityUtil;
import com.android.messaging.util.Assert;
import com.android.messaging.util.AvatarUriUtil;
import com.android.messaging.util.BuglePrefs;
import com.android.messaging.util.ContentType;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.MediaUtil;
import com.android.messaging.util.SafeAsyncTask;
import com.android.messaging.util.UiUtils;
import com.android.messaging.util.UriUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import androidx.appcompat.app.ActionBar;

/**
 * This view contains the UI required to generate and send messages.
 */
public class ComposeMessageView extends LinearLayout
        implements TextView.OnEditorActionListener, DraftMessageDataListener, TextWatcher,
        ConversationInputSink {

    public interface IComposeMessageViewHost extends
            DraftMessageData.DraftMessageSubscriptionDataProvider {
        void sendMessage(MessageData message);
        void onComposeEditTextFocused();
        void onAttachmentsCleared();
        void onAttachmentsChanged(final boolean haveAttachments);
        void displayPhoto(Uri photoUri, Rect imageBounds, boolean isDraft);
        void promptForSelfPhoneNumber();
        boolean isReadyForAction();
        void warnOfMissingActionConditions(final boolean sending,
                final Runnable commandToRunAfterActionConditionResolved);
        void warnOfExceedingMessageLimit(final boolean showAttachmentChooser,
                boolean tooManyVideos);
        void notifyOfAttachmentLoadFailed();
        void showAttachmentChooser();
        boolean shouldShowSubjectEditor();
        boolean shouldHideAttachmentsWhenSimSelectorShown();
        Uri getSelfSendButtonIconUri();
        int overrideCounterColor();
        int getAttachmentsClearedFlags();
    }

    public static final int CODEPOINTS_REMAINING_BEFORE_COUNTER_SHOWN = 10;

    // There is no draft and there is no need for the SIM selector
    private static final int SEND_WIDGET_MODE_SELF_AVATAR = 1;
    // There is no draft but we need to show the SIM selector
    private static final int SEND_WIDGET_MODE_SIM_SELECTOR = 2;
    // There is a draft
    private static final int SEND_WIDGET_MODE_SEND_BUTTON = 3;

    private PlainTextEditText mComposeEditText;
    private PlainTextEditText mComposeSubjectText;
    private TextView mMessageBodySize;
    private TextView mMmsIndicator;
    private SimIconView mSelfSendIcon;
    private ImageButton mSendButton;
    private View mSubjectView;
    private ImageButton mDeleteSubjectButton;
    private AttachmentPreview mAttachmentPreview;
    private ImageButton mAttachMediaButton;

    private final Binding<DraftMessageData> mBinding;
    private IComposeMessageViewHost mHost;
    private final Context mOriginalContext;
    private int mSendWidgetMode = SEND_WIDGET_MODE_SELF_AVATAR;

    // Shared data model object binding from the conversation.
    private ImmutableBindingRef<ConversationData> mConversationDataModel;

    // Centrally manages all the mutual exclusive UI components accepting user input, i.e.
    // media picker, IME keyboard and SIM selector.
    private ConversationInputManager mInputManager;

    private final ConversationDataListener mDataListener = new SimpleConversationDataListener() {
        @Override
        public void onConversationMetadataUpdated(ConversationData data) {
            mConversationDataModel.ensureBound(data);
            updateVisualsOnDraftChanged();
        }

        @Override
        public void onConversationParticipantDataLoaded(ConversationData data) {
            mConversationDataModel.ensureBound(data);
            updateVisualsOnDraftChanged();
        }

        @Override
        public void onSubscriptionListDataLoaded(ConversationData data) {
            mConversationDataModel.ensureBound(data);
            updateOnSelfSubscriptionChange();
            updateVisualsOnDraftChanged();
        }
    };

    public ComposeMessageView(final Context context, final AttributeSet attrs) {
        super(new ContextThemeWrapper(context, R.style.ColorAccentBlueOverrideStyle), attrs);
        mOriginalContext = context;
        mBinding = BindingBase.createBinding(this);
    }

    /**
     * Host calls this to bind view to DraftMessageData object
     */
    public void bind(final DraftMessageData data, final IComposeMessageViewHost host) {
        mHost = host;
        mBinding.bind(data);
        data.addListener(this);
        data.setSubscriptionDataProvider(host);

        final int counterColor = mHost.overrideCounterColor();
        if (counterColor != -1) {
            mMessageBodySize.setTextColor(counterColor);
        }
    }

    /**
     * Host calls this to unbind view
     */
    public void unbind() {
        mBinding.unbind();
        mHost = null;
        mInputManager.onDetach();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mComposeEditText = findViewById(R.id.compose_message_text);
        mComposeEditText.setOnEditorActionListener(this);
        mComposeEditText.addTextChangedListener(this);
        mComposeEditText.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override
            public void onFocusChange(final View v, final boolean hasFocus) {
                if (v == mComposeEditText && hasFocus) {
                    mHost.onComposeEditTextFocused();
                }
            }
        });
        mComposeEditText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View arg0) {
                if (mHost.shouldHideAttachmentsWhenSimSelectorShown()) {
                    hideSimSelector();
                }
            }
        });

        // onFinishInflate() is called before self is loaded from db. We set the default text
        // limit here, and apply the real limit later in updateOnSelfSubscriptionChange().
        mComposeEditText.setFilters(new InputFilter[] {
                new LengthFilter(MmsConfig.get(ParticipantData.DEFAULT_SELF_SUB_ID)
                        .getMaxTextLimit()) });

        mSelfSendIcon = (SimIconView) findViewById(R.id.self_send_icon);
        mSelfSendIcon.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean shown = mInputManager.toggleSimSelector(true /* animate */,
                        getSelfSubscriptionListEntry());
                hideAttachmentsWhenShowingSims(shown);
            }
        });
        mSelfSendIcon.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(final View v) {
                if (mHost.shouldShowSubjectEditor()) {
                    showSubjectEditor();
                } else {
                    boolean shown = mInputManager.toggleSimSelector(true /* animate */,
                            getSelfSubscriptionListEntry());
                    hideAttachmentsWhenShowingSims(shown);
                }
                return true;
            }
        });

        mComposeSubjectText = (PlainTextEditText) findViewById(
                R.id.compose_subject_text);
        // We need the listener to change the avatar to the send button when the user starts
        // typing a subject without a message.
        mComposeSubjectText.addTextChangedListener(this);
        // onFinishInflate() is called before self is loaded from db. We set the default text
        // limit here, and apply the real limit later in updateOnSelfSubscriptionChange().
        mComposeSubjectText.setFilters(new InputFilter[] {
                new LengthFilter(MmsConfig.get(ParticipantData.DEFAULT_SELF_SUB_ID)
                        .getMaxSubjectLength())});

        mDeleteSubjectButton = (ImageButton) findViewById(R.id.delete_subject_button);
        mDeleteSubjectButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(final View clickView) {
                hideSubjectEditor();
                mComposeSubjectText.setText(null);
                mBinding.getData().setMessageSubject(null);
            }
        });

        mSubjectView = findViewById(R.id.subject_view);

        mSendButton = (ImageButton) findViewById(R.id.send_message_button);
        mSendButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(final View clickView) {
                sendMessageInternal(true /* checkMessageSize */);
            }
        });
        mSendButton.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(final View arg0) {
                boolean shown = mInputManager.toggleSimSelector(true /* animate */,
                        getSelfSubscriptionListEntry());
                hideAttachmentsWhenShowingSims(shown);
                if (mHost.shouldShowSubjectEditor()) {
                    showSubjectEditor();
                }
                return true;
            }
        });
        mSendButton.setAccessibilityDelegate(new AccessibilityDelegate() {
            @Override
            public void onPopulateAccessibilityEvent(View host, AccessibilityEvent event) {
                super.onPopulateAccessibilityEvent(host, event);
                // When the send button is long clicked, we want TalkBack to announce the real
                // action (select SIM or edit subject), as opposed to "long press send button."
                if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
                    event.getText().clear();
                    event.getText().add(getResources()
                            .getText(shouldShowSimSelector(mConversationDataModel.getData()) ?
                            R.string.send_button_long_click_description_with_sim_selector :
                                R.string.send_button_long_click_description_no_sim_selector));
                    // Make this an announcement so TalkBack will read our custom message.
                    event.setEventType(AccessibilityEvent.TYPE_ANNOUNCEMENT);
                }
            }
        });

        mAttachMediaButton =
                (ImageButton) findViewById(R.id.attach_media_button);
        mAttachMediaButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View clickView) {
                // Showing the media picker is treated as starting to compose the message.
                mInputManager.showHideMediaPicker(true /* show */, true /* animate */);
            }
        });

        mAttachmentPreview = (AttachmentPreview) findViewById(R.id.attachment_draft_view);
        mAttachmentPreview.setComposeMessageView(this);

        mMessageBodySize = (TextView) findViewById(R.id.message_body_size);
        mMmsIndicator = (TextView) findViewById(R.id.mms_indicator);
    }

    private void hideAttachmentsWhenShowingSims(final boolean simPickerVisible) {
        if (!mHost.shouldHideAttachmentsWhenSimSelectorShown()) {
            return;
        }
        final boolean haveAttachments = mBinding.getData().hasAttachments();
        if (simPickerVisible && haveAttachments) {
            mHost.onAttachmentsChanged(false);
            mAttachmentPreview.hideAttachmentPreview();
        } else {
            mHost.onAttachmentsChanged(haveAttachments);
            mAttachmentPreview.onAttachmentsChanged(mBinding.getData());
        }
    }

    public void setInputManager(final ConversationInputManager inputManager) {
        mInputManager = inputManager;
    }

    public void setConversationDataModel(final ImmutableBindingRef<ConversationData> refDataModel) {
        mConversationDataModel = refDataModel;
        mConversationDataModel.getData().addConversationDataListener(mDataListener);
    }

    ImmutableBindingRef<DraftMessageData> getDraftDataModel() {
        return BindingBase.createBindingReference(mBinding);
    }

    // returns true if it actually shows the subject editor and false if already showing
    private boolean showSubjectEditor() {
        // show the subject editor
        if (mSubjectView.getVisibility() == View.GONE) {
            mSubjectView.setVisibility(View.VISIBLE);
            mSubjectView.requestFocus();
            return true;
        }
        return false;
    }

    private void hideSubjectEditor() {
        mSubjectView.setVisibility(View.GONE);
        mComposeEditText.requestFocus();
    }

    /**
     * {@inheritDoc} from TextView.OnEditorActionListener
     */
    @Override // TextView.OnEditorActionListener.onEditorAction
    public boolean onEditorAction(final TextView view, final int actionId, final KeyEvent event) {
        if (actionId == EditorInfo.IME_ACTION_SEND) {
            sendMessageInternal(true /* checkMessageSize */);
            return true;
        }
        return false;
    }

    private void sendMessageInternal(final boolean checkMessageSize) {
        LogUtil.i(LogUtil.BUGLE_TAG, "UI initiated message sending in conversation " +
                mBinding.getData().getConversationId());
        if (mBinding.getData().isCheckingDraft()) {
            // Don't send message if we are currently checking draft for sending.
            LogUtil.w(LogUtil.BUGLE_TAG, "Message can't be sent: still checking draft");
            return;
        }
        // Check the host for pre-conditions about any action.
        if (mHost.isReadyForAction()) {
            mInputManager.showHideSimSelector(false /* show */, true /* animate */);
            final String messageToSend = mComposeEditText.getText().toString();
            mBinding.getData().setMessageText(messageToSend);
            final String subject = mComposeSubjectText.getText().toString();
            mBinding.getData().setMessageSubject(subject);
            // Asynchronously check the draft against various requirements before sending.
            mBinding.getData().checkDraftForAction(checkMessageSize,
                    mHost.getConversationSelfSubId(), new CheckDraftTaskCallback() {
                @Override
                public void onDraftChecked(DraftMessageData data, int result) {
                    mBinding.ensureBound(data);
                    switch (result) {
                        case CheckDraftForSendTask.RESULT_PASSED:
                            // Continue sending after check succeeded.
                            final MessageData message = mBinding.getData()
                                    .prepareMessageForSending(mBinding);
                            if (message != null && message.hasContent()) {
                                playSentSound();
                                mHost.sendMessage(message);
                                hideSubjectEditor();
                                if (AccessibilityUtil.isTouchExplorationEnabled(getContext())) {
                                    AccessibilityUtil.announceForAccessibilityCompat(
                                            ComposeMessageView.this, null,
                                            R.string.sending_message);
                                }
                            }
                            break;

                        case CheckDraftForSendTask.RESULT_HAS_PENDING_ATTACHMENTS:
                            // Cannot send while there's still attachment(s) being loaded.
                            UiUtils.showToastAtBottom(
                                    R.string.cant_send_message_while_loading_attachments);
                            break;

                        case CheckDraftForSendTask.RESULT_NO_SELF_PHONE_NUMBER_IN_GROUP_MMS:
                            mHost.promptForSelfPhoneNumber();
                            break;

                        case CheckDraftForSendTask.RESULT_MESSAGE_OVER_LIMIT:
                            Assert.isTrue(checkMessageSize);
                            mHost.warnOfExceedingMessageLimit(
                                    true /*sending*/, false /* tooManyVideos */);
                            break;

                        case CheckDraftForSendTask.RESULT_VIDEO_ATTACHMENT_LIMIT_EXCEEDED:
                            Assert.isTrue(checkMessageSize);
                            mHost.warnOfExceedingMessageLimit(
                                    true /*sending*/, true /* tooManyVideos */);
                            break;

                        case CheckDraftForSendTask.RESULT_SIM_NOT_READY:
                            // Cannot send if there is no active subscription
                            UiUtils.showToastAtBottom(
                                    R.string.cant_send_message_without_active_subscription);
                            break;

                        default:
                            break;
                    }
                }
            }, mBinding);
        } else {
            mHost.warnOfMissingActionConditions(true /*sending*/,
                    new Runnable() {
                        @Override
                        public void run() {
                            sendMessageInternal(checkMessageSize);
                        }

            });
        }
    }

    public static void playSentSound() {
        // Check if this setting is enabled before playing
        final BuglePrefs prefs = BuglePrefs.getApplicationPrefs();
        final Context context = Factory.get().getApplicationContext();
        final String prefKey = context.getString(R.string.send_sound_pref_key);
        final boolean defaultValue = context.getResources().getBoolean(
                R.bool.send_sound_pref_default);
        if (!prefs.getBoolean(prefKey, defaultValue)) {
            return;
        }
        MediaUtil.get().playSound(context, R.raw.message_sent, null /* completionListener */);
    }

    /**
     * {@inheritDoc} from DraftMessageDataListener
     */
    @Override // From DraftMessageDataListener
    public void onDraftChanged(final DraftMessageData data, final int changeFlags) {
        // As this is called asynchronously when message read check bound before updating text
        mBinding.ensureBound(data);

        // We have to cache the values of the DraftMessageData because when we set
        // mComposeEditText, its onTextChanged calls updateVisualsOnDraftChanged,
        // which immediately reloads the text from the subject and message fields and replaces
        // what's in the DraftMessageData.

        final String subject = data.getMessageSubject();
        final String message = data.getMessageText();

        boolean hasAttachmentsChanged = false;

        if ((changeFlags & DraftMessageData.MESSAGE_SUBJECT_CHANGED) ==
                DraftMessageData.MESSAGE_SUBJECT_CHANGED) {
            mComposeSubjectText.setText(subject);

            // Set the cursor selection to the end since setText resets it to the start
            mComposeSubjectText.setSelection(mComposeSubjectText.getText().length());
        }

        if ((changeFlags & DraftMessageData.MESSAGE_TEXT_CHANGED) ==
                DraftMessageData.MESSAGE_TEXT_CHANGED) {
            mComposeEditText.setText(message);

            // Set the cursor selection to the end since setText resets it to the start
            mComposeEditText.setSelection(mComposeEditText.getText().length());
        }

        if ((changeFlags & DraftMessageData.ATTACHMENTS_CHANGED) ==
                DraftMessageData.ATTACHMENTS_CHANGED) {
            final boolean haveAttachments = mAttachmentPreview.onAttachmentsChanged(data);
            mHost.onAttachmentsChanged(haveAttachments);
            hasAttachmentsChanged = true;
        }

        if ((changeFlags & DraftMessageData.SELF_CHANGED) == DraftMessageData.SELF_CHANGED) {
            updateOnSelfSubscriptionChange();
        }
        updateVisualsOnDraftChanged(hasAttachmentsChanged);
    }

    @Override   // From DraftMessageDataListener
    public void onDraftAttachmentLimitReached(final DraftMessageData data) {
        mBinding.ensureBound(data);
        mHost.warnOfExceedingMessageLimit(false /* sending */, false /* tooManyVideos */);
    }

    private void updateOnSelfSubscriptionChange() {
        // Refresh the length filters according to the selected self's MmsConfig.
        mComposeEditText.setFilters(new InputFilter[] {
                new LengthFilter(MmsConfig.get(mBinding.getData().getSelfSubId())
                        .getMaxTextLimit()) });
        mComposeSubjectText.setFilters(new InputFilter[] {
                new LengthFilter(MmsConfig.get(mBinding.getData().getSelfSubId())
                        .getMaxSubjectLength())});
    }

    @Override
    public void onMediaItemsSelected(final Collection<MessagePartData> items) {
        mBinding.getData().addAttachments(items);
        announceMediaItemState(true /*isSelected*/);
    }

    @Override
    public void onMediaItemsUnselected(final MessagePartData item) {
        mBinding.getData().removeAttachment(item);
        announceMediaItemState(false /*isSelected*/);
    }

    @Override
    public void onPendingAttachmentAdded(final PendingAttachmentData pendingItem) {
        mBinding.getData().addPendingAttachment(pendingItem, mBinding);
        resumeComposeMessage();
    }

    private void announceMediaItemState(final boolean isSelected) {
        final Resources res = getContext().getResources();
        final String announcement = isSelected ? res.getString(
                R.string.mediapicker_gallery_item_selected_content_description) :
                    res.getString(R.string.mediapicker_gallery_item_unselected_content_description);
        AccessibilityUtil.announceForAccessibilityCompat(
                this, null, announcement);
    }

    private void announceAttachmentState() {
        if (AccessibilityUtil.isTouchExplorationEnabled(getContext())) {
            int attachmentCount = mBinding.getData().getReadOnlyAttachments().size()
                    + mBinding.getData().getReadOnlyPendingAttachments().size();
            final String announcement = getContext().getResources().getQuantityString(
                    R.plurals.attachment_changed_accessibility_announcement,
                    attachmentCount, attachmentCount);
            AccessibilityUtil.announceForAccessibilityCompat(
                    this, null, announcement);
        }
    }

    @Override
    public void resumeComposeMessage() {
        mComposeEditText.requestFocus();
        mInputManager.showHideImeKeyboard(true, true);
        announceAttachmentState();
    }

    public void clearAttachments() {
        mBinding.getData().clearAttachments(mHost.getAttachmentsClearedFlags());
        mHost.onAttachmentsCleared();
    }

    public void requestDraftMessage(boolean clearLocalDraft) {
        mBinding.getData().loadFromStorage(mBinding, null, clearLocalDraft);
    }

    public void setDraftMessage(final MessageData message) {
        mBinding.getData().loadFromStorage(mBinding, message, false);
    }

    public void writeDraftMessage() {
        final String messageText = mComposeEditText.getText().toString();
        mBinding.getData().setMessageText(messageText);

        final String subject = mComposeSubjectText.getText().toString();
        mBinding.getData().setMessageSubject(subject);

        mBinding.getData().saveToStorage(mBinding);
    }

    private void updateConversationSelfId(final String selfId, final boolean notify) {
        mBinding.getData().setSelfId(selfId, notify);
    }

    private Uri getSelfSendButtonIconUri() {
        final Uri overridenSelfUri = mHost.getSelfSendButtonIconUri();
        if (overridenSelfUri != null) {
            return overridenSelfUri;
        }
        final SubscriptionListEntry subscriptionListEntry = getSelfSubscriptionListEntry();

        if (subscriptionListEntry != null) {
            return subscriptionListEntry.selectedIconUri;
        }

        // Fall back to default self-avatar in the base case.
        final ParticipantData self = mConversationDataModel.getData().getDefaultSelfParticipant();
        return self == null ? null : AvatarUriUtil.createAvatarUri(self);
    }

    private SubscriptionListEntry getSelfSubscriptionListEntry() {
        return mConversationDataModel.getData().getSubscriptionEntryForSelfParticipant(
                mBinding.getData().getSelfId(), false /* excludeDefault */);
    }

    private boolean isDataLoadedForMessageSend() {
        // Check data loading prerequisites for sending a message.
        return mConversationDataModel != null && mConversationDataModel.isBound() &&
                mConversationDataModel.getData().getParticipantsLoaded();
    }

    private static class AsyncUpdateMessageBodySizeTask
            extends SafeAsyncTask<List<MessagePartData>, Void, Long> {

        private final Context mContext;
        private final TextView mSizeTextView;

        public AsyncUpdateMessageBodySizeTask(final Context context, final TextView tv) {
            mContext = context;
            mSizeTextView = tv;
        }

        @Override
        protected Long doInBackgroundTimed(final List<MessagePartData>... params) {
            final List<MessagePartData> attachments = params[0];
            long totalSize = 0;
            for (final MessagePartData attachment : attachments) {
                final Uri contentUri = attachment.getContentUri();
                if (contentUri != null) {
                    totalSize += UriUtil.getContentSize(attachment.getContentUri());
                }
            }
            return totalSize;
        }

        @Override
        protected void onPostExecute(Long size) {
            if (mSizeTextView != null) {
                mSizeTextView.setText(Formatter.formatFileSize(mContext, size));
                mSizeTextView.setVisibility(View.VISIBLE);
            }
        }
    }

    private void updateVisualsOnDraftChanged() {
        updateVisualsOnDraftChanged(false);
    }

    private void updateVisualsOnDraftChanged(boolean hasAttachmentsChanged) {
        final String messageText = mComposeEditText.getText().toString();
        final DraftMessageData draftMessageData = mBinding.getData();
        draftMessageData.setMessageText(messageText);

        final String subject = mComposeSubjectText.getText().toString();
        draftMessageData.setMessageSubject(subject);
        if (!TextUtils.isEmpty(subject)) {
             mSubjectView.setVisibility(View.VISIBLE);
        }

        final boolean hasMessageText = (TextUtils.getTrimmedLength(messageText) > 0);
        final boolean hasSubject = (TextUtils.getTrimmedLength(subject) > 0);
        final boolean hasWorkingDraft = hasMessageText || hasSubject ||
                mBinding.getData().hasAttachments();

        final List<MessagePartData> attachments =
                new ArrayList<MessagePartData>(draftMessageData.getReadOnlyAttachments());
        if (draftMessageData.getIsMms()) { // MMS case
            if (draftMessageData.hasAttachments()) {
                if (hasAttachmentsChanged) {
                    // Calculate message attachments size and show it.
                    new AsyncUpdateMessageBodySizeTask(getContext(), mMessageBodySize)
                            .executeOnThreadPool(attachments, null, null);
                } else {
                    // No update. Just show previous size.
                    mMessageBodySize.setVisibility(View.VISIBLE);
                }
            } else {
                mMessageBodySize.setVisibility(View.INVISIBLE);
            }
        } else { // SMS case
            // Update the SMS text counter.
            final int messageCount = draftMessageData.getNumMessagesToBeSent();
            final int codePointsRemaining =
                    draftMessageData.getCodePointsRemainingInCurrentMessage();
            // Show the counter only if we are going to send more than one message OR we are getting
            // close.
            if (messageCount > 1
                    || codePointsRemaining <= CODEPOINTS_REMAINING_BEFORE_COUNTER_SHOWN) {
                // Update the remaining characters and number of messages required.
                final String counterText =
                        messageCount > 1
                                ? codePointsRemaining + " / " + messageCount
                                : String.valueOf(codePointsRemaining);
                mMessageBodySize.setText(counterText);
                mMessageBodySize.setVisibility(View.VISIBLE);
            } else {
                mMessageBodySize.setVisibility(View.INVISIBLE);
            }
        }

        // Update the send message button. Self icon uri might be null if self participant data
        // and/or conversation metadata hasn't been loaded by the host.
        final Uri selfSendButtonUri = getSelfSendButtonIconUri();
        int sendWidgetMode = SEND_WIDGET_MODE_SELF_AVATAR;
        if (selfSendButtonUri != null) {
            if (hasWorkingDraft && isDataLoadedForMessageSend()) {
                UiUtils.revealOrHideViewWithAnimation(mSendButton, VISIBLE, null);
                if (isOverriddenAvatarAGroup()) {
                    // If the host has overriden the avatar to show a group avatar where the
                    // send button sits, we have to hide the group avatar because it can be larger
                    // than the send button and pieces of the avatar will stick out from behind
                    // the send button.
                    UiUtils.revealOrHideViewWithAnimation(mSelfSendIcon, GONE, null);
                }
                mMmsIndicator.setVisibility(draftMessageData.getIsMms() ? VISIBLE : INVISIBLE);
                sendWidgetMode = SEND_WIDGET_MODE_SEND_BUTTON;
            } else {
                mSelfSendIcon.setImageResourceUri(selfSendButtonUri);
                if (isOverriddenAvatarAGroup()) {
                    UiUtils.revealOrHideViewWithAnimation(mSelfSendIcon, VISIBLE, null);
                }
                UiUtils.revealOrHideViewWithAnimation(mSendButton, GONE, null);
                mMmsIndicator.setVisibility(INVISIBLE);
                if (shouldShowSimSelector(mConversationDataModel.getData())) {
                    sendWidgetMode = SEND_WIDGET_MODE_SIM_SELECTOR;
                }
            }
        } else {
            mSelfSendIcon.setImageResourceUri(null);
        }

        if (mSendWidgetMode != sendWidgetMode || sendWidgetMode == SEND_WIDGET_MODE_SIM_SELECTOR) {
            setSendButtonAccessibility(sendWidgetMode);
            mSendWidgetMode = sendWidgetMode;
        }

        // Update the text hint on the message box depending on the attachment type.
        final int attachmentCount = attachments.size();
        if (attachmentCount == 0) {
            final SubscriptionListEntry subscriptionListEntry =
                    mConversationDataModel.getData().getSubscriptionEntryForSelfParticipant(
                            mBinding.getData().getSelfId(), false /* excludeDefault */);
            if (subscriptionListEntry == null) {
                mComposeEditText.setHint(R.string.compose_message_view_hint_text);
            } else {
                mComposeEditText.setHint(Html.fromHtml(getResources().getString(
                        R.string.compose_message_view_hint_text_multi_sim,
                        subscriptionListEntry.displayName)));
            }
        } else {
            int type = -1;
            for (final MessagePartData attachment : attachments) {
                int newType;
                if (attachment.isImage()) {
                    newType = ContentType.TYPE_IMAGE;
                } else if (attachment.isAudio()) {
                    newType = ContentType.TYPE_AUDIO;
                } else if (attachment.isVideo()) {
                    newType = ContentType.TYPE_VIDEO;
                } else if (attachment.isVCard()) {
                    newType = ContentType.TYPE_VCARD;
                } else {
                    newType = ContentType.TYPE_OTHER;
                }

                if (type == -1) {
                    type = newType;
                } else if (type != newType || type == ContentType.TYPE_OTHER) {
                    type = ContentType.TYPE_OTHER;
                    break;
                }
            }

            switch (type) {
                case ContentType.TYPE_IMAGE:
                    mComposeEditText.setHint(getResources().getQuantityString(
                            R.plurals.compose_message_view_hint_text_photo, attachmentCount));
                    break;

                case ContentType.TYPE_AUDIO:
                    mComposeEditText.setHint(getResources().getQuantityString(
                            R.plurals.compose_message_view_hint_text_audio, attachmentCount));
                    break;

                case ContentType.TYPE_VIDEO:
                    mComposeEditText.setHint(getResources().getQuantityString(
                            R.plurals.compose_message_view_hint_text_video, attachmentCount));
                    break;

                case ContentType.TYPE_VCARD:
                    mComposeEditText.setHint(getResources().getQuantityString(
                            R.plurals.compose_message_view_hint_text_vcard, attachmentCount));
                    break;

                case ContentType.TYPE_OTHER:
                    mComposeEditText.setHint(getResources().getQuantityString(
                            R.plurals.compose_message_view_hint_text_attachments, attachmentCount));
                    break;

                default:
                    Assert.fail("Unsupported attachment type!");
                    break;
            }
        }
    }

    private void setSendButtonAccessibility(final int sendWidgetMode) {
        switch (sendWidgetMode) {
            case SEND_WIDGET_MODE_SELF_AVATAR:
                // No send button and no SIM selector; the self send button is no longer
                // important for accessibility.
                mSelfSendIcon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                mSelfSendIcon.setContentDescription(null);
                mSendButton.setVisibility(View.GONE);
                setSendWidgetAccessibilityTraversalOrder(SEND_WIDGET_MODE_SELF_AVATAR);
                break;

            case SEND_WIDGET_MODE_SIM_SELECTOR:
                mSelfSendIcon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
                mSelfSendIcon.setContentDescription(getSimContentDescription());
                setSendWidgetAccessibilityTraversalOrder(SEND_WIDGET_MODE_SIM_SELECTOR);
                break;

            case SEND_WIDGET_MODE_SEND_BUTTON:
                mMmsIndicator.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                mMmsIndicator.setContentDescription(null);
                setSendWidgetAccessibilityTraversalOrder(SEND_WIDGET_MODE_SEND_BUTTON);
                break;
        }
    }

    private String getSimContentDescription() {
        final SubscriptionListEntry sub = getSelfSubscriptionListEntry();
        if (sub != null) {
            return getResources().getString(
                    R.string.sim_selector_button_content_description_with_selection,
                    sub.displayName);
        } else {
            return getResources().getString(
                    R.string.sim_selector_button_content_description);
        }
    }

    // Set accessibility traversal order of the components in the send widget.
    private void setSendWidgetAccessibilityTraversalOrder(final int mode) {
        mAttachMediaButton.setAccessibilityTraversalBefore(R.id.compose_message_text);
        switch (mode) {
            case SEND_WIDGET_MODE_SIM_SELECTOR:
                mComposeEditText.setAccessibilityTraversalBefore(R.id.self_send_icon);
                break;
            case SEND_WIDGET_MODE_SEND_BUTTON:
                mComposeEditText.setAccessibilityTraversalBefore(R.id.send_message_button);
                break;
            default:
                break;
        }
    }

    @Override
    public void afterTextChanged(final Editable editable) {
    }

    @Override
    public void beforeTextChanged(final CharSequence s, final int start, final int count,
            final int after) {
        if (mHost.shouldHideAttachmentsWhenSimSelectorShown()) {
            hideSimSelector();
        }
    }

    private void hideSimSelector() {
        if (mInputManager.showHideSimSelector(false /* show */, true /* animate */)) {
            // Now that the sim selector has been hidden, reshow the attachments if they
            // have been hidden.
            hideAttachmentsWhenShowingSims(false /*simPickerVisible*/);
        }
    }

    @Override
    public void onTextChanged(final CharSequence s, final int start, final int before,
            final int count) {
        final BugleActionBarActivity activity = (mOriginalContext instanceof BugleActionBarActivity)
                ? (BugleActionBarActivity) mOriginalContext : null;
        if (activity != null && activity.getIsDestroyed()) {
            LogUtil.v(LogUtil.BUGLE_TAG, "got onTextChanged after onDestroy");

            // if we get onTextChanged after the activity is destroyed then, ah, wtf
            // b/18176615
            // This appears to have occurred as the result of orientation change.
            return;
        }

        mBinding.ensureBound();
        updateVisualsOnDraftChanged();
    }

    @Override
    public PlainTextEditText getComposeEditText() {
        return mComposeEditText;
    }

    public void displayPhoto(final Uri photoUri, final Rect imageBounds) {
        mHost.displayPhoto(photoUri, imageBounds, true /* isDraft */);
    }

    public void updateConversationSelfIdOnExternalChange(final String selfId) {
        updateConversationSelfId(selfId, true /* notify */);
    }

    /**
     * The selfId of the conversation. As soon as the DraftMessageData successfully loads (i.e.
     * getSelfId() is non-null), the selfId in DraftMessageData is treated as the sole source
     * of truth for conversation self id since it reflects any pending self id change the user
     * makes in the UI.
     */
    public String getConversationSelfId() {
        return mBinding.getData().getSelfId();
    }

    public void selectSim(SubscriptionListEntry subscriptionData) {
        final String oldSelfId = getConversationSelfId();
        final String newSelfId = subscriptionData.selfParticipantId;
        Assert.notNull(newSelfId);
        // Don't attempt to change self if self hasn't been loaded, or if self hasn't changed.
        if (oldSelfId == null || TextUtils.equals(oldSelfId, newSelfId)) {
            return;
        }
        updateConversationSelfId(newSelfId, true /* notify */);
    }

    public void hideAllComposeInputs(final boolean animate) {
        mInputManager.hideAllInputs(animate);
    }

    public void saveInputState(final Bundle outState) {
        mInputManager.onSaveInputState(outState);
    }

    public void resetMediaPickerState() {
        mInputManager.resetMediaPickerState();
    }

    public boolean onBackPressed() {
        return mInputManager.onBackPressed();
    }

    public boolean onNavigationUpPressed() {
        return mInputManager.onNavigationUpPressed();
    }

    public boolean updateActionBar(final ActionBar actionBar) {
        return mInputManager != null ? mInputManager.updateActionBar(actionBar) : false;
    }

    public static boolean shouldShowSimSelector(final ConversationData convData) {
        return convData.getSelfParticipantsCountExcludingDefault(true /* activeOnly */) > 1;
    }

    public void sendMessageIgnoreMessageSizeLimit() {
        sendMessageInternal(false /* checkMessageSize */);
    }

    public void onAttachmentPreviewLongClicked() {
        mHost.showAttachmentChooser();
    }

    @Override
    public void onDraftAttachmentLoadFailed() {
        mHost.notifyOfAttachmentLoadFailed();
    }

    private boolean isOverriddenAvatarAGroup() {
        final Uri overridenSelfUri = mHost.getSelfSendButtonIconUri();
        if (overridenSelfUri == null) {
            return false;
        }
        return AvatarUriUtil.TYPE_GROUP_URI.equals(AvatarUriUtil.getAvatarType(overridenSelfUri));
    }

    @Override
    public void setAccessibility(boolean enabled) {
        if (enabled) {
            mAttachMediaButton.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            mComposeEditText.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            mSendButton.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            setSendButtonAccessibility(mSendWidgetMode);
        } else {
            mSelfSendIcon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            mComposeEditText.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            mSendButton.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            mAttachMediaButton.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
    }
}
