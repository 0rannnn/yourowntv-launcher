package com.gathin.tvhome;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The ds_home screensaver: Home's own background (the striped blue, or whichever the panel picked) with the handheld's
 * two screens drawn as white rounded panels, the clock on the upper one and the date on the lower, gently floating up
 * and down, with the accent's corner brackets breathing round the upper screen. One translation and one alpha
 * animation on GPU layers; the clock redraws once a minute.
 */
final class DsScene implements DreamScene {
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** One loader thread for every instance: a new Home or screensaver must not start one more. */
    private static final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Context ctx;
    private final FrameLayout root;
    private final ImageView back;
    private final LinearLayout device;
    private final FrameLayout brackets;
    private final int width, height;
    private ObjectAnimator floatAnim, breathe;
    private boolean running;

    DsScene(Context ctx) {
        this.ctx = ctx;
        DisplayMetrics screen = new DisplayMetrics();
        ((WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(screen);
        width = screen.widthPixels;
        height = screen.heightPixels;
        float density = ctx.getResources().getDisplayMetrics().density;
        DsHome.load(ctx);

        root = new FrameLayout(ctx);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF4FA3F0, 0xFF1F64C8}));
        back = new ImageView(ctx);
        back.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(back, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        device = new LinearLayout(ctx);
        device.setOrientation(LinearLayout.VERTICAL);
        device.setGravity(Gravity.CENTER_HORIZONTAL);
        device.setAlpha(0f);
        int topW = Math.round(height * 0.62f), topH = Math.round(height * 0.37f);
        FrameLayout upper = panel(ctx, density);
        TextClock clock = new TextClock(ctx);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("H:mm");
        clock.setTextColor(0xFF3A3A3A);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, topH * 0.5f);
        clock.setTypeface(UiFont.get(ctx, R.string.font_medium));
        clock.setIncludeFontPadding(false);
        clock.setGravity(Gravity.CENTER);
        upper.addView(clock, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        brackets = new FrameLayout(ctx);
        int arm = Math.round(26 * density), stroke = Math.round(5 * density);
        int[] corners = {Gravity.TOP | Gravity.LEFT, Gravity.TOP | Gravity.RIGHT, Gravity.BOTTOM | Gravity.LEFT, Gravity.BOTTOM | Gravity.RIGHT};
        for (int corner : corners) {
            for (boolean across : new boolean[]{true, false}) {
                View line = new View(ctx);
                GradientDrawable fill = new GradientDrawable();
                fill.setCornerRadius(stroke / 2f);
                fill.setColor(DsHome.accent);
                line.setBackground(fill);
                brackets.addView(line, new FrameLayout.LayoutParams(across ? arm : stroke, across ? stroke : arm, corner));
            }
        }
        int gap = Math.round(14 * density);
        FrameLayout upperWrap = new FrameLayout(ctx);
        upperWrap.addView(upper, new FrameLayout.LayoutParams(topW, topH, Gravity.CENTER));
        upperWrap.addView(brackets, new FrameLayout.LayoutParams(topW + 2 * gap, topH + 2 * gap, Gravity.CENTER));
        device.addView(upperWrap, new LinearLayout.LayoutParams(topW + 2 * gap, topH + 2 * gap));

        FrameLayout lower = panel(ctx, density);
        TextClock date = new TextClock(ctx);
        String pattern = DateFormat.getBestDateTimePattern(ctx.getResources().getConfiguration().getLocales().get(0), "EEEEdMMMM");
        date.setFormat12Hour(pattern);
        date.setFormat24Hour(pattern);
        date.setTextColor(0xFF5A5A5A);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
        date.setTypeface(UiFont.get(ctx, R.string.font_regular));
        date.setGravity(Gravity.CENTER);
        lower.addView(date, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams lowerLp = new LinearLayout.LayoutParams(Math.round(topW * 0.82f), Math.round(topH * 0.5f));
        lowerLp.topMargin = Math.round(10 * density);
        device.addView(lower, lowerLp);
        root.addView(device, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        HebrewFont.apply(root);
    }

    private static FrameLayout panel(Context ctx, float density) {
        FrameLayout p = new FrameLayout(ctx);
        GradientDrawable sheet = new GradientDrawable();
        sheet.setCornerRadius(18 * density);
        sheet.setColor(0xF2FFFFFF);
        sheet.setStroke(Math.round(3 * density), 0xFFFFFFFF);
        p.setBackground(sheet);
        p.setElevation(6 * density);
        return p;
    }

    View view() {
        return root;
    }

    @Override
    public void start() {
        running = true;
        device.animate().alpha(1f).setDuration(1200).start();
        device.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        float drift = height * 0.04f;
        floatAnim = ObjectAnimator.ofFloat(device, "translationY", -drift, drift);
        floatAnim.setDuration(9000);
        floatAnim.setInterpolator(new AccelerateDecelerateInterpolator());
        floatAnim.setRepeatMode(ValueAnimator.REVERSE);
        floatAnim.setRepeatCount(ValueAnimator.INFINITE);
        floatAnim.start();
        breathe = ObjectAnimator.ofFloat(brackets, "alpha", 1f, 0.35f);
        breathe.setDuration(900);
        breathe.setRepeatMode(ValueAnimator.REVERSE);
        breathe.setRepeatCount(ValueAnimator.INFINITE);
        breathe.start();
        File picture = homePicture();
        if (picture == null) return;
        loader.execute(() -> {
            Bitmap bmp = WallpaperPlayer.decode(picture, width, height, Bitmap.Config.ARGB_8888, 0);
            handler.post(() -> {
                if (running && bmp != null) back.setImageBitmap(bmp);
            });
        });
    }

    @Override
    public void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (floatAnim != null) floatAnim.cancel();
        if (breathe != null) breathe.cancel();
        device.animate().cancel();
        device.setLayerType(View.LAYER_TYPE_NONE, null);
    }

    /** Home's background as it is now: the panel's pick, else the theme's single wallpaper. */
    private File homePicture() {
        try {
            Config cfg = Config.load(ctx);
            if (!cfg.wallpaperEnabled || cfg.wallpapers.isEmpty()) return null;
            return cfg.wallpapers.get(0);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
