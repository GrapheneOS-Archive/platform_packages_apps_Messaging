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

import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;

import com.android.messaging.R;
import com.android.messaging.datamodel.MessagingContentProvider;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.ui.BugleActionBarActivity;
import com.android.messaging.ui.UIIntents;
import com.android.messaging.ui.contact.ContactPickerFragment;
import com.android.messaging.ui.contact.ContactPickerFragment.ContactPickerFragmentHost;
import com.android.messaging.ui.conversation.ConversationActivityUiState.ConversationActivityUiStateHost;
import com.android.messaging.ui.conversation.ConversationFragment.ConversationFragmentHost;
import com.android.messaging.ui.conversationlist.ConversationListActivity;
import com.android.messaging.util.Assert;
import com.android.messaging.util.ContentType;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.UiUtils;

import androidx.appcompat.app.ActionBar;

public class ConversationActivity extends BugleActionBarActivity
        implements ContactPickerFragmentHost, ConversationFragmentHost,
        ConversationActivityUiStateHost {
    public static final int FINISH_RESULT_CODE = 1;
    private static final String SAVED_INSTANCE_STATE_UI_STATE_KEY = "uistate";

    private ConversationActivityUiState mUiState;

    // Fragment transactions cannot be performed after onSaveInstanceState() has been called since
    // it will cause state loss. We don't want to call commitAllowingStateLoss() since it's
    // dangerous. Therefore, we note when instance state is saved and avoid performing UI state
    // updates concerning fragments past that point.
    private boolean mInstanceStateSaved;

    // Tracks whether onPause is called.
    private boolean mIsPaused;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.conversation_activity);

        final Intent intent = getIntent();

        // Do our best to restore UI state from saved instance state.
        if (savedInstanceState != null) {
            mUiState = savedInstanceState.getParcelable(SAVED_INSTANCE_STATE_UI_STATE_KEY);
        } else {
            if (intent.
                    getBooleanExtra(UIIntents.UI_INTENT_EXTRA_GOTO_CONVERSATION_LIST, false)) {
                // See the comment in BugleWidgetService.getViewMoreConversationsView() why this
                // is unfortunately necessary. The Bugle desktop widget can display a list of
                // conversations. When there are more conversations that can be displayed in
                // the widget, the last item is a "More conversations" item. The way widgets
                // are built, the list items can only go to a single fill-in intent which points
                // to this ConversationActivity. When the user taps on "More conversations", we
                // really want to go to the ConversationList. This code makes that possible.
                finish();
                final Intent convListIntent = new Intent(this, ConversationListActivity.class);
                convListIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(convListIntent);
                return;
            }
        }

        // If saved instance state doesn't offer a clue, get the info from the intent.
        if (mUiState == null) {
            final String conversationId = intent.getStringExtra(
                    UIIntents.UI_INTENT_EXTRA_CONVERSATION_ID);
            mUiState = new ConversationActivityUiState(conversationId);
        }
        mUiState.setHost(this);
        mInstanceStateSaved = false;

        // Don't animate UI state change for initial setup.
        updateUiState(false /* animate */);

        // See if we're getting called from a widget to directly display an image or video
        final String extraToDisplay =
                intent.getStringExtra(UIIntents.UI_INTENT_EXTRA_ATTACHMENT_URI);
        if (!TextUtils.isEmpty(extraToDisplay)) {
            final String contentType =
                    intent.getStringExtra(UIIntents.UI_INTENT_EXTRA_ATTACHMENT_TYPE);
            final Rect bounds = UiUtils.getMeasuredBoundsOnScreen(
                    findViewById(R.id.conversation_and_compose_container));
            if (ContentType.isImageType(contentType)) {
                final Uri imagesUri = MessagingContentProvider.buildConversationImagesUri(
                        mUiState.getConversationId());
                UIIntents.get().launchFullScreenPhotoViewer(
                        this, Uri.parse(extraToDisplay), bounds, imagesUri);
            } else if (ContentType.isVideoType(contentType)) {
                UIIntents.get().launchFullScreenVideoViewer(this, Uri.parse(extraToDisplay));
            }
        }
    }

    @Override
    protected void onSaveInstanceState(final Bundle outState) {
        super.onSaveInstanceState(outState);
        // After onSaveInstanceState() is called, future changes to mUiState won't update the UI
        // anymore, because fragment transactions are not allowed past this point.
        // For an activity recreation due to orientation change, the saved instance state keeps
        // using the in-memory copy of the UI state instead of writing it to parcel as an
        // optimization, so the UI state values may still change in response to, for example,
        // focus change from the framework, making mUiState and actual UI inconsistent.
        // Therefore, save an exact "snapshot" (clone) of the UI state object to make sure the
        // restored UI state ALWAYS matches the actual restored UI components.
        outState.putParcelable(SAVED_INSTANCE_STATE_UI_STATE_KEY, mUiState.clone());
        mInstanceStateSaved = true;
    }

    @Override
    protected void onResume() {
        super.onResume();

        // we need to reset the mInstanceStateSaved flag since we may have just been restored from
        // a previous onStop() instead of an onDestroy().
        mInstanceStateSaved = false;
        mIsPaused = false;
    }

    @Override
    protected void onPause() {
        super.onPause();
        mIsPaused = true;
    }

    @Override
    public void onWindowFocusChanged(final boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        final ConversationFragment conversationFragment = getConversationFragment();
        // When the screen is turned on, the last used activity gets resumed, but it gets
        // window focus only after the lock screen is unlocked.
        if (hasFocus && conversationFragment != null) {
            conversationFragment.setConversationFocus();
        }
    }

    @Override
    public void onDisplayHeightChanged(final int heightSpecification) {
        super.onDisplayHeightChanged(heightSpecification);
        invalidateActionBar();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mUiState != null) {
            mUiState.setHost(null);
        }
    }

    @Override
    public void updateActionBar(final ActionBar actionBar) {
        super.updateActionBar(actionBar);
        final ConversationFragment conversation = getConversationFragment();
        final ContactPickerFragment contactPicker = getContactPicker();
        if (contactPicker != null && mUiState.shouldShowContactPickerFragment()) {
            contactPicker.updateActionBar(actionBar);
        } else if (conversation != null && mUiState.shouldShowConversationFragment()) {
            conversation.updateActionBar(actionBar);
        }
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem menuItem) {
        if (super.onOptionsItemSelected(menuItem)) {
            return true;
        }
        if (menuItem.getItemId() == android.R.id.home) {
            onNavigationUpPressed();
            return true;
        }
        return false;
    }

    public void onNavigationUpPressed() {
        // Let the conversation fragment handle the navigation up press.
        final ConversationFragment conversationFragment = getConversationFragment();
        if (conversationFragment != null && conversationFragment.onNavigationUpPressed()) {
            return;
        }
        onFinishCurrentConversation();
    }

    @Override
    public void onBackPressed() {
        // If action mode is active dismiss it
        if (getActionMode() != null) {
            dismissActionMode();
            return;
        }

        // Let the conversation fragment handle the back press.
        final ConversationFragment conversationFragment = getConversationFragment();
        if (conversationFragment != null && conversationFragment.onBackPressed()) {
            return;
        }
        super.onBackPressed();
    }

    private ContactPickerFragment getContactPicker() {
        return (ContactPickerFragment) getFragmentManager().findFragmentByTag(
                ContactPickerFragment.FRAGMENT_TAG);
    }

    private ConversationFragment getConversationFragment() {
        return (ConversationFragment) getFragmentManager().findFragmentByTag(
                ConversationFragment.FRAGMENT_TAG);
    }

    @Override // From ContactPickerFragmentHost
    public void onGetOrCreateNewConversation(final String conversationId) {
        Assert.isTrue(conversationId != null);
        mUiState.onGetOrCreateConversation(conversationId);
    }

    @Override // From ContactPickerFragmentHost
    public void onBackButtonPressed() {
        onBackPressed();
    }

    @Override // From ContactPickerFragmentHost
    public void onInitiateAddMoreParticipants() {
        mUiState.onAddMoreParticipants();
    }


    @Override
    public void onParticipantCountChanged(final boolean canAddMoreParticipants) {
        mUiState.onParticipantCountUpdated(canAddMoreParticipants);
    }

    @Override // From ConversationFragmentHost
    public void onStartComposeMessage() {
        mUiState.onStartMessageCompose();
    }

    @Override // From ConversationFragmentHost
    public void onConversationMetadataUpdated() {
        invalidateActionBar();
    }

    @Override // From ConversationFragmentHost
    public void onConversationMessagesUpdated(final int numberOfMessages) {
    }

    @Override // From ConversationFragmentHost
    public void onConversationParticipantDataLoaded(final int numberOfParticipants) {
    }

    @Override // From ConversationFragmentHost
    public boolean isActiveAndFocused() {
        return !mIsPaused && hasWindowFocus();
    }

    @Override // From ConversationActivityUiStateListener
    public void onConversationContactPickerUiStateChanged(final int oldState, final int newState,
            final boolean animate) {
        Assert.isTrue(oldState != newState);
        updateUiState(animate);
    }

    private void updateUiState(final boolean animate) {
        if (mInstanceStateSaved || mIsPaused) {
            return;
        }
        Assert.notNull(mUiState);
        final Intent intent = getIntent();
        final String conversationId = mUiState.getConversationId();

        final FragmentManager fragmentManager = getFragmentManager();
        final FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();

        final boolean needConversationFragment = mUiState.shouldShowConversationFragment();
        final boolean needContactPickerFragment = mUiState.shouldShowContactPickerFragment();
        ConversationFragment conversationFragment = getConversationFragment();

        // Set up the conversation fragment.
        if (needConversationFragment) {
            Assert.notNull(conversationId);
            if (conversationFragment == null) {
                conversationFragment = new ConversationFragment();
                fragmentTransaction.add(R.id.conversation_fragment_container,
                        conversationFragment, ConversationFragment.FRAGMENT_TAG);
            }
            final MessageData draftData = intent.getParcelableExtra(
                    UIIntents.UI_INTENT_EXTRA_DRAFT_DATA);
            if (!needContactPickerFragment) {
                // Once the user has committed the audience,remove the draft data from the
                // intent to prevent reuse
                intent.removeExtra(UIIntents.UI_INTENT_EXTRA_DRAFT_DATA);
            }
            conversationFragment.setHost(this);
            conversationFragment.setConversationInfo(this, conversationId, draftData);
        } else if (conversationFragment != null) {
            // Don't save draft to DB when removing conversation fragment and switching to
            // contact picking mode.  The draft is intended for the new group.
            conversationFragment.suppressWriteDraft();
            fragmentTransaction.remove(conversationFragment);
        }

        // Set up the contact picker fragment.
        ContactPickerFragment contactPickerFragment = getContactPicker();
        if (needContactPickerFragment) {
            if (contactPickerFragment == null) {
                contactPickerFragment = new ContactPickerFragment();
                fragmentTransaction.add(R.id.contact_picker_fragment_container,
                        contactPickerFragment, ContactPickerFragment.FRAGMENT_TAG);
            }
            contactPickerFragment.setHost(this);
            contactPickerFragment.setContactPickingMode(mUiState.getDesiredContactPickingMode(),
                    animate);
        } else if (contactPickerFragment != null) {
            fragmentTransaction.remove(contactPickerFragment);
        }

        fragmentTransaction.commit();
        invalidateActionBar();
    }

    @Override
    public void onFinishCurrentConversation() {
        // Simply finish the current activity. The current design is to leave any empty
        // conversations as is.
        finishAfterTransition();
    }

    @Override
    public boolean shouldResumeComposeMessage() {
        return mUiState.shouldResumeComposeMessage();
    }

    @SuppressWarnings("MissingSuperCall") // TODO: fix me
    @Override
    protected void onActivityResult(final int requestCode, final int resultCode,
            final Intent data) {
        if (requestCode == ConversationFragment.REQUEST_CHOOSE_ATTACHMENTS &&
                resultCode == RESULT_OK) {
            final ConversationFragment conversationFragment = getConversationFragment();
            if (conversationFragment != null) {
                conversationFragment.onAttachmentChoosen();
            } else {
                LogUtil.e(LogUtil.BUGLE_TAG, "ConversationFragment is missing after launching " +
                        "AttachmentChooserActivity!");
            }
        } else if (resultCode == FINISH_RESULT_CODE) {
            finish();
        }
    }
}
