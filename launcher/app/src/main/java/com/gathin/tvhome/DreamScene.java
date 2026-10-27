package com.gathin.tvhome;

import android.content.Context;
import android.view.View;

import java.util.function.Consumer;

/**
 * What the screensaver shows, chosen by the theme: the Reel scene (the photo with the glass clock behind its
 * cut-out) for a center_clock theme, the classic drifting clock (ClockScene) for any other.
 * Shared by ClockDream (the system screensaver) and ClockActivity (Home left idle).
 */
interface DreamScene {
    void start();

    void stop();

    /** Builds the theme's scene and hands its view to show (setContentView). */
    static DreamScene create(Context ctx, Consumer<View> show) {
        if (HeaderExtras.themeFlag(ctx, "center_clock")) {
            ReelScene reel = new ReelScene(ctx);
            show.accept(reel.view());
            return reel;
        }
        View root = View.inflate(ctx, R.layout.dream_clock, null);
        show.accept(root);
        ClockScene clock = new ClockScene(root);
        clock.useHomeWallpaper(ctx);
        return clock;
    }
}
