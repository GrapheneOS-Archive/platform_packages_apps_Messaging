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

import java.util.List;

/**
 * Interface for loading APNs for default SMS SIM
 */
public interface ApnSettingsLoader {
    /**
     * Interface to represent the minimal information MMS lib needs from an APN
     */
    interface Apn {
        /**
         * Get the MMSC URL string
         *
         * @return MMSC URL
         */
        String getMmsc();

        /**
         * Get the MMS proxy host address
         *
         * @return MMS proxy
         */
        String getMmsProxy();

        /**
         * Get the MMS proxy host port
         *
         * @return the port of MMS proxy
         */
        int getMmsProxyPort();

        /**
         * Flag the APN as a successful APN to use
         */
        void setSuccess();
    }

    /**
     * Get a list possible APN matching the subId and APN name
     *
     * @param apnName the APN name
     * @return a list of possible APNs
     */
    List<Apn> get(String apnName);
}
