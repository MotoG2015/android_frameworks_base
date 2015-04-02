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

package com.android.systemui.volume;

import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Settings.Global;
import android.service.notification.ZenModeConfig;
import android.service.notification.ZenModeConfig.DowntimeInfo;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLayoutChangeListener;
import android.view.View.OnTouchListener;
import android.view.ViewGroup;
import android.view.ViewGroup.MarginLayoutParams;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.volume.VolumeDialogController.State;
import com.android.systemui.volume.VolumeDialogController.StreamState;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Visual presentation of the volume dialog.
 *
 * A client of VolumeDialogController and its state model.
 *
 * Methods ending in "H" must be called on the (ui) handler.
 */
public class VolumeDialog {
    private static final String TAG = Util.logTag(VolumeDialog.class);

    private static final long USER_ATTEMPT_GRACE_PERIOD = 1000;
    private static final int WAIT_FOR_RIPPLE = 200;
    private static final int UPDATE_ANIMATION_DURATION = 80;

    private final Context mContext;
    private final H mHandler = new H();
    private final VolumeDialogController mController;

    private final CustomDialog mDialog;
    private final ViewGroup mDialogView;
    private final ViewGroup mDialogContentView;
    private final ImageButton mExpandButton;
    private final TextView mFootlineText;
    private final Button mFootlineAction;
    private final View mSettingsButton;
    private final View mFooter;
    private final List<VolumeRow> mRows = new ArrayList<VolumeRow>();
    private final SpTexts mSpTexts;
    private final SparseBooleanArray mDynamic = new SparseBooleanArray();
    private final KeyguardManager mKeyguard;
    private final int mExpandButtonAnimationDuration;
    private final View mTextFooter;
    private final ZenFooter mZenFooter;
    private final LayoutTransition mLayoutTransition;

    private boolean mShowing;
    private boolean mExpanded;
    private int mActiveStream;
    private boolean mShowHeaders = Prefs.DEFAULT_SHOW_HEADERS;
    private boolean mShowFooter = Prefs.DEFAULT_SHOW_FOOTER;
    private boolean mShowZenFooter = Prefs.DEFAULT_ZEN_FOOTER;
    private boolean mAutomute = Prefs.DEFAULT_ENABLE_AUTOMUTE;
    private boolean mSilentMode = Prefs.DEFAULT_ENABLE_SILENT_MODE;
    private State mState;
    private int mExpandAnimRes;
    private boolean mExpanding;

    public VolumeDialog(Context context, VolumeDialogController controller,
            ZenModeController zenModeController) {
        mContext = context;
        mController = controller;
        mSpTexts = new SpTexts(mContext);
        mKeyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);

        mDialog = new CustomDialog(mContext);

        final Window window = mDialog.getWindow();
        window.requestFeature(Window.FEATURE_NO_TITLE);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        mDialog.setCanceledOnTouchOutside(true);
        final Resources res = mContext.getResources();
        final WindowManager.LayoutParams lp = window.getAttributes();
        lp.type = WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
        lp.format = PixelFormat.TRANSLUCENT;
        lp.setTitle(VolumeDialog.class.getSimpleName());
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.windowAnimations = R.style.VolumeDialogAnimations;
        lp.y = res.getDimensionPixelSize(R.dimen.volume_offset_top);
        lp.gravity = Gravity.TOP;
        window.setAttributes(lp);

        mDialog.setContentView(R.layout.volume_dialog);
        mDialogView = (ViewGroup) mDialog.findViewById(R.id.volume_dialog);
        mDialogContentView = (ViewGroup) mDialog.findViewById(R.id.volume_dialog_content);
        mExpandButton = (ImageButton) mDialogView.findViewById(R.id.volume_expand_button);
        mExpandButton.setOnClickListener(mClickExpand);
        updateWindowWidthH();
        updateExpandButtonH();
        mLayoutTransition = new LayoutTransition();
        mLayoutTransition.setDuration(new ValueAnimator().getDuration() / 2);
        mLayoutTransition.disableTransitionType(LayoutTransition.DISAPPEARING);
        mLayoutTransition.disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);
        mDialogContentView.setLayoutTransition(mLayoutTransition);

        addRow(AudioManager.STREAM_RING,
                R.drawable.ic_volume_ringer, R.drawable.ic_volume_ringer_mute, true);
        addRow(AudioManager.STREAM_MUSIC,
                R.drawable.ic_volume_media, R.drawable.ic_volume_media_mute, true);
        addRow(AudioManager.STREAM_ALARM,
                R.drawable.ic_volume_alarm, R.drawable.ic_volume_alarm_mute, false);
        addRow(AudioManager.STREAM_VOICE_CALL,
                R.drawable.ic_volume_voice, R.drawable.ic_volume_voice, false);
        addRow(AudioManager.STREAM_BLUETOOTH_SCO,
                R.drawable.ic_volume_bt_sco, R.drawable.ic_volume_bt_sco, false);
        addRow(AudioManager.STREAM_SYSTEM,
                R.drawable.ic_volume_system, R.drawable.ic_volume_system_mute, false);

        mTextFooter = mDialog.findViewById(R.id.volume_text_footer);
        mFootlineText = (TextView) mDialog.findViewById(R.id.volume_footline_text);
        mSpTexts.add(mFootlineText);
        mFootlineAction = (Button) mDialog.findViewById(R.id.volume_footline_action_button);
        mSpTexts.add(mFootlineAction);
        mFooter = mDialog.findViewById(R.id.volume_footer);
        mSettingsButton = mDialog.findViewById(R.id.volume_settings_button);
        mSettingsButton.setOnClickListener(mClickSettings);
        mExpandButtonAnimationDuration = res.getInteger(R.integer.volume_expand_animation_duration);
        mZenFooter = (ZenFooter) mDialog.findViewById(R.id.volume_zen_footer);
        mZenFooter.init(zenModeController, mZenFooterCallback);

        controller.addCallback(mControllerCallbackH, mHandler);
        controller.getState();
    }

    private void updateWindowWidthH() {
        final ViewGroup.LayoutParams lp = mDialogView.getLayoutParams();
        final DisplayMetrics dm = mContext.getResources().getDisplayMetrics();
        if (D.BUG) Log.d(TAG, "updateWindowWidth dm.w=" + dm.widthPixels);
        int w = dm.widthPixels;
        final int max = mContext.getResources()
                .getDimensionPixelSize(R.dimen.standard_notification_panel_width);
        if (w > max) {
            w = max;
        }
        w -= mContext.getResources().getDimensionPixelSize(R.dimen.notification_side_padding) * 2;
        lp.width = w;
        mDialogView.setLayoutParams(lp);
    }

    public void setStreamImportant(int stream, boolean important) {
        mHandler.obtainMessage(H.SET_STREAM_IMPORTANT, stream, important ? 1 : 0).sendToTarget();
    }

    public void setShowHeaders(boolean showHeaders) {
        if (showHeaders == mShowHeaders) return;
        mShowHeaders = showHeaders;
        mHandler.sendEmptyMessage(H.RECHECK_ALL);
    }

    public void setShowFooter(boolean show) {
        if (mShowFooter == show) return;
        mShowFooter = show;
        mHandler.sendEmptyMessage(H.RECHECK_ALL);
    }

    public void setZenFooter(boolean zen) {
        if (mShowZenFooter == zen) return;
        mShowZenFooter = zen;
        mHandler.sendEmptyMessage(H.RECHECK_ALL);
    }

    public void setAutomute(boolean automute) {
        if (mAutomute == automute) return;
        mAutomute = automute;
        mHandler.sendEmptyMessage(H.RECHECK_ALL);
    }

    public void setSilentMode(boolean silentMode) {
        if (mSilentMode == silentMode) return;
        mSilentMode = silentMode;
        mHandler.sendEmptyMessage(H.RECHECK_ALL);
    }

    private void addRow(int stream, int iconRes, int iconMuteRes, boolean important) {
        final VolumeRow row = initRow(stream, iconRes, iconMuteRes, important);
        if (!mRows.isEmpty()) {
            final View v = new View(mContext);
            final int h = mContext.getResources()
                    .getDimensionPixelSize(R.dimen.volume_slider_interspacing);
            final LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h);
            mDialogContentView.addView(v, mDialogContentView.getChildCount() - 1, lp);
            row.space = v;
        }
        row.settingsButton.addOnLayoutChangeListener(new OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (D.BUG) Log.d(TAG, "onLayoutChange"
                        + " old=" + new Rect(oldLeft, oldTop, oldRight, oldBottom).toShortString()
                        + " new=" + new Rect(left,top,right,bottom).toShortString());
                if (oldLeft != left || oldTop != top) {
                    for (int i = 0; i < mDialogContentView.getChildCount(); i++) {
                        final View c = mDialogContentView.getChildAt(i);
                        if (!c.isShown()) continue;
                        if (c == row.view) {
                            repositionExpandAnim(row);
                        }
                        return;
                    }
                }
            }
        });
        // add new row just before the footer
        mDialogContentView.addView(row.view, mDialogContentView.getChildCount() - 1);
        mRows.add(row);
    }

    private boolean isAttached() {
        return mDialogContentView != null && mDialogContentView.isAttachedToWindow();
    }

    private VolumeRow getActiveRow() {
        for (VolumeRow row : mRows) {
            if (row.stream == mActiveStream) {
                return row;
            }
        }
        return mRows.get(0);
    }

    private VolumeRow findRow(int stream) {
        for (VolumeRow row : mRows) {
            if (row.stream == stream) return row;
        }
        return null;
    }

    private void repositionExpandAnim(VolumeRow row) {
        final int[] loc = new int[2];
        row.settingsButton.getLocationInWindow(loc);
        final MarginLayoutParams mlp = (MarginLayoutParams) mDialogView.getLayoutParams();
        final int x = loc[0] - mlp.leftMargin;
        final int y = loc[1] - mlp.topMargin;
        if (D.BUG) Log.d(TAG, "repositionExpandAnim x=" + x + " y=" + y);
        mExpandButton.setTranslationX(x);
        mExpandButton.setTranslationY(y);
    }

    public void dump(PrintWriter writer) {
        writer.println(VolumeDialog.class.getSimpleName() + " state:");
        writer.print("  mShowing: "); writer.println(mShowing);
        writer.print("  mExpanded: "); writer.println(mExpanded);
        writer.print("  mExpanding: "); writer.println(mExpanding);
        writer.print("  mActiveStream: "); writer.println(mActiveStream);
        writer.print("  mDynamic: "); writer.println(mDynamic);
        writer.print("  mShowHeaders: "); writer.println(mShowHeaders);
        writer.print("  mShowFooter: "); writer.println(mShowFooter);
        writer.print("  mAutomute: "); writer.println(mAutomute);
        writer.print("  mSilentMode: "); writer.println(mSilentMode);
    }

    private static int getImpliedLevel(SeekBar seekBar, int progress) {
        final int m = seekBar.getMax();
        final int n = m / 100 - 1;
        final int level = progress == 0 ? 0
                : progress == m ? (m / 100) : (1 + (int)((progress / (float) m) * n));
        return level;
    }

    @SuppressLint("InflateParams")
    private VolumeRow initRow(final int stream, int iconRes, int iconMuteRes, boolean important) {
        final VolumeRow row = new VolumeRow();
        row.stream = stream;
        row.iconRes = iconRes;
        row.iconMuteRes = iconMuteRes;
        row.important = important;
        row.view = mDialog.getLayoutInflater().inflate(R.layout.volume_dialog_row, null);
        row.view.setTag(row);
        row.header = (TextView) row.view.findViewById(R.id.volume_row_header);
        mSpTexts.add(row.header);
        row.slider = (SeekBar) row.view.findViewById(R.id.volume_row_slider);
        row.slider.setOnSeekBarChangeListener(new VolumeSeekBarChangeListener(row));

        // forward events above the slider into the slider
        row.view.setOnTouchListener(new OnTouchListener() {
            private final Rect mSliderHitRect = new Rect();
            private boolean mDragging;

            @SuppressLint("ClickableViewAccessibility")
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                row.slider.getHitRect(mSliderHitRect);
                if (!mDragging && event.getActionMasked() == MotionEvent.ACTION_DOWN
                        && event.getY() < mSliderHitRect.top) {
                    mDragging = true;
                }
                if (mDragging) {
                    event.offsetLocation(-mSliderHitRect.left, -mSliderHitRect.top);
                    row.slider.dispatchTouchEvent(event);
                    if (event.getActionMasked() == MotionEvent.ACTION_UP
                            || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                        mDragging = false;
                    }
                    return true;
                }
                return false;
            }
        });
        row.icon = (ImageButton) row.view.findViewById(R.id.volume_row_icon);
        row.icon.setImageResource(iconRes);
        row.icon.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Events.writeEvent(Events.EVENT_ICON_CLICK, row.stream, row.iconState);
                mController.setActiveStream(row.stream);
                if (row.stream == AudioManager.STREAM_RING) {
                    final boolean hasVibrator = mController.hasVibrator();
                    if (mState.ringerModeInternal == AudioManager.RINGER_MODE_NORMAL) {
                        if (hasVibrator) {
                            mController.setRingerMode(AudioManager.RINGER_MODE_VIBRATE, false);
                        } else {
                            final boolean wasZero = row.ss.level == 0;
                            mController.setStreamVolume(stream, wasZero ? row.lastAudibleLevel : 0);
                        }
                    } else {
                        mController.setRingerMode(AudioManager.RINGER_MODE_NORMAL, false);
                        if (row.ss.level == 0) {
                            mController.setStreamVolume(stream, 1);
                        }
                    }
                } else {
                    if (mAutomute && !row.ss.muteSupported) {
                        final boolean vmute = row.ss.level == 0;
                        mController.setStreamVolume(stream, vmute ? row.lastAudibleLevel : 0);
                    } else {
                        final boolean mute = !row.ss.muted;
                        mController.setStreamMute(stream, mute);
                        if (mAutomute) {
                            if (!mute && row.ss.level == 0) {
                                mController.setStreamVolume(stream, 1);
                            }
                        }
                    }
                }
                row.userAttempt = 0;  // reset the grace period, slider should update immediately
            }
        });
        row.settingsButton = (ImageButton) row.view.findViewById(R.id.volume_settings_button);
        row.settingsButton.setOnClickListener(mClickSettings);
        return row;
    }

    public void destroy() {
        mController.removeCallback(mControllerCallbackH);
    }

    public void show(int reason) {
        mHandler.obtainMessage(H.SHOW, reason, 0).sendToTarget();
    }

    public void dismiss(int reason) {
        mHandler.obtainMessage(H.DISMISS, reason, 0).sendToTarget();
    }

    protected void onSettingsClickedH() {
        // hook for subclasses
    }

    protected void onZenSettingsClickedH() {
        // hook for subclasses
    }

    private void showH(int reason) {
        mHandler.removeMessages(H.SHOW);
        mHandler.removeMessages(H.DISMISS);
        rescheduleTimeoutH();
        if (mShowing) return;
        mShowing = true;
        mDialog.show();
        Events.writeEvent(Events.EVENT_SHOW_DIALOG, reason, mKeyguard.isKeyguardLocked());
        mController.notifyVisible(true);
    }

    protected void rescheduleTimeoutH() {
        mHandler.removeMessages(H.DISMISS);
        final int timeout = computeTimeoutH();
        if (D.BUG) Log.d(TAG, "rescheduleTimeout " + timeout);
        mHandler.sendMessageDelayed(mHandler
                .obtainMessage(H.DISMISS, Events.DISMISS_REASON_TIMEOUT, 0), timeout);
        mController.userActivity();
    }

    private int computeTimeoutH() {
        if (mZenFooter != null && mZenFooter.isFooterExpanded()) return 10000;
        if (mExpanded || mExpanding) return 5000;
        if (mActiveStream == AudioManager.STREAM_MUSIC) return 1500;
        return 3000;
    }

    protected void dismissH(int reason) {
        mHandler.removeMessages(H.DISMISS);
        mHandler.removeMessages(H.SHOW);
        if (!mShowing) return;
        mShowing = false;
        mDialog.dismiss();
        Events.writeEvent(Events.EVENT_DISMISS_DIALOG, reason);
        setExpandedH(false);
        mController.notifyVisible(false);
    }

    private void setExpandedH(boolean expanded) {
        if (mExpanded == expanded) return;
        mExpanded = expanded;
        mExpanding = isAttached();
        if (D.BUG) Log.d(TAG, "setExpandedH " + expanded);
        updateRowsH();
        if (mExpanding) {
            final Drawable d = mExpandButton.getDrawable();
            if (d instanceof AnimatedVectorDrawable) {
                // workaround to reset drawable
                final AnimatedVectorDrawable avd = (AnimatedVectorDrawable) d.getConstantState()
                        .newDrawable();
                mExpandButton.setImageDrawable(avd);
                avd.start();
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        mExpanding = false;
                        updateExpandButtonH();
                        rescheduleTimeoutH();
                    }
                }, mExpandButtonAnimationDuration);
            }
        }
        rescheduleTimeoutH();
    }

    private void updateExpandButtonH() {
        mExpandButton.setClickable(!mExpanding);
        if (mExpanding && isAttached()) return;
        final int res = mExpanded ? R.drawable.ic_volume_collapse_animation
                : R.drawable.ic_volume_expand_animation;
        if (res == mExpandAnimRes) return;
        mExpandAnimRes = res;
        mExpandButton.setImageResource(res);
    }

    private boolean isVisibleH(VolumeRow row, boolean isActive) {
        return mExpanded && row.view.getVisibility() == View.VISIBLE
                || (mExpanded && (row.important || isActive))
                || !mExpanded && isActive;
    }

    private void updateRowsH() {
        final VolumeRow activeRow = getActiveRow();
        updateFooterH();
        updateExpandButtonH();
        final boolean footerVisible = mFooter.getVisibility() == View.VISIBLE;
        if (!mShowing) {
            trimObsoleteH();
        }
        // first, find the last visible row
        VolumeRow lastVisible = null;
        for (VolumeRow row : mRows) {
            final boolean isActive = row == activeRow;
            if (isVisibleH(row, isActive)) {
                lastVisible = row;
            }
        }
        // apply changes to all rows
        for (VolumeRow row : mRows) {
            final boolean isActive = row == activeRow;
            final boolean visible = isVisibleH(row, isActive);
            Util.setVisOrGone(row.view, visible);
            Util.setVisOrGone(row.space, visible && mExpanded);
            final int expandButtonRes = mExpanded ? R.drawable.ic_volume_settings : 0;
            if (expandButtonRes != row.cachedExpandButtonRes) {
                row.cachedExpandButtonRes = expandButtonRes;
                if (expandButtonRes == 0) {
                    row.settingsButton.setImageDrawable(null);
                } else {
                    row.settingsButton.setImageResource(expandButtonRes);
                }
            }
            Util.setVisOrInvis(row.settingsButton,
                     mExpanded && (!footerVisible && row == lastVisible));
            row.header.setAlpha(mExpanded && isActive ? 1 : 0.5f);
        }
    }

    private void trimObsoleteH() {
        for (int i = mRows.size() -1; i >= 0; i--) {
            final VolumeRow row = mRows.get(i);
            if (row.ss == null || !row.ss.dynamic) continue;
            if (!mDynamic.get(row.stream)) {
                mRows.remove(i);
                mDialogContentView.removeView(row.view);
                mDialogContentView.removeView(row.space);
            }
        }
    }

    private void onStateChangedH(State state) {
        mState = state;
        mDynamic.clear();
        // add any new dynamic rows
        for (int i = 0; i < state.states.size(); i++) {
            final int stream = state.states.keyAt(i);
            final StreamState ss = state.states.valueAt(i);
            if (!ss.dynamic) continue;
            mDynamic.put(stream, true);
            if (findRow(stream) == null) {
                addRow(stream, R.drawable.ic_volume_remote, R.drawable.ic_volume_remote_mute, true);
            }
        }

        if (mActiveStream != state.activeStream) {
            mActiveStream = state.activeStream;
            updateRowsH();
            rescheduleTimeoutH();
        }
        for (VolumeRow row : mRows) {
            updateVolumeRowH(row);
        }
        updateFooterH();
    }

    private void updateTextFooterH() {
        final boolean zen = mState.zenMode != Global.ZEN_MODE_OFF;
        final boolean wasVisible = mFooter.getVisibility() == View.VISIBLE;
        Util.setVisOrGone(mTextFooter, mExpanded && mShowFooter && (zen || mShowing && wasVisible));
        if (mTextFooter.getVisibility() == View.VISIBLE) {
            String text = null;
            String action = null;
            if (mState.exitCondition != null) {
                final long countdown = ZenModeConfig.tryParseCountdownConditionId(mState
                        .exitCondition.id);
                if (countdown != 0) {
                    text = mContext.getString(R.string.volume_dnd_ends_at,
                            Util.getShortTime(countdown));
                    action = mContext.getString(R.string.volume_end_now);
                } else {
                    final DowntimeInfo info = ZenModeConfig.tryParseDowntimeConditionId(mState.
                            exitCondition.id);
                    if (info != null) {
                        text = mContext.getString(R.string.volume_dnd_ends_at,
                                Util.getShortTime(info));
                        action = mContext.getString(R.string.volume_end_now);
                    }
                }
            }
            if (text == null) {
                text = mContext.getString(R.string.volume_dnd_is_on);
            }
            if (action == null) {
                action = mContext.getString(R.string.volume_turn_off);
            }
            Util.setText(mFootlineText, text);
            Util.setText(mFootlineAction, action);
            mFootlineAction.setOnClickListener(mTurnOffDnd);
        }
        Util.setVisOrGone(mFootlineText, zen);
        Util.setVisOrGone(mFootlineAction, zen);
    }

    private void updateFooterH() {
        if (!mShowFooter) {
            Util.setVisOrGone(mFooter, false);
            return;
        }
        if (mShowZenFooter) {
            Util.setVisOrGone(mTextFooter, false);
            final boolean ringActive = mActiveStream == AudioManager.STREAM_RING;
            Util.setVisOrGone(mZenFooter, mZenFooter.isZen() && ringActive
                    || mShowing && (mExpanded || mZenFooter.getVisibility() == View.VISIBLE));
            mZenFooter.update();
        } else {
            Util.setVisOrGone(mZenFooter, false);
            updateTextFooterH();
        }
    }

    private void updateVolumeRowH(VolumeRow row) {
        if (mState == null) return;
        final StreamState ss = mState.states.get(row.stream);
        if (ss == null) return;
        row.ss = ss;
        if (ss.level > 0) {
            row.lastAudibleLevel = ss.level;
        }
        final boolean isRingStream = row.stream == AudioManager.STREAM_RING;
        final boolean isSystemStream = row.stream == AudioManager.STREAM_SYSTEM;
        final boolean isRingVibrate = isRingStream
                && mState.ringerModeInternal == AudioManager.RINGER_MODE_VIBRATE;
        final boolean isNoned = (isRingStream || isSystemStream)
                && mState.zenMode == Global.ZEN_MODE_NO_INTERRUPTIONS;
        final boolean isLimited = isRingStream
                && mState.zenMode == Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        final boolean isRingAndSuppressed = isRingStream && mState.effectsSuppressor != null;

        // update slider max
        final int max = ss.levelMax * 100;
        if (max != row.slider.getMax()) {
            row.slider.setMax(max);
        }

        // update header visible
        if (row.cachedShowHeaders != mShowHeaders) {
            row.cachedShowHeaders = mShowHeaders;
            Util.setVisOrGone(row.header, mShowHeaders);
        }

        // update header text
        final String text;
        if (isRingAndSuppressed) {
            text = mContext.getString(R.string.volume_stream_suppressed, ss.name,
                    mState.effectsSuppressorName);
        } else if (isNoned) {
            text = mContext.getString(R.string.volume_stream_muted_dnd, ss.name);
        } else if (isRingVibrate && isLimited) {
            text = mContext.getString(R.string.volume_stream_vibrate_dnd, ss.name);
        } else if (isRingVibrate) {
            text = mContext.getString(R.string.volume_stream_vibrate, ss.name);
        } else if (ss.muted || mAutomute && ss.level == 0) {
            text = mContext.getString(R.string.volume_stream_muted, ss.name);
        } else if (isLimited) {
            text = mContext.getString(R.string.volume_stream_limited_dnd, ss.name);
        } else {
            text = ss.name;
        }
        Util.setText(row.header, text);

        // update icon
        final boolean iconEnabled = !isRingAndSuppressed && (mAutomute || ss.muteSupported);
        row.icon.setEnabled(iconEnabled);
        row.icon.setAlpha(iconEnabled ? 1 : 0.5f);
        final int iconRes =
                !isRingAndSuppressed && isRingVibrate ? R.drawable.ic_volume_ringer_vibrate
                : ss.routedToBluetooth ?
                        (ss.muted ? R.drawable.ic_volume_bt_mute : R.drawable.ic_volume_bt)
                : isRingAndSuppressed || (mAutomute && ss.level == 0) ? row.iconMuteRes
                : (ss.muted ? row.iconMuteRes : row.iconRes);
        if (iconRes != row.cachedIconRes) {
            if (row.cachedIconRes != 0 && isRingVibrate) {
                mController.vibrate();
            }
            row.cachedIconRes = iconRes;
            row.icon.setImageResource(iconRes);
        }
        row.iconState =
                iconRes == R.drawable.ic_volume_ringer_vibrate ? Events.ICON_STATE_VIBRATE
                : (iconRes == R.drawable.ic_volume_bt_mute || iconRes == row.iconMuteRes)
                        ? Events.ICON_STATE_MUTE
                : (iconRes == R.drawable.ic_volume_bt || iconRes == row.iconRes)
                        ? Events.ICON_STATE_UNMUTE
                : Events.ICON_STATE_UNKNOWN;

        // update slider
        updateVolumeRowSliderH(row, isRingAndSuppressed);
    }

    private void updateVolumeRowSliderH(VolumeRow row, boolean isRingAndSuppressed) {
        row.slider.setEnabled(!isRingAndSuppressed);
        if (row.tracking) {
            return;  // don't update if user is sliding
        }
        if (isRingAndSuppressed) {
            row.slider.setProgress(0);
            return;
        }
        final int progress = row.slider.getProgress();
        final int level = getImpliedLevel(row.slider, progress);
        final boolean rowVisible = row.view.getVisibility() == View.VISIBLE;
        final boolean inGracePeriod = (SystemClock.uptimeMillis() - row.userAttempt)
                < USER_ATTEMPT_GRACE_PERIOD;
        mHandler.removeMessages(H.RECHECK, row);
        if (mShowing && rowVisible && inGracePeriod) {
            if (D.BUG) Log.d(TAG, "inGracePeriod");
            mHandler.sendMessageAtTime(mHandler.obtainMessage(H.RECHECK, row),
                    row.userAttempt + USER_ATTEMPT_GRACE_PERIOD);
            return;  // don't update if visible and in grace period
        }
        final int vlevel = row.ss.muted ? 0 : row.ss.level;
        if (vlevel == level) {
            if (mShowing && rowVisible) {
                return;  // don't clamp if visible
            }
        }
        final int newProgress = vlevel * 100;
        if (progress != newProgress) {
            if (mShowing && rowVisible) {
                // animate!
                if (row.anim != null && row.anim.isRunning()
                        && row.animTargetProgress == newProgress) {
                    return;  // already animating to the target progress
                }
                // start/update animation
                if (row.anim == null) {
                    row.anim = ObjectAnimator.ofInt(row.slider, "progress", progress, newProgress);
                    row.anim.setInterpolator(new DecelerateInterpolator());
                } else {
                    row.anim.cancel();
                    row.anim.setIntValues(progress, newProgress);
                }
                row.animTargetProgress = newProgress;
                row.anim.setDuration(UPDATE_ANIMATION_DURATION);
                row.anim.start();
            } else {
                // update slider directly to clamped value
                if (row.anim != null) {
                    row.anim.cancel();
                }
                row.slider.setProgress(newProgress);
            }
            if (mAutomute) {
                if (vlevel == 0 && !row.ss.muted && row.stream == AudioManager.STREAM_MUSIC) {
                    mController.setStreamMute(row.stream, true);
                }
            }
        }
    }

    private void recheckH(VolumeRow row) {
        if (row == null) {
            if (D.BUG) Log.d(TAG, "recheckH ALL");
            trimObsoleteH();
            for (VolumeRow r : mRows) {
                updateVolumeRowH(r);
            }
        } else {
            if (D.BUG) Log.d(TAG, "recheckH " + row.stream);
            updateVolumeRowH(row);
        }
    }

    private void setStreamImportantH(int stream, boolean important) {
        for (VolumeRow row : mRows) {
            if (row.stream == stream) {
                row.important = important;
                return;
            }
        }
    }

    private final VolumeDialogController.Callbacks mControllerCallbackH
            = new VolumeDialogController.Callbacks() {
        @Override
        public void onShowRequested(int reason) {
            showH(reason);
        }

        @Override
        public void onDismissRequested(int reason) {
            dismissH(reason);
        }

        public void onScreenOff() {
            dismissH(Events.DISMISS_REASON_SCREEN_OFF);
        }

        @Override
        public void onStateChanged(State state) {
            onStateChangedH(state);
        }

        @Override
        public void onLayoutDirectionChanged(int layoutDirection) {
            mDialogView.setLayoutDirection(layoutDirection);
        }

        @Override
        public void onConfigurationChanged() {
            updateWindowWidthH();
            mSpTexts.update();
        }

        @Override
        public void onShowVibrateHint() {
            if (mSilentMode) {
                mController.setRingerMode(AudioManager.RINGER_MODE_SILENT, false);
            }
        }

        public void onShowSilentHint() {
            if (mSilentMode) {
                mController.setRingerMode(AudioManager.RINGER_MODE_NORMAL, false);
            }
        }
    };

    private final OnClickListener mClickExpand = new OnClickListener() {
        @Override
        public void onClick(View v) {
            if (mExpanding) return;
            final boolean newExpand = !mExpanded;
            Events.writeEvent(Events.EVENT_EXPAND, v);
            setExpandedH(newExpand);
        }
    };

    private final OnClickListener mClickSettings = new OnClickListener() {
        @Override
        public void onClick(View v) {
            mSettingsButton.postDelayed(new Runnable() {
                @Override
                public void run() {
                    Events.writeEvent(Events.EVENT_SETTINGS_CLICK);
                    onSettingsClickedH();
                }
            }, WAIT_FOR_RIPPLE);
        }
    };

    private final View.OnClickListener mTurnOffDnd = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            mSettingsButton.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mController.setZenMode(Global.ZEN_MODE_OFF);
                }
            }, WAIT_FOR_RIPPLE);
        }
    };

    private final ZenFooter.Callback mZenFooterCallback = new ZenFooter.Callback() {
        @Override
        public void onFooterExpanded() {
            mHandler.sendEmptyMessage(H.RESCHEDULE_TIMEOUT);
        }

        @Override
        public void onSettingsClicked() {
            dismiss(Events.DISMISS_REASON_SETTINGS_CLICKED);
            onZenSettingsClickedH();
        }

        @Override
        public void onDoneClicked() {
            dismiss(Events.DISMISS_REASON_DONE_CLICKED);
        }
    };

    private final class H extends Handler {
        private static final int SHOW = 1;
        private static final int DISMISS = 2;
        private static final int RECHECK = 3;
        private static final int RECHECK_ALL = 4;
        private static final int SET_STREAM_IMPORTANT = 5;
        private static final int RESCHEDULE_TIMEOUT = 6;

        public H() {
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case SHOW: showH(msg.arg1); break;
                case DISMISS: dismissH(msg.arg1); break;
                case RECHECK: recheckH((VolumeRow) msg.obj); break;
                case RECHECK_ALL: recheckH(null); break;
                case SET_STREAM_IMPORTANT: setStreamImportantH(msg.arg1, msg.arg2 != 0); break;
                case RESCHEDULE_TIMEOUT: rescheduleTimeoutH(); break;
            }
        }
    }

    private final class CustomDialog extends Dialog {
        public CustomDialog(Context context) {
            super(context);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            rescheduleTimeoutH();
            return super.dispatchTouchEvent(ev);
        }

        @Override
        protected void onStop() {
            super.onStop();
            mHandler.sendEmptyMessage(H.RECHECK_ALL);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (isShowing()) {
                if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
                    dismissH(Events.DISMISS_REASON_TOUCH_OUTSIDE);
                    return true;
                }
            }
            return false;
        }
    }

    private final class VolumeSeekBarChangeListener implements OnSeekBarChangeListener {
        private final VolumeRow mRow;

        private VolumeSeekBarChangeListener(VolumeRow row) {
            mRow = row;
        }

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (mRow.ss == null) return;
            if (D.BUG) Log.d(TAG, AudioSystem.streamToString(mRow.stream)
                    + " onProgressChanged " + progress + " fromUser=" + fromUser);
            if (!fromUser) return;
            if (mRow.ss.levelMin > 0) {
                final int minProgress = mRow.ss.levelMin * 100;
                if (progress < minProgress) {
                    seekBar.setProgress(minProgress);
                }
            }
            final int userLevel = getImpliedLevel(seekBar, progress);
            if (mRow.ss.level != userLevel || mRow.ss.muted && userLevel > 0) {
                mRow.userAttempt = SystemClock.uptimeMillis();
                if (mAutomute) {
                    if (mRow.stream != AudioManager.STREAM_RING) {
                        if (userLevel > 0 && mRow.ss.muted) {
                            mController.setStreamMute(mRow.stream, false);
                        }
                        if (userLevel == 0 && mRow.ss.muteSupported && !mRow.ss.muted) {
                            mController.setStreamMute(mRow.stream, true);
                        }
                    }
                }
                if (mRow.requestedLevel != userLevel) {
                    mController.setStreamVolume(mRow.stream, userLevel);
                    mRow.requestedLevel = userLevel;
                    Events.writeEvent(Events.EVENT_TOUCH_LEVEL_CHANGED, mRow.stream, userLevel);
                }
            }
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
            if (D.BUG) Log.d(TAG, "onStartTrackingTouch"+ " " + mRow.stream);
            mController.setActiveStream(mRow.stream);
            mRow.tracking = true;
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            if (D.BUG) Log.d(TAG, "onStopTrackingTouch"+ " " + mRow.stream);
            mRow.tracking = false;
            mRow.userAttempt = SystemClock.uptimeMillis();
            int userLevel = getImpliedLevel(seekBar, seekBar.getProgress());
            if (mRow.ss.level != userLevel) {
                mHandler.sendMessageDelayed(mHandler.obtainMessage(H.RECHECK, mRow),
                        USER_ATTEMPT_GRACE_PERIOD);
            }
        }
    }

    private static class VolumeRow {
        private View view;
        private View space;
        private TextView header;
        private ImageButton icon;
        private SeekBar slider;
        private ImageButton settingsButton;
        private int stream;
        private StreamState ss;
        private long userAttempt;  // last user-driven slider change
        private boolean tracking;  // tracking slider touch
        private int requestedLevel;
        private int iconRes;
        private int iconMuteRes;
        private boolean important;
        private int cachedIconRes;
        private int iconState;  // from Events
        private boolean cachedShowHeaders = Prefs.DEFAULT_SHOW_HEADERS;
        private int cachedExpandButtonRes;
        private ObjectAnimator anim;  // slider progress animation for non-touch-related updates
        private int animTargetProgress;
        private int lastAudibleLevel = 1;
    }
}