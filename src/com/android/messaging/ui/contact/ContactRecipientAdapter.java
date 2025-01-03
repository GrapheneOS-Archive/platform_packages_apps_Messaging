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
package com.android.messaging.ui.contact;

import android.content.Context;
import android.database.Cursor;
import android.database.MergeCursor;
import android.text.TextUtils;
import android.text.util.Rfc822Token;
import android.text.util.Rfc822Tokenizer;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Filter;
import android.widget.TextView;

import com.android.ex.chips.BaseRecipientAdapter;
import com.android.ex.chips.RecipientAlternatesAdapter;
import com.android.ex.chips.RecipientAlternatesAdapter.RecipientMatchCallback;
import com.android.ex.chips.RecipientEntry;
import com.android.messaging.R;
import com.android.messaging.util.Assert;
import com.android.messaging.util.Assert.DoesNotRunOnMainThread;
import com.android.messaging.util.BugleGservices;
import com.android.messaging.util.BugleGservicesKeys;
import com.android.messaging.util.ContactRecipientEntryUtils;
import com.android.messaging.util.ContactUtil;
import com.android.messaging.util.PhoneUtils;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * An extension on the base {@link BaseRecipientAdapter} that uses data layer from Bugle,
 * such as the ContactRecipientPhotoManager that uses our own MediaResourceManager, and
 * contact lookup that relies on ContactUtil. It provides data source and filtering ability
 * for {@link ContactRecipientAutoCompleteView}
 */
public final class ContactRecipientAdapter extends BaseRecipientAdapter {
    private static final int WORD_DIRECTORY_HEADER_POS_NONE = -1;
    /**
     * Stores the index of work directory header.
     */
    private int mWorkDirectoryHeaderPos = WORD_DIRECTORY_HEADER_POS_NONE;
    private final LayoutInflater mInflater;

    /**
     * Type of directory entry.
     */
    private static final int ENTRY_TYPE_DIRECTORY = RecipientEntry.ENTRY_TYPE_SIZE;

    public ContactRecipientAdapter(final Context context,
            final ContactListItemView.HostInterface clivHost) {
        this(context, Integer.MAX_VALUE, QUERY_TYPE_PHONE, clivHost);
    }

    public ContactRecipientAdapter(final Context context, final int preferredMaxResultCount,
            final int queryMode, final ContactListItemView.HostInterface clivHost) {
        super(context, preferredMaxResultCount, queryMode);
        setPhotoManager(new ContactRecipientPhotoManager(context, clivHost));
        mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    }

    @Override
    public boolean forceShowAddress() {
        // We should always use the SingleRecipientAddressAdapter
        // And never use the RecipientAlternatesAdapter
        return true;
    }

    @Override
    public Filter getFilter() {
        return new ContactFilter();
    }

    /**
     * A Filter for a RecipientEditTextView that queries Bugle's ContactUtil for auto-complete
     * results.
     */
    public class ContactFilter extends Filter {

        // Used to sort filtered contacts when it has combined results from email and phone.
        private final RecipientEntryComparator mComparator = new RecipientEntryComparator();

        /**
         * Returns a cursor containing the filtered results in contacts given the search text,
         * and a boolean indicating whether the results are sorted.
         *
         * The queries are synchronously performed since this is not run on the main thread.
         *
         * Some locales (e.g. JPN) expect email addresses to be auto-completed for MMS.
         * If this is the case, perform two queries on phone number followed by email and
         * return the merged results.
         */
        @DoesNotRunOnMainThread
        private CursorResult getFilteredResultsCursor(final String searchText) {
            Assert.isNotMainThread();
            if (BugleGservices.get().getBoolean(
                    BugleGservicesKeys.ALWAYS_AUTOCOMPLETE_EMAIL_ADDRESS,
                    BugleGservicesKeys.ALWAYS_AUTOCOMPLETE_EMAIL_ADDRESS_DEFAULT)) {

                final Cursor personalFilterPhonesCursor = ContactUtil
                        .filterPhones(getContext(), searchText).performSynchronousQuery();
                final Cursor personalFilterEmailsCursor = ContactUtil
                        .filterEmails(getContext(), searchText).performSynchronousQuery();
                final Cursor personalCursor = new MergeCursor(
                        new Cursor[]{personalFilterEmailsCursor, personalFilterPhonesCursor});
                final CursorResult cursorResult =
                        new CursorResult(personalCursor, false /* sorted */);

                final Cursor enterpriseFilterPhonesCursor = ContactUtil.filterPhonesEnterprise(
                        getContext(), searchText).performSynchronousQuery();
                final Cursor enterpriseFilterEmailsCursor = ContactUtil.filterEmailsEnterprise(
                        getContext(), searchText).performSynchronousQuery();
                final Cursor enterpriseCursor = new MergeCursor(
                        new Cursor[]{enterpriseFilterEmailsCursor,
                                enterpriseFilterPhonesCursor});
                cursorResult.enterpriseCursor = enterpriseCursor;
                return cursorResult;
            } else {
                final Cursor personalFilterDestinationCursor = ContactUtil
                        .filterDestination(getContext(), searchText).performSynchronousQuery();
                final CursorResult cursorResult = new CursorResult(personalFilterDestinationCursor,
                        true);

                final Cursor enterpriseFilterDestinationCursor = ContactUtil
                        .filterDestinationEnterprise(getContext(), searchText)
                        .performSynchronousQuery();
                cursorResult.enterpriseCursor = enterpriseFilterDestinationCursor;
                return cursorResult;
            }
        }

        @Override
        protected FilterResults performFiltering(final CharSequence constraint) {
            Assert.isNotMainThread();
            final FilterResults results = new FilterResults();

            // No query, return empty results.
            if (TextUtils.isEmpty(constraint)) {
                clearTempEntries();
                return results;
            }

            final String searchText = constraint.toString();

            // Query for auto-complete results, since performFiltering() is not done on the
            // main thread, perform the cursor loader queries directly.

            final CursorResult cursorResult = getFilteredResultsCursor(searchText);
            final List<RecipientEntry> entries = new ArrayList<>();

            // First check if the constraint is a valid SMS destination. If so, add the
            // destination as a suggestion item to the drop down.
            if (PhoneUtils.isValidSmsMmsDestination(searchText)) {
                entries.add(ContactRecipientEntryUtils
                        .constructSendToDestinationEntry(searchText));
            }

            // Only show work directory header if more than one result in work directory.
            int workDirectoryHeaderPos = WORD_DIRECTORY_HEADER_POS_NONE;
            if (cursorResult.enterpriseCursor != null
                    && cursorResult.enterpriseCursor.getCount() > 0) {
                if (cursorResult.personalCursor != null) {
                    workDirectoryHeaderPos = entries.size();
                    workDirectoryHeaderPos += cursorResult.personalCursor.getCount();
                }
            }

            final Cursor[] cursors = new Cursor[]{cursorResult.personalCursor,
                    cursorResult.enterpriseCursor};
            for (Cursor cursor : cursors) {
                if (cursor != null) {
                    try {
                        final List<RecipientEntry> tempEntries = new ArrayList<>();
                        HashSet<Long> existingContactIds = new HashSet<>();
                        while (cursor.moveToNext()) {
                            // Make sure there's only one first-level contact (i.e. contact for
                            // which we show the avatar picture and name) for every contact id.
                            final long contactId = cursor.getLong(ContactUtil.INDEX_CONTACT_ID);
                            final boolean isFirstLevel = !existingContactIds.contains(contactId);
                            if (isFirstLevel) {
                                existingContactIds.add(contactId);
                            }
                            tempEntries.add(ContactUtil.createRecipientEntryForPhoneQuery(cursor,
                                    isFirstLevel));
                        }

                        if (!cursorResult.isSorted) {
                            Collections.sort(tempEntries, mComparator);
                        }
                        entries.addAll(tempEntries);
                    } finally {
                        cursor.close();
                    }
                }
            }
            results.values = new ContactReceipientFilterResult(entries, workDirectoryHeaderPos);
            results.count = 1;
            return results;
        }

        @Override
        protected void publishResults(final CharSequence constraint, final FilterResults results) {
            mCurrentConstraint = constraint;
            clearTempEntries();

            final ContactReceipientFilterResult contactReceipientFilterResult
                    = (ContactReceipientFilterResult) results.values;
            if (contactReceipientFilterResult != null) {
                mWorkDirectoryHeaderPos = contactReceipientFilterResult.workDirectoryPos;
                if (contactReceipientFilterResult.recipientEntries != null) {
                    updateEntries(contactReceipientFilterResult.recipientEntries);
                } else {
                    updateEntries(Collections.<RecipientEntry>emptyList());
                }
            }
        }

        private class RecipientEntryComparator implements Comparator<RecipientEntry> {

            private final Collator mCollator;

            public RecipientEntryComparator() {
                mCollator = Collator.getInstance(Locale.getDefault());
                mCollator.setStrength(Collator.PRIMARY);
            }

            /**
             * Compare two RecipientEntry's, first by locale-aware display name comparison, then by
             * contact id comparison, finally by first-level-ness comparison.
             */
            @Override
            public int compare(RecipientEntry lhs, RecipientEntry rhs) {
                // Send-to-destinations always appear before everything else.
                final boolean sendToLhs = ContactRecipientEntryUtils
                        .isSendToDestinationContact(lhs);
                final boolean sendToRhs = ContactRecipientEntryUtils
                        .isSendToDestinationContact(lhs);
                if (sendToLhs != sendToRhs) {
                    if (sendToLhs) {
                        return -1;
                    } else if (sendToRhs) {
                        return 1;
                    }
                }

                final int displayNameCompare = mCollator.compare(lhs.getDisplayName(),
                        rhs.getDisplayName());
                if (displayNameCompare != 0) {
                    return displayNameCompare;
                }

                // Long.compare could accomplish the following three lines, but this is only
                // available in API 19+
                final long lhsContactId = lhs.getContactId();
                final long rhsContactId = rhs.getContactId();
                final int contactCompare = lhsContactId < rhsContactId ? -1 :
                        (lhsContactId == rhsContactId ? 0 : 1);
                if (contactCompare != 0) {
                    return contactCompare;
                }

                // These are the same contact. Make sure first-level contacts always
                // appear at the front.
                if (lhs.isFirstLevel()) {
                    return -1;
                } else if (rhs.isFirstLevel()) {
                    return 1;
                } else {
                    return 0;
                }
            }
        }

        private class CursorResult {

            public final Cursor personalCursor;

            public Cursor enterpriseCursor;

            public final boolean isSorted;

            public CursorResult(Cursor personalCursor, boolean isSorted) {
                this.personalCursor = personalCursor;
                this.isSorted = isSorted;
            }
        }

        private class ContactReceipientFilterResult {
            /**
             * Recipient entries in all directories.
             */
            public final List<RecipientEntry> recipientEntries;

            /**
             * Index of row that showing work directory header.
             */
            public final int workDirectoryPos;

            public ContactReceipientFilterResult(List<RecipientEntry> recipientEntries,
                    int workDirectoryPos) {
                this.recipientEntries = recipientEntries;
                this.workDirectoryPos = workDirectoryPos;
            }
        }
    }

    /**
     * Called when we need to substitute temporary recipient chips with better alternatives.
     * For example, if a list of comma-delimited phone numbers are pasted into the edit box,
     * we want to be able to look up in the ContactUtil for exact matches and get contact
     * details such as name and photo thumbnail for the contact to display a better chip.
     */
    @Override
    public void getMatchingRecipients(final ArrayList<String> inAddresses,
            final RecipientMatchCallback callback) {
        final int addressesSize = Math.min(
                RecipientAlternatesAdapter.MAX_LOOKUPS, inAddresses.size());
        final HashSet<String> addresses = new HashSet<String>();
        for (int i = 0; i < addressesSize; i++) {
            final Rfc822Token[] tokens = Rfc822Tokenizer.tokenize(inAddresses.get(i).toLowerCase());
            addresses.add(tokens.length > 0 ? tokens[0].getAddress() : inAddresses.get(i));
        }

        final Map<String, RecipientEntry> recipientEntries =
                new HashMap<String, RecipientEntry>();
        // query for each address
        for (final String address : addresses) {
            final Cursor cursor = ContactUtil.lookupDestination(getContext(), address)
                    .performSynchronousQuery();
            if (cursor != null) {
                try {
                    if (cursor.moveToNext()) {
                        // There may be multiple matches to the same number, always take the
                        // first match.
                        // TODO: May need to consider if there's an existing conversation
                        // that matches this particular contact and prioritize that contact.
                        final RecipientEntry entry =
                                ContactUtil.createRecipientEntryForPhoneQuery(cursor, true);
                        recipientEntries.put(address, entry);
                    }

                } finally {
                    cursor.close();
                }
            }
        }

        // report matches
        callback.matchesFound(recipientEntries);
    }

    /**
     * We handle directory header here and then delegate the work of creating recipient views to
     * the {@link BaseRecipientAdapter}. Please notice that we need to fix the position
     * before passing to {@link BaseRecipientAdapter} because it is not aware of the existence of
     * directory headers.
     */
    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        TextView textView;
        if (isDirectoryEntry(position)) {
            if (convertView == null) {
                textView = (TextView) mInflater.inflate(R.layout.work_directory_header, parent,
                        false);
            } else {
                textView = (TextView) convertView;
            }
            return textView;
        }
        return super.getView(fixPosition(position), convertView, parent);
    }

    @Override
    public RecipientEntry getItem(int position) {
        if (isDirectoryEntry(position)) {
            return null;
        }
        return super.getItem(fixPosition(position));
    }

    @Override
    public int getViewTypeCount() {
        return RecipientEntry.ENTRY_TYPE_SIZE + 1;
    }

    @Override
    public int getItemViewType(int position) {
        if (isDirectoryEntry(position)) {
            return ENTRY_TYPE_DIRECTORY;
        }
        return super.getItemViewType(fixPosition(position));
    }

    @Override
    public boolean isEnabled(int position) {
        if (isDirectoryEntry(position)) {
            return false;
        }
        return super.isEnabled(fixPosition(position));
    }

    @Override
    public int getCount() {
        return super.getCount() + ((hasWorkDirectoryHeader()) ? 1 : 0);
    }

    private boolean isDirectoryEntry(int position) {
        return position == mWorkDirectoryHeaderPos;
    }

    /**
     * @return the position of items without counting directory headers.
     */
    private int fixPosition(int position) {
        if (hasWorkDirectoryHeader()) {
            Assert.isTrue(position != mWorkDirectoryHeaderPos);
            if (position > mWorkDirectoryHeaderPos) {
                return position - 1;
            }
        }
        return position;
    }

    private boolean hasWorkDirectoryHeader() {
        return mWorkDirectoryHeaderPos != WORD_DIRECTORY_HEADER_POS_NONE;
    }

}
