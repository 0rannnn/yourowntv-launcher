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
    static final long DELAY_MS = 350;
    static final long INTERVAL_MS = 50;
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
    private final Handler main = new Handler(Looper.getMainLooper());
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
        if ("DOWN".equals(state)) main.post(() -> press(key));
        else if ("UP".equals(state)) main.post(this::release);
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
        heldKey = direction;
        long startedAt = SystemClock.uptimeMillis();
        int before = earlierLevel;
        repeat = new Runnable() {
            @Override
            public void run() {
                if (SystemClock.uptimeMillis() - startedAt < DELAY_MS + INTERVAL_MS) {
                    // The first step only. The helper got this key itself (Xiaomi, TCL): it repeats a held key, so
                    // this stands aside. And a press the TV did not take (the level did not move: a stray signal
                    // from another device) is never turned into a run of steps.
                    if (SystemClock.uptimeMillis() - VolumeKeys.lastKeyAt < 1500 || level() == before) {
                        release();
                        return;
                    }
                }
                // Stops by itself: at the top or the bottom, and after MAX_HOLD_MS even if the release never came.
                if (SystemClock.uptimeMillis() - startedAt > MAX_HOLD_MS + DELAY_MS) {
                    release();
                    return;
                }
                try {
                    int now = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                    int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    if ((direction == AudioManager.ADJUST_RAISE && now >= max) || (direction == AudioManager.ADJUST_LOWER && now <= 0)) {
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
