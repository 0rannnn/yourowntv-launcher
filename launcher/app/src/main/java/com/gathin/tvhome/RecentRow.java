package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The theme's recent_apps: up to three glass cards just above the dock for the apps Home opened last, newest first,
 * each with the app's banner, its name and when it was opened (LastOpened). Android keeps the real "continue
 * watching" list to itself, so this is what Home can know on its own: no permission, nothing read from other apps.
 */
final class RecentRow {
    private static final int MAX = 3;
    private static final float CARD_W_DP = 146f, ART_H_DP = 82f;

    private final Activity activity;
    private final View grid;
    private final LinearLayout row;
    private final float density;
    /** Where Up and Down from a card go: the top-right buttons, and back to the dock. */
    private final Runnable up, down;

    RecentRow(Activity activity, ViewGroup root, View grid, Runnable up, Runnable down) {
        this.activity = activity;
        this.grid = grid;
        this.up = up;
        this.down = down;
        density = activity.getResources().getDisplayMetrics().density;
        row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        root.addView(row, lp);
        // The row's foot sits just over the dock, wherever the grid has put the dock this time.
        View.OnLayoutChangeListener place = (v, l, t, r, b, ol, ot, or, ob) -> place();
        grid.addOnLayoutChangeListener(place);
        grid.getViewTreeObserver().addOnGlobalLayoutListener(this::place);
        row.addOnLayoutChangeListener(place);
    }

    private void place() {
        // Measured from the first dock tile itself: the grid's padding moves it a frame after the first layout.
        if (!(grid instanceof ViewGroup) || ((ViewGroup) grid).getChildCount() == 0) return;
        View tile = ((ViewGroup) grid).getChildAt(0).findViewById(R.id.card);
        if (tile == null) return;
        // Both in window coordinates: the grid sits inside the header's column, the row straight in the root.
        int[] at = new int[2];
        tile.getLocationInWindow(at);
        float tileTop = at[1] - tile.getTranslationY();
        row.setTranslationY(tileTop - 18 * density - row.getHeight() - row.getTop());
    }

    /** Focus on the newest card; false when the row is empty or hidden. */
    boolean focus() {
        if (row.getVisibility() != View.VISIBLE || row.getChildCount() == 0) return false;
        View newest = row.getChildAt(HebrewFont.hebrew(activity) ? row.getChildCount() - 1 : 0);
        return newest.requestFocus();
    }

    /** Focus on the card straight above {@code from} (a dock tile), or the nearest one to its side. */
    boolean focusNear(View from) {
        if (from == null) return focus();
        if (row.getVisibility() != View.VISIBLE || row.getChildCount() == 0) return false;
        int[] at = new int[2];
        from.getLocationInWindow(at);
        float x = at[0] + from.getWidth() / 2f;
        View best = null;
        float bestGap = Float.MAX_VALUE;
        for (int i = 0; i < row.getChildCount(); i++) {
            View card = row.getChildAt(i);
            card.getLocationInWindow(at);
            float gap = Math.abs(at[0] + card.getWidth() / 2f - x);
            if (gap < bestGap) {
                bestGap = gap;
                best = card;
            }
        }
        return best != null && best.requestFocus();
    }

    /** Home came up: the cards again, an app opened since then now first. */
    void refresh() {
        row.removeAllViews();
        PackageManager pm = activity.getPackageManager();
        SharedPreferences prefs = activity.getSharedPreferences("last_opened", Activity.MODE_PRIVATE);
        List<Map.Entry<String, ?>> opened = new ArrayList<>(prefs.getAll().entrySet());
        opened.removeIf(e -> !(e.getValue() instanceof Long));
        opened.sort((a, b) -> Long.compare((Long) b.getValue(), (Long) a.getValue()));
        int shown = 0;
        for (Map.Entry<String, ?> e : opened) {
            if (shown == MAX) break;
            String pkg = e.getKey();
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
            if (launch == null) continue;
            View card = card(pm, pkg, launch, (Long) e.getValue());
            if (card == null) continue;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    Math.round(CARD_W_DP * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            // Hebrew reads from the right: the newest card there, the others to its left.
            boolean hebrew = HebrewFont.hebrew(activity);
            if (shown > 0) {
                if (hebrew) lp.rightMargin = Math.round(20 * density);
                else lp.leftMargin = Math.round(20 * density);
            }
            if (hebrew) {
                ((TextView) card.findViewWithTag("name")).setGravity(Gravity.RIGHT);
                ((TextView) card.findViewWithTag("ago")).setGravity(Gravity.RIGHT);
                row.addView(card, 0, lp);
            } else {
                row.addView(card, lp);
            }
            shown++;
        }
        row.setVisibility(shown > 0 ? View.VISIBLE : View.GONE);
        HebrewFont.apply(row);
    }

    private View card(PackageManager pm, String pkg, Intent launch, long when) {
        ApplicationInfo app;
        try {
            app = pm.getApplicationInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException gone) {
            return null;
        }
        float radius = 18 * density;
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setFocusable(true);
        card.setClickable(true);
        int pad = Math.round(6 * density);
        card.setPadding(pad, pad, pad, Math.round(9 * density));
        GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {activity.getColor(R.color.glass_top), activity.getColor(R.color.glass_bottom)});
        glass.setCornerRadius(radius);
        glass.setAlpha(HomeToggles.surfaceAlpha(HomeToggles.glass(activity)));
        glass.setStroke(Math.max(1, Math.round(density)), activity.getColor(R.color.glass_edge));
        card.setBackground(glass);

        ImageView art = new ImageView(activity);
        Drawable banner = app.banner != 0 ? pm.getApplicationBanner(app) : null;
        if (banner == null && launch.getComponent() != null) {
            try {
                banner = pm.getActivityBanner(launch.getComponent());
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        if (banner != null) {
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setImageDrawable(banner);
        } else {
            art.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            art.setImageDrawable(pm.getApplicationIcon(app));
            int inset = Math.round(26 * density);
            art.setPadding(inset, inset, inset, inset);
            art.setBackgroundColor(activity.getColor(R.color.control_fill));
        }
        float artRadius = radius - pad;
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), artRadius);
            }
        });
        art.setClipToOutline(true);
        card.addView(art, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.round(ART_H_DP * density - 2 * pad)));

        TextView name = text(String.valueOf(pm.getApplicationLabel(app)), 14, R.color.text_primary);
        name.setTag("name");
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.topMargin = Math.round(7 * density);
        nameLp.leftMargin = nameLp.rightMargin = Math.round(4 * density);
        card.addView(name, nameLp);
        TextView ago = text(LastOpened.describe(activity, when), 11, R.color.text_secondary);
        ago.setTag("ago");
        LinearLayout.LayoutParams agoLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        agoLp.topMargin = Math.round(2 * density);
        agoLp.leftMargin = agoLp.rightMargin = Math.round(4 * density);
        card.addView(ago, agoLp);

        int scale = activity.getResources().getInteger(R.integer.focus_scale_percent);
        int ms = activity.getResources().getInteger(R.integer.motion_fast_ms);
        // Focused, the card turns solid white with dark text, as the quick panel's focus does: a brighter rim alone
        // was hard to see over a photo.
        int glassAlpha = glass.getAlpha();
        card.setOnFocusChangeListener((v, focused) -> {
            float s = focused ? scale / 100f + 0.02f : 1f;
            v.animate().scaleX(s).scaleY(s).setDuration(ms).start();
            if (focused) {
                int white = activity.getColor(R.color.focus_fill);
                glass.setColors(new int[] {white, white});
                glass.setAlpha(255);
            } else {
                glass.setColors(new int[] {activity.getColor(R.color.glass_top), activity.getColor(R.color.glass_bottom)});
                glass.setAlpha(glassAlpha);
            }
            glass.setStroke(Math.max(1, Math.round(density)),
                    focused ? activity.getColor(R.color.focus_fill) : activity.getColor(R.color.glass_edge));
            name.setTextColor(activity.getColor(focused ? R.color.text_on_focus : R.color.text_primary));
            ago.setTextColor(focused ? 0xB3101014 : activity.getColor(R.color.text_secondary));
        });
        card.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
            int at = row.indexOfChild(v);
            switch (keyCode) {
                case android.view.KeyEvent.KEYCODE_DPAD_UP:
                    up.run();
                    return true;
                case android.view.KeyEvent.KEYCODE_DPAD_DOWN:
                    down.run();
                    return true;
                case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
                    if (at > 0) row.getChildAt(at - 1).requestFocus();
                    return true;
                case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (at + 1 < row.getChildCount()) row.getChildAt(at + 1).requestFocus();
                    return true;
                default:
                    return false;
            }
        });
        card.setOnClickListener(v -> {
            try {
                activity.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
                LastOpened.record(activity, pkg);
            } catch (RuntimeException ignored) {
            }
        });
        return card;
    }

    private TextView text(String value, int sp, int color) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        text.setTextColor(activity.getColor(color));
        text.setTypeface(activity.getResources().getFont(R.font.outfit_regular));
        text.setSingleLine(true);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.setIncludeFontPadding(false);
        return text;
    }
}
