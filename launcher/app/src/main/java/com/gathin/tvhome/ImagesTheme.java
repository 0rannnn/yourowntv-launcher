package com.gathin.tvhome;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The person's own photos (sent from the phone, or from the installer's setup) as a theme of their own, themes/images
 * ("Images" / "תמונות"), kept on the TV and picked like any other theme. It is made the first time a photo arrives:
 * the look and settings of the Apple theme (or, without one, the theme in use), with the photos as its only
 * wallpapers. Photos that an older version put into the other themes (90-photo-*) move here.
 */
final class ImagesTheme {
    private static final String TAG = "ImagesTheme";
    static final String SLOT = "images";
    /** The name every sent photo gets (the phone editor and the installer): 90-photo-TIME.jpg. */
    static final String PHOTO_PREFIX = "90-photo-";
    /** The theme files besides config.json that the photos theme takes from the theme it starts from. */
    private static final String[] SHARED_FILES = {HeaderExtras.CLOCK_FONT, HebrewFont.FILE, NavSound.FILE};

    private ImagesTheme() {}

    static File dir(Context ctx) {
        File base = Config.baseDir(ctx);
        return base == null ? null : new File(new File(base, Config.THEMES), SLOT);
    }

    static File wallpapers(Context ctx) {
        File dir = dir(ctx);
        return dir == null ? null : new File(dir, ThemeFiles.WALLPAPERS);
    }

    /** The photos in the theme, oldest first. */
    static List<File> photos(Context ctx) {
        return WallpaperRotation.images(wallpapers(ctx));
    }

    /** A photo (a 1920x1080 JPEG) into the theme, which is made if it is not there yet. Returns the file's name. */
    static synchronized String add(Context ctx, byte[] jpeg) throws IOException {
        File walls = wallpapers(ctx);
        if (walls == null) throw new IOException("no storage");
        if (!walls.isDirectory() && !walls.mkdirs()) throw new IOException("no wallpapers folder");
        String name = String.format(Locale.ROOT, "%s%d.jpg", PHOTO_PREFIX, System.currentTimeMillis());
        try (OutputStream out = new FileOutputStream(new File(walls, name))) {
            out.write(jpeg);
        }
        ensure(ctx);
        return name;
    }

    /**
     * Deletes one photo; the last one takes the whole theme with it (and Home goes back to the Apple theme, or the
     * base one, when it was in use).
     */
    static synchronized void delete(Context ctx, String name) throws IOException {
        File walls = wallpapers(ctx);
        File picture = walls == null ? null : new File(walls, name);
        if (picture == null || !picture.isFile()) throw new IllegalArgumentException("no such picture");
        if (!picture.delete()) throw new IOException("could not delete");
        if (!photos(ctx).isEmpty()) return;
        deleteTree(dir(ctx));
        if (SLOT.equals(HomeToggles.theme(ctx))) HomeToggles.setTheme(ctx, Config.themes(ctx).contains("apple") ? "apple" : "");
    }

    /**
     * Brings the theme up to date: photos left in other themes move in, and with any photo here and no config.json
     * yet, the theme is made. Cheap when there is nothing to do; called when Home starts. Returns true when it changed
     * anything.
     */
    static synchronized boolean ensure(Context ctx) {
        File base = Config.baseDir(ctx);
        File images = dir(ctx);
        if (base == null || images == null) return false;
        boolean changed = false;
        try {
            changed = gatherPhotos(ctx, base, images);
            File config = new File(images, Config.FILE_NAME);
            if (!config.isFile() && !photos(ctx).isEmpty()) {
                make(ctx, base, images);
                changed = true;
            }
        } catch (Exception e) {
            Log.w(TAG, "images theme: " + e);
        }
        return changed;
    }

    /** 90-photo-* pictures in the other themes' wallpapers (and their time-of-day groups) move into this theme. */
    private static boolean gatherPhotos(Context ctx, File base, File images) throws Exception {
        List<File> others = new ArrayList<>();
        others.add(base);
        for (String slot : Config.themes(ctx)) if (!SLOT.equals(slot)) others.add(new File(new File(base, Config.THEMES), slot));
        File walls = new File(images, ThemeFiles.WALLPAPERS);
        boolean moved = false;
        for (File theme : others) {
            File[] found = new File(theme, ThemeFiles.WALLPAPERS).listFiles((d, n) -> n.startsWith(PHOTO_PREFIX));
            if (found == null || found.length == 0) continue;
            if (!walls.isDirectory() && !walls.mkdirs()) return moved;
            List<String> names = new ArrayList<>();
            for (File photo : found) {
                File to = new File(walls, photo.getName());
                if (to.exists() ? photo.delete() : photo.renameTo(to)) names.add(photo.getName());
            }
            if (names.isEmpty()) continue;
            moved = true;
            File file = new File(theme, Config.FILE_NAME);
            JSONObject cfg = readJson(file);
            JSONObject moods = cfg == null ? null : cfg.optJSONObject("wallpaper_moods");
            if (moods != null) {
                for (java.util.Iterator<String> keys = moods.keys(); keys.hasNext(); ) {
                    String key = keys.next();
                    JSONArray group = moods.optJSONArray(key);
                    if (group == null) continue;
                    JSONArray kept = new JSONArray();
                    for (int i = 0; i < group.length(); i++) if (!names.contains(group.optString(i))) kept.put(group.opt(i));
                    moods.put(key, kept);
                }
                writeJson(file, cfg);
            }
        }
        return moved;
    }

    /** The theme's config.json and files, from the Apple theme (or the one in use), showing only the photos. */
    private static void make(Context ctx, File base, File images) throws Exception {
        File from = new File(new File(base, Config.THEMES), "apple");
        if (!new File(from, Config.FILE_NAME).isFile()) {
            from = Config.dir(ctx);
            if (from == null || from.equals(images) || !new File(from, Config.FILE_NAME).isFile()) from = base;
        }
        JSONObject cfg = readJson(new File(from, Config.FILE_NAME));
        if (cfg == null) cfg = new JSONObject();
        cfg.remove("wallpaper_moods");
        cfg.put("theme_name", "Images").put("theme_name_he", "תמונות").put("wallpaper", true);
        if (cfg.optInt("wallpaper_rotation_minutes", 0) <= 0) cfg.put("wallpaper_rotation_minutes", 3);
        for (String name : SHARED_FILES) {
            File src = new File(from, name);
            if (src.isFile()) copy(src, new File(images, name));
        }
        writeJson(new File(images, Config.FILE_NAME), cfg);
    }

    private static void copy(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }
    }

    private static void deleteTree(File file) {
        if (file == null) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    private static JSONObject readJson(File file) {
        if (!file.isFile()) return null;
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return new JSONObject(out.toString("UTF-8"));
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeJson(File file, JSONObject json) throws Exception {
        File tmp = new File(file.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(json.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(file)) {
            file.delete();
            if (!tmp.renameTo(file)) throw new IOException("could not save " + file.getName());
        }
    }
}
