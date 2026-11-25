package com.gathin.tvhome;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Holding a volume key raises or lowers the volume quickly and evenly, as modern TVs do, on every TV, including
 * firmware that takes the remote's volume keys before any app sees them (Formovie: com.fengmi.platform steps about 4
 * times a second and the key never reaches the key helper).
 *
 * The remote's raw key events are readable only by the shell, so a small shell-side watcher (started over adb by the
 * setup app, com.gathin.kit's Kit.VOLUME_WATCHER, and by phone-kit/update_tvs.py) runs {@code getevent} and streams
 * its lines to this server on
 * 127.0.0.1:{@link #PORT}. A volume key down starts our own repeat after {@link #DELAY_MS} (a tap stays the
 * firmware's single step), every {@link #INTERVAL_MS}, until the key comes up, any other key goes down, the volume
 * reaches the top or the bottom, or {@link #MAX_HOLD_MS} pass (so a lost release can never run the volume away).
 */
final class VolumeHold {
    private static final String TAG = "VolumeHold";
    static final int PORT = 8897;
    /** A press shorter than this stays the TV's single step; holding longer starts our repeat. */
    static final long DELAY_MS = 300;
    /** One step every 30 ms while held: about 33 steps a second, 0 to 100 in about 3 seconds. */
    static final long INTERVAL_MS = 30;
    /**
     * A repeat never runs longer than this, whatever happens to the key's release (a lost "up" line, the watcher
     * going away): 4 seconds take the volume from 0 to the top, and holding on just keeps it there.
     */
    static final long MAX_HOLD_MS = 4000;
    private static VolumeHold active;
    /** Whether the shell watcher is connected now (when not, Home asks the setup app to start it). */
    static volatile boolean live;

    private final Context context;
    private final AudioManager audio;
    /** Every check and step runs on the volume thread, never on the one that draws Home (VolumeThread). */
    private final Handler main = VolumeThread.get();
    private volatile boolean running = true;
    private ServerSocket server;
    private Runnable repeat;
    private int heldKey;

    private VolumeHold(Context context) {
        this.context = context.getApplicationContext();
        audio = context.getSystemService(AudioManager.class);
    }

    static synchronized void start(Context context) {
        if (active != null) return;
        VolumeHold hold = new VolumeHold(context);
        if (hold.audio == null) return;
        active = hold;
        new Thread(hold::serve, "volume-hold").start();
        // No watcher a little after Home starts (after a reboot, or a TV set up before this): the setup app starts it.
        hold.main.postDelayed(hold.askForWatcher, 15_000);
        hold.main.post(hold.sample);
        // Notes an external sound device (ARC/eARC, Bluetooth) at once, while it is connected, so the moments after
        // a later power-on, when ARC is not back yet, already count as external (externalOutput).
        hold.main.post(() -> {
            // A WiiM on the network is looked for at once, so the first hold already ramps fast (WiimLink).
            if (externalOutput(hold.context)) WiimLink.findAsync(hold.context, hold.level());
        });
    }

    static synchronized void stop() {
        if (active == null) return;
        active.running = false;
        try {
            if (active.server != null) active.server.close();
        } catch (Exception ignored) {
        }
        active.main.post(active::release);
        active = null;
        live = false;
    }

    private void serve() {
        try (ServerSocket socket = new ServerSocket(PORT, 2, InetAddress.getByName("127.0.0.1"))) {
            server = socket;
            while (running) {
                try (Socket client = socket.accept()) {
                    if (!client.getInetAddress().isLoopbackAddress()) continue;
                    live = true;
                    BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while (running && (line = in.readLine()) != null) handle(line);
                } catch (Exception e) {
                    if (running) Log.w(TAG, "watcher: " + e.getMessage());
                } finally {
                    live = false;
                    main.post(this::release);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "no server: " + e.getMessage());
            synchronized (VolumeHold.class) {
                if (active == this) active = null;
            }
        }
    }

    /**
     * "/dev/input/event11: EV_KEY KEY_VOLUMEUP DOWN" (getevent -ql). A volume key down starts the repeat, its up (or
     * any other key going down) stops it; everything else is ignored.
     */
    private void handle(String line) {
        if (!line.contains("EV_KEY")) return;
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 4) return;
        String code = parts[parts.length - 2], state = parts[parts.length - 1];
        int key = "KEY_VOLUMEUP".equals(code) ? AudioManager.ADJUST_RAISE
                : "KEY_VOLUMEDOWN".equals(code) ? AudioManager.ADJUST_LOWER : 0;
        if (key == 0) {
            if ("DOWN".equals(state)) main.post(this::release);
            return;
        }
        if ("DOWN".equals(state)) {
            // The meter moves at once with the press (on an external sound device it would otherwise trail the
            // device's slow reports); nothing is sent anywhere for this.
            VolumeMeter.pressed(key == AudioManager.ADJUST_RAISE ? 1 : -1);
            main.post(() -> press(key));
        } else if ("UP".equals(state)) main.post(this::release);
    }

    /** While no watcher is connected: asks the setup app to start one, then again every 5 minutes. */
    private final Runnable askForWatcher = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (!live) {
                try {
                    context.sendBroadcast(new android.content.Intent("com.gathin.kit.action.VOLUME_WATCHER")
                            .setPackage("com.gathin.kit"));
                } catch (RuntimeException ignored) {
                }
            }
            main.postDelayed(this, 5 * 60_000);
        }
    };

    private void press(int direction) {
        release();
        if (externalOutput()) {
            pressExternal(direction);
            return;
        }
        heldKey = direction;
        long startedAt = SystemClock.uptimeMillis();
        int before = earlierLevel;
        repeat = new Runnable() {
            private boolean first = true;

            @Override
            public void run() {
                long now = SystemClock.uptimeMillis();
                if (first) {
                    first = false;
                    // The helper swallows this key and repeats it itself (TCL): this stands aside. And a press the TV
                    // did not take (the level did not move: a stray signal from another device) never becomes a run.
                    if ((VolumeKeys.consuming && now - VolumeKeys.lastKeyAt < 1500) || level() == before) {
                        release();
                        return;
                    }
                }
                // Stops by itself: at the release (release()), at the top or the bottom, if the sound moves to an
                // external device meanwhile, and after MAX_HOLD_MS even if the release never came.
                if (now - startedAt > MAX_HOLD_MS + DELAY_MS || externalOutput()) {
                    release();
                    return;
                }
                try {
                    int level = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                    int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    if ((direction == AudioManager.ADJUST_RAISE && level >= max) || (direction == AudioManager.ADJUST_LOWER && level <= 0)) {
                        release();
                        return;
                    }
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0);
                } catch (RuntimeException ignored) {
                }
                main.postDelayed(this, INTERVAL_MS);
            }
        };
        main.postDelayed(repeat, DELAY_MS);
    }

    // ---- a held key on an external sound device (soundbar / WiiM over HDMI ARC) --------------------------------------
    // Oran's rule was "never touch eARC"; on 2026-11-25 he OK'd this one exception: hold-to-ramp there. That TV's
    // firmware stops repeating a held key once our key helper is on (a 6-second hold gave the WiiM 2 steps), and the
    // helper must stay (the Settings button). So while the key is held we send the steps, but never like the old
    // runaway (2026-11-21): one step at a time, each only after the device confirms the last (the TV's level moves
    // on its <Report Audio Status>), so nothing queues; and it stops the moment the key is released (the watcher sees
    // the real release), at 0 or the top, on any other key, or after EXTERNAL_MAX_MS.

    /** The TV itself sends 2 steps for a press (at once and 0.4 s later): ours start after those. */
    static final long EXTERNAL_DELAY_MS = 800;
    /** How long to wait for the device to confirm a step; no answer by then and the hold stops (never a blind step). */
    static final long EXTERNAL_CONFIRM_MS = 1500;
    /** A hold never runs longer than this, whatever happens to the release. */
    static final long EXTERNAL_MAX_MS = 15_000;

    /** The network ramp (a WiiM): it starts this long after the press, after the TV's own first CEC step. */
    static final long NETWORK_DELAY_MS = 250;

    /**
     * Steps per second while a key is held on the network ramp, by how long it has been held: slow at first, faster
     * the longer it is held (as LG TVs do). Over the network a volume is an exact number, so a fast ramp cannot queue.
     * 2026-11-25, Oran: "make the ramp up come quicker" (was 3/5/8/12 from 1.2 s steps).
     */
    static float rampRate(long heldMs) {
        if (heldMs < 600) return 4f;
        if (heldMs < 1200) return 7f;
        if (heldMs < 2000) return 11f;
        if (heldMs < 3000) return 16f;
        return 22f;
    }

    private void pressExternal(int direction) {
        String wiim = WiimLink.ready();
        if (wiim == null) {
            WiimLink.findAsync(context, level());
        } else {
            pressNetwork(direction, wiim);
            return;
        }
        heldKey = direction;
        long startedAt = SystemClock.uptimeMillis();
        int before = earlierLevel;
        repeat = new Runnable() {
            private int waitingFrom = -1;   // the level when our last step went out, -1 when none is in flight
            private long sentAt;

            @Override
            public void run() {
                if (heldKey != direction) return;   // released (release() also removes this)
                long now = SystemClock.uptimeMillis();
                if (now - startedAt > EXTERNAL_MAX_MS) {
                    release();
                    return;
                }
                int level;
                int max;
                try {
                    level = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                    max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                } catch (RuntimeException e) {
                    release();
                    return;
                }
                if (waitingFrom < 0 && before >= 0 && level == before && now - startedAt < EXTERNAL_DELAY_MS + 100) {
                    // The TV did not take the press (the level never moved): a stray signal, not a hold.
                    release();
                    return;
                }
                if (waitingFrom >= 0 && level == waitingFrom) {
                    // Our last step is not confirmed yet: wait for the device, never stack a second one on it.
                    if (now - sentAt > EXTERNAL_CONFIRM_MS) {
                        release();   // the device stopped answering: stop rather than guess
                        return;
                    }
                    main.postDelayed(this, 50);
                    return;
                }
                if ((direction == AudioManager.ADJUST_RAISE && level >= max) || (direction == AudioManager.ADJUST_LOWER && level <= 0)) {
                    release();
                    return;
                }
                try {
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0);
                } catch (RuntimeException e) {
                    release();
                    return;
                }
                waitingFrom = level;
                sentAt = now;
                main.postDelayed(this, 50);
            }
        };
        main.postDelayed(repeat, EXTERNAL_DELAY_MS);
    }

    /** A held key with a WiiM on the network: its volume set directly, faster the longer the key is held. */
    private void pressNetwork(int direction, String wiim) {
        heldKey = direction;
        long startedAt = SystemClock.uptimeMillis();
        int sign = direction == AudioManager.ADJUST_RAISE ? 1 : -1;
        repeat = new Runnable() {
            private int target = -1;
            private float owed;
            private long lastAt;

            @Override
            public void run() {
                if (heldKey != direction) return;   // released
                long now = SystemClock.uptimeMillis();
                if (now - startedAt > EXTERNAL_MAX_MS) {
                    release();
                    return;
                }
                if (target < 0) {
                    target = WiimLink.volume(wiim);   // where the press (and the TV's own first step) left it
                    if (target < 0) {
                        WiimLink.lost();
                        release();
                        return;
                    }
                    lastAt = now;
                }
                owed += rampRate(now - startedAt) * (now - lastAt) / 1000f;
                lastAt = now;
                int steps = (int) owed;
                if (steps > 0) {
                    owed -= steps;
                    int next = Math.max(0, Math.min(100, target + sign * steps));
                    if (next == target) {   // at 0 or 100
                        release();
                        return;
                    }
                    // The request itself takes ~150 ms (this thread waits for it): the ramp's natural tick.
                    if (heldKey != direction) return;
                    if (!WiimLink.setVolume(wiim, next)) {
                        WiimLink.lost();
                        release();
                        return;
                    }
                    target = next;
                    VolumeMeter.shown(target);
                }
                main.postDelayed(this, 40);
            }
        };
        main.postDelayed(repeat, NETWORK_DELAY_MS);
    }

    private void release() {
        if (repeat != null) main.removeCallbacks(repeat);
        repeat = null;
        heldKey = 0;
    }

    /**
     * The volume a little before now (sampled every 100 ms, 200 ms old): what it was before the press, even when the
     * TV took its first step before the press reached here.
     */
    private volatile int earlierLevel = -1;
    private int recentLevel = -1;
    private final Runnable sample = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (heldKey == 0) {
                earlierLevel = recentLevel;
                recentLevel = level();
            }
            main.postDelayed(this, 100);
        }
    };

    private boolean externalOutput() {
        return externalOutput(context);
    }

    /** Remembered once a TV has had its sound on an external device (see externalOutput). */
    private static final String AUDIO_PREFS = "audio_output";
    private static final String EXTERNAL_SEEN = "external_seen";
    private static volatile boolean externalSeen;

    /**
     * Whether the volume belongs to something other than the TV's own speaker (HDMI ARC/eARC, Bluetooth, USB): then
     * nothing of ours sends a volume step, ever (Oran's rule: never touch eARC).
     *
     * Once a TV has had such a device it stays "external" for good. Asking only where media plays right now was not
     * enough: on the balcony Xiaomi (a WiiM over HDMI ARC) the ARC link is down for a few seconds after every power-on
     * while the TV and the WiiM shake hands, Android says "speaker" meanwhile, and on 2026-11-24 a volume key held in
     * that window let VolumeHold send 24 steps (20:22:34), which the TV then handed to the WiiM when ARC came back.
     */
    static boolean externalOutput(Context context) {
        if (externalSeen) return true;
        android.content.SharedPreferences prefs = context.getSharedPreferences(AUDIO_PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(EXTERNAL_SEEN, false)) {
            externalSeen = true;
            android.util.Log.i("tvhome-volume", "external sound device on this TV (remembered): no volume steps of ours");
            return true;
        }
        if (externalNow(context)) {
            externalSeen = true;
            prefs.edit().putBoolean(EXTERNAL_SEEN, true).apply();
            android.util.Log.i("tvhome-volume", "external sound device on this TV (ARC/eARC/Bluetooth): no volume steps of ours, from now on");
            return true;
        }
        return false;
    }

    /** Right now: media routed to a non-speaker output, or any ARC/eARC/Bluetooth/USB audio output connected. */
    private static boolean externalNow(Context context) {
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio == null) return false;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                android.media.AudioAttributes media = new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA).build();
                for (android.media.AudioDeviceInfo d : audio.getAudioDevicesForAttributes(media)) {
                    if (d.getType() != android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return true;
                }
            }
            // Any Android: a connected external sound device counts, wherever media happens to go this second.
            for (android.media.AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                switch (d.getType()) {
                    case android.media.AudioDeviceInfo.TYPE_HDMI_ARC:
                    case 29:   // TYPE_HDMI_EARC
                    case android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                    case 26:   // TYPE_BLE_HEADSET
                    case 27:   // TYPE_BLE_SPEAKER
                    case android.media.AudioDeviceInfo.TYPE_USB_DEVICE:
                    case android.media.AudioDeviceInfo.TYPE_USB_HEADSET:
                        // Not optical (TYPE_LINE_DIGITAL): some TVs always list it, plugged in or not, and it is no
                        // part of HDMI eARC; counting it would end the fast hold on a TV that only has its speaker.
                        return true;
                    default:
                        break;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private int level() {
        try {
            return audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** For the meter: true while a key is held through the watcher. */
    static boolean holding() {
        VolumeHold hold = active;
        return hold != null && hold.heldKey != 0;
    }

    static long now() {
        return SystemClock.uptimeMillis();
    }
}
