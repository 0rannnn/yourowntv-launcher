package com.gathin.tvhome;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The quick panel's pickers (vision_panel): a glass card in place of the panel with a row of pictures to choose from,
 * moved through with Left and Right. Background holds one of the theme's photos on Home (or goes back to the rotation),
 * Theme switches between the themes kept on the TV (Config.themes). Back returns to the panel.
 */
final class PanelPicker {
    private static final int THUMB_W_DP = 208, THUMB_H_DP = 117;

    /** One choice: its name, a picture (or an icon in its place), and whether it is the one in use. */
    static final class Option {
        final String label;
        final File picture;
        final int icon;
        final boolean selected;
        final Runnable pick;

        Option(String label, File picture, int icon, boolean selected, Runnable pick) {
            this.label = label;
            this.picture = picture;
            this.icon = icon;
            this.selected = selected;
            this.pick = pick;
        }
    }

    private final Activity activity;
    private final FrameLayout scrim;
    private final View panel;
    private final float density;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService decoder;
    private View card;
    /** The panel's view that had the focus, to return to. */
    private View opener;

    PanelPicker(Activity activity, FrameLayout scrim, View panel) {
        this.activity = activity;
        this.scrim = scrim;
        this.panel = panel;
        density = activity.getResources().getDisplayMetrics().density;
    }

    boolean isOpen() {
        return card != null;
    }

    void show(String title, List<Option> options) {
        if (card != null || options.isEmpty()) return;
        opener = activity.getCurrentFocus();
        boolean hebrew = HebrewFont.hebrew(activity);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.vision_card);
        if (box.getBackground() != null) {
            box.setBackground(box.getBackground().mutate());
            box.getBackground().setAlpha(HomeToggles.surfaceAlpha(HomeToggles.glass(activity)));
        }
        box.setPadding(dp(22), dp(18), dp(22), dp(20));
        box.setElevation(dp(16));
        box.setGravity(hebrew ? Gravity.RIGHT : Gravity.LEFT);

        TextView heading = new TextView(activity);
        heading.setText(title);
        heading.setTextColor(activity.getColor(R.color.text_primary));
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        heading.setTypeface(activity.getResources().getFont(R.font.outfit_regular));
        LinearLayout.LayoutParams headingLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headingLp.setMargins(dp(4), 0, dp(4), dp(12));
        box.addView(heading, headingLp);

        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        scroll.addView(row);
        box.addView(scroll, new LinearLayout.LayoutParams(
                Math.min(dp(1040), activity.getResources().getDisplayMetrics().widthPixels - dp(120)),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        decoder = Executors.newSingleThreadExecutor();
        View focus = null;
        List<View> tiles = new ArrayList<>();
        for (Option option : options) {
            View tile = tile(option);
            tiles.add(tile);
            if (option.selected || focus == null) focus = tile;
        }
        // Hebrew reads from the right: the first choice at the right end, the row scrolled there.
        if (hebrew) java.util.Collections.reverse(tiles);
        for (View tile : tiles) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(8), 0, dp(8), 0);
            row.addView(tile, lp);
        }
        // A short row sits at the reading side: right in Hebrew. The focused choice scrolls itself into view.
        scroll.setFillViewport(true);
        row.setGravity(hebrew ? Gravity.RIGHT : Gravity.LEFT);

        card = box;
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        scrim.addView(box, cardLp);
        HebrewFont.apply(box);
        panel.animate().alpha(0f).setDuration(160).withEndAction(() -> panel.setVisibility(View.INVISIBLE)).start();
        box.setAlpha(0f);
        box.setScaleX(0.96f);
        box.setScaleY(0.96f);
        box.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260)
                .setInterpolator(new DecelerateInterpolator(2f)).start();
        View first = focus;
        box.post(() -> first.requestFocus());
    }

    void close() {
        if (card == null) return;
        View closing = card;
        card = null;
        if (decoder != null) decoder.shutdownNow();
        closing.animate().alpha(0f).setDuration(160).withEndAction(() -> scrim.removeView(closing)).start();
        panel.setVisibility(View.VISIBLE);
        panel.animate().alpha(1f).setDuration(220).start();
        if (opener != null) opener.requestFocus();
    }

    private View tile(Option option) {
        LinearLayout tile = new LinearLayout(activity);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setContentDescription(option.label);

        float radius = dp(14);
        FrameLayout frame = new FrameLayout(activity);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(radius + dp(4));
        ring.setColor(Color.TRANSPARENT);
        frame.setBackground(ring);
        frame.setPadding(dp(4), dp(4), dp(4), dp(4));
        ImageView picture = new ImageView(activity);
        picture.setScaleType(option.picture != null ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.CENTER);
        picture.setBackgroundColor(activity.getColor(R.color.vision_control));
        if (option.picture == null && option.icon != 0) picture.setImageResource(option.icon);
        picture.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        picture.setClipToOutline(true);
        frame.addView(picture, new FrameLayout.LayoutParams(dp(THUMB_W_DP), dp(THUMB_H_DP)));
        if (option.selected) {
            // The one in use: a blue check in the corner, as the panel's toggles are blue while on.
            ImageView check = new ImageView(activity);
            check.setImageResource(R.drawable.ic_check);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(activity.getColor(R.color.vision_on));
            check.setBackground(dot);
            check.setPadding(dp(4), dp(4), dp(4), dp(4));
            FrameLayout.LayoutParams checkLp = new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP | Gravity.RIGHT);
            checkLp.setMargins(0, dp(8), dp(8), 0);
            frame.addView(check, checkLp);
        }
        // Wrap, not the vertical layout's default match_parent: that sized the frame to the label's width.
        tile.addView(frame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (option.picture != null) thumbnail(option.picture, picture);

        TextView label = new TextView(activity);
        label.setText(option.label);
        label.setTextColor(activity.getColor(R.color.text_secondary));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setTypeface(activity.getResources().getFont(R.font.outfit_regular));
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setMaxWidth(dp(THUMB_W_DP));
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = dp(6);
        tile.addView(label, labelLp);

        int ms = activity.getResources().getInteger(R.integer.motion_fast_ms);
        tile.setOnFocusChangeListener((v, focused) -> {
            // A white ring round the picture, so the picture itself (and Rotate's icon) stays visible.
            ring.setStroke(focused ? dp(3) : 0, focused ? activity.getColor(R.color.focus_fill) : Color.TRANSPARENT);
            label.setTextColor(activity.getColor(focused ? R.color.text_primary : R.color.text_secondary));
            float s = focused ? 1.06f : 1f;
            v.animate().scaleX(s).scaleY(s).setDuration(ms).start();
        });
        // Up and Down have nowhere to go in a single row: keep the focus in it.
        tile.setOnKeyListener((v, keyCode, event) ->
                keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN);
        tile.setOnClickListener(v -> option.pick.run());
        return tile;
    }

    /** Decoded small, off the main thread: a 1080p photo sampled down to about the thumbnail's width. */
    private void thumbnail(File file, ImageView into) {
        int width = dp(THUMB_W_DP);
        decoder.execute(() -> {
            Bitmap bitmap = null;
            try {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getPath(), options);
                int sample = 1;
                while (options.outWidth / (sample * 2) >= width) sample *= 2;
                options.inJustDecodeBounds = false;
                options.inSampleSize = sample;
                options.inPreferredConfig = Bitmap.Config.RGB_565;
                bitmap = BitmapFactory.decodeFile(file.getPath(), options);
            } catch (Exception | OutOfMemoryError ignored) {
                // left as the plain tile
            }
            Bitmap done = bitmap;
            if (done != null) main.post(() -> into.setImageBitmap(done));
        });
    }

    private int dp(int value) {
        return Math.round(value * density);
    }

    // ---- What the pickers list ------------------------------------------------------------------------

    /** Whether the theme in use has a collection to pick from: the panel shows Background only then. */
    static boolean hasWallpapers(Activity activity) {
        File dir = Config.dir(activity);
        return WallpaperRotation.images(dir == null ? null : new File(dir, ThemeFiles.WALLPAPERS)).size() >= 2;
    }

    /** Rotate, then every photo of the theme in use; picking one holds it on Home (Config.WALLPAPER_CHOICE_KEY). */
    static List<Option> wallpapers(Activity activity, Runnable done) {
        List<Option> options = new ArrayList<>();
        File dir = Config.dir(activity);
        List<File> images = WallpaperRotation.images(dir == null ? null : new File(dir, ThemeFiles.WALLPAPERS));
        if (images.size() < 2) return options;
        SharedPreferences choices = activity.getSharedPreferences(Config.SETTINGS_NAME, Activity.MODE_PRIVATE);
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        boolean rotating = WallpaperRotation.find(images, current) == null;
        options.add(new Option(activity.getString(R.string.qs_rotate), null, R.drawable.ic_swap_vert, rotating, () -> {
            choose(choices, null);
            done.run();
        }));
        for (File image : images) {
            options.add(new Option(photoLabel(image.getName()), image, 0, image.getName().equals(current), () -> {
                choose(choices, image.getName());
                done.run();
            }));
        }
        return options;
    }

    /** "03-golden-gate.jpg" as "Golden Gate": the number that orders the collection and the type dropped. */
    static String photoLabel(String file) {
        String name = file.replaceFirst("\\.[A-Za-z]+$", "").replaceFirst("^\\d+[-_ ]*", "").replace('-', ' ').replace('_', ' ');
        StringBuilder label = new StringBuilder();
        for (String word : name.trim().split("\\s+")) {
            if (word.isEmpty()) continue;
            if (label.length() > 0) label.append(' ');
            label.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return label.length() > 0 ? label.toString() : file;
    }

    private static void choose(SharedPreferences choices, String name) {
        SharedPreferences.Editor edit = choices.edit();
        if (name == null) edit.remove(Config.WALLPAPER_CHOICE_KEY);
        else edit.putString(Config.WALLPAPER_CHOICE_KEY, name);
        // Home rebuilds with it when it is next in front (MainActivity.onResume).
        edit.putLong(Config.SETTINGS_REVISION_KEY, choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).commit();
    }

    /** The themes kept on the TV, each shown by its first photo and named by its theme_name (theme_name_he in Hebrew). */
    static List<Option> themes(Activity activity, Runnable done) {
        List<Option> options = new ArrayList<>();
        List<String> names = Config.themes(activity);
        if (names.size() < 2) return options;
        String current = HomeToggles.theme(activity);
        File base = Config.baseDir(activity);
        boolean hebrew = HebrewFont.hebrew(activity);
        for (String name : names) {
            File slot = new File(new File(base, Config.THEMES), name);
            List<File> images = WallpaperRotation.images(new File(slot, ThemeFiles.WALLPAPERS));
            File cover = !images.isEmpty() ? images.get(0) : new File(slot, Config.WALLPAPER_NAME);
            if (!cover.isFile()) cover = null;
            options.add(new Option(themeLabel(slot, name, hebrew), cover, R.drawable.ic_theme, name.equals(current), () -> {
                if (name.equals(current)) {
                    done.run();
                    return;
                }
                HomeToggles.setTheme(activity, name);
                SharedPreferences choices = activity.getSharedPreferences(Config.SETTINGS_NAME, Activity.MODE_PRIVATE);
                choices.edit().putLong(Config.SETTINGS_REVISION_KEY,
                        choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).commit();
                done.run();
            }));
        }
        return options;
    }

    private static String themeLabel(File slot, String name, boolean hebrew) {
        JSONObject json = null;
        try (java.io.InputStream in = new java.io.FileInputStream(new File(slot, Config.FILE_NAME))) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            json = new JSONObject(new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // named by its folder
        }
        String label = json == null ? "" : json.optString(hebrew ? "theme_name_he" : "theme_name", "").trim();
        if (label.isEmpty() && json != null) label = json.optString("theme_name", "").trim();
        return label.isEmpty() ? Character.toUpperCase(name.charAt(0)) + name.substring(1) : label;
    }
}
