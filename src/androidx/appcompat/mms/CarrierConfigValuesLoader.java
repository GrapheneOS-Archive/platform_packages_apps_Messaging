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

package androidx.appcompat.mms;

import android.os.Bundle;

/**
 * Loader for carrier dependent configuration values
 */
public interface CarrierConfigValuesLoader {
    /**
     * Get the carrier config values in a bundle
     *
     * @param subId the associated subscription ID for the carrier configuration
     * @return a bundle of all the values
     */
    Bundle get(int subId);

    // Configuration keys and default values

    /** Boolean value: if MMS is enabled */
    public static final String CONFIG_ENABLED_MMS = "enabledMMS";
    public static final boolean CONFIG_ENABLED_MMS_DEFAULT = true;
    /**
     * Boolean value: if transaction ID should be appended to
     * the download URL of a single segment WAP push message
     */
    public static final String CONFIG_ENABLED_TRANS_ID = "enabledTransID";
    public static final boolean CONFIG_ENABLED_TRANS_ID_DEFAULT = false;
    /**
     * Boolean value: if acknowledge or notify response to a download
     * should be sent to the WAP push message's download URL
     */
    public static final String CONFIG_ENABLED_NOTIFY_WAP_MMSC = "enabledNotifyWapMMSC";
    public static final boolean CONFIG_ENABLED_NOTIFY_WAP_MMSC_DEFAULT = false;
    /**
     * Boolean value: if phone number alias can be used
     */
    public static final String CONFIG_ALIAS_ENABLED = "aliasEnabled";
    public static final boolean CONFIG_ALIAS_ENABLED_DEFAULT = false;
    /**
     * Boolean value: if audio is allowed in attachment
     */
    public static final String CONFIG_ALLOW_ATTACH_AUDIO = "allowAttachAudio";
    public static final boolean CONFIG_ALLOW_ATTACH_AUDIO_DEFAULT = true;
    /**
     * Boolean value: if true, long sms messages are always sent as multi-part sms
     * messages, with no checked limit on the number of segments. If false, then
     * as soon as the user types a message longer than a single segment (i.e. 140 chars),
     * the message will turn into and be sent as an mms message or separate,
     * independent SMS messages (dependent on CONFIG_SEND_MULTIPART_SMS_AS_SEPARATE_MESSAGES flag).
     * This feature exists for carriers that don't support multi-part sms.
     */
    public static final String CONFIG_ENABLE_MULTIPART_SMS = "enableMultipartSMS";
    public static final boolean CONFIG_ENABLE_MULTIPART_SMS_DEFAULT = true;
    /**
     * Boolean value: if SMS delivery report is supported
     */
    public static final String CONFIG_ENABLE_SMS_DELIVERY_REPORTS = "enableSMSDeliveryReports";
    public static final boolean CONFIG_ENABLE_SMS_DELIVERY_REPORTS_DEFAULT = true;
    /**
     * Boolean value: if group MMS is supported
     */
    public static final String CONFIG_ENABLE_GROUP_MMS = "enableGroupMms";
    public static final boolean CONFIG_ENABLE_GROUP_MMS_DEFAULT = true;
    /**
     * Boolean value: if the content_disposition field of an MMS part should be parsed
     * Check wap-230-wsp-20010705-a.pdf, chapter 8.4.2.21. Most carriers support it except some.
     */
    public static final String CONFIG_SUPPORT_MMS_CONTENT_DISPOSITION =
            "supportMmsContentDisposition";
    public static final boolean CONFIG_SUPPORT_MMS_CONTENT_DISPOSITION_DEFAULT = true;
    /**
     * Boolean value: if the sms app should support a link to the system settings
     * where amber alerts are configured.
     */
    public static final String CONFIG_CELL_BROADCAST_APP_LINKS = "config_cellBroadcastAppLinks";
    public static final boolean CONFIG_CELL_BROADCAST_APP_LINKS_DEFAULT = true;
    /**
     * Boolean value: if multipart SMS should be sent as separate SMS messages
     */
    public static final String CONFIG_SEND_MULTIPART_SMS_AS_SEPARATE_MESSAGES =
            "sendMultipartSmsAsSeparateMessages";
    public static final boolean CONFIG_SEND_MULTIPART_SMS_AS_SEPARATE_MESSAGES_DEFAULT = false;
    /**
     * Boolean value: if MMS read report is supported
     */
    public static final String CONFIG_ENABLE_MMS_READ_REPORTS = "enableMMSReadReports";
    public static final boolean CONFIG_ENABLE_MMS_READ_REPORTS_DEFAULT = false;
    /**
     * Boolean value: if MMS delivery report is supported
     */
    public static final String CONFIG_ENABLE_MMS_DELIVERY_REPORTS = "enableMMSDeliveryReports";
    public static final boolean CONFIG_ENABLE_MMS_DELIVERY_REPORTS_DEFAULT = false;
    /**
     * Boolean value: if "charset" value is supported in the "Content-Type" HTTP header
     */
    public static final String CONFIG_SUPPORT_HTTP_CHARSET_HEADER = "supportHttpCharsetHeader";
    public static final boolean CONFIG_SUPPORT_HTTP_CHARSET_HEADER_DEFAULT = false;
    /**
     * Integer value: maximal MMS message size in bytes
     */
    public static final String CONFIG_MAX_MESSAGE_SIZE = "maxMessageSize";
    public static final int CONFIG_MAX_MESSAGE_SIZE_DEFAULT = 300 * 1024;
    /**
     * Integer value: maximal MMS image height in pixels
     */
    public static final String CONFIG_MAX_IMAGE_HEIGHT = "maxImageHeight";
    public static final int CONFIG_MAX_IMAGE_HEIGHT_DEFAULT = 480;
    /**
     * Integer value: maximal MMS image width in pixels
     */
    public static final String CONFIG_MAX_IMAGE_WIDTH = "maxImageWidth";
    public static final int CONFIG_MAX_IMAGE_WIDTH_DEFAULT = 640;
    /**
     * Integer value: limit on recipient list of an MMS message
     */
    public static final String CONFIG_RECIPIENT_LIMIT = "recipientLimit";
    public static final int CONFIG_RECIPIENT_LIMIT_DEFAULT = Integer.MAX_VALUE;
    /**
     * Integer value: HTTP socket timeout in milliseconds for MMS
     */
    public static final String CONFIG_HTTP_SOCKET_TIMEOUT = "httpSocketTimeout";
    public static final int CONFIG_HTTP_SOCKET_TIMEOUT_DEFAULT = 60 * 1000;
    /**
     * Integer value: minimal number of characters of an alias
     */
    public static final String CONFIG_ALIAS_MIN_CHARS = "aliasMinChars";
    public static final int CONFIG_ALIAS_MIN_CHARS_DEFAULT = 2;
    /**
     * Integer value: maximal number of characters of an alias
     */
    public static final String CONFIG_ALIAS_MAX_CHARS = "aliasMaxChars";
    public static final int CONFIG_ALIAS_MAX_CHARS_DEFAULT = 48;
    /**
     * Integer value: the threshold of number of SMS parts when an multipart SMS will be
     * converted into an MMS, e.g. if this is "4", when an multipart SMS message has 5
     * parts, then it will be sent as MMS message instead. "-1" indicates no such conversion
     * can happen.
     */
    public static final String CONFIG_SMS_TO_MMS_TEXT_THRESHOLD = "smsToMmsTextThreshold";
    public static final int CONFIG_SMS_TO_MMS_TEXT_THRESHOLD_DEFAULT = -1;
    /**
     * Integer value: the threshold of SMS length when it will be converted into an MMS.
     * "-1" indicates no such conversion can happen.
     */
    public static final String CONFIG_SMS_TO_MMS_TEXT_LENGTH_THRESHOLD =
            "smsToMmsTextLengthThreshold";
    public static final int CONFIG_SMS_TO_MMS_TEXT_LENGTH_THRESHOLD_DEFAULT = -1;
    /**
     * Integer value: maximal length in bytes of SMS message
     */
    public static final String CONFIG_MAX_MESSAGE_TEXT_SIZE = "maxMessageTextSize";
    public static final int CONFIG_MAX_MESSAGE_TEXT_SIZE_DEFAULT = -1;
    /**
     * Integer value: maximum number of characters allowed for mms subject
     */
    public static final String CONFIG_MAX_SUBJECT_LENGTH = "maxSubjectLength";
    public static final int CONFIG_MAX_SUBJECT_LENGTH_DEFAULT = 40;
    /**
     * String value: name for the user agent profile HTTP header
     */
    public static final String CONFIG_UA_PROF_TAG_NAME = "uaProfTagName";
    public static final String CONFIG_UA_PROF_TAG_NAME_DEFAULT = "x-wap-profile";
    /**
     * String value: additional HTTP headers for MMS HTTP requests.
     * The format is
     * header_1:header_value_1|header_2:header_value_2|...
     * Each value can contain macros.
     */
    public static final String CONFIG_HTTP_PARAMS = "httpParams";
    public static final String CONFIG_HTTP_PARAMS_DEFAULT = null;
    /**
     * String value: number of email gateway
     */
    public static final String CONFIG_EMAIL_GATEWAY_NUMBER = "emailGatewayNumber";
    public static final String CONFIG_EMAIL_GATEWAY_NUMBER_DEFAULT = null;
    /**
     * String value: suffix for the NAI HTTP header value, e.g. ":pcs"
     * (NAI is used as authentication in HTTP headers for some carriers)
     */
    public static final String CONFIG_NAI_SUFFIX = "naiSuffix";
    public static final String CONFIG_NAI_SUFFIX_DEFAULT = null;
    /**
     * String value: Url for user agent profile
     */
    public static final String CONFIG_UA_PROF_URL = "uaProfUrl";
    public static final String CONFIG_UA_PROF_URL_DEFAULT = null;
    /**
     * String value: user agent
     */
    public static final String CONFIG_USER_AGENT = "userAgent";
    public static final String CONFIG_USER_AGENT_DEFAULT = null;
}
