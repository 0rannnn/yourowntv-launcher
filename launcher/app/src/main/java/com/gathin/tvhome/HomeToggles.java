package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Switches the quick panel flips for Home, kept in the launcher's own storage so they outlive theme pushes:
 * whether the centred clock stands behind the wallpaper's foreground, and whether the navigation sound plays.
 */
final class HomeToggles {
    private static final String PREFS = "home_toggles";
    private static final String CLOCK_BEHIND = "clock_behind";
    private static final String NAV_MUTED = "nav_muted";
    private static final String GLASS = "glass";
    private static final String THEME = "theme";

    private HomeToggles() {
    }

    /** The clock behind the landscape (the cut-out drawn over it); on until switched off. */
    static boolean clockBehind(Context ctx) {
        return prefs(ctx).getBoolean(CLOCK_BEHIND, true);
    }

    static void setClockBehind(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(CLOCK_BEHIND, on).apply();
    }

    static boolean navMuted(Context ctx) {
        return prefs(ctx).getBoolean(NAV_MUTED, false);
    }

    static void setNavMuted(Context ctx, boolean muted) {
        prefs(ctx).edit().putBoolean(NAV_MUTED, muted).apply();
    }

    /** How see-through the glass is, 0 (frosted, nearly solid) to 100 (clear); the panel's slider. */
    static int glass(Context ctx) {
        return Math.max(0, Math.min(100, prefs(ctx).getInt(GLASS, 50)));
    }

    static void setGlass(Context ctx, int level) {
        prefs(ctx).edit().putInt(GLASS, Math.max(0, Math.min(100, level))).apply();
    }

    /** The theme picked in the quick panel (a folder name under themes/), or "" for the one the tools pushed. */
    static String theme(Context ctx) {
        return prefs(ctx).getString(THEME, "");
    }

    /** commit, not apply: Home restarts right after and must read the new choice. */
    static void setTheme(Context ctx, String name) {
        prefs(ctx).edit().putString(THEME, name).commit();
    }

    /**
     * A glass surface's opacity for the slider's level: solid at 0, a little over half at 100, so even the clearest
     * glass keeps its text readable over what is behind it.
     */
    static int surfaceAlpha(int level) {
        return Math.round(255 - level * 1.1f);
    }

    /** Between {@code solid} (level 0) and {@code clear} (level 100). */
    static int mix(int solid, int clear, int level) {
        return Math.round(solid + (clear - solid) * level / 100f);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
