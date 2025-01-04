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

package com.android.messaging.util;


/**
 * List of gservices keys and default values which are in use.
 */
public final class BugleGservicesKeys {
    private BugleGservicesKeys() {}   // do not instantiate

    /**
     * Whether to enable extra debugging features on the client. Default is
     * {@value #ENABLE_DEBUGGING_FEATURES_DEFAULT}.
     */
    public static final String ENABLE_DEBUGGING_FEATURES
            = "bugle_debugging";
    public static final boolean ENABLE_DEBUGGING_FEATURES_DEFAULT
            = false;

    /**
     * Whether to enable saving extra logs. Default is {@value #ENABLE_LOG_SAVER_DEFAULT}.
     */
    public static final String ENABLE_LOG_SAVER = "bugle_logsaver";
    public static final boolean ENABLE_LOG_SAVER_DEFAULT = false;

    /**
     * Time in milliseconds of initial (attempt 1) resend backoff for failing messages
     */
    public static final String INITIAL_MESSAGE_RESEND_DELAY_MS = "bugle_resend_delay_in_millis";
    public static final long INITIAL_MESSAGE_RESEND_DELAY_MS_DEFAULT = 5 * 1000L;

    /**
     * Time in milliseconds of max resend backoff for failing messages
     */
    public static final String MAX_MESSAGE_RESEND_DELAY_MS = "bugle_max_resend_delay_in_millis";
    public static final long MAX_MESSAGE_RESEND_DELAY_MS_DEFAULT = 2 * 60 * 60 * 1000L;

    /**
     * Time in milliseconds of resend window for unsent messages
     */
    public static final String MESSAGE_RESEND_TIMEOUT_MS = "bugle_resend_timeout_in_millis";
    public static final long MESSAGE_RESEND_TIMEOUT_MS_DEFAULT = 20 * 60 * 1000L;

    /**
     * Time in milliseconds of download window for new mms notifications
     */
    public static final String MESSAGE_DOWNLOAD_TIMEOUT_MS = "bugle_download_timeout_in_millis";
    public static final long MESSAGE_DOWNLOAD_TIMEOUT_MS_DEFAULT = 20 * 60 * 1000L;

    /**
     * Time in milliseconds for SMS send timeout
     */
    public static final String SMS_SEND_TIMEOUT_IN_MILLIS = "bugle_sms_send_timeout";
    public static final long SMS_SEND_TIMEOUT_IN_MILLIS_DEFAULT = 5 * 60 * 1000L;

    /**
     * Keys to control the SMS sync batch size. The batch size is defined by the number
     * of messages that incur local database change, e.g. importing messages and
     * deleting messages.
     *
     * 1. The minimum size for a batch and
     * 2. The maximum size for a batch.
     * The first batch uses the minimum size for probing. Set this to a small number for the
     * first sync batch to make sure the user sees SMS showing up in conversations quickly
     * Use these two settings to limit the number of messages to sync in each batch.
     * The minimum is to make sure we always make progress during sync. The maximum is
     * to limit the sync batch size within a reasonable range (needs to fit in an intent).
     * 3. The time limit controls the limit of time duration of a sync batch. We can
     * not control this directly due to the batching nature of sync. So this provides
     * heuristics. We may sometime exceeds the limit if our calculation is off due to
     * whatever reasons. Keeping this low ensures responsiveness of the application.
     * 4. The limit on number of total messages to scan in one batch.
     */
    public static final String SMS_SYNC_BATCH_SIZE_MIN =
            "bugle_sms_sync_batch_size_min";
    public static final int SMS_SYNC_BATCH_SIZE_MIN_DEFAULT = 80;
    public static final String SMS_SYNC_BATCH_SIZE_MAX =
            "bugle_sms_sync_batch_size_max";
    public static final int SMS_SYNC_BATCH_SIZE_MAX_DEFAULT = 1000;
    public static final String SMS_SYNC_BATCH_TIME_LIMIT_MILLIS =
            "bugle_sms_sync_batch_time_limit";
    public static final long SMS_SYNC_BATCH_TIME_LIMIT_MILLIS_DEFAULT = 400;
    public static final String SMS_SYNC_BATCH_MAX_MESSAGES_TO_SCAN =
            "bugle_sms_sync_batch_max_messages_to_scan";
    public static final int SMS_SYNC_BATCH_MAX_MESSAGES_TO_SCAN_DEFAULT =
            SMS_SYNC_BATCH_SIZE_MAX_DEFAULT * 4;

    /**
     * Time in ms for sync to backoff from "now" to the latest message that will be sync'd.
     *
     * This controls the best case for how out of date the application will appear to be
     * when bringing in changes made outside the application. It also represents a buffer
     * to ensure that sync doesn't trigger based on changes made within the application.
     */
    public static final String SMS_SYNC_BACKOFF_TIME_MILLIS =
            "bugle_sms_sync_backoff_time";
    public static final long SMS_SYNC_BACKOFF_TIME_MILLIS_DEFAULT = 5000L;

    /**
     * Just in case if we fall into a loop of full sync -> still not synchronized -> full sync ...
     * This forces a backoff time so that we at most do full sync once a while (an hour by default)
     */
    public static final String SMS_FULL_SYNC_BACKOFF_TIME_MILLIS =
            "bugle_sms_full_sync_backoff_time";
    public static final long SMS_FULL_SYNC_BACKOFF_TIME_MILLIS_DEFAULT = 60 * 60 * 1000;

    /**
     * Time duration to retain the most recent SMS messages for SMS storage purging
     *
     * Format:
     *   <number>(w|m|y)
     * Examples:
     *   "1y" -- a year
     *   "2w" -- two weeks
     *   "6m" -- six months
     */
    public static final String SMS_STORAGE_PURGING_MESSAGE_RETAINING_DURATION =
            "bugle_sms_storage_purging_message_retaining_duration";
    public static final String SMS_STORAGE_PURGING_MESSAGE_RETAINING_DURATION_DEFAULT = "1m";

    /**
     * MMS UA profile url.
     *
     * This is used on all Android devices running Hangout, so cannot just host the profile of the
     * latest and greatest phones. However, if we're on KitKat or below we can't get the phone's
     * UA profile and thus we need to send them the default url.
     */
    public static final String MMS_UA_PROFILE_URL =
            "bugle_mms_uaprofurl";
    public static final String MMS_UA_PROFILE_URL_DEFAULT =
            "http://www.gstatic.com/android/sms/mms_ua_profile.xml";

    /**
     * List of known SMS system messages that we will ignore (no deliver, no abort) so that the
     * user doesn't see them and the appropriate app is able to handle them. We are delivering
     * these as a \n delimited list of patterns, however we should eventually move to storing
     * them with the per-carrier mms config xml file.
     */
    public static final String SMS_IGNORE_MESSAGE_REGEX =
            "bugle_sms_ignore_message_regex";
    public static final String SMS_IGNORE_MESSAGE_REGEX_DEFAULT = "";

    /**
     * When receiving or importing an mms, limit the length of text to this limit. Huge blocks
     * of text can cause the app to hang/ANR/or crash in native text code..
     */
    public static final String MMS_TEXT_LIMIT = "bugle_mms_text_limit";
    public static final int MMS_TEXT_LIMIT_DEFAULT = 2000;

    /**
     * Max number of attachments the user may add to a single message.
     */
    public static final String MMS_ATTACHMENT_LIMIT = "bugle_mms_attachment_limit";
    public static final int MMS_ATTACHMENT_LIMIT_DEFAULT = 10;

    /**
     * The max number of messages to show in a single conversation notification. We always show
     * the most recent message. If this value is >1, we may also include prior messages as well.
     */
    public static final String MAX_MESSAGES_IN_CONVERSATION_NOTIFICATION =
            "bugle_max_messages_in_conversation_notification";
    public static final int MAX_MESSAGES_IN_CONVERSATION_NOTIFICATION_DEFAULT = 7;

    /**
     * Time (in seconds) between notification ringing for incoming messages of the same
     * conversation. We won't ding more often than this value for messages coming in at a high rate.
     */
    public static final String NOTIFICATION_TIME_BETWEEN_RINGS_SECONDS
            = "bugle_notification_time_between_rings_seconds";
    public static final int NOTIFICATION_TIME_BETWEEN_RINGS_SECONDS_DEFAULT = 10;

    /**
     * The max number of messages to show in a single conversation notification, when a wearable
     * device (i.e. smartwatch) is paired with the phone. Watches have a different UX model and
     * less screen real estate, so we may want to optimize for that case. Note that if a wearable
     * is paired, this value will apply to notifications as shown both on the watch and the phone.
     */
    public static final String MAX_MESSAGES_IN_CONVERSATION_NOTIFICATION_WITH_WEARABLE =
            "bugle_max_messages_in_conversation_notification_with_wearable";
    public static final int MAX_MESSAGES_IN_CONVERSATION_NOTIFICATION_WITH_WEARABLE_DEFAULT = 1;

    /**
     * Regular expression to match against query.  If it matches then display
     * the query plan for this query.
     */
    public static final String EXPLAIN_QUERY_PLAN_REGEXP = "bugle_query_plan_regexp";

    /**
     * Whether asserts are fatal on user/userdebug builds.
     * Default is {@value #ASSERTS_FATAL_DEFAULT}.
     */
    public static final String ASSERTS_FATAL = "bugle_asserts_fatal";
    public static final boolean ASSERTS_FATAL_DEFAULT = false;

    /**
     * Whether to use API for sending/downloading MMS (if present, true for L).
     * Default is {@value #USE_MMS_API_IF_PRESENT_DEFAULT}.
     */
    public static final String USE_MMS_API_IF_PRESENT = "bugle_use_mms_api";
    public static final boolean USE_MMS_API_IF_PRESENT_DEFAULT = true;

    /**
     * Whether to always auto-complete email addresses for sending MMS. By default, Bugle starts
     * to auto-complete after the user has typed the "@" character.
     * Default is (@value ALWAYS_AUTOCOMPLETE_EMAIL_ADDRESS_DEFAULT}.
     */
    public static final String ALWAYS_AUTOCOMPLETE_EMAIL_ADDRESS =
            "bugle_always_autocomplete_email_address";
    public static final boolean ALWAYS_AUTOCOMPLETE_EMAIL_ADDRESS_DEFAULT = false;

    // We typically request an aspect ratio close the the screen size, but some cameras can be
    // flaky and not work well in certain aspect ratios.  This allows us to guide the CameraManager
    // to pick a more reliable aspect ratio.  The value is a float like 1.333f or 1.777f.  There is
    // no hard coded default because the default is the screen aspect ratio.
    public static final String CAMERA_ASPECT_RATIO = "bugle_camera_aspect_ratio";

    /**
     * The recent time range within which we should check MMS WAP Push duplication
     * If the value is 0, it signals that we should use old dedup algorithm for wap push
     */
    public static final String MMS_WAP_PUSH_DEDUP_TIME_LIMIT_SECS =
            "bugle_mms_wap_push_dedup_time_limit_secs";
    public static final long MMS_WAP_PUSH_DEDUP_TIME_LIMIT_SECS_DEFAULT = 7 * 24 * 3600; // 7 days

    /**
     * Whether to use persistent, on-disk LogSaver
     */
    public static final String PERSISTENT_LOGSAVER = "bugle_persistent_logsaver";
    public static final boolean PERSISTENT_LOGSAVER_DEFAULT = false;

    /**
     * For in-memory LogSaver, what's the size of memory buffer in number of records
     */
    public static final String IN_MEMORY_LOGSAVER_RECORD_COUNT =
            "bugle_in_memory_logsaver_record_count";
    public static final int IN_MEMORY_LOGSAVER_RECORD_COUNT_DEFAULT = 500;

    /**
     * For on-disk LogSaver, what's the size of file rotation set
     */
    public static final String PERSISTENT_LOGSAVER_ROTATION_SET_SIZE =
            "bugle_persistent_logsaver_rotation_set_size";
    public static final int PERSISTENT_LOGSAVER_ROTATION_SET_SIZE_DEFAULT = 8;

    /**
     * For on-disk LogSaver, what's the byte limit of a single log file
     */
    public static final String PERSISTENT_LOGSAVER_FILE_LIMIT_BYTES =
            "bugle_persistent_logsaver_file_limit";
    public static final int PERSISTENT_LOGSAVER_FILE_LIMIT_BYTES_DEFAULT = 256 * 1024; // 256KB

    /**
     * We concatenate all text parts in an MMS to form the message text. This specifies
     * the separator between the combinated text parts. Default is ' ' (space).
     */
    public static final String MMS_TEXT_CONCAT_SEPARATOR = "bugle_mms_text_concat_separator";
    public static final String MMS_TEXT_CONCAT_SEPARATOR_DEFAULT = " ";

    /**
     * Whether to enable transcoding GIFs. We sometimes need to compress GIFs to make them small
     * enough to send via MMS (which often limits messages to 1 MB in size).
     */
    public static final String ENABLE_GIF_TRANSCODING = "bugle_gif_transcoding";
    public static final boolean ENABLE_GIF_TRANSCODING_DEFAULT = true;
}
