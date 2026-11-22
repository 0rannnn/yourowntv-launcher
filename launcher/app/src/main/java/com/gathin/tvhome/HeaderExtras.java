package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.text.style.ImageSpan;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Extras in Home's header, all from the theme's config.json and off unless set there:
 * <ul>
 * <li>{@code clock_font.ttf} next to config.json: the big clock in that typeface, at {@code clock_font_weight}
 * when the font is a variable one.</li>
 * <li>{@code location_label} ("HaMerkaz, Israel"): a pin and the place after the greeting;
 * {@code location_label_he} ("המרכז, ישראל") instead while the launcher is in Hebrew.</li>
 * <li>{@code weather_lat} / {@code weather_lon}: the temperature in degrees Celsius and an icon for the sky beside the
 * clock, from Open-Meteo (no account). Read when Home comes up, at most every 30 minutes, never in the background;
 * the last reading is kept so Home shows it at once.</li>
 * <li>{@code center_clock}: the time huge and centred near the top, behind the wallpaper's foreground cut-out
 * (WallpaperPlayer), with one line above it: greeting and place, date, weather. The corner clock is hidden.
 * {@code center_clock_size} is the type size as a share of the screen height (0.36). The digits are glass: a
 * translucent fill with a brighter edge. The quick panel's clock-depth switch (HomeToggles) takes the cut-out away.</li>
 * </ul>
 */
final class HeaderExtras {
    private static final String TAG = "HeaderExtras";
    static final String CLOCK_FONT = "clock_font.ttf";
    private static final long REFRESH_MS = 30 * 60 * 1000L;
    private static final long RETRY_MS = 5 * 60 * 1000L;
    private static final String PREFS = "header_weather";

    private final Activity activity;
    private final String location;
    private final String localName;
    private final double lat;
    private final double lon;
    private final boolean weather;
    /** clock_font_weight: 100 (thin) to 900 (black) for a variable clock font; 0 leaves the font's default. */
    private final int fontWeight;
    private final boolean centerClock;
    private final float centerClockSize;
    /** Where the top of the centred clock's digits sits, as a share of the screen height (prepare.py's CLOCK_BOX). */
    private static final float CENTER_CLOCK_TOP = 0.12f;
    /** The centred clock and the band its digits fill, for the glass slider to redraw it. */
    private GlassClock glassClock;
    private float glassTop, glassBottom;
    private ImageView weatherIcon;
    private TextView weatherTemp;
    private boolean fetching;
    private final Handler main = new Handler(Looper.getMainLooper());

    HeaderExtras(Activity activity) {
        this.activity = activity;
        JSONObject json = readConfig(activity);
        // location_label_he (or _ar, _vi...) while the launcher shows that language; location_label otherwise.
        String lang = activity.getResources().getConfiguration().getLocales().get(0).getLanguage();
        if ("iw".equals(lang)) lang = "he";
        String local = json != null ? json.optString("location_label_" + lang, "").trim() : "";
        location = !local.isEmpty() ? local : json != null ? json.optString("location_label", "").trim() : "";
        localName = json != null ? json.optString("greeting_name_" + lang, "").trim() : "";
        lat = json != null ? json.optDouble("weather_lat", Double.NaN) : Double.NaN;
        lon = json != null ? json.optDouble("weather_lon", Double.NaN) : Double.NaN;
        weather = !Double.isNaN(lat) && !Double.isNaN(lon);
        fontWeight = json != null ? json.optInt("clock_font_weight", 0) : 0;
        centerClock = json != null && json.optBoolean("center_clock", false);
        float size = json != null ? (float) json.optDouble("center_clock_size", 0.36) : 0.36f;
        centerClockSize = size > 0.05f && size < 0.6f ? size : 0.36f;
        clockScale = json == null ? 1f : AppsLayout.percent(json, "clock_size", 100, 40, 250) / 100f;
        clockX = json == null ? 0f : AppsLayout.percent(json, "clock_x", 0, -90, 90) / 100f;
        clockY = json == null ? 0f : AppsLayout.percent(json, "clock_y", 0, -20, 90) / 100f;
    }

    /** clock_size (40 to 250 %): the clock, its greeting and date, bigger or smaller than the theme's own. */
    private final float clockScale;
    /** clock_x / clock_y: how far the clock moves from its own place, as a share of the screen's width / height. */
    private final float clockX, clockY;

    /**
     * The corner clock (leftBlock: the greeting, the time, the date and the weather) scaled from its top-left corner
     * and moved by clock_x / clock_y. Only drawn differently: the top buttons and the grid stay where they are.
     */
    private void placeCornerClock() {
        View block = activity.findViewById(R.id.leftBlock);
        if (block == null || (clockScale == 1f && clockX == 0f && clockY == 0f)) return;
        DisplayMetrics screen = activity.getResources().getDisplayMetrics();
        block.setPivotX(0);
        block.setPivotY(0);
        block.setScaleX(clockScale);
        block.setScaleY(clockScale);
        block.setTranslationX(clockX * screen.widthPixels);
        block.setTranslationY(clockY * screen.heightPixels);
    }

    /** A true/false key of the theme's config.json, false when the theme has none. */
    static boolean themeFlag(Context ctx, String key) {
        JSONObject json = readConfig(ctx);
        return json != null && json.optBoolean(key, false);
    }

    /** Once, after setContentView: the clock's typeface and the weather views beside it. */
    void attach(TextView clock) {
        applyClockFont(clock);
        if (centerClock) {
            addCenterClock();
            return;
        }
        if (weather) addWeatherViews(clock);
        placeCornerClock();
        // The corner clock (greeting, time, date, weather, with their shadows) the same way: a ready-made picture.
        View block = activity.findViewById(R.id.leftBlock);
        if (block != null) block.setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    private void applyClockFont(TextView clock) {
        applyClockFont(activity, clock, fontWeight);
    }

    /** The theme's clock_font.ttf at clock_font_weight, for the screensavers' clocks too. */
    static void applyClockFont(Context ctx, TextView clock) {
        JSONObject json = readConfig(ctx);
        applyClockFont(ctx, clock, json != null ? json.optInt("clock_font_weight", 0) : 0);
    }

    private static void applyClockFont(Context ctx, TextView clock, int fontWeight) {
        // The clocks keep their own typeface in Hebrew too (HebrewFont).
        clock.setTag(HebrewFont.KEEP);
        File dir = Config.dir(ctx);
        File font = dir != null ? new File(dir, CLOCK_FONT) : null;
        if (font != null && font.isFile() && font.length() > 0) {
            try {
                clock.setTypeface(Typeface.createFromFile(font));
                clock.setLetterSpacing(0f);
                // A variable font (Rubik, Inter...) at the weight asked for; a static font ignores it.
                if (fontWeight > 0) clock.setFontVariationSettings("'wght' " + fontWeight);
            } catch (RuntimeException e) {
                Log.w(TAG, "clock font unreadable: " + e.getMessage());
            }
        }
    }

    /**
     * The centred clock and the line above it, as children of the root just under the wallpaper's cut-out, so the
     * landscape stands in front of the time. The corner clock stays in place, invisible, for the header's height.
     */
    private void addCenterClock() {
        ViewGroup root = activity.findViewById(R.id.root);
        if (!(root instanceof FrameLayout)) return;
        View corner = activity.findViewById(R.id.leftBlock);
        if (corner != null) corner.setAlpha(0f);
        // The round buttons go up into the top-right corner, a little smaller, clear of the line and the clock.
        View buttons = activity.findViewById(R.id.topButtons);
        if (buttons != null) {
            int edge = Math.round(8 * activity.getResources().getDisplayMetrics().density);
            buttons.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                v.setPivotX(v.getWidth());
                v.setPivotY(0);
                v.setScaleX(0.9f);
                v.setScaleY(0.9f);
                int[] at = new int[2];
                v.getLocationInWindow(at);
                v.setTranslationY(edge - (at[1] - v.getTranslationY()));
            });
        }
        float density = activity.getResources().getDisplayMetrics().density;
        DisplayMetrics screen = new DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getRealMetrics(screen);
        int height = screen.heightPixels;

        GlassClock clock = new GlassClock(activity);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("H:mm");
        clock.setIncludeFontPadding(false);
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, centerClockSize * clockScale * height);
        applyClockFont(clock);
        clock.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        // Where the digits' tops fall inside the view: the view moves so they land on CENTER_CLOCK_TOP.
        Paint paint = clock.getPaint();
        Rect digits = new Rect();
        paint.getTextBounds("0123456789", 0, 10, digits);
        float inset = -paint.getFontMetrics().ascent + digits.top;
        // clock_y moves the digits down (or up) the screen; clock_x sideways, with the line above them.
        float glyphTop = Math.max(0, (CENTER_CLOCK_TOP + clockY) * height);
        float shiftX = clockX * screen.widthPixels;
        clock.setTranslationX(shiftX);
        glassClock = clock;
        glassTop = inset;
        glassBottom = inset + digits.height();
        applyGlass();
        FrameLayout.LayoutParams clockLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        clockLp.topMargin = Math.round(glyphTop - inset);

        LinearLayout line = new LinearLayout(activity);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        Locale locale = activity.getResources().getConfiguration().getLocales().get(0);
        java.util.List<View> parts = new java.util.ArrayList<>();
        TextView greeting = activity.findViewById(R.id.greeting);
        if (greeting != null && greeting.getVisibility() == View.VISIBLE) {
            TextView copy = lineText();
            copy.setText(greeting.getText());
            greeting.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void afterTextChanged(Editable s) { copy.setText(greeting.getText()); }
            });
            parts.add(copy);
        }
        TextClock date = new TextClock(activity);
        styleLineText(date);
        String pattern = DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM");
        date.setFormat12Hour(pattern);
        date.setFormat24Hour(pattern);
        parts.add(date);
        if (weather) parts.add(weatherBox(density, 19, 17));
        // The launcher lays out left to right; Hebrew reads the line from the right.
        if ("iw".equals(locale.getLanguage())) java.util.Collections.reverse(parts);
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                TextView dot = lineText();
                dot.setText("\u00B7");
                LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                dotLp.leftMargin = Math.round(12 * density);
                dotLp.rightMargin = Math.round(12 * density);
                line.addView(dot, dotLp);
            }
            line.addView(parts.get(i));
        }
        FrameLayout.LayoutParams lineLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        int gap = Math.round(8 * density);
        // Its foot sits just over the digits' tops, whatever height the line comes out at.
        line.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            v.setTranslationY(glyphTop - gap - v.getHeight());
            v.setTranslationX(shiftX);
        });

        View cutOut = root.findViewWithTag(WallpaperPlayer.FOREGROUND_TAG);
        int at = cutOut != null ? root.indexOfChild(cutOut) : root.indexOfChild(activity.findViewById(R.id.sky)) + 1;
        root.addView(line, Math.max(at, 0), lineLp);
        root.addView(clock, Math.max(at, 0), clockLp);
        // The digits are huge text drawn twice with gradients (GlassClock): kept as ready-made pictures, so scrolling
        // the apps only copies them (they are drawn again when the minute or the glass changes), instead of drawing
        // them again on every frame (that alone cost about 15 ms a frame on the projector).
        clock.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        line.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        HebrewFont.apply(line);
    }

    /** The centred clock's digits drawn twice: a translucent fill, then a thin brighter edge over it. */
    static final class GlassClock extends TextClock {
        private Shader fill;
        private Shader edge;
        private float edgeWidth;

        GlassClock(Context context) {
            super(context);
        }

        void setGlass(Shader fill, Shader edge, float edgeWidth) {
            this.fill = fill;
            this.edge = edge;
            this.edgeWidth = edgeWidth;
            getPaint().setShader(fill);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            TextPaint paint = getPaint();
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(fill);
            super.onDraw(canvas);
            if (edge == null) return;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(edgeWidth);
            paint.setShader(edge);
            super.onDraw(canvas);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(fill);
        }
    }

    private TextView lineText() {
        TextView text = new TextView(activity);
        styleLineText(text);
        return text;
    }

    private void styleLineText(TextView text) {
        text.setTextColor(activity.getColor(R.color.text_primary));
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        text.setTypeface(activity.getResources().getFont(R.font.outfit_light));
        text.setIncludeFontPadding(false);
        text.setSingleLine(true);
        text.setShadowLayer(10, 0, 1, 0x80000000);
    }

    /** greeting_name_he (or _ar...) while the launcher shows that language, else the theme's greeting_name. */
    String name(String greetingName) {
        return localName.isEmpty() ? greetingName : localName;
    }

    boolean hasLocation() {
        return !location.isEmpty();
    }

    /** The greeting with the pin and the place after it, or the greeting alone without a location_label. */
    CharSequence withLocation(CharSequence greeting, TextView view) {
        if (location.isEmpty()) return greeting;
        SpannableStringBuilder text = new SpannableStringBuilder(greeting).append("     ");
        Drawable pin = activity.getDrawable(R.drawable.ic_location);
        if (pin != null) {
            int size = Math.round(view.getTextSize() * 1.1f);
            pin.setBounds(0, 0, size, size);
            pin.setTint(view.getCurrentTextColor());
            int at = text.length();
            text.append(" ");
            text.setSpan(new ImageSpan(pin, ImageSpan.ALIGN_BASELINE), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.append(" ");
        }
        return text.append(location);
    }

    /**
     * Glass: the photo shows through the digits, more towards the foot, and a brighter rim catches the light. How much
     * shows through follows the quick panel's glass slider.
     */
    private void applyGlass() {
        if (glassClock == null) return;
        applyGlass(glassClock, HomeToggles.glass(activity), activity.getResources().getDisplayMetrics().density,
                glassTop, glassBottom, photoSolidity);
    }

    /** The shown picture's solidity (WallpaperPlayer.solidity): the glass firms up over a dark or busy photo. */
    private float photoSolidity;

    void photoSolidity(float solidity) {
        photoSolidity = solidity;
        applyGlass();
    }

    /**
     * The glass for a level of the slider, its shading running down the digits from glassTop to glassBottom. Over a
     * dark or busy photo (solidity towards 1) the fill moves most of the way to near-solid white, so the digits stay
     * white rather than grey and the photo behind them stops cluttering them; the rim is left as it is.
     */
    static void applyGlass(GlassClock glassClock, int level, float density, float glassTop, float glassBottom,
                           float solidity) {
        float firm = 0.75f * Math.max(0, Math.min(1, solidity));
        int top = HomeToggles.mix(0xF0, 0x66, level);
        int bottom = HomeToggles.mix(0xC0, 0x30, level);
        top = Math.round(top + (0xFA - top) * firm);
        bottom = Math.round(bottom + (0xF0 - bottom) * firm);
        glassClock.setGlass(new LinearGradient(0, glassTop, 0, glassBottom,
                        white(top), white(bottom),
                        Shader.TileMode.CLAMP),
                new LinearGradient(0, glassTop, 0, glassBottom,
                        white(HomeToggles.mix(0xFF, 0xE6, level)), white(HomeToggles.mix(0x80, 0x70, level)),
                        Shader.TileMode.CLAMP),
                Math.max(1.5f, 1.25f * density));
    }

    private static int white(int alpha) {
        return (alpha << 24) | 0xFFFFFF;
    }

    /** Home came up: show the last reading and fetch a new one when it is due. */
    void onResume() {
        applyGlass();
        if (!weather || weatherTemp == null) return;
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        show(prefs.getInt("temp", Integer.MIN_VALUE), prefs.getInt("code", -1), prefs.getBoolean("day", true));
        long now = System.currentTimeMillis();
        long due = prefs.getLong("ok_at", 0) + REFRESH_MS;
        long retry = prefs.getLong("failed_at", 0) + RETRY_MS;
        if (fetching || now < due || now < retry) return;
        fetching = true;
        Context app = activity.getApplicationContext();
        new Thread(() -> fetch(app), "weather").start();
    }

    private void fetch(Context app) {
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        HttpURLConnection http = null;
        try {
            URL url = new URL(String.format(Locale.ROOT,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                            + "&current=temperature_2m,weather_code,is_day&timezone=auto", lat, lon));
            http = (HttpURLConnection) url.openConnection();
            http.setConnectTimeout(10000);
            http.setReadTimeout(10000);
            if (http.getResponseCode() != 200) throw new java.io.IOException("HTTP " + http.getResponseCode());
            JSONObject current = new JSONObject(read(http.getInputStream())).getJSONObject("current");
            int temp = (int) Math.round(current.getDouble("temperature_2m"));
            int code = current.getInt("weather_code");
            boolean day = current.optInt("is_day", 1) == 1;
            prefs.edit().putInt("temp", temp).putInt("code", code).putBoolean("day", day)
                    .putLong("ok_at", System.currentTimeMillis()).apply();
            main.post(() -> show(temp, code, day));
        } catch (Exception e) {
            Log.w(TAG, "weather: " + e.getMessage());
            prefs.edit().putLong("failed_at", System.currentTimeMillis()).apply();
        } finally {
            if (http != null) http.disconnect();
            main.post(() -> fetching = false);
        }
    }

    private void show(int temp, int code, boolean day) {
        if (weatherTemp == null) return;
        boolean known = temp != Integer.MIN_VALUE;
        ((View) weatherTemp.getParent()).setVisibility(known ? View.VISIBLE : View.GONE);
        if (!known) return;
        weatherTemp.setText(String.format(Locale.ROOT, "%d°", temp));
        weatherIcon.setImageResource(icon(code, day));
    }

    /** WMO weather interpretation code (as Open-Meteo reports it) to one of the icons. */
    static int icon(int code, boolean day) {
        if (code == 0 || code == 1) return day ? R.drawable.wx_sun : R.drawable.wx_moon;
        if (code == 2) return day ? R.drawable.wx_partly_day : R.drawable.wx_partly_night;
        if (code == 3) return R.drawable.wx_cloud;
        if (code == 45 || code == 48) return R.drawable.wx_fog;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.drawable.wx_snow;
        if (code >= 95) return R.drawable.wx_storm;
        if (code >= 51 && code <= 82) return R.drawable.wx_rain;
        return R.drawable.wx_cloud;
    }

    /** The icon and the temperature, beside the clock on the date's side, in the left-to-right and Hebrew layouts. */
    private void addWeatherViews(TextView clock) {
        View divider = activity.findViewById(R.id.clockDivider);
        if (divider == null || !(divider.getParent() instanceof ViewGroup)) return;
        ViewGroup row = (ViewGroup) divider.getParent();
        View clockSide = clock;
        while (clockSide.getParent() != row && clockSide.getParent() instanceof View) clockSide = (View) clockSide.getParent();
        if (clockSide.getParent() != row) return;
        float density = activity.getResources().getDisplayMetrics().density;

        // Secondary to the clock: smaller, and in the design system's secondary white.
        LinearLayout box = weatherBox(density, 28, 24);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        lp.bottomMargin = Math.round(10 * density);
        lp.leftMargin = Math.round(18 * density);
        lp.rightMargin = Math.round(4 * density);
        int dividerAt = row.indexOfChild(divider);
        row.addView(box, row.indexOfChild(clockSide) < dividerAt ? dividerAt : dividerAt + 1, lp);
    }

    /** The sky icon and the temperature, hidden until there is a reading. */
    private LinearLayout weatherBox(float density, int iconDp, int textSp) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setVisibility(View.GONE);
        weatherIcon = new ImageView(activity);
        int iconSize = Math.round(iconDp * density);
        box.addView(weatherIcon, new LinearLayout.LayoutParams(iconSize, iconSize));
        weatherTemp = new TextView(activity);
        weatherTemp.setTextColor(activity.getColor(R.color.text_primary));
        weatherTemp.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp);
        weatherTemp.setTypeface(activity.getResources().getFont(R.font.outfit_light));
        weatherTemp.setIncludeFontPadding(false);
        weatherTemp.setShadowLayer(10, 0, 1, 0x80000000);
        LinearLayout.LayoutParams tempLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tempLp.setMarginStart(Math.round(6 * density));
        box.addView(weatherTemp, tempLp);
        return box;
    }

    static JSONObject readConfig(Context ctx) {
        File dir = Config.dir(ctx);
        File file = dir != null ? new File(dir, Config.FILE_NAME) : null;
        if (file == null || !file.isFile()) return null;
        try (InputStream in = new FileInputStream(file)) {
            return new JSONObject(read(in));
        } catch (Exception e) {
            return null;
        }
    }

    private static String read(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
