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

public class MmsService {
    static final String TAG = "MmsLib";

    /**
     * Carrier configuration values loader
     */
    private static volatile CarrierConfigValuesLoader sCarrierConfigValuesLoader = null;

    /**
     * UserAgent and UA Prof URL loader
     */
    private static volatile UserAgentInfoLoader sUserAgentInfoLoader = null;

    /**
     * Set the optional carrier config values
     *
     * @param loader the carrier config values loader
     */
    static void setCarrierConfigValuesLoader(final CarrierConfigValuesLoader loader) {
        sCarrierConfigValuesLoader = loader;
    }

    /**
     * Get the current carrier config values loader
     *
     * @return the carrier config values loader currently set
     */
    static CarrierConfigValuesLoader getCarrierConfigValuesLoader() {
        return sCarrierConfigValuesLoader;
    }

    /**
     * Set user agent info loader
     *
     * @param loader the user agent info loader
     */
    static void setUserAgentInfoLoader(final UserAgentInfoLoader loader) {
        sUserAgentInfoLoader = loader;
    }

    /**
     * Get the current user agent info loader
     *
     * @return the user agent info loader currently set
     */
    static UserAgentInfoLoader getUserAgentInfoLoader() {
        return sUserAgentInfoLoader;
    }
}
