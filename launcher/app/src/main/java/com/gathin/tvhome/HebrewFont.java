package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Typeface;
import android.util.Log;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.io.File;

/**
 * The theme's {@code ui_font_he.ttf} next to config.json: while the launcher is in Hebrew, Home's text and the quick
 * panel's are set in it, each at the weight it had (a variable font follows it exactly). The big clocks keep their own
 * typeface: a view tagged {@link #KEEP} is left alone. Without the file, or in another language, nothing changes.
 */
final class HebrewFont {
    private static final String TAG = "UiFont";
    static final String FILE = "ui_font_he.ttf";
    /** View tag for text that keeps its typeface (the clocks). */
    static final String KEEP = "keep_font";

    private static File loadedFrom;
    private static long loadedStamp;
    private static final SparseArray<Typeface> byWeight = new SparseArray<>();

    private HebrewFont() {
    }

    static boolean hebrew(Context ctx) {
        String lang = ctx.getResources().getConfiguration().getLocales().get(0).getLanguage();
        return "iw".equals(lang) || "he".equals(lang);
    }

    /** The font at this weight, or null when there is no font or the launcher is not in Hebrew. */
    static Typeface at(Context ctx, int weight) {
        File file = current(ctx);
        return file == null ? null : face(file, weight);
    }

    /**
     * The font file in use, its cache started over when a theme push replaced it; null when there is none or the
     * launcher is not in Hebrew. Several file checks on shared storage: once per apply, not once per text.
     */
    private static File current(Context ctx) {
        if (!hebrew(ctx)) return null;
        File dir = Config.dir(ctx);
        File file = dir != null ? new File(dir, FILE) : null;
        if (file == null || !file.isFile() || file.length() == 0) return null;
        long stamp = file.lastModified();
        if (!file.equals(loadedFrom) || stamp != loadedStamp) {
            byWeight.clear();
            loadedFrom = file;
            loadedStamp = stamp;
        }
        return file;
    }

    private static Typeface face(File file, int weight) {
        int w = Math.max(100, Math.min(900, Math.round(weight / 100f) * 100));
        Typeface face = byWeight.get(w);
        if (face == null) {
            try {
                face = new Typeface.Builder(file).setFontVariationSettings("'wght' " + w).setWeight(w).build();
            } catch (RuntimeException e) {
                Log.w(TAG, "font unreadable: " + e.getMessage());
                return null;
            }
            if (face != null) byWeight.put(w, face);
        }
        return face;
    }

    /** Sets every TextView under {@code view} in the font, at its own weight. */
    static void apply(View view) {
        File file = view == null ? null : current(view.getContext());
        if (file == null || face(file, 400) == null) return;
        walk(view, file);
    }

    private static void walk(View view, File file) {
        if (KEEP.equals(view.getTag())) return;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            Typeface now = text.getTypeface();
            int weight = now == null ? 400 : android.os.Build.VERSION.SDK_INT >= 28 ? now.getWeight() : now.isBold() ? 700 : 400;
            Typeface face = face(file, weight);
            if (face != null) text.setTypeface(face);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) walk(group.getChildAt(i), file);
        }
    }
}
