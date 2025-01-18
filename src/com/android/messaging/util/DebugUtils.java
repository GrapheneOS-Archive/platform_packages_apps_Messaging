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

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Environment;
import android.telephony.SmsMessage;
import android.text.TextUtils;
import android.widget.ArrayAdapter;

import com.android.messaging.Factory;
import com.android.messaging.R;
import com.android.messaging.datamodel.SyncManager;
import com.android.messaging.datamodel.action.DumpDatabaseAction;
import com.android.messaging.datamodel.action.LogTelephonyDatabaseAction;
import com.android.messaging.sms.MmsUtils;
import com.android.messaging.ui.UIIntents;
import com.android.messaging.ui.debug.DebugSmsMmsFromDumpFileDialogFragment;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.StreamCorruptedException;

public class DebugUtils {
    private static final String TAG = "bugle.util.DebugUtils";

    private static boolean sDebugNoise;
    private static boolean sDebugClassZeroSms;
    private static MediaPlayer [] sMediaPlayer;
    private static final Object sLock = new Object();

    public static final int DEBUG_SOUND_SERVER_REQUEST = 0;
    public static final int DEBUG_SOUND_DB_OP = 1;

    public static void maybePlayDebugNoise(final Context context, final int sound) {
        if (sDebugNoise) {
            synchronized (sLock) {
                try {
                    if (sMediaPlayer == null) {
                        sMediaPlayer = new MediaPlayer[2];
                        sMediaPlayer[DEBUG_SOUND_SERVER_REQUEST] =
                                MediaPlayer.create(context, R.raw.server_request_debug);
                        sMediaPlayer[DEBUG_SOUND_DB_OP] =
                                MediaPlayer.create(context, R.raw.db_op_debug);
                        sMediaPlayer[DEBUG_SOUND_DB_OP].setVolume(1.0F, 1.0F);
                        sMediaPlayer[DEBUG_SOUND_SERVER_REQUEST].setVolume(0.3F, 0.3F);
                    }
                    if (sMediaPlayer[sound] != null) {
                        sMediaPlayer[sound].start();
                    }
                } catch (final IllegalArgumentException e) {
                    LogUtil.e(TAG, "MediaPlayer exception", e);
                } catch (final SecurityException e) {
                    LogUtil.e(TAG, "MediaPlayer exception", e);
                } catch (final IllegalStateException e) {
                    LogUtil.e(TAG, "MediaPlayer exception", e);
                }
            }
        }
    }

    public static boolean isDebugEnabled() {
        return BugleGservices.get().getBoolean(BugleGservicesKeys.ENABLE_DEBUGGING_FEATURES,
                BugleGservicesKeys.ENABLE_DEBUGGING_FEATURES_DEFAULT);
    }

    public abstract static class DebugAction {
        String mTitle;
        public DebugAction(final String title) {
            mTitle = title;
        }

        @Override
        public String toString() {
            return mTitle;
        }

        public abstract void run();
    }

    public static void showDebugOptions(final Activity host) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(host);

        final ArrayAdapter<DebugAction> arrayAdapter = new ArrayAdapter<DebugAction>(
                host, android.R.layout.simple_list_item_1);

        arrayAdapter.add(new DebugAction("Dump Database") {
            @Override
            public void run() {
                DumpDatabaseAction.dumpDatabase();
            }
        });

        arrayAdapter.add(new DebugAction("Log Telephony Data") {
            @Override
            public void run() {
                LogTelephonyDatabaseAction.dumpDatabase();
            }
        });

        arrayAdapter.add(new DebugAction("Toggle Noise") {
            @Override
            public void run() {
                sDebugNoise = !sDebugNoise;
            }
        });

        arrayAdapter.add(new DebugAction("Force sync SMS") {
            @Override
            public void run() {
                final BuglePrefs prefs = BuglePrefs.getApplicationPrefs();
                prefs.putLong(BuglePrefsKeys.LAST_FULL_SYNC_TIME, -1);
                SyncManager.forceSync();
            }
        });

        arrayAdapter.add(new DebugAction("Sync SMS") {
            @Override
            public void run() {
                SyncManager.sync();
            }
        });

        arrayAdapter.add(new DebugAction("Load SMS/MMS from dump file") {
            @Override
            public void run() {
                new DebugSmsMmsDumpTask(host,
                        DebugSmsMmsFromDumpFileDialogFragment.ACTION_LOAD).executeOnThreadPool();
            }
        });

        arrayAdapter.add(new DebugAction("Email SMS/MMS dump file") {
            @Override
            public void run() {
                new DebugSmsMmsDumpTask(host,
                        DebugSmsMmsFromDumpFileDialogFragment.ACTION_EMAIL).executeOnThreadPool();
            }
        });

        arrayAdapter.add(new DebugAction("MMS Config...") {
            @Override
            public void run() {
                UIIntents.get().launchDebugMmsConfigActivity(host);
            }
        });

        arrayAdapter.add(new DebugAction(sDebugClassZeroSms ? "Turn off Class 0 sms test" :
                "Turn on Class Zero test") {
            @Override
            public void run() {
                sDebugClassZeroSms = !sDebugClassZeroSms;
            }
        });

        arrayAdapter.add(new DebugAction("Test sharing a file URI") {
            @Override
            public void run() {
                shareFileUri();
            }
        });

        builder.setAdapter(arrayAdapter,
                new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(final DialogInterface arg0, final int pos) {
                arrayAdapter.getItem(pos).run();
            }
        });

        builder.create().show();
    }

    /**
     * Task to list all the dump files and perform an action on it
     */
    private static class DebugSmsMmsDumpTask extends SafeAsyncTask<Void, Void, String[]> {
        private final String mAction;
        private final Activity mHost;

        public DebugSmsMmsDumpTask(final Activity host, final String action) {
            mHost = host;
            mAction = action;
        }

        @Override
        protected void onPostExecute(final String[] result) {
            if (result == null || result.length < 1) {
                return;
            }
            final FragmentManager fragmentManager = mHost.getFragmentManager();
            final FragmentTransaction ft = fragmentManager.beginTransaction();
            final DebugSmsMmsFromDumpFileDialogFragment dialog =
                    DebugSmsMmsFromDumpFileDialogFragment.newInstance(result, mAction);
            dialog.show(fragmentManager, ""/*tag*/);
        }

        @Override
        protected String[] doInBackgroundTimed(final Void... params) {
            final File dir = DebugUtils.getDebugFilesDir();
            return dir.list(new FilenameFilter() {
                @Override
                public boolean accept(final File dir, final String filename) {
                    return filename != null
                            && ((mAction == DebugSmsMmsFromDumpFileDialogFragment.ACTION_EMAIL
                            && filename.equals(DumpDatabaseAction.DUMP_NAME))
                            || filename.startsWith(MmsUtils.MMS_DUMP_PREFIX)
                            || filename.startsWith(MmsUtils.SMS_DUMP_PREFIX));
                }
            });
        }
    }

    /**
     * Dump the received raw SMS data into a file on external storage
     *
     * @param id The ID to use as part of the dump file name
     * @param messages The raw SMS data
     */
    public static void dumpSms(final long id, final android.telephony.SmsMessage[] messages,
            final String format) {
        try {
            final String dumpFileName = MmsUtils.SMS_DUMP_PREFIX + Long.toString(id);
            final File dumpFile = DebugUtils.getDebugFile(dumpFileName, true);
            if (dumpFile != null) {
                final FileOutputStream fos = new FileOutputStream(dumpFile);
                final DataOutputStream dos = new DataOutputStream(fos);
                try {
                    final int chars = (TextUtils.isEmpty(format) ? 0 : format.length());
                    dos.writeInt(chars);
                    if (chars > 0) {
                        dos.writeUTF(format);
                    }
                    dos.writeInt(messages.length);
                    for (final android.telephony.SmsMessage message : messages) {
                        final byte[] pdu = message.getPdu();
                        dos.writeInt(pdu.length);
                        dos.write(pdu, 0, pdu.length);
                    }
                    dos.flush();
                } finally {
                    dos.close();
                    ensureReadable(dumpFile);
                }
            }
        } catch (final IOException e) {
            LogUtil.e(LogUtil.BUGLE_TAG, "dumpSms: " + e, e);
        }
    }

    /**
     * Load MMS/SMS from the dump file
     */
    public static SmsMessage[] retreiveSmsFromDumpFile(final String dumpFileName) {
        SmsMessage[] messages = null;
        final File inputFile = DebugUtils.getDebugFile(dumpFileName, false);
        if (inputFile != null) {
            FileInputStream fis = null;
            DataInputStream dis = null;
            try {
                fis = new FileInputStream(inputFile);
                dis = new DataInputStream(fis);

                // SMS dump
                String format = null;
                final int chars = dis.readInt();
                if (chars > 0) {
                    format = dis.readUTF();
                }
                final int count = dis.readInt();
                final SmsMessage[] messagesTemp = new SmsMessage[count];
                for (int i = 0; i < count; i++) {
                    final int length = dis.readInt();
                    final byte[] pdu = new byte[length];
                    dis.read(pdu, 0, length);
                    messagesTemp[i] = SmsMessage.createFromPdu(pdu, format);
                }
                messages = messagesTemp;
            } catch (final FileNotFoundException e) {
                // Nothing to do
            } catch (final StreamCorruptedException e) {
                // Nothing to do
            } catch (final IOException e) {
                // Nothing to do
            } finally {
                if (dis != null) {
                    try {
                        dis.close();
                    } catch (final IOException e) {
                        // Nothing to do
                    }
                }
            }
        }
        return messages;
    }

    public static File getDebugFile(final String fileName, final boolean create) {
        final File dir = getDebugFilesDir();
        final File file = new File(dir, fileName);
        if (create && file.exists()) {
            file.delete();
        }
        return file;
    }

    public static File getDebugFilesDir() {
        final File dir = Environment.getExternalStorageDirectory();
        return dir;
    }

    /**
     * Load MMS/SMS from the dump file
     */
    public static byte[] receiveFromDumpFile(final String dumpFileName) {
        byte[] data = null;
        try {
            final File inputFile = getDebugFile(dumpFileName, false);
            if (inputFile != null) {
                final FileInputStream fis = new FileInputStream(inputFile);
                final BufferedInputStream bis = new BufferedInputStream(fis);
                try {
                    // dump file
                    data = bis.readAllBytes();
                    if (data == null || data.length < 1) {
                        LogUtil.e(LogUtil.BUGLE_TAG, "receiveFromDumpFile: empty data");
                    }
                } finally {
                    bis.close();
                }
            }
        } catch (final IOException e) {
            LogUtil.e(LogUtil.BUGLE_TAG, "receiveFromDumpFile: " + e, e);
        }
        return data;
    }

    @SuppressLint("SetWorldReadable")
    public static void ensureReadable(final File file) {
        if (file.exists()){
            file.setReadable(true, false);
        }
    }

    /**
     * Logs the name of the method that is currently executing, e.g. "MyActivity.onCreate". This is
     * useful for surgically adding logs for tracing execution while debugging.
     * <p>
     * NOTE: This method retrieves the current thread's stack trace, which adds runtime overhead.
     * However, this method is only executed on eng builds if DEBUG logs are loggable.
     */
    public static void logCurrentMethod(String tag) {
        if (!LogUtil.isLoggable(tag, LogUtil.DEBUG)) {
            return;
        }
        StackTraceElement caller = getCaller(1);
        if (caller == null) {
            return;
        }
        String className = caller.getClassName();
        // Strip off the package name
        int lastDot = className.lastIndexOf('.');
        if (lastDot > -1) {
            className = className.substring(lastDot + 1);
        }
        LogUtil.d(tag, className + "." + caller.getMethodName());
    }

    /**
     * Returns info about the calling method. The {@code depth} parameter controls how far back to
     * go. For example, if foo() calls bar(), and bar() calls getCaller(0), it returns info about
     * bar(). If bar() instead called getCaller(1), it would return info about foo(). And so on.
     * <p>
     * NOTE: This method retrieves the current thread's stack trace, which adds runtime overhead.
     * It should only be used in production where necessary to gather context about an error or
     * unexpected event (e.g. the {@link Assert} class uses it).
     *
     * @return stack frame information for the caller (if found); otherwise {@code null}.
     */
    public static StackTraceElement getCaller(int depth) {
        // If the signature of this method is changed, proguard.flags must be updated!
        if (depth < 0) {
            throw new IllegalArgumentException("depth cannot be negative");
        }
        StackTraceElement[] trace = Thread.currentThread().getStackTrace();
        if (trace == null || trace.length < (depth + 2)) {
            return null;
        }
        // The stack trace includes some methods we don't care about (e.g. this method).
        // Walk down until we find this method, and then back up to the caller we're looking for.
        for (int i = 0; i < trace.length - 1; i++) {
            String methodName = trace[i].getMethodName();
            if ("getCaller".equals(methodName)) {
                return trace[i + depth + 1];
            }
        }
        // Never found ourself in the stack?!
        return null;
    }

    /**
     * Returns a boolean indicating whether ClassZero debugging is enabled. If enabled, any received
     * sms is treated as if it were a class zero message and displayed by the ClassZeroActivity.
     */
    public static boolean debugClassZeroSmsEnabled() {
        return sDebugClassZeroSms;
    }

    /** Shares a ringtone file via file URI. */
    private static void shareFileUri() {
        final String packageName = "com.android.messaging";
        final String fileName = "/system/media/audio/ringtones/Andromeda.ogg";

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setPackage(packageName);
        intent.putExtra(Intent.EXTRA_STREAM, Uri.parse("file://" + fileName));
        intent.setType("image/*");
        Factory.get().getApplicationContext().startActivity(intent);
    }
}
