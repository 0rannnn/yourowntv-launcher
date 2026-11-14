package com.gathin.tvhome;

import android.content.Context;

import org.json.JSONObject;

/**
 * Where the apps sit and how big they are, from the theme's config.json (the phone editor and the computer editor set
 * these; a theme without them looks as it always did):
 * <ul>
 * <li>{@code apps_size}: 50 to 100 (%), the tiles' size. A smaller grid is a narrower one too, so the row or rows
 * keep their spacing and can be moved across the screen. Fewer {@code columns} make bigger tiles.</li>
 * <li>{@code apps_x}: 0 (left) to 100 (right), where that narrower grid sits; 50, the middle, by default.</li>
 * <li>{@code apps_y}: -40 to 60 (% of the screen's height), the grid higher (minus) or lower than the theme puts it.</li>
 * </ul>
 * The clock's own keys ({@code clock_size}, {@code clock_x}, {@code clock_y}) are read by HeaderExtras.
 */
final class AppsLayout {
    final float size;
    final float x;
    final float y;

    AppsLayout() {
        this(1f, 0.5f, 0f);
    }

    AppsLayout(float size, float x, float y) {
        this.size = size;
        this.x = x;
        this.y = y;
    }

    static AppsLayout load(Context ctx) {
        JSONObject json = HeaderExtras.readConfig(ctx);
        if (json == null) return new AppsLayout();
        return new AppsLayout(percent(json, "apps_size", 100, 50, 100) / 100f, percent(json, "apps_x", 50, 0, 100) / 100f,
                percent(json, "apps_y", 0, -40, 60) / 100f);
    }

    /** A whole number of the config, clamped; the default when it is missing or not a number. */
    static int percent(JSONObject json, String key, int fallback, int min, int max) {
        Object v = json.opt(key);
        double d;
        if (v instanceof Number) {
            d = ((Number) v).doubleValue();
        } else if (v != null) {
            try {
                d = Double.parseDouble(v.toString());
            } catch (NumberFormatException e) {
                return fallback;
            }
        } else {
            return fallback;
        }
        if (Double.isNaN(d)) return fallback;
        return Math.max(min, Math.min(max, (int) Math.round(d)));
    }

    /** The grid's left and right padding for a grid this wide: the room apps_size leaves, split by apps_x. */
    int[] sides(int width, int edge) {
        if (size >= 1f || width <= 0) return new int[]{edge, edge};
        int inner = Math.round((width - 2 * edge) * size);
        int free = width - 2 * edge - inner;
        int left = edge + Math.round(free * x);
        return new int[]{left, width - inner - left};
    }

    /** The grid's top padding moved by apps_y, kept on screen with at least one row showing. */
    int top(int want, int height, int itemHeight) {
        if (y == 0f) return want;
        return Math.max(0, Math.min(height - itemHeight, want + Math.round(y * height)));
    }
}
