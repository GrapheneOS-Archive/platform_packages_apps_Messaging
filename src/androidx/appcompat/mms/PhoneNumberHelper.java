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

import android.text.TextUtils;
import android.util.Log;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;

/**
 * Helper methods for phone number formatting
 * This is isolated into a standalone class since it depends on libphonenumber
 */
public class PhoneNumberHelper {
    /**
     * Given a phone number, get its national part without country code
     *
     * @param number the original number
     * @param country the country ISO code
     * @return the national number
     */
    static String getNumberNoCountryCode(final String number, final String country) {
        if (!TextUtils.isEmpty(number)) {
            final PhoneNumberUtil phoneNumberUtil = PhoneNumberUtil.getInstance();
            try {
                final Phonenumber.PhoneNumber phoneNumber = phoneNumberUtil.parse(number, country);
                if (phoneNumber != null && phoneNumberUtil.isValidNumber(phoneNumber)) {
                    return phoneNumberUtil
                            .format(phoneNumber, PhoneNumberUtil.PhoneNumberFormat.NATIONAL)
                            .replaceAll("\\D", "");
                }
            } catch (final NumberParseException e) {
                Log.w(MmsService.TAG, "getNumberNoCountryCode: invalid number " + e);
            }
        }
        return number;
    }
}
