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

import android.content.Context;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MMS HTTP client for sending and downloading MMS messages
 */
public class MmsHttpClient {
    static final String METHOD_POST = "POST";
    static final String METHOD_GET = "GET";

    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String HEADER_ACCEPT = "Accept";
    private static final String HEADER_ACCEPT_LANGUAGE = "Accept-Language";
    private static final String HEADER_USER_AGENT = "User-Agent";

    // The "Accept" header value
    private static final String HEADER_VALUE_ACCEPT =
            "*/*, application/vnd.wap.mms-message, application/vnd.wap.sic";
    // The "Content-Type" header value
    private static final String HEADER_VALUE_CONTENT_TYPE_WITH_CHARSET =
            "application/vnd.wap.mms-message; charset=utf-8";
    private static final String HEADER_VALUE_CONTENT_TYPE_WITHOUT_CHARSET =
            "application/vnd.wap.mms-message";

    /*
     * Macro names
     */
    // The raw phone number
    private static final String MACRO_LINE1 = "LINE1";
    // The phone number without country code
    private static final String MACRO_LINE1NOCOUNTRYCODE = "LINE1NOCOUNTRYCODE";
    // NAI (Network Access Identifier)
    private static final String MACRO_NAI = "NAI";

    // The possible NAI system property name
    private static final String NAI_PROPERTY = "persist.radio.cdma.nai";

    private final Context mContext;
    private final TelephonyManager mTelephonyManager;

    /**
     * Constructor
     *
     * @param context The Context object
     */
    MmsHttpClient(Context context) {
        mContext = context;
        mTelephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
    }

    /**
     * Execute an MMS HTTP request, either a POST (sending) or a GET (downloading)
     *
     * @param urlString The request URL, for sending it is usually the MMSC, and for downloading
     *                  it is the message URL
     * @param pdu For POST (sending) only, the PDU to send
     * @param method HTTP method, POST for sending and GET for downloading
     * @param isProxySet Is there a proxy for the MMSC
     * @param proxyHost The proxy host
     * @param proxyPort The proxy port
     * @param mmsConfig The MMS config to use
     * @param userAgent The user agent header value
     * @param uaProfUrl The UA Prof URL header value
     * @return The HTTP response body
     * @throws MmsHttpException For any failures
     */
    public byte[] execute(String urlString, byte[] pdu, String method, boolean isProxySet,
            String proxyHost, int proxyPort, Bundle mmsConfig, String userAgent, String uaProfUrl)
            throws MmsHttpException {
        Log.d(MmsService.TAG, "HTTP: " + method + " " + Utils.redactUrlForNonVerbose(urlString)
                + (isProxySet ? (", proxy=" + proxyHost + ":" + proxyPort) : "")
                + ", PDU size=" + (pdu != null ? pdu.length : 0));
        checkMethod(method);
        HttpURLConnection connection = null;
        try {
            Proxy proxy = Proxy.NO_PROXY;
            if (isProxySet) {
                proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
            }
            final URL url = new URL(urlString);
            // Now get the connection
            connection = (HttpURLConnection) url.openConnection(proxy);
            connection.setDoInput(true);
            connection.setConnectTimeout(
                    mmsConfig.getInt(CarrierConfigValuesLoader.CONFIG_HTTP_SOCKET_TIMEOUT,
                            CarrierConfigValuesLoader.CONFIG_HTTP_SOCKET_TIMEOUT_DEFAULT));
            // ------- COMMON HEADERS ---------
            // Header: Accept
            connection.setRequestProperty(HEADER_ACCEPT, HEADER_VALUE_ACCEPT);
            // Header: Accept-Language
            connection.setRequestProperty(
                    HEADER_ACCEPT_LANGUAGE, getCurrentAcceptLanguage(Locale.getDefault()));
            // Header: User-Agent
            Log.i(MmsService.TAG, "HTTP: User-Agent=" + userAgent);
            connection.setRequestProperty(HEADER_USER_AGENT, userAgent);
            // Header: x-wap-profile
            final String uaProfUrlTagName = mmsConfig.getString(
                    CarrierConfigValuesLoader.CONFIG_UA_PROF_TAG_NAME,
                    CarrierConfigValuesLoader.CONFIG_UA_PROF_TAG_NAME_DEFAULT);
            if (uaProfUrl != null) {
                Log.i(MmsService.TAG, "HTTP: UaProfUrl=" + uaProfUrl);
                connection.setRequestProperty(uaProfUrlTagName, uaProfUrl);
            }
            // Add extra headers specified by mms_config.xml's httpparams
            addExtraHeaders(connection, mmsConfig);
            // Different stuff for GET and POST
            if (METHOD_POST.equals(method)) {
                if (pdu == null || pdu.length < 1) {
                    Log.e(MmsService.TAG, "HTTP: empty pdu");
                    throw new MmsHttpException(0/*statusCode*/, "Sending empty PDU");
                }
                connection.setDoOutput(true);
                connection.setRequestMethod(METHOD_POST);
                if (mmsConfig.getBoolean(
                        CarrierConfigValuesLoader.CONFIG_SUPPORT_HTTP_CHARSET_HEADER,
                        CarrierConfigValuesLoader.CONFIG_SUPPORT_HTTP_CHARSET_HEADER_DEFAULT)) {
                    connection.setRequestProperty(HEADER_CONTENT_TYPE,
                            HEADER_VALUE_CONTENT_TYPE_WITH_CHARSET);
                } else {
                    connection.setRequestProperty(HEADER_CONTENT_TYPE,
                            HEADER_VALUE_CONTENT_TYPE_WITHOUT_CHARSET);
                }
                if (Log.isLoggable(MmsService.TAG, Log.VERBOSE)) {
                    logHttpHeaders(connection.getRequestProperties());
                }
                connection.setFixedLengthStreamingMode(pdu.length);
                // Sending request body
                final OutputStream out =
                        new BufferedOutputStream(connection.getOutputStream());
                out.write(pdu);
                out.flush();
                out.close();
            } else if (METHOD_GET.equals(method)) {
                if (Log.isLoggable(MmsService.TAG, Log.VERBOSE)) {
                    logHttpHeaders(connection.getRequestProperties());
                }
                connection.setRequestMethod(METHOD_GET);
            }
            // Get response
            final int responseCode = connection.getResponseCode();
            final String responseMessage = connection.getResponseMessage();
            Log.d(MmsService.TAG, "HTTP: " + responseCode + " " + responseMessage);
            if (Log.isLoggable(MmsService.TAG, Log.VERBOSE)) {
                logHttpHeaders(connection.getHeaderFields());
            }
            if (responseCode / 100 != 2) {
                throw new MmsHttpException(responseCode, responseMessage);
            }
            final InputStream in = new BufferedInputStream(connection.getInputStream());
            final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
            final byte[] buf = new byte[4096];
            int count = 0;
            while ((count = in.read(buf)) > 0) {
                byteOut.write(buf, 0, count);
            }
            in.close();
            final byte[] responseBody = byteOut.toByteArray();
            Log.d(MmsService.TAG, "HTTP: response size="
                    + (responseBody != null ? responseBody.length : 0));
            return responseBody;
        } catch (MalformedURLException e) {
            final String redactedUrl = Utils.redactUrlForNonVerbose(urlString);
            Log.e(MmsService.TAG, "HTTP: invalid URL " + redactedUrl, e);
            throw new MmsHttpException(0/*statusCode*/, "Invalid URL " + redactedUrl, e);
        } catch (ProtocolException e) {
            final String redactedUrl = Utils.redactUrlForNonVerbose(urlString);
            Log.e(MmsService.TAG, "HTTP: invalid URL protocol " + redactedUrl, e);
            throw new MmsHttpException(0/*statusCode*/, "Invalid URL protocol " + redactedUrl, e);
        } catch (IOException e) {
            Log.e(MmsService.TAG, "HTTP: IO failure", e);
            throw new MmsHttpException(0/*statusCode*/, e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static void logHttpHeaders(Map<String, List<String>> headers) {
        final StringBuilder sb = new StringBuilder();
        if (headers != null) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                final String key = entry.getKey();
                final List<String> values = entry.getValue();
                if (values != null) {
                    for (String value : values) {
                        sb.append(key).append('=').append(value).append('\n');
                    }
                }
            }
            Log.v(MmsService.TAG, "HTTP: headers\n" + sb.toString());
        }
    }

    private static void checkMethod(String method) throws MmsHttpException {
        if (!METHOD_GET.equals(method) && !METHOD_POST.equals(method)) {
            throw new MmsHttpException(0/*statusCode*/, "Invalid method " + method);
        }
    }

    private static final String ACCEPT_LANG_FOR_US_LOCALE = "en-US";

    /**
     * Return the Accept-Language header.  Use the current locale plus
     * US if we are in a different locale than US.
     * This code copied from the browser's WebSettings.java
     *
     * @return Current AcceptLanguage String.
     */
    public static String getCurrentAcceptLanguage(Locale locale) {
        final StringBuilder buffer = new StringBuilder();
        addLocaleToHttpAcceptLanguage(buffer, locale);

        if (!Locale.US.equals(locale)) {
            if (buffer.length() > 0) {
                buffer.append(", ");
            }
            buffer.append(ACCEPT_LANG_FOR_US_LOCALE);
        }

        return buffer.toString();
    }

    /**
     * Convert obsolete language codes, including Hebrew/Indonesian/Yiddish,
     * to new standard.
     */
    private static String convertObsoleteLanguageCodeToNew(String langCode) {
        if (langCode == null) {
            return null;
        }
        if ("iw".equals(langCode)) {
            // Hebrew
            return "he";
        } else if ("in".equals(langCode)) {
            // Indonesian
            return "id";
        } else if ("ji".equals(langCode)) {
            // Yiddish
            return "yi";
        }
        return langCode;
    }

    private static void addLocaleToHttpAcceptLanguage(StringBuilder builder, Locale locale) {
        final String language = convertObsoleteLanguageCodeToNew(locale.getLanguage());
        if (language != null) {
            builder.append(language);
            final String country = locale.getCountry();
            if (country != null) {
                builder.append("-");
                builder.append(country);
            }
        }
    }

    private static final Pattern MACRO_P = Pattern.compile("##(\\S+)##");
    /**
     * Resolve the macro in HTTP param value text
     * For example, "something##LINE1##something" is resolved to "something9139531419something"
     *
     * @param value The HTTP param value possibly containing macros
     * @return The HTTP param with macro resolved to real value
     */
    private String resolveMacro(String value, Bundle mmsConfig) {
        if (TextUtils.isEmpty(value)) {
            return value;
        }
        final Matcher matcher = MACRO_P.matcher(value);
        int nextStart = 0;
        StringBuilder replaced = null;
        while (matcher.find()) {
            if (replaced == null) {
                replaced = new StringBuilder();
            }
            final int matchedStart = matcher.start();
            if (matchedStart > nextStart) {
                replaced.append(value.substring(nextStart, matchedStart));
            }
            final String macro = matcher.group(1);
            final String macroValue = getHttpParamMacro(macro, mmsConfig);
            if (macroValue != null) {
                replaced.append(macroValue);
            }
            nextStart = matcher.end();
        }
        if (replaced != null && nextStart < value.length()) {
            replaced.append(value.substring(nextStart));
        }
        return replaced == null ? value : replaced.toString();
    }

    /**
     * Add extra HTTP headers from mms_config.xml's httpParams, which is a list of key/value
     * pairs separated by "|". Each key/value pair is separated by ":". Value may contain
     * macros like "##LINE1##" or "##NAI##" which is resolved with methods in this class
     *
     * @param connection The HttpURLConnection that we add headers to
     * @param mmsConfig The MmsConfig object
     */
    private void addExtraHeaders(HttpURLConnection connection, Bundle mmsConfig) {
        final String extraHttpParams = mmsConfig.getString(
                CarrierConfigValuesLoader.CONFIG_HTTP_PARAMS);
        if (!TextUtils.isEmpty(extraHttpParams)) {
            // Parse the parameter list
            String paramList[] = extraHttpParams.split("\\|");
            for (String paramPair : paramList) {
                String splitPair[] = paramPair.split(":", 2);
                if (splitPair.length == 2) {
                    final String name = splitPair[0].trim();
                    final String value = resolveMacro(splitPair[1].trim(), mmsConfig);
                    if (!TextUtils.isEmpty(name) && !TextUtils.isEmpty(value)) {
                        // Add the header if the param is valid
                        connection.setRequestProperty(name, value);
                    }
                }
            }
        }
    }

    /**
     * Return the HTTP param macro value.
     * Example: LINE1 returns the phone number, etc.
     *
     * @param macro The macro name
     * @param mmsConfig The carrier configuration values
     * @return The value of the defined macro
     */
    private String getHttpParamMacro(final String macro, final Bundle mmsConfig) {
        if (MACRO_LINE1.equals(macro)) {
            return getSelfNumber();
        } else if (MACRO_LINE1NOCOUNTRYCODE.equals(macro)) {
            return PhoneNumberHelper.getNumberNoCountryCode(
                    getSelfNumber(), getSimOrLocaleCountry());
        }  else if (MACRO_NAI.equals(macro)) {
            return getEncodedNai(mmsConfig.getString(
                    CarrierConfigValuesLoader.CONFIG_NAI_SUFFIX,
                    CarrierConfigValuesLoader.CONFIG_NAI_SUFFIX_DEFAULT));
        }
        return null;
    }

    /**
     * Get the device phone number
     *
     * @return the phone number text
     */
    private String getSelfNumber() {
        if (Utils.supportMSim()) {
            final SubscriptionManager subscriptionManager = SubscriptionManager.from(mContext);
            final SubscriptionInfo info = subscriptionManager.getActiveSubscriptionInfo(
                    SmsManager.getDefaultSmsSubscriptionId());
            if (info != null) {
                return info.getNumber();
            } else {
                return null;
            }
        } else {
            return mTelephonyManager.getLine1Number();
        }
    }

    /**
     * Get the country ISO code from SIM or system locale
     *
     * @return the country ISO code
     */
    private String getSimOrLocaleCountry() {
        String country = null;
        if (Utils.supportMSim()) {
            final SubscriptionManager subscriptionManager = SubscriptionManager.from(mContext);
            final SubscriptionInfo info = subscriptionManager.getActiveSubscriptionInfo(
                    SmsManager.getDefaultSmsSubscriptionId());
            if (info != null) {
                country = info.getCountryIso();
            }
        } else {
            country = mTelephonyManager.getSimCountryIso();
        }
        if (!TextUtils.isEmpty(country)) {
            return country.toUpperCase();
        } else {
            return Locale.getDefault().getCountry();
        }
    }

    /**
     * Get encoded NAI string to use as the HTTP header for some carriers.
     * On L-MR1+, we call the hidden system API to get this
     * On L-MR1-, we try to find it via system property.
     *
     * @param naiSuffix the suffix to append to NAI before encoding
     * @return the Base64 encoded NAI string to use as HTTP header
     */
    private String getEncodedNai(final String naiSuffix) {
        String nai;
        if (Utils.supportMSim()) {
            nai = getNaiBySystemApi(
                    getSlotId(Utils.getEffectiveSubscriptionId(MmsManager.DEFAULT_SUB_ID)));
        } else {
            nai = getNaiBySystemProperty();
        }
        if (!TextUtils.isEmpty(nai)) {
            Log.i(MmsService.TAG, "NAI is not empty");
            if (!TextUtils.isEmpty(naiSuffix)) {
                nai = nai + naiSuffix;
            }
            byte[] encoded = null;
            try {
                encoded = Base64.encode(nai.getBytes("UTF-8"), Base64.NO_WRAP);
            } catch (UnsupportedEncodingException e) {
                encoded = Base64.encode(nai.getBytes(), Base64.NO_WRAP);
            }
            try {
                return new String(encoded, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                return new String(encoded);
            }
        }
        return null;
    }

    /**
     * Invoke hidden SubscriptionManager.getSlotIndex(int)
     *
     * @param subId the subId
     * @return the SIM slot ID
     */
    private static int getSlotId(final int subId) {
        try {
            final Method method = SubscriptionManager.class.getMethod("getSlotIndex", Integer.TYPE);
            if (method != null) {
                return (Integer) method.invoke(null, subId);
            }
        } catch (Exception e) {
            Log.w(MmsService.TAG, "SubscriptionManager.getSlotIndex failed " + e);
        }
        return -1;
    }

    /**
     * Get NAI using hidden TelephonyManager.getNai(int)
     *
     * @param slotId the SIM slot ID
     * @return the NAI string
     */
    private String getNaiBySystemApi(final int slotId) {
        try {
            final Method method = mTelephonyManager.getClass().getMethod("getNai", Integer.TYPE);
            if (method != null) {
                return (String) method.invoke(mTelephonyManager, slotId);
            }
        } catch (Exception e) {
            Log.w(MmsService.TAG, "TelephonyManager.getNai failed " + e);
        }
        return null;
    }

    /**
     * Get NAI using hidden SystemProperties.get(String)
     *
     * @return the NAI string as system property
     */
    private static String getNaiBySystemProperty() {
        try {
            final Class systemPropertiesClass = Class.forName("android.os.SystemProperties");
            if (systemPropertiesClass != null) {
                final Method method = systemPropertiesClass.getMethod("get", String.class);
                if (method != null) {
                    return (String) method.invoke(null, NAI_PROPERTY);
                }
            }
        } catch (Exception e) {
            Log.w(MmsService.TAG, "SystemProperties.get failed " + e);
        }
        return null;
    }
}
