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
        if (!hebrew(ctx)) return null;
        File dir = Config.dir(ctx);
        File file = dir != null ? new File(dir, FILE) : null;
        if (file == null || !file.isFile() || file.length() == 0) return null;
        // A new theme push replaces the file: start over with it.
        if (!file.equals(loadedFrom) || file.lastModified() != loadedStamp) {
            byWeight.clear();
            loadedFrom = file;
            loadedStamp = file.lastModified();
        }
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
        if (view == null || at(view.getContext(), 400) == null) return;
        walk(view);
    }

    private static void walk(View view) {
        if (KEEP.equals(view.getTag())) return;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            Typeface now = text.getTypeface();
            int weight = now == null ? 400 : android.os.Build.VERSION.SDK_INT >= 28 ? now.getWeight() : now.isBold() ? 700 : 400;
            Typeface face = at(view.getContext(), weight);
            if (face != null) text.setTypeface(face);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) walk(group.getChildAt(i));
        }
    }
}
