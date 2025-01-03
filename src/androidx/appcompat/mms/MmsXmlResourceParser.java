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

import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/**
 * Base class for a parser of XML resources
 */
abstract class MmsXmlResourceParser {
    /**
     * Parse the content
     *
     * @throws IOException
     * @throws XmlPullParserException
     */
    protected abstract void parseRecord() throws IOException, XmlPullParserException;

    /**
     * Get the root tag of the content
     *
     * @return the text of root tag
     */
    protected abstract String getRootTag();

    private final StringBuilder mLogStringBuilder = new StringBuilder();

    protected final XmlPullParser mInputParser;

    protected MmsXmlResourceParser(XmlPullParser parser) {
        mInputParser = parser;
    }

    void parse() {
        try {
            // Find the first element
            if (advanceToNextEvent(XmlPullParser.START_TAG) != XmlPullParser.START_TAG) {
                throw new XmlPullParserException("ApnsXmlProcessor: expecting start tag @"
                        + xmlParserDebugContext());
            }
            if (!getRootTag().equals(mInputParser.getName())) {
                Log.w(MmsService.TAG, "Carrier config does not start with " + getRootTag());
                return;
            }
            // We are at the start tag
            for (;;) {
                int nextEvent;
                // Skipping spaces
                while ((nextEvent = mInputParser.next()) == XmlPullParser.TEXT);
                if (nextEvent == XmlPullParser.START_TAG) {
                    // Parse one record
                    parseRecord();
                } else if (nextEvent == XmlPullParser.END_TAG) {
                    break;
                } else {
                    throw new XmlPullParserException("Expecting start or end tag @"
                            + xmlParserDebugContext());
                }
            }
        } catch (IOException e) {
            Log.w(MmsService.TAG, "XmlResourceParser: I/O failure", e);
        } catch (XmlPullParserException e) {
            Log.w(MmsService.TAG, "XmlResourceParser: parsing failure", e);
        }
    }

    /**
     * Move XML parser forward to next event type or the end of doc
     *
     * @param eventType
     * @return The final event type we meet
     * @throws XmlPullParserException
     * @throws IOException
     */
    protected int advanceToNextEvent(int eventType) throws XmlPullParserException, IOException {
        for (;;) {
            int nextEvent = mInputParser.next();
            if (nextEvent == eventType
                    || nextEvent == XmlPullParser.END_DOCUMENT) {
                return nextEvent;
            }
        }
    }

    /**
     * @return The debugging information of the parser's current position
     */
    protected String xmlParserDebugContext() {
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
                Log.w(MmsService.TAG, "XmlResourceParser exception", e);
            }
        }
        return "Unknown";
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
}
