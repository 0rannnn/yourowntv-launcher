package com.gathin.tvhome;

import android.content.Context;
import android.service.dreams.DreamService;

/**
 * Screensaver, in the theme's style (DreamScene): the Reel photo with the glass clock behind its cut-out, or the
 * launcher's thin clock and date drifting over a slow ambient glow or Home's picture (ClockScene).
 * No network, no video: cheap enough for a 1 GB TV.
 */
public class ClockDream extends DreamService {

    private DreamScene scene;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // the launcher's language, as on Home
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(false);
        scene = DreamScene.create(this, this::setContentView);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        scene.start();
    }

    @Override
    public void onDreamingStopped() {
        scene.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (scene != null) scene.stop();
        super.onDetachedFromWindow();
    }
}
