package com.gathin.tvhome;

import android.content.Context;
import android.view.View;

import java.util.function.Consumer;

/**
 * What the screensaver shows, chosen by the theme: the console idle screens for ps5_home (Ps5Scene) and switch_home
 * (SwitchScene) and ds_home (DsScene), the Reel scene (the photo with the glass clock behind its cut-out) for a center_clock theme, the
 * classic drifting clock (ClockScene) for any other.
 * Shared by ClockDream (the system screensaver) and ClockActivity (Home left idle).
 */
interface DreamScene {
    void start();

    void stop();

    /** Builds the theme's scene and hands its view to show (setContentView). */
    static DreamScene create(Context ctx, Consumer<View> show) {
        if (Ps5Home.on(ctx)) {
            Ps5Scene ps5 = new Ps5Scene(ctx);
            show.accept(ps5.view());
            return ps5;
        }
        if (DsHome.on(ctx)) {
            DsScene ds = new DsScene(ctx);
            show.accept(ds.view());
            return ds;
        }
        if (SwitchHome.on(ctx)) {
            SwitchScene nx = new SwitchScene(ctx);
            show.accept(nx.view());
            return nx;
        }
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
