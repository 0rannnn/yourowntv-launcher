package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * "New version!" on our setup app's tile (com.gathin.kit, "התקנת מסך הבית"), and only there: from the moment it is
 * updated (every commit publishes one) until it is opened from Home. Earlier it marked every app; Oran wants it on
 * ours alone. Its version number (the pack's, from build.gradle) shows on the tile too.
 *
 * Originally: a dot on an app's tile from the moment the app is updated (or newly installed) until
 * it is opened from Home. Android keeps each package's last update time; this remembers the one seen when the app was
 * last opened (or when the dots were first switched on) and compares. No permission, nothing read from the apps.
 */
final class AppUpdates {
    private static final String PREFS = "app_updates";
    /** Set once: the first time, every app's current version counts as seen, so the dots start with none. */
    private static final String BASELINE = "\u0000baseline";

    /** The one app the label is for: our setup app. */
    static final String KIT = "com.gathin.kit";

    private AppUpdates() {
    }

    /** Our setup app's version, the pack's number ("75"), or null when it is not installed or has no number. */
    static String kitVersion(Context ctx) {
        try {
            String name = ctx.getPackageManager().getPackageInfo(KIT, 0).versionName;
            return name != null && name.matches("\\d+") ? name : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** The packages among these that were updated or installed since they were last opened from Home. */
    static Set<String> updated(Context ctx, Collection<String> pkgs) {
        SharedPreferences prefs = prefs(ctx);
        boolean first = !prefs.getBoolean(BASELINE, false);
        SharedPreferences.Editor edit = first ? prefs.edit().putBoolean(BASELINE, true) : null;
        Set<String> out = new HashSet<>();
        for (String pkg : pkgs) {
            if (pkg == null) continue;
            long time = updateTime(ctx, pkg);
            if (time <= 0) continue;
            if (first) {
                edit.putLong(pkg, time);
            } else if (prefs.getLong(pkg, 0L) != time) {
                out.add(pkg);
            }
        }
        if (edit != null) edit.apply();
        return out;
    }

    /** The app was just opened from Home: its version now counts as seen, and its dot goes away. */
    static void opened(Context ctx, String pkg) {
        long time = updateTime(ctx, pkg);
        if (time > 0) prefs(ctx).edit().putLong(pkg, time).apply();
    }

    private static long updateTime(Context ctx, String pkg) {
        try {
            return ctx.getPackageManager().getPackageInfo(pkg, 0).lastUpdateTime;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
