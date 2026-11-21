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
 * Our own volume meter: the TV's volume on a 0 to 100 scale, in a glass pill at the top of the screen, over any app,
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
    private static final long SHOW_MS = 2200;

    private static VolumeMeter active;

    private final AccessibilityService service;
    private final AudioManager audio;
    private final WindowManager windows;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float density;
    private BroadcastReceiver receiver;
    private ContentObserver observer;
    private LinearLayout pill;
    private ImageView icon;
    private Bar bar;
    private TextView number;
    private boolean added;
    private int last = Integer.MIN_VALUE;
    private final Runnable hide = this::hide;
    /** A look at the level four times a second: some firmware (Formovie) changes it with no broadcast at all. */
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            show(false);
            main.postDelayed(this, POLL_MS);
        }
    };
    private static final long POLL_MS = 100;
    /** Hold boost: one extra step, halfway to the firmware's next one, while a volume key is held (see boost). */
    private static final long BOOST_DELAY_MS = 110;
    /** Two steps in the same direction this close together mean the key is held, not tapped. */
    private static final long HELD_GAP_MS = 600;
    private long lastStepAt;
    private int lastDirection;
    /** Our own extra steps land just after we make them: those changes are shown but not boosted again. */
    private long ownStepsUntil;
    /** The last volume key the helper itself received: then VolumeKeys repeats a held key and no boost is needed. */
    private static long lastKeyAt;

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
        JSONObject json = HeaderExtras.readConfig(service);
        meter.boostEnabled = json == null || json.optBoolean("volume_hold_boost", true);
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
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            lastKeyAt = android.os.SystemClock.uptimeMillis();
            meter.main.postDelayed(() -> meter.show(true), 120);
        }
    }

    /** The theme's volume_hold_boost: false leaves a held key at the firmware's own speed. */
    private boolean boostEnabled = true;

    private static boolean enabled(Context ctx) {
        JSONObject json = HeaderExtras.readConfig(ctx);
        return json == null || json.optBoolean("volume_meter", true);
    }

    private void start() {
        last = level();
        main.postDelayed(poll, POLL_MS);
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                show(false);
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
                show(false);
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
    private void show(boolean fromKey) {
        int now = level();
        if (!fromKey && now == last) return;
        if (!fromKey && now >= 0 && last >= 0) boost(now - last);
        last = now;
        try {
            if (pill == null) build();
            boolean muted = now == 0 && audio.isStreamMute(AudioManager.STREAM_MUSIC);
            icon.setAlpha(muted ? 0.45f : 1f);
            bar.setLevel(Math.max(0, now));
            bar.setVisibility(now < 0 ? View.GONE : View.VISIBLE);
            number.setText(now < 0 ? service.getString(R.string.volume_fixed) : muted ? service.getString(R.string.volume_muted)
                    : String.valueOf(now));
            if (!added) {
                windows.addView(pill, params());
                added = true;
                pill.setAlpha(0f);
                pill.setTranslationY(-dp(12));
            }
            pill.animate().alpha(1f).translationY(0).setDuration(180).setInterpolator(Motion.ENTER).start();
            main.removeCallbacks(hide);
            main.postDelayed(hide, SHOW_MS);
        } catch (RuntimeException e) {
            // the overlay could not be shown (the helper is going away): the next change tries again
            added = false;
        }
    }

    /**
     * Holding a volume key: some firmware (Formovie) takes the remote's volume keys before any app sees them and
     * repeats a held one at only about 4 steps a second. Steps in the same direction close together mean the key is
     * held, so each one gets one more step, placed halfway to the firmware's next: holding moves evenly at about twice
     * the speed, a single press still moves one step. Not when the helper gets the keys itself (VolumeKeys repeats
     * those quickly already).
     */
    private void boost(int change) {
        long t = android.os.SystemClock.uptimeMillis();
        int direction = Integer.signum(change);
        if (direction == 0 || t < ownStepsUntil) return;
        boolean held = direction == lastDirection && t - lastStepAt < HELD_GAP_MS && t - lastKeyAt > 1500;
        lastDirection = direction;
        lastStepAt = t;
        // The shell watcher gives the exact key down and up (VolumeHold): it repeats instead.
        if (!held || !boostEnabled || VolumeHold.live) return;
        // Our step, and the change it makes, are not boosted again: the firmware's next step comes after this.
        ownStepsUntil = t + BOOST_DELAY_MS + 110;
        main.postDelayed(() -> {
            try {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        direction > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
            } catch (RuntimeException ignored) {
            }
        }, BOOST_DELAY_MS);
    }

    private void hide() {
        if (pill == null || !added) return;
        pill.animate().alpha(0f).translationY(-dp(12)).setDuration(220).setInterpolator(Motion.EXIT)
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
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(36);
        return lp;
    }

    private void build() {
        pill = new LinearLayout(service);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(22), dp(14), dp(26), dp(14));
        GradientDrawable glass = new GradientDrawable();
        glass.setCornerRadius(dp(40));
        glass.setColor(0xD91C1C22);
        glass.setStroke(Math.max(1, dp(1)), 0x33FFFFFF);
        pill.setBackground(glass);
        pill.setElevation(dp(10));

        icon = new ImageView(service);
        icon.setImageResource(R.drawable.ic_volume);
        icon.setColorFilter(Color.WHITE);
        pill.addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));

        bar = new Bar(service);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(300), dp(10));
        barLp.leftMargin = dp(18);
        barLp.rightMargin = dp(18);
        pill.addView(bar, barLp);

        number = new TextView(service);
        number.setTextColor(Color.WHITE);
        number.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        number.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        number.setSingleLine(true);
        number.setMaxLines(1);
        number.setIncludeFontPadding(false);
        number.setMinWidth(dp(56));
        number.setGravity(Gravity.CENTER);
        number.setTag(HebrewFont.KEEP);
        pill.addView(number, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
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
            float r = getHeight() / 2f;
            rect.set(0, 0, getWidth(), getHeight());
            canvas.drawRoundRect(rect, r, r, track);
            // Glide a quarter of the way per frame: a held key reads as one smooth movement.
            shown += (target - shown) * 0.25f;
            if (Math.abs(target - shown) < 0.002f) shown = target;
            if (shown > 0) {
                rect.set(0, 0, Math.max(getHeight(), getWidth() * shown), getHeight());
                canvas.drawRoundRect(rect, r, r, fill);
            }
            if (shown != target) postInvalidateOnAnimation();
        }
    }
}
