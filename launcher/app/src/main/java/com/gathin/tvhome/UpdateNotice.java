package com.gathin.tvhome;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Date;

/**
 * "New version installed": the first time Home opens after this launcher was updated (an update on every commit, the
 * setup app's "Update the current pack", or a fresh install), a glass strip at the top says so, with the time of the
 * update, for a few seconds. The update time Android keeps for the package is remembered, so it shows once per update.
 */
final class UpdateNotice {
    private static final String PREFS = "update_notice";
    private static final String SEEN = "seen_update_time";
    private static final long SHOW_MS = 6000;

    private UpdateNotice() {}

    /** Shows the strip over Home's root when this launcher was updated since the last time; remembers it either way. */
    static void showIfUpdated(Activity activity, FrameLayout root) {
        long updated;
        try {
            updated = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).lastUpdateTime;
        } catch (Exception e) {
            return;
        }
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        if (prefs.getLong(SEEN, 0) == updated) return;
        prefs.edit().putLong(SEEN, updated).apply();
        root.postDelayed(() -> show(activity, root, updated), 1200);
    }

    private static void show(Activity activity, FrameLayout root, long updated) {
        if (activity.isFinishing() || !root.isAttachedToWindow()) return;
        float d = activity.getResources().getDisplayMetrics().density;
        LinearLayout pill = new LinearLayout(activity);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(Math.round(18 * d), Math.round(10 * d), Math.round(22 * d), Math.round(10 * d));
        GradientDrawable glass = new GradientDrawable();
        glass.setCornerRadius(40 * d);
        glass.setColor(0xF51C1C22);   // nearly solid: the greeting line under it must not show through
        glass.setStroke(Math.max(1, Math.round(d)), 0x33FFFFFF);
        pill.setBackground(glass);
        pill.setElevation(10 * d);
        pill.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);

        ImageView check = new ImageView(activity);
        check.setImageResource(R.drawable.ic_check);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(0xFF30D158);
        check.setBackground(dot);
        int pad = Math.round(4 * d);
        check.setPadding(pad, pad, pad, pad);
        pill.addView(check, new LinearLayout.LayoutParams(Math.round(26 * d), Math.round(26 * d)));

        TextView text = new TextView(activity);
        String time = DateFormat.getTimeFormat(activity).format(new Date(updated));
        text.setText(activity.getString(R.string.update_notice, time));
        text.setTextColor(Color.WHITE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        text.setTypeface(UiFont.get(activity, R.string.font_medium));
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        textLp.leftMargin = Math.round(12 * d);
        pill.addView(text, textLp);
        pill.setContentDescription(text.getText());

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = Math.round(26 * d);
        root.addView(pill, lp);
        HebrewFont.apply(pill);
        pill.setAlpha(0f);
        pill.setTranslationY(-14 * d);
        pill.animate().alpha(1f).translationY(0).setDuration(260).setInterpolator(Motion.ENTER).start();
        pill.postDelayed(() -> pill.animate().alpha(0f).translationY(-14 * d).setDuration(300)
                .setInterpolator(Motion.EXIT).withEndAction(() -> root.removeView(pill)).start(), SHOW_MS);
    }
}
