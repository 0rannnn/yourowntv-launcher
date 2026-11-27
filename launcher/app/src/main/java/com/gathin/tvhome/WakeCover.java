package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;

/**
 * stock_home_redirect at power-on: TVs like the balcony Xiaomi bring the stock home (Google TV) up on every wake,
 * and it comes back a second time a moment later, so the redirect alone still shows it for seconds. Oran,
 * 2026-11-27: "the tv opens on the regular google tv screen, make sure it doesn't". So a black cover goes up when
 * the screen goes off (unseen) and stays over the waking screen until this launcher has been in front for a
 * moment; at most MAX_MS after the screen comes on, whatever happens. It takes no keys or touches.
 */
final class WakeCover {
    /** Our home in front this long, with no stock home since, and the cover comes off. */
    static final long SETTLE_MS = 2500;   // the Xiaomi brings Google TV back ~1.2 s after our first redirect
    /** Never longer than this after the screen comes on. */
    static final long MAX_MS = 12000;
    /** If the screen never goes off for real (a quick off/on), not forever either. */
    static final long OFF_MAX_MS = 6 * 60 * 60 * 1000L;

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static View cover;
    private static final Runnable hide = WakeCover::hide;

    private WakeCover() {
    }

    /** Screen off: up, unseen. */
    static void show(AccessibilityService service) {
        if (cover != null) return;
        WindowManager windows = service.getSystemService(WindowManager.class);
        if (windows == null) return;
        View view = new View(service);
        view.setBackgroundColor(Color.BLACK);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE);
        try {
            windows.addView(view, lp);
            cover = view;
        } catch (RuntimeException e) {
            return;
        }
        handler.removeCallbacks(hide);
        handler.postDelayed(hide, OFF_MAX_MS);
    }

    /** No stock home this long after the screen-on broadcast (it woke to an input or an app): off. */
    static final long QUIET_MS = 3000;

    /** Screen on: the waking screen stays covered, MAX_MS at most. */
    static void screenOn() {
        if (cover == null) return;
        handler.removeCallbacks(hide);
        handler.postDelayed(hide, MAX_MS);
        handler.removeCallbacks(settle);
        handler.postDelayed(settle, QUIET_MS);
    }

    /** A window came up: our home (or another app) settles the cover; the stock home keeps it. */
    static void windowShown(boolean stockHome) {
        if (cover == null) return;
        handler.removeCallbacks(settle);
        if (!stockHome) handler.postDelayed(settle, SETTLE_MS);
    }

    private static final Runnable settle = WakeCover::hide;

    static boolean up() {
        return cover != null;
    }

    static void hide() {
        handler.removeCallbacks(hide);
        handler.removeCallbacks(settle);
        View view = cover;
        cover = null;
        if (view == null) return;
        view.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            try {
                view.getContext().getSystemService(WindowManager.class).removeView(view);
            } catch (RuntimeException ignored) {
            }
        }).start();
    }
}
