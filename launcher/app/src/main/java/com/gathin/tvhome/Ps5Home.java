package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The theme's ps5_home: Home laid out like a games console's. A top bar (two tabs on one side; the header's own
 * buttons, search, the quick panel, a profile badge and the time on the other), the row of square apps just under
 * it, the focused app's kicker, title and a line about it (ps5_blurbs / ps5_blurbs_he) over the middle of the
 * screen, and the app's own artwork full-bleed behind when the theme has one (art/PACKAGE.jpg, .png or .webp). The
 * header's clock is hidden; MainActivity keeps everything else.
 *
 * Every part can be tuned from config.json (the wallpaper editor has a page for it): ps5_columns (tile size),
 * ps5_zoom, ps5_title_size, ps5_scrim (0-100), ps5_tab_apps / ps5_tab_library (labels), ps5_avatar_color, and
 * ps5_show_buttons / _search / _avatar / _clock / _blurb / _last_opened (all on unless set false).
 */
final class Ps5Home {
    /** The theme folder of per-app artwork, art/PACKAGE.jpg. */
    static final String ART = "art";
    /** The profile picture next to config.json; without one the badge shows the greeting name's first letter. */
    static final String AVATAR = "avatar.jpg";
    /** How far the focused tile grows (ps5_zoom), against the 1.10 of the other layouts. Set by load(). */
    static float zoom = 1.3f;
    /** Tiles across the row (ps5_columns): fewer, larger tiles. Set by load(). */
    static int columns = 9;

    private static final String[] ART_TYPES = {".jpg", ".png", ".webp"};

    private final Activity activity;
    private final float density;
    private final boolean hebrew;
    private final ImageView art;
    private final TextView blurb;
    private final JSONObject blurbs, fallbackBlurbs;
    private final Runnable focusDock;
    /** The bar's focusable views, left to right on screen. */
    private final List<View> bar = new ArrayList<>();
    private final LruCache<String, Bitmap> artCache = new LruCache<>(2);
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private String artPkg;
    private final JSONObject cfg;

    static boolean on(Context ctx) {
        return HeaderExtras.themeFlag(ctx, "ps5_home");
    }

    /** The row's size, read before the grid is built: the adapter sizes its tiles from both. */
    static void load(Context ctx) {
        JSONObject json = HeaderExtras.readConfig(ctx);
        columns = json == null ? 9 : Math.max(5, Math.min(12, json.optInt("ps5_columns", 9)));
        zoom = json == null ? 1.3f : (float) Math.max(1.0, Math.min(1.6, json.optDouble("ps5_zoom", 1.3)));
    }

    /** A ps5_show_* switch: on unless the theme turns it off. */
    private boolean shows(String part) {
        return cfg == null || cfg.optBoolean("ps5_show_" + part, true);
    }

    /** Whether the kicker line ("Last opened ...") is shown above the title. */
    boolean showsLastOpened() {
        return shows("last_opened");
    }

    Ps5Home(Activity activity, FrameLayout root, LinearLayout hero, TextView heroName, TextView heroMeta,
            List<View> cornerButtons, Runnable focusDock, Runnable openLibrary, Runnable openPanel) {
        this.activity = activity;
        this.focusDock = focusDock;
        density = activity.getResources().getDisplayMetrics().density;
        hebrew = HebrewFont.hebrew(activity);
        cfg = HeaderExtras.readConfig(activity);
        JSONObject en = cfg == null ? null : cfg.optJSONObject("ps5_blurbs");
        JSONObject he = cfg == null ? null : cfg.optJSONObject("ps5_blurbs_he");
        blurbs = hebrew && he != null ? he : en;
        fallbackBlurbs = en;

        // The artwork and its scrim go just above the wallpaper and the sky, under everything else.
        int at = root.indexOfChild(root.findViewById(R.id.heroArt));
        art = new ImageView(activity);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setAlpha(0f);
        art.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(art, at, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View scrim = new View(activity);
        scrim.setBackground(scrim());
        scrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(scrim, at + 1, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        activity.findViewById(R.id.leftBlock).setVisibility(View.GONE);
        activity.findViewById(R.id.topButtons).setVisibility(View.GONE);
        root.addView(topBar(cornerButtons, openLibrary, openPanel), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // The kicker ("Last opened ...") above the title, a line about the app under it.
        hero.removeView(heroMeta);
        hero.addView(heroMeta, 0);
        LinearLayout.LayoutParams metaLp = (LinearLayout.LayoutParams) heroMeta.getLayoutParams();
        metaLp.topMargin = 0;
        metaLp.bottomMargin = dp(12);
        metaLp.leftMargin = metaLp.rightMargin = dp(2);
        heroMeta.setLayoutParams(metaLp);
        heroMeta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        heroMeta.setVisibility(showsLastOpened() ? View.INVISIBLE : View.GONE);
        heroName.setTextSize(TypedValue.COMPLEX_UNIT_SP,
                cfg == null ? 40f : Math.max(20, Math.min(72, cfg.optInt("ps5_title_size", 40))));
        heroName.setLetterSpacing(-0.01f);
        blurb = new TextView(activity);
        blurb.setTextColor(0xD9FFFFFF);
        blurb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        blurb.setTypeface(UiFont.get(activity, R.string.font_light));
        blurb.setLineSpacing(dp(4), 1f);
        blurb.setMaxLines(2);
        blurb.setEllipsize(android.text.TextUtils.TruncateAt.END);
        blurb.setVisibility(View.GONE);
        LinearLayout.LayoutParams blurbLp = new LinearLayout.LayoutParams(
                Math.round(activity.getResources().getDisplayMetrics().widthPixels * 0.46f), ViewGroup.LayoutParams.WRAP_CONTENT);
        blurbLp.topMargin = dp(14);
        blurbLp.leftMargin = blurbLp.rightMargin = dp(2);
        hero.addView(blurb, blurbLp);
        if (hebrew) {
            // Hebrew reads from the right: the text block moves to the right edge, each line against it.
            FrameLayout.LayoutParams heroLp = (FrameLayout.LayoutParams) hero.getLayoutParams();
            heroLp.gravity = Gravity.TOP | Gravity.RIGHT;
            heroLp.rightMargin = heroLp.leftMargin;
            heroLp.leftMargin = 0;
            heroLp.setMarginStart(0);
            hero.setLayoutParams(heroLp);
            hero.setGravity(Gravity.RIGHT);
            for (TextView line : new TextView[]{heroName, heroMeta, blurb}) line.setGravity(Gravity.RIGHT);
        }
    }

    /** The shelf's top edge: a little above the middle of the screen, clear of the row and of the cards. */
    float heroY(int rootHeight) {
        return rootHeight * 0.45f;
    }

    /** The line about this app and its artwork, if the theme has them. */
    void fill(String pkg) {
        String text = pkg == null ? "" : blurbs != null ? blurbs.optString(pkg, "") : "";
        if (text.isEmpty() && pkg != null && fallbackBlurbs != null && fallbackBlurbs != blurbs) {
            text = fallbackBlurbs.optString(pkg, "");
        }
        blurb.setText(text);
        blurb.setVisibility(text.isEmpty() || !shows("blurb") ? View.GONE : View.VISIBLE);
        showArt(pkg);
    }

    /** Focus on the bar's settings button; Up from the row lands there. */
    boolean focusBar() {
        for (View v : bar) {
            if (v.getId() == R.id.ps5_gear) return v.requestFocus();
        }
        return false;
    }

    // ---- artwork ---------------------------------------------------------------------------------

    private void showArt(String pkg) {
        if (pkg == null || pkg.equals(artPkg)) return;
        artPkg = pkg;
        Bitmap cached = artCache.get(pkg);
        if (cached != null) {
            swap(cached);
            return;
        }
        File file = artFile(pkg);
        if (file == null) {
            swap(null);
            return;
        }
        int w = activity.getResources().getDisplayMetrics().widthPixels;
        int h = activity.getResources().getDisplayMetrics().heightPixels;
        // Off the UI thread: a full-screen photo takes tens of milliseconds to decode on a TV.
        loader.execute(() -> {
            Bitmap bmp = WallpaperPlayer.decode(file, w, h, Bitmap.Config.ARGB_8888, 0);
            main.post(() -> {
                if (activity.isDestroyed()) return;
                if (bmp != null) artCache.put(pkg, bmp);
                if (pkg.equals(artPkg)) swap(bmp);
            });
        });
    }

    private File artFile(String pkg) {
        File dir = Config.dir(activity);
        if (dir == null) return null;
        for (String type : ART_TYPES) {
            File file = new File(new File(dir, ART), pkg + type);
            if (file.isFile()) return file;
        }
        return null;
    }

    private void swap(Bitmap bmp) {
        art.animate().cancel();
        if (bmp == null) {
            art.animate().alpha(0f).setDuration(260).start();
            return;
        }
        if (art.getAlpha() == 0f) {
            art.setImageBitmap(bmp);
            art.animate().alpha(1f).setDuration(420).start();
            return;
        }
        art.animate().alpha(0f).setDuration(140).withEndAction(() -> {
            art.setImageBitmap(bmp);
            art.animate().alpha(1f).setDuration(420).start();
        }).start();
    }

    /**
     * Darkens the text side, the top edge under the bar and the bottom under the cards, so all stay readable over a
     * bright photo. One small bitmap stretched over the screen, like MainActivity's glow.
     */
    private BitmapDrawable scrim() {
        int w = 480, h = 270;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint();
        paint.setShader(new LinearGradient(hebrew ? w : 0, 0, hebrew ? 0 : w, 0,
                new int[]{0xB3020A1E, 0x59020A1E, 0x00020A1E}, new float[]{0f, 0.45f, 0.75f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0x8C020A1E, 0x00020A1E, 0x00020A1E, 0xCC020A1E}, new float[]{0f, 0.2f, 0.6f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        BitmapDrawable scrim = new BitmapDrawable(activity.getResources(), bmp);
        scrim.setGravity(Gravity.FILL);
        // ps5_scrim: how strong the shade is, on the bitmap's paint rather than a view alpha.
        int percent = cfg == null ? 100 : Math.max(0, Math.min(100, cfg.optInt("ps5_scrim", 100)));
        scrim.setAlpha(percent * 255 / 100);
        return scrim;
    }

    // ---- top bar ---------------------------------------------------------------------------------

    private View topBar(List<View> cornerButtons, Runnable openLibrary, Runnable openPanel) {
        FrameLayout top = new FrameLayout(activity);
        top.setPadding(dp(56), dp(22), dp(56), 0);
        top.setClipChildren(false);
        top.setClipToPadding(false);

        TextView apps = tab(label("ps5_tab_apps", R.string.ps5_tab_apps), true);
        apps.setOnClickListener(v -> focusDock.run());
        TextView library = tab(label("ps5_tab_library", R.string.ps5_tab_library), false);
        library.setOnClickListener(v -> openLibrary.run());
        LinearLayout tabs = row();
        tabs.addView(hebrew ? library : apps);
        tabs.addView(hebrew ? apps : library, margins(dp(14)));

        ImageView search = icon(R.drawable.ic_search, R.string.ps5_search);
        search.setOnClickListener(v -> search());
        // The quick panel gets sliders, not a gear: the header's Settings button keeps the gear.
        ImageView gear = icon(R.drawable.ic_tune, R.string.qs_launcher);
        gear.setId(R.id.ps5_gear);
        gear.setOnClickListener(v -> openPanel.run());
        View avatar = shows("avatar") ? avatar(cfg) : null;
        TextClock clock = null;
        if (shows("clock")) {
            clock = new TextClock(activity);
            clock.setFormat12Hour("h:mm a");
            clock.setFormat24Hour("HH:mm");
            clock.setTextColor(0xFFFFFFFF);
            clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
            clock.setTypeface(UiFont.get(activity, R.string.font_light));
        }
        // Left to right in English: the header's buttons, search, the panel, the badge, the time; mirrored in Hebrew.
        List<View> tools = new ArrayList<>();
        if (shows("buttons")) {
            for (View button : cornerButtons) tools.add(adopt((TextView) button));
        }
        if (shows("search")) tools.add(search);
        tools.add(gear);
        if (avatar != null) tools.add(avatar);
        if (clock != null) tools.add(clock);
        if (hebrew) java.util.Collections.reverse(tools);
        LinearLayout toolRow = row();
        for (int i = 0; i < tools.size(); i++) {
            boolean byClock = clock != null && (tools.get(i) == clock || i > 0 && tools.get(i - 1) == clock);
            // Keep a button's own square size (icon(), adopt()); the badge and the clock wrap their content.
            View tool = tools.get(i);
            LinearLayout.LayoutParams lp = margins(i == 0 ? 0 : dp(byClock ? 26 : 8));
            if (tool.getLayoutParams() != null) {
                lp.width = tool.getLayoutParams().width;
                lp.height = tool.getLayoutParams().height;
            }
            toolRow.addView(tool, lp);
        }

        top.addView(tabs, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                (hebrew ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL));
        top.addView(toolRow, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                (hebrew ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL));

        // Left to right on screen, for Left and Right; Down goes back to the row, Up stays.
        List<View> focusable = new ArrayList<>();
        for (View v : tools) if (v.isFocusable()) focusable.add(v);
        if (hebrew) {
            bar.addAll(focusable);
            bar.add(library);
            bar.add(apps);
        } else {
            bar.add(apps);
            bar.add(library);
            bar.addAll(focusable);
        }
        for (View v : bar) {
            v.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int i = bar.indexOf(view);
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        if (i > 0) bar.get(i - 1).requestFocus();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        if (i + 1 < bar.size()) bar.get(i + 1).requestFocus();
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

    /** A label from the theme (ps5_tab_apps ...), else the built-in one. */
    private String label(String key, int fallback) {
        String text = cfg == null ? "" : cfg.optString(key, "").trim();
        return text.isEmpty() ? activity.getString(fallback) : text;
    }

    /**
     * One of the header's own buttons (Inputs, the network, Bluetooth, Reorder, Settings) in the bar's style: the
     * plain white mark, a white disc with the mark dark on focus. Their clicks stay MainActivity's.
     */
    private View adopt(TextView button) {
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(0x00FFFFFF);
        button.setBackground(disc);
        button.setText(null);
        button.setGravity(Gravity.CENTER);
        int side = dp(42);
        int pad = Math.max(0, (side - dp(22)) / 2);
        button.setPadding(pad, 0, pad, 0);
        button.setCompoundDrawablePadding(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setLayoutParams(new LinearLayout.LayoutParams(side, side));
        button.setCompoundDrawableTintList(ColorStateList.valueOf(0xFFFFFFFF));
        button.setOnFocusChangeListener((v, focused) -> {
            disc.setColor(focused ? 0xFFFFFFFF : 0x00FFFFFF);
            button.setCompoundDrawableTintList(ColorStateList.valueOf(focused ? 0xFF101014 : 0xFFFFFFFF));
            v.animate().scaleX(focused ? 1.1f : 1f).scaleY(focused ? 1.1f : 1f).setDuration(160).start();
        });
        return button;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        return row;
    }

    private LinearLayout.LayoutParams margins(int left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = left;
        return lp;
    }

    /** A tab: white when it is the page shown, dimmed otherwise; focused, a white pill with dark text. */
    private TextView tab(String text, boolean current) {
        TextView tab = new TextView(activity);
        tab.setText(text);
        tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f);
        tab.setTypeface(UiFont.get(activity, current ? R.string.font_medium : R.string.font_regular));
        int rest = current ? 0xFFFFFFFF : 0x99FFFFFF;
        tab.setTextColor(rest);
        tab.setPadding(dp(14), dp(5), dp(14), dp(6));
        tab.setFocusable(true);
        tab.setClickable(true);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(20));
        pill.setColor(0x00FFFFFF);
        tab.setBackground(pill);
        tab.setOnFocusChangeListener((v, focused) -> {
            pill.setColor(focused ? 0xFFFFFFFF : 0x00FFFFFF);
            tab.setTextColor(focused ? 0xFF101014 : rest);
            v.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f).setDuration(160).start();
        });
        return tab;
    }

    /** A round icon button: a white mark, and on focus a white disc with the mark dark. */
    private ImageView icon(int drawable, int description) {
        ImageView icon = new ImageView(activity);
        icon.setImageResource(drawable);
        icon.setContentDescription(activity.getString(description));
        icon.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = dp(9);
        icon.setPadding(pad, pad, pad, pad);
        icon.setFocusable(true);
        icon.setClickable(true);
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(0x00FFFFFF);
        icon.setBackground(disc);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(42)));
        icon.setOnFocusChangeListener((v, focused) -> {
            disc.setColor(focused ? 0xFFFFFFFF : 0x00FFFFFF);
            icon.setImageTintList(ColorStateList.valueOf(focused ? 0xFF101014 : 0xFFFFFFFF));
            v.animate().scaleX(focused ? 1.1f : 1f).scaleY(focused ? 1.1f : 1f).setDuration(160).start();
        });
        return icon;
    }

    /** The profile badge: avatar.jpg, or the greeting name's first letter on a blue disc, with a green "online" dot. */
    private View avatar(JSONObject cfg) {
        String name = cfg == null ? "" : cfg.optString(hebrew ? "greeting_name_he" : "greeting_name", "");
        if (name.isEmpty() && cfg != null) name = cfg.optString("greeting_name", "");
        File dir = Config.dir(activity);
        File photo = dir != null ? new File(dir, AVATAR) : null;
        Bitmap face = photo != null && photo.isFile() ? WallpaperPlayer.decode(photo, dp(40), dp(40), Bitmap.Config.ARGB_8888, 0) : null;
        if (face == null && name.trim().isEmpty()) return null;

        int size = dp(36);
        FrameLayout badge = new FrameLayout(activity);
        badge.setClipChildren(false);
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
            letter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
            letter.setTypeface(UiFont.get(activity, R.string.font_medium));
            letter.setGravity(Gravity.CENTER);
            int color = 0xFF3B7BFF;
            try {
                if (cfg != null && !cfg.optString("ps5_avatar_color", "").isEmpty()) {
                    color = android.graphics.Color.parseColor(cfg.optString("ps5_avatar_color"));
                }
            } catch (IllegalArgumentException ignored) {
            }
            int darker = android.graphics.Color.rgb(android.graphics.Color.red(color) * 45 / 100,
                    android.graphics.Color.green(color) * 45 / 100, android.graphics.Color.blue(color) * 65 / 100);
            GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF000000 | color, darker});
            fill.setShape(GradientDrawable.OVAL);
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
        badge.addView(disc, new FrameLayout.LayoutParams(size, size));
        View dot = new View(activity);
        GradientDrawable green = new GradientDrawable();
        green.setShape(GradientDrawable.OVAL);
        green.setColor(0xFF2ED36B);
        green.setStroke(dp(2), 0xFF0A1A3A);
        dot.setBackground(green);
        FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(dp(12), dp(12), Gravity.BOTTOM | Gravity.RIGHT);
        dotLp.rightMargin = -dp(1);
        dotLp.bottomMargin = -dp(1);
        badge.addView(dot, dotLp);
        badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return badge;
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
