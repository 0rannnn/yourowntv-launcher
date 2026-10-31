package com.gathin.tvhome;

import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;

/**
 * The breathing focus ring of switch_home and ds_home. Ten alpha steps a second from the main thread's Handler instead
 * of an animator: an ObjectAnimator redrew the screen 60 times a second for as long as Home sat focused (a third of the
 * projector's CPU), and kept running on a ring that had left the screen. One ring breathes at a time; it stops when its
 * tile loses the focus, and pauses while Home is not in front.
 */
final class RingPulse {
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final int STEPS = 14;
    private static final long FRAME_MS = 100;
    private static Drawable ring;
    private static int step;

    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            if (ring == null) return;
            double t = (step++ % STEPS) / (double) STEPS;
            ring.setAlpha((int) Math.round(175 + 80 * Math.cos(2 * Math.PI * t)));
            HANDLER.postDelayed(this, FRAME_MS);
        }
    };

    private RingPulse() {
    }

    static void start(Drawable focused) {
        stop();
        ring = focused;
        step = 0;
        HANDLER.post(TICK);
    }

    /** The focused tile let go of its ring (or a ring took its place). */
    static void stop() {
        HANDLER.removeCallbacks(TICK);
        if (ring != null) ring.setAlpha(255);
        ring = null;
    }

    /** A tile lost the focus: its ring stops, if it was the one breathing. */
    static void stopIf(Drawable leaving) {
        if (leaving != null && leaving == ring) stop();
    }

    /** Home went behind another app: no frames for a screen nobody sees. */
    static void pause() {
        HANDLER.removeCallbacks(TICK);
    }

    static void resume() {
        HANDLER.removeCallbacks(TICK);
        if (ring != null) HANDLER.post(TICK);
    }
}
