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
package com.android.messaging.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatEditText;

/**
 * We want the EditText used in Conversations to convert text to plain text on paste.  This
 * conversion would happen anyway on send, so without this class it could appear to the user
 * that we would send e.g. bold or italic formatting, but in the sent message it would just be
 * plain text.
 */
public class PlainTextEditText extends AppCompatEditText {
    private static final char OBJECT_UNICODE = '\uFFFC';

    public PlainTextEditText(final Context context, final AttributeSet attrs) {
        super(context, attrs);
    }

    // Intercept and modify the paste event. Let everything else through unchanged.
    @Override
    public boolean onTextContextMenuItem(final int id) {
        if (id == android.R.id.paste) {
            // We can use this to know where the text position was originally before we pasted
            final int selectionStartPrePaste = getSelectionStart();

            // Let the EditText's normal paste routine fire, then modify the content after.
            // This is simpler than re-implementing the paste logic, which we'd have to do
            // if we want to get the text from the clipboard ourselves and then modify it.

            final boolean result = super.onTextContextMenuItem(id);
            CharSequence text = getText();
            int selectionStart = getSelectionStart();
            int selectionEnd = getSelectionEnd();

            // There is an option in the Chrome mobile app to copy image; however, instead of the
            // image in the form of the uri, Chrome gives us the html source for the image, which
            // the platform paste code turns into the unicode object character. The below section
            // of code looks for that edge case and replaces it with the url for the image.
            final int startIndex = selectionStart - 1;
            final int pasteStringLength = selectionStart - selectionStartPrePaste;
            // Only going to handle the case where the pasted object is the image
            if (pasteStringLength == 1 && text.charAt(startIndex) == OBJECT_UNICODE) {
                final ClipboardManager clipboard =
                        (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                final ClipData clip = clipboard.getPrimaryClip();
                if (clip != null) {
                    ClipData.Item item = clip.getItemAt(0);
                    StringBuilder sb = new StringBuilder(text);
                    final String url = item.getText().toString();
                    sb.replace(selectionStartPrePaste, selectionStart, url);
                    text = sb.toString();
                    selectionStart = selectionStartPrePaste + url.length();
                    selectionEnd = selectionStart;
                }
            }

            // This removes the formatting due to the conversion to string.
            setText(text.toString(), BufferType.EDITABLE);

            // Restore the cursor selection state.
            setSelection(selectionStart, selectionEnd);
            return result;
        } else {
            return super.onTextContextMenuItem(id);
        }
    }
}
