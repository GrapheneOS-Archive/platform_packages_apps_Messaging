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

package com.android.messaging.ui.contact;

import android.app.Activity;
import android.app.Fragment;
import android.database.Cursor;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.transition.Explode;
import android.transition.Transition;
import android.transition.Transition.EpicenterCallback;
import android.transition.TransitionManager;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;

import com.android.messaging.R;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.action.ActionMonitor;
import com.android.messaging.datamodel.action.GetOrCreateConversationAction;
import com.android.messaging.datamodel.action.GetOrCreateConversationAction.GetOrCreateConversationActionListener;
import com.android.messaging.datamodel.action.GetOrCreateConversationAction.GetOrCreateConversationActionMonitor;
import com.android.messaging.datamodel.binding.Binding;
import com.android.messaging.datamodel.binding.BindingBase;
import com.android.messaging.datamodel.data.ContactListItemData;
import com.android.messaging.datamodel.data.ContactPickerData;
import com.android.messaging.datamodel.data.ContactPickerData.ContactPickerDataListener;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.ui.CustomHeaderPagerViewHolder;
import com.android.messaging.ui.CustomHeaderViewPager;
import com.android.messaging.ui.animation.ViewGroupItemVerticalExplodeAnimation;
import com.android.messaging.ui.contact.ContactRecipientAutoCompleteView.ContactChipsChangeListener;
import com.android.messaging.util.Assert;
import com.android.messaging.util.Assert.RunsOnMainThread;
import com.android.messaging.util.ContactUtil;
import com.android.messaging.util.ImeUtil;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.PhoneUtils;
import com.android.messaging.util.UiUtils;
import com.google.common.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Set;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;
import androidx.appcompat.widget.Toolbar.OnMenuItemClickListener;


/**
 * Shows lists of contacts to start conversations with.
 */
public class ContactPickerFragment extends Fragment implements ContactPickerDataListener,
        ContactListItemView.HostInterface, ContactChipsChangeListener, OnMenuItemClickListener,
        GetOrCreateConversationActionListener {
    public static final String FRAGMENT_TAG = "contactpicker";

    // Undefined contact picker mode. We should never be in this state after the host activity has
    // been created.
    public static final int MODE_UNDEFINED = 0;

    // The initial contact picker mode for starting a new conversation with one contact.
    public static final int MODE_PICK_INITIAL_CONTACT = 1;

    // The contact picker mode where one initial contact has been picked and we are showing
    // only the chips edit box.
    public static final int MODE_CHIPS_ONLY = 2;

    // The contact picker mode for picking more contacts after starting the initial 1-1.
    public static final int MODE_PICK_MORE_CONTACTS = 3;

    // The contact picker mode when max number of participants is reached.
    public static final int MODE_PICK_MAX_PARTICIPANTS = 4;

    public interface ContactPickerFragmentHost {
        void onGetOrCreateNewConversation(String conversationId);
        void onBackButtonPressed();
        void onInitiateAddMoreParticipants();
        void onParticipantCountChanged(boolean canAddMoreParticipants);
        void invalidateActionBar();
    }

    @VisibleForTesting
    final Binding<ContactPickerData> mBinding = BindingBase.createBinding(this);

    private ContactPickerFragmentHost mHost;
    private ContactRecipientAutoCompleteView mRecipientTextView;
    private CustomHeaderViewPager mCustomHeaderViewPager;
    private AllContactsListViewHolder mAllContactsListViewHolder;
    private FrequentContactsListViewHolder mFrequentContactsListViewHolder;
    private View mRootView;
    private View mPendingExplodeView;
    private View mComposeDivider;
    private Toolbar mToolbar;
    private int mContactPickingMode = MODE_UNDEFINED;

    // Keeps track of the currently selected phone numbers in the chips view to enable fast lookup.
    private Set<String> mSelectedPhoneNumbers = null;

    /**
     * {@inheritDoc} from Fragment
     */
    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mAllContactsListViewHolder = new AllContactsListViewHolder(getActivity(), this);
        mFrequentContactsListViewHolder = new FrequentContactsListViewHolder(getActivity(), this);

        if (ContactUtil.hasReadContactsPermission()) {
            mBinding.bind(DataModel.get().createContactPickerData(getActivity(), this));
            mBinding.getData().init(getLoaderManager(), mBinding);
        }
    }

    /**
     * {@inheritDoc} from Fragment
     */
    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container,
            final Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.contact_picker_fragment, container, false);
        mRecipientTextView = (ContactRecipientAutoCompleteView)
                view.findViewById(R.id.recipient_text_view);
        mRecipientTextView.setThreshold(0);
        mRecipientTextView.setDropDownAnchor(R.id.compose_contact_divider);

        mRecipientTextView.setContactChipsListener(this);
        mRecipientTextView.setDropdownChipLayouter(new ContactDropdownLayouter(inflater,
                getActivity(), this));
        mRecipientTextView.setAdapter(new ContactRecipientAdapter(getActivity(), this));
        mRecipientTextView.addTextChangedListener(new TextWatcher() {
            @Override
            public void onTextChanged(final CharSequence s, final int start, final int before,
                    final int count) {
            }

            @Override
            public void beforeTextChanged(final CharSequence s, final int start, final int count,
                    final int after) {
            }

            @Override
            public void afterTextChanged(final Editable s) {
                updateTextInputButtonsVisibility();
            }
        });

        final CustomHeaderPagerViewHolder[] viewHolders = {
                mFrequentContactsListViewHolder,
                mAllContactsListViewHolder };

        mCustomHeaderViewPager = (CustomHeaderViewPager) view.findViewById(R.id.contact_pager);
        mCustomHeaderViewPager.setViewHolders(viewHolders);
        mCustomHeaderViewPager.setViewPagerTabHeight(CustomHeaderViewPager.DEFAULT_TAB_STRIP_SIZE);
        mCustomHeaderViewPager.setBackgroundColor(getResources()
                .getColor(R.color.contact_picker_background));

        // The view pager defaults to the frequent contacts page.
        mCustomHeaderViewPager.setCurrentItem(0);

        mToolbar = (Toolbar) view.findViewById(R.id.toolbar);
        mToolbar.setNavigationIcon(R.drawable.ic_arrow_back_light);
        mToolbar.setNavigationContentDescription(R.string.back);
        mToolbar.setNavigationOnClickListener(new OnClickListener() {
            @Override
            public void onClick(final View v) {
                mHost.onBackButtonPressed();
            }
        });

        mToolbar.inflateMenu(R.menu.compose_menu);
        mToolbar.setOnMenuItemClickListener(this);

        mComposeDivider = view.findViewById(R.id.compose_contact_divider);
        mRootView = view;
        return view;
    }

    /**
     * {@inheritDoc}
     *
     * Called when the host activity has been created. At this point, the host activity should
     * have set the contact picking mode for us so that we may update our visuals.
     */
    @Override
    public void onActivityCreated(final Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        Assert.isTrue(mContactPickingMode != MODE_UNDEFINED);
        updateVisualsForContactPickingMode(false /* animate */);
        mHost.invalidateActionBar();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // We could not have bound to the data if the permission was denied.
        if (mBinding.isBound()) {
            mBinding.unbind();
        }

        if (mMonitor != null) {
            mMonitor.unregister();
        }
        mMonitor = null;
    }

    @Override
    public boolean onMenuItemClick(final MenuItem menuItem) {
        int itemId = menuItem.getItemId();
        if (itemId == R.id.action_ime_dialpad_toggle) {
            final int baseInputType = InputType.TYPE_TEXT_FLAG_MULTI_LINE;
            if ((mRecipientTextView.getInputType() & InputType.TYPE_CLASS_PHONE) !=
                InputType.TYPE_CLASS_PHONE) {
                mRecipientTextView.setInputType(baseInputType | InputType.TYPE_CLASS_PHONE);
                menuItem.setIcon(R.drawable.ic_ime_light);
            }
            else {
                mRecipientTextView.setInputType(baseInputType | InputType.TYPE_CLASS_TEXT);
                menuItem.setIcon(R.drawable.ic_numeric_dialpad);
            }
            ImeUtil.get().showImeKeyboard(getActivity(), mRecipientTextView);
            return true;
        }
        if (itemId == R.id.action_add_more_participants) {
            mHost.onInitiateAddMoreParticipants();
            return true;
        }
        if (itemId == R.id.action_confirm_participants) {
            maybeGetOrCreateConversation();
            return true;
        }
        if (itemId == R.id.action_delete_text) {
            Assert.equals(MODE_PICK_INITIAL_CONTACT, mContactPickingMode);
            mRecipientTextView.setText("");
            return true;
        }
        return false;
    }

    @Override // From ContactPickerDataListener
    public void onAllContactsCursorUpdated(final Cursor data) {
        mBinding.ensureBound();
        mAllContactsListViewHolder.onContactsCursorUpdated(data);
    }

    @Override // From ContactPickerDataListener
    public void onFrequentContactsCursorUpdated(final Cursor data) {
        mBinding.ensureBound();
        mFrequentContactsListViewHolder.onContactsCursorUpdated(data);
        if (data != null && data.getCount() == 0) {
            // Show the all contacts list when there's no frequents.
            mCustomHeaderViewPager.setCurrentItem(1);
        }
    }

    @Override // From ContactListItemView.HostInterface
    public void onContactListItemClicked(final ContactListItemData item,
            final ContactListItemView view) {
        if (!isContactSelected(item)) {
            if (mContactPickingMode == MODE_PICK_INITIAL_CONTACT) {
                mPendingExplodeView = view;
            }
            mRecipientTextView.appendRecipientEntry(item.getRecipientEntry());
        } else if (mContactPickingMode != MODE_PICK_INITIAL_CONTACT) {
            mRecipientTextView.removeRecipientEntry(item.getRecipientEntry());
        }
    }

    @Override // From ContactListItemView.HostInterface
    public boolean isContactSelected(final ContactListItemData item) {
        return mSelectedPhoneNumbers != null &&
                mSelectedPhoneNumbers.contains(PhoneUtils.getDefault().getCanonicalBySystemLocale(
                        item.getRecipientEntry().getDestination()));
    }

    /**
     * Call this immediately after attaching the fragment, or when there's a ui state change that
     * changes our host (i.e. restore from saved instance state).
     */
    public void setHost(final ContactPickerFragmentHost host) {
        mHost = host;
    }

    public void setContactPickingMode(final int mode, final boolean animate) {
        if (mContactPickingMode != mode) {
            // Guard against impossible transitions.
            Assert.isTrue(
                    // We may start from undefined mode to any mode when we are restoring state.
                    (mContactPickingMode == MODE_UNDEFINED) ||
                    (mContactPickingMode == MODE_PICK_INITIAL_CONTACT && mode == MODE_CHIPS_ONLY) ||
                    (mContactPickingMode == MODE_CHIPS_ONLY && mode == MODE_PICK_MORE_CONTACTS) ||
                    (mContactPickingMode == MODE_PICK_MORE_CONTACTS
                            && mode == MODE_PICK_MAX_PARTICIPANTS) ||
                    (mContactPickingMode == MODE_PICK_MAX_PARTICIPANTS
                            && mode == MODE_PICK_MORE_CONTACTS));

            mContactPickingMode = mode;
            updateVisualsForContactPickingMode(animate);
        }
    }

    private void showImeKeyboard() {
        Assert.notNull(mRecipientTextView);
        mRecipientTextView.requestFocus();

        // showImeKeyboard() won't work until the layout is ready, so wait until layout is complete
        // before showing the soft keyboard.
        UiUtils.doOnceAfterLayoutChange(mRootView, new Runnable() {
            @Override
            public void run() {
                final Activity activity = getActivity();
                if (activity != null) {
                    ImeUtil.get().showImeKeyboard(activity, mRecipientTextView);
                }
            }
        });
        mRecipientTextView.invalidate();
    }

    private void updateVisualsForContactPickingMode(final boolean animate) {
        // Don't update visuals if the visuals haven't been inflated yet.
        if (mRootView != null) {
            final Menu menu = mToolbar.getMenu();
            final MenuItem addMoreParticipantsItem = menu.findItem(
                    R.id.action_add_more_participants);
            final MenuItem confirmParticipantsItem = menu.findItem(
                    R.id.action_confirm_participants);
            switch (mContactPickingMode) {
                case MODE_PICK_INITIAL_CONTACT:
                    addMoreParticipantsItem.setVisible(false);
                    confirmParticipantsItem.setVisible(false);
                    mCustomHeaderViewPager.setVisibility(View.VISIBLE);
                    mComposeDivider.setVisibility(View.INVISIBLE);
                    mRecipientTextView.setEnabled(true);
                    showImeKeyboard();
                    break;

                case MODE_CHIPS_ONLY:
                    if (animate) {
                        if (mPendingExplodeView == null) {
                            // The user didn't click on any contact item, so use the toolbar as
                            // the view to "explode."
                            mPendingExplodeView = mToolbar;
                        }
                        startExplodeTransitionForContactLists(false /* show */);

                        ViewGroupItemVerticalExplodeAnimation.startAnimationForView(
                                mCustomHeaderViewPager, mPendingExplodeView, mRootView,
                                true /* snapshotView */, UiUtils.COMPOSE_TRANSITION_DURATION);
                        showHideContactPagerWithAnimation(false /* show */);
                    } else {
                        mCustomHeaderViewPager.setVisibility(View.GONE);
                    }

                    addMoreParticipantsItem.setVisible(true);
                    confirmParticipantsItem.setVisible(false);
                    mComposeDivider.setVisibility(View.VISIBLE);
                    mRecipientTextView.setEnabled(true);
                    break;

                case MODE_PICK_MORE_CONTACTS:
                    if (animate) {
                        // Correctly set the start visibility state for the view pager and
                        // individual list items (hidden initially), so that the transition
                        // manager can properly track the visibility change for the explode.
                        mCustomHeaderViewPager.setVisibility(View.VISIBLE);
                        toggleContactListItemsVisibilityForPendingTransition(false /* show */);
                        startExplodeTransitionForContactLists(true /* show */);
                    }
                    addMoreParticipantsItem.setVisible(false);
                    confirmParticipantsItem.setVisible(true);
                    mCustomHeaderViewPager.setVisibility(View.VISIBLE);
                    mComposeDivider.setVisibility(View.INVISIBLE);
                    mRecipientTextView.setEnabled(true);
                    showImeKeyboard();
                    break;

                case MODE_PICK_MAX_PARTICIPANTS:
                    addMoreParticipantsItem.setVisible(false);
                    confirmParticipantsItem.setVisible(true);
                    mCustomHeaderViewPager.setVisibility(View.VISIBLE);
                    mComposeDivider.setVisibility(View.INVISIBLE);
                    // TODO: Verify that this is okay for accessibility
                    mRecipientTextView.setEnabled(false);
                    break;

                default:
                    Assert.fail("Unsupported contact picker mode!");
                    break;
            }
            updateTextInputButtonsVisibility();
        }
    }

    private void updateTextInputButtonsVisibility() {
        final Menu menu = mToolbar.getMenu();
        final MenuItem keypadToggleItem = menu.findItem(R.id.action_ime_dialpad_toggle);
        final MenuItem deleteTextItem = menu.findItem(R.id.action_delete_text);
        if (mContactPickingMode == MODE_PICK_INITIAL_CONTACT) {
            if (TextUtils.isEmpty(mRecipientTextView.getText())) {
                deleteTextItem.setVisible(false);
                keypadToggleItem.setVisible(true);
            } else {
                deleteTextItem.setVisible(true);
                keypadToggleItem.setVisible(false);
            }
        } else {
            deleteTextItem.setVisible(false);
            keypadToggleItem.setVisible(false);
        }
    }

    private void maybeGetOrCreateConversation() {
        final ArrayList<ParticipantData> participants =
                mRecipientTextView.getRecipientParticipantDataForConversationCreation();
        if (ContactPickerData.isTooManyParticipants(participants.size())) {
            UiUtils.showToast(R.string.too_many_participants);
        } else if (participants.size() > 0 && mMonitor == null) {
            mMonitor = GetOrCreateConversationAction.getOrCreateConversation(participants,
                    null, this);
        }
    }

    /**
     * Watches changes in contact chips to determine possible state transitions (e.g. creating
     * the initial conversation, adding more participants or finish the current conversation)
     */
    @Override
    public void onContactChipsChanged(final int oldCount, final int newCount) {
        Assert.isTrue(oldCount != newCount);
        if (mContactPickingMode == MODE_PICK_INITIAL_CONTACT) {
            // Initial picking mode. Start a conversation once a recipient has been picked.
            maybeGetOrCreateConversation();
        } else if (mContactPickingMode == MODE_CHIPS_ONLY) {
            // oldCount == 0 means we are restoring from savedInstanceState to add the existing
            // chips, don't switch to "add more participants" mode in this case.
            if (oldCount > 0 && mRecipientTextView.isFocused()) {
                // Chips only mode. The user may have picked an additional contact or deleted the
                // only existing contact. Either way, switch to picking more participants mode.
                mHost.onInitiateAddMoreParticipants();
            }
        }
        mHost.onParticipantCountChanged(ContactPickerData.getCanAddMoreParticipants(newCount));

        // Refresh our local copy of the selected chips set to keep it up-to-date.
        mSelectedPhoneNumbers =  mRecipientTextView.getSelectedDestinations();
        invalidateContactLists();
    }

    /**
     * Listens for notification that invalid contacts have been removed during resolving them.
     * These contacts were not local contacts, valid email, or valid phone numbers
     */
    @Override
    public void onInvalidContactChipsPruned(final int prunedCount) {
        Assert.isTrue(prunedCount > 0);
        UiUtils.showToast(R.plurals.add_invalid_contact_error, prunedCount);
    }

    /**
     * Listens for notification that the user has pressed enter/done on the keyboard with all
     * contacts in place and we should create or go to the existing conversation now
     */
    @Override
    public void onEntryComplete() {
        if (mContactPickingMode == MODE_PICK_INITIAL_CONTACT ||
                mContactPickingMode == MODE_PICK_MORE_CONTACTS ||
                mContactPickingMode == MODE_PICK_MAX_PARTICIPANTS) {
            // Avoid multiple calls to create in race cases (hit done right after selecting contact)
            maybeGetOrCreateConversation();
        }
    }

    private void invalidateContactLists() {
        mAllContactsListViewHolder.invalidateList();
        mFrequentContactsListViewHolder.invalidateList();
    }

    /**
     * Kicks off a scene transition that animates visibility changes of individual contact list
     * items via explode animation.
     * @param show whether the contact lists are to be shown or hidden.
     */
    private void startExplodeTransitionForContactLists(final boolean show) {
        final Explode transition = new Explode();
        final Rect epicenter = mPendingExplodeView == null ? null :
            UiUtils.getMeasuredBoundsOnScreen(mPendingExplodeView);
        transition.setDuration(UiUtils.COMPOSE_TRANSITION_DURATION);
        transition.setInterpolator(UiUtils.EASE_IN_INTERPOLATOR);
        transition.setEpicenterCallback(new EpicenterCallback() {
            @Override
            public Rect onGetEpicenter(final Transition transition) {
                return epicenter;
            }
        });

        // Kick off the delayed scene explode transition. Anything happens after this line in this
        // method before the next frame will be tracked by the transition manager for visibility
        // changes and animated accordingly.
        TransitionManager.beginDelayedTransition(mCustomHeaderViewPager,
                transition);

        toggleContactListItemsVisibilityForPendingTransition(show);
    }

    /**
     * Toggle the visibility of contact list items in the contact lists for them to be tracked by
     * the transition manager for pending explode transition.
     */
    private void toggleContactListItemsVisibilityForPendingTransition(final boolean show) {
        mAllContactsListViewHolder.toggleVisibilityForPendingTransition(show, mPendingExplodeView);
        mFrequentContactsListViewHolder.toggleVisibilityForPendingTransition(show,
                mPendingExplodeView);
    }

    private void showHideContactPagerWithAnimation(final boolean show) {
        final boolean isPagerVisible = (mCustomHeaderViewPager.getVisibility() == View.VISIBLE);
        if (show == isPagerVisible) {
            return;
        }

        mCustomHeaderViewPager.animate().alpha(show ? 1F : 0F)
            .setStartDelay(!show ? UiUtils.COMPOSE_TRANSITION_DURATION : 0)
            .withStartAction(new Runnable() {
                @Override
                public void run() {
                    mCustomHeaderViewPager.setVisibility(View.VISIBLE);
                    mCustomHeaderViewPager.setAlpha(show ? 0F : 1F);
                }
            })
            .withEndAction(new Runnable() {
                @Override
                public void run() {
                    mCustomHeaderViewPager.setVisibility(show ? View.VISIBLE : View.GONE);
                    mCustomHeaderViewPager.setAlpha(1F);
                }
            });
    }

    @Override
    public void onContactCustomColorLoaded(final ContactPickerData data) {
        mBinding.ensureBound(data);
        invalidateContactLists();
    }

    public void updateActionBar(final ActionBar actionBar) {
        // Hide the action bar for contact picker mode. The custom ToolBar containing chips UI
        // etc. will take the spot of the action bar.
        actionBar.hide();
        UiUtils.setStatusBarColor(getActivity(),
                getResources().getColor(R.color.compose_notification_bar_background));
    }

    private GetOrCreateConversationActionMonitor mMonitor;

    @Override
    @RunsOnMainThread
    public void onGetOrCreateConversationSucceeded(final ActionMonitor monitor,
            final Object data, final String conversationId) {
        Assert.isTrue(monitor == mMonitor);
        Assert.isTrue(conversationId != null);

        mRecipientTextView.setInputType(InputType.TYPE_TEXT_FLAG_MULTI_LINE |
                InputType.TYPE_CLASS_TEXT);
        mHost.onGetOrCreateNewConversation(conversationId);

        mMonitor = null;
    }

    @Override
    @RunsOnMainThread
    public void onGetOrCreateConversationFailed(final ActionMonitor monitor,
            final Object data) {
        Assert.isTrue(monitor == mMonitor);
        LogUtil.e(LogUtil.BUGLE_TAG, "onGetOrCreateConversationFailed");
        mMonitor = null;
    }
}
