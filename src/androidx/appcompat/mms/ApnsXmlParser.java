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

import android.content.ContentValues;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/**
 * Parser for built-in XML resource file for APN list
 */
class ApnsXmlParser extends MmsXmlResourceParser {
    interface ApnProcessor {
        void process(ContentValues apnValues);
    }

    private static final String TAG_APNS = "apns";
    private static final String TAG_APN = "apn";

    private final ApnProcessor mApnProcessor;

    private final ContentValues mValues = new ContentValues();

    ApnsXmlParser(final XmlPullParser parser, final ApnProcessor apnProcessor) {
        super(parser);
        mApnProcessor = apnProcessor;
    }

    // Parse one APN
    @Override
    protected void parseRecord() throws IOException, XmlPullParserException {
        if (TAG_APN.equals(mInputParser.getName())) {
            mValues.clear();
            // Collect all the attributes
            for (int i = 0; i < mInputParser.getAttributeCount(); i++) {
                final String key = mInputParser.getAttributeName(i);
                if (key != null) {
                    mValues.put(key, mInputParser.getAttributeValue(i));
                }
            }
            // We are done parsing one APN, call the handler
            if (mApnProcessor != null) {
                mApnProcessor.process(mValues);
            }
        }
        // We are at the end tag
        if (mInputParser.next() != XmlPullParser.END_TAG) {
            throw new XmlPullParserException("Expecting end tag @" + xmlParserDebugContext());
        }
    }

    @Override
    protected String getRootTag() {
        return TAG_APNS;
    }
}
