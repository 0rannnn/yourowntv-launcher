package com.gathin.tvhome;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.util.Log;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.ViewOutlineProvider;
import android.widget.ProgressBar;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The panel the remote's Settings button opens when {@code settings_key_panel} is on (HomeKeyService):
 * a tall glass panel at the right edge, after Google TV's own, with the date and clock, a gear for the full
 * TV settings and a two-column grid of tiles. Translucent and in a task of its own, so the app behind keeps
 * running and Back returns to it. Pressing Settings again closes the panel.
 * <p>
 * With the theme's {@code vision_panel} the same controls come as visionOS does them instead: three light glass cards
 * in the middle of the screen (activity_quick_vision.xml), the clock with what is playing, round toggles that are blue
 * while on, and the paired devices.
 */
public final class QuickSettingsActivity extends Activity {
    private static final String TV_SETTINGS = "com.android.tv.settings";
    private static final String TAG = "QuickSettings";
    /** Open straight on "Edit from the phone" (MainActivity.ACTION_PHONE_EDIT). */
    static final String EXTRA_PHONE_EDIT = "phone_edit";
    private static final String SPOTIFY = "com.spotify.tv.android";
    private static final String SPOTIFY_LIBRARY = "com.spotify.androidtv.medialibraryservice.AndroidTvMediaLibraryService";
    private static final String SPOTIFY_BUTTONS = "androidx.media3.session.MediaButtonReceiver";
    private static final String KEYSTONE_ACTION = "com.fengmi.projector.imaging.action.START_KEYSTONE";
    /** The projector's own automatic keystone: it projects a pattern and squares the picture to the screen's 16:9. */
    private static final String AUTO_KEYSTONE_ACTION = "com.fengmi.projector.imaging.action.START_AUTO_KEYSTONE";
    /** A2DP, headset, HID host (4, hidden constant) and LE audio (22): the profiles a TV's accessories use. */
    private static final int[] PROFILES = {BluetoothProfile.A2DP, BluetoothProfile.HEADSET, 4, 22};

    /** Tile height and the gap around each tile; short enough that the Spotify section peeks out under the tiles. */
    private static final int TILE_DP = 54;
    private static final int GAP_DP = 4;

    /** The rebuilt panel after a language switch puts the focus back on the language button. */
    private static boolean focusLanguage;
    /** This panel is being rebuilt by the language button, not left: onPause must not close it. */
    private boolean relaunching;
    /** Back was pressed and the exit animation is running. */
    private boolean closing;

    /** vision_panel: the centred visionOS cards instead of the right-edge panel. */
    private boolean vision;
    /** The Background and Theme pickers, shown in place of the panel. */
    private PanelPicker picker;
    /** Spotify's session the now-playing card follows, and the tick that moves its progress bar. */
    private MediaController following;
    private MediaController.Callback followCallback;
    private final Handler ticker = new Handler(Looper.getMainLooper());
    private Runnable tick;

    private BluetoothAdapter adapter;
    private final Map<Integer, BluetoothProfile> proxies = new HashMap<>();
    private final Map<String, View> deviceRows = new HashMap<>();

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }


    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        vision = HeaderExtras.themeFlag(this, "vision_panel");
        setContentView(vision ? R.layout.activity_quick_vision : R.layout.activity_quick_settings);
        if (vision) findViewById(R.id.qsClose).setOnClickListener(v -> onBackPressed());
        if (!vision && getResources().getBoolean(R.bool.rtl_menu_bar)) {
            View panel = findViewById(R.id.panel);
            FrameLayout.LayoutParams position = (FrameLayout.LayoutParams) panel.getLayoutParams();
            position.gravity = Gravity.LEFT;
            int inset = position.getMarginEnd();
            position.setMarginEnd(0);
            position.setMargins(inset, position.topMargin, 0, position.bottomMargin);
            panel.setLayoutParams(position);
            findViewById(R.id.scrim).setBackground(new GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT,
                    new int[] {getColor(R.color.scrim_far), 0x40000000, getColor(R.color.scrim_edge)}));
        }
        // Slide in from the panel's own edge, unless this is the panel rebuilding itself in the other language.
        boolean entering = !focusLanguage;
        findViewById(R.id.scrim).setOnClickListener(v -> finish());
        View gear = findViewById(R.id.qsGear);
        gear.setOnClickListener(v -> open(listOf(SystemSettings.intent(this, Settings.ACTION_SETTINGS))));

        GridLayout tiles = findViewById(R.id.tiles);
        // The top-right buttons' Inputs and Reorder, through Home: it owns the inputs list and the tiles. Wi-Fi,
        // Bluetooth and the TV settings gear are here already.
        if (vision) {
            addTile(tiles, R.drawable.ic_input, getString(R.string.inputs), null,
                    listOf(new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_VIEW_INPUTS)));
        }
        addTile(tiles, R.drawable.ic_screensaver, getString(R.string.qs_screensaver), null,
                listOf(new Intent(this, ClockActivity.class)));
        String[] network = network();
        View networkTile = addTile(tiles, network[0].equals(getString(R.string.ethernet)) ? R.drawable.ic_ethernet : R.drawable.ic_wifi,
                network[0], network[1], listOf(SystemSettings.intent(this, Settings.ACTION_WIFI_SETTINGS)));
        // Its icon is Wi-Fi or Ethernet: one key either way, for the arranged order.
        networkTile.setTag(R.id.panel_key, "network");
        // On a visionOS toggle, blue means on: online, and Bluetooth switched on.
        networkTile.setActivated(!network[0].equals(getString(R.string.quick_network)));
        View bluetoothTile = addTile(tiles, R.drawable.ic_bluetooth, getString(R.string.qs_bluetooth), null, bluetoothTargets(this));
        bluetoothTile.setActivated(bluetoothOn());
        addTile(tiles, R.drawable.ic_volume, getString(R.string.qs_sound), null, listOf(
                new Intent("com.android.tv.action.VIEW_SOUND_SETTINGS").setPackage(TV_SETTINGS),
                new Intent(Settings.ACTION_SOUND_SETTINGS).setPackage(TV_SETTINGS),
                new Intent(Settings.ACTION_SOUND_SETTINGS)));
        addTile(tiles, R.drawable.ic_picture, getString(R.string.quick_picture), null,
                listOf(SystemSettings.intent(this, Settings.ACTION_DISPLAY_SETTINGS)));
        // Fengmi / Formovie projectors: the manual keystone screen (Quick Adjustment, Fine Tuning, Reset).
        Intent keystone = new Intent(KEYSTONE_ACTION);
        if (hasKeystone == null) hasKeystone = getPackageManager().resolveActivity(keystone, 0) != null;
        if (hasKeystone) {
            Intent auto = new Intent(AUTO_KEYSTONE_ACTION);
            if (getPackageManager().resolveActivity(auto, 0) == null) {
                addTile(tiles, R.drawable.ic_keystone, getString(R.string.qs_keystone), null, listOf(keystone));
            } else {
                // Two ways: automatic, back to the TV's ratio in one press, or the manual corners.
                View keystoneTile = addTile(tiles, R.drawable.ic_keystone, getString(R.string.qs_keystone), null, null);
                keystoneTile.setOnClickListener(v -> picker.show(getString(R.string.qs_keystone), java.util.Arrays.asList(
                        new PanelPicker.Option(getString(R.string.qs_keystone_auto), null, R.drawable.ic_picture, false,
                                () -> open(listOf(auto))),
                        new PanelPicker.Option(getString(R.string.qs_keystone_manual), null, R.drawable.ic_keystone, false,
                                () -> open(listOf(keystone))))));
            }
        }
        addTile(tiles, R.drawable.ic_apps, getString(R.string.quick_apps), getString(R.string.quick_apps_hint),
                listOf(SystemSettings.intent(this, Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)));
        // Edit from the phone: a QR code and a 4-digit code, in place of the panel (PhoneEditor).
        View phone = addTile(tiles, R.drawable.ic_phone_edit, getString(R.string.qs_phone_edit), null, null);
        phone.setOnClickListener(v -> showPhoneCard());
        // Through Home, so it rebuilds with the new choices when Launcher settings closes.
        addTile(tiles, R.drawable.ic_home_settings, getString(R.string.qs_launcher), null,
                listOf(new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_LAUNCHER_SETTINGS)));
        if (vision) {
            addTile(tiles, R.drawable.ic_swap_vert, getString(R.string.qs_reorder), null,
                    listOf(new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_REORDER)));
        }
        // The pickers: one of the theme's photos held on Home, and the themes kept on the TV. A pick closes the
        // panel; Home rebuilds with it as it comes back in front.
        picker = new PanelPicker(this, findViewById(R.id.scrim), findViewById(R.id.panel));
        if (PanelPicker.hasWallpapers(this)) {
            View background = addTile(tiles, R.drawable.ic_wallpaper, getString(R.string.qs_background), null, null);
            background.setOnClickListener(v -> picker.show(getString(R.string.qs_background),
                    PanelPicker.wallpapers(this, this::closeAfterPick)));
            // The rotation in one press: off holds the photo on screen now, on goes back to rotating.
            addSwitch(tiles, R.drawable.ic_rotate, R.drawable.ic_rotate, R.string.qs_rotating,
                    PanelPicker.rotating(this), on -> PanelPicker.setRotating(this, on));
        }
        if (Config.themes(this).size() >= 2) {
            View theme = addTile(tiles, R.drawable.ic_theme, getString(R.string.qs_theme), null, null);
            theme.setOnClickListener(v -> picker.show(getString(R.string.qs_theme),
                    PanelPicker.themes(this, this::closeAfterPick)));
        }
        // Home's own switches: the clock behind the landscape or in front of it, and the navigation sound.
        if (HeaderExtras.themeFlag(this, "center_clock")) {
            addSwitch(tiles, R.drawable.ic_depth, R.drawable.ic_depth, R.string.qs_clock_depth,
                    HomeToggles.clockBehind(this), on -> HomeToggles.setClockBehind(this, on));
        }
        File dir = Config.dir(this);
        if (dir != null && new File(dir, NavSound.FILE).isFile()) {
            addSwitch(tiles, R.drawable.ic_nav_sound, R.drawable.ic_nav_sound_off, R.string.qs_nav_sound,
                    !HomeToggles.navMuted(this), on -> HomeToggles.setNavMuted(this, !on));
        }
        addSpotify(tiles);
        addPairedDevices(tiles);

        // Language: switches the launcher between Hebrew and English; the panel rebuilds in the new language and
        // stays open on this button, and Home rebuilds when it is next in front.
        boolean hebrew = "iw".equals(getResources().getConfiguration().getLocales().get(0).getLanguage())
                || "he".equals(getResources().getConfiguration().getLocales().get(0).getLanguage());
        ((TextView) findViewById(R.id.qsLangName)).setText(hebrew ? R.string.qs_lang_hebrew : R.string.qs_lang_english);
        View language = findViewById(R.id.qsLang);
        language.setOnClickListener(v -> {
            LauncherLocale.set(this, hebrew ? "en" : "iw");
            focusLanguage = true;
            relaunching = true;
            recreate();
        });

        // The vision panel's tiles in the order last arranged (the arrange button beside the language).
        if (vision) {
            arrangeTiles(tiles);
            View arrange = findViewById(R.id.qsArrange);
            arrange.setOnClickListener(v -> setArranging(!arranging));
            arrange.setNextFocusDownId(tiles.getChildAt(0).getId());
        }
        // Up from the top row reaches the gear, Down from the gear and the language button the first tile.
        for (int i = 0; i < tiles.getColumnCount() && i < tiles.getChildCount(); i++) {
            tiles.getChildAt(i).setNextFocusUpId(R.id.qsGear);
        }
        gear.setNextFocusDownId(tiles.getChildAt(0).getId());
        language.setNextFocusDownId(tiles.getChildAt(0).getId());
        if (vision) addGlassSlider();
        if (vision && DsHome.on(this)) addIconSizeSlider();
        // Mirroring takes the views out and puts them back, which drops the focus: mirror first, then focus.
        if (vision && HebrewFont.hebrew(this)) mirrorForHebrew();
        addFocusMotion(findViewById(R.id.panel));
        HebrewFont.apply(findViewById(R.id.scrim));
        if (focusLanguage) {
            focusLanguage = false;
            language.requestFocus();
        } else {
            tiles.getChildAt(0).requestFocus();
        }
        if (entering) animatePanel(true, null);
        if (getIntent().getBooleanExtra(EXTRA_PHONE_EDIT, false)) findViewById(R.id.scrim).post(this::showPhoneCard);
    }

    /**
     * The glass slider under the toggles: Left and Right make the cards here, and Home's clock and cards, more or less
     * see-through. The cards follow at once; Home picks it up when it is back in front.
     */
    private void addGlassSlider() {
        android.widget.SeekBar slider = findViewById(R.id.visionGlass);
        slider.setKeyProgressIncrement(10);
        TextView value = findViewById(R.id.visionGlassValue);
        slider.setProgress(HomeToggles.glass(this));
        applyCardGlass(slider.getProgress());
        value.setText(slider.getProgress() + "%");
        if (HebrewFont.hebrew(this)) {
            // Hebrew fills from the right. The launcher has no RTL layout, so the bar is mirrored and Left and
            // Right swapped by hand: Left (towards the far end) still adds.
            slider.setScaleX(-1f);
            slider.setOnKeyListener((v, keyCode, event) -> {
                if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    slider.incrementProgressBy(keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? 10 : -10);
                }
                return true;
            });
        }
        slider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar bar, int level, boolean fromUser) {
                HomeToggles.setGlass(QuickSettingsActivity.this, level);
                applyCardGlass(level);
                value.setText(level + "%");
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar bar) {
            }
        });
    }

    /**
     * ds_home only: an icon-size slider under the glass one. Each step is a number of tiles across Home's grid
     * (DsHome.SIZES); Home rebuilds with it when it is back in front (the settings revision moves).
     */
    private void addIconSizeSlider() {
        LinearLayout glassRow = findViewById(R.id.visionGlassRow);
        ViewGroup card = (ViewGroup) glassRow.getParent();
        float d = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(glassRow.getPaddingLeft(), 0, glassRow.getPaddingRight(), 0);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_apps);
        row.addView(icon, new LinearLayout.LayoutParams(Math.round(20 * d), Math.round(20 * d)));
        TextView label = new TextView(this);
        label.setText(R.string.ds_icon_size);
        label.setTextColor(getColor(R.color.text_primary));
        label.setTextSize(13);
        label.setTypeface(UiFont.get(this, R.string.font_medium));
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.setMarginStart(Math.round(8 * d));
        row.addView(label, labelLp);
        android.widget.SeekBar slider = (android.widget.SeekBar) getLayoutInflater()
                .inflate(R.layout.item_size_slider, row, false);
        slider.setMax(DsHome.SIZES.length - 1);
        slider.setKeyProgressIncrement(1);
        row.addView(slider);
        TextView value = new TextView(this);
        value.setGravity(Gravity.CENTER);
        value.setTextColor(getColor(R.color.vision_text));
        value.setTextSize(12);
        value.setTypeface(UiFont.get(this, R.string.font_medium));
        row.addView(value, new LinearLayout.LayoutParams(Math.round(42 * d), ViewGroup.LayoutParams.WRAP_CONTENT));
        DsHome.load(this);
        int step = HomeToggles.dsSize(this) >= 0 ? HomeToggles.dsSize(this) : DsHome.stepFor(DsHome.columns);
        slider.setProgress(step);
        value.setText(String.valueOf(step + 1));
        if (HebrewFont.hebrew(this)) {
            // As the glass slider: filled from the right, Left still makes the icons bigger.
            slider.setScaleX(-1f);
            slider.setOnKeyListener((v, keyCode, event) -> {
                if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    slider.incrementProgressBy(keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? 1 : -1);
                }
                return true;
            });
            for (int i = row.getChildCount() - 1; i >= 0; i--) {
                View child = row.getChildAt(i);
                row.removeViewAt(i);
                row.addView(child);
            }
        }
        slider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar bar, int level, boolean fromUser) {
                HomeToggles.setDsSize(QuickSettingsActivity.this, level);
                value.setText(String.valueOf(level + 1));
                // Home rebuilds its grid with the new size when it comes back in front.
                android.content.SharedPreferences choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
                choices.edit().putLong(Config.SETTINGS_REVISION_KEY,
                        choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).commit();
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar bar) {
            }
        });
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = Math.round(2 * d);
        card.addView(row, card.indexOfChild(glassRow) + 1, rowLp);
    }

    private void applyCardGlass(int level) {
        int alpha = HomeToggles.surfaceAlpha(level);
        View[] cards = {findViewById(R.id.visionNow), findViewById(R.id.visionDevices),
                (View) findViewById(R.id.tiles).getParent()};
        for (View card : cards) {
            if (card == null) continue;
            // A fresh drawable each time: the alpha of the inflated layer-list's shared state did not reach the screen.
            android.graphics.drawable.Drawable glass = getDrawable(R.drawable.vision_card);
            if (glass == null) continue;
            glass = glass.mutate();
            glass.setAlpha(alpha);
            card.setBackground(glass);
        }
    }

    /**
     * A switch tile: OK flips it and it stays open, blue while on, with On or Off under its name. The icon may change
     * with it (the sound's speaker is struck through while muted).
     */
    private void addSwitch(GridLayout tiles, int iconOn, int iconOff, int title, boolean on,
                           java.util.function.Consumer<Boolean> store) {
        View tile = addTile(tiles, on ? iconOn : iconOff, getString(title), getString(on ? R.string.qs_on : R.string.qs_off), null);
        // One key whether it starts on or off (the sound switch shows another icon while off).
        tile.setTag(R.id.panel_key, getResources().getResourceEntryName(iconOn));
        tile.setActivated(on);
        tile.setOnClickListener(v -> {
            boolean now = !v.isActivated();
            store.accept(now);
            v.setActivated(now);
            ((ImageView) v.findViewById(R.id.icon)).setImageResource(now ? iconOn : iconOff);
            TextView status = v.findViewById(R.id.status);
            status.setText(now ? R.string.qs_on : R.string.qs_off);
            status.setVisibility(View.VISIBLE);
        });
    }

    /**
     * The vision panel in Hebrew: read from the right, so the cards, the header row and every row's icon and text run
     * the other way, and texts sit at the right. The launcher lays out left to right (no supportsRtl), so the order is
     * turned around by hand; the toggle grid is placed right to left as it is filled (addVisionTile).
     */
    private void mirrorForHebrew() {
        reverseChildren(findViewById(R.id.panel));
        reverseChildren((ViewGroup) findViewById(R.id.qsGear).getParent());
        reverseChildren(findViewById(R.id.visionGlassRow));
        LinearLayout now = findViewById(R.id.visionNow);
        now.setGravity(Gravity.RIGHT);
        mirrorRows(findViewById(R.id.visionMedia));
        mirrorRows(findViewById(R.id.visionDevicesList));
        View devices = findViewById(R.id.visionDevices);
        if (devices instanceof LinearLayout) ((LinearLayout) devices).setGravity(Gravity.RIGHT);
    }

    /** Each horizontal row with an icon and lines of text: icon to the right, text right-aligned. */
    private static void mirrorRows(ViewGroup list) {
        if (list == null) return;
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (!(child instanceof LinearLayout)) continue;
            LinearLayout row = (LinearLayout) child;
            if (row.getOrientation() != LinearLayout.HORIZONTAL || row.getChildCount() < 2) continue;
            // Icon then text only: the media buttons keep their order, as they do in every language.
            if (!(row.getChildAt(0) instanceof ImageView)
                    || !(row.getChildAt(row.getChildCount() - 1) instanceof LinearLayout)) continue;
            reverseChildren(row);
            for (int k = 0; k < row.getChildCount(); k++) {
                View part = row.getChildAt(k);
                ViewGroup.LayoutParams lp = part.getLayoutParams();
                if (lp instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
                    int start = m.getMarginStart();
                    m.setMarginStart(m.getMarginEnd());
                    m.setMarginEnd(start);
                    m.leftMargin = m.getMarginStart();
                    m.rightMargin = m.getMarginEnd();
                }
                if (part instanceof LinearLayout) {
                    LinearLayout lines = (LinearLayout) part;
                    lines.setGravity(Gravity.RIGHT);
                    for (int t = 0; t < lines.getChildCount(); t++) {
                        if (lines.getChildAt(t) instanceof TextView) {
                            ((TextView) lines.getChildAt(t)).setGravity(Gravity.RIGHT);
                        }
                    }
                }
            }
            row.setPadding(row.getPaddingRight(), row.getPaddingTop(), row.getPaddingLeft(), row.getPaddingBottom());
        }
    }

    private static void reverseChildren(ViewGroup group) {
        if (group == null) return;
        List<View> children = new ArrayList<>();
        for (int i = group.getChildCount() - 1; i >= 0; i--) children.add(group.getChildAt(i));
        group.removeAllViews();
        for (View child : children) group.addView(child);
    }

    // ---- arranging the panel's own tiles -------------------------------------------------------------------

    /** The vision panel's tiles in their order; the grid is laid out from this. */
    private final List<View> panelTiles = new ArrayList<>();
    /** The arrange button is on: OK lifts a tile, the arrows move it, OK sets it down. */
    private boolean arranging;
    /** The tile lifted while arranging, or null. */
    private View held;

    private static String key(View tile) {
        Object key = tile.getTag(R.id.panel_key);
        return key != null ? key.toString() : "";
    }

    /** Puts the tiles in the saved order; tiles new since then keep their place after the saved ones. */
    private void arrangeTiles(GridLayout tiles) {
        panelTiles.clear();
        for (int i = 0; i < tiles.getChildCount(); i++) panelTiles.add(tiles.getChildAt(i));
        List<String> saved = HomeToggles.panelOrder(this);
        if (!saved.isEmpty()) {
            // A stable sort: tiles the saved order does not name stay in their own order at the end.
            panelTiles.sort(java.util.Comparator.comparingInt(t -> {
                int at = saved.indexOf(key(t));
                return at < 0 ? Integer.MAX_VALUE : at;
            }));
        }
        placeTiles(tiles);
        for (View tile : panelTiles) tile.setOnKeyListener(this::arrangeKey);
    }

    /** Lays the grid out from panelTiles, row by row, each row from the right in Hebrew (as addVisionTile does). */
    private void placeTiles(GridLayout tiles) {
        tiles.removeAllViews();
        int columns = tiles.getColumnCount();
        boolean hebrew = HebrewFont.hebrew(this);
        for (int i = 0; i < panelTiles.size(); i++) {
            View tile = panelTiles.get(i);
            int column = i % columns;
            if (hebrew) column = columns - 1 - column;
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(i / columns), GridLayout.spec(column, 1f));
            lp.width = 0;
            lp.setMargins(dp(2), dp(4), dp(2), dp(4));
            tiles.addView(tile, lp);
            tile.setNextFocusUpId(i < columns ? R.id.qsGear : View.NO_ID);
        }
    }

    private void setArranging(boolean on) {
        if (!on && held != null) setDown();
        arranging = on;
        findViewById(R.id.qsArrange).setActivated(on);
        for (View tile : panelTiles) tile.animate().alpha(on ? 0.7f : 1f).setDuration(150).start();
        if (on) {
            Toast.makeText(this, R.string.qs_arrange_hint, Toast.LENGTH_LONG).show();
            panelTiles.get(0).requestFocus();
        } else {
            List<String> keys = new ArrayList<>();
            for (View tile : panelTiles) keys.add(key(tile));
            HomeToggles.setPanelOrder(this, keys);
        }
    }

    /** While arranging: OK lifts or sets down the focused tile; with one lifted, the arrows carry it. */
    private boolean arrangeKey(View tile, int keyCode, KeyEvent event) {
        if (!arranging) return false;
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            // Both halves of the press are taken, so the tile does not also open its screen.
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (held == tile) setDown();
                else pickUp(tile);
            }
            return true;
        }
        if (held == null) return false;   // nothing lifted: the arrows move the focus as usual
        boolean hebrew = HebrewFont.hebrew(this);
        int columns = ((GridLayout) findViewById(R.id.tiles)).getColumnCount();
        int at = panelTiles.indexOf(held), to;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT: to = at + (hebrew ? 1 : -1); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: to = at + (hebrew ? -1 : 1); break;
            case KeyEvent.KEYCODE_DPAD_UP: to = at - columns; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: to = at + columns; break;
            default: return false;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN || to < 0 || to >= panelTiles.size()) return true;
        View moving = held;
        panelTiles.remove(at);
        panelTiles.add(to, moving);
        placeTiles(findViewById(R.id.tiles));
        moving.requestFocus();
        lift(moving);
        return true;
    }

    private void pickUp(View tile) {
        if (held != null) setDown();
        held = tile;
        lift(tile);
    }

    private void lift(View tile) {
        tile.animate().cancel();
        tile.setAlpha(1f);
        tile.animate().scaleX(1.2f).scaleY(1.2f).setDuration(120).start();
    }

    private void setDown() {
        View tile = held;
        held = null;
        float focused = getResources().getInteger(R.integer.focus_scale_percent) / 100f;
        float scale = tile.isFocused() ? focused : 1f;
        tile.animate().scaleX(scale).scaleY(scale).alpha(arranging ? 0.7f : 1f).setDuration(120).start();
    }

    /** A picker's choice is made: the whole panel goes, so Home shows it. */
    private void closeAfterPick() {
        picker.close();
        onBackPressed();
    }

    /** Back: a picker returns to the panel; the panel slides out the way it came in, then closes. */
    /** The "Edit from the phone" card on screen, in place of the panel; Back closes it. */
    private View phoneCard;

    /**
     * Edit from the phone: the panel steps aside for one glass card with a QR code (this TV's address, opening the
     * phone page PhoneEditor sends) and a new 4-digit code to type on the phone. Scanning and typing are the whole
     * pairing: no IP address to type, no computer.
     */
    private void showPhoneCard() {
        PhoneEditor editor = PhoneEditor.start(this);
        if (editor == null) {
            Toast.makeText(this, R.string.qs_phone_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        String code = editor.newCode();
        String url = editor.url();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackgroundResource(R.drawable.vision_card);
        card.setPadding(dp(32), dp(26), dp(32), dp(26));
        card.setElevation(dp(16));
        TextView title = new TextView(this);
        title.setText(R.string.qs_phone_title);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(20);
        title.setTypeface(UiFont.get(this, R.string.font_medium));
        title.setGravity(Gravity.CENTER);
        card.addView(title);
        ImageView qr = new ImageView(this);
        qr.setImageBitmap(qrCode(url, 420));
        qr.setBackgroundColor(0xFFFFFFFF);
        qr.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(dp(190), dp(190));
        qrLp.topMargin = dp(16);
        card.addView(qr, qrLp);
        TextView steps = new TextView(this);
        steps.setText(R.string.qs_phone_steps);
        steps.setTextColor(getColor(R.color.vision_text));
        steps.setTextSize(13);
        steps.setGravity(Gravity.CENTER);
        steps.setMaxWidth(dp(300));
        LinearLayout.LayoutParams stepsLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        stepsLp.topMargin = dp(14);
        card.addView(steps, stepsLp);
        TextView digits = new TextView(this);
        digits.setText(code.charAt(0) + " " + code.charAt(1) + " " + code.charAt(2) + " " + code.charAt(3));
        digits.setTextColor(getColor(R.color.text_primary));
        digits.setTextSize(44);
        digits.setLetterSpacing(0.08f);
        digits.setTypeface(UiFont.get(this, R.string.font_medium));
        digits.setTextDirection(View.TEXT_DIRECTION_LTR);
        digits.setGravity(Gravity.CENTER);
        card.addView(digits, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView address = new TextView(this);
        address.setText(url);
        address.setTextColor(getColor(R.color.vision_text));
        address.setTextSize(11);
        address.setAlpha(0.7f);
        address.setTextDirection(View.TEXT_DIRECTION_LTR);
        address.setGravity(Gravity.CENTER);
        card.addView(address, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ImageView close = new ImageView(this);
        close.setImageResource(R.drawable.ic_close);
        close.setBackgroundResource(R.drawable.vision_toggle_bg);
        close.setPadding(dp(11), dp(11), dp(11), dp(11));
        close.setFocusable(true);
        close.setClickable(true);
        close.setContentDescription(getString(R.string.qs_close));
        close.setImageTintList(getColorStateList(R.drawable.pill_text));
        close.setOnClickListener(v -> closePhoneCard());
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        closeLp.topMargin = dp(14);
        card.addView(close, closeLp);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        View panel = findViewById(R.id.panel);
        panel.animate().alpha(0f).setDuration(160).withEndAction(() -> panel.setVisibility(View.INVISIBLE)).start();
        ((FrameLayout) findViewById(R.id.scrim)).addView(card, cardLp);
        card.setAlpha(0f);
        card.setScaleX(0.96f);
        card.setScaleY(0.96f);
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(Motion.ENTER).start();
        close.requestFocus();
        phoneCard = card;
    }

    private void closePhoneCard() {
        if (phoneCard == null) return;
        ((ViewGroup) phoneCard.getParent()).removeView(phoneCard);
        phoneCard = null;
        View panel = findViewById(R.id.panel);
        panel.setVisibility(View.VISIBLE);
        panel.animate().alpha(1f).setDuration(200).start();
        GridLayout tiles = findViewById(R.id.tiles);
        if (tiles.getChildCount() > 0) tiles.getChildAt(0).requestFocus();
    }

    private static Bitmap qrCode(String text, int size) {
        try {
            com.google.zxing.common.BitMatrix m = new com.google.zxing.qrcode.QRCodeWriter()
                    .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size);
            int[] pixels = new int[size * size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) pixels[y * size + x] = m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            }
            return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }
    }

    @Override
    public void onBackPressed() {
        if (phoneCard != null) {
            closePhoneCard();
            return;
        }
        if (picker != null && picker.isOpen()) {
            picker.close();
            return;
        }
        if (arranging) {
            setArranging(false);
            return;
        }
        if (closing) return;
        closing = true;
        animatePanel(false, this::finish);
    }

    /**
     * The panel's entrance and exit: a short slide from its edge with a fade, the backdrop fading with it. Transform
     * and alpha only, so the TV composites it without redrawing the glass.
     */
    private void animatePanel(boolean in, Runnable done) {
        View panel = findViewById(R.id.panel);
        View scrim = findViewById(R.id.scrim);
        if (vision) {
            // The cards rise out of the dim, a little smaller at first, as visionOS windows open. Quicker in than the
            // usual panel motion, and from partly visible, so the press shows at once.
            int duration = in ? 170 : getResources().getInteger(R.integer.motion_panel_ms);
            if (in) {
                panel.setAlpha(0.3f);
                panel.setScaleX(0.96f);
                panel.setScaleY(0.96f);
                scrim.setAlpha(0f);
            }
            scrim.animate().alpha(in ? 1f : 0f).setDuration(duration).start();
            panel.animate().alpha(in ? 1f : 0f).scaleX(in ? 1f : 0.96f).scaleY(in ? 1f : 0.96f)
                    .setDuration(in ? duration : duration * 2 / 3)
                    .setInterpolator(in ? Motion.ENTER : Motion.EXIT)
                    .withEndAction(done).start();
            return;
        }
        float offset = dp(40) * (getResources().getBoolean(R.bool.rtl_menu_bar) ? -1 : 1);
        int duration = getResources().getInteger(R.integer.motion_panel_ms);
        if (in) {
            panel.setAlpha(0f);
            panel.setTranslationX(offset);
            scrim.setAlpha(0f);
        }
        scrim.animate().alpha(in ? 1f : 0f).setDuration(duration).start();
        panel.animate().alpha(in ? 1f : 0f).translationX(in ? 0f : offset)
                .setDuration(in ? duration : duration * 2 / 3)
                .setInterpolator(in ? Motion.ENTER : Motion.EXIT)
                .withEndAction(done).start();
    }

    /** Every focusable control in the panel grows slightly while focused, as tvOS controls lift. */
    private void addFocusMotion(View view) {
        // The glass slider shows focus with its ring and keeps its own scale (mirrored in Hebrew).
        if (view.isFocusable() && !(view instanceof android.widget.SeekBar)) {
            float tileScale = getResources().getInteger(R.integer.focus_scale_percent) / 100f;
            int duration = getResources().getInteger(R.integer.motion_fast_ms);
            // A full-width row (Resume, a paired device) grows half as much, so it stays inside the glass.
            view.setOnFocusChangeListener((v, focused) -> {
                float scale = v.getWidth() > dp(200) ? 1f + (tileScale - 1f) / 2f : tileScale;
                v.animate().scaleX(focused ? scale : 1f).scaleY(focused ? scale : 1f)
                        .setDuration(duration).setInterpolator(Motion.SETTLE).start();
            });
            if (view.isFocused()) {
                view.setScaleX(tileScale);
                view.setScaleY(tileScale);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) addFocusMotion(group.getChildAt(i));
        }
    }

    @Override
    protected void onDestroy() {
        ticker.removeCallbacksAndMessages(null);
        if (following != null && followCallback != null) following.unregisterCallback(followCallback);
        for (int profile : PROFILES) {
            BluetoothProfile proxy = proxies.get(profile);
            if (proxy != null && adapter != null) adapter.closeProfileProxy(profile, proxy);
        }
        super.onDestroy();
    }

    // ---- Spotify ---------------------------------------------------------------------------------------

    /**
     * A Spotify tile (what is playing, or Resume) and a row of previous / play-pause / next. With notification
     * access for GuardRevival the controls go to Spotify's own session and the tile names the track and playlist.
     * With no session (Spotify not running, or no access) Resume and play-pause open Spotify and press Play on its
     * session once it appears. The controls keep the panel open, so a track can be skipped twice in a row.
     */
    private void addSpotify(GridLayout tiles) {
        Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(SPOTIFY);
        if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(SPOTIFY);
        if (launch == null) return;   // not installed, or disabled
        if (vision) {
            addNowPlaying(launch);
            return;
        }
        addSection(tiles, "Spotify");
        MediaController spotify = spotifySession();
        String line = null;
        if (spotify != null && spotify.getMetadata() != null) {
            CharSequence title = spotify.getMetadata().getText(MediaMetadata.METADATA_KEY_TITLE);
            CharSequence playlist = spotify.getQueueTitle();
            CharSequence artist = spotify.getMetadata().getText(MediaMetadata.METADATA_KEY_ARTIST);
            CharSequence second = playlist != null && playlist.length() > 0 ? playlist : artist;
            if (title != null) line = second != null ? title + " · " + second : title.toString();
        }
        boolean playing = spotify != null && spotify.getPlaybackState() != null
                && spotify.getPlaybackState().getState() == PlaybackState.STATE_PLAYING;
        Intent open = launch;
        View tile = addTile(tiles, R.drawable.ic_music, playing ? "Spotify" : getString(R.string.qs_resume),
                line != null ? line : "Spotify", null, 2);
        tile.setOnClickListener(v -> {
            MediaController session = spotifySession(this);
            if (session != null) {
                session.getTransportControls().play();
            } else {
                // Spotify keeps its last context: open it, wait for its own session and press Play on that. A media
                // key here would go to whichever app played last (YouTube, say), not to Spotify.
                startFirst(this, listOf(open));
                playWhenReady(getApplicationContext(), 0);
            }
            finish();
        });

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        addControl(controls, R.drawable.ic_skip_previous, R.string.qs_previous, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        addControl(controls, R.drawable.ic_play_pause, R.string.qs_play_pause, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        addControl(controls, R.drawable.ic_skip_next, R.string.qs_next, KeyEvent.KEYCODE_MEDIA_NEXT);
        tiles.addView(controls, fullWidth());
    }

    private void addControl(LinearLayout row, int icon, int description, int keyCode) {
        ImageView button = (ImageView) getLayoutInflater().inflate(R.layout.item_quick_button, row, false);
        button.setId(View.generateViewId());
        button.setImageResource(icon);
        button.setContentDescription(getString(description));
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) button.getLayoutParams();
        if (row.getChildCount() > 0) lp.setMarginStart(dp(10));
        if (vision) {
            // Round, as visionOS media buttons are, centred under the track.
            button.setBackgroundResource(R.drawable.vision_toggle_bg);
            lp.width = dp(52);
            lp.height = dp(52);
            lp.weight = 0;
            if (row.getChildCount() > 0) lp.setMarginStart(dp(18));
        }
        button.setOnClickListener(v -> {
            MediaController session = spotifySession();
            if (session == null) {
                // Spotify is not running: play-pause resumes it like the Resume tile; previous and next have
                // nothing to skip. No media key, which would reach another app.
                if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                    Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(SPOTIFY);
                    if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(SPOTIFY);
                    if (launch != null && startFirst(this, listOf(launch))) playWhenReady(getApplicationContext(), 0);
                    finish();
                }
                return;
            }
            MediaController.TransportControls controls = session.getTransportControls();
            if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) controls.skipToPrevious();
            else if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) controls.skipToNext();
            else if (session.getPlaybackState() != null
                    && session.getPlaybackState().getState() == PlaybackState.STATE_PLAYING) controls.pause();
            else controls.play();
        });
        row.addView(button, lp);
    }

    /**
     * After Spotify was opened for Resume. Spotify makes its media session only once something plays, so there is no
     * session to press Play on yet: connect to Spotify's media library service (the way Google TV's Home resumes it)
     * and press Play on the session it hands over. If Spotify refuses the connection, send Play to Spotify's own
     * media button receiver instead. Never a plain media key, which the app that played last (YouTube) would take.
     */
    private static void playWhenReady(Context app, int attempt) {
        Handler main = new Handler(Looper.getMainLooper());
        main.postDelayed(() -> {
            MediaBrowser[] browser = new MediaBrowser[1];
            browser[0] = new MediaBrowser(app, new ComponentName(SPOTIFY, SPOTIFY_LIBRARY),
                    new MediaBrowser.ConnectionCallback() {
                        @Override
                        public void onConnected() {
                            try {
                                new MediaController(app, browser[0].getSessionToken()).getTransportControls().play();
                                Log.i(TAG, "Spotify: play through its media library service");
                            } catch (RuntimeException e) {
                                Log.w(TAG, "Spotify: play through the library failed: " + e);
                                mediaButton(app);
                            }
                            main.postDelayed(browser[0]::disconnect, 5000);
                        }

                        @Override
                        public void onConnectionFailed() {
                            Log.w(TAG, "Spotify: media library connection refused");
                            mediaButton(app);
                        }
                    }, null);
            browser[0].connect();
        }, attempt == 0 ? 1500 : 0);
    }

    /** KEYCODE_MEDIA_PLAY to Spotify's own media button receiver, down and up. */
    private static void mediaButton(Context app) {
        for (int action : new int[] {KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            Intent press = new Intent(Intent.ACTION_MEDIA_BUTTON)
                    .setComponent(new ComponentName(SPOTIFY, SPOTIFY_BUTTONS))
                    .putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PLAY));
            app.sendBroadcast(press);
        }
        Log.i(TAG, "Spotify: play sent to its media button receiver");
    }

    private MediaController spotifySession() {
        return spotifySession(this);
    }

    /** Spotify's media session, or null when Spotify has none or this app has no notification access. */
    private static MediaController spotifySession(Context context) {
        try {
            MediaSessionManager sessions = context.getSystemService(MediaSessionManager.class);
            for (MediaController c : sessions.getActiveSessions(new ComponentName(context, GuardRevival.class))) {
                if (SPOTIFY.equals(c.getPackageName())) return c;
            }
        } catch (SecurityException | NullPointerException ignored) {
            // GuardRevival has no notification access
        }
        return null;
    }

    /**
     * The now-playing part of the vision panel's first card: the album art, track and artist (OK resumes, as the
     * Resume tile does), a progress bar and round previous / play-pause / next. It follows Spotify's session while
     * the panel is open, so a skip shows the new track.
     */
    private void addNowPlaying(Intent open) {
        LinearLayout media = findViewById(R.id.visionMedia);
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setGravity(Gravity.CENTER_VERTICAL);
        track.setBackgroundResource(R.drawable.vision_row_bg);
        track.setPadding(dp(6), dp(6), dp(10), dp(6));
        track.setFocusable(true);
        track.setClickable(true);
        track.setId(View.generateViewId());
        ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(12));
            }
        });
        art.setClipToOutline(true);
        track.addView(art, new LinearLayout.LayoutParams(dp(60), dp(60)));
        LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        lines.setDuplicateParentStateEnabled(true);
        TextView title = mediaText(15, R.drawable.pill_text, R.font.outfit_medium);
        TextView artist = mediaText(12, R.drawable.vision_text_dim, R.font.outfit_regular);
        lines.addView(title);
        lines.addView(artist);
        LinearLayout.LayoutParams linesLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        linesLp.setMarginStart(dp(12));
        track.addView(lines, linesLp);
        track.setOnClickListener(v -> {
            MediaController session = spotifySession(this);
            if (session != null) {
                session.getTransportControls().play();
            } else {
                startFirst(this, listOf(open));
                playWhenReady(getApplicationContext(), 0);
            }
            finish();
        });
        media.addView(track, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.text_primary)));
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x4DFFFFFF));
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        progressLp.topMargin = dp(14);
        progressLp.leftMargin = progressLp.rightMargin = dp(6);
        media.addView(progress, progressLp);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        addControl(controls, R.drawable.ic_skip_previous, R.string.qs_previous, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        addControl(controls, R.drawable.ic_play_pause, R.string.qs_play_pause, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        addControl(controls, R.drawable.ic_skip_next, R.string.qs_next, KeyEvent.KEYCODE_MEDIA_NEXT);
        LinearLayout.LayoutParams controlsLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        controlsLp.topMargin = dp(14);
        media.addView(controls, controlsLp);

        following = spotifySession();
        Runnable show = () -> showTrack(following, art, title, artist, progress);
        show.run();
        if (following != null) {
            followCallback = new MediaController.Callback() {
                @Override
                public void onMetadataChanged(MediaMetadata metadata) {
                    show.run();
                }

                @Override
                public void onPlaybackStateChanged(PlaybackState state) {
                    show.run();
                }
            };
            following.registerCallback(followCallback, ticker);
            tick = () -> {
                showProgress(following, progress);
                ticker.postDelayed(tick, 1000);
            };
            ticker.postDelayed(tick, 1000);
        }
    }

    private TextView mediaText(int sp, int colors, int font) {
        TextView text = new TextView(this);
        text.setTextSize(sp);
        text.setTextColor(getColorStateList(colors));
        text.setTypeface(getResources().getFont(font));
        text.setSingleLine(true);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.setDuplicateParentStateEnabled(true);
        return text;
    }

    private void showTrack(MediaController session, ImageView art, TextView title, TextView artist, ProgressBar progress) {
        MediaMetadata meta = session != null ? session.getMetadata() : null;
        CharSequence name = meta != null ? meta.getText(MediaMetadata.METADATA_KEY_TITLE) : null;
        CharSequence by = meta != null ? meta.getText(MediaMetadata.METADATA_KEY_ARTIST) : null;
        Bitmap cover = null;
        if (meta != null) {
            cover = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (cover == null) cover = meta.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (cover == null) cover = meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        }
        if (cover != null) {
            art.setImageBitmap(cover);
            art.setPadding(0, 0, 0, 0);
            art.setBackground(null);
        } else {
            art.setImageResource(R.drawable.ic_music);
            art.setPadding(dp(16), dp(16), dp(16), dp(16));
            art.setBackgroundColor(getColor(R.color.vision_control));
        }
        title.setText(name != null ? name : getString(session != null ? R.string.qs_not_playing : R.string.qs_resume));
        artist.setText(by != null ? by : "Spotify");
        showProgress(session, progress);
    }

    /** Where the track is, worked out from the last position Spotify reported and the time since. */
    private static void showProgress(MediaController session, ProgressBar progress) {
        PlaybackState state = session != null ? session.getPlaybackState() : null;
        MediaMetadata meta = session != null ? session.getMetadata() : null;
        long duration = meta != null ? meta.getLong(MediaMetadata.METADATA_KEY_DURATION) : 0;
        if (state == null || duration <= 0) {
            progress.setProgress(0);
            return;
        }
        long position = state.getPosition();
        if (state.getState() == PlaybackState.STATE_PLAYING) {
            position += (long) ((SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime())
                    * state.getPlaybackSpeed());
        }
        progress.setProgress((int) Math.max(0, Math.min(1000, position * 1000 / duration)));
    }

    // ---- paired Bluetooth devices ----------------------------------------------------------------------

    /** Bluetooth is switched on; false when it is off or cannot be read. */
    private boolean bluetoothOn() {
        try {
            BluetoothManager manager = getSystemService(BluetoothManager.class);
            return manager != null && manager.getAdapter() != null && manager.getAdapter().isEnabled();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * One row per paired device with whether it is connected. A row opens that device's page in TV Settings,
     * where Connect or Disconnect is the first item: apps cannot connect a device themselves.
     */
    @android.annotation.SuppressLint("MissingPermission")   // BLUETOOTH_CONNECT is granted over ADB; every call catches SecurityException
    private void addPairedDevices(GridLayout tiles) {
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        adapter = manager != null ? manager.getAdapter() : null;
        Set<BluetoothDevice> bonded;
        try {
            bonded = adapter != null && adapter.isEnabled() ? adapter.getBondedDevices() : null;
        } catch (SecurityException e) {
            bonded = null;   // BLUETOOTH_CONNECT not granted
        }
        if (bonded == null || bonded.isEmpty()) return;
        addSection(tiles, getString(R.string.qs_bt_devices));
        for (BluetoothDevice device : bonded) {
            String name;
            try {
                name = android.os.Build.VERSION.SDK_INT >= 30 && device.getAlias() != null
                        ? device.getAlias() : device.getName();
            } catch (SecurityException e) {
                name = null;
            }
            if (name == null) name = device.getAddress();
            Intent page = new Intent("android.settings.SLICE_SETTINGS").setPackage(TV_SETTINGS)
                    .putExtra("slice_uri", "content://com.android.tv.settings.accessories.sliceprovider/device/"
                            + device.getAddress());
            View row = addTile(tiles, R.drawable.ic_bluetooth, name, getString(R.string.qs_not_connected),
                    listOf(page), 2);
            row.findViewById(R.id.icon).setAlpha(0.45f);
            deviceRows.put(device.getAddress(), row);
        }
        // Connection state comes from each profile: A2DP for speakers and earbuds, HID for remotes and
        // keyboards, headset and LE audio for the rest. The proxies answer asynchronously; they are asked once the
        // panel is on screen, since binding four of them held up its first frame.
        afterFirstFrame(() -> watchConnections(manager));
    }

    @android.annotation.SuppressLint("MissingPermission")
    private void watchConnections(BluetoothManager manager) {
        if (isFinishing()) return;
        for (int profile : PROFILES) {
            try {
                adapter.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
                    @Override
                    public void onServiceConnected(int which, BluetoothProfile proxy) {
                        proxies.put(which, proxy);
                        try {
                            for (BluetoothDevice d : proxy.getConnectedDevices()) markConnected(d.getAddress());
                        } catch (SecurityException ignored) {
                        }
                    }

                    @Override
                    public void onServiceDisconnected(int which) {
                        proxies.remove(which);
                    }
                }, profile);
            } catch (RuntimeException ignored) {
                // profile not supported here
            }
        }
        try {
            for (BluetoothDevice d : manager.getConnectedDevices(BluetoothProfile.GATT)) markConnected(d.getAddress());
        } catch (RuntimeException ignored) {
        }
    }

    /** Runs {@code work} just after the panel's first frame is drawn. */
    private void afterFirstFrame(Runnable work) {
        View decor = getWindow().getDecorView();
        decor.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                decor.getViewTreeObserver().removeOnPreDrawListener(this);
                decor.post(work);
                return true;
            }
        });
    }

    private void markConnected(String address) {
        View row = deviceRows.get(address);
        if (row != null) runOnUiThread(() -> {
            ((TextView) row.findViewById(R.id.status)).setText(R.string.qs_connected);
            row.findViewById(R.id.icon).setAlpha(1f);
            row.findViewById(R.id.icon).setActivated(vision);
        });
    }

    private void addSection(GridLayout tiles, String title) {
        if (vision) {
            // The devices card has its own heading.
            findViewById(R.id.visionDevices).setVisibility(View.VISIBLE);
            return;
        }
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(getColor(R.color.text_tertiary));
        label.setTextSize(11);
        label.setAllCaps(true);
        label.setLetterSpacing(0.14f);
        label.setTypeface(getResources().getFont(R.font.outfit_medium));
        label.setPadding(dp(10), dp(18), dp(10), dp(4));
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(0, 2));
        tiles.addView(label, lp);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Settings pressed again while the panel is up. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finish();
    }

    /** Anything else taking the screen (Home, an app, a screensaver) closes the panel; its own language rebuild does not. */
    @Override
    protected void onPause() {
        super.onPause();
        if (relaunching) return;
        finish();
        overridePendingTransition(0, 0);
    }

    /**
     * The TV's "Remotes & Accessories" screen (TV Settings' connected-devices slice, which Settings guards with
     * WRITE_SECURE_SETTINGS), else its pairing screen (CONNECT_INPUT, guarded with BLUETOOTH_CONNECT), else
     * Bluetooth settings: those that resolve, in that order. Both permissions are granted over ADB.
     */
    static List<Intent> bluetoothTargets(Context context) {
        // Resolved once per process: three package lookups on every open of the panel added up.
        if (bluetoothTargets != null) return bluetoothTargets;
        PackageManager pm = context.getPackageManager();
        List<Intent> targets = new ArrayList<>();
        for (Intent candidate : new Intent[] {
                new Intent("android.settings.SLICE_SETTINGS").setPackage(TV_SETTINGS)
                        .putExtra("slice_uri", "content://com.android.tv.settings.accessories.sliceprovider/general"),
                new Intent("com.google.android.intent.action.CONNECT_INPUT"),
                new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)}) {
            if (pm.resolveActivity(candidate, 0) != null) targets.add(candidate);
        }
        bluetoothTargets = targets;
        return targets;
    }

    private static List<Intent> bluetoothTargets;
    /** Whether this projector has the keystone screen, looked up once per process. */
    private static Boolean hasKeystone;

    /** Starts the first of {@code targets} this app may start; false when none could be. */
    static boolean startFirst(Context context, List<Intent> targets) {
        for (Intent target : targets) {
            try {
                context.startActivity(new Intent(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // not allowed here; try the next screen
            }
        }
        return false;
    }

    private void open(List<Intent> targets) {
        if (!startFirst(this, targets)) Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        finish();
    }

    private View addTile(GridLayout tiles, int icon, String title, String status, List<Intent> targets) {
        return addTile(tiles, icon, title, status, targets, 1);
    }

    /** A tile one or two columns wide; without targets the caller sets the click itself. */
    private View addTile(GridLayout tiles, int icon, String title, String status, List<Intent> targets, int span) {
        if (vision) return addVisionTile(tiles, icon, title, status, targets, span);
        View tile = getLayoutInflater().inflate(R.layout.item_quick_tile, tiles, false);
        tile.setId(View.generateViewId());
        ((ImageView) tile.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) tile.findViewById(R.id.title)).setText(title);
        if (status != null && !status.isEmpty()) {
            TextView line = tile.findViewById(R.id.status);
            line.setText(status);
            line.setVisibility(View.VISIBLE);
        }
        if (targets != null) tile.setOnClickListener(v -> open(targets));
        GridLayout.LayoutParams lp = span == 2 ? fullWidth() : new GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
        if (span != 2) {
            lp.width = 0;
            lp.setMargins(dp(GAP_DP), dp(GAP_DP), dp(GAP_DP), dp(GAP_DP));
        }
        lp.height = dp(TILE_DP);
        tiles.addView(tile, lp);
        return tile;
    }

    /** On the vision panel: a round toggle in the grid, or (span 2, a paired device) a row on the devices card. */
    private View addVisionTile(GridLayout tiles, int icon, String title, String status, List<Intent> targets, int span) {
        LinearLayout list = findViewById(R.id.visionDevicesList);
        View tile = getLayoutInflater().inflate(span == 2 ? R.layout.item_vision_row : R.layout.item_vision_toggle,
                span == 2 ? list : tiles, false);
        tile.setId(View.generateViewId());
        // Named by its icon for the arranged order: the same tile keeps its place in either language.
        tile.setTag(R.id.panel_key, getResources().getResourceEntryName(icon));
        ((ImageView) tile.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) tile.findViewById(R.id.title)).setText(title);
        if (status != null && !status.isEmpty()) {
            TextView line = tile.findViewById(R.id.status);
            line.setText(status);
            line.setVisibility(View.VISIBLE);
        }
        if (targets != null) tile.setOnClickListener(v -> open(targets));
        if (span == 2) {
            list.addView(tile);
            return tile;
        }
        // Filled row by row; in Hebrew each row from the right.
        int index = tiles.getChildCount();
        int columns = tiles.getColumnCount();
        int column = index % columns;
        if (HebrewFont.hebrew(this)) column = columns - 1 - column;
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                GridLayout.spec(index / columns), GridLayout.spec(column, 1f));
        lp.width = 0;
        lp.setMargins(dp(2), dp(4), dp(2), dp(4));
        tiles.addView(tile, lp);
        return tile;
    }

    /**
     * A row across both columns. A weighted span-2 cell comes out 0 px wide in GridLayout, so it gets the panel's
     * inner width outright: 360dp less 2 x 18dp padding, less its margins.
     */
    private GridLayout.LayoutParams fullWidth() {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(0, 2));
        lp.width = dp(360 - 36 - 2 * GAP_DP);
        lp.setMargins(dp(GAP_DP), dp(GAP_DP), dp(GAP_DP), dp(GAP_DP));
        return lp;
    }

    /** The network tile's name and second line: Ethernet, or Wi-Fi with the network's name, or Network / offline. */
    private String[] network() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean wifi = false;
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    return new String[] {getString(R.string.ethernet), null};
                }
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
            }
            if (wifi) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (ssid.isEmpty() || ssid.contains("unknown") || ssid.equals("0x")) ssid = null;
                }
                return new String[] {getString(R.string.wifi), ssid};
            }
            return new String[] {getString(R.string.quick_network), getString(R.string.offline)};
        } catch (Exception e) {
            return new String[] {getString(R.string.quick_network), null};
        }
    }

    private static List<Intent> listOf(Intent... intents) {
        List<Intent> list = new ArrayList<>();
        for (Intent i : intents) list.add(i);
        return list;
    }
}
