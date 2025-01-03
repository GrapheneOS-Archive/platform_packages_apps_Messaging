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

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioManager;
import android.media.MediaPlayer;

/**
 * Default implementation of MediaUtil
 */
public class MediaUtilImpl extends MediaUtil {

    @Override
    public void playSound(final Context context, final int resId,
            final OnCompletionListener completionListener) {
        // We want to play at the media volume and not the ringer volume, but we do want to
        // avoid playing sound when the ringer/notifications are silenced. This is used for
        // in app sounds that are not critical and should not impact running silent but also
        // shouldn't play at full ring volume if you want to hear your ringer but don't want
        // to be annoyed with in-app volume.
        try {
            final MediaPlayer mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_NOTIFICATION);
            final AssetFileDescriptor afd = context.getResources().openRawResourceFd(resId);
            mediaPlayer.setDataSource(
                    afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            mediaPlayer.prepare();
            mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(final MediaPlayer mp) {
                    if (completionListener != null) {
                        completionListener.onCompletion();
                    }
                    mp.stop();
                    mp.release();
                }
            });
            mediaPlayer.seekTo(0);
            mediaPlayer.start();
           return;
        } catch (final Exception e) {
            LogUtil.w("MediaUtilImpl", "Error playing sound id: " + resId, e);
        }
        if (completionListener != null) {
            // Call the completion handler to not block functionality if audio play fails
            completionListener.onCompletion();
        }
    }
}
