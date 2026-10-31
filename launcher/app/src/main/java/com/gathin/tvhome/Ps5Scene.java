package com.gathin.tvhome;

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
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The ps5_home screensaver: Home's picture slowly pushing in, shaded towards the text side, with a large thin clock,
 * the date and a "press any button" line in the corner, as a console's idle screen. The zoom is one scale animation
 * on a GPU layer: no redraw of the picture itself.
 */
final class Ps5Scene implements DreamScene {
    private static final long ZOOM_MS = 150_000;

    private final Context ctx;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final FrameLayout root;
    private final ImageView photo;
    private final LinearLayout text;
    private final int width, height;
    private boolean running;

    Ps5Scene(Context ctx) {
        this.ctx = ctx;
        DisplayMetrics screen = new DisplayMetrics();
        ((WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(screen);
        width = screen.widthPixels;
        height = screen.heightPixels;
        float density = ctx.getResources().getDisplayMetrics().density;
        boolean hebrew = HebrewFont.hebrew(ctx);

        root = new FrameLayout(ctx);
        // Without a picture: the console's deep blue.
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TR_BL, new int[]{0xFF0E3C9A, 0xFF020A1E}));
        photo = new ImageView(ctx);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        photo.setAlpha(0f);
        root.addView(photo, match());
        View shade = new View(ctx);
        shade.setBackground(new GradientDrawable(hebrew ? GradientDrawable.Orientation.RIGHT_LEFT
                : GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xCC020A1E, 0x66020A1E, 0x00020A1E}));
        root.addView(shade, match());
        View foot = new View(ctx);
        foot.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xB3020A1E, 0x00020A1E}));
        root.addView(foot, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height / 2, Gravity.BOTTOM));

        text = new LinearLayout(ctx);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setGravity(hebrew ? Gravity.RIGHT : Gravity.LEFT);
        text.setAlpha(0f);
        TextClock clock = new TextClock(ctx);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("H:mm");
        clock.setTextColor(0xFFFFFFFF);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, height * 0.16f);
        clock.setTypeface(UiFont.get(ctx, R.string.font_light));
        clock.setIncludeFontPadding(false);
        clock.setLetterSpacing(-0.02f);
        clock.setGravity(hebrew ? Gravity.RIGHT : Gravity.LEFT);
        // Full width of the block: a wrapped width kept the minute it was measured at and clipped wider ones.
        text.addView(clock, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        TextClock date = new TextClock(ctx);
        String pattern = DateFormat.getBestDateTimePattern(ctx.getResources().getConfiguration().getLocales().get(0), "EEEEdMMMM");
        date.setFormat12Hour(pattern);
        date.setFormat24Hour(pattern);
        date.setTextColor(0xD9FFFFFF);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        date.setTypeface(UiFont.get(ctx, R.string.font_regular));
        LinearLayout.LayoutParams dateLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        dateLp.topMargin = Math.round(6 * density);
        text.addView(date, dateLp);
        TextView hint = new TextView(ctx);
        hint.setText(R.string.ps5_wake);
        hint.setTextColor(0x99FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        hint.setTypeface(UiFont.get(ctx, R.string.font_regular));
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = Math.round(22 * density);
        text.addView(hint, hintLp);
        FrameLayout.LayoutParams textLp = new FrameLayout.LayoutParams(Math.round(width * 0.6f),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | (hebrew ? Gravity.RIGHT : Gravity.LEFT));
        textLp.leftMargin = textLp.rightMargin = Math.round(72 * density);
        textLp.bottomMargin = Math.round(64 * density);
        root.addView(text, textLp);
        HebrewFont.apply(root);
    }

    View view() {
        return root;
    }

    @Override
    public void start() {
        running = true;
        text.animate().alpha(1f).setDuration(1200).start();
        File file = homePicture();
        if (file == null) return;
        loader.execute(() -> {
            Bitmap bmp = WallpaperPlayer.decode(file, width, height, Bitmap.Config.ARGB_8888, 0);
            handler.post(() -> {
                if (!running || bmp == null) return;
                photo.setImageBitmap(bmp);
                photo.animate().alpha(1f).setDuration(1400).start();
                zoom(true);
            });
        });
    }

    /** In, then back out, a few percent over minutes. */
    private void zoom(boolean in) {
        if (!running) return;
        float to = in ? 1.08f : 1f;
        photo.animate().scaleX(to).scaleY(to).setDuration(ZOOM_MS).setInterpolator(new LinearInterpolator())
                .withLayer().withEndAction(() -> zoom(!in)).start();
    }

    @Override
    public void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        photo.animate().cancel();
        text.animate().cancel();
    }

    /** The picture Home is showing (WallpaperPlayer records it, same process), else the theme's first. */
    private File homePicture() {
        Config cfg;
        try {
            cfg = Config.load(ctx);
        } catch (RuntimeException e) {
            return null;
        }
        List<File> files = cfg.wallpaperEnabled ? new ArrayList<>(cfg.wallpapers) : new ArrayList<>();
        if (files.isEmpty()) return null;
        String shown = ctx.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE).getString("image", "");
        File current = WallpaperRotation.find(files, shown);
        return current != null ? current : files.get(0);
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }
}
