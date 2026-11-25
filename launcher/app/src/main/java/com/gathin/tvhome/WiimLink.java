package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * A WiiM (LinkPlay) sound system on the home network, the one this TV plays through over HDMI ARC: its volume set
 * straight over Wi-Fi/Ethernet, not over HDMI-CEC. Over CEC the WiiM takes about 2 steps a second and extra presses
 * queue up (they kept running after the key was let go, 2026-11-21); over its HTTP API a volume is an exact number,
 * set in ~150 ms, so a held key can ramp fast and stop exactly at the release (VolumeHold.pressExternal). The TV's own
 * level follows by itself (the WiiM reports every change over CEC).
 *
 * Found once by trying the TV's own /24 subnet for the WiiM API, keeping the one on its HDMI input (mode 49) whose
 * volume matches the TV's; remembered (prefs wiim/ip) and checked again before use. Private addresses only; the WiiM
 * has a self-signed certificate, accepted for that one local address.
 */
final class WiimLink {
    private static final String TAG = "tvhome-wiim";
    private static final String PREFS = "wiim";
    private static volatile String ip;
    private static volatile boolean searching;

    private WiimLink() {
    }

    /** The WiiM's address when one is known and answering; null otherwise (then CEC steps are used). */
    static String ready() {
        return ip;
    }

    /** In the background: the remembered WiiM still there, else a search. tvLevel is the TV's volume (0-100). */
    static void findAsync(Context context, int tvLevel) {
        if (searching || ip != null) return;
        searching = true;
        new Thread(() -> {
            try {
                SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                String known = prefs.getString("ip", null);
                if (known != null && onHdmi(known)) {
                    ip = known;
                    return;
                }
                String found = search(tvLevel);
                if (found != null) {
                    ip = found;
                    prefs.edit().putString("ip", found).apply();
                    Log.i(TAG, "WiiM on HDMI found at " + found);
                }
            } finally {
                searching = false;
            }
        }, "wiim-find").start();
    }

    /** Forget the address (it stopped answering): the next hold uses CEC and a new search starts. */
    static void lost() {
        ip = null;
    }

    /** The WiiM's volume 0-100, or -1. */
    static int volume(String host) {
        String s = get(host, "getPlayerStatus", 600);
        if (s == null) return -1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"vol\"\\s*:\\s*\"(\\d+)\"").matcher(s);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** Sets the WiiM's volume (0-100); true when it answered OK. */
    static boolean setVolume(String host, int volume) {
        String s = get(host, "setPlayerCmd:vol:" + Math.max(0, Math.min(100, volume)), 800);
        return s != null && s.trim().startsWith("OK");
    }

    private static boolean onHdmi(String host) {
        String s = get(host, "getPlayerStatus", 800);
        return s != null && s.replace(" ", "").contains("\"mode\":\"49\"");
    }

    private static String search(int tvLevel) {
        List<String> bases = new ArrayList<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        String s = a.getHostAddress();
                        bases.add(s.substring(0, s.lastIndexOf('.') + 1));
                    }
                }
            }
        } catch (Exception e) {
            return null;
        }
        ExecutorService pool = Executors.newFixedThreadPool(24);
        try {
            List<String> onHdmi = new ArrayList<>();
            for (String base : bases) {
                List<Future<String>> jobs = new ArrayList<>();
                for (int i = 1; i < 255; i++) {
                    String host = base + i;
                    jobs.add(pool.submit(() -> onHdmi(host) ? host : null));
                }
                for (Future<String> f : jobs) {
                    try {
                        String h = f.get(3, TimeUnit.SECONDS);
                        if (h != null) onHdmi.add(h);
                    } catch (Exception ignored) {
                    }
                }
            }
            if (onHdmi.size() == 1) return onHdmi.get(0);
            for (String h : onHdmi) if (volume(h) == tvLevel) return h;   // several on HDMI: the one at this TV's level
            return null;
        } finally {
            pool.shutdownNow();
        }
    }

    private static String get(String host, String command, int timeoutMs) {
        HttpsURLConnection c = null;
        try {
            if (!InetAddress.getByName(host).isSiteLocalAddress()) return null;   // the home network only
            c = (HttpsURLConnection) new URL("https://" + host + "/httpapi.asp?command=" + command).openConnection();
            c.setSSLSocketFactory(LOCAL_TLS);
            c.setHostnameVerifier(ANY_HOST);
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setUseCaches(false);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] b = new byte[2048];
                for (int n; (n = in.read(b)) > 0 && out.size() < 16384; ) out.write(b, 0, n);
                return out.toString("UTF-8");
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** The WiiM's certificate is self-signed: accepted, but only ever used for a private (home network) address. */
    private static final HostnameVerifier ANY_HOST = (h, s) -> true;
    private static final SSLSocketFactory LOCAL_TLS = localTls();

    private static SSLSocketFactory localTls() {
        try {
            TrustManager[] all = {new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String type) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String type) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, all, null);
            return ctx.getSocketFactory();
        } catch (Exception e) {
            return null;
        }
    }
}
