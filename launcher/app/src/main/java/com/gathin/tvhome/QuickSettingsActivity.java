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
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

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
 */
public final class QuickSettingsActivity extends Activity {
    private static final String TV_SETTINGS = "com.android.tv.settings";
    private static final String TAG = "QuickSettings";
    private static final String SPOTIFY = "com.spotify.tv.android";
    private static final String SPOTIFY_LIBRARY = "com.spotify.androidtv.medialibraryservice.AndroidTvMediaLibraryService";
    private static final String SPOTIFY_BUTTONS = "androidx.media3.session.MediaButtonReceiver";
    private static final String KEYSTONE_ACTION = "com.fengmi.projector.imaging.action.START_KEYSTONE";
    /** A2DP, headset, HID host (4, hidden constant) and LE audio (22): the profiles a TV's accessories use. */
    private static final int[] PROFILES = {BluetoothProfile.A2DP, BluetoothProfile.HEADSET, 4, 22};

    /** Tile height and the gap around each tile; short enough that the Spotify section peeks out under the tiles. */
    private static final int TILE_DP = 54;
    private static final int GAP_DP = 4;

    /** The rebuilt panel after a language switch puts the focus back on the language button. */
    private static boolean focusLanguage;
    /** This panel is being rebuilt by the language button, not left: onPause must not close it. */
    private boolean relaunching;

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
        setContentView(R.layout.activity_quick_settings);
        if (getResources().getBoolean(R.bool.rtl_menu_bar)) {
            View panel = findViewById(R.id.panel);
            FrameLayout.LayoutParams position = (FrameLayout.LayoutParams) panel.getLayoutParams();
            position.gravity = Gravity.LEFT;
            int inset = position.getMarginEnd();
            position.setMarginEnd(0);
            position.setMargins(inset, position.topMargin, 0, position.bottomMargin);
            panel.setLayoutParams(position);
        }
        findViewById(R.id.scrim).setOnClickListener(v -> finish());
        View gear = findViewById(R.id.qsGear);
        gear.setOnClickListener(v -> open(listOf(SystemSettings.intent(this, Settings.ACTION_SETTINGS))));

        GridLayout tiles = findViewById(R.id.tiles);
        addTile(tiles, R.drawable.ic_screensaver, getString(R.string.qs_screensaver), null,
                listOf(new Intent(this, ClockActivity.class)));
        String[] network = network();
        addTile(tiles, network[0].equals(getString(R.string.ethernet)) ? R.drawable.ic_ethernet : R.drawable.ic_wifi,
                network[0], network[1], listOf(SystemSettings.intent(this, Settings.ACTION_WIFI_SETTINGS)));
        addTile(tiles, R.drawable.ic_bluetooth, getString(R.string.qs_bluetooth), null, bluetoothTargets(this));
        addTile(tiles, R.drawable.ic_volume, getString(R.string.qs_sound), null, listOf(
                new Intent("com.android.tv.action.VIEW_SOUND_SETTINGS").setPackage(TV_SETTINGS),
                new Intent(Settings.ACTION_SOUND_SETTINGS).setPackage(TV_SETTINGS),
                new Intent(Settings.ACTION_SOUND_SETTINGS)));
        addTile(tiles, R.drawable.ic_picture, getString(R.string.quick_picture), null,
                listOf(SystemSettings.intent(this, Settings.ACTION_DISPLAY_SETTINGS)));
        // Fengmi / Formovie projectors: the manual keystone screen (Quick Adjustment, Fine Tuning, Reset).
        Intent keystone = new Intent(KEYSTONE_ACTION);
        if (getPackageManager().resolveActivity(keystone, 0) != null) {
            addTile(tiles, R.drawable.ic_keystone, getString(R.string.qs_keystone), null, listOf(keystone));
        }
        addTile(tiles, R.drawable.ic_accessibility, getString(R.string.qs_accessibility), null,
                listOf(SystemSettings.intent(this, Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        addTile(tiles, R.drawable.ic_apps, getString(R.string.quick_apps), getString(R.string.quick_apps_hint),
                listOf(SystemSettings.intent(this, Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)));
        // Through Home, so it rebuilds with the new choices when Launcher settings closes.
        addTile(tiles, R.drawable.ic_home_settings, getString(R.string.qs_launcher), null,
                listOf(new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_LAUNCHER_SETTINGS)));
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

        // Up from the top row reaches the gear, Down from the gear and the language button the first tile.
        for (int i = 0; i < 2 && i < tiles.getChildCount(); i++) tiles.getChildAt(i).setNextFocusUpId(R.id.qsGear);
        gear.setNextFocusDownId(tiles.getChildAt(0).getId());
        language.setNextFocusDownId(tiles.getChildAt(0).getId());
        if (focusLanguage) {
            focusLanguage = false;
            language.requestFocus();
        } else {
            tiles.getChildAt(0).requestFocus();
        }
    }

    @Override
    protected void onDestroy() {
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

    // ---- paired Bluetooth devices ----------------------------------------------------------------------

    /**
     * One row per paired device with whether it is connected. A row opens that device's page in TV Settings,
     * where Connect or Disconnect is the first item: apps cannot connect a device themselves.
     */
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
            deviceRows.put(device.getAddress(), row);
        }
        // Connection state comes from each profile: A2DP for speakers and earbuds, HID for remotes and
        // keyboards, headset and LE audio for the rest. The proxies answer asynchronously.
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

    private void markConnected(String address) {
        View row = deviceRows.get(address);
        if (row != null) runOnUiThread(() -> ((TextView) row.findViewById(R.id.status)).setText(R.string.qs_connected));
    }

    private void addSection(GridLayout tiles, String title) {
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(0xB3FFFFFF);
        label.setTextSize(12);
        label.setAllCaps(true);
        label.setLetterSpacing(0.08f);
        label.setPadding(dp(8), dp(14), dp(8), dp(2));
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
        PackageManager pm = context.getPackageManager();
        List<Intent> targets = new ArrayList<>();
        for (Intent candidate : new Intent[] {
                new Intent("android.settings.SLICE_SETTINGS").setPackage(TV_SETTINGS)
                        .putExtra("slice_uri", "content://com.android.tv.settings.accessories.sliceprovider/general"),
                new Intent("com.google.android.intent.action.CONNECT_INPUT"),
                new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)}) {
            if (pm.resolveActivity(candidate, 0) != null) targets.add(candidate);
        }
        return targets;
    }

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
