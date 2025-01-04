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

package com.android.messaging.ui.mediapicker;

import android.content.Context;
import android.graphics.Canvas;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import com.android.messaging.R;
import com.android.messaging.ui.PersistentInstanceState;
import com.android.messaging.util.ThreadUtil;

public class CameraMediaChooserView extends FrameLayout implements PersistentInstanceState {
    private static final String KEY_CAMERA_INDEX = "camera_index";

    // True if we have at least queued an update to the view tree to support software rendering
    // fallback
    private boolean mIsSoftwareFallbackActive;

    public CameraMediaChooserView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        final Bundle bundle = new Bundle();
        bundle.putInt(KEY_CAMERA_INDEX, CameraManager.get().getCameraIndex());
        return bundle;
    }

    @Override
    protected void onRestoreInstanceState(final Parcelable state) {
        if (!(state instanceof Bundle)) {
            return;
        }

        final Bundle bundle = (Bundle) state;
        CameraManager.get().selectCameraByIndex(bundle.getInt(KEY_CAMERA_INDEX));
    }

    @Override
    public Parcelable saveState() {
        return onSaveInstanceState();
    }

    @Override
    public void restoreState(final Parcelable restoredState) {
        onRestoreInstanceState(restoredState);
    }

    @Override
    public void resetState() {
        CameraManager.get().selectCamera(Camera.CameraInfo.CAMERA_FACING_BACK);
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        super.onDraw(canvas);
        // If the canvas isn't hardware accelerated, we have to replace the HardwareCameraPreview
        // with a SoftwareCameraPreview which supports software rendering
        if (!canvas.isHardwareAccelerated() && !mIsSoftwareFallbackActive) {
            mIsSoftwareFallbackActive = true;
            // Post modifying the tree since we can't modify the view tree during a draw pass
            ThreadUtil.getMainThreadHandler().post(new Runnable() {
                @Override
                public void run() {
                    final HardwareCameraPreview cameraPreview =
                            (HardwareCameraPreview) findViewById(R.id.camera_preview);
                    if (cameraPreview == null) {
                        return;
                    }
                    final ViewGroup parent = ((ViewGroup) cameraPreview.getParent());
                    final int index = parent.indexOfChild(cameraPreview);
                    final SoftwareCameraPreview softwareCameraPreview =
                            new SoftwareCameraPreview(getContext());
                    // Be sure to remove the hardware view before adding the software view to
                    // prevent having 2 camera previews active at the same time
                    parent.removeView(cameraPreview);
                    parent.addView(softwareCameraPreview, index);
                }
            });
        }
    }
}
