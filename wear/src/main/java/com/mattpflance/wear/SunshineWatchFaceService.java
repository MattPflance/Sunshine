/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.mattpflance.wear;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.content.ContextCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.io.InputStream;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFaceService extends CanvasWatchFaceService {

    private static final String TAG = "SunshineWatchFaceService";
    private static final Typeface NORMAL_TYPEFACE = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
    private static final Typeface THIN_TYPEFACE = Typeface.create("sans-serif-thin", Typeface.NORMAL);

    // Update twice a second to blink colons
    private static final long INTERACTIVE_UPDATE_RATE_MS = 500;

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;


    @Override
    public Engine onCreateEngine() {
        /* provide your watch face implementation */
        return new Engine();
    }

    /* implement service callback methods */
    private class Engine extends CanvasWatchFaceService.Engine implements
            DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener {

        private final String COLON_STRING = ":";

        private GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFaceService.this)
                .addApi(Wearable.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();

        private Paint mBackgroundPaint;
        private Paint mHourPaint;
        private Paint mColonPaint;
        private Paint mMinutePaint;
        private Paint mAmPmPaint;
        private Paint mDatePaint;
        private Paint mHighTempPaint;
        private Paint mLowTempPaint;
        private Bitmap mWeatherBitmap;

        private GregorianCalendar mCalendar;

        private float mXOffset;
        private float mYOffset;
        private float mColonWidth;
        private String mAmString;
        private String mPmString;

        /* the time changed in interactive mode */
        private final Handler mUpdateTimeHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_UPDATE_TIME:
                        invalidate();
                        if (shouldTimerBeRunning()) {
                            long timeMs = System.currentTimeMillis();
                            long delayMs = INTERACTIVE_UPDATE_RATE_MS - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                            mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
                        }
                        break;
                }
            }
        };

        /* Receiver that updates the time zone */
        private final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };

        /* Keep track of the registration status of mTimeZoneReceiver */
        private boolean mRegisteredTimeZoneReceiver = false;

        /* when true, disable anti-aliasing and bitmap filtering in ambient mode */
        private boolean mLowBitAmbient;

        /* when true, avoid large blocks of white in ambient and no content within
         * 10px of the edge of the screen (because system shifts to avoid burn-in */
        private boolean mBurnInProtection;

        /* initialize the watch face */
        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            // Set watch face style / configure system UI
            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setHotwordIndicatorGravity(Gravity.TOP | Gravity.END)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());

            // Initialize resources
            Resources resources = SunshineWatchFaceService.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);
            mAmString = "AM";
            mPmString = "PM";

            Context context = SunshineWatchFaceService.this;

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(ContextCompat.getColor(context, R.color.primary));

            mHourPaint = createTextPaint(ContextCompat.getColor(context, R.color.white), NORMAL_TYPEFACE);
            mColonPaint = createTextPaint(ContextCompat.getColor(context, R.color.forecast_low_text), NORMAL_TYPEFACE);
            mMinutePaint = createTextPaint(ContextCompat.getColor(context, R.color.white), THIN_TYPEFACE);
            mAmPmPaint = createTextPaint(ContextCompat.getColor(context, R.color.forecast_low_text), NORMAL_TYPEFACE);
            mDatePaint = createTextPaint(ContextCompat.getColor(context, R.color.forecast_low_text), THIN_TYPEFACE);
            mHighTempPaint = createTextPaint(ContextCompat.getColor(context, R.color.white), NORMAL_TYPEFACE);
            mLowTempPaint = createTextPaint(ContextCompat.getColor(context, R.color.forecast_low_text), NORMAL_TYPEFACE);

            //mWeatherBitmap;          // Colored or Gray icon

            mCalendar = new GregorianCalendar();
            mCalendar.setTimeInMillis(System.currentTimeMillis());
        }

        /* get device features (burn-in, low-bit ambient) */
        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
            mBurnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
        }

        /* the time changed in ambient mode and is called every minute */
        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        /* the wearable switched between modes */
        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);

            // Adjust colors to ambient or interactive mode


            // Turn off AntiAliasing in Low Bit Ambient mode
            if (mLowBitAmbient) {
                boolean antiAliasStatus = !inAmbientMode;
                mBackgroundPaint.setAntiAlias(antiAliasStatus);
                mHourPaint.setAntiAlias(antiAliasStatus);
                mColonPaint.setAntiAlias(antiAliasStatus);
                mMinutePaint.setAntiAlias(antiAliasStatus);
                mAmPmPaint.setAntiAlias(antiAliasStatus);
                mDatePaint.setAntiAlias(antiAliasStatus);
                mHighTempPaint.setAntiAlias(antiAliasStatus);
                mLowTempPaint.setAntiAlias(antiAliasStatus);
            }

            // Remove blocks of white text for burn-in protection
            if (mBurnInProtection) {
                boolean antiAliasStatus = !inAmbientMode;

            }

            invalidate();

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /* draw the watch face */
        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            boolean isAmbient = isInAmbientMode();
            mXOffset = bounds.centerX();

            // Draw the background.
            if (isAmbient) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Update the time
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            boolean is24Hour = DateFormat.is24HourFormat(SunshineWatchFaceService.this);

            boolean drawColons = (now % 1000) < 500;

            float textWidth = 0;

            // HOURS
            String hourString;
            if (is24Hour) {
                hourString = String.format(Locale.getDefault(), "%02d", mCalendar.get(Calendar.HOUR_OF_DAY));
            } else {
                int hour = mCalendar.get(Calendar.HOUR);
                if (hour == 0) {
                    hour = 12;
                }
                hourString = String.valueOf(hour);
            }
            textWidth += mHourPaint.measureText(hourString);

            // COLON
            textWidth += mColonWidth;

            // MINUTES
            String minuteString = String.format(Locale.getDefault(), "%02d", mCalendar.get(Calendar.MINUTE));
            textWidth += mMinutePaint.measureText(minuteString);

            // AM/PM
            String amPmString = (mCalendar.get(Calendar.AM_PM) == Calendar.AM ? mAmString : mPmString);
            if (!is24Hour) {
                textWidth += mAmPmPaint.measureText(amPmString);
            }

            // Center the text
            float x = mXOffset - (textWidth / 2);

            // Draw text centered
            canvas.drawText(hourString, x, mYOffset, mHourPaint);
            x += mHourPaint.measureText(hourString);

            if (isAmbient || drawColons) {
                canvas.drawText(COLON_STRING, x, mYOffset, mColonPaint);
            }
            x += mColonWidth;

            canvas.drawText(minuteString, x, mYOffset, mMinutePaint);
            if (!is24Hour) {
                x += mMinutePaint.measureText(minuteString);
                canvas.drawText(amPmString, x, mYOffset, mAmPmPaint);
            }
        }

        private void drawTime(Canvas canvas, Rect bounds) {

        }

        /* the watch face became visible or invisible */
        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();

                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
            } else {
                unregisterReceiver();

                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFaceService.this.getResources();
            boolean isRound = insets.isRound();
            float timeTextSize = resources.getDimension(isRound
                    ? R.dimen.time_text_size_round : R.dimen.time_text_size);
            float dateTextSize = resources.getDimension(R.dimen.date_text_size);
            float tempTextSize = resources.getDimension(isRound
                    ? R.dimen.temp_text_size_round : R.dimen.temp_text_size);
            float amPmTextSize = resources.getDimension(isRound
                    ? R.dimen.am_pm_size_round : R.dimen.am_pm_size);

            mHourPaint.setTextSize(timeTextSize);
            mColonPaint.setTextSize(timeTextSize);
            mMinutePaint.setTextSize(timeTextSize);
            mAmPmPaint.setTextSize(amPmTextSize);
            mDatePaint.setTextSize(dateTextSize);
            mHighTempPaint.setTextSize(tempTextSize);
            mLowTempPaint.setTextSize(tempTextSize);

            mColonWidth = mColonPaint.measureText(COLON_STRING);
        }






        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            filter.addAction(Intent.ACTION_LOCALE_CHANGED);
            SunshineWatchFaceService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFaceService.this.unregisterReceiver(mTimeZoneReceiver);
        }

        /**
         * Helper function to adjust paint color to current watch face mode
         * @param paint Is the paint to be changed
         * @param interactiveColor paint's interactive color
         * @param ambientColor paint's ambient color
         */
        private void adjustPaintColorToCurrentMode(Paint paint, int interactiveColor,
                                                   int ambientColor) {
            paint.setColor(isInAmbientMode() ? ambientColor : interactiveColor);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        @Override
        public void onConnected(Bundle connectionHint) {
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
            //updateConfigDataItemAndUiOnStartup();
        }

        @Override
        public void onConnectionSuspended(int cause) {

        }

        @Override
        public void onConnectionFailed(ConnectionResult result) {

        }

        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {
            Log.v("TAG", "onDataChanged Happened");
            for (DataEvent event : dataEvents) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    Log.v("TAG", "A Change Actually Happened");
                    String path = event.getDataItem().getUri().getPath();
                    if (path.equals("/weather")) {

                        DataMap dataMap = DataMapItem.fromDataItem(event.getDataItem()).getDataMap();

                        byte[] bytes  = dataMap.getAsset("image").getData();
                        Bitmap image = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                        String highTemp = dataMap.getString("high");
                        String lowTemp = dataMap.getString("low");

                        Log.v("TAG", "TEST TEST TEST TEST");

                    }
                }
            }
        }
    }
}
