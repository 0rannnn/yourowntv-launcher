package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
    }

    /** Once, after setContentView: the clock's typeface and the weather views beside it. */
    void attach(TextView clock) {
        File dir = Config.dir(activity);
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
        if (weather) addWeatherViews(clock);
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

    /** Home came up: show the last reading and fetch a new one when it is due. */
    void onResume() {
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

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setVisibility(View.GONE);
        weatherIcon = new ImageView(activity);
        // Secondary to the clock: smaller, and in the design system's secondary white.
        int iconSize = Math.round(28 * density);
        box.addView(weatherIcon, new LinearLayout.LayoutParams(iconSize, iconSize));
        weatherTemp = new TextView(activity);
        weatherTemp.setTextColor(activity.getColor(R.color.text_primary));
        weatherTemp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        weatherTemp.setTypeface(activity.getResources().getFont(R.font.outfit_light));
        weatherTemp.setIncludeFontPadding(false);
        LinearLayout.LayoutParams tempLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tempLp.setMarginStart(Math.round(6 * density));
        box.addView(weatherTemp, tempLp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        lp.bottomMargin = Math.round(10 * density);
        lp.leftMargin = Math.round(18 * density);
        lp.rightMargin = Math.round(4 * density);
        int dividerAt = row.indexOfChild(divider);
        row.addView(box, row.indexOfChild(clockSide) < dividerAt ? dividerAt : dividerAt + 1, lp);
    }

    private static JSONObject readConfig(Context ctx) {
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
