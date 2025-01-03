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

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/**
 * XML parser for carrier config (i.e. mms_config)
 */
class CarrierConfigXmlParser extends MmsXmlResourceParser {
    interface KeyValueProcessor {
        void process(String type, String key, String value);
    }

    private static final String TAG_MMS_CONFIG = "mms_config";

    private final KeyValueProcessor mKeyValueProcessor;

    CarrierConfigXmlParser(final XmlPullParser parser, final KeyValueProcessor keyValueProcessor) {
        super(parser);
        mKeyValueProcessor = keyValueProcessor;
    }

    // Parse one key/value
    @Override
    protected void parseRecord() throws IOException, XmlPullParserException {
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
            throw new XmlPullParserException("Expecting end tag @" + xmlParserDebugContext());
        }
        // We are done parsing one mms_config key/value, call the handler
        if (mKeyValueProcessor != null) {
            mKeyValueProcessor.process(type, key, value);
        }
    }

    @Override
    protected String getRootTag() {
        return TAG_MMS_CONFIG;
    }

}
