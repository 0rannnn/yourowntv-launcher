package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.io.File;

/**
 * Home's own navigation sound: {@code nav_sound.wav} next to the theme's config.json, played when the remote moves
 * the focus. Android plays its tick from the system, not from the app, so while Home is in front the system's sound
 * effects are switched off (WRITE_SECURE_SETTINGS) and put back as they were when Home leaves the screen; other apps
 * keep the system tick. Without the file nothing changes.
 */
final class NavSound {
    private static final String TAG = "NavSound";
    static final String FILE = "nav_sound.wav";
    /** A focus change this soon after a D-pad press is the user's move; others (Home coming up) stay silent. */
    private static final long KEY_WINDOW_MS = 350;
    /** Holding a direction: no more than one sound per this many milliseconds. */
    private static final long MIN_GAP_MS = 45;

    private final Context context;
    private final SoundPool pool;
    private final int sound;
    private volatile boolean loaded;
    private long lastKey;
    private long lastPlayed;
    private int savedSystemSetting = -1;

    private NavSound(Context context, File file) {
        this.context = context.getApplicationContext();
        pool = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        pool.setOnLoadCompleteListener((p, id, status) -> loaded = status == 0);
        sound = pool.load(file.getPath(), 1);
    }

    /** The sound from the theme, or null when there is none. */
    static NavSound load(Context context) {
        File dir = Config.dir(context);
        File file = dir != null ? new File(dir, FILE) : null;
        if (file == null || !file.isFile() || file.length() == 0) return null;
        // A new sound (the editor or a theme push replaced the file) is meant to be heard: it lifts the panel's mute.
        String stamp = file.getPath() + ":" + file.length() + ":" + file.lastModified();
        android.content.SharedPreferences prefs = context.getSharedPreferences(TAG, Context.MODE_PRIVATE);
        String seen = prefs.getString("sound_stamp", null);
        if (!stamp.equals(seen)) {
            if (seen != null) HomeToggles.setNavMuted(context, false);
            prefs.edit().putString("sound_stamp", stamp).apply();
        }
        return new NavSound(context, file);
    }

    /** A D-pad key went down: the next focus change is the user's. */
    void keyPressed() {
        lastKey = SystemClock.uptimeMillis();
    }

    /** Focus moved: play, if a D-pad press caused it. */
    void focusMoved() {
        long now = SystemClock.uptimeMillis();
        if (!loaded || now - lastKey > KEY_WINDOW_MS || now - lastPlayed < MIN_GAP_MS) return;
        // Muted from the quick panel: silent, and the system tick stays off as well.
        if (HomeToggles.navMuted(context)) return;
        lastPlayed = now;
        pool.play(sound, 1f, 1f, 1, 0, 1f);
    }

    /** Home in front: the system tick off, so the two sounds do not play on top of each other. */
    void muteSystemTick() {
        ContentResolver resolver = context.getContentResolver();
        android.content.SharedPreferences prefs = context.getSharedPreferences(TAG, Context.MODE_PRIVATE);
        try {
            int current = Settings.System.getInt(resolver, Settings.System.SOUND_EFFECTS_ENABLED, 1);
            if (current == 0) {
                // Off already: by us, when Home was killed before it could put the tick back, or by the owner.
                if (prefs.getBoolean("muted", false)) savedSystemSetting = 1;
                return;
            }
            Settings.System.putInt(resolver, Settings.System.SOUND_EFFECTS_ENABLED, 0);
            prefs.edit().putBoolean("muted", true).apply();
            savedSystemSetting = current;
        } catch (RuntimeException e) {
            Log.w(TAG, "could not switch the system tick off: " + e.getMessage());
        }
    }

    /** Home leaves the screen: the system tick back as it was. */
    void restoreSystemTick() {
        if (savedSystemSetting < 0) return;
        try {
            Settings.System.putInt(context.getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED, savedSystemSetting);
            context.getSharedPreferences(TAG, Context.MODE_PRIVATE).edit().putBoolean("muted", false).apply();
        } catch (RuntimeException e) {
            Log.w(TAG, "could not restore the system tick: " + e.getMessage());
        }
        savedSystemSetting = -1;
    }

    void release() {
        restoreSystemTick();
        pool.release();
    }
}
