package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Editing this TV's home screen from a phone (or a Mac), no computer and no IP address to type. The quick panel's
 * "Edit from the phone" tile shows a QR code with this TV's address and a 4-digit code; the phone opens the page this
 * small web server sends (assets/phone/index.html), types the code once, and gets a token it keeps. With the token it
 * reads and changes the theme in use: the name in both languages, the place for the weather, the wallpapers (adding
 * photos from the phone, removing them), the rotation and the theme's own switches, and which theme is in use.
 * <p>
 * Changes go straight into the launcher's own theme files (no ADB) and Home rebuilds with them: at once when it is on
 * screen, else when it comes back (the settings revision moves). Only the home network reaches it; a wrong code five
 * times makes a new one.
 */
final class PhoneEditor {
    static final int PORT = 8899;
    private static final String PREFS = "phone_editor";
    /** Settings that belong to the person, not to one theme: written into every theme on the TV. */
    private static final Set<String> PERSONAL = new HashSet<>(Arrays.asList("greeting_name", "greeting_name_he",
            "location_label", "location_label_he", "weather_lat", "weather_lon"));
    private static final SecureRandom RANDOM = new SecureRandom();

    private static PhoneEditor running;
    /** Home on screen: rebuild now when the phone changes something (MainActivity sets and clears it). */
    static volatile Runnable onChange;

    private final Context context;
    private ServerSocket server;
    private volatile String code = "";
    private volatile long codeUntil;
    private volatile int wrong;

    private PhoneEditor(Context context) {
        this.context = context.getApplicationContext();
    }

    /** The server, started if it is not running yet; null when the port cannot be opened. */
    static synchronized PhoneEditor start(Context context) {
        if (running != null) return running;
        PhoneEditor editor = new PhoneEditor(context);
        try {
            editor.server = new ServerSocket(PORT);
        } catch (IOException e) {
            return null;
        }
        Thread accept = new Thread(() -> {
            while (!editor.server.isClosed()) {
                try {
                    Socket socket = editor.server.accept();
                    Thread t = new Thread(() -> editor.serve(socket), "phone-editor");
                    t.setDaemon(true);
                    t.start();
                } catch (IOException ignored) {
                }
            }
        }, "phone-editor-accept");
        accept.setDaemon(true);
        accept.start();
        running = editor;
        return editor;
    }

    /** A new 4-digit code, good for 15 minutes (the quick panel shows it with the QR code). */
    String newCode() {
        code = String.format(Locale.ROOT, "%04d", RANDOM.nextInt(10000));
        codeUntil = System.currentTimeMillis() + 15 * 60_000L;
        wrong = 0;
        return code;
    }

    /** The address the QR code opens: http://<this TV>:8899/ */
    String url() {
        return "http://" + ownAddress(context) + ":" + PORT + "/";
    }

    static String ownAddress(Context context) {
        try {
            ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
            LinkProperties props = cm == null ? null : cm.getLinkProperties(cm.getActiveNetwork());
            if (props != null) {
                for (LinkAddress address : props.getLinkAddresses()) {
                    if (address.getAddress() instanceof Inet4Address) return address.getAddress().getHostAddress();
                }
            }
        } catch (RuntimeException ignored) {
        }
        return "0.0.0.0";
    }

    // ---- requests ---------------------------------------------------------------------------------------------

    private static final class Request {
        String method = "", path = "";
        final Map<String, String> query = new HashMap<>();
        final Map<String, String> headers = new HashMap<>();
        byte[] body = new byte[0];

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private void serve(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(30_000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            String line = readLine(in);
            if (line == null || line.isEmpty()) return;
            String[] parts = line.split(" ");
            Request r = new Request();
            r.method = parts[0];
            String target = parts.length > 1 ? parts[1] : "/";
            int q = target.indexOf('?');
            r.path = q >= 0 ? target.substring(0, q) : target;
            if (q >= 0) {
                for (String pair : target.substring(q + 1).split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0) r.query.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
                }
            }
            String header;
            while ((header = readLine(in)) != null && !header.isEmpty()) {
                int colon = header.indexOf(':');
                if (colon > 0) r.headers.put(header.substring(0, colon).trim().toLowerCase(Locale.ROOT), header.substring(colon + 1).trim());
            }
            int length = 0;
            try {
                length = Integer.parseInt(r.headers.getOrDefault("content-length", "0"));
            } catch (NumberFormatException ignored) {
            }
            if (length > 16_000_000) length = 0;
            if (length > 0) {
                byte[] body = new byte[length];
                int read = 0;
                while (read < length) {
                    int n = in.read(body, read, length - read);
                    if (n < 0) break;
                    read += n;
                }
                r.body = body;
            }
            int status = 200;
            String type = "application/json; charset=utf-8";
            byte[] data;
            try {
                Object out = handle(r);
                if (out instanceof byte[]) {
                    data = (byte[]) out;
                    type = r.path.equals("/") ? "text/html; charset=utf-8" : "image/jpeg";
                } else if (out == null) {
                    status = 404;
                    data = "{\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8);
                } else {
                    data = out.toString().getBytes(StandardCharsets.UTF_8);
                }
            } catch (SecurityException e) {
                status = 401;
                data = new JSONObject().put("error", e.getMessage()).toString().getBytes(StandardCharsets.UTF_8);
            } catch (Exception e) {
                status = 500;
                data = new JSONObject().put("error", String.valueOf(e.getMessage())).toString().getBytes(StandardCharsets.UTF_8);
            }
            OutputStream out = s.getOutputStream();
            out.write(("HTTP/1.1 " + status + " OK\r\nContent-Type: " + type + "\r\nContent-Length: " + data.length
                    + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(data);
            out.flush();
        } catch (Exception ignored) {
            // the phone went away mid-request
        }
    }

    private Object handle(Request r) throws Exception {
        if (r.path.equals("/") || r.path.equals("/index.html")) return asset("phone/index.html");
        if (r.path.equals("/api/pair")) return pair(new JSONObject(r.text()).optString("code"));
        // Everything else needs the token from the pairing.
        String token = r.headers.getOrDefault("x-token", r.query.getOrDefault("t", ""));
        if (token.isEmpty() || !prefs().getStringSet("tokens", new HashSet<>()).contains(token)) {
            throw new SecurityException("not paired");
        }
        switch (r.path) {
            case "/api/state":
                return state();
            case "/api/config":
                return saveConfig(new JSONObject(r.text()));
            case "/api/theme":
                return pickTheme(new JSONObject(r.text()).optString("slot"));
            case "/api/wallpaper":
                return "GET".equals(r.method) ? thumbnail(r.query.get("name"), r.query.get("slot")) : addWallpaper(r.body);
            case "/api/wallpaper/delete":
                return deleteWallpaper(new JSONObject(r.text()).optString("name"));
            default:
                return null;
        }
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private synchronized JSONObject pair(String typed) throws Exception {
        boolean ok = !code.isEmpty() && System.currentTimeMillis() < codeUntil && code.equals(typed.trim());
        if (!ok) {
            if (++wrong >= 5) code = "";   // too many tries: the TV has to show a new code
            return new JSONObject().put("ok", false).put("error", code.isEmpty()
                    ? "הקוד לא בתוקף. לפתוח שוב את \"עריכה מהטלפון\" בטלוויזיה" : "הקוד לא נכון");
        }
        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        StringBuilder token = new StringBuilder();
        for (byte b : raw) token.append(String.format(Locale.ROOT, "%02x", b));
        Set<String> tokens = new HashSet<>(prefs().getStringSet("tokens", new HashSet<>()));
        if (tokens.size() > 20) tokens.clear();
        tokens.add(token.toString());
        prefs().edit().putStringSet("tokens", tokens).commit();
        code = "";
        return new JSONObject().put("ok", true).put("token", token.toString());
    }

    // ---- the theme ---------------------------------------------------------------------------------------------

    private File active() {
        return Config.dir(context);
    }

    private List<File> allThemes() {
        List<File> out = new ArrayList<>();
        File base = Config.baseDir(context);
        if (base == null) return out;
        if (new File(base, Config.FILE_NAME).isFile()) out.add(base);
        for (String name : Config.themes(context)) out.add(new File(new File(base, Config.THEMES), name));
        return out;
    }

    private JSONObject state() throws Exception {
        File dir = active();
        JSONObject cfg = readJson(new File(dir, Config.FILE_NAME));
        JSONArray themes = new JSONArray();
        String picked = HomeToggles.theme(context);
        for (String name : Config.themes(context)) {
            JSONObject t = readJson(new File(new File(new File(Config.baseDir(context), Config.THEMES), name), Config.FILE_NAME));
            List<File> images = WallpaperRotation.images(new File(new File(new File(Config.baseDir(context), Config.THEMES), name),
                    ThemeFiles.WALLPAPERS));
            themes.put(new JSONObject().put("slot", name).put("name", t.optString("theme_name", name))
                    .put("name_he", t.optString("theme_name_he", t.optString("theme_name", name)))
                    .put("cover", images.isEmpty() ? "" : images.get(0).getName())
                    .put("active", name.equals(picked)));
        }
        // The person's photos: the Images theme's (ImagesTheme), whichever theme is in use.
        JSONArray walls = new JSONArray();
        for (File f : ImagesTheme.photos(context)) walls.put(f.getName());
        return new JSONObject().put("config", cfg).put("themes", themes).put("active", picked)
                .put("wallpapers", walls).put("tv", Build.MANUFACTURER + " " + Build.MODEL);
    }

    /** Merges the phone's changes: the person's details into every theme, the rest into the theme in use. */
    private synchronized JSONObject saveConfig(JSONObject changes) throws Exception {
        File activeDir = active();
        for (File dir : allThemes()) {
            boolean isActive = dir.getAbsolutePath().equals(activeDir.getAbsolutePath());
            File file = new File(dir, Config.FILE_NAME);
            JSONObject cfg = readJson(file);
            boolean changed = false;
            for (java.util.Iterator<String> keys = changes.keys(); keys.hasNext(); ) {
                String key = keys.next();
                if (!key.matches("[a-z0-9_]{1,40}")) continue;
                if (!isActive && !PERSONAL.contains(key)) continue;
                Object value = changes.get(key);
                if (value == JSONObject.NULL) cfg.remove(key);
                else cfg.put(key, value);
                changed = true;
            }
            if (changed) writeJson(file, cfg);
        }
        changed();
        return new JSONObject().put("ok", true);
    }

    private JSONObject pickTheme(String slot) throws Exception {
        if (!slot.isEmpty() && !Config.themes(context).contains(slot)) throw new IllegalArgumentException("no such theme");
        HomeToggles.setTheme(context, slot);
        changed();
        return new JSONObject().put("ok", true);
    }

    /**
     * A photo from the phone into the Images theme (ImagesTheme), kept on the TV; the first one makes the theme. Home
     * switches to it, so the photo shows at once.
     */
    private synchronized JSONObject addWallpaper(byte[] data) throws Exception {
        if (data.length == 0 || data.length > 16_000_000) throw new IllegalArgumentException("התמונה גדולה מדי (עד 16MB)");
        String name = ImagesTheme.add(context, toWallpaper(data));
        HomeToggles.setTheme(context, ImagesTheme.SLOT);
        changed();
        return new JSONObject().put("ok", true).put("name", name);
    }

    /** A photo out of the Images theme; the last one removes the theme. */
    private synchronized JSONObject deleteWallpaper(String name) throws Exception {
        if (!name.matches("[A-Za-z0-9_.\\-]+") || name.contains("..")) throw new IllegalArgumentException("bad name");
        ImagesTheme.delete(context, name);
        changed();
        return new JSONObject().put("ok", true);
    }

    /** A small JPEG of a wallpaper (about 480 px wide) for the phone's grid. */
    private byte[] thumbnail(String name, String slot) throws Exception {
        if (name == null || !name.matches("[A-Za-z0-9_.\\-]+") || name.contains("..")) return null;
        File dir = active();
        if (slot != null && !slot.isEmpty()) {
            if (!Config.themes(context).contains(slot)) return null;
            dir = new File(new File(Config.baseDir(context), Config.THEMES), slot);
        }
        File file = new File(new File(dir, ThemeFiles.WALLPAPERS), name);
        if (!file.isFile()) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = Math.max(1, bounds.outWidth / 480);
        Bitmap small = BitmapFactory.decodeFile(file.getPath(), opts);
        if (small == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        small.compress(Bitmap.CompressFormat.JPEG, 72, out);
        return out.toByteArray();
    }

    /**
     * Any picture the TV can read (JPEG, PNG, WebP, and HEIC/HEIF from iPhones and Samsungs, which phone browsers often
     * cannot open themselves) made into a 1920x1080 JPEG, cropped from the middle as a wallpaper fills the screen.
     * Already 1920x1080 JPEG (the page did it): sent on as it is.
     */
    static byte[] toWallpaper(byte[] data) throws java.io.IOException {
        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IllegalArgumentException("הטלוויזיה לא מצליחה לקרוא את התמונה הזאת");
        boolean jpeg = data.length > 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8;
        if (jpeg && bounds.outWidth == 1920 && bounds.outHeight == 1080) return data;
        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (bounds.outWidth / (opts.inSampleSize * 2) >= 1920 && bounds.outHeight / (opts.inSampleSize * 2) >= 1080) opts.inSampleSize *= 2;
        android.graphics.Bitmap src = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        if (src == null) throw new IllegalArgumentException("הטלוויזיה לא מצליחה לקרוא את התמונה הזאת");
        android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(1920, 1080, android.graphics.Bitmap.Config.ARGB_8888);
        float k = Math.max(1920f / src.getWidth(), 1080f / src.getHeight());
        float w = src.getWidth() * k, h = src.getHeight() * k;
        android.graphics.Canvas canvas = new android.graphics.Canvas(out);
        canvas.drawBitmap(src, null, new android.graphics.RectF((1920 - w) / 2, (1080 - h) / 2, (1920 + w) / 2, (1080 + h) / 2),
                new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        src.recycle();
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, bytes);
        out.recycle();
        return bytes.toByteArray();
    }

    /** Home rebuilds: now if it is on screen, else when it comes back (the settings revision moves). */
    private void changed() {
        SharedPreferences choices = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY, choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).commit();
        Runnable now = onChange;
        if (now != null) now.run();
    }

    // ---- files --------------------------------------------------------------------------------------------------

    private static JSONObject readJson(File file) {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
            if (text.startsWith("﻿")) text = text.substring(1);
            return new JSONObject(text);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** Written next to the old one and moved over it, so Home never reads half a file. */
    private static void writeJson(File file, JSONObject json) throws Exception {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(json.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(file)) {
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(json.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            tmp.delete();
        }
    }

    private byte[] asset(String path) throws IOException {
        try (InputStream in = context.getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') line.write(c);
            if (line.size() > 8192) break;
        }
        if (c < 0 && line.size() == 0) return null;
        return line.toString("UTF-8");
    }
}
