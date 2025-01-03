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

package com.android.messaging.widget;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;

import com.android.messaging.util.LogUtil;

public abstract class BaseWidgetProvider extends AppWidgetProvider {
    protected static final String TAG = LogUtil.BUGLE_WIDGET_TAG;

    public static final int WIDGET_CONVERSATION_REQUEST_CODE = 987;

    static final String WIDGET_SIZE_KEY = "widgetSizeKey";

    public static final int SIZE_LARGE  = 0;    // undefined == 0, which is the default, large
    public static final int SIZE_SMALL  = 1;
    public static final int SIZE_MEDIUM = 2;

    /**
     * Update all widgets in the list
     */
    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        super.onUpdate(context, appWidgetManager, appWidgetIds);

        for (int i = 0; i < appWidgetIds.length; ++i) {
            updateWidget(context, appWidgetIds[i]);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
            LogUtil.v(TAG, "onReceive intent: " + intent + " for " + this.getClass());
        }
        final String action = intent.getAction();

        // The base class AppWidgetProvider's onReceive handles the normal widget intents. Here
        // we're looking for an intent sent by our app when it knows a message has
        // been sent or received (or a conversation has been read) and is telling the widget it
        // needs to update.
        if (getAction().equals(action)) {
            final AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
            final int[] appWidgetIds = appWidgetManager.getAppWidgetIds(new ComponentName(context,
                    this.getClass()));

            if (appWidgetIds.length > 0) {
                // We need to update all Bugle app widgets on the home screen.
                if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
                    LogUtil.v(TAG, "onReceive notifyAppWidgetViewDataChanged listId: " +
                            getListId() + " first widgetId: " + appWidgetIds[0]);
                }
                appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, getListId());
            }
        } else {
            super.onReceive(context, intent);
        }
    }

    @Override
    public void onEnabled(Context context) {
        super.onEnabled(context);
        context.getApplicationContext().registerReceiver(this, new IntentFilter(getAction()));
    }

    protected abstract String getAction();

    protected abstract int getListId();

    /**
     * Update the widget appWidgetId
     */
    protected abstract void updateWidget(Context context, int appWidgetId);

    private int getWidgetSize(AppWidgetManager appWidgetManager, int appWidgetId) {
        if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
            LogUtil.v(TAG, "BaseWidgetProvider.getWidgetSize");
        }

        // Get the dimensions
        final Bundle options = appWidgetManager.getAppWidgetOptions(appWidgetId);

        // Get min width and height.
        final int minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH);
        final int minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT);

        // First find out rows and columns based on width provided.
        final int rows = getCellsForSize(minHeight);
        final int columns = getCellsForSize(minWidth);

        if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
            LogUtil.v(TAG, "BaseWidgetProvider.getWidgetSize row: " + rows
                    + " columns: " + columns);
        }

        int size = SIZE_MEDIUM;
        if (rows == 1) {
            size = SIZE_SMALL;  // Our widget doesn't let itself get this small. Perhaps in the
                                // future will add a super-mini widget.
        } else if (columns > 3) {
            size = SIZE_LARGE;
        }

        // put the size in the bundle so our service know what size it's dealing with.
        final int savedSize = options.getInt(WIDGET_SIZE_KEY);
        if (savedSize != size) {
            options.putInt(WIDGET_SIZE_KEY, size);
            appWidgetManager.updateAppWidgetOptions(appWidgetId, options);

            // The size changed. We have to force the widget to rebuild the list.
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, getListId());

            if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
                LogUtil.v(TAG, "BaseWidgetProvider.getWidgetSize old size: " + savedSize
                                + " new size saved: " + size);
            }
        }

        return size;
    }

    /**
     * Returns number of cells needed for given size of the widget.
     *
     * @param size Widget size in dp.
     * @return Size in number of cells.
     */
    private static int getCellsForSize(int size) {
        // The hardwired sizes in this function come from the hardwired formula found in
        // Android's UI guidelines for widget design:
        // http://developer.android.com/guide/practices/ui_guidelines/widget_design.html
        return (size + 30) / 70;
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager,
            int appWidgetId, Bundle newOptions) {

        final int widgetSize = getWidgetSize(appWidgetManager, appWidgetId);

        if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
            LogUtil.v(TAG, "BaseWidgetProvider.onAppWidgetOptionsChanged new size: " +
                    widgetSize);
        }

        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions);
    }

    protected void deletePreferences(final int widgetId) {
    }

    /**
     * Remove preferences when deleting widget
     */
    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        super.onDeleted(context, appWidgetIds);

        if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
            LogUtil.v(TAG, "BaseWidgetProvider.onDeleted");
        }

        for (final int widgetId : appWidgetIds) {
            deletePreferences(widgetId);
        }
    }

}
