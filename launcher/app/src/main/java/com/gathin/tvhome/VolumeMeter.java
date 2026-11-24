package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Our own volume meter: the TV's volume on a 0 to 100 scale, in a slim glass column on the right edge (as a TV's own
 * volume bar: the number on top, the bar filling from the bottom, the speaker under it; dimmed while muted), over any app,
 * for two seconds after every change. Some firmware never shows its own bar for a change made through the key helper
 * (Xiaomi), and the others draw it differently on every model; this one is the same everywhere.
 *
 * It is drawn by the remote's key helper (HomeKeyService) as an accessibility overlay, which needs no other permission
 * and stays above every app. A change is noticed three ways, so at least one works on every TV: the system's volume
 * broadcast, the volume settings changing, the remote's volume keys (when the helper filters keys), and a look at the
 * level four times a second for firmware that changes it with none of those (Formovie). The level is
 * the media stream's, the one a TV plays everything on; a TV whose volume is set by a receiver or soundbar (fixed
 * volume) shows that instead of a number.
 *
 * The theme's {@code volume_meter: false} turns it off.
 */
final class VolumeMeter {
    private static final String VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    private static final String MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION";
    /**
     * Longer than Android's own volume bar stays (3 s after the last change): ours covers it (see the window's
     * position below), so it must not fade first and let the system's bar show at the end.
     */
    private static final long SHOW_MS = 3400;

    private static VolumeMeter active;

    private final AccessibilityService service;
    private final AudioManager audio;
    private final WindowManager windows;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** The checks (calls into the audio service) run here, off the thread that draws Home (VolumeThread). */
    private final Handler work = VolumeThread.get();
    private final float density;
    private BroadcastReceiver receiver;
    private ContentObserver observer;
    private LinearLayout pill;
    private ImageView icon;
    private Bar bar;
    private TextView number;
    private boolean added;
    private volatile int last = Integer.MIN_VALUE;
    private final Runnable hide = this::hide;
    /** A look at the level four times a second: some firmware (Formovie) changes it with no broadcast at all. */
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            check(false);
            work.postDelayed(this, POLL_MS);
        }
    };
    private static final long POLL_MS = 100;

    private VolumeMeter(AccessibilityService service) {
        this.service = service;
        audio = service.getSystemService(AudioManager.class);
        windows = service.getSystemService(WindowManager.class);
        density = service.getResources().getDisplayMetrics().density;
    }

    /** Starts the meter for this key helper (one meter, whichever helper comes first). */
    static void attach(AccessibilityService service) {
        if (active != null || !enabled(service)) return;
        VolumeMeter meter = new VolumeMeter(service);
        if (meter.audio == null || meter.windows == null) return;
        meter.start();
        active = meter;
    }

    static void detach(AccessibilityService service) {
        if (active == null || active.service != service) return;
        active.stop();
        active = null;
    }

    /** A volume key from the remote: the meter shows the level the key left, a moment after it. */
    static void onKey(KeyEvent event) {
        VolumeMeter meter = active;
        if (meter == null || event.getAction() != KeyEvent.ACTION_DOWN) return;
        int code = event.getKeyCode();
        if (event.getRepeatCount() == 0 && (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            pressed(code == KeyEvent.KEYCODE_VOLUME_UP ? 1 : -1);
        }
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            meter.work.postDelayed(() -> meter.check(true), 120);
        }
    }

    // ---- rapid presses on an external sound device -------------------------------------------------------------
    // On the balcony Xiaomi (WiiM over HDMI ARC) the WiiM applies about one step every 0.45 s and the TV's level only
    // follows its reports, so on quick presses the meter trailed the finger and kept moving after it stopped. There
    // the meter shows where the presses are taking the level at once (one step per press) and ignores reports that
    // lag behind it, until the device gets there (or goes further, on a held key) or PREDICT_MS pass without a
    // press. Display only: nothing is sent to the TV or the device.

    /** How long after the last press the meter keeps its own count before showing only what the device reports. */
    static final long PREDICT_MS = 4000;
    /** The same press can arrive twice (the key helper and the shell watcher): presses closer than this are one. */
    static final long SAME_PRESS_MS = 90;
    private volatile int predicted = -1;
    private volatile long predictedAt;
    private volatile int predictedDirection;
    private static long lastPressAt;
    private static int lastPressDirection;

    /** A volume key went down (+1 up, -1 down), from the key helper or the shell watcher. */
    static synchronized void pressed(int direction) {
        VolumeMeter meter = active;
        if (meter == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (direction == lastPressDirection && now - lastPressAt < SAME_PRESS_MS) return;
        lastPressAt = now;
        lastPressDirection = direction;
        meter.work.post(() -> meter.predict(direction, now));
    }

    /** On the volume thread: one step further than the last count (or the level now), shown at once. */
    private void predict(int direction, long at) {
        if (!VolumeHold.externalOutput(service)) return;   // the TV's own speaker answers at once: nothing to do
        int now = level();
        if (now < 0) return;
        int step = 1;
        try {
            step = Math.max(1, Math.round(100f / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)));
        } catch (RuntimeException ignored) {
        }
        // Still counting (a press in the last PREDICT_MS, either way): on from the count, not from the device's level,
        // which may not have caught up yet.
        boolean counting = predicted >= 0 && at - predictedAt < PREDICT_MS;
        int base = counting ? predicted : now;
        predicted = Math.max(0, Math.min(100, base + direction * step));
        predictedAt = at;
        predictedDirection = direction;
        last = predicted;
        int shown = predicted;
        android.util.Log.i("tvhome-volume", "press " + (direction > 0 ? "+" : "-") + ": meter " + shown + " (device " + now + ")");
        main.post(() -> show(shown, false));
    }

    /** A reported level while counting: true when it should not be shown (it still lags behind the presses). */
    private boolean lagging(int reported) {
        if (predicted < 0) return false;
        if (android.os.SystemClock.uptimeMillis() - predictedAt >= PREDICT_MS) {
            predicted = -1;
            return false;
        }
        boolean reached = predictedDirection > 0 ? reported >= predicted : reported <= predicted;
        if (reached) {
            predicted = -1;   // the device caught up (or went further): its own number again
            return false;
        }
        return true;
    }

    private static boolean enabled(Context ctx) {
        JSONObject json = HeaderExtras.readConfig(ctx);
        return json == null || json.optBoolean("volume_meter", true);
    }

    private void start() {
        last = level();
        work.postDelayed(poll, POLL_MS);
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                work.post(() -> check(false));
            }
        };
        IntentFilter filter = new IntentFilter(VOLUME_CHANGED);
        filter.addAction(MUTE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) service.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        else service.registerReceiver(receiver, filter);
        // Firmware that sends no broadcast still writes the new level to the volume settings.
        observer = new ContentObserver(main) {
            @Override
            public void onChange(boolean selfChange) {
                work.post(() -> check(false));
            }
        };
        try {
            service.getContentResolver().registerContentObserver(Settings.System.CONTENT_URI, true, observer);
        } catch (RuntimeException e) {
            observer = null;
        }
    }

    private void stop() {
        main.removeCallbacksAndMessages(null);
        work.removeCallbacks(poll);
        try {
            if (receiver != null) service.unregisterReceiver(receiver);
        } catch (RuntimeException ignored) {
        }
        if (observer != null) service.getContentResolver().unregisterContentObserver(observer);
        remove();
    }

    /** The media volume as 0 to 100; 0 while muted; -1 when the TV's volume is set elsewhere (fixed volume). */
    private int level() {
        try {
            if (audio.isVolumeFixed()) return -1;
            if (audio.isStreamMute(AudioManager.STREAM_MUSIC)) return 0;
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (max <= 0) return -1;
            return Math.round(audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** From a key: always shown. From a broadcast or a settings change: only when the level really moved. */
    /** On the volume thread: the level now; the meter shows it (on the main thread) when it moved or a key asked. */
    private void check(boolean fromKey) {
        int now = level();
        if (lagging(now)) return;   // rapid presses on an external device: the count stays on screen
        if (!fromKey && now == last) return;
        last = now;
        boolean muted;
        try {
            muted = now == 0 && audio.isStreamMute(AudioManager.STREAM_MUSIC);
        } catch (RuntimeException e) {
            muted = false;
        }
        boolean isMuted = muted;
        main.post(() -> show(now, isMuted));
    }

    /** On the main thread: the meter drawn for this level. */
    private void show(int now, boolean muted) {
        try {
            if (pill == null) build();
            icon.setAlpha(muted ? 0.45f : 1f);
            bar.setLevel(Math.max(0, now));
            bar.setVisibility(now < 0 ? View.GONE : View.VISIBLE);
            number.setText(now < 0 ? "–" : String.valueOf(Math.max(0, now)));
            if (!added) {
                windows.addView(pill, params());
                added = true;
                pill.setAlpha(0f);
                pill.setTranslationX(dp(24));
            }
            pill.animate().alpha(1f).translationX(0).setDuration(180).setInterpolator(Motion.ENTER).start();
            main.removeCallbacks(hide);
            main.postDelayed(hide, SHOW_MS);
        } catch (RuntimeException e) {
            // the overlay could not be shown (the helper is going away): the next change tries again
            added = false;
        }
    }

    private void hide() {
        if (pill == null || !added) return;
        pill.animate().alpha(0f).translationX(dp(24)).setDuration(220).setInterpolator(Motion.EXIT)
                .withEndAction(this::remove).start();
    }

    private void remove() {
        if (pill != null && added) {
            try {
                windows.removeView(pill);
            } catch (RuntimeException ignored) {
            }
        }
        added = false;
    }

    private WindowManager.LayoutParams params() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        // Over Android TV's own volume bar (VolumeDialogImpl, right edge, centred: on the Formovie x 1728-1920,
        // y 218-862, its panel about x 1785-1870). The firmware handles the volume keys before any helper sees them,
        // so its bar cannot be stopped; this overlay is drawn above it and, solid and wide enough, hides it.
        lp.x = dp(18);
        return lp;
    }

    /** A slim glass column on the right: the number on top, the bar filling from the bottom, the speaker under it. */
    private void build() {
        pill = new LinearLayout(service);
        pill.setOrientation(LinearLayout.VERTICAL);
        pill.setGravity(Gravity.CENTER_HORIZONTAL);
        pill.setPadding(dp(14), dp(18), dp(14), dp(16));
        GradientDrawable glass = new GradientDrawable();
        glass.setCornerRadius(dp(36));
        glass.setColor(0xFF1C1C22);   // solid: the system's bar underneath must not show through
        glass.setStroke(Math.max(1, dp(1)), 0x33FFFFFF);
        pill.setBackground(glass);
        pill.setElevation(dp(10));

        number = new TextView(service);
        number.setTextColor(Color.WHITE);
        number.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        number.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        number.setSingleLine(true);
        number.setIncludeFontPadding(false);
        number.setMinWidth(dp(40));
        number.setGravity(Gravity.CENTER);
        number.setTag(HebrewFont.KEEP);
        pill.addView(number, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        bar = new Bar(service);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(10), dp(240));
        barLp.topMargin = dp(14);
        barLp.bottomMargin = dp(14);
        pill.addView(bar, barLp);

        icon = new ImageView(service);
        icon.setImageResource(R.drawable.ic_volume);
        icon.setColorFilter(Color.WHITE);
        pill.addView(icon, new LinearLayout.LayoutParams(dp(26), dp(26)));
    }

    private int dp(float value) {
        return Math.round(value * density);
    }

    /** A rounded track and its white fill, the fill gliding to each new level. */
    private static final class Bar extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float shown;
        private float target;

        Bar(Context context) {
            super(context);
            track.setColor(0x40FFFFFF);
            fill.setColor(Color.WHITE);
        }

        void setLevel(int level) {
            target = Math.max(0, Math.min(100, level)) / 100f;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float r = getWidth() / 2f;
            rect.set(0, 0, getWidth(), getHeight());
            canvas.drawRoundRect(rect, r, r, track);
            // Glide a quarter of the way per frame: a held key reads as one smooth movement.
            shown += (target - shown) * 0.25f;
            if (Math.abs(target - shown) < 0.002f) shown = target;
            if (shown > 0) {
                float h = Math.max(getWidth(), getHeight() * shown);
                rect.set(0, getHeight() - h, getWidth(), getHeight());
                canvas.drawRoundRect(rect, r, r, fill);
            }
            if (shown != target) postInvalidateOnAnimation();
        }
    }
}
