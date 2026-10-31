package com.gathin.tvhome;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The theme's switch_home: Home laid out like a handheld console's. A light page (switch_dark for the black one), the
 * profile badge top left and the time top right, a row of large square apps across the middle with the focused one's
 * name above it and a pulsing ring round it, and a white pill of round buttons below (the App library, search, the
 * header's own buttons, the quick panel) whose focused button names itself underneath.
 *
 * Tuned from config.json (the wallpaper editor has a page for it): switch_columns (tile size), switch_dark,
 * switch_accent (the ring), switch_title_color, and switch_show_avatar / _clock / _hint / _title (on unless false).
 */
final class SwitchHome {
    /** Tiles across the row (switch_columns): fewer, larger tiles. Set by load(). */
    static int columns = 5;
    /** The focused tile's zoom: a nudge, the ring does the work. */
    static final float ZOOM = 1.04f;
    /** The ring's colour (switch_accent). Set by load(). */
    static int accent = 0xFF19B8E6;

    private static ObjectAnimator pulse;

    private final Activity activity;
    private final float density;
    private final boolean hebrew, dark;
    private final JSONObject cfg;
    private final int ink, pill;
    private final Runnable focusDock;
    private final LinearLayout hero;
    private final List<View> bar = new ArrayList<>();
    private final List<String> barNames = new ArrayList<>();
    private TextView barLabel;
    private int lastBar;
    private float heroY;

    static boolean on(Context ctx) {
        return HeaderExtras.themeFlag(ctx, "switch_home");
    }

    /** The row's size and the ring's colour, read before the grid is built. */
    static void load(Context ctx) {
        JSONObject json = HeaderExtras.readConfig(ctx);
        columns = json == null ? 5 : Math.max(3, Math.min(8, json.optInt("switch_columns", 5)));
        accent = color(json, "switch_accent", 0xFF19B8E6);
    }

    private static int color(JSONObject json, String key, int fallback) {
        try {
            String value = json == null ? "" : json.optString(key, "");
            return value.isEmpty() ? fallback : 0xFF000000 | Color.parseColor(value);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** The focused tile's ring: the accent outside, a thin white line inside it, as on the console. */
    static Drawable ring(Context ctx, int tileWidth) {
        float d = ctx.getResources().getDisplayMetrics().density;
        float radius = AppAdapter.cornerRadius(tileWidth, d);
        GradientDrawable outer = new GradientDrawable();
        outer.setCornerRadius(radius);
        outer.setStroke(Math.round(5 * d), accent);
        GradientDrawable inner = new GradientDrawable();
        inner.setCornerRadius(Math.max(0, radius - 5 * d));
        inner.setStroke(Math.round(2 * d), 0xFFFFFFFF);
        LayerDrawable both = new LayerDrawable(new Drawable[]{outer, inner});
        int inset = Math.round(5 * d);
        both.setLayerInset(1, inset, inset, inset, inset);
        return both;
    }

    /** Breathes the ring's strength while its tile keeps the focus; one ring pulses at a time. */
    static void pulse(Drawable ring) {
        if (pulse != null) pulse.cancel();
        pulse = ObjectAnimator.ofInt(ring, "alpha", 255, 140);
        pulse.setDuration(900);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.start();
    }

    /** A switch_show_* setting: on unless the theme turns it off. */
    private boolean shows(String part) {
        return cfg == null || cfg.optBoolean("switch_show_" + part, true);
    }

    SwitchHome(Activity activity, FrameLayout root, LinearLayout hero, TextView heroName, TextView heroMeta,
               List<View> cornerButtons, Runnable focusDock, Runnable openLibrary, Runnable openPanel) {
        this.activity = activity;
        this.focusDock = focusDock;
        this.hero = hero;
        density = activity.getResources().getDisplayMetrics().density;
        hebrew = HebrewFont.hebrew(activity);
        cfg = HeaderExtras.readConfig(activity);
        dark = cfg != null && cfg.optBoolean("switch_dark", false);
        ink = dark ? 0xFFFFFFFF : 0xFF2B2B2B;
        pill = dark ? 0xFF3A3A3C : 0xFFFFFFFF;
        if (dark) root.setBackgroundColor(0xFF2D2D2D);

        activity.findViewById(R.id.leftBlock).setVisibility(View.GONE);
        activity.findViewById(R.id.topButtons).setVisibility(View.GONE);
        root.addView(topBar(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        root.addView(bottomBar(cornerButtons, openLibrary, openPanel), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // The focused app's name, small and blue, just above its tile.
        heroMeta.setVisibility(View.GONE);
        heroName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
        heroName.setTextColor(color(cfg, "switch_title_color", dark ? 0xFF4FC3F7 : 0xFF2F6FD6));
        heroName.setTypeface(UiFont.get(activity, R.string.font_medium));
        heroName.setLetterSpacing(0f);
        if (!shows("title")) hero.setVisibility(View.GONE);
        FrameLayout.LayoutParams heroLp = (FrameLayout.LayoutParams) hero.getLayoutParams();
        heroLp.gravity = Gravity.TOP | (hebrew ? Gravity.RIGHT : Gravity.LEFT);
        heroLp.setMarginStart(0);
        hero.setLayoutParams(heroLp);
        if (hebrew) {
            hero.setGravity(Gravity.RIGHT);
            heroName.setGravity(Gravity.RIGHT);
        }
    }

    /** The focused tile: the name moves over its corner (left, or right in Hebrew). */
    void focused(View card) {
        View root = (View) hero.getParent();
        int[] rootAt = new int[2], cardAt = new int[2];
        root.getLocationInWindow(rootAt);
        card.getLocationInWindow(cardAt);
        int left = cardAt[0] - rootAt[0];
        // The card may be mid-zoom: take its unscaled box.
        float grown = card.getWidth() * (card.getScaleX() - 1f) / 2f;
        left += Math.round(grown);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) hero.getLayoutParams();
        int want = hebrew ? root.getWidth() - left - card.getWidth() : left;
        if (hebrew ? lp.rightMargin != want : lp.leftMargin != want) {
            if (hebrew) lp.rightMargin = want;
            else lp.leftMargin = want;
            lp.setMarginStart(hebrew ? 0 : want);
            hero.setLayoutParams(lp);
        }
        float top = cardAt[1] - rootAt[1] + card.getHeight() * (card.getScaleY() - 1f) / 2f;
        heroY = top - dp(12) - (hero.getHeight() > 0 ? hero.getHeight() : dp(26));
    }

    float heroY() {
        return heroY;
    }

    /** Down from the row: the bar's button used last. */
    boolean focusBar() {
        if (bar.isEmpty()) return false;
        return bar.get(Math.max(0, Math.min(lastBar, bar.size() - 1))).requestFocus();
    }

    // ---- top: the badge and the time ----------------------------------------------------------------

    private View topBar() {
        FrameLayout top = new FrameLayout(activity);
        top.setPadding(dp(40), dp(22), dp(40), 0);
        View badge = shows("avatar") ? avatar() : null;
        if (badge != null) {
            top.addView(badge, new FrameLayout.LayoutParams(dp(46), dp(46),
                    (hebrew ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL));
        }
        if (shows("clock")) {
            LinearLayout status = new LinearLayout(activity);
            status.setOrientation(LinearLayout.HORIZONTAL);
            status.setGravity(Gravity.CENTER_VERTICAL);
            TextClock clock = new TextClock(activity);
            clock.setFormat12Hour("h:mm a");
            clock.setFormat24Hour("HH:mm");
            clock.setTextColor(ink);
            clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f);
            clock.setTypeface(UiFont.get(activity, R.string.font_medium));
            ImageView wifi = new ImageView(activity);
            wifi.setImageResource(R.drawable.ic_wifi);
            wifi.setImageTintList(ColorStateList.valueOf(ink));
            LinearLayout.LayoutParams wifiLp = new LinearLayout.LayoutParams(dp(22), dp(22));
            if (hebrew) {
                wifiLp.rightMargin = dp(14);
                status.addView(wifi, wifiLp);
                status.addView(clock);
            } else {
                wifiLp.leftMargin = dp(14);
                status.addView(clock);
                status.addView(wifi, wifiLp);
            }
            top.addView(status, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46),
                    (hebrew ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL));
        }
        return top;
    }

    /** avatar.jpg, or the greeting name's first letter, in a white ring like a console profile. */
    private View avatar() {
        String name = cfg == null ? "" : cfg.optString(hebrew ? "greeting_name_he" : "greeting_name", "");
        if (name.trim().isEmpty() && cfg != null) name = cfg.optString("greeting_name", "");
        File dir = Config.dir(activity);
        File photo = dir != null ? new File(dir, Ps5Home.AVATAR) : null;
        Bitmap face = photo != null && photo.isFile()
                ? WallpaperPlayer.decode(photo, dp(46), dp(46), Bitmap.Config.ARGB_8888, 0) : null;
        if (face == null && name.trim().isEmpty()) return null;
        View disc;
        if (face != null) {
            ImageView img = new ImageView(activity);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setImageBitmap(face);
            disc = img;
        } else {
            TextView letter = new TextView(activity);
            letter.setText(name.trim().substring(0, 1).toUpperCase(java.util.Locale.ROOT));
            letter.setTextColor(0xFFFFFFFF);
            letter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
            letter.setTypeface(UiFont.get(activity, R.string.font_medium));
            letter.setGravity(Gravity.CENTER);
            GradientDrawable fill = new GradientDrawable();
            fill.setShape(GradientDrawable.OVAL);
            fill.setColor(color(cfg, "switch_avatar_color", 0xFFE60012));
            letter.setBackground(fill);
            disc = letter;
        }
        disc.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        disc.setClipToOutline(true);
        FrameLayout ring = new FrameLayout(activity);
        GradientDrawable edge = new GradientDrawable();
        edge.setShape(GradientDrawable.OVAL);
        edge.setColor(0xFFFFFFFF);
        ring.setBackground(edge);
        int pad = dp(3);
        ring.setPadding(pad, pad, pad, pad);
        ring.addView(disc, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ring.setElevation(dp(2));
        ring.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return ring;
    }

    // ---- bottom: the pill of round buttons, the name under the focused one, and the corner hints -----------

    private View bottomBar(List<View> cornerButtons, Runnable openLibrary, Runnable openPanel) {
        FrameLayout layer = new FrameLayout(activity);
        layer.setClipChildren(false);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(40));
        shape.setColor(pill);
        row.setBackground(shape);
        row.setElevation(dp(3));
        row.setPadding(dp(14), dp(8), dp(14), dp(8));

        // Left to right in English; the Hebrew bar runs the other way.
        List<View> items = new ArrayList<>();
        List<String> names = new ArrayList<>();
        ImageView library = disc(R.drawable.ic_apps, 0xFFE60012, true);
        library.setOnClickListener(v -> openLibrary.run());
        items.add(library);
        names.add(activity.getString(R.string.app_library));
        ImageView search = disc(R.drawable.ic_search, 0xFFF28C00, false);
        search.setOnClickListener(v -> search());
        items.add(search);
        names.add(activity.getString(R.string.ps5_search));
        if (shows("buttons")) {
            int[] tints = {0xFF2EB84B, 0xFFE5383B, 0xFF3C5BD6, 0xFF1CB5C9, 0xFF8A8A8E, 0xFF8A8A8E, 0xFF8A8A8E};
            for (int i = 0; i < cornerButtons.size(); i++) {
                TextView button = (TextView) cornerButtons.get(i);
                CharSequence label = button.getContentDescription();
                items.add(adopt(button, tints[Math.min(i, tints.length - 1)]));
                names.add(label == null ? "" : label.toString());
            }
        }
        ImageView panel = disc(R.drawable.ic_tune, 0xFF8E44AD, false);
        panel.setOnClickListener(v -> openPanel.run());
        items.add(panel);
        names.add(activity.getString(R.string.switch_panel));
        if (hebrew) {
            java.util.Collections.reverse(items);
            java.util.Collections.reverse(names);
        }
        for (int i = 0; i < items.size(); i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(54), dp(54));
            if (i > 0) lp.leftMargin = dp(12);
            row.addView(items.get(i), lp);
        }
        bar.addAll(items);
        barNames.addAll(names);
        lastBar = hebrew ? items.size() - 1 : 0;

        FrameLayout.LayoutParams rowLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        rowLp.bottomMargin = dp(92);
        layer.addView(row, rowLp);

        barLabel = new TextView(activity);
        barLabel.setTextColor(color(cfg, "switch_title_color", dark ? 0xFF4FC3F7 : 0xFF2F6FD6));
        barLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        barLabel.setTypeface(UiFont.get(activity, R.string.font_regular));
        barLabel.setSingleLine(true);
        barLabel.setAlpha(0f);
        FrameLayout.LayoutParams labelLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT);
        labelLp.bottomMargin = dp(62);
        layer.addView(barLabel, labelLp);

        for (int i = 0; i < bar.size(); i++) {
            View v = bar.get(i);
            final int index = i;
            View.OnFocusChangeListener style = v.getOnFocusChangeListener();
            v.setOnFocusChangeListener((view, focused) -> {
                if (style != null) style.onFocusChange(view, focused);
                if (focused) {
                    lastBar = index;
                    showLabel(view, barNames.get(index));
                } else {
                    barLabel.animate().alpha(0f).setDuration(120).start();
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
                    case KeyEvent.KEYCODE_DPAD_UP:
                        focusDock.run();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        return true;
                    default:
                        return false;
                }
            });
        }

        if (shows("hint")) {
            ImageView pad = new ImageView(activity);
            pad.setImageResource(R.drawable.ic_joycons);
            pad.setImageTintList(ColorStateList.valueOf(ink));
            FrameLayout.LayoutParams padLp = new FrameLayout.LayoutParams(dp(38), dp(38),
                    Gravity.BOTTOM | (hebrew ? Gravity.RIGHT : Gravity.LEFT));
            padLp.leftMargin = padLp.rightMargin = dp(40);
            padLp.bottomMargin = dp(20);
            layer.addView(pad, padLp);

            LinearLayout ok = new LinearLayout(activity);
            ok.setOrientation(LinearLayout.HORIZONTAL);
            ok.setGravity(Gravity.CENTER_VERTICAL);
            TextView key = new TextView(activity);
            key.setText("OK");
            key.setTextColor(dark ? 0xFF2D2D2D : 0xFFFFFFFF);
            key.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
            key.setTypeface(UiFont.get(activity, R.string.font_medium));
            key.setGravity(Gravity.CENTER);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(ink);
            key.setBackground(circle);
            TextView open = new TextView(activity);
            open.setText(R.string.switch_open);
            open.setTextColor(ink);
            open.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
            open.setTypeface(UiFont.get(activity, R.string.font_regular));
            LinearLayout.LayoutParams keyLp = new LinearLayout.LayoutParams(dp(28), dp(28));
            LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (hebrew) {
                openLp.rightMargin = dp(8);
                ok.addView(open, openLp);
                ok.addView(key, keyLp);
            } else {
                openLp.leftMargin = dp(8);
                ok.addView(key, keyLp);
                ok.addView(open, openLp);
            }
            FrameLayout.LayoutParams okLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | (hebrew ? Gravity.LEFT : Gravity.RIGHT));
            okLp.leftMargin = okLp.rightMargin = dp(44);
            okLp.bottomMargin = dp(26);
            layer.addView(ok, okLp);
        }
        return layer;
    }

    /** The focused button's name, centred under it (kept on screen). */
    private void showLabel(View button, String text) {
        barLabel.setText(text);
        barLabel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        View layer = (View) barLabel.getParent();
        int[] layerAt = new int[2], at = new int[2];
        layer.getLocationInWindow(layerAt);
        button.getLocationInWindow(at);
        float x = at[0] - layerAt[0] + button.getWidth() / 2f - barLabel.getMeasuredWidth() / 2f;
        x = Math.max(dp(16), Math.min(layer.getWidth() - barLabel.getMeasuredWidth() - dp(16), x));
        barLabel.setTranslationX(x);
        barLabel.animate().alpha(1f).setDuration(160).start();
    }

    /**
     * A round button: its mark in its colour on the pill, or white on a filled disc (the library, like the console's
     * first button); focused, a white disc with the accent ring.
     */
    private ImageView disc(int drawable, int tint, boolean filled) {
        ImageView icon = new ImageView(activity);
        icon.setImageResource(drawable);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = dp(15);
        icon.setPadding(pad, pad, pad, pad);
        icon.setFocusable(true);
        icon.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        icon.setBackground(bg);
        Runnable rest = () -> {
            bg.setColor(filled ? tint : 0x00FFFFFF);
            bg.setStroke(0, 0);
            icon.setImageTintList(ColorStateList.valueOf(filled ? 0xFFFFFFFF : tint));
        };
        rest.run();
        icon.setOnFocusChangeListener((v, focused) -> {
            if (focused) {
                bg.setColor(filled ? tint : (dark ? 0xFF4A4A4E : 0xFFFFFFFF));
                bg.setStroke(dp(4), accent);
            } else {
                rest.run();
            }
            v.animate().scaleX(focused ? 1.14f : 1f).scaleY(focused ? 1.14f : 1f).setDuration(160).start();
        });
        return icon;
    }

    /** One of the header's own buttons in the bar's style; its click stays MainActivity's. */
    private View adopt(TextView button, int tint) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x00FFFFFF);
        button.setBackground(bg);
        button.setText(null);
        button.setGravity(Gravity.CENTER);
        int pad = Math.max(0, (dp(54) - dp(22)) / 2);
        button.setPadding(pad, 0, pad, 0);
        button.setCompoundDrawablePadding(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setCompoundDrawableTintList(ColorStateList.valueOf(tint));
        button.setOnFocusChangeListener((v, focused) -> {
            bg.setColor(focused ? (dark ? 0xFF4A4A4E : 0xFFFFFFFF) : 0x00FFFFFF);
            bg.setStroke(focused ? dp(4) : 0, accent);
            v.animate().scaleX(focused ? 1.14f : 1f).scaleY(focused ? 1.14f : 1f).setDuration(160).start();
        });
        return button;
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
