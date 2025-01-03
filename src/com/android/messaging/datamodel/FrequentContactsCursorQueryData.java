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

package com.android.messaging.datamodel;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.ContactsContract.Contacts;

import com.android.messaging.util.FallbackStrategies;
import com.android.messaging.util.FallbackStrategies.Strategy;
import com.android.messaging.util.LogUtil;

/**
 * Helper for querying frequent (and/or starred) contacts.
 */
public class FrequentContactsCursorQueryData extends CursorQueryData {
    private static final String TAG = LogUtil.BUGLE_TAG;

    private static class FrequentContactsCursorLoader extends BoundCursorLoader {
        private final Uri mOriginalUri;

        FrequentContactsCursorLoader(String bindingId, Context context, Uri uri,
                String[] projection, String selection, String[] selectionArgs, String sortOrder) {
            super(bindingId, context, uri, projection, selection, selectionArgs, sortOrder);
            mOriginalUri = uri;
        }

        @Override
        public Cursor loadInBackground() {
            return FallbackStrategies
                    .startWith(new PrimaryStrequentContactsQueryStrategy())
                    .thenTry(new FrequentOnlyContactsQueryStrategy())
                    .thenTry(new PhoneOnlyStrequentContactsQueryStrategy())
                    .execute(null);
        }

        private abstract class StrequentContactsQueryStrategy implements Strategy<Void, Cursor> {
            @Override
            public Cursor execute(Void params) throws Exception {
                final Uri uri = getUri();
                if (uri != null) {
                    setUri(uri);
                }
                return FrequentContactsCursorLoader.super.loadInBackground();
            }
            protected abstract Uri getUri();
        }

        private class PrimaryStrequentContactsQueryStrategy extends StrequentContactsQueryStrategy {
            @Override
            protected Uri getUri() {
                // Use the original URI requested.
                return mOriginalUri;
            }
        }

        private class FrequentOnlyContactsQueryStrategy extends StrequentContactsQueryStrategy {
            @Override
            protected Uri getUri() {
                // Some phones have a buggy implementation of the Contacts provider which crashes
                // when we query for strequent (starred+frequent) contacts (b/17991485).
                // If this happens, switch to just querying for frequent contacts.
                return Contacts.CONTENT_FREQUENT_URI;
            }
        }

        private class PhoneOnlyStrequentContactsQueryStrategy extends
                StrequentContactsQueryStrategy {
            @Override
            protected Uri getUri() {
                // Some 3rd party ROMs have content provider
                // implementation where invalid SQL queries are returned for regular strequent
                // queries. Using strequent_phone_only query as a fallback to display only phone
                // contacts. This is the last-ditch effort; if this fails, we will display an
                // empty frequent list (b/18354836).
                final String strequentQueryParam = ContactsContract.STREQUENT_PHONE_ONLY;
                // TODO: Handle enterprise contacts post M once contacts provider supports it
                return Contacts.CONTENT_STREQUENT_URI.buildUpon()
                        .appendQueryParameter(strequentQueryParam, "true").build();
            }
        }
    }

    public FrequentContactsCursorQueryData(Context context, String[] projection,
            String selection, String[] selectionArgs, String sortOrder) {
        // TODO: Handle enterprise contacts post M once contacts provider supports it
        super(context, Contacts.CONTENT_STREQUENT_URI, projection, selection, selectionArgs,
                sortOrder);
    }

    @Override
    public BoundCursorLoader createBoundCursorLoader(String bindingId) {
        return new FrequentContactsCursorLoader(bindingId, mContext, mUri, mProjection, mSelection,
                mSelectionArgs, mSortOrder);
    }
}
