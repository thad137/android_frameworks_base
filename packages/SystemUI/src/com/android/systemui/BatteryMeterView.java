/*
 * Copyright (C) 2013 The Android Open Source Project
 * Copyright (C) 2014 The CyanogenMod Project
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

package com.android.systemui;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import com.android.systemui.statusbar.policy.BatteryController;

public class BatteryMeterView extends View implements DemoMode,
        BatteryController.BatteryStateChangeCallback {
    public static final String TAG = BatteryMeterView.class.getSimpleName();
    public static final String ACTION_LEVEL_TEST = "com.android.systemui.BATTERY_LEVEL_TEST";
    public static final boolean DEBUG = false;

    private static final String STATUS_BAR_BATTERY_STYLE = "status_bar_battery_style";
    private static final String STATUS_BAR_SHOW_BATTERY_PERCENT = "status_bar_show_battery_percent";

    private static final boolean SINGLE_DIGIT_PERCENT = false;
    private static final boolean SHOW_100_PERCENT = false;

    private static final int FULL = 96;

    private static final float BOLT_LEVEL_THRESHOLD = 0.3f;  // opaque bolt below this fraction

    private final int[] mColors;

    protected boolean mShowPercent = true;
    private float mButtonHeightFraction;
    private float mSubpixelSmoothingLeft;
    private float mSubpixelSmoothingRight;

    public static enum BatteryMeterMode {
        BATTERY_METER_GONE,
        BATTERY_METER_ICON_PORTRAIT,
        BATTERY_METER_CIRCLE,
        BATTERY_METER_TEXT
    }

    private int mHeight;
    private int mWidth;

    private String mWarningString;
    private final int mCriticalLevel;
    private final int mFrameColor;

    private final Path mShapePath = new Path();
    private final Path mClipPath = new Path();
    private final Path mTextPath = new Path();

    private BatteryController mBatteryController;
    private boolean mPowerSaveEnabled;

    private final Handler mHandler;

    protected BatteryMeterMode mMeterMode = null;

    protected boolean mAttached;

    private boolean mDemoMode;
    protected BatteryTracker mDemoTracker = new BatteryTracker();
    protected BatteryTracker mTracker = new BatteryTracker();
    private BatteryMeterDrawable mBatteryMeterDrawable;
    private final Object mLock = new Object();

    private class BatteryTracker extends BroadcastReceiver {
        public static final int UNKNOWN_LEVEL = -1;

        // current battery status
        boolean present = true;
        int level = UNKNOWN_LEVEL;
        String percentStr;
        int plugType;
        boolean plugged;
        int health;
        int status;
        String technology;
        int voltage;
        int temperature;
        boolean testmode = false;

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
                if (testmode && ! intent.getBooleanExtra("testmode", false)) return;

                level = (int)(100f
                        * intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
                        / intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100));

                present = intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true);
                plugType = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                plugged = plugType != 0;
                health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH,
                        BatteryManager.BATTERY_HEALTH_UNKNOWN);
                status = intent.getIntExtra(BatteryManager.EXTRA_STATUS,
                        BatteryManager.BATTERY_STATUS_UNKNOWN);
                technology = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
                voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
                temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);

                setContentDescription(
                        context.getString(R.string.accessibility_battery_level, level));
                synchronized (mLock) {
                    if (mBatteryMeterDrawable != null) {
                        setVisibility(View.VISIBLE);
                        invalidateIfVisible();
                    }
                }
            } else if (action.equals(ACTION_LEVEL_TEST)) {
                testmode = true;
                post(new Runnable() {
                    int curLevel = 0;
                    int incr = 1;
                    int saveLevel = level;
                    int savePlugged = plugType;
                    Intent dummy = new Intent(Intent.ACTION_BATTERY_CHANGED);
                    @Override
                    public void run() {
                        if (curLevel < 0) {
                            testmode = false;
                            dummy.putExtra("level", saveLevel);
                            dummy.putExtra("plugged", savePlugged);
                            dummy.putExtra("testmode", false);
                        } else {
                            dummy.putExtra("level", curLevel);
                            dummy.putExtra("plugged", incr > 0
                                    ? BatteryManager.BATTERY_PLUGGED_AC : 0);
                            dummy.putExtra("testmode", true);
                        }
                        getContext().sendBroadcast(dummy);

                        if (!testmode) return;

                        curLevel += incr;
                        if (curLevel == 100) {
                            incr *= -1;
                        }
                        postDelayed(this, 200);
                    }
                });
            } else if (action.equals(Intent.ACTION_USER_SWITCHED)) {
                if (DEBUG) Log.v(TAG, "onReceive: " + intent);
                loadShowBatterySetting();
            }
        }

        protected boolean shouldIndicateCharging() {
            if (status == BatteryManager.BATTERY_STATUS_CHARGING) {
                return true;
            }
            if (plugged) {
                return status == BatteryManager.BATTERY_STATUS_FULL || level == 100;
            }
            return false;
        }
    }

    private final Runnable mInvalidate = new Runnable() {
        public void run() {
            invalidateIfVisible();
        }
    };

    private ContentObserver mObserver = new ContentObserver(new Handler()) {
        public void onChange(boolean selfChange, Uri uri) {
            loadShowBatterySetting();
            postInvalidate();
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(ACTION_LEVEL_TEST);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        final Intent sticky = getContext().registerReceiver(mTracker, filter);
        if (sticky != null) {
            // preload the battery level
            mTracker.onReceive(getContext(), sticky);
        }
        mBatteryController.addStateChangedCallback(this);
        mAttached = true;
        getContext().getContentResolver().registerContentObserver(Settings.System.getUriFor(
                STATUS_BAR_BATTERY_STYLE), false, mObserver);
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        mAttached = false;
        getContext().unregisterReceiver(mTracker);
        mBatteryController.removeStateChangedCallback(this);
    }

    private void loadShowBatterySetting() {
        boolean showInsidePercent = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_SHOW_BATTERY_PERCENT, 0, ActivityManager.getCurrentUser()) == 1;

        int batteryStyle = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_BATTERY_STYLE, 0, ActivityManager.getCurrentUser());

        BatteryMeterMode meterMode = BatteryMeterMode.BATTERY_METER_ICON_PORTRAIT;

        switch (batteryStyle) {
            case 2:
                meterMode = BatteryMeterMode.BATTERY_METER_CIRCLE;
                break;
            case 4:
                meterMode = BatteryMeterMode.BATTERY_METER_GONE;
                showInsidePercent = false;
                break;
            case 5:
                meterMode = BatteryMeterMode.BATTERY_METER_TEXT;
                showInsidePercent = false;
                break;
            default:
                break;
        }

        setMode(meterMode);
        mShowPercent = showInsidePercent;
        invalidateIfVisible();
    }

    public BatteryMeterView(Context context) {
        this(context, null, 0);
    }

    public BatteryMeterView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BatteryMeterView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mHandler = new Handler();

        final Resources res = context.getResources();
        TypedArray atts = context.obtainStyledAttributes(attrs, R.styleable.BatteryMeterView,
                defStyle, 0);
        mFrameColor = atts.getColor(R.styleable.BatteryMeterView_frameColor,
                res.getColor(R.color.batterymeter_frame_color));
        TypedArray levels = res.obtainTypedArray(R.array.batterymeter_color_levels);
        TypedArray colors = res.obtainTypedArray(R.array.batterymeter_color_values);

        final int N = levels.length();
        mColors = new int[2*N];
        for (int i=0; i<N; i++) {
            mColors[2*i] = levels.getInt(i, 0);
            mColors[2*i+1] = colors.getColor(i, 0);
        }
        levels.recycle();
        colors.recycle();
        atts.recycle();
        mWarningString = context.getString(R.string.battery_meter_very_low_overlay_symbol);
        mCriticalLevel = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_criticalBatteryWarningLevel);
        mButtonHeightFraction = context.getResources().getFraction(
                R.fraction.battery_button_height_fraction, 1, 1);
        mSubpixelSmoothingLeft = context.getResources().getFraction(
                R.fraction.battery_subpixel_smoothing_left, 1, 1);
        mSubpixelSmoothingRight = context.getResources().getFraction(
                R.fraction.battery_subpixel_smoothing_right, 1, 1);

        loadShowBatterySetting();
        mBatteryMeterDrawable = createBatteryMeterDrawable(mMeterMode);
    }

    protected BatteryMeterDrawable createBatteryMeterDrawable(BatteryMeterMode mode) {
        Resources res = mContext.getResources();
        switch (mode) {
            case BATTERY_METER_CIRCLE:
                return new CircleBatteryMeterDrawable(res);
            case BATTERY_METER_TEXT:
            case BATTERY_METER_GONE:
                return null;
            default:
                return new NormalBatteryMeterDrawable(res);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        if (mMeterMode == BatteryMeterMode.BATTERY_METER_CIRCLE) {
            height += (CircleBatteryMeterDrawable.STROKE_WITH / 3);
            width = height;
        } else if (mMeterMode == BatteryMeterMode.BATTERY_METER_TEXT) {
            onSizeChanged(width, height, 0, 0); // Force a size changed event
        }

        setMeasuredDimension(width, height);
    }

    public void setBatteryController(BatteryController batteryController) {
        mBatteryController = batteryController;
        mPowerSaveEnabled = mBatteryController.isPowerSave();
    }

    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
        // TODO: Use this callback instead of own broadcast receiver.
    }

    @Override
    public void onPowerSaveChanged() {
        mPowerSaveEnabled = mBatteryController.isPowerSave();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        mHeight = h;
        mWidth = w;
        synchronized (mLock) {
            if (mBatteryMeterDrawable != null) {
                mBatteryMeterDrawable.onSizeChanged(w, h, oldw, oldh);
            }
        }
    }

    protected void invalidateIfVisible() {
        if (getVisibility() == View.VISIBLE && mAttached) {
            if (mAttached) {
                postInvalidate();
            } else {
                invalidate();
            }
        }
    }

    public void setMode(BatteryMeterMode mode) {
        if (mMeterMode == mode) {
            return;
        }

        mMeterMode = mode;
        BatteryTracker tracker = mDemoMode ? mDemoTracker : mTracker;
        if (mode == BatteryMeterMode.BATTERY_METER_GONE ||
                mode == BatteryMeterMode.BATTERY_METER_TEXT) {
            setVisibility(View.GONE);
            synchronized (mLock) {
                mBatteryMeterDrawable = null;
            }
        } else {
            synchronized (mLock) {
                if (mBatteryMeterDrawable != null) {
                    mBatteryMeterDrawable.onDispose();
                }
                mBatteryMeterDrawable = createBatteryMeterDrawable(mode);
            }
            if (mMeterMode == BatteryMeterMode.BATTERY_METER_ICON_PORTRAIT) {
                ((NormalBatteryMeterDrawable) mBatteryMeterDrawable).loadBoltPoints(
                        mContext.getResources());
            }
            if (tracker.present) {
                setVisibility(View.VISIBLE);
                postInvalidate();
                requestLayout();
            } else {
                setVisibility(View.GONE);
            }
        }
    }

    public int getColorForLevel(int percent) {

        // If we are in power save mode, always use the normal color.
        if (mPowerSaveEnabled) {
            return mColors[mColors.length-1];
        }
        int thresh, color = 0;
        for (int i=0; i<mColors.length; i+=2) {
            thresh = mColors[i];
            color = mColors[i+1];
            if (percent <= thresh) return color;
        }
        return color;
    }

    @Override
    public void draw(Canvas c) {
        synchronized (mLock) {
            if (mBatteryMeterDrawable != null) {
                BatteryTracker tracker = mDemoMode ? mDemoTracker : mTracker;
                mBatteryMeterDrawable.onDraw(c, tracker);
            }
        }
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    public void dispatchDemoCommand(String command, Bundle args) {
        if (getVisibility() == View.VISIBLE) {
            if (!mDemoMode && command.equals(COMMAND_ENTER)) {
                mDemoMode = true;
                mDemoTracker.level = mTracker.level;
                mDemoTracker.plugged = mTracker.plugged;
            } else if (mDemoMode && command.equals(COMMAND_EXIT)) {
                mDemoMode = false;
                postInvalidate();
            } else if (mDemoMode && command.equals(COMMAND_BATTERY)) {
               String level = args.getString("level");
               String plugged = args.getString("plugged");
               if (level != null) {
                   mDemoTracker.level = Math.min(Math.max(Integer.parseInt(level), 0), 100);
               }
               if (plugged != null) {
                   mDemoTracker.plugged = Boolean.parseBoolean(plugged);
               }
               postInvalidate();
            }
        }
    }

    protected interface BatteryMeterDrawable {
        void onDraw(Canvas c, BatteryTracker tracker);
        void onSizeChanged(int w, int h, int oldw, int oldh);
        void onDispose();
    }

    protected class NormalBatteryMeterDrawable implements BatteryMeterDrawable {
        private static final boolean SINGLE_DIGIT_PERCENT = false;
        private static final boolean SHOW_100_PERCENT = false;

        private boolean mDisposed;

        private final Paint mFramePaint, mBatteryPaint, mWarningTextPaint, mTextPaint, mBoltPaint;
        private float mTextHeight, mWarningTextHeight;

        private final int mChargeColor;
        private final float[] mBoltPoints;
        private final Path mBoltPath = new Path();

        private final RectF mFrame = new RectF();
        private final RectF mButtonFrame = new RectF();
        private final RectF mBoltFrame = new RectF();

        public NormalBatteryMeterDrawable(Resources res) {
            super();
            mDisposed = false;

            mFramePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mFramePaint.setColor(mFrameColor);
            mFramePaint.setDither(true);
            mFramePaint.setStrokeWidth(0);
            mFramePaint.setStyle(Paint.Style.FILL_AND_STROKE);

            mBatteryPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mBatteryPaint.setDither(true);
            mBatteryPaint.setStrokeWidth(0);
            mBatteryPaint.setStyle(Paint.Style.FILL_AND_STROKE);

            mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            Typeface font = Typeface.create("sans-serif-condensed", Typeface.BOLD);
            mTextPaint.setTypeface(font);
            mTextPaint.setTextAlign(Paint.Align.CENTER);

            mWarningTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mWarningTextPaint.setColor(mColors[1]);
            font = Typeface.create("sans-serif", Typeface.BOLD);
            mWarningTextPaint.setTypeface(font);
            mWarningTextPaint.setTextAlign(Paint.Align.CENTER);

            mChargeColor = getResources().getColor(R.color.batterymeter_charge_color);

            mBoltPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mBoltPaint.setColor(res.getColor(R.color.batterymeter_bolt_color));
            mBoltPoints = loadBoltPoints(res);
        }

        @Override
        public void onDraw(Canvas c, BatteryTracker tracker) {
            if (mDisposed) return;

            final int level = tracker.level;

            if (level == BatteryTracker.UNKNOWN_LEVEL) return;

            float drawFrac = (float) level / 100f;
            final int pt = getPaddingTop();
            final int pl = getPaddingLeft();
            final int pr = getPaddingRight();
            final int pb = getPaddingBottom();
            final int height = mHeight - pt - pb;
            final int width = mWidth - pl - pr;

            final int buttonHeight = (int) ((height) * mButtonHeightFraction);

            mFrame.set(0, 0, width, height);
            mFrame.offset(pl, pt);

            // button-frame: area above the battery body
            mButtonFrame.set(
                    mFrame.left + Math.round(width * 0.25f),
                    mFrame.top,
                    mFrame.right - Math.round(width * 0.25f),
                    mFrame.top + buttonHeight);

            mButtonFrame.top += mSubpixelSmoothingLeft;
            mButtonFrame.left += mSubpixelSmoothingLeft;
            mButtonFrame.right -= mSubpixelSmoothingRight;

            // frame: battery body area

            mFrame.top += buttonHeight;
            mFrame.left += mSubpixelSmoothingLeft;
            mFrame.top += mSubpixelSmoothingLeft;
            mFrame.right -= mSubpixelSmoothingRight;
            mFrame.bottom -= mSubpixelSmoothingRight;

            // set the battery charging color
            mBatteryPaint.setColor(tracker.plugged ? mChargeColor : getColorForLevel(level));

            if (level >= FULL) {
                drawFrac = 1f;
            } else if (level <= mCriticalLevel) {
                drawFrac = 0f;
            }

            final float levelTop;

            if (drawFrac == 1f) {
                levelTop = mButtonFrame.top;
            } else {
                levelTop = (mFrame.top + (mFrame.height() * (1f - drawFrac)));
            }

            // define the battery shape
            mShapePath.reset();
            mShapePath.moveTo(mButtonFrame.left, mButtonFrame.top);
            mShapePath.lineTo(mButtonFrame.right, mButtonFrame.top);
            mShapePath.lineTo(mButtonFrame.right, mFrame.top);
            mShapePath.lineTo(mFrame.right, mFrame.top);
            mShapePath.lineTo(mFrame.right, mFrame.bottom);
            mShapePath.lineTo(mFrame.left, mFrame.bottom);
            mShapePath.lineTo(mFrame.left, mFrame.top);
            mShapePath.lineTo(mButtonFrame.left, mFrame.top);
            mShapePath.lineTo(mButtonFrame.left, mButtonFrame.top);

            if (tracker.plugged) {
                // define the bolt shape
                final float bl = mFrame.left + mFrame.width() / 4.5f;
                final float bt = mFrame.top + mFrame.height() / 6f;
                final float br = mFrame.right - mFrame.width() / 7f;
                final float bb = mFrame.bottom - mFrame.height() / 10f;
                if (mBoltFrame.left != bl || mBoltFrame.top != bt
                        || mBoltFrame.right != br || mBoltFrame.bottom != bb) {
                    mBoltFrame.set(bl, bt, br, bb);
                    mBoltPath.reset();
                    mBoltPath.moveTo(
                            mBoltFrame.left + mBoltPoints[0] * mBoltFrame.width(),
                            mBoltFrame.top + mBoltPoints[1] * mBoltFrame.height());
                    for (int i = 2; i < mBoltPoints.length; i += 2) {
                        mBoltPath.lineTo(
                                mBoltFrame.left + mBoltPoints[i] * mBoltFrame.width(),
                                mBoltFrame.top + mBoltPoints[i + 1] * mBoltFrame.height());
                    }
                    mBoltPath.lineTo(
                            mBoltFrame.left + mBoltPoints[0] * mBoltFrame.width(),
                            mBoltFrame.top + mBoltPoints[1] * mBoltFrame.height());
                }

                float boltPct =
                        (mBoltFrame.bottom - levelTop) / (mBoltFrame.bottom - mBoltFrame.top);
                boltPct = Math.min(Math.max(boltPct, 0), 1);
                if (boltPct <= BOLT_LEVEL_THRESHOLD) {
                    // draw the bolt if opaque
                    c.drawPath(mBoltPath, mBoltPaint);
                } else {
                    // otherwise cut the bolt out of the overall shape
                    mShapePath.op(mBoltPath, Path.Op.DIFFERENCE);
                }
            }

            // compute percentage text
            boolean pctOpaque = false;
            float pctX = 0, pctY = 0;
            String pctText = null;
            if (!tracker.plugged && level > mCriticalLevel && (mShowPercent
                    && !(tracker.level == 100 && !SHOW_100_PERCENT))) {
                mTextPaint.setColor(getColorForLevel(level));
                final float full = 0.45f;
                final float nofull = 0.6f;
                final float single = 0.75f;
                mTextPaint.setTextSize(height *
                        (SINGLE_DIGIT_PERCENT ? single
                                : (tracker.level == 100 ? full : nofull)));
                mTextHeight = -mTextPaint.getFontMetrics().ascent;
                pctText = String.valueOf(SINGLE_DIGIT_PERCENT ? (level/10) : level);
                pctX = mWidth * 0.5f;
                pctY = (mHeight + mTextHeight) * 0.47f;
                pctOpaque = levelTop > pctY;
                if (!pctOpaque) {
                    mTextPath.reset();
                    mTextPaint.getTextPath(pctText, 0, pctText.length(), pctX, pctY, mTextPath);
                    // cut the percentage text out of the overall shape
                    mShapePath.op(mTextPath, Path.Op.DIFFERENCE);
                }
            }

            // draw the battery shape background
            c.drawPath(mShapePath, mFramePaint);

            // draw the battery shape, clipped to charging level
            mFrame.top = levelTop;
            mClipPath.reset();
            mClipPath.addRect(mFrame,  Path.Direction.CCW);
            mShapePath.op(mClipPath, Path.Op.INTERSECT);
            c.drawPath(mShapePath, mBatteryPaint);

            if (!tracker.plugged) {
                if (level <= mCriticalLevel) {
                    // draw the warning text
                    final float x = mWidth * 0.5f;
                    final float y = (mHeight + mWarningTextHeight) * 0.48f;
                    c.drawText(mWarningString, x, y, mWarningTextPaint);
                } else if (pctOpaque) {
                    // draw the percentage text
                    c.drawText(pctText, pctX, pctY, mTextPaint);
                }
            }
        }

        @Override
        public void onDispose() {
            mHandler.removeCallbacks(mInvalidate);
            mDisposed = true;
        }

        @Override
        public void onSizeChanged(int w, int h, int oldw, int oldh) {
            mHeight = h;
            mWidth = w;
            mWarningTextPaint.setTextSize(h * 0.75f);
            mWarningTextHeight = -mWarningTextPaint.getFontMetrics().ascent;
        }

        private float[] loadBoltPoints(Resources res) {
            final int[] pts = res.getIntArray(R.array.batterymeter_bolt_points);
            int maxX = 0, maxY = 0;
            for (int i = 0; i < pts.length; i += 2) {
                maxX = Math.max(maxX, pts[i]);
                maxY = Math.max(maxY, pts[i + 1]);
            }
            final float[] ptsF = new float[pts.length];
            for (int i = 0; i < pts.length; i += 2) {
                ptsF[i] = (float)pts[i] / maxX;
                ptsF[i + 1] = (float)pts[i + 1] / maxY;
            }
            return ptsF;
        }
    }

    protected class CircleBatteryMeterDrawable implements BatteryMeterDrawable {
        private static final boolean SINGLE_DIGIT_PERCENT = false;
        private static final boolean SHOW_100_PERCENT = false;

        private static final int FULL = 96;

        // alpha animation constants
        private static final int MAX_ALPHA = 255;
        private static final int MIN_ALPHA = 25;
        private static final int ALPHA_FAST_INCREMENT = 10;
        private static final int ALPHA_SLOW_INCREMENT = 5;
        private static final int SPEED_SWITCH_VALUE = 105;

        public static final float STROKE_WITH = 6.5f;

        private boolean mDisposed;

        private int     mCircleSize;              // draw size of circle
        private RectF   mRectLeft;                // contains the precalculated rect used in drawArc(),
                                                  // derived from mCircleSize

        private int     mLastChargingAlpha = 0;   // stores last used alpha for charging dot
        private boolean mIncreaseAlpha;           // controls animation direction for charging dot
        private RectF   mChargingRectLeft;        // contains the precalculated rect used in drawArc(),
                                                  // derived from mRectLeft

        private float   mTextX, mTextY;           // precalculated position for drawText() to appear centered

        private Paint   mTextPaint;
        private Paint   mFrontPaint;
        private Paint   mBackPaint;
        private Paint   mChargingDotPaint;
        private Paint   mWarningTextPaint;

        private final int mChargeColor;

        public CircleBatteryMeterDrawable(Resources res) {
            super();
            mDisposed = false;

            mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            Typeface font = Typeface.create("sans-serif-condensed", Typeface.BOLD);
            mTextPaint.setTypeface(font);
            mTextPaint.setTextAlign(Paint.Align.CENTER);

            mFrontPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mFrontPaint.setStrokeCap(Paint.Cap.BUTT);
            mFrontPaint.setDither(true);
            mFrontPaint.setStrokeWidth(0);
            mFrontPaint.setStyle(Paint.Style.STROKE);

            mBackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mBackPaint.setColor(res.getColor(R.color.batterymeter_frame_color));
            mBackPaint.setStrokeCap(Paint.Cap.BUTT);
            mBackPaint.setDither(true);
            mBackPaint.setStrokeWidth(0);
            mBackPaint.setStyle(Paint.Style.STROKE);

            mWarningTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mWarningTextPaint.setColor(mColors[1]);
            font = Typeface.create("sans-serif", Typeface.BOLD);
            mWarningTextPaint.setTypeface(font);
            mWarningTextPaint.setTextAlign(Paint.Align.CENTER);

            mChargeColor = getResources().getColor(R.color.batterymeter_charge_color);

            mChargingDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mChargingDotPaint.setColor(res.getColor(R.color.batterymeter_bolt_color));
            mChargingDotPaint.setStrokeCap(Paint.Cap.BUTT);
            mChargingDotPaint.setDither(true);
            mChargingDotPaint.setStrokeWidth(0);
            mChargingDotPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        }

        @Override
        public void onDraw(Canvas c, BatteryTracker tracker) {
            if (mDisposed) return;

            if (mRectLeft == null || mChargingRectLeft == null) {
                initSizes();
            }

            updateChargeAnim(tracker);

            drawCircle(c, tracker, mTextX, mRectLeft);
        }

        @Override
        public void onDispose() {
            mHandler.removeCallbacks(mInvalidate);
            mDisposed = true;
        }

        @Override
        public void onSizeChanged(int w, int h, int oldw, int oldh) {
            initSizes();
        }

        private void drawCircle(Canvas canvas, BatteryTracker tracker,
                float textX, RectF drawRect) {
            boolean unknownStatus = tracker.status == BatteryManager.BATTERY_STATUS_UNKNOWN;
            int level = tracker.level;
            Paint paint;

            if (unknownStatus) {
                paint = mBackPaint;
                level = 100; // Draw all the circle;
            } else {
                paint = mFrontPaint;
                paint.setColor(getColorForLevel(level));
                if (tracker.status == BatteryManager.BATTERY_STATUS_FULL) {
                    level = 100;
                }
            }

            // draw thin gray ring first
            canvas.drawArc(drawRect, 270, 360, false, mBackPaint);
            // draw colored arc representing charge level
            canvas.drawArc(drawRect, 270, 3.6f * level, false, paint);
            // if chosen by options, draw percentage text in the middle
            // always skip percentage when 100, so layout doesnt break
            if (unknownStatus) {
                mTextPaint.setColor(paint.getColor());
                canvas.drawText("?", textX, mTextY, mTextPaint);
                // reset, unknown status means we need to start over next time
                // and mLastChargingAlpha may be != 0, we don't want it to be
                mLastChargingAlpha = 0;
            } else if (tracker.plugged) {
                // draw charging dot Paint with calculated alpha
                canvas.drawArc(mChargingRectLeft, 270, 360, false, mChargingDotPaint);
            } else {
                mLastChargingAlpha = 0; // reset, charger is un-plugged
                if (level > mCriticalLevel
                        && (mShowPercent && !(tracker.level == 100 && !SHOW_100_PERCENT))) {
                    // draw the percentage text
                    String pctText = String.valueOf(SINGLE_DIGIT_PERCENT ? (level / 10) : level);
                    mTextPaint.setColor(paint.getColor());
                    canvas.drawText(pctText, textX, mTextY, mTextPaint);
                } else if (level <= mCriticalLevel) {
                    // draw the warning text
                    canvas.drawText(mWarningString, textX, mTextY, mWarningTextPaint);
                }
            }
        }

        /**
         * Update alpha animation.
         * Cares for timed callbacks to continue animation cycles.
         * Uses mInvalidate for delayed invalidate() callbacks.
         */
        private void updateChargeAnim(BatteryTracker tracker) {
            boolean batteryFull = tracker.status == BatteryManager.BATTERY_STATUS_FULL;
            boolean batteryLevel100 = tracker.level == 100;
            boolean shouldIndicateCharging = tracker.shouldIndicateCharging();

            if (!shouldIndicateCharging) {
                // Remove invalidate, drawCircle() removes dot Paint when unplugged.
                mLastChargingAlpha = 0;
                mHandler.removeCallbacks(mInvalidate);
                return;
            }

            mChargingDotPaint.setAlpha(calculateChargingDotAlpha(batteryFull, batteryLevel100));

            mHandler.removeCallbacks(mInvalidate);
            mHandler.postDelayed(mInvalidate, 50);
        }

        /**
         * Generate next alpha for charging dot.
         * Animate faster (2x) till around 40% battery level then slow down (1x) till 100% and
         * from 100% to 40% back again, then speed up (2x) till MIN_ALPHA and restart loop.
         */
        private int calculateChargingDotAlpha(boolean batteryFull, boolean batteryLevel100) {
            if (mLastChargingAlpha == 0) {
                mIncreaseAlpha = true;
                // set up start point
                mLastChargingAlpha = ALPHA_SLOW_INCREMENT;
            } else if (mLastChargingAlpha == MAX_ALPHA) {
                if (batteryFull || batteryLevel100) {
                    // we've reached full battery, stop animating
                    // and keep setting MAX_ALPHA alpha
                    return MAX_ALPHA;
                }
                // start to descrease
                mIncreaseAlpha = false;
                mLastChargingAlpha -= ALPHA_SLOW_INCREMENT;
            } else if (mLastChargingAlpha == MIN_ALPHA) {
                // start to increase
                mIncreaseAlpha = true;
                mLastChargingAlpha += ALPHA_FAST_INCREMENT;
            } else if (mLastChargingAlpha != 0) {
                // continue incresing/decreasing
                if (mIncreaseAlpha) {
                    if (mLastChargingAlpha < SPEED_SWITCH_VALUE) {
                        mLastChargingAlpha += ALPHA_FAST_INCREMENT;
                    } else {
                        mLastChargingAlpha += ALPHA_SLOW_INCREMENT;
                    }
                } else {
                    if (mLastChargingAlpha > SPEED_SWITCH_VALUE) {
                        mLastChargingAlpha -= ALPHA_SLOW_INCREMENT;
                    } else {
                        mLastChargingAlpha -= ALPHA_FAST_INCREMENT;
                    }
                }
            }

            return mLastChargingAlpha;
        }

        /**
         * Initializes all size dependent variables.
         * Sets stroke width and text size of all involved Paints.
         */
        private void initSizes() {
            mCircleSize = Math.min(getMeasuredWidth(), getMeasuredHeight());
            mTextPaint.setTextSize(mCircleSize / 2f);
            mWarningTextPaint.setTextSize(mCircleSize / 2f);

            float strokeWidth = mCircleSize / STROKE_WITH;
            mFrontPaint.setStrokeWidth(strokeWidth);
            mBackPaint.setStrokeWidth(strokeWidth);

            // calculate circle indicator rectangle for drawArc calls
            int pLeft = getPaddingLeft();
            mRectLeft = new RectF(pLeft + strokeWidth / 2.0f, 0 + strokeWidth / 2.0f, mCircleSize
                    - strokeWidth / 2.0f + pLeft, mCircleSize - strokeWidth / 2.0f);

            // calculate charging dot rectangle for drawArc calls
            mChargingRectLeft = new RectF(mRectLeft.left / 2.0f, mRectLeft.top / 2.0f,
                    mRectLeft.right / 2.0f, mRectLeft.bottom / 2.0f);
            // center charging dot rectangle
            float rectSizeY = mChargingRectLeft.right - mChargingRectLeft.left;
            float rectSizeX = mChargingRectLeft.bottom - mChargingRectLeft.top;
            float newY = mRectLeft.centerY() - (rectSizeY / 2);
            float newX = mRectLeft.centerX() - (rectSizeX / 2);
            mChargingRectLeft.offsetTo(newX, newY);

            mLastChargingAlpha = 0;

            // calculate Y position for text
            Rect bounds = new Rect();
            mTextPaint.getTextBounds("99", 0, "99".length(), bounds);
            mTextX = mCircleSize / 2.0f + getPaddingLeft();
            // the +1dp at end of formula balances out rounding issues.works out on all resolutions
            mTextY = mCircleSize / 2.0f + (bounds.bottom - bounds.top) / 2.0f
                    - strokeWidth / 2.0f + getResources().getDisplayMetrics().density;
        }
    }
}
