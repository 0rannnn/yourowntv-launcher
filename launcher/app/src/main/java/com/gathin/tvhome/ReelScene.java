package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The Reel screensaver: Home's photo with nothing in front of it but the glass clock, standing behind the photo's
 * cut-out, and the date and weather line over the digits. The photo moves on with Home's rotation, fading through
 * black. Nothing animates between changes, so the screen is redrawn only once a minute (the clock).
 */
final class ReelScene implements DreamScene {
    /** As Home's centred clock (HeaderExtras): the digits' tops at this share of the screen height. */
    private static final float CLOCK_TOP = 0.12f;
    private static final long FADE_OUT_MS = 900;
    private static final long FADE_IN_MS = 1400;

    private final Context ctx;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final FrameLayout root;
    private final FrameLayout stage;
    private final ImageView photo;
    private final ImageView cutOut;
    private final int width, height;
    private final Bitmap.Config bitmapConfig;
    private final int dimColor;
    private final List<File> files;
    private final long rotationMs;
    private final String order;
    private final java.util.Map<String, java.util.Set<String>> moods;
    private File shown;
    private Bitmap currentPhoto, currentCutOut;
    /** One number per decode: a result whose number is no longer current is dropped. Main thread only. */
    private int generation;

    private final Runnable next = new Runnable() {
        @Override
        public void run() {
            advance();
            handler.postDelayed(this, rotationMs);
        }
    };

    ReelScene(Context ctx) {
        this.ctx = ctx;
        Config cfg = Config.load(ctx);
        DisplayMetrics screen = new DisplayMetrics();
        ((WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(screen);
        width = screen.widthPixels;
        height = screen.heightPixels;
        float density = ctx.getResources().getDisplayMetrics().density;
        bitmapConfig = cfg.wallpaperTrueColor ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        dimColor = cfg.wallpaperDim > 0 ? (cfg.wallpaperDim * 255 / 100) << 24 : 0;
        files = cfg.wallpaperEnabled ? new ArrayList<>(cfg.wallpapers) : new ArrayList<>();
        rotationMs = Math.max(1, cfg.wallpaperRotationMinutes) * 60_000L;
        order = cfg.wallpaperOrder;
        moods = cfg.wallpaperMoods;

        root = new FrameLayout(ctx);
        root.setBackgroundColor(Color.BLACK);
        stage = new FrameLayout(ctx);
        stage.setAlpha(0f);
        root.addView(stage, match());

        photo = image();
        stage.addView(photo, match());

        JSONObject json = HeaderExtras.readConfig(ctx);
        float size = json != null ? (float) json.optDouble("center_clock_size", 0.36) : 0.36f;
        if (size <= 0.05f || size >= 0.6f) size = 0.36f;
        HeaderExtras.GlassClock clock = new HeaderExtras.GlassClock(ctx);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("H:mm");
        clock.setIncludeFontPadding(false);
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * height);
        HeaderExtras.applyClockFont(ctx, clock);
        clock.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        Paint paint = clock.getPaint();
        Rect digits = new Rect();
        paint.getTextBounds("0123456789", 0, 10, digits);
        float inset = -paint.getFontMetrics().ascent + digits.top;
        float glyphTop = CLOCK_TOP * height;
        this.clock = clock;
        glassTop = inset;
        glassBottom = inset + digits.height();
        glass(0f);
        FrameLayout.LayoutParams clockLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        clockLp.topMargin = Math.round(glyphTop - inset);
        stage.addView(clock, clockLp);

        LinearLayout line = infoLine(density);
        FrameLayout.LayoutParams lineLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        int gap = Math.round(8 * density);
        line.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                v.setTranslationY(glyphTop - gap - v.getHeight()));
        stage.addView(line, lineLp);

        // Over the clock, so the landscape stands in front of the time; the panel's clock-depth switch turns it off.
        cutOut = image();
        if (dimColor != 0) cutOut.setColorFilter(dimColor, android.graphics.PorterDuff.Mode.SRC_ATOP);
        cutOut.setVisibility(HomeToggles.clockBehind(ctx) ? View.VISIBLE : View.GONE);
        stage.addView(cutOut, match());
        HebrewFont.apply(line);
    }

    View view() {
        return root;
    }

    private HeaderExtras.GlassClock clock;
    private float glassTop, glassBottom;

    /** The glass for the slider's level, firmed up over a dark or busy photo as on Home. */
    private void glass(float solidity) {
        HeaderExtras.applyGlass(clock, HomeToggles.glass(ctx), ctx.getResources().getDisplayMetrics().density,
                glassTop, glassBottom, solidity);
    }

    @Override
    public void start() {
        handler.removeCallbacksAndMessages(null);
        if (files.isEmpty()) {
            stage.animate().alpha(1f).setDuration(FADE_IN_MS).start();
            return;
        }
        // Start on the picture Home is showing (WallpaperPlayer records it; same process, so just a read).
        String onHome = prefs().getString("image", "");
        File first = WallpaperRotation.find(files, onHome);
        load(first != null ? first : WallpaperRotation.pick(playable(), onHome, order, random));
        handler.postDelayed(next, rotationMs);
    }

    @Override
    public void stop() {
        handler.removeCallbacksAndMessages(null);
        stage.animate().cancel();
        generation++;   // a decode still running must not attach its picture to a stopped scene
    }

    private void advance() {
        if (files.size() < 2 || shown == null) return;
        File pick = WallpaperRotation.pick(playable(), shown.getName(), order, random);
        if (pick == null || pick.equals(shown)) return;
        load(pick);
    }

    /** Decodes off the main thread, then fades the stage out, swaps the picture and fades back in. */
    private void load(File file) {
        if (file == null) return;
        int ticket = ++generation;
        new Thread(() -> {
            Bitmap picture = WallpaperPlayer.decode(file, width, height, bitmapConfig, dimColor);
            Bitmap front = picture == null ? null : WallpaperPlayer.decodeForeground(file, width, height);
            handler.post(() -> {
                if (ticket != generation || picture == null) {
                    if (picture != null) picture.recycle();
                    if (front != null) front.recycle();
                    if (picture == null && ticket == generation) {
                        files.remove(file);   // unreadable: the next turn picks another
                        if (shown == null) stage.animate().alpha(1f).setDuration(FADE_IN_MS).start();
                    }
                    return;
                }
                Runnable swap = () -> {
                    if (ticket != generation) return;
                    Bitmap oldPhoto = currentPhoto, oldCut = currentCutOut;
                    currentPhoto = picture;
                    currentCutOut = front;
                    photo.setImageBitmap(picture);
                    cutOut.setImageBitmap(front);
                    shown = file;
                    glass(WallpaperPlayer.solidity(file));
                    if (oldPhoto != null) photo.postDelayed(oldPhoto::recycle, 500);
                    if (oldCut != null) cutOut.postDelayed(oldCut::recycle, 500);
                    stage.animate().alpha(1f).setDuration(FADE_IN_MS).withLayer().start();
                };
                if (stage.getAlpha() == 0f) {
                    swap.run();
                } else {
                    stage.animate().alpha(0f).setDuration(FADE_OUT_MS).withLayer().withEndAction(swap).start();
                }
            });
        }, "reel-dream").start();
    }

    /** The date and, when Home has a reading, the weather, as Home's line over the clock. */
    private LinearLayout infoLine(float density) {
        LinearLayout line = new LinearLayout(ctx);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        Locale locale = ctx.getResources().getConfiguration().getLocales().get(0);
        List<View> parts = new ArrayList<>();
        TextClock date = new TextClock(ctx);
        style(date);
        String pattern = DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM");
        date.setFormat12Hour(pattern);
        date.setFormat24Hour(pattern);
        parts.add(date);
        // The reading Home last fetched; the screensaver itself never goes online.
        SharedPreferences weather = ctx.getSharedPreferences("header_weather", Context.MODE_PRIVATE);
        int temp = weather.getInt("temp", Integer.MIN_VALUE);
        if (temp != Integer.MIN_VALUE) {
            LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.HORIZONTAL);
            box.setGravity(Gravity.CENTER_VERTICAL);
            ImageView icon = new ImageView(ctx);
            icon.setImageResource(HeaderExtras.icon(weather.getInt("code", -1), weather.getBoolean("day", true)));
            int iconSize = Math.round(19 * density);
            box.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));
            TextView value = new TextView(ctx);
            style(value);
            value.setText(String.format(Locale.ROOT, "%d°", temp));
            LinearLayout.LayoutParams valueLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            valueLp.setMarginStart(Math.round(6 * density));
            box.addView(value, valueLp);
            parts.add(box);
        }
        // The launcher lays out left to right; Hebrew reads the line from the right.
        if ("iw".equals(locale.getLanguage())) Collections.reverse(parts);
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                TextView dot = new TextView(ctx);
                style(dot);
                dot.setText("·");
                LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                dotLp.leftMargin = Math.round(12 * density);
                dotLp.rightMargin = Math.round(12 * density);
                line.addView(dot, dotLp);
            }
            line.addView(parts.get(i));
        }
        return line;
    }

    private void style(TextView text) {
        text.setTextColor(ctx.getColor(R.color.text_primary));
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        text.setTypeface(ctx.getResources().getFont(R.font.outfit_light));
        text.setIncludeFontPadding(false);
        text.setSingleLine(true);
        text.setShadowLayer(10, 0, 1, 0x80000000);
    }

    /** WallpaperPlayer's filter: the pictures wallpaper_moods allows at this hour, or all of them. */
    private List<File> playable() {
        if (moods.isEmpty()) return files;
        LocalDateTime now = LocalDateTime.now();
        int[] sun = WallpaperMood.sun(now.toLocalDate(), ctx.getString(R.string.latitude),
                ctx.getString(R.string.longitude), ZoneId.systemDefault());
        return WallpaperMood.select(files, moods, WallpaperMood.timeOfDay(now.getHour() * 60 + now.getMinute(),
                sun[0], sun[1]));
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE);
    }

    private ImageView image() {
        ImageView view = new ImageView(ctx);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }
}
