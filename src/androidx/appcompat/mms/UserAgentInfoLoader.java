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

/**
 * Interface to load UserAgent and UA Prof URL
 */
public interface UserAgentInfoLoader {
    // Carrier configuration keys for passing as config overrides into system MMS service
    public static final String CONFIG_USER_AGENT = "userAgent";
    public static final String CONFIG_UA_PROF_URL = "uaProfUrl";

    /**
     * Get UserAgent value
     *
     * @return the text of UserAgent
     */
    String getUserAgent();

    /**
     * Get UA Profile URL
     *
     * @return the URL of UA profile
     */
    String getUAProfUrl();
}
