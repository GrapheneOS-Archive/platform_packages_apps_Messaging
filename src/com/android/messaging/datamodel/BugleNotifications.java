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
import android.content.Intent;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.ContactsContract;
import android.provider.ContactsContract.Contacts;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationCompat.WearableExtender;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.Person;
import androidx.core.app.RemoteInput;
import androidx.collection.SimpleArrayMap;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.text.style.TextAppearanceSpan;

import com.android.messaging.Factory;
import com.android.messaging.R;
import com.android.messaging.datamodel.MessageNotificationState.BundledMessageNotificationState;
import com.android.messaging.datamodel.MessageNotificationState.ConversationLineInfo;
import com.android.messaging.datamodel.MessageNotificationState.MultiConversationNotificationState;
import com.android.messaging.datamodel.MessageNotificationState.MultiMessageNotificationState;
import com.android.messaging.datamodel.action.MarkAsReadAction;
import com.android.messaging.datamodel.action.MarkAsSeenAction;
import com.android.messaging.datamodel.action.RedownloadMmsAction;
import com.android.messaging.datamodel.data.ConversationListItemData;
import com.android.messaging.datamodel.media.AvatarRequestDescriptor;
import com.android.messaging.datamodel.media.ImageResource;
import com.android.messaging.datamodel.media.MediaRequest;
import com.android.messaging.datamodel.media.MediaResourceManager;
import com.android.messaging.datamodel.media.MessagePartVideoThumbnailRequestDescriptor;
import com.android.messaging.datamodel.media.UriImageRequestDescriptor;
import com.android.messaging.datamodel.media.VideoThumbnailRequest;
import com.android.messaging.sms.MmsSmsUtils;
import com.android.messaging.sms.MmsUtils;
import com.android.messaging.ui.UIIntents;
import com.android.messaging.util.Assert;
import com.android.messaging.util.AvatarUriUtil;
import com.android.messaging.util.BugleGservices;
import com.android.messaging.util.BugleGservicesKeys;
import com.android.messaging.util.BuglePrefs;
import com.android.messaging.util.BuglePrefsKeys;
import com.android.messaging.util.ContentType;
import com.android.messaging.util.ConversationIdSet;
import com.android.messaging.util.ImageUtils;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.NotificationChannelUtil;
import com.android.messaging.util.NotificationPlayer;
import com.android.messaging.util.OsUtil;
import com.android.messaging.util.PendingIntentConstants;
import com.android.messaging.util.PhoneUtils;
import com.android.messaging.util.RingtoneUtil;
import com.android.messaging.util.ThreadUtil;
import com.android.messaging.util.UriUtil;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Handle posting, updating and removing all conversation notifications.
 *
 * There are currently two main classes of notification and their rules: <p>
 * 1) Messages - {@link MessageNotificationState}. Only one message notification.
 * Unread messages across senders and conversations are coalesced.<p>
 * 2) Failed Messages - {@link MessageNotificationState#checkFailedMesages } Only one failed
 * message. Multiple failures are coalesced.<p>
 *
 * To add a new class of notifications, subclass the NotificationState and add commands which
 * create one and pass into general creation function.
 *
 */
public class BugleNotifications {
    // Logging
    public static final String TAG = LogUtil.BUGLE_NOTIFICATIONS_TAG;

    // Constants to use for update.
    public static final int UPDATE_NONE = 0;
    public static final int UPDATE_MESSAGES = 1;
    public static final int UPDATE_ERRORS = 2;
    public static final int UPDATE_ALL = UPDATE_MESSAGES + UPDATE_ERRORS;

    // Constants for notification type used for audio and vibration settings.
    public static final int LOCAL_SMS_NOTIFICATION = 0;

    private static final String SMS_NOTIFICATION_TAG = ":sms:";
    private static final String SMS_ERROR_NOTIFICATION_TAG = ":error:";

    private static final String WEARABLE_COMPANION_APP_PACKAGE = "com.google.android.wearable.app";

    private static final Set<NotificationState> sPendingNotifications =
            new HashSet<NotificationState>();

    private static int sWearableImageWidth;
    private static int sWearableImageHeight;
    private static int sIconWidth;
    private static int sIconHeight;

    private static boolean sInitialized = false;

    private static final Object mLock = new Object();

    // sLastMessageDingTime is a map between a conversation id and a time. It's used to keep track
    // of the time we last dinged a message for this conversation. When messages are coming in
    // at flurry, we don't want to over-ding the user.
    private static final SimpleArrayMap<String, Long> sLastMessageDingTime =
            new SimpleArrayMap<String, Long>();
    private static int sTimeBetweenDingsMs;

    /**
     * This is the volume at which to play the observable-conversation notification sound,
     * expressed as a fraction of the system notification volume.
     */
    private static final float OBSERVABLE_CONVERSATION_NOTIFICATION_VOLUME = 0.25f;

    /**
     * Entry point for posting notifications.
     * Don't call this on the UI thread.
     * @param silent If true, no ring will be played. If false, checks global settings before
     * playing a ringtone
     * @param coverage Indicates which notification types should be checked. Valid values are
     * UPDATE_NONE, UPDATE_MESSAGES, UPDATE_ERRORS, or UPDATE_ALL
     */
    public static void update(final boolean silent, final int coverage) {
        update(silent, null /* conversationId */, coverage);
    }

    /**
     * Entry point for posting notifications.
     * Don't call this on the UI thread.
     * @param silent If true, no ring will be played. If false, checks global settings before
     * playing a ringtone
     * @param conversationId Conversation ID where a new message was received
     * @param coverage Indicates which notification types should be checked. Valid values are
     * UPDATE_NONE, UPDATE_MESSAGES, UPDATE_ERRORS, or UPDATE_ALL
     */
    public static void update(final boolean silent, final String conversationId,
            final int coverage) {
        if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
            LogUtil.v(TAG, "Update: silent = " + silent
                    + " conversationId = " + conversationId
                    + " coverage = " + coverage);
        }
    Assert.isNotMainThread();
        checkInitialized();

        if (!shouldNotify()) {
            if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
                LogUtil.v(TAG, "Notifications disabled");
            }
            cancel(PendingIntentConstants.SMS_NOTIFICATION_ID);
            return;
        } else {
            if ((coverage & UPDATE_MESSAGES) != 0) {
                createMessageNotification(silent, conversationId);
            }
        }
        if ((coverage & UPDATE_ERRORS) != 0) {
            MessageNotificationState.checkFailedMessages();
        }
    }

    /**
     * Cancel all notifications of a certain type.
     *
     * @param type Message or error notifications from Constants.
     */
    private static synchronized void cancel(final int type) {
        cancel(type, null, false);
    }

    /**
     * Cancel all notifications of a certain type.
     *
     * @param type Message or error notifications from Constants.
     * @param conversationId If set, cancel the notification for this
     *            conversation only. For message notifications, this only works
     *            if the notifications are bundled (group children).
     * @param isBundledNotification True if this notification is part of a
     *            notification bundle. This only applies to message notifications,
     *            which are bundled together with other message notifications.
     */
    private static synchronized void cancel(final int type, final String conversationId,
            final boolean isBundledNotification) {
        final String notificationTag = buildNotificationTag(type, conversationId,
                isBundledNotification);
        final NotificationManagerCompat notificationManager =
                NotificationManagerCompat.from(Factory.get().getApplicationContext());

        // Find all pending notifications and cancel them.
        synchronized (sPendingNotifications) {
            final Iterator<NotificationState> iter = sPendingNotifications.iterator();
            while (iter.hasNext()) {
                final NotificationState notifState = iter.next();
                if (notifState.mType == type) {
                    notifState.mCanceled = true;
                    if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
                        LogUtil.v(TAG, "Canceling pending notification");
                    }
                    iter.remove();
                }
            }
        }
        notificationManager.cancel(notificationTag, type);
        if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
            LogUtil.d(TAG, "Canceled notifications of type " + type);
        }

        // Message notifications for multiple conversations can be grouped together (see comment in
        // createMessageNotification). We need to do bookkeeping to track the current set of
        // notification group children, including removing them when we cancel notifications).
        if (type == PendingIntentConstants.SMS_NOTIFICATION_ID) {
            final Context context = Factory.get().getApplicationContext();
            final ConversationIdSet groupChildIds = getGroupChildIds(context);

            if (groupChildIds != null && groupChildIds.size() > 0) {
                // If a conversation is specified, remove just that notification. Otherwise,
                // we're removing the group summary so clear all children.
                if (conversationId != null) {
                    groupChildIds.remove(conversationId);
                    writeGroupChildIds(context, groupChildIds);
                } else {
                    cancelStaleGroupChildren(groupChildIds, null);
                    // We'll update the group children preference as we cancel each child,
                    // so we don't need to do it here.
                }
            }
        }
    }

    /**
     * Cancels stale notifications from the currently active group of
     * notifications. If the {@code state} parameter is an instance of
     * {@link MultiConversationNotificationState} it represents a new
     * notification group. This method will cancel any notifications that were
     * in the old group, but not the new one. If the new notification is not a
     * group, then all existing grouped notifications are cancelled.
     *
     * @param previousGroupChildren Conversation ids for the active notification
     *            group
     * @param state New notification state
     */
    private static void cancelStaleGroupChildren(final ConversationIdSet previousGroupChildren,
            final NotificationState state) {
        final ConversationIdSet newChildren = new ConversationIdSet();
        if (state instanceof MultiConversationNotificationState) {
            for (final NotificationState child :
                ((MultiConversationNotificationState) state).mChildren) {
                if (child.mConversationIds != null) {
                    newChildren.add(child.mConversationIds.first());
                }
            }
        }
        for (final String childConversationId : previousGroupChildren) {
            if (!newChildren.contains(childConversationId)) {
                cancel(PendingIntentConstants.SMS_NOTIFICATION_ID, childConversationId, true);
            }
        }
    }

    /**
     * Returns {@code true} if incoming notifications should display a
     * notification, {@code false} otherwise.
     *
     * @return true if the notification should occur
     */
    private static boolean shouldNotify() {
        // If we're not the default sms app, don't put up any notifications.
        if (!PhoneUtils.getDefault().isDefaultSmsApp()) {
            return false;
        }

        // Now check prefs (i.e. settings) to see if the user turned off notifications.
        final BuglePrefs prefs = BuglePrefs.getApplicationPrefs();
        final Context context = Factory.get().getApplicationContext();
        final String prefKey = context.getString(R.string.notifications_enabled_pref_key);
        final boolean defaultValue = context.getResources().getBoolean(
                R.bool.notifications_enabled_pref_default);
        return prefs.getBoolean(prefKey, defaultValue);
    }

    /**
     * Returns {@code true} if incoming notifications for the given {@link NotificationState}
     * should vibrate the device, {@code false} otherwise.
     *
     * @return true if vibration should be used
     */
    public static boolean shouldVibrate(final NotificationState state) {
        // The notification should vibrate if the global setting is turned on AND
        // the per-conversation setting is turned on (default).
        if (!state.getNotificationVibrate()) {
            return false;
        } else {
            final BuglePrefs prefs = BuglePrefs.getApplicationPrefs();
            final Context context = Factory.get().getApplicationContext();
            final String prefKey = context.getString(R.string.notification_vibration_pref_key);
            final boolean defaultValue = context.getResources().getBoolean(
                    R.bool.notification_vibration_pref_default);
            return prefs.getBoolean(prefKey, defaultValue);
        }
    }

    private static Uri getNotificationRingtoneUriForConversationId(final String conversationId) {
        final DatabaseWrapper db = DataModel.get().getDatabase();
        final ConversationListItemData convData =
                ConversationListItemData.getExistingConversation(db, conversationId);
        return RingtoneUtil.getNotificationRingtoneUri(
                convData != null ? convData.getNotificationSoundUri() : null);
    }

    /**
     * Returns a unique tag to identify a notification.
     *
     * @param name The tag name (in practice, the type)
     * @param conversationId The conversation id (optional)
     */
    private static String buildNotificationTag(final String name,
            final String conversationId) {
        final Context context = Factory.get().getApplicationContext();
        if (conversationId != null) {
            return context.getPackageName() + name + ":" + conversationId;
        } else {
            return context.getPackageName() + name;
        }
    }

    /**
     * Returns a unique tag to identify a notification.
     * <p>
     * This delegates to
     * {@link #buildNotificationTag(int, String, boolean)} and can be
     * used when the notification is never bundled (e.g. error notifications).
     */
    static String buildNotificationTag(final int type, final String conversationId) {
        return buildNotificationTag(type, conversationId, false /* bundledNotification */);
    }

    /**
     * Returns a unique tag to identify a notification.
     *
     * @param type One of the constants in {@link PendingIntentConstants}
     * @param conversationId The conversation id (where applicable)
     * @param bundledNotification Set to true if this notification will be
     *            bundled together with other notifications (e.g. on a wearable
     *            device).
     */
    static String buildNotificationTag(final int type, final String conversationId,
            final boolean bundledNotification) {
        String tag = null;
        switch(type) {
            case PendingIntentConstants.SMS_NOTIFICATION_ID:
                if (bundledNotification) {
                    tag = buildNotificationTag(SMS_NOTIFICATION_TAG, conversationId);
                } else {
                    tag = buildNotificationTag(SMS_NOTIFICATION_TAG, null);
                }
                break;
            case PendingIntentConstants.MSG_SEND_ERROR:
                tag = buildNotificationTag(SMS_ERROR_NOTIFICATION_TAG, null);
                break;
        }
        return tag;
    }

    private static void checkInitialized() {
        if (!sInitialized) {
            final Resources resources = Factory.get().getApplicationContext().getResources();
            sWearableImageWidth = resources.getDimensionPixelSize(
                    R.dimen.notification_wearable_image_width);
            sWearableImageHeight = resources.getDimensionPixelSize(
                    R.dimen.notification_wearable_image_height);
            sIconHeight = (int) resources.getDimension(
                    android.R.dimen.notification_large_icon_height);
            sIconWidth =
                    (int) resources.getDimension(android.R.dimen.notification_large_icon_width);

            sInitialized = true;
        }
    }

    private static void processAndSend(final NotificationState state, final boolean silent,
            final boolean softSound) {
        final Context context = Factory.get().getApplicationContext();
        final NotificationCompat.Builder notifBuilder =
                new NotificationCompat.Builder(context, NotificationChannelUtil.CONVERSATIONS);
        notifBuilder.setCategory(Notification.CATEGORY_MESSAGE);
        // TODO: Need to fix this for multi conversation notifications to rate limit dings.
        final String conversationId = state.mConversationIds.first();


        final Uri ringtoneUri = RingtoneUtil.getNotificationRingtoneUri(state.getRingtoneUri());
        // If the notification's conversation is currently observable (focused or in the
        // conversation list),  then play a notification beep at a low volume and don't display an
        // actual notification.
        if (softSound) {
            if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
                LogUtil.v(TAG, "processAndSend: fromConversationId == " +
                        "sCurrentlyDisplayedConversationId so NOT showing notification," +
                        " but playing soft sound. conversationId: " + conversationId);
            }
            playObservableConversationNotificationSound(ringtoneUri);
            return;
        }
        state.mBaseRequestCode = state.mType;

        // Set the delete intent (except for bundled wearable notifications, which are dismissed
        // as a group, either from the wearable or when the summary notification is dismissed from
        // the host device).
        if (!(state instanceof BundledMessageNotificationState)) {
            final PendingIntent clearIntent = state.getClearIntent();
            notifBuilder.setDeleteIntent(clearIntent);
        }

        updateBuilderAudioVibrate(state, notifBuilder, silent, ringtoneUri, conversationId);

        // Set the content intent
        PendingIntent destinationIntent;
        if (state.mConversationIds.size() > 1) {
            // We have notifications for multiple conversation, go to the conversation list.
            destinationIntent = UIIntents.get()
                .getPendingIntentForConversationListActivity(context);
        } else {
            // We have a single conversation, go directly to that conversation.
            destinationIntent = UIIntents.get()
                    .getPendingIntentForConversationActivity(context,
                            state.mConversationIds.first(),
                            null /*draft*/);
        }
        notifBuilder.setContentIntent(destinationIntent);

        // TODO: set based on contact coming from a favorite.
        notifBuilder.setPriority(state.getPriority());

        // Save the state of the notification in-progress so when the avatar is loaded,
        // we can continue building the notification.
        final NotificationCompat.Style notifStyle = state.build(notifBuilder);
        state.mNotificationBuilder = notifBuilder;
        state.mNotificationStyle = notifStyle;
        if (!state.mPeople.isEmpty()) {
            final Bundle people = new Bundle();
            people.putStringArray(NotificationCompat.EXTRA_PEOPLE,
                    state.mPeople.toArray(new String[state.mPeople.size()]));
            notifBuilder.addExtras(people);
        }

        if (state.mParticipantAvatarsUris != null) {
            final Uri avatarUri = state.mParticipantAvatarsUris.get(0);
            final AvatarRequestDescriptor descriptor = new AvatarRequestDescriptor(avatarUri,
                    sIconWidth, sIconHeight, OsUtil.isAtLeastL());
            final MediaRequest<ImageResource> imageRequest = descriptor.buildSyncMediaRequest(
                    context);

            synchronized (sPendingNotifications) {
                sPendingNotifications.add(state);
            }

            // Synchronously load the avatar.
            final ImageResource avatarImage =
                    MediaResourceManager.get().requestMediaResourceSync(imageRequest);
            if (avatarImage != null) {
                ImageResource avatarHiRes = null;
                try {
                    if (isWearCompanionAppInstalled()) {
                        // For Wear users, we need to request a high-res avatar image to use as the
                        // notification card background. If the sender has a contact photo, we'll
                        // request the display photo from the Contacts provider. Otherwise, we ask
                        // the local content provider for a hi-res version of the generic avatar
                        // (e.g. letter with colored background).
                        avatarHiRes = requestContactDisplayPhoto(context,
                                getDisplayPhotoUri(avatarUri));
                        if (avatarHiRes == null) {
                            final AvatarRequestDescriptor hiResDesc =
                                    new AvatarRequestDescriptor(avatarUri,
                                    sWearableImageWidth,
                                    sWearableImageHeight,
                                    false /* cropToCircle */,
                                    true /* isWearBackground */);
                            avatarHiRes = MediaResourceManager.get().requestMediaResourceSync(
                                    hiResDesc.buildSyncMediaRequest(context));
                        }
                    }

                    // We have to make copies of the bitmaps to hand to the NotificationManager
                    // because the bitmap in the ImageResource is managed and will automatically
                    // get released.
                    Bitmap avatarBitmap = Bitmap.createBitmap(avatarImage.getBitmap());
                    Bitmap avatarHiResBitmap = (avatarHiRes != null) ?
                            Bitmap.createBitmap(avatarHiRes.getBitmap()) : null;
                    sendNotification(state, avatarBitmap, avatarHiResBitmap);
                    return;
                } finally {
                    avatarImage.release();
                    if (avatarHiRes != null) {
                        avatarHiRes.release();
                    }
                }
            }
        }
        // We have no avatar. Post the notification anyway.
        sendNotification(state, null, null);
    }

    /**
     * Returns the thumbnailUri from the avatar URI, or null if avatar URI does not have thumbnail.
     */
    private static Uri getThumbnailUri(final Uri avatarUri) {
        Uri localUri = null;
        final String avatarType = AvatarUriUtil.getAvatarType(avatarUri);
        if (TextUtils.equals(avatarType, AvatarUriUtil.TYPE_LOCAL_RESOURCE_URI)) {
            localUri = AvatarUriUtil.getPrimaryUri(avatarUri);
        } else if (UriUtil.isLocalResourceUri(avatarUri)) {
            localUri = avatarUri;
        }
        if (localUri != null && localUri.getAuthority().equals(ContactsContract.AUTHORITY)) {
            // Contact photos are of the form: content://com.android.contacts/contacts/123/photo
            final List<String> pathParts = localUri.getPathSegments();
            if (pathParts.size() == 3 &&
                    pathParts.get(2).equals(Contacts.Photo.CONTENT_DIRECTORY)) {
                return localUri;
            }
        }
        return null;
    }

    /**
     * Returns the displayPhotoUri from the avatar URI, or null if avatar URI
     * does not have a displayPhotoUri.
     */
    private static Uri getDisplayPhotoUri(final Uri avatarUri) {
        final Uri thumbnailUri = getThumbnailUri(avatarUri);
        if (thumbnailUri == null) {
            return null;
        }
        final List<String> originalPaths = thumbnailUri.getPathSegments();
        final int originalPathsSize = originalPaths.size();
        final StringBuilder newPathBuilder = new StringBuilder();
        // Change content://com.android.contacts/contacts("_corp")/123/photo to
        // content://com.android.contacts/contacts("_corp")/123/display_photo
        for (int i = 0; i < originalPathsSize; i++) {
            newPathBuilder.append('/');
            if (i == 2) {
                newPathBuilder.append(ContactsContract.Contacts.Photo.DISPLAY_PHOTO);
            } else {
                newPathBuilder.append(originalPaths.get(i));
            }
        }
        return thumbnailUri.buildUpon().path(newPathBuilder.toString()).build();
    }

    private static ImageResource requestContactDisplayPhoto(final Context context,
            final Uri displayPhotoUri) {
        final UriImageRequestDescriptor bgDescriptor =
                new UriImageRequestDescriptor(displayPhotoUri,
                        sWearableImageWidth,
                        sWearableImageHeight,
                        false, /* allowCompression */
                        true, /* isStatic */
                        false /* cropToCircle */,
                        ImageUtils.DEFAULT_CIRCLE_BACKGROUND_COLOR /* circleBackgroundColor */,
                        ImageUtils.DEFAULT_CIRCLE_STROKE_COLOR /* circleStrokeColor */);
        return MediaResourceManager.get().requestMediaResourceSync(
                bgDescriptor.buildSyncMediaRequest(context));
    }

    private static void createMessageNotification(final boolean silent,
            final String conversationId) {
        final NotificationState state = MessageNotificationState.getNotificationState();
        final boolean softSound = DataModel.get().isNewMessageObservable(conversationId);
        if (state == null) {
            cancel(PendingIntentConstants.SMS_NOTIFICATION_ID);
            if (softSound && !TextUtils.isEmpty(conversationId)) {
                final Uri ringtoneUri = getNotificationRingtoneUriForConversationId(conversationId);
                playObservableConversationNotificationSound(ringtoneUri);
            }
            return;
        }
        processAndSend(state, silent, softSound);

        // The rest of the logic here is for supporting Android Wear devices, specifically for when
        // we are notifying about multiple conversations. In that case, the Inbox-style summary
        // notification (which we already processed above) appears on the phone (as it always has),
        // but wearables show per-conversation notifications, bundled together in a group.

        // It is valid to replace a notification group with another group with fewer conversations,
        // or even with one notification for a single conversation. In either case, we need to
        // explicitly cancel any children from the old group which are not being notified about now.
        final Context context = Factory.get().getApplicationContext();
        final ConversationIdSet oldGroupChildIds = getGroupChildIds(context);
        if (oldGroupChildIds != null && oldGroupChildIds.size() > 0) {
            cancelStaleGroupChildren(oldGroupChildIds, state);
        }

        // Send per-conversation notifications (if there are multiple conversations).
        final ConversationIdSet groupChildIds = new ConversationIdSet();
        if (state instanceof MultiConversationNotificationState) {
            for (final NotificationState child :
                ((MultiConversationNotificationState) state).mChildren) {
                processAndSend(child, true /* silent */, softSound);
                if (child.mConversationIds != null) {
                    groupChildIds.add(child.mConversationIds.first());
                }
            }
        }

        // Record the new set of group children.
        writeGroupChildIds(context, groupChildIds);
    }

    private static void updateBuilderAudioVibrate(final NotificationState state,
            final NotificationCompat.Builder notifBuilder, final boolean silent,
            final Uri ringtoneUri, final String conversationId) {
        int defaults = Notification.DEFAULT_LIGHTS;
        if (!silent) {
            final BuglePrefs prefs = Factory.get().getApplicationPrefs();
            final long latestNotificationTimestamp = prefs.getLong(
                    BuglePrefsKeys.LATEST_NOTIFICATION_MESSAGE_TIMESTAMP, Long.MIN_VALUE);
            final long latestReceivedTimestamp = state.getLatestReceivedTimestamp();
            prefs.putLong(
                    BuglePrefsKeys.LATEST_NOTIFICATION_MESSAGE_TIMESTAMP,
                    Math.max(latestNotificationTimestamp, latestReceivedTimestamp));
            if (latestReceivedTimestamp > latestNotificationTimestamp) {
                synchronized (mLock) {
                    // Find out the last time we dinged for this conversation
                    Long lastTime = sLastMessageDingTime.get(conversationId);
                    if (sTimeBetweenDingsMs == 0) {
                        sTimeBetweenDingsMs = BugleGservices.get().getInt(
                                BugleGservicesKeys.NOTIFICATION_TIME_BETWEEN_RINGS_SECONDS,
                                BugleGservicesKeys.NOTIFICATION_TIME_BETWEEN_RINGS_SECONDS_DEFAULT) *
                                    1000;
                    }
                    if (lastTime == null
                            || SystemClock.elapsedRealtime() - lastTime > sTimeBetweenDingsMs) {
                        sLastMessageDingTime.put(conversationId, SystemClock.elapsedRealtime());
                        notifBuilder.setSound(ringtoneUri);
                        if (shouldVibrate(state)) {
                            defaults |= Notification.DEFAULT_VIBRATE;
                        }
                    }
                }
            }
        }
        notifBuilder.setDefaults(defaults);
    }

    // TODO: this doesn't seem to be defined in NotificationCompat yet. Temporarily
    // define it here until it makes its way from Notification -> NotificationCompat.
    /**
     * Notification category: incoming direct message (SMS, instant message, etc.).
     */
    private static final String CATEGORY_MESSAGE = "msg";

    private static void sendNotification(final NotificationState notificationState,
            final Bitmap avatarIcon, final Bitmap avatarHiRes) {
        final Context context = Factory.get().getApplicationContext();
        if (notificationState.mCanceled) {
            if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
                LogUtil.d(TAG, "sendNotification: Notification already cancelled; dropping it");
            }
            return;
        }

        synchronized (sPendingNotifications) {
            if (sPendingNotifications.contains(notificationState)) {
                sPendingNotifications.remove(notificationState);
            }
        }

        notificationState.mNotificationBuilder
            .setSmallIcon(notificationState.getIcon())
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setColor(context.getResources().getColor(R.color.notification_accent_color))
//            .setPublicVersion(null)    // TODO: when/if we ever support different
                                         // text on the lockscreen, instead of "contents hidden"
            .setCategory(CATEGORY_MESSAGE);

        if (avatarIcon != null) {
            notificationState.mNotificationBuilder.setLargeIcon(avatarIcon);
        }

        if (notificationState.mParticipantContactUris != null &&
                notificationState.mParticipantContactUris.size() > 0) {
            for (final Uri contactUri : notificationState.mParticipantContactUris) {
                notificationState.mNotificationBuilder.addPerson(contactUri.toString());
            }
        }

        final Uri attachmentUri = notificationState.getAttachmentUri();
        final String attachmentType = notificationState.getAttachmentType();
        Bitmap attachmentBitmap = null;

        // For messages with photo/video attachment, request an image to show in the notification.
        if (attachmentUri != null && notificationState.mNotificationStyle != null &&
                (notificationState.mNotificationStyle instanceof
                        NotificationCompat.BigPictureStyle) &&
                        (ContentType.isImageType(attachmentType) ||
                                ContentType.isVideoType(attachmentType))) {
            final boolean isVideo = ContentType.isVideoType(attachmentType);

            MediaRequest<ImageResource> imageRequest;
            if (isVideo) {
                Assert.isTrue(VideoThumbnailRequest.shouldShowIncomingVideoThumbnails());
                final MessagePartVideoThumbnailRequestDescriptor videoDescriptor =
                        new MessagePartVideoThumbnailRequestDescriptor(attachmentUri);
                imageRequest = videoDescriptor.buildSyncMediaRequest(context);
            } else {
                final UriImageRequestDescriptor imageDescriptor =
                        new UriImageRequestDescriptor(attachmentUri,
                            sWearableImageWidth,
                            sWearableImageHeight,
                            false /* allowCompression */,
                            true /* isStatic */,
                            false /* cropToCircle */,
                            ImageUtils.DEFAULT_CIRCLE_BACKGROUND_COLOR /* circleBackgroundColor */,
                            ImageUtils.DEFAULT_CIRCLE_STROKE_COLOR /* circleStrokeColor */);
                imageRequest = imageDescriptor.buildSyncMediaRequest(context);
            }
            final ImageResource imageResource =
                    MediaResourceManager.get().requestMediaResourceSync(imageRequest);
            if (imageResource != null) {
                try {
                    // Copy the bitmap, because the one in the ImageResource is managed by
                    // MediaResourceManager.
                    Bitmap imageResourceBitmap = imageResource.getBitmap();
                    Config config = imageResourceBitmap.getConfig();

                    // Make sure our bitmap has a valid format.
                    if (config == null) {
                        config = Bitmap.Config.ARGB_8888;
                    }
                    attachmentBitmap = imageResourceBitmap.copy(config, true);
                } finally {
                    imageResource.release();
                }
            }
        }

        fireOffNotification(notificationState, attachmentBitmap, avatarIcon, avatarHiRes);
    }

    private static void fireOffNotification(final NotificationState notificationState,
            final Bitmap attachmentBitmap, final Bitmap avatarBitmap, Bitmap avatarHiResBitmap) {
        if (notificationState.mCanceled) {
            if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
                LogUtil.v(TAG, "Firing off notification, but notification already canceled");
            }
            return;
        }

        final Context context = Factory.get().getApplicationContext();

        if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
            LogUtil.v(TAG, "MMS picture loaded, bitmap: " + attachmentBitmap);
        }

        final NotificationCompat.Builder notifBuilder = notificationState.mNotificationBuilder;
        notifBuilder.setStyle(notificationState.mNotificationStyle);
        notifBuilder.setColor(context.getResources().getColor(R.color.notification_accent_color));

        final WearableExtender wearableExtender = new WearableExtender();
        setWearableGroupOptions(notifBuilder, notificationState);

        if (avatarHiResBitmap != null) {
            wearableExtender.setBackground(avatarHiResBitmap);
        } else if (avatarBitmap != null) {
            // Nothing to do here; we already set avatarBitmap as the notification icon
        } else {
            final Bitmap defaultBackground = BitmapFactory.decodeResource(
                    context.getResources(), R.drawable.bg_sms);
            wearableExtender.setBackground(defaultBackground);
        }

        if (notificationState instanceof MultiMessageNotificationState) {
            if (attachmentBitmap != null) {
                // When we've got a picture attachment, we do some switcheroo trickery. When
                // the notification is expanded, we show the picture as a bigPicture. The small
                // icon shows the sender's avatar. When that same notification is collapsed, the
                // picture is shown in the location where the avatar is normally shown. The lines
                // below make all that happen.

                // Here we're taking the picture attachment and making a small, scaled, center
                // cropped version of the picture we can stuff into the place where the avatar
                // goes when the notification is collapsed.
                final Bitmap smallBitmap = ImageUtils.scaleCenterCrop(attachmentBitmap, sIconWidth,
                        sIconHeight);
                ((NotificationCompat.BigPictureStyle) notificationState.mNotificationStyle)
                    .bigPicture(attachmentBitmap)
                    .bigLargeIcon(avatarBitmap);
                notificationState.mNotificationBuilder.setLargeIcon(smallBitmap);

                // Add a wearable page with no visible card so you can more easily see the photo.
                final NotificationCompat.Builder photoPageNotifBuilder =
                        new NotificationCompat.Builder(Factory.get().getApplicationContext(), NotificationChannelUtil.CONVERSATIONS);
                final WearableExtender photoPageWearableExtender = new WearableExtender();
                photoPageWearableExtender.setHintShowBackgroundOnly(true);
                if (attachmentBitmap != null) {
                    final Bitmap wearBitmap = ImageUtils.scaleCenterCrop(attachmentBitmap,
                            sWearableImageWidth, sWearableImageHeight);
                    photoPageWearableExtender.setBackground(wearBitmap);
                }
                photoPageNotifBuilder.extend(photoPageWearableExtender);
                wearableExtender.addPage(photoPageNotifBuilder.build());
            }

            maybeAddWearableConversationLog(wearableExtender,
                    (MultiMessageNotificationState) notificationState);
            addDownloadMmsAction(notifBuilder, wearableExtender, notificationState);
            addWearableVoiceReplyAction(wearableExtender, notificationState);
        }

        // Apply the wearable options and build & post the notification
        notifBuilder.extend(wearableExtender);
        doNotify(notifBuilder.build(), notificationState);
    }

    private static void setWearableGroupOptions(final NotificationCompat.Builder notifBuilder,
            final NotificationState notificationState) {
        final String groupKey = "groupkey";
        if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
            LogUtil.v(TAG, "Group key (for wearables)=" + groupKey);
        }
        if (notificationState instanceof MultiConversationNotificationState) {
            notifBuilder.setGroup(groupKey).setGroupSummary(true);
        } else if (notificationState instanceof BundledMessageNotificationState) {
            final int order = ((BundledMessageNotificationState) notificationState).mGroupOrder;
            // Convert the order to a zero-padded string ("00", "01", "02", etc).
            // The Wear library orders notifications within a bundle lexicographically
            // by the sort key, hence the need for zeroes to preserve the ordering.
            final String sortKey = String.format(Locale.US, "%02d", order);
            notifBuilder.setGroup(groupKey).setSortKey(sortKey);
        }
    }

    private static void maybeAddWearableConversationLog(
            final WearableExtender wearableExtender,
            final MultiMessageNotificationState notificationState) {
        if (!isWearCompanionAppInstalled()) {
            return;
        }
        final String convId = notificationState.mConversationIds.first();
        ConversationLineInfo convInfo = notificationState.mConvList.mConvInfos.get(0);
        final Notification page = MessageNotificationState.buildConversationPageForWearable(
                convId,
                convInfo.mParticipantCount);
        if (page != null) {
            wearableExtender.addPage(page);
        }
    }

    private static void addWearableVoiceReplyAction(
            final WearableExtender wearableExtender, final NotificationState notificationState) {
        if (!(notificationState instanceof MultiMessageNotificationState)) {
            return;
        }
        final MultiMessageNotificationState multiMessageNotificationState =
                (MultiMessageNotificationState) notificationState;
        final Context context = Factory.get().getApplicationContext();

        final String conversationId = notificationState.mConversationIds.first();
        final ConversationLineInfo convInfo =
                multiMessageNotificationState.mConvList.mConvInfos.get(0);
        final String selfId = convInfo.mSelfParticipantId;

        final boolean requiresMms =
                MmsSmsUtils.getRequireMmsForEmailAddress(
                        convInfo.mIncludeEmailAddress, convInfo.mSubId) ||
                (convInfo.mIsGroup && MmsUtils.groupMmsEnabled(convInfo.mSubId));

        final int requestCode = multiMessageNotificationState.getReplyIntentRequestCode();
        final PendingIntent replyPendingIntent = UIIntents.get()
                .getPendingIntentForSendingMessageToConversation(context,
                        conversationId, selfId, requiresMms, requestCode);

        final int replyLabelRes = requiresMms ? R.string.notification_reply_via_mms :
            R.string.notification_reply_via_sms;

        final NotificationCompat.Action.Builder actionBuilder =
                new NotificationCompat.Action.Builder(R.drawable.ic_wear_reply,
                        context.getString(replyLabelRes), replyPendingIntent);
        final String[] choices = context.getResources().getStringArray(
                R.array.notification_reply_choices);
        final RemoteInput remoteInput = new RemoteInput.Builder(Intent.EXTRA_TEXT).setLabel(
                context.getString(R.string.notification_reply_prompt)).
                setChoices(choices)
                .build();
        actionBuilder.addRemoteInput(remoteInput);
        wearableExtender.addAction(actionBuilder.build());
    }

    private static void addDownloadMmsAction(final NotificationCompat.Builder notifBuilder,
            final WearableExtender wearableExtender, final NotificationState notificationState) {
        if (!(notificationState instanceof MultiMessageNotificationState)) {
            return;
        }
        final MultiMessageNotificationState multiMessageNotificationState =
                (MultiMessageNotificationState) notificationState;
        final ConversationLineInfo convInfo =
                multiMessageNotificationState.mConvList.mConvInfos.get(0);
        if (!convInfo.getDoesLatestMessageNeedDownload()) {
            return;
        }
        final String messageId = convInfo.getLatestMessageId();
        if (messageId == null) {
            // No message Id, no download for you
            return;
        }
        final Context context = Factory.get().getApplicationContext();
        final PendingIntent downloadPendingIntent =
                RedownloadMmsAction.getPendingIntentForRedownloadMms(context, messageId);

        final NotificationCompat.Action.Builder actionBuilder =
                new NotificationCompat.Action.Builder(R.drawable.ic_file_download_light,
                        context.getString(R.string.notification_download_mms),
                        downloadPendingIntent);
        final NotificationCompat.Action downloadAction = actionBuilder.build();
        notifBuilder.addAction(downloadAction);

        // Support the action on a wearable device as well
        wearableExtender.addAction(downloadAction);
    }

    private static synchronized void doNotify(final Notification notification,
            final NotificationState notificationState) {
        if (notification == null) {
            return;
        }
        final int type = notificationState.mType;
        final ConversationIdSet conversationIds = notificationState.mConversationIds;
        final boolean isBundledNotification =
                (notificationState instanceof BundledMessageNotificationState);

        // Mark the notification as finished
        notificationState.mCanceled = true;

        final NotificationManagerCompat notificationManager =
                NotificationManagerCompat.from(Factory.get().getApplicationContext());
        // Only need conversationId for tags with a single conversation.
        String conversationId = null;
        if (conversationIds != null && conversationIds.size() == 1) {
            conversationId = conversationIds.first();
        }
        final String notificationTag = buildNotificationTag(type,
                conversationId, isBundledNotification);

        notification.flags |= Notification.FLAG_AUTO_CANCEL;
        notification.defaults |= Notification.DEFAULT_LIGHTS;

        notificationManager.notify(notificationTag, type, notification);

        LogUtil.i(TAG, "Notifying for conversation " + conversationId + "; "
                + "tag = " + notificationTag + ", type = " + type);
    }

    // This is the message string used in each line of an inboxStyle notification.
    // TODO: add attachment type
    static CharSequence formatInboxMessage(final String sender,
            final CharSequence message, final Uri attachmentUri, final String attachmentType) {
      final Context context = Factory.get().getApplicationContext();
      final TextAppearanceSpan notificationSenderSpan = new TextAppearanceSpan(
              context, R.style.NotificationSenderText);

      final TextAppearanceSpan notificationTertiaryText = new TextAppearanceSpan(
              context, R.style.NotificationTertiaryText);

      final SpannableStringBuilder spannableStringBuilder = new SpannableStringBuilder();
      if (!TextUtils.isEmpty(sender)) {
          spannableStringBuilder.append(sender);
          spannableStringBuilder.setSpan(notificationSenderSpan, 0, sender.length(), 0);
      }
      final String separator = context.getString(R.string.notification_separator);

      if (!TextUtils.isEmpty(message)) {
          if (spannableStringBuilder.length() > 0) {
              spannableStringBuilder.append(separator);
          }
          final int start = spannableStringBuilder.length();
          spannableStringBuilder.append(message);
          spannableStringBuilder.setSpan(notificationTertiaryText, start,
                  start + message.length(), 0);
      }
      if (attachmentUri != null) {
          if (spannableStringBuilder.length() > 0) {
              spannableStringBuilder.append(separator);
          }
          spannableStringBuilder.append(formatAttachmentTag(null, attachmentType));
      }
      return spannableStringBuilder;
    }

    protected static CharSequence buildColonSeparatedMessage(
            final String title, final CharSequence content, final Uri attachmentUri,
            final String attachmentType) {
        return buildBoldedMessage(title, content, attachmentUri, attachmentType,
                R.string.notification_ticker_separator);
    }

    protected static CharSequence buildSpaceSeparatedMessage(
            final String title, final CharSequence content, final Uri attachmentUri,
            final String attachmentType) {
        return buildBoldedMessage(title, content, attachmentUri, attachmentType,
                R.string.notification_space_separator);
    }

    /**
     * buildBoldedMessage - build a formatted message where the title is bold, there's a
     * separator, then the message.
     */
    private static CharSequence buildBoldedMessage(
            final String title, final CharSequence message, final Uri attachmentUri,
            final String attachmentType,
            final int separatorId) {
        final Context context = Factory.get().getApplicationContext();
        final SpannableStringBuilder spanBuilder = new SpannableStringBuilder();

        // Boldify the title (which is the sender's name)
        if (!TextUtils.isEmpty(title)) {
            spanBuilder.append(title);
            spanBuilder.setSpan(new StyleSpan(Typeface.BOLD), 0, title.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (!TextUtils.isEmpty(message)) {
            if (spanBuilder.length() > 0) {
                spanBuilder.append(context.getString(separatorId));
            }
            spanBuilder.append(message);
        }
        if (attachmentUri != null) {
            if (spanBuilder.length() > 0) {
                final String separator = context.getString(R.string.notification_separator);
                spanBuilder.append(separator);
            }
            spanBuilder.append(formatAttachmentTag(null, attachmentType));
        }
        return spanBuilder;
    }

    static CharSequence formatAttachmentTag(final String author, final String attachmentType) {
        final Context context = Factory.get().getApplicationContext();
            final TextAppearanceSpan notificationSecondaryText = new TextAppearanceSpan(
                    context, R.style.NotificationSecondaryText);
        final SpannableStringBuilder spannableStringBuilder = new SpannableStringBuilder();
        if (!TextUtils.isEmpty(author)) {
            final TextAppearanceSpan notificationSenderSpan = new TextAppearanceSpan(
                    context, R.style.NotificationSenderText);
            spannableStringBuilder.append(author);
            spannableStringBuilder.setSpan(notificationSenderSpan, 0, author.length(), 0);
            final String separator = context.getString(R.string.notification_separator);
            spannableStringBuilder.append(separator);
        }
        final int start = spannableStringBuilder.length();
        // The default attachment type is an image, since that's what was originally
        // supported. When there's no content type, assume it's an image.
        int message = R.string.notification_picture;
        if (ContentType.isAudioType(attachmentType)) {
            message = R.string.notification_audio;
        } else if (ContentType.isVideoType(attachmentType)) {
            message = R.string.notification_video;
        } else if (ContentType.isVCardType(attachmentType)) {
            message = R.string.notification_vcard;
        }
        spannableStringBuilder.append(context.getText(message));
        spannableStringBuilder.setSpan(notificationSecondaryText, start,
                spannableStringBuilder.length(), 0);
        return spannableStringBuilder;
    }

    /**
     * Play the observable conversation notification sound (it's the regular notification sound, but
     * played at half-volume)
     */
    private static void playObservableConversationNotificationSound(final Uri ringtoneUri) {
        final Context context = Factory.get().getApplicationContext();
        final AudioManager audioManager = (AudioManager) context
                .getSystemService(Context.AUDIO_SERVICE);
        final boolean silenced =
                audioManager.getRingerMode() != AudioManager.RINGER_MODE_NORMAL;
        if (silenced) {
             return;
        }

        final NotificationPlayer player = new NotificationPlayer(LogUtil.BUGLE_TAG);
        player.play(ringtoneUri, false,
                AudioManager.STREAM_NOTIFICATION,
                OBSERVABLE_CONVERSATION_NOTIFICATION_VOLUME);

        // Stop the sound after five seconds to handle continuous ringtones
        ThreadUtil.getMainThreadHandler().postDelayed(new Runnable() {
            @Override
            public void run() {
                player.stop();
            }
        }, 5000);
    }

    public static boolean isWearCompanionAppInstalled() {
        boolean found = false;
        try {
            Factory.get().getApplicationContext().getPackageManager()
                    .getPackageInfo(WEARABLE_COMPANION_APP_PACKAGE, 0);
            found = true;
        } catch (final NameNotFoundException e) {
            // Ignore; found is already false
        }
        return found;
    }

    /**
     * When we go to the conversation list, call this to mark all messages as seen. That means
     * we won't show a notification again for the same message.
     */
    public static void markAllMessagesAsSeen() {
        MarkAsSeenAction.markAllAsSeen();
        resetLastMessageDing(null);     // reset the ding timeout for all conversations
    }

    /**
     * When we open a particular conversation, call this to mark all messages as read.
     */
    public static void markMessagesAsRead(final String conversationId) {
        MarkAsReadAction.markAsRead(conversationId);
        resetLastMessageDing(conversationId);
    }

    /**
     * Returns the conversation ids of all active, grouped notifications, or
     * {code null} if no notifications are currently active and grouped.
     */
    private static ConversationIdSet getGroupChildIds(final Context context) {
        final String prefKey = context.getString(R.string.notifications_group_children_key);
        final String groupChildIdsText = BuglePrefs.getApplicationPrefs().getString(prefKey, "");
        if (!TextUtils.isEmpty(groupChildIdsText)) {
            return ConversationIdSet.createSet(groupChildIdsText);
        } else {
            return null;
        }
    }

    /**
     * Records the conversation ids of the currently active grouped notifications.
     */
    private static void writeGroupChildIds(final Context context,
            final ConversationIdSet childIds) {
        final ConversationIdSet oldChildIds = getGroupChildIds(context);
        if (childIds.equals(oldChildIds)) {
            return;
        }
        final String prefKey = context.getString(R.string.notifications_group_children_key);
        BuglePrefs.getApplicationPrefs().putString(prefKey, childIds.getDelimitedString());
    }

    /**
     * Reset the timer for a notification ding on a particular conversation or all conversations.
     */
    public static void resetLastMessageDing(final String conversationId) {
        synchronized (mLock) {
            if (TextUtils.isEmpty(conversationId)) {
                // reset all conversation dings
                sLastMessageDingTime.clear();
            } else {
                sLastMessageDingTime.remove(conversationId);
            }
        }
    }

    public static void notifyEmergencySmsFailed(final String emergencyNumber,
            final String conversationId) {
        final Context context = Factory.get().getApplicationContext();

        final CharSequence line1 = MessageNotificationState.applyWarningTextColor(context,
                context.getString(R.string.notification_emergency_send_failure_line1,
                emergencyNumber));
        final String line2 = context.getString(R.string.notification_emergency_send_failure_line2,
                emergencyNumber);
        final PendingIntent destinationIntent = UIIntents.get()
                .getPendingIntentForConversationActivity(context, conversationId, null /* draft */);

        final NotificationCompat.Builder builder =
                new NotificationCompat.Builder(context, NotificationChannelUtil.ALERTS);
        builder.setTicker(line1)
                .setContentTitle(line1)
                .setContentText(line2)
                .setStyle(new NotificationCompat.BigTextStyle(builder).bigText(line2))
                .setSmallIcon(R.drawable.ic_failed_light)
                .setContentIntent(destinationIntent)
                .setSound(UriUtil.getUriForResourceId(context, R.raw.message_failure));

        final String tag = context.getPackageName() + ":emergency_sms_error";
        NotificationManagerCompat.from(context).notify(
                tag,
                PendingIntentConstants.MSG_SEND_ERROR,
                builder.build());
    }
}

