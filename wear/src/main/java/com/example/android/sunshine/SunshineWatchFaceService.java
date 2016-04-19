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

package com.example.android.sunshine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
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
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFaceService extends CanvasWatchFaceService {

    private static final String TAG = "SunshineWatchFaceService";
    private static final Typeface NORMAL_TYPEFACE = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
    private static final Typeface THIN_TYPEFACE = Typeface.create("sans-serif-thin", Typeface.NORMAL);

    private static final String WEATHER_REQUEST_PATH = "/request-weather";
    private static final String WEATHER_PATH = "/weather";
    private static final String WEATHER_ID_KEY = "weather-id";
    private static final String HIGH_TEMP_KEY = "high-temp";
    private static final String LOW_TEMP_KEY = "low-temp";

    private static final int WEATHER_BITMAP_HEIGHT = 60;
    private static final int WEATHER_BITMAP_WIDTH = 60;

    // Update twice a second to blink colons
    private static final long INTERACTIVE_UPDATE_RATE_MS = 500;
    private static final long DAY_IN_MILLIS = 1000 * 60 * 60 * 24;

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    /**
     * Handler message id for updating weather data every 24 hours
     */
    private static final int MSG_UPDATE_WEATHER = 1;


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

        private GoogleApiClient mGoogleApiClient;

        private Paint mBackgroundPaint;
        private Paint mHourPaint;
        private Paint mColonPaint;
        private Paint mMinutePaint;
        private Paint mAmPmPaint;
        private Paint mDatePaint;
        private Paint mDividerPaint;
        private Paint mHighTempPaint;
        private Paint mLowTempPaint;
        private Paint mWhiteBorderPaint;
        private Paint mBlueBorderPaint;
        private Bitmap mWeatherBitmap;

        private GregorianCalendar mCalendar;

        private float mXOffset;
        private float mYOffset;
        private float mDividerSize;
        private float mColonWidth;
        private String mAmString;
        private String mPmString;
        private String mHighString;
        private String mLowString;

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

        /* Handler to update the weather every 24 hours */
        private final Handler mUpdateWeatherHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_UPDATE_WEATHER:
                        long timeMs = System.currentTimeMillis();
                        long delayMs = DAY_IN_MILLIS - (timeMs % DAY_IN_MILLIS);

                        //Log.v("WATCH FACE", "Delay: " + delayMs);

                        PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(WEATHER_REQUEST_PATH);
                        putDataMapRequest.getDataMap().putLong("Time", timeMs);
                        putDataMapRequest.setUrgent();
                        PutDataRequest request = putDataMapRequest.asPutDataRequest();
                        Wearable.DataApi.putDataItem(mGoogleApiClient, request).setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                            @Override
                            public void onResult(final DataApi.DataItemResult result) {
                                //Log.d("WATCH_FACE", "Data item status: " + result.getStatus());
                            }
                        });
                        mUpdateWeatherHandler.sendEmptyMessageDelayed(MSG_UPDATE_WEATHER, delayMs);
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

            mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFaceService.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();

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
            mDividerSize = 1f;
            mAmString = "AM";
            mPmString = "PM";

            mWeatherBitmap = null;
            mHighString = "";
            mLowString = "";

            Context context = SunshineWatchFaceService.this;

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(ContextCompat.getColor(context, R.color.primary));

            mHourPaint = createTextPaint(ContextCompat.getColor(context, R.color.white), NORMAL_TYPEFACE);
            mColonPaint = createTextPaint(ContextCompat.getColor(context, R.color.white), NORMAL_TYPEFACE);
            mMinutePaint = createTextPaint(ContextCompat.getColor(context, R.color.white), THIN_TYPEFACE);
            mAmPmPaint = createTextPaint(ContextCompat.getColor(context, R.color.primary_light), NORMAL_TYPEFACE);
            mDatePaint = createTextPaint(ContextCompat.getColor(context, R.color.primary_light), NORMAL_TYPEFACE);
            mDividerPaint = new Paint();
            mDividerPaint.setStrokeWidth(mDividerSize);
            mDividerPaint.setColor(ContextCompat.getColor(context, R.color.primary_light));
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
            // No need to do this since colors are supported

            // Turn off AntiAliasing in Low Bit Ambient mode
            if (mLowBitAmbient) {
                boolean antiAliasStatus = !inAmbientMode;
                mBackgroundPaint.setAntiAlias(antiAliasStatus);
                mHourPaint.setAntiAlias(antiAliasStatus);
                mColonPaint.setAntiAlias(antiAliasStatus);
                mMinutePaint.setAntiAlias(antiAliasStatus);
                mAmPmPaint.setAntiAlias(antiAliasStatus);
                mDatePaint.setAntiAlias(antiAliasStatus);
                mDividerPaint.setAntiAlias(antiAliasStatus);
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
            float centerX = bounds.centerX();

            /* TIME */

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
            float x = centerX - (textWidth / 2);

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

            /* DATE */

            String date = getDayOfWeek(mCalendar.get(Calendar.DAY_OF_WEEK)) + ", " +
                    getMonth(mCalendar.get(Calendar.MONTH)) + " " +
                    mCalendar.get(Calendar.DAY_OF_MONTH) + " " +
                    mCalendar.get(Calendar.YEAR);
            date = date.toUpperCase();

            float y = mYOffset;
            x = centerX - (mDatePaint.measureText(date) / 2);
            y += mColonPaint.getTextSize();

            canvas.drawText(date, x, y, mDatePaint);

            /* Divider */

            y += mDatePaint.getTextSize();
            x = centerX - (mColonWidth*2);

            canvas.drawLine(x, y, x + (mColonWidth*4), y, mDividerPaint);

            /* Weather */
//            mWeatherBitmap = Bitmap.createScaledBitmap(
//                    BitmapFactory.decodeResource(getResources(), R.drawable.art_clear),
//                    WEATHER_BITMAP_WIDTH,
//                    WEATHER_BITMAP_HEIGHT,
//                    false);
//            mHighString = "99\u00B0";
//            mLowString = "-99\u00B0";

            if (mWeatherBitmap != null && mHighString != null && mLowString != null) {

                y += mDatePaint.getTextSize()*2;
                float highTempWidth = mHighTempPaint.measureText(mHighString);
                float lowTempWidth = mLowTempPaint.measureText(mLowString);
                x = centerX - 30 - mColonWidth - highTempWidth/2 - lowTempWidth/2;

                float bitmapXWidth = WEATHER_BITMAP_WIDTH + mColonWidth;
                if (!isAmbient) {
                    canvas.drawBitmap(mWeatherBitmap, x, y-(WEATHER_BITMAP_HEIGHT-20), null);
                    x += bitmapXWidth;
                } else {
                    // Adjust x so the temperatures are centered
                    x += bitmapXWidth/2;
                }

                canvas.drawText(mHighString, x, y, mHighTempPaint);
                x += highTempWidth + mColonWidth;

                canvas.drawText(mLowString, x, y, mLowTempPaint);

            }

        }

        private String getDayOfWeek(int day) {
            switch(day) {
                case Calendar.SUNDAY: return "sun";
                case Calendar.MONDAY: return "mon";
                case Calendar.TUESDAY: return "tue";
                case Calendar.WEDNESDAY: return "wed";
                case Calendar.THURSDAY: return "thu";
                case Calendar.FRIDAY: return "fri";
                case Calendar.SATURDAY: return "sat";
                default: return "N/A";
            }
        }

        private String getMonth(int month) {
            switch(month) {
                case Calendar.JANUARY: return "jan";
                case Calendar.FEBRUARY: return "feb";
                case Calendar.MARCH: return "mar";
                case Calendar.APRIL: return "apr";
                case Calendar.MAY: return "may";
                case Calendar.JUNE: return "jun";
                case Calendar.JULY: return "jul";
                case Calendar.AUGUST: return "aug";
                case Calendar.SEPTEMBER: return "sep";
                case Calendar.OCTOBER: return "oct";
                case Calendar.NOVEMBER: return "nov";
                case Calendar.DECEMBER: return "dec";
                default: return "N/A";
            }
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
            mXOffset = resources.getDimension(isRound ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            mDividerSize = isRound ? 1f : 2f;
            float timeTextSize = resources.getDimension(isRound ? R.dimen.time_text_size_round : R.dimen.time_text_size);
            float dateTextSize = resources.getDimension(R.dimen.date_text_size);
            float tempTextSize = resources.getDimension(isRound ? R.dimen.temp_text_size_round : R.dimen.temp_text_size);
            float amPmTextSize = resources.getDimension(isRound ? R.dimen.am_pm_size_round : R.dimen.am_pm_size);

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
        private void adjustPaintColorToCurrentMode(Paint paint, int interactiveColor, int ambientColor) {
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
            Log.i("WATCH FACE", "GoogleApiClient Connected!");
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
            mUpdateWeatherHandler.sendEmptyMessage(MSG_UPDATE_WEATHER);
        }
        @Override
        public void onConnectionSuspended(int cause) {
            Log.e("WATCH FACE", "GoogleApiClient Connection Suspended!");
        }
        @Override
        public void onConnectionFailed(ConnectionResult result) {
            Log.e("WATCH FACE", "GoogleApiClient Connection Failed with result " + result);
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {
            for (DataEvent event : dataEvents) {
                String path = event.getDataItem().getUri().getPath();
                //Log.v("WATCH FACE", "Path: " + path);
                if (WEATHER_PATH.equals(path)) {

                    DataMap dataMap = DataMapItem.fromDataItem(event.getDataItem()).getDataMap();

                    mWeatherBitmap = Bitmap.createScaledBitmap(
                            BitmapFactory.decodeResource(getResources(), getResourceIdFromWeatherId(dataMap.getInt(WEATHER_ID_KEY))),
                            WEATHER_BITMAP_WIDTH,
                            WEATHER_BITMAP_HEIGHT,
                            false);
                    mHighString = dataMap.getString(HIGH_TEMP_KEY);
                    mLowString = dataMap.getString(LOW_TEMP_KEY);

                    //Log.v("WATCH FACE", "mHighString: " + mHighString + " mLowString: " + mLowString);

                    invalidate();
                }
            }
        }

        private int getResourceIdFromWeatherId(int weatherId) {
            if (weatherId >= 200 && weatherId <= 232) {
                return R.drawable.art_storm;
            } else if (weatherId >= 300 && weatherId <= 321) {
                return R.drawable.art_light_rain;
            } else if (weatherId >= 500 && weatherId <= 504) {
                return R.drawable.art_rain;
            } else if (weatherId == 511) {
                return R.drawable.art_snow;
            } else if (weatherId >= 520 && weatherId <= 531) {
                return R.drawable.art_rain;
            } else if (weatherId >= 600 && weatherId <= 622) {
                return R.drawable.art_snow;
            } else if (weatherId >= 701 && weatherId <= 761) {
                return R.drawable.art_fog;
            } else if (weatherId == 761 || weatherId == 781) {
                return R.drawable.art_storm;
            } else if (weatherId == 800) {
                return R.drawable.art_clear;
            } else if (weatherId == 801) {
                return R.drawable.art_light_clouds;
            } else if (weatherId >= 802 && weatherId <= 804) {
                return R.drawable.art_clouds;
            }
            return -1;
        }
    }
}
