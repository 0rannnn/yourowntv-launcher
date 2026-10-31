package com.gathin.tvhome;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The theme's ds_home: Home laid out like a handheld's two-screen console menu. A white strip of coloured tab buttons
 * along the top (the App library, search, the header's own buttons, the quick panel) with the time at its end, a grid of
 * framed square apps filling the screen (as many across as the quick panel's icon-size slider says), a pulsing ring
 * round the focused one, and a white bar along the bottom naming what is focused, with the OK hint.
 *
 * Tuned from config.json (the wallpaper editor has a page for it): ds_columns (the default icon size, until the panel's
 * slider picks one), ds_accent (the ring), and ds_show_buttons / _search / _clock / _bottom (on unless false).
 */
final class DsHome {
    /** Tiles across for each step of the panel's icon-size slider, smallest icons first. */
    static final int[] SIZES = {10, 9, 8, 7, 6, 5, 4};
    /** The focused tile's zoom: a small lift, the ring does the work. */
    static final float ZOOM = 1.08f;
    /** Tiles across the grid. Set by load(). */
    static int columns = 6;
    /** The ring's colour (ds_accent). Set by load(). */
    static int accent = 0xFFFF2E9A;

    private static final int[] TAB_COLORS = {0xFF5A7FA8, 0xFFE3B800, 0xFFF07A1E, 0xFF1DB3A0, 0xFF2A8CDB, 0xFF3BAA35,
            0xFFE5383B, 0xFF8E44AD};
    private static ObjectAnimator pulse;

    private final Activity activity;
    private final float density;
    private final boolean hebrew;
    private final JSONObject cfg;
    private final Runnable focusDock;
    private final List<View> bar = new ArrayList<>();
    private final List<String> barNames = new ArrayList<>();
    private TextView focusName;
    private int lastBar;

    static boolean on(Context ctx) {
        return HeaderExtras.themeFlag(ctx, "ds_home");
    }

    /** The grid's size (the panel's slider, else the theme's ds_columns) and the ring's colour, before the grid is built. */
    static void load(Context ctx) {
        JSONObject json = HeaderExtras.readConfig(ctx);
        int step = HomeToggles.dsSize(ctx);
        int fallback = json == null ? 6 : json.optInt("ds_columns", 6);
        columns = step >= 0 ? SIZES[Math.min(step, SIZES.length - 1)] : Math.max(4, Math.min(10, fallback));
        accent = color(json, "ds_accent", 0xFFFF2E9A);
    }

    /** The slider's step for a number of tiles across: the nearest size. */
    static int stepFor(int across) {
        for (int i = 0; i < SIZES.length; i++) if (SIZES[i] <= across) return i;
        return SIZES.length - 1;
    }

    private static int color(JSONObject json, String key, int fallback) {
        try {
            String value = json == null ? "" : json.optString(key, "");
            return value.isEmpty() ? fallback : 0xFF000000 | Color.parseColor(value);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** A resting tile's white frame, as the console draws round every icon. */
    static Drawable frame(Context ctx, int tileWidth) {
        float d = ctx.getResources().getDisplayMetrics().density;
        GradientDrawable edge = new GradientDrawable();
        edge.setCornerRadius(AppAdapter.cornerRadius(tileWidth, d));
        edge.setStroke(Math.round(3 * d), 0xF2FFFFFF);
        return edge;
    }

    /** The focused tile: the accent ring outside the white frame. */
    static Drawable ring(Context ctx, int tileWidth) {
        float d = ctx.getResources().getDisplayMetrics().density;
        float radius = AppAdapter.cornerRadius(tileWidth, d);
        GradientDrawable outer = new GradientDrawable();
        outer.setCornerRadius(radius);
        outer.setStroke(Math.round(5 * d), accent);
        GradientDrawable inner = new GradientDrawable();
        inner.setCornerRadius(Math.max(0, radius - 5 * d));
        inner.setStroke(Math.round(3 * d), 0xFFFFFFFF);
        LayerDrawable both = new LayerDrawable(new Drawable[]{outer, inner});
        int inset = Math.round(5 * d);
        both.setLayerInset(1, inset, inset, inset, inset);
        return both;
    }

    /** Breathes the ring's strength while its tile keeps the focus; one ring pulses at a time. */
    static void pulse(Drawable ring) {
        if (pulse != null) pulse.cancel();
        pulse = ObjectAnimator.ofInt(ring, "alpha", 255, 120);
        pulse.setDuration(700);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.start();
    }

    /** A ds_show_* setting: on unless the theme turns it off. */
    private boolean shows(String part) {
        return cfg == null || cfg.optBoolean("ds_show_" + part, true);
    }

    DsHome(Activity activity, FrameLayout root, View grid, View hero, List<View> cornerButtons, Runnable focusDock,
           Runnable openLibrary, Runnable openPanel) {
        this.activity = activity;
        this.focusDock = focusDock;
        density = activity.getResources().getDisplayMetrics().density;
        hebrew = HebrewFont.hebrew(activity);
        cfg = HeaderExtras.readConfig(activity);

        activity.findViewById(R.id.leftBlock).setVisibility(View.GONE);
        activity.findViewById(R.id.topButtons).setVisibility(View.GONE);
        hero.setVisibility(View.GONE);   // the bottom bar names the focused app
        // Room under the grid for the bottom bar.
        grid.setPadding(grid.getPaddingLeft(), grid.getPaddingTop(), grid.getPaddingRight(), dp(shows("bottom") ? 84 : 24));
        // Rows scroll out of sight at the strip and the bar, not underneath them.
        ((android.view.ViewGroup) grid).setClipToPadding(true);
        root.addView(topBar(cornerButtons, openLibrary, openPanel), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        if (shows("bottom")) {
            root.addView(bottomBar(), new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        }
    }

    /** A tile took the focus: the bottom bar names it. */
    void focused(String label) {
        if (focusName != null) focusName.setText(label);
    }

    /** Up from the grid's first row: the strip's button used last. */
    boolean focusBar() {
        if (bar.isEmpty()) return false;
        return bar.get(Math.max(0, Math.min(lastBar, bar.size() - 1))).requestFocus();
    }

    // ---- the top strip -----------------------------------------------------------------------------------------

    private View topBar(List<View> cornerButtons, Runnable openLibrary, Runnable openPanel) {
        FrameLayout top = new FrameLayout(activity);
        top.setPadding(dp(28), dp(16), dp(28), 0);
        top.setClipChildren(false);
        top.setClipToPadding(false);

        LinearLayout strip = new LinearLayout(activity);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setClipChildren(false);
        strip.setClipToPadding(false);
        GradientDrawable sheet = new GradientDrawable();
        sheet.setCornerRadius(dp(18));
        sheet.setColor(0xEBFFFFFF);
        strip.setBackground(sheet);
        strip.setElevation(dp(3));
        strip.setPadding(dp(10), dp(8), dp(10), dp(8));

        List<View> items = new ArrayList<>();
        List<String> names = new ArrayList<>();
        ImageView library = tab(R.drawable.ic_apps, 0);
        library.setOnClickListener(v -> openLibrary.run());
        items.add(library);
        names.add(activity.getString(R.string.app_library));
        if (shows("search")) {
            ImageView search = tab(R.drawable.ic_search, 1);
            search.setOnClickListener(v -> search());
            items.add(search);
            names.add(activity.getString(R.string.ps5_search));
        }
        if (shows("buttons")) {
            for (View b : cornerButtons) {
                TextView button = (TextView) b;
                CharSequence label = button.getContentDescription();
                items.add(adopt(button, TAB_COLORS[(items.size()) % TAB_COLORS.length]));
                names.add(label == null ? "" : label.toString());
            }
        }
        ImageView panel = tab(R.drawable.ic_tune, items.size());
        panel.setOnClickListener(v -> openPanel.run());
        items.add(panel);
        names.add(activity.getString(R.string.switch_panel));
        if (hebrew) {
            java.util.Collections.reverse(items);
            java.util.Collections.reverse(names);
        }
        for (int i = 0; i < items.size(); i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(50), dp(50));
            if (i > 0) lp.leftMargin = dp(10);
            strip.addView(items.get(i), lp);
        }
        bar.addAll(items);
        barNames.addAll(names);
        lastBar = hebrew ? items.size() - 1 : 0;
        top.addView(strip, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, (hebrew ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL));

        if (shows("clock")) {
            TextClock clock = new TextClock(activity);
            clock.setFormat12Hour("h:mm a");
            clock.setFormat24Hour("HH:mm");
            clock.setTextColor(0xFF3A3A3A);
            clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
            clock.setTypeface(UiFont.get(activity, R.string.font_medium));
            clock.setGravity(Gravity.CENTER);
            GradientDrawable chip = new GradientDrawable();
            chip.setCornerRadius(dp(18));
            chip.setColor(0xEBFFFFFF);
            clock.setBackground(chip);
            clock.setElevation(dp(3));
            clock.setPadding(dp(16), 0, dp(16), 0);
            top.addView(clock, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(66),
                    (hebrew ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL));
        }

        for (int i = 0; i < bar.size(); i++) {
            View v = bar.get(i);
            final int index = i;
            View.OnFocusChangeListener style = v.getOnFocusChangeListener();
            v.setOnFocusChangeListener((view, focused) -> {
                if (style != null) style.onFocusChange(view, focused);
                if (focused) {
                    lastBar = index;
                    focused(barNames.get(index));
                }
            });
            v.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        if (index > 0) bar.get(index - 1).requestFocus();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        if (index + 1 < bar.size()) bar.get(index + 1).requestFocus();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        focusDock.run();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        return true;
                    default:
                        return false;
                }
            });
        }
        return top;
    }

    /** A tab button: its mark in the tab's colour on a pale rounded square; focused, white with the accent ring. */
    private ImageView tab(int drawable, int index) {
        int tint = TAB_COLORS[index % TAB_COLORS.length];
        ImageView icon = new ImageView(activity);
        icon.setImageResource(drawable);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = dp(13);
        icon.setPadding(pad, pad, pad, pad);
        icon.setFocusable(true);
        icon.setClickable(true);
        icon.setImageTintList(ColorStateList.valueOf(tint));
        icon.setBackground(tabBackground());
        icon.setOnFocusChangeListener(tabFocus());
        return icon;
    }

    private View adopt(TextView button, int tint) {
        button.setBackground(tabBackground());
        button.setText(null);
        button.setGravity(Gravity.CENTER);
        int pad = Math.max(0, (dp(50) - dp(22)) / 2);
        button.setPadding(pad, 0, pad, 0);
        button.setCompoundDrawablePadding(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setCompoundDrawableTintList(ColorStateList.valueOf(tint));
        button.setOnFocusChangeListener(tabFocus());
        return button;
    }

    private GradientDrawable tabBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(0xFFF1F3F6);
        return bg;
    }

    private View.OnFocusChangeListener tabFocus() {
        return (v, focused) -> {
            GradientDrawable bg = (GradientDrawable) v.getBackground();
            bg.setColor(focused ? 0xFFFFFFFF : 0xFFF1F3F6);
            bg.setStroke(focused ? dp(4) : 0, accent);
            v.animate().scaleX(focused ? 1.12f : 1f).scaleY(focused ? 1.12f : 1f).setDuration(150).start();
        };
    }

    // ---- the bottom bar ------------------------------------------------------------------------------------------

    private View bottomBar() {
        FrameLayout wrap = new FrameLayout(activity);
        wrap.setPadding(dp(28), 0, dp(28), dp(16));
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable sheet = new GradientDrawable();
        sheet.setCornerRadius(dp(18));
        sheet.setColor(0xEBFFFFFF);
        row.setBackground(sheet);
        row.setElevation(dp(3));
        row.setPadding(dp(22), 0, dp(22), 0);

        focusName = new TextView(activity);
        focusName.setTextColor(0xFF3A3A3A);
        focusName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
        focusName.setTypeface(UiFont.get(activity, R.string.font_medium));
        focusName.setSingleLine(true);
        focusName.setGravity(hebrew ? Gravity.RIGHT : Gravity.LEFT);

        LinearLayout ok = new LinearLayout(activity);
        ok.setOrientation(LinearLayout.HORIZONTAL);
        ok.setGravity(Gravity.CENTER_VERTICAL);
        TextView key = new TextView(activity);
        key.setText("OK");
        key.setTextColor(0xFFFFFFFF);
        key.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        key.setTypeface(UiFont.get(activity, R.string.font_medium));
        key.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0xFF3A3A3A);
        key.setBackground(circle);
        TextView open = new TextView(activity);
        open.setText(R.string.switch_open);
        open.setTextColor(0xFF3A3A3A);
        open.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        open.setTypeface(UiFont.get(activity, R.string.font_regular));
        LinearLayout.LayoutParams keyLp = new LinearLayout.LayoutParams(dp(28), dp(28));
        LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (hebrew) {
            openLp.rightMargin = dp(8);
            ok.addView(open, openLp);
            ok.addView(key, keyLp);
            row.addView(ok);
            row.addView(focusName, nameLp);
        } else {
            openLp.leftMargin = dp(8);
            ok.addView(key, keyLp);
            ok.addView(open, openLp);
            row.addView(focusName, nameLp);
            row.addView(ok);
        }
        wrap.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        return wrap;
    }

    /** The system's search (the assistant on Google TV), else the older global search. */
    private void search() {
        for (Intent intent : new Intent[]{new Intent(Intent.ACTION_ASSIST), new Intent("android.search.action.GLOBAL_SEARCH")}) {
            try {
                activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
    }

    private int dp(float value) {
        return Math.round(value * density);
    }
}
