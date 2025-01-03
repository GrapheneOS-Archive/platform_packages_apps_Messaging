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

package com.android.messaging.util;

import android.Manifest;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Directory;
import android.provider.ContactsContract.DisplayNameSources;
import android.provider.ContactsContract.PhoneLookup;
import android.provider.ContactsContract.Profile;
import android.text.TextUtils;
import android.view.View;

import com.android.ex.chips.RecipientEntry;
import com.android.messaging.datamodel.CursorQueryData;
import com.android.messaging.datamodel.FrequentContactsCursorQueryData;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.sms.MmsSmsUtils;
import com.android.messaging.ui.contact.AddContactsConfirmationDialog;
import com.google.common.annotations.VisibleForTesting;

/**
 * Utility class including logic to list, filter, and lookup phone and emails in CP2.
 */
@VisibleForTesting
public class ContactUtil {

    /**
     * Index of different columns in phone or email queries. All queries below should confirm to
     * this column content and ordering so that caller can use the uniformed way to process
     * returned cursors.
     */
    public static final int INDEX_CONTACT_ID              = 0;
    public static final int INDEX_DISPLAY_NAME            = 1;
    public static final int INDEX_PHOTO_URI               = 2;
    public static final int INDEX_PHONE_EMAIL             = 3;
    public static final int INDEX_PHONE_EMAIL_TYPE        = 4;
    public static final int INDEX_PHONE_EMAIL_LABEL       = 5;

    // An optional lookup_id column used by PhoneLookupQuery that is needed when querying for
    // contact information.
    public static final int INDEX_LOOKUP_KEY              = 6;

    // An optional _id column to query results that need to be displayed in a list view.
    public static final int INDEX_DATA_ID                 = 7;

    // An optional sort_key column for displaying contact section labels.
    public static final int INDEX_SORT_KEY                = 8;

    // Lookup key column index specific to frequent contacts query.
    public static final int INDEX_LOOKUP_KEY_FREQUENT     = 3;

    /**
     * Constants for listing and filtering phones.
     */
    public static class PhoneQuery {
        public static final String SORT_KEY = Phone.SORT_KEY_PRIMARY;

        public static final String[] PROJECTION = new String[] {
            Phone.CONTACT_ID,                   // 0
            Phone.DISPLAY_NAME_PRIMARY,         // 1
            Phone.PHOTO_THUMBNAIL_URI,          // 2
            Phone.NUMBER,                       // 3
            Phone.TYPE,                         // 4
            Phone.LABEL,                        // 5
            Phone.LOOKUP_KEY,                   // 6
            Phone._ID,                          // 7
            PhoneQuery.SORT_KEY,                // 8
        };
    }

    /**
     * Constants for looking up phone numbers.
     */
    public static class PhoneLookupQuery {
        public static final String[] PROJECTION = new String[] {
            // The _ID field points to the contact id of the content
            PhoneLookup._ID,                          // 0
            PhoneLookup.DISPLAY_NAME,                 // 1
            PhoneLookup.PHOTO_THUMBNAIL_URI,          // 2
            PhoneLookup.NUMBER,                       // 3
            PhoneLookup.TYPE,                         // 4
            PhoneLookup.LABEL,                        // 5
            PhoneLookup.LOOKUP_KEY,                   // 6
            // The data id is not included as part of the projection since it's not part of
            // PhoneLookup. This is okay because the _id field serves as both the data id and
            // contact id. Also we never show the results directly in a list view so we are not
            // concerned about duplicated _id's (namely, the same contact has two same phone
            // numbers)
        };
    }

    public static class FrequentContactQuery {
        public static final String[] PROJECTION = new String[] {
            Contacts._ID,                       // 0
            Contacts.DISPLAY_NAME,              // 1
            Contacts.PHOTO_URI,                 // 2
            Phone.LOOKUP_KEY,                   // 3
        };
    }

    /**
     * Constants for listing and filtering emails.
     */
    public static class EmailQuery {
        public static final String SORT_KEY = Email.SORT_KEY_PRIMARY;

        public static final String[] PROJECTION = new String[] {
            Email.CONTACT_ID,                   // 0
            Email.DISPLAY_NAME_PRIMARY,         // 1
            Email.PHOTO_THUMBNAIL_URI,          // 2
            Email.ADDRESS,                      // 3
            Email.TYPE,                         // 4
            Email.LABEL,                        // 5
            Email.LOOKUP_KEY,                   // 6
            Email._ID,                          // 7
            EmailQuery.SORT_KEY,                // 8
        };
    }

    public static final int INDEX_SELF_QUERY_LOOKUP_KEY = 3;

    /**
     * Constants for querying self from CP2.
     */
    public static class SelfQuery {
        public static final String[] PROJECTION = new String[] {
            Profile._ID,                        // 0
            Profile.DISPLAY_NAME_PRIMARY,       // 1
            Profile.PHOTO_THUMBNAIL_URI,        // 2
            Profile.LOOKUP_KEY                  // 3
            // Phone number, type, label and data_id is not provided in this projection since
            // Profile CONTENT_URI doesn't include this information. Also, we don't need it
            // we just need the name and avatar url.
        };
    }

    public static class StructuredNameQuery {
        public static final String[] PROJECTION = new String[] {
            StructuredName.DISPLAY_NAME,
            StructuredName.GIVEN_NAME,
            StructuredName.FAMILY_NAME,
            StructuredName.PREFIX,
            StructuredName.MIDDLE_NAME,
            StructuredName.SUFFIX
        };
    }

    public static final int INDEX_STRUCTURED_NAME_DISPLAY_NAME = 0;
    public static final int INDEX_STRUCTURED_NAME_GIVEN_NAME = 1;
    public static final int INDEX_STRUCTURED_NAME_FAMILY_NAME = 2;
    public static final int INDEX_STRUCTURED_NAME_PREFIX = 3;
    public static final int INDEX_STRUCTURED_NAME_MIDDLE_NAME = 4;
    public static final int INDEX_STRUCTURED_NAME_SUFFIX = 5;

    public static final long INVALID_CONTACT_ID = -1;

    /**
     * This class is static. No need to create an instance.
     */
    private ContactUtil() {
    }

    /**
     * Shows a contact card or add to contacts dialog for the given contact info
     * @param view The view whose click triggered this to show
     * @param contactId The id of the contact in the android contacts DB
     * @param contactLookupKey The lookup key from contacts DB
     * @param avatarUri Uri to the avatar image if available
     * @param normalizedDestination The normalized phone number or email
     */
    public static void showOrAddContact(final View view, final long contactId,
            final String contactLookupKey, final Uri avatarUri,
            final String normalizedDestination) {
        if (contactId > ParticipantData.PARTICIPANT_CONTACT_ID_NOT_RESOLVED
                && !TextUtils.isEmpty(contactLookupKey)) {
            final Uri lookupUri =
                    ContactsContract.Contacts.getLookupUri(contactId, contactLookupKey);
            ContactsContract.QuickContact.showQuickContact(view.getContext(), view, lookupUri,
                    ContactsContract.QuickContact.MODE_LARGE, null);
        } else if (!TextUtils.isEmpty(normalizedDestination) && !TextUtils.equals(
                normalizedDestination, ParticipantData.getUnknownSenderDestination())) {
            final AddContactsConfirmationDialog dialog = new AddContactsConfirmationDialog(
                    view.getContext(), avatarUri, normalizedDestination);
            dialog.show();
        }
    }

    @VisibleForTesting
    public static CursorQueryData getSelf(final Context context) {
        if (!ContactUtil.hasReadContactsPermission()) {
            return CursorQueryData.getEmptyQueryData();
        }
        return new CursorQueryData(context, Profile.CONTENT_URI, SelfQuery.PROJECTION, null, null,
                null);
    }

    /**
     * Get a list of phones sorted by contact name. One contact may have multiple phones.
     * In that case, each phone will be returned as a separate record in the result cursor.
     */
    @VisibleForTesting
    public static CursorQueryData getPhones(final Context context) {
        if (!ContactUtil.hasReadContactsPermission()) {
            return CursorQueryData.getEmptyQueryData();
        }

        // The AOSP Contacts provider allows adding a ContactsContract.REMOVE_DUPLICATE_ENTRIES
        // query parameter that removes duplicate (raw) numbers. Unfortunately, we can't use that
        // because it causes the some phones' contacts provider to return incorrect sections.
        final Uri uri = Phone.CONTENT_URI.buildUpon().appendQueryParameter(
                ContactsContract.DIRECTORY_PARAM_KEY, String.valueOf(Directory.DEFAULT))
                .appendQueryParameter(Contacts.EXTRA_ADDRESS_BOOK_INDEX, "true")
                .build();

        return new CursorQueryData(context, uri, PhoneQuery.PROJECTION, null, null,
                PhoneQuery.SORT_KEY);
    }

    /**
     * Lookup a destination (phone, email). Supplied destination should be a relatively complete
     * one for this to succeed. PhoneLookup / EmailLookup URI will apply some smartness to do a
     * loose match to see whether there is a contact that matches this destination.
     */
    public static CursorQueryData lookupDestination(final Context context,
            final String destination) {
        if (MmsSmsUtils.isEmailAddress(destination)) {
            return ContactUtil.lookupEmail(context, destination);
        } else {
            return ContactUtil.lookupPhone(context, destination);
        }
    }

    /**
     * Returns whether the search text indicates an email based search or a phone number based one.
     */
    private static boolean shouldFilterForEmail(final String searchText) {
        return searchText != null && searchText.contains("@");
    }

    /**
     * Get a list of destinations (phone, email) matching the partial destination.
     */
    public static CursorQueryData filterDestination(final Context context,
            final String destination) {
        if (shouldFilterForEmail(destination)) {
            return ContactUtil.filterEmails(context, destination);
        } else {
            return ContactUtil.filterPhones(context, destination);
        }
    }

    /**
     * Get a list of destinations (phone, email) matching the partial destination in work profile.
     */
    public static CursorQueryData filterDestinationEnterprise(final Context context,
            final String destination) {
        if (shouldFilterForEmail(destination)) {
            return ContactUtil.filterEmailsEnterprise(context, destination);
        } else {
            return ContactUtil.filterPhonesEnterprise(context, destination);
        }
    }

    /**
     * Get a list of phones matching a search criteria. The search may be on contact name or
     * phone number. In case search is on contact name, all matching contact's phone number
     * will be returned.
     * NOTE: This is visible for testing only, clients should only call filterDestination() since
     * we support email addresses as well.
     */
    @VisibleForTesting
    public static CursorQueryData filterPhones(final Context context, final String query) {
        return filterPhonesInternal(context, Phone.CONTENT_FILTER_URI, query, Directory.DEFAULT);
    }

    /**
     * Similar to {@link #filterPhones(Context, String)}, but search in work profile instead.
     */
    public static CursorQueryData filterPhonesEnterprise(final Context context,
            final String query) {
        return filterPhonesInternal(context, Phone.ENTERPRISE_CONTENT_FILTER_URI, query,
                Directory.ENTERPRISE_DEFAULT);
    }

    private static CursorQueryData filterPhonesInternal(final Context context,
            final Uri phoneFilterBaseUri, final String query, final long directoryId) {
        if (!ContactUtil.hasReadContactsPermission()) {
            return CursorQueryData.getEmptyQueryData();
        }
        Uri phoneFilterUri = buildDirectorySearchUri(phoneFilterBaseUri, query, directoryId);
        return new CursorQueryData(context,
                phoneFilterUri,
                PhoneQuery.PROJECTION, null, null,
                PhoneQuery.SORT_KEY);
    }
    /**
     * Lookup a phone based on a phone number. Supplied phone should be a relatively complete
     * phone number for this to succeed. PhoneLookup URI will apply some smartness to do a
     * loose match to see whether there is a contact that matches this phone.
     * NOTE: This is visible for testing only, clients should only call lookupDestination() since
     * we support email addresses as well.
     */
    @VisibleForTesting
    public static CursorQueryData lookupPhone(final Context context, final String phone) {
        if (!ContactUtil.hasReadContactsPermission()) {
            return CursorQueryData.getEmptyQueryData();
        }

        final Uri uri = getPhoneLookupUri().buildUpon()
                .appendPath(phone).build();

        return new CursorQueryData(context, uri, PhoneLookupQuery.PROJECTION, null, null, null);
    }

    /**
     * Get frequently contacted people. This queries for Contacts.CONTENT_STREQUENT_URI, which
     * includes both starred or frequently contacted people.
     */
    public static CursorQueryData getFrequentContacts(final Context context) {
        if (!ContactUtil.hasReadContactsPermission()) {
            return CursorQueryData.getEmptyQueryData();
        }

        return new FrequentContactsCursorQueryData(context, FrequentContactQuery.PROJECTION,
                null, null, null);
    }

    /**
     * Get a list of emails matching a search criteria. In Bugle, since email is not a common
     * usage scenario, we should only do email search after user typed in a query indicating
     * an intention to search by email (for example, "joe@").
     * NOTE: This is visible for testing only, clients should only call filterDestination() since
     * we support email addresses as well.
     */
    @VisibleForTesting
    public static CursorQueryData filterEmails(final Context context, final String query) {
        return filterEmailsInternal(context, Email.CONTENT_FILTER_URI, query, Directory.DEFAULT);
    }

    /**
     * Similar to {@link #filterEmails(Context, String)}, but search in work profile instead.
     */
    public static CursorQueryData filterEmailsEnterprise(final Context context,
            final String query) {
        return filterEmailsInternal(context, Email.ENTERPRISE_CONTENT_FILTER_URI, query,
                Directory.ENTERPRISE_DEFAULT);
    }

    private static CursorQueryData filterEmailsInternal(final Context context,
            final Uri filterEmailsBaseUri, final String query, final long directoryId) {
        if (!ContactUtil.hasReadContactsPermission()) {
            return CursorQueryData.getEmptyQueryData();
        }
        final Uri filterEmailsUri = buildDirectorySearchUri(filterEmailsBaseUri, query,
                directoryId);
        return new CursorQueryData(context,
                filterEmailsUri,
                PhoneQuery.PROJECTION, null, null,
                PhoneQuery.SORT_KEY);
    }

    /**
     * Lookup emails based a complete email address. Since there is no special logic needed for
     * email lookup, this simply calls filterEmails.
     * NOTE: This is visible for testing only, clients should only call lookupDestination() since
     * we support email addresses as well.
     */
    @VisibleForTesting
    public static CursorQueryData lookupEmail(final Context context, final String email) {
        if (!ContactUtil.hasReadContactsPermission()) {
            return CursorQueryData.getEmptyQueryData();
        }

        final Uri uri = getEmailContentLookupUri().buildUpon()
                .appendPath(email).appendQueryParameter(
                        ContactsContract.DIRECTORY_PARAM_KEY, String.valueOf(Directory.DEFAULT))
                        .build();

        return new CursorQueryData(context, uri, EmailQuery.PROJECTION, null, null,
                EmailQuery.SORT_KEY);
    }

    /**
     * Looks up the structured name for a contact.
     *
     * @param primaryOnly If there are multiple raw contacts, set this flag to return only the
     * name used as the primary display name. Otherwise, this method returns all names.
     */
    private static CursorQueryData lookupStructuredName(final Context context, final long contactId,
            final boolean primaryOnly) {
        if (!ContactUtil.hasReadContactsPermission()) {
            return CursorQueryData.getEmptyQueryData();
        }

        // TODO: Handle enterprise contacts
        final Uri uri = ContactsContract.Contacts.CONTENT_URI.buildUpon()
                .appendPath(String.valueOf(contactId))
                .appendPath(ContactsContract.Contacts.Data.CONTENT_DIRECTORY).build();

        String selection = ContactsContract.Data.MIMETYPE + "=?";
        final String[] selectionArgs = {
                StructuredName.CONTENT_ITEM_TYPE
        };
        if (primaryOnly) {
            selection += " AND " + Contacts.DISPLAY_NAME_PRIMARY + "="
                    + StructuredName.DISPLAY_NAME;
        }

        return new CursorQueryData(context, uri,
                StructuredNameQuery.PROJECTION, selection, selectionArgs, null);
    }

    /**
     * Looks up the first name for a contact. If there are multiple raw
     * contacts, this returns the name that is associated with the contact's
     * primary display name. The name is null when contact id does not exist
     * (possibly because it is a corp contact) or it does not have a first name.
     */
    public static String lookupFirstName(final Context context, final long contactId) {
        if (isEnterpriseContactId(contactId)) {
            return null;
        }
        String firstName = null;
        Cursor nameCursor = null;
        try {
            nameCursor = ContactUtil.lookupStructuredName(context, contactId, true)
                    .performSynchronousQuery();
            if (nameCursor != null && nameCursor.moveToFirst()) {
                firstName = nameCursor.getString(ContactUtil.INDEX_STRUCTURED_NAME_GIVEN_NAME);
            }
        } finally {
            if (nameCursor != null) {
                nameCursor.close();
            }
        }
        return firstName;
    }

    /**
     * Creates a RecipientEntry from the provided data fields (from the contacts cursor).
     * @param firstLevel whether this item is the first entry of this contact in the list.
     */
    public static RecipientEntry createRecipientEntry(final String displayName,
            final int displayNameSource, final String destination, final int destinationType,
            final String destinationLabel, final long contactId, final String lookupKey,
            final long dataId, final String photoThumbnailUri, final boolean firstLevel) {
        if (firstLevel) {
            return RecipientEntry.constructTopLevelEntry(displayName, displayNameSource,
                    destination, destinationType, destinationLabel, contactId, null, dataId,
                    photoThumbnailUri, true, lookupKey);
        } else {
            return RecipientEntry.constructSecondLevelEntry(displayName, displayNameSource,
                    destination, destinationType, destinationLabel, contactId, null, dataId,
                    photoThumbnailUri, true, lookupKey);
        }
    }

    /**
     * Creates a RecipientEntry for PhoneQuery result. The result is then displayed in the
     * contact search drop down or as replacement chips in the chips edit box.
     */
    public static RecipientEntry createRecipientEntryForPhoneQuery(final Cursor cursor,
            final boolean isFirstLevel) {
        final long contactId = cursor.getLong(ContactUtil.INDEX_CONTACT_ID);
        final String displayName = cursor.getString(
                ContactUtil.INDEX_DISPLAY_NAME);
        final String photoThumbnailUri = cursor.getString(
                ContactUtil.INDEX_PHOTO_URI);
        final String destination = cursor.getString(
                ContactUtil.INDEX_PHONE_EMAIL);
        final int destinationType = cursor.getInt(
                ContactUtil.INDEX_PHONE_EMAIL_TYPE);
        final String destinationLabel = cursor.getString(
                ContactUtil.INDEX_PHONE_EMAIL_LABEL);
        final String lookupKey = cursor.getString(
                ContactUtil.INDEX_LOOKUP_KEY);

        // PhoneQuery uses the contact id as the data id ("_id").
        final long dataId = contactId;

        return createRecipientEntry(displayName,
                DisplayNameSources.STRUCTURED_NAME, destination, destinationType,
                destinationLabel, contactId, lookupKey, dataId, photoThumbnailUri,
                isFirstLevel);
    }

    /**
     * Returns if a given contact id is valid.
     */
    public static boolean isValidContactId(final long contactId) {
        return contactId >= 0;
    }

    /**
     * Returns if a given contact id belongs to managed profile.
     */
    public static boolean isEnterpriseContactId(final long contactId) {
        return ContactsContract.Contacts.isEnterpriseContactId(contactId);
    }

    /**
     * Returns Email lookup uri that will query both primary and corp profile
     */
    private static Uri getEmailContentLookupUri() {
        return Email.ENTERPRISE_CONTENT_LOOKUP_URI;
    }

    /**
     * Returns PhoneLookup URI.
     */
    public static Uri getPhoneLookupUri() {
        return PhoneLookup.ENTERPRISE_CONTENT_FILTER_URI;
    }

    public static boolean hasReadContactsPermission() {
        return OsUtil.hasPermission(Manifest.permission.READ_CONTACTS);
    }

    private static Uri buildDirectorySearchUri(final Uri uri, final String query,
            final long directoryId) {
        return uri.buildUpon()
                .appendPath(query).appendQueryParameter(
                        ContactsContract.DIRECTORY_PARAM_KEY, String.valueOf(directoryId))
                .build();
    }
}
