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

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;

import com.android.messaging.BugleApplication;
import com.android.messaging.BuildConfig;
import com.android.messaging.Factory;
import com.android.messaging.datamodel.DatabaseHelper.ConversationColumns;
import com.android.messaging.datamodel.DatabaseHelper.ConversationParticipantsColumns;
import com.android.messaging.datamodel.DatabaseHelper.ParticipantColumns;
import com.android.messaging.datamodel.data.ConversationListItemData;
import com.android.messaging.datamodel.data.ConversationMessageData;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.util.Assert;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.PhoneUtils;
import com.android.messaging.widget.BugleWidgetProvider;
import com.android.messaging.widget.WidgetConversationProvider;
import com.google.common.annotations.VisibleForTesting;

import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.PrintWriter;

/**
 * A centralized provider for Uris exposed by Bugle.
 *  */
public class MessagingContentProvider extends ContentProvider {
    private static final String TAG = LogUtil.BUGLE_TAG;

    @VisibleForTesting
    public static final String AUTHORITY =
        BuildConfig.APPLICATION_ID + ".datamodel.MessagingContentProvider";
    private static final String CONTENT_AUTHORITY = "content://" + AUTHORITY + '/';

    // Conversations query
    private static final String CONVERSATIONS_QUERY = "conversations";

    public static final Uri CONVERSATIONS_URI = Uri.parse(CONTENT_AUTHORITY + CONVERSATIONS_QUERY);
    static final Uri PARTS_URI = Uri.parse(CONTENT_AUTHORITY + DatabaseHelper.PARTS_TABLE);

    // Messages query
    private static final String MESSAGES_QUERY = "messages";

    static final Uri MESSAGES_URI = Uri.parse(CONTENT_AUTHORITY + MESSAGES_QUERY);

    public static final Uri CONVERSATION_MESSAGES_URI = Uri.parse(CONTENT_AUTHORITY +
            MESSAGES_QUERY + "/conversation");

    // Conversation participants query
    private static final String PARTICIPANTS_QUERY = "participants";

    static class ConversationParticipantsQueryColumns extends ParticipantColumns {
        static final String CONVERSATION_ID = ConversationParticipantsColumns.CONVERSATION_ID;
    }

    static final Uri CONVERSATION_PARTICIPANTS_URI = Uri.parse(CONTENT_AUTHORITY +
            PARTICIPANTS_QUERY + "/conversation");

    public static final Uri PARTICIPANTS_URI = Uri.parse(CONTENT_AUTHORITY + PARTICIPANTS_QUERY);

    // Conversation images query
    private static final String CONVERSATION_IMAGES_QUERY = "conversation_images";

    public static final Uri CONVERSATION_IMAGES_URI = Uri.parse(CONTENT_AUTHORITY +
            CONVERSATION_IMAGES_QUERY);

    private static final String DRAFT_IMAGES_QUERY = "draft_images";

    public static final Uri DRAFT_IMAGES_URI = Uri.parse(CONTENT_AUTHORITY +
            DRAFT_IMAGES_QUERY);

    /**
     * Notifies that <i>all</i> data exposed by the provider needs to be refreshed.
     * <p>
     * <b>IMPORTANT!</b> You probably shouldn't be calling this. Prefer to notify more specific
     * uri's instead. Currently only sync uses this, because sync can potentially update many
     * different tables at once.
     */
    public static void notifyEverythingChanged() {
        final Uri uri = Uri.parse(CONTENT_AUTHORITY);
        final Context context = Factory.get().getApplicationContext();
        final ContentResolver cr = context.getContentResolver();
        cr.notifyChange(uri, null);

        // Notify any conversations widgets the conversation list has changed.
        BugleWidgetProvider.notifyConversationListChanged(context);

        // Notify all conversation widgets to update.
        WidgetConversationProvider.notifyMessagesChanged(context, null /*conversationId*/);
    }

    /**
     * Build a participant uri from the conversation id.
     */
    public static Uri buildConversationParticipantsUri(final String conversationId) {
        final Uri.Builder builder = CONVERSATION_PARTICIPANTS_URI.buildUpon();
        builder.appendPath(conversationId);
        return builder.build();
    }

    public static void notifyParticipantsChanged(final String conversationId) {
        final Uri uri = buildConversationParticipantsUri(conversationId);
        final ContentResolver cr = Factory.get().getApplicationContext().getContentResolver();
        cr.notifyChange(uri, null);
    }

    public static void notifyAllMessagesChanged() {
        final ContentResolver cr = Factory.get().getApplicationContext().getContentResolver();
        cr.notifyChange(CONVERSATION_MESSAGES_URI, null);
    }

    public static void notifyAllParticipantsChanged() {
        final ContentResolver cr = Factory.get().getApplicationContext().getContentResolver();
        cr.notifyChange(CONVERSATION_PARTICIPANTS_URI, null);
    }

    // Default value for unknown dimension of image
    public static final int UNSPECIFIED_SIZE = -1;

    // Internal
    private static final int CONVERSATIONS_QUERY_CODE = 10;

    private static final int CONVERSATION_QUERY_CODE = 20;
    private static final int CONVERSATION_MESSAGES_QUERY_CODE = 30;
    private static final int CONVERSATION_PARTICIPANTS_QUERY_CODE = 40;
    private static final int CONVERSATION_IMAGES_QUERY_CODE = 50;
    private static final int DRAFT_IMAGES_QUERY_CODE = 60;
    private static final int PARTICIPANTS_QUERY_CODE = 70;

    // TODO: Move to a better structured URI namespace.
    private static final UriMatcher sURIMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    static {
        sURIMatcher.addURI(AUTHORITY, CONVERSATIONS_QUERY, CONVERSATIONS_QUERY_CODE);
        sURIMatcher.addURI(AUTHORITY, CONVERSATIONS_QUERY + "/*", CONVERSATION_QUERY_CODE);
        sURIMatcher.addURI(AUTHORITY, MESSAGES_QUERY + "/conversation/*",
                CONVERSATION_MESSAGES_QUERY_CODE);
        sURIMatcher.addURI(AUTHORITY, PARTICIPANTS_QUERY + "/conversation/*",
                CONVERSATION_PARTICIPANTS_QUERY_CODE);
        sURIMatcher.addURI(AUTHORITY, PARTICIPANTS_QUERY, PARTICIPANTS_QUERY_CODE);
        sURIMatcher.addURI(AUTHORITY, CONVERSATION_IMAGES_QUERY + "/*",
                CONVERSATION_IMAGES_QUERY_CODE);
        sURIMatcher.addURI(AUTHORITY, DRAFT_IMAGES_QUERY + "/*",
                DRAFT_IMAGES_QUERY_CODE);
    }

    /**
     * Build a messages uri from the conversation id.
     */
    public static Uri buildConversationMessagesUri(final String conversationId) {
        final Uri.Builder builder = CONVERSATION_MESSAGES_URI.buildUpon();
        builder.appendPath(conversationId);
        return builder.build();
    }

    public static void notifyMessagesChanged(final String conversationId) {
        final Uri uri = buildConversationMessagesUri(conversationId);
        final Context context = Factory.get().getApplicationContext();
        final ContentResolver cr = context.getContentResolver();
        cr.notifyChange(uri, null);
        notifyConversationListChanged();

        // Notify the widget the messages changed
        WidgetConversationProvider.notifyMessagesChanged(context, conversationId);
    }

    /**
     * Build a conversation metadata uri from a conversation id.
     */
    public static Uri buildConversationMetadataUri(final String conversationId) {
        final Uri.Builder builder = CONVERSATIONS_URI.buildUpon();
        builder.appendPath(conversationId);
        return builder.build();
    }

    public static void notifyConversationMetadataChanged(final String conversationId) {
        final Uri uri = buildConversationMetadataUri(conversationId);
        final ContentResolver cr = Factory.get().getApplicationContext().getContentResolver();
        cr.notifyChange(uri, null);
        notifyConversationListChanged();
    }

    public static void notifyPartsChanged() {
        final ContentResolver cr = Factory.get().getApplicationContext().getContentResolver();
        cr.notifyChange(PARTS_URI, null);
    }

    public static void notifyConversationListChanged() {
        final Context context = Factory.get().getApplicationContext();
        final ContentResolver cr = context.getContentResolver();
        cr.notifyChange(CONVERSATIONS_URI, null);

        // Notify the widget the conversation list changed
        BugleWidgetProvider.notifyConversationListChanged(context);
    }

    /**
     * Build a conversation images uri from a conversation id.
     */
    public static Uri buildConversationImagesUri(final String conversationId) {
        final Uri.Builder builder = CONVERSATION_IMAGES_URI.buildUpon();
        builder.appendPath(conversationId);
        return builder.build();
    }

    /**
     * Build a draft images uri from a conversation id.
     */
    public static Uri buildDraftImagesUri(final String conversationId) {
        final Uri.Builder builder = DRAFT_IMAGES_URI.buildUpon();
        builder.appendPath(conversationId);
        return builder.build();
    }

    private DatabaseHelper mDatabaseHelper;
    private DatabaseWrapper mDatabaseWrapper;

    public MessagingContentProvider() {
        super();
    }

    @VisibleForTesting
    public void setDatabaseForTest(final DatabaseWrapper db) {
        Assert.isTrue(BugleApplication.isRunningTests());
        mDatabaseWrapper = db;
    }

    private DatabaseWrapper getDatabaseWrapper() {
        if (mDatabaseWrapper == null) {
            mDatabaseWrapper = mDatabaseHelper.getDatabase();
        }
        return mDatabaseWrapper;
    }

    @Override
    public Cursor query(final Uri uri, final String[] projection, String selection,
            final String[] selectionArgs, String sortOrder) {

        // Processes other than self are allowed to temporarily access the media
        // scratch space; we grant uri read access on a case-by-case basis. Dialer app and
        // contacts app would doQuery() on the vCard uri before trying to open the inputStream.
        // There's nothing that we need to return for this uri so just No-Op.
        //if (isMediaScratchSpaceUri(uri)) {
        //    return null;
        //}

        final SQLiteQueryBuilder queryBuilder = new SQLiteQueryBuilder();

        String[] queryArgs = selectionArgs;
        final int match = sURIMatcher.match(uri);
        String groupBy = null;
        String limit = null;
        switch (match) {
            case CONVERSATIONS_QUERY_CODE:
                queryBuilder.setTables(ConversationListItemData.getConversationListView());
                // Hide empty conversations (ones with 0 sort_timestamp)
                queryBuilder.appendWhere(ConversationColumns.SORT_TIMESTAMP + " > 0 ");
                break;
            case CONVERSATION_QUERY_CODE:
                queryBuilder.setTables(ConversationListItemData.getConversationListView());
                if (uri.getPathSegments().size() == 2) {
                    queryBuilder.appendWhere(ConversationColumns._ID + "=?");
                    // Get the conversation id from the uri
                    queryArgs = prependArgs(queryArgs, uri.getPathSegments().get(1));
                } else {
                    throw new IllegalArgumentException("Malformed URI " + uri);
                }
                break;
            case CONVERSATION_PARTICIPANTS_QUERY_CODE:
                queryBuilder.setTables(DatabaseHelper.PARTICIPANTS_TABLE);
                if (uri.getPathSegments().size() == 3 &&
                        TextUtils.equals(uri.getPathSegments().get(1), "conversation")) {
                    queryBuilder.appendWhere(ParticipantColumns._ID + " IN ( " + "SELECT "
                            + ConversationParticipantsColumns.PARTICIPANT_ID + " AS "
                            + ParticipantColumns._ID
                            + " FROM " + DatabaseHelper.CONVERSATION_PARTICIPANTS_TABLE
                            + " WHERE " + ConversationParticipantsColumns.CONVERSATION_ID
                            + " =? UNION SELECT " + ParticipantColumns._ID + " FROM "
                            + DatabaseHelper.PARTICIPANTS_TABLE + " WHERE "
                            + ParticipantColumns.SUB_ID + " != "
                            + ParticipantData.OTHER_THAN_SELF_SUB_ID + " )");
                    // Get the conversation id from the uri
                    queryArgs = prependArgs(queryArgs, uri.getPathSegments().get(2));
                } else {
                    throw new IllegalArgumentException("Malformed URI " + uri);
                }
                break;
            case PARTICIPANTS_QUERY_CODE:
                queryBuilder.setTables(DatabaseHelper.PARTICIPANTS_TABLE);
                if (uri.getPathSegments().size() != 1) {
                    throw new IllegalArgumentException("Malformed URI " + uri);
                }
                break;
            case CONVERSATION_MESSAGES_QUERY_CODE:
                if (uri.getPathSegments().size() == 3 &&
                    TextUtils.equals(uri.getPathSegments().get(1), "conversation")) {
                    // Get the conversation id from the uri
                    final String conversationId = uri.getPathSegments().get(2);

                    // We need to handle this query differently, instead of falling through to the
                    // generic query call at the bottom. For performance reasons, the conversation
                    // messages query is executed as a raw query. It is invalid to specify
                    // selection/sorting for this query.

                    if (selection == null && selectionArgs == null && sortOrder == null) {
                        return queryConversationMessages(conversationId, uri);
                    } else {
                        throw new IllegalArgumentException(
                                "Cannot set selection or sort order with this query");
                    }
                } else {
                    throw new IllegalArgumentException("Malformed URI " + uri);
                }
            case CONVERSATION_IMAGES_QUERY_CODE:
                queryBuilder.setTables(ConversationImagePartsView.getViewName());
                if (uri.getPathSegments().size() == 2) {
                    // Exclude draft.
                    queryBuilder.appendWhere(
                            ConversationImagePartsView.Columns.CONVERSATION_ID + " =? AND " +
                                    ConversationImagePartsView.Columns.STATUS + "<>" +
                                    MessageData.BUGLE_STATUS_OUTGOING_DRAFT);
                    // Get the conversation id from the uri
                    queryArgs = prependArgs(queryArgs, uri.getPathSegments().get(1));
                } else {
                    throw new IllegalArgumentException("Malformed URI " + uri);
                }
                break;
            case DRAFT_IMAGES_QUERY_CODE:
                queryBuilder.setTables(ConversationImagePartsView.getViewName());
                if (uri.getPathSegments().size() == 2) {
                    // Draft only.
                    queryBuilder.appendWhere(
                            ConversationImagePartsView.Columns.CONVERSATION_ID + " =? AND " +
                                    ConversationImagePartsView.Columns.STATUS + "=" +
                                    MessageData.BUGLE_STATUS_OUTGOING_DRAFT);
                    // Get the conversation id from the uri
                    queryArgs = prependArgs(queryArgs, uri.getPathSegments().get(1));
                } else {
                    throw new IllegalArgumentException("Malformed URI " + uri);
                }
                break;
            default: {
                throw new IllegalArgumentException("Unknown URI " + uri);
            }
        }

        final Cursor cursor = getDatabaseWrapper().query(queryBuilder, projection, selection,
                queryArgs, groupBy, null, sortOrder, limit);
        cursor.setNotificationUri(getContext().getContentResolver(), uri);
        return cursor;
    }

    private Cursor queryConversationMessages(final String conversationId, final Uri notifyUri) {
        final String[] queryArgs = { conversationId };
        final Cursor cursor = getDatabaseWrapper().rawQuery(
                ConversationMessageData.getConversationMessagesQuerySql(), queryArgs);
        cursor.setNotificationUri(getContext().getContentResolver(), notifyUri);
        return cursor;
    }

    @Override
    public String getType(final Uri uri) {
        final StringBuilder sb = new
                StringBuilder("vnd.android.cursor.dir/vnd.android.messaging.");

        switch (sURIMatcher.match(uri)) {
            case CONVERSATIONS_QUERY_CODE: {
                sb.append(CONVERSATIONS_QUERY);
                break;
            }
            default: {
                throw new IllegalArgumentException("Unknown URI: " + uri);
            }
        }
        return sb.toString();
    }

    protected DatabaseHelper getDatabase() {
        return DatabaseHelper.getInstance(getContext());
    }

    @Override
    public ParcelFileDescriptor openFile(final Uri uri, final String fileMode)
            throws FileNotFoundException {
        throw new IllegalArgumentException("openFile not supported: " + uri);
    }

    @Override
    public Uri insert(final Uri uri, final ContentValues values) {
        throw new IllegalStateException("Insert not supported " + uri);
    }

    @Override
    public int delete(final Uri uri, final String selection, final String[] selectionArgs) {
        throw new IllegalArgumentException("Delete not supported: " + uri);
    }

    @Override
    public int update(final Uri uri, final ContentValues values, final String selection,
            final String[] selectionArgs) {
        throw new IllegalArgumentException("Update not supported: " + uri);
    }

    /**
     * Prepends new arguments to the existing argument list.
     *
     * @param oldArgList The current list of arguments. May be {@code null}
     * @param args The new arguments to prepend
     * @return A new argument list with the given arguments prepended
     */
    private String[] prependArgs(final String[] oldArgList, final String... args) {
        if (args == null || args.length == 0) {
            return oldArgList;
        }
        final int oldArgCount = (oldArgList == null ? 0 : oldArgList.length);
        final int newArgCount = args.length;

        final String[] newArgs = new String[oldArgCount + newArgCount];
        System.arraycopy(args, 0, newArgs, 0, newArgCount);
        if (oldArgCount > 0) {
            System.arraycopy(oldArgList, 0, newArgs, newArgCount, oldArgCount);
        }
        return newArgs;
    }
    /**
     * {@inheritDoc}
     */
    @Override
    public void dump(final FileDescriptor fd, final PrintWriter writer, final String[] args) {
        // First dump out the default SMS app package name
        String defaultSmsApp = PhoneUtils.getDefault().getDefaultSmsApp();
        if (TextUtils.isEmpty(defaultSmsApp)) {
            defaultSmsApp = "None";
        }
        writer.println("Default SMS app: " + defaultSmsApp);
        // Now dump logs
        LogUtil.dump(writer);
    }

    @Override
    public boolean onCreate() {
        // This is going to wind up calling into createDatabase() below.
        mDatabaseHelper = (DatabaseHelper) getDatabase();
        // We cannot initialize mDatabaseWrapper yet as the Factory may not be initialized
        return true;
    }
}
