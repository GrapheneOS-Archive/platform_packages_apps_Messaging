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

import android.app.Activity;
import android.app.Fragment;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;

import com.android.messaging.Factory;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.util.ConversationIdSet;

/**
 * A central repository of Intents used to start activities.
 */
public abstract class UIIntents {
    public static UIIntents get() {
        return Factory.get().getUIIntents();
    }

    // Intent extras
    public static final String UI_INTENT_EXTRA_CONVERSATION_ID = "conversation_id";

    // Sending draft data (from share intent / message forwarding) to the ConversationActivity.
    public static final String UI_INTENT_EXTRA_DRAFT_DATA = "draft_data";

    // The request code for picking a media from the Document picker.
    public static final int REQUEST_PICK_MEDIA_FROM_DOCUMENT_PICKER = 1400;

    // The request code for picking a contact card from existing Contacts apps.
    public static final int REQUEST_PICK_CONTACT_CARD = 1500;

    // Indicates what type of notification this applies to (See BugleNotifications:
    // UPDATE_NONE, UPDATE_MESSAGES, UPDATE_ERRORS, UPDATE_ALL)
    public static final String UI_INTENT_EXTRA_NOTIFICATIONS_UPDATE = "notifications_update";

    // Pass a set of conversation id's.
    public static final String UI_INTENT_EXTRA_CONVERSATION_ID_SET = "conversation_id_set";

    // Sending class zero message to its activity
    public static final String UI_INTENT_EXTRA_MESSAGE_VALUES = "message_values";

    // For the widget to go to the ConversationList from the Conversation.
    public static final String UI_INTENT_EXTRA_GOTO_CONVERSATION_LIST = "goto_conv_list";

    // Indicates whether a conversation is launched with custom transition.
    public static final String UI_INTENT_EXTRA_WITH_CUSTOM_TRANSITION = "with_custom_transition";

    public static final String ACTION_RESET_NOTIFICATIONS =
            "com.android.messaging.reset_notifications";

    // Sending VCard uri to VCard detail activity
    public static final String UI_INTENT_EXTRA_VCARD_URI = "vcard_uri";

    public static final String CMAS_COMPONENT = "com.android.cellbroadcastreceiver.module";

    // Intent action for local broadcast receiver for conversation self id change.
    public static final String CONVERSATION_SELF_ID_CHANGE_BROADCAST_ACTION =
            "conversation_self_id_change";

    // Conversation self id
    public static final String UI_INTENT_EXTRA_CONVERSATION_SELF_ID = "conversation_self_id";

    // Subscription id
    public static final String UI_INTENT_EXTRA_SUB_ID = "sub_id";

    // Per-Subscription setting activity title
    public static final String UI_INTENT_EXTRA_PER_SUBSCRIPTION_SETTING_TITLE =
            "per_sub_setting_title";

    // Is application settings launched as the top level settings activity?
    public static final String UI_INTENT_EXTRA_TOP_LEVEL_SETTINGS = "top_level_settings";

    // Sending attachment uri from widget
    public static final String UI_INTENT_EXTRA_ATTACHMENT_URI = "attachment_uri";

    // Sending attachment content type from widget
    public static final String UI_INTENT_EXTRA_ATTACHMENT_TYPE = "attachment_type";

    public static final String ACTION_WIDGET_CONVERSATION =
            "com.android.messaging.widget_conversation:";

    public static final String UI_INTENT_EXTRA_REQUIRES_MMS = "requires_mms";

    public static final String UI_INTENT_EXTRA_SELF_ID = "self_id";

    // Message position to scroll to.
    public static final String UI_INTENT_EXTRA_MESSAGE_POSITION = "message_position";

    /**
     * Launch the permission check activity
     */
    public abstract void launchPermissionCheckActivity(final Context context);

    public abstract void launchConversationListActivity(final Context context);

    /**
     * Launch an activity to show a conversation. This method by default provides no additional
     * activity options.
     */
    public void launchConversationActivity(final Context context,
            final String conversationId, final MessageData draft) {
        launchConversationActivity(context, conversationId, draft, null,
                false /* withCustomTransition */);
    }

    /**
     * Launch an activity to show a conversation.
     */
    public abstract void launchConversationActivity(final Context context,
            final String conversationId, final MessageData draft, final Bundle activityOptions,
            final boolean withCustomTransition);


    /**
     * Launch an activity to show conversation with conversation list in back stack.
     */
    public abstract void launchConversationActivityWithParentStack(Context context,
            String conversationId, String smsBody);

    /**
     * Launch an activity to show a conversation as a new task.
     */
    public abstract void launchConversationActivityNewTask(final Context context,
            final String conversationId);

    /**
     * Launch an activity to start a new conversation
     */
    public abstract void launchCreateNewConversationActivity(final Context context,
            final MessageData draft);

    /**
     * Launch debug activity to set MMS config options.
     */
    public abstract void launchDebugMmsConfigActivity(final Context context);

    /**
     * Launch an activity to change settings.
     */
    public abstract void launchSettingsActivity(final Context context);

    /**
     * Launch an activity to add a contact with a given destination.
     */
    public abstract void launchAddContactActivity(final Context context, final String destination);

    /**
     * Launch an activity to show the document picker to pick an image/video/audio.
     *
     * @param fragment the requesting fragment
     */
    public abstract void launchDocumentImagePicker(final Fragment fragment);

    /**
     * Launch an activity to show the contacts list to pick one.
     *
     * @param fragment the requesting fragment
     */
    public abstract void launchContactCardPicker(final Fragment fragment);

    /**
     * Launch an activity to show people & options for a given conversation.
     */
    public abstract void launchPeopleAndOptionsActivity(final Activity context,
            final String conversationId);

    /**
     * Launch an external activity to handle a phone call
     * @param phoneNumber the phone number to call
     * @param clickPosition is the location tapped to start this launch for transition use
     */
    public abstract void launchPhoneCallActivity(final Context context, final String phoneNumber,
                                                 final Point clickPosition);

    /**
     * Launch an activity to show archived conversations.
     */
    public abstract void launchArchivedConversationsActivity(final Context context);

    /**
     * Launch an activity to show blocked participants.
     */
    public abstract void launchBlockedParticipantsActivity(final Context context);

    /**
     * Launch an activity to show a class zero message
     */
    public abstract void launchClassZeroActivity(Context context, ContentValues messageValues);

    /**
     * Launch an activity to let the user forward a message
     */
    public abstract void launchForwardMessageActivity(Context context, MessageData message);

    /**
     * Launch an activity to show details for a VCard
     */
    public abstract void launchVCardDetailActivity(Context context, Uri vcardUri);

    /**
     * Launch an external activity that handles the intent to add VCard to contacts
     */
    public abstract void launchSaveVCardToContactsActivity(Context context, Uri vcardUri);

    /**
     * Launch an activity to let the user select & unselect the list of attachments to send.
     */
    public abstract void launchAttachmentChooserActivity(final Activity activity,
            final String conversationId, final int requestCode);

    /**
     * Launch full screen video viewer.
     */
    public abstract void launchFullScreenVideoViewer(Context context, Uri videoUri);

    /**
     * Launch full screen photo viewer.
     */
    public abstract void launchFullScreenPhotoViewer(Activity activity, Uri initialPhoto,
            Rect initialPhotoBounds, Uri photosUri);

    /**
     * Launch an activity to show general app settings
     * @param topLevel indicates whether the app settings is launched as the top-level settings
     *        activity (instead of SettingsActivity which shows a collapsed view of the app
     *        settings + one settings item per subscription). This is true when there's only one
     *        active SIM in the system so we can show this activity directly.
     */
    public abstract void launchApplicationSettingsActivity(Context context, boolean topLevel);

    /**
     * Launch an activity to show per-subscription settings
     */
    public abstract void launchPerSubscriptionSettingsActivity(Context context, int subId,
            String settingTitle);

    /**
     * Get a ACTION_VIEW intent
     * @param url display the data in the url to users
     */
    public abstract Intent getViewUrlIntent(final String url);

    /**
     * Get an intent to launch the ringtone picker
     * @param title the title to show in the ringtone picker
     * @param existingUri the currently set uri
     * @param defaultUri the default uri if none is currently set
     * @param toneType type of ringtone to pick, maybe any of RingtoneManager.TYPE_*
     */
    public abstract Intent getRingtonePickerIntent(final String title, final Uri existingUri,
            final Uri defaultUri, final int toneType);

    /**
     * Get an intent to launch the wireless alert viewer.
     */
    public abstract Intent getWirelessAlertsIntent();

    /**
     * Get an intent to launch the dialog for changing the default SMS App.
     */
    public abstract Intent getChangeDefaultSmsAppIntent(final Activity activity);

    /**
     * Broadcast conversation self id change so it may be reflected in the message compose UI.
     */
    public abstract void broadcastConversationSelfIdChange(final Context context,
            final String conversationId, final String conversationSelfId);

    /**
     * Get a PendingIntent for starting conversation list from notifications.
     */
    public abstract PendingIntent getPendingIntentForConversationListActivity(
            final Context context);

    /**
     * Get a PendingIntent for starting conversation list from widget.
     */
    public abstract PendingIntent getWidgetPendingIntentForConversationListActivity(
            final Context context);

    /**
     * Get a PendingIntent for showing a conversation from notifications.
     */
    public abstract PendingIntent getPendingIntentForConversationActivity(final Context context,
            final String conversationId, final MessageData draft);

    /**
     * Get an Intent for showing a conversation from the widget.
     */
    public abstract Intent getIntentForConversationActivity(final Context context,
            final String conversationId, final MessageData draft);

    /**
     * Get a PendingIntent for sending a message to a conversation, without opening the Bugle UI.
     *
     * <p>This is intended to be used by the Android Wear companion app when sending transcribed
     * voice replies.
     */
    public abstract PendingIntent getPendingIntentForSendingMessageToConversation(
            final Context context, final String conversationId, final String selfId,
            final boolean requiresMms, final int requestCode);

    /**
     * Get a PendingIntent for clearing notifications.
     *
     * <p>This is intended to be used by notifications.
     */
    public abstract PendingIntent getPendingIntentForClearingNotifications(final Context context,
            final int updateTargets, final ConversationIdSet conversationIdSet,
            final int requestCode);

    /**
     * Get a PendingIntent for showing low storage notifications.
     */
    public abstract PendingIntent getPendingIntentForLowStorageNotifications(final Context context);

    /**
     * Get a PendingIntent for showing a new message to a secondary user.
     */
    public abstract PendingIntent getPendingIntentForSecondaryUserNewMessageNotification(
            final Context context);

    /**
     * Get an intent for showing advanced settings.
     */
    public abstract Intent getAdvancedSettingsIntent(final Context context);

    /**
     * Get an intent for the LaunchConversationActivity.
     */
    public abstract Intent getLaunchConversationActivityIntent(final Context context);

    /**
     *  Tell MediaScanner to re-scan the specified volume.
     */
    public abstract void kickMediaScanner(final Context context, final String volume);

    /**
     * Launch to browser for a url.
     */
    public abstract void launchBrowserForUrl(final Context context, final String url);

    /**
     * Get a PendingIntent for the widget conversation template.
     */
    public abstract PendingIntent getWidgetPendingIntentForConversationActivity(
            final Context context, final String conversationId, final int requestCode);

    /**
     * Get a PendingIntent for the conversation widget configuration activity template.
     */
    public abstract PendingIntent getWidgetPendingIntentForConfigurationActivity(
            final Context context, final int appWidgetId);

}
