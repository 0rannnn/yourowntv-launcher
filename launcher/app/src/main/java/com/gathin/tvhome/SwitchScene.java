package com.gathin.tvhome;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
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

import org.json.JSONObject;

/**
 * The switch_home screensaver: the theme's light (or switch_dark black) page with a large clock and the date in the
 * middle, and four dots in the controllers' colours slowly circling it. The circle is one rotation of a small view
 * on a GPU layer; the clock redraws once a minute.
 */
final class SwitchScene implements DreamScene {
    private static final long TURN_MS = 48_000;

    private final FrameLayout root;
    private final FrameLayout orbit;
    private final LinearLayout center;
    private ObjectAnimator turn;

    SwitchScene(Context ctx) {
        DisplayMetrics screen = new DisplayMetrics();
        ((WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(screen);
        int height = screen.heightPixels;
        float density = ctx.getResources().getDisplayMetrics().density;
        JSONObject cfg = HeaderExtras.readConfig(ctx);
        boolean dark = cfg != null && cfg.optBoolean("switch_dark", false);
        int ink = dark ? 0xFFFFFFFF : 0xFF2B2B2B;

        root = new FrameLayout(ctx);
        root.setBackgroundColor(dark ? 0xFF2D2D2D : 0xFFEBEBEB);

        // Four dots on a ring around the clock: red, blue, green, orange.
        int ring = Math.round(height * 0.62f);
        orbit = new FrameLayout(ctx);
        orbit.setAlpha(0f);
        int[] colors = {0xFFE60012, 0xFF0AB9E6, 0xFF2EB84B, 0xFFF28C00};
        int dot = Math.round(26 * density);
        int[][] at = {{Gravity.TOP | Gravity.CENTER_HORIZONTAL}, {Gravity.RIGHT | Gravity.CENTER_VERTICAL},
                {Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL}, {Gravity.LEFT | Gravity.CENTER_VERTICAL}};
        for (int i = 0; i < 4; i++) {
            View v = new View(ctx);
            GradientDrawable fill = new GradientDrawable();
            fill.setShape(GradientDrawable.OVAL);
            fill.setColor(colors[i]);
            v.setBackground(fill);
            orbit.addView(v, new FrameLayout.LayoutParams(dot, dot, at[i][0]));
        }
        View track = new View(ctx);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setStroke(Math.max(1, Math.round(2 * density)), dark ? 0x33FFFFFF : 0x1A000000);
        track.setBackground(circle);
        FrameLayout.LayoutParams trackLp = new FrameLayout.LayoutParams(ring - dot, ring - dot, Gravity.CENTER);
        orbit.addView(track, 0, trackLp);
        root.addView(orbit, new FrameLayout.LayoutParams(ring, ring, Gravity.CENTER));

        center = new LinearLayout(ctx);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER_HORIZONTAL);
        center.setAlpha(0f);
        TextClock clock = new TextClock(ctx);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("H:mm");
        clock.setTextColor(ink);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, height * 0.15f);
        clock.setTypeface(UiFont.get(ctx, R.string.font_medium));
        clock.setIncludeFontPadding(false);
        clock.setGravity(Gravity.CENTER_HORIZONTAL);
        // Full width: a wrapped width kept the minute it was measured at and clipped the next one's digits.
        center.addView(clock, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        TextClock date = new TextClock(ctx);
        String pattern = DateFormat.getBestDateTimePattern(ctx.getResources().getConfiguration().getLocales().get(0), "EEEEdMMMM");
        date.setFormat12Hour(pattern);
        date.setFormat24Hour(pattern);
        date.setTextColor(dark ? 0xB3FFFFFF : 0xFF6B6B6B);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        date.setTypeface(UiFont.get(ctx, R.string.font_regular));
        LinearLayout.LayoutParams dateLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        dateLp.topMargin = Math.round(8 * density);
        center.addView(date, dateLp);
        root.addView(center, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        ImageView pads = new ImageView(ctx);
        pads.setImageResource(R.drawable.ic_joycons);
        pads.setImageTintList(ColorStateList.valueOf(dark ? 0x66FFFFFF : 0x552B2B2B));
        int size = Math.round(34 * density);
        FrameLayout.LayoutParams padsLp = new FrameLayout.LayoutParams(size, size, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        padsLp.bottomMargin = Math.round(36 * density);
        root.addView(pads, padsLp);
        HebrewFont.apply(root);
    }

    View view() {
        return root;
    }

    @Override
    public void start() {
        center.animate().alpha(1f).setDuration(1200).start();
        orbit.animate().alpha(1f).setDuration(1600).start();
        orbit.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        if (turn != null) turn.cancel();
        turn = ObjectAnimator.ofFloat(orbit, "rotation", 0f, 360f);
        turn.setDuration(TURN_MS);
        turn.setInterpolator(new LinearInterpolator());
        turn.setRepeatCount(ValueAnimator.INFINITE);
        turn.start();
    }

    @Override
    public void stop() {
        if (turn != null) turn.cancel();
        orbit.animate().cancel();
        center.animate().cancel();
        orbit.setLayerType(View.LAYER_TYPE_NONE, null);
    }
}
