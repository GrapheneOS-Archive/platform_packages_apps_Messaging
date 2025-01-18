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

package com.android.messaging.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Resources;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.android.messaging.R;
import com.android.messaging.datamodel.action.HandleLowStorageAction;
import com.android.messaging.sms.SmsReleaseStorage;
import com.android.messaging.sms.SmsReleaseStorage.Duration;
import com.android.messaging.sms.SmsStorageStatusManager;
import com.android.messaging.util.Assert;

import java.util.ArrayList;
import java.util.List;

/**
 * Dialog to show the sms storage low warning
 */
public class SmsStorageLowWarningFragment extends Fragment {
    private SmsStorageLowWarningFragment() {
    }

    public static SmsStorageLowWarningFragment newInstance() {
        return new SmsStorageLowWarningFragment();
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final FragmentTransaction ft = getFragmentManager().beginTransaction();
        final ChooseActionDialogFragment dialog = ChooseActionDialogFragment.newInstance();
        dialog.setTargetFragment(this, 0/*requestCode*/);
        dialog.show(ft, null/*tag*/);
    }

    /**
     * Perform confirm action for a specific action
     *
     * @param actionIndex
     */
    private void confirm(final int actionIndex) {
        final FragmentTransaction ft = getFragmentManager().beginTransaction();
        final ConfirmationDialog dialog = ConfirmationDialog.newInstance(actionIndex);
        dialog.setTargetFragment(this, 0/*requestCode*/);
        dialog.show(ft, null/*tag*/);
    }

    /**
     * The dialog is cancelled at any step
     */
    private void cancel() {
        getActivity().finish();
    }

    /**
     * The dialog to show for user to choose what delete actions to take when storage is low
     */
    private static class ChooseActionDialogFragment extends DialogFragment {
        public static ChooseActionDialogFragment newInstance() {
            return new ChooseActionDialogFragment();
        }

        @Override
        public Dialog onCreateDialog(final Bundle savedInstanceState) {
            final AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());

            final LayoutInflater inflater = getActivity().getLayoutInflater();
            final View dialogLayout = inflater.inflate(
                    R.layout.sms_storage_low_warning_dialog, null);
            final ListView actionListView = (ListView) dialogLayout.findViewById(
                    R.id.free_storage_action_list);
            final List<String> actions = loadFreeStorageActions(getActivity().getResources());
            final ActionListAdapter listAdapter = new ActionListAdapter(getActivity(), actions);
            actionListView.setAdapter(listAdapter);

            builder.setTitle(R.string.sms_storage_low_title)
                    .setView(dialogLayout)
                    .setNegativeButton(R.string.ignore, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int id) {
                            dialog.cancel();
                        }
                    });

            final Dialog dialog = builder.create();
            dialog.setCanceledOnTouchOutside(false);
            return dialog;
        }

        @Override
        public void onCancel(final DialogInterface dialog) {
            ((SmsStorageLowWarningFragment) getTargetFragment()).cancel();
        }

        private class ActionListAdapter extends ArrayAdapter<String> {
            public ActionListAdapter(final Context context, final List<String> actions) {
                super(context, R.layout.sms_free_storage_action_item_view, actions);
            }

            @Override
            public View getView(final int position, final View view, final ViewGroup parent) {
                TextView actionItemView;
                if (view == null || !(view instanceof TextView)) {
                    final LayoutInflater inflater = LayoutInflater.from(getContext());
                    actionItemView = (TextView) inflater.inflate(
                            R.layout.sms_free_storage_action_item_view, parent, false);
                } else {
                    actionItemView = (TextView) view;
                }

                final String action = getItem(position);
                actionItemView.setText(action);
                actionItemView.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(final View view) {
                        dismiss();
                        ((SmsStorageLowWarningFragment) getTargetFragment()).confirm(position);
                    }
                });
                return actionItemView;
            }
        }
    }

    private static final String KEY_ACTION_INDEX = "action_index";

    /**
     * The dialog to confirm user's delete action
     */
    private static class ConfirmationDialog extends DialogFragment {
        private Duration mDuration;
        private String mDurationString;

        public static ConfirmationDialog newInstance(final int actionIndex) {
            final ConfirmationDialog dialog = new ConfirmationDialog();
            final Bundle args = new Bundle();
            args.putInt(KEY_ACTION_INDEX, actionIndex);
            dialog.setArguments(args);
            return dialog;
        }

        @Override
        public void onCancel(final DialogInterface dialog) {
            ((SmsStorageLowWarningFragment) getTargetFragment()).cancel();
        }

        @Override
        public Dialog onCreateDialog(final Bundle savedInstanceState) {
            mDuration = SmsReleaseStorage.parseMessageRetainingDuration();
            mDurationString = SmsReleaseStorage.getMessageRetainingDurationString(mDuration);

            final int actionIndex = getArguments().getInt(KEY_ACTION_INDEX);
            if (actionIndex < 0 || actionIndex > 1) {
                return null;
            }
            final AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle(R.string.sms_storage_low_title)
                    .setMessage(getConfirmDialogMessage(actionIndex))
                    .setNegativeButton(android.R.string.cancel,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(final DialogInterface dialog,
                                        final int button) {
                                    dismiss();
                                    ((SmsStorageLowWarningFragment) getTargetFragment()).cancel();
                                }
                    })
                    .setPositiveButton(android.R.string.ok,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(final DialogInterface dialog,
                                        final int button) {
                                    dismiss();
                                    handleAction(actionIndex);
                                    getActivity().finish();
                                    SmsStorageStatusManager.cancelStorageLowNotification();
                                }
                    });
            return builder.create();
        }

        private void handleAction(final int actionIndex) {
            final long durationInMillis =
                    SmsReleaseStorage.durationToTimeInMillis(mDuration);
            switch (actionIndex) {
                case 0:
                    HandleLowStorageAction.handleDeleteMediaMessages(durationInMillis);
                    break;

                case 1:
                    HandleLowStorageAction.handleDeleteOldMessages(durationInMillis);
                    break;

                default:
                    Assert.fail("Unsupported action");
                    break;
            }
        }

        /**
         * Get the confirm dialog text for a specific delete action
         * @param index The action index
         * @return
         */
        private String getConfirmDialogMessage(final int index) {
            switch (index) {
                case 0:
                    return getString(R.string.delete_all_media_confirmation);
                case 1:
                    return getString(R.string.delete_oldest_messages_confirmation, mDurationString);
                case 2:
                    return getString(R.string.auto_delete_oldest_messages_confirmation,
                            mDurationString);
            }
            throw new IllegalArgumentException(
                    "SmsStorageLowWarningFragment: invalid action index " + index);
        }
    }

    /**
     * Load the text of delete message actions
     *
     * @param resources
     * @return
     */
    private static List<String> loadFreeStorageActions(final Resources resources) {
        final Duration duration = SmsReleaseStorage.parseMessageRetainingDuration();
        final String durationString = SmsReleaseStorage.getMessageRetainingDurationString(duration);
        final List<String> actions = new ArrayList<>();
        actions.add(resources.getString(R.string.delete_all_media));
        actions.add(resources.getString(R.string.delete_oldest_messages, durationString));

        // TODO: Auto-purging is disabled for Bugle V1.
        // actions.add(resources.getString(R.string.auto_delete_oldest_messages, durationString));
        return actions;
    }
}
