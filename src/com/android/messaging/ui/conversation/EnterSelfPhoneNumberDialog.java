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

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.widget.EditText;

import com.android.messaging.R;
import com.android.messaging.datamodel.ParticipantRefresh;
import com.android.messaging.util.BuglePrefs;
import com.android.messaging.util.UiUtils;

/**
 * The dialog for the user to enter the phone number of their sim.
 */
public class EnterSelfPhoneNumberDialog extends DialogFragment {
    private EditText mEditText;
    private int mSubId;

    public static EnterSelfPhoneNumberDialog newInstance(final int subId) {
        final EnterSelfPhoneNumberDialog dialog = new EnterSelfPhoneNumberDialog();
        dialog.mSubId = subId;
        return dialog;
    }

    @Override
    public Dialog onCreateDialog(final Bundle savedInstanceState) {
        final Context context = getActivity();
        final LayoutInflater inflater = getLayoutInflater();
        mEditText = (EditText) inflater.inflate(R.layout.enter_phone_number_view, null, false);

        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(R.string.enter_phone_number_title)
                .setMessage(R.string.enter_phone_number_text)
                .setView(mEditText)
                .setNegativeButton(android.R.string.cancel,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(final DialogInterface dialog,
                                    final int button) {
                                dismiss();
                            }
                })
                .setPositiveButton(android.R.string.ok,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(final DialogInterface dialog,
                                    final int button) {
                                final String newNumber = mEditText.getText().toString();
                                dismiss();
                                if (!TextUtils.isEmpty(newNumber)) {
                                    savePhoneNumberInPrefs(newNumber);
                                    // TODO: Remove this toast and just auto-send
                                    // the message instead
                                    UiUtils.showToast(
                                            R.string
                                        .toast_after_setting_default_sms_app_for_message_send);
                                }
                            }
                });
        return builder.create();
    }

    private void savePhoneNumberInPrefs(final String newPhoneNumber) {
        final BuglePrefs subPrefs = BuglePrefs.getSubscriptionPrefs(mSubId);
        subPrefs.putString(getString(R.string.mms_phone_number_pref_key),
                newPhoneNumber);
        // Update the self participants so the new phone number will be reflected
        // everywhere in the UI.
        ParticipantRefresh.refreshSelfParticipants();
    }
}
