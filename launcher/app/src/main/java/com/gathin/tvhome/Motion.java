package com.gathin.tvhome;

import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/**
 * The launcher's shared motion: the easing curves every animation uses, so focus, panels and scrolling feel like one
 * system (and no curve is allocated on each key press), and reduced motion (config.xml reduced_motion): the one spring
 * and the one time limit every animation uses then.
 */
final class Motion {

    /** The longest any reduced-motion animation runs, in milliseconds. */
    static final int REDUCED_MS = 90;
    /** Critically damped (no bounce) and stiff enough that any move, even across the screen, settles in 5 frames. */
    static final float REDUCED_STIFFNESS = 15000f;
    /** The spring stops once it is within this many pixels of its target. */
    static final float REDUCED_MIN_CHANGE = 2f;

    /** Things arriving or growing (focus in, a panel opening): fast start, long soft landing. */
    static final Interpolator ENTER = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    /** Things settling back or moving within the screen (focus out, a row scrolling): an even ease out. */
    static final Interpolator SETTLE = new PathInterpolator(0.2f, 0f, 0f, 1f);
    /** Things leaving (a panel closing): gathers speed, gone before it drags. */
    static final Interpolator EXIT = new PathInterpolator(0.3f, 0f, 0.8f, 0.15f);

    /**
     * How long a row's scroll to the focused tile takes: 110 to 200 ms, longer for longer moves; 80 ms when the
     * presses come quickly (a held arrow key), so the row keeps up with the remote instead of trailing behind it.
     */
    static int scrollMs(int distancePx, float density) {
        long now = android.os.SystemClock.uptimeMillis();
        boolean quick = now - lastScroll < 260;
        lastScroll = now;
        if (quick) return 80;
        return Math.round(Math.max(110f, Math.min(200f, 110f + Math.abs(distancePx) / density * 0.18f)));
    }

    private static long lastScroll;

    private Motion() {
    }

    static void reduce(SpringAnimation spring) {
        spring.setSpring(new SpringForce().setStiffness(REDUCED_STIFFNESS).setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY));
        spring.setMinimumVisibleChange(REDUCED_MIN_CHANGE);
    }
}
