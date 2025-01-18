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

package com.android.messaging.sms;

import android.content.ContentValues;
import android.provider.Telephony;

import com.android.messaging.util.Assert;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.PhoneUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/*
 * XML processor for the following files:
 * 1. res/xml/apns.xml
 * 2. res/xml/mms_config.xml (or related overlay files)
 */
class ApnsXmlProcessor {
    public interface ApnHandler {
        public void process(ContentValues apnValues);
    }

    public interface MmsConfigHandler {
        public void process(String mccMnc, String key, String value, String type);
    }

    private static final String TAG = LogUtil.BUGLE_TAG;

    private static final Map<String, String> APN_ATTRIBUTE_MAP = new HashMap<>();
    static {
        APN_ATTRIBUTE_MAP.put("mcc", Telephony.Carriers.MCC);
        APN_ATTRIBUTE_MAP.put("mnc", Telephony.Carriers.MNC);
        APN_ATTRIBUTE_MAP.put("carrier", Telephony.Carriers.NAME);
        APN_ATTRIBUTE_MAP.put("apn", Telephony.Carriers.APN);
        APN_ATTRIBUTE_MAP.put("mmsc", Telephony.Carriers.MMSC);
        APN_ATTRIBUTE_MAP.put("mmsproxy", Telephony.Carriers.MMSPROXY);
        APN_ATTRIBUTE_MAP.put("mmsport", Telephony.Carriers.MMSPORT);
        APN_ATTRIBUTE_MAP.put("type", Telephony.Carriers.TYPE);
        APN_ATTRIBUTE_MAP.put("user", Telephony.Carriers.USER);
        APN_ATTRIBUTE_MAP.put("password", Telephony.Carriers.PASSWORD);
        APN_ATTRIBUTE_MAP.put("authtype", Telephony.Carriers.AUTH_TYPE);
        APN_ATTRIBUTE_MAP.put("mvno_match_data", Telephony.Carriers.MVNO_MATCH_DATA);
        APN_ATTRIBUTE_MAP.put("mvno_type", Telephony.Carriers.MVNO_TYPE);
        APN_ATTRIBUTE_MAP.put("protocol", Telephony.Carriers.PROTOCOL);
        APN_ATTRIBUTE_MAP.put("bearer", Telephony.Carriers.BEARER);
        APN_ATTRIBUTE_MAP.put("server", Telephony.Carriers.SERVER);
        APN_ATTRIBUTE_MAP.put("roaming_protocol", Telephony.Carriers.ROAMING_PROTOCOL);
        APN_ATTRIBUTE_MAP.put("proxy", Telephony.Carriers.PROXY);
        APN_ATTRIBUTE_MAP.put("port", Telephony.Carriers.PORT);
        APN_ATTRIBUTE_MAP.put("carrier_enabled", Telephony.Carriers.CARRIER_ENABLED);
    }

    private static final String TAG_APNS = "apns";
    private static final String TAG_APN = "apn";
    private static final String TAG_MMS_CONFIG = "mms_config";

    // Handler to process one apn
    private ApnHandler mApnHandler;
    // Handler to process one mms_config key/value pair
    private MmsConfigHandler mMmsConfigHandler;

    private final StringBuilder mLogStringBuilder = new StringBuilder();

    private final XmlPullParser mInputParser;

    private ApnsXmlProcessor(XmlPullParser parser) {
        mInputParser = parser;
        mApnHandler = null;
        mMmsConfigHandler = null;
    }

    public static ApnsXmlProcessor get(XmlPullParser parser) {
        Assert.notNull(parser);
        return new ApnsXmlProcessor(parser);
    }

    public ApnsXmlProcessor setApnHandler(ApnHandler handler) {
        mApnHandler = handler;
        return this;
    }

    public ApnsXmlProcessor setMmsConfigHandler(MmsConfigHandler handler) {
        mMmsConfigHandler = handler;
        return this;
    }

    /**
     * Move XML parser forward to next event type or the end of doc
     *
     * @param eventType
     * @return The final event type we meet
     * @throws XmlPullParserException
     * @throws IOException
     */
    private int advanceToNextEvent(int eventType) throws XmlPullParserException, IOException {
        for (;;) {
            int nextEvent = mInputParser.next();
            if (nextEvent == eventType
                    || nextEvent == XmlPullParser.END_DOCUMENT) {
                return nextEvent;
            }
        }
    }

    public void process() {
        try {
            // Find the first element
            if (advanceToNextEvent(XmlPullParser.START_TAG) != XmlPullParser.START_TAG) {
                throw new XmlPullParserException("ApnsXmlProcessor: expecting start tag @"
                        + xmlParserDebugContext());
            }
            // A single ContentValues object for holding the parsing result of
            // an apn element
            final ContentValues values = new ContentValues();
            String tagName = mInputParser.getName();
            // Top level tag can be "apns" (apns.xml)
            // or "mms_config" (mms_config.xml)
            if (TAG_APNS.equals(tagName)) {
                // For "apns", there could be "apn" or both "apn" and "mms_config"
                for (;;) {
                    if (advanceToNextEvent(XmlPullParser.START_TAG) != XmlPullParser.START_TAG) {
                        break;
                    }
                    tagName = mInputParser.getName();
                    if (TAG_APN.equals(tagName)) {
                        processApn(values);
                    } else if (TAG_MMS_CONFIG.equals(tagName)) {
                        processMmsConfig();
                    }
                }
            } else if (TAG_MMS_CONFIG.equals(tagName)) {
                // mms_config.xml resource
                processMmsConfig();
            }
        } catch (IOException e) {
            LogUtil.e(TAG, "ApnsXmlProcessor: I/O failure " + e, e);
        } catch (XmlPullParserException e) {
            LogUtil.e(TAG, "ApnsXmlProcessor: parsing failure " + e, e);
        }
    }

    private Integer parseInt(String text, Integer defaultValue, String logHint) {
        Integer value = defaultValue;
        try {
            value = Integer.parseInt(text);
        } catch (Exception e) {
            LogUtil.e(TAG,
                    "Invalid value " + text + "for" + logHint + " @" + xmlParserDebugContext());
        }
        return value;
    }

    private Boolean parseBoolean(String text, Boolean defaultValue, String logHint) {
        Boolean value = defaultValue;
        try {
            value = Boolean.parseBoolean(text);
        } catch (Exception e) {
            LogUtil.e(TAG,
                    "Invalid value " + text + "for" + logHint + " @" + xmlParserDebugContext());
        }
        return value;
    }

    private static String xmlParserEventString(int event) {
        switch (event) {
            case XmlPullParser.START_DOCUMENT: return "START_DOCUMENT";
            case XmlPullParser.END_DOCUMENT: return "END_DOCUMENT";
            case XmlPullParser.START_TAG: return "START_TAG";
            case XmlPullParser.END_TAG: return "END_TAG";
            case XmlPullParser.TEXT: return "TEXT";
        }
        return Integer.toString(event);
    }

    /**
     * @return The debugging information of the parser's current position
     */
    private String xmlParserDebugContext() {
        mLogStringBuilder.setLength(0);
        if (mInputParser != null) {
            try {
                final int eventType = mInputParser.getEventType();
                mLogStringBuilder.append(xmlParserEventString(eventType));
                if (eventType == XmlPullParser.START_TAG
                        || eventType == XmlPullParser.END_TAG
                        || eventType == XmlPullParser.TEXT) {
                    mLogStringBuilder.append('<').append(mInputParser.getName());
                    for (int i = 0; i < mInputParser.getAttributeCount(); i++) {
                        mLogStringBuilder.append(' ')
                            .append(mInputParser.getAttributeName(i))
                            .append('=')
                            .append(mInputParser.getAttributeValue(i));
                    }
                    mLogStringBuilder.append("/>");
                }
                return mLogStringBuilder.toString();
            } catch (XmlPullParserException e) {
                LogUtil.e(TAG, "xmlParserDebugContext: " + e, e);
            }
        }
        return "Unknown";
    }

    /**
     * Process one apn
     *
     * @param apnValues Where we store the parsed apn
     * @throws IOException
     * @throws XmlPullParserException
     */
    private void processApn(ContentValues apnValues) throws IOException, XmlPullParserException {
        Assert.notNull(apnValues);
        apnValues.clear();
        // Collect all the attributes
        for (int i = 0; i < mInputParser.getAttributeCount(); i++) {
            final String key = APN_ATTRIBUTE_MAP.get(mInputParser.getAttributeName(i));
            if (key != null) {
                apnValues.put(key, mInputParser.getAttributeValue(i));
            }
        }
        // Set numeric to be canonicalized mcc/mnc like "310120", always 6 digits
        final String canonicalMccMnc = PhoneUtils.canonicalizeMccMnc(
                apnValues.getAsString(Telephony.Carriers.MCC),
                apnValues.getAsString(Telephony.Carriers.MNC));
        apnValues.put(Telephony.Carriers.NUMERIC, canonicalMccMnc);
        // Some of the values should not be string type, converting them to desired types
        final String authType = apnValues.getAsString(Telephony.Carriers.AUTH_TYPE);
        if (authType != null) {
            apnValues.put(Telephony.Carriers.AUTH_TYPE, parseInt(authType, -1, "apn authtype"));
        }
        final String carrierEnabled = apnValues.getAsString(Telephony.Carriers.CARRIER_ENABLED);
        if (carrierEnabled != null) {
            apnValues.put(Telephony.Carriers.CARRIER_ENABLED,
                    parseBoolean(carrierEnabled, null, "apn carrierEnabled"));
        }
        final String bearer = apnValues.getAsString(Telephony.Carriers.BEARER);
        if (bearer != null) {
            apnValues.put(Telephony.Carriers.BEARER, parseInt(bearer, 0, "apn bearer"));
        }
        // We are at the end tag
        if (mInputParser.next() != XmlPullParser.END_TAG) {
            throw new XmlPullParserException("Apn: expecting end tag @"
                    + xmlParserDebugContext());
        }
        // We are done parsing one APN, call the handler
        if (mApnHandler != null) {
            mApnHandler.process(apnValues);
        }
    }

    /**
     * Process one mms_config.
     *
     * @throws IOException
     * @throws XmlPullParserException
     */
    private void processMmsConfig()
            throws IOException, XmlPullParserException {
        // Get the mcc and mnc attributes
        final String canonicalMccMnc = PhoneUtils.canonicalizeMccMnc(
                mInputParser.getAttributeValue(null, "mcc"),
                mInputParser.getAttributeValue(null, "mnc"));
        // We are at the start tag
        for (;;) {
            int nextEvent;
            // Skipping spaces
            while ((nextEvent = mInputParser.next()) == XmlPullParser.TEXT) {
            }
            if (nextEvent == XmlPullParser.START_TAG) {
                // Parse one mms config key/value
                processMmsConfigKeyValue(canonicalMccMnc);
            } else if (nextEvent == XmlPullParser.END_TAG) {
                break;
            } else {
                throw new XmlPullParserException("MmsConfig: expecting start or end tag @"
                        + xmlParserDebugContext());
            }
        }
    }

    /**
     * Process one mms_config key/value pair
     *
     * @param mccMnc The mcc and mnc of this mms_config
     * @throws IOException
     * @throws XmlPullParserException
     */
    private void processMmsConfigKeyValue(String mccMnc)
            throws IOException, XmlPullParserException {
        final String key = mInputParser.getAttributeValue(null, "name");
        // We are at the start tag, the name of the tag is the type
        // e.g. <int name="key">value</int>
        final String type = mInputParser.getName();
        int nextEvent = mInputParser.next();
        String value = null;
        if (nextEvent == XmlPullParser.TEXT) {
            value = mInputParser.getText();
            nextEvent = mInputParser.next();
        }
        if (nextEvent != XmlPullParser.END_TAG) {
            throw new XmlPullParserException("ApnsXmlProcessor: expecting end tag @"
                    + xmlParserDebugContext());
        }
        // We are done parsing one mms_config key/value, call the handler
        if (mMmsConfigHandler != null) {
            mMmsConfigHandler.process(mccMnc, key, value, type);
        }
    }
}
