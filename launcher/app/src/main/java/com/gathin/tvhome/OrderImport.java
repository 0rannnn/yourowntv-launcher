package com.gathin.tvhome;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The app order handed over by the setup app, and a copy of ours it can read back. Both are plain package lists, one
 * per line, in the theme base folder (external files, written through ThemeProvider or the shell):
 * <ul>
 * <li>{@code order-import.txt}: the order to adopt, read once at Home's start and deleted. The setup app writes the
 * order the TV's own Google TV home had ("Your apps"), or, on an update, the owner's order from before.</li>
 * <li>{@code order-backup.txt}: Home's own order, rewritten whenever it is saved, so an update (which starts the
 * launcher clean) keeps it.</li>
 * </ul>
 * The order is one for the whole launcher (Order, order.txt), so every theme shows the apps the same way.
 */
final class OrderImport {
    static final String IMPORT = "order-import.txt";
    static final String BACKUP = "order-backup.txt";
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+");

    private OrderImport() {}

    /** The order waiting to be adopted (packages), removed as it is read; null when there is none. */
    static List<String> take(Context ctx) {
        File base = Config.baseDir(ctx);
        File file = base == null ? null : new File(base, IMPORT);
        if (file == null || !file.isFile()) return null;
        List<String> packages = new ArrayList<>();
        try {
            for (String line : new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).split("\n")) {
                String p = line.trim();
                if (PACKAGE.matcher(p).matches() && !packages.contains(p)) packages.add(p);
            }
        } catch (Exception ignored) {
        }
        file.delete();
        return packages.isEmpty() ? null : packages;
    }

    /** Our order (tile keys "package/activity") as a package list the setup app can read back. */
    static void backup(Context ctx, List<String> keys) {
        File base = Config.baseDir(ctx);
        if (base == null) return;
        StringBuilder out = new StringBuilder();
        List<String> seen = new ArrayList<>();
        for (String key : keys) {
            int slash = key.indexOf('/');
            String p = slash > 0 ? key.substring(0, slash) : key;
            if (!seen.contains(p)) {
                seen.add(p);
                out.append(p).append('\n');
            }
        }
        try (FileOutputStream f = new FileOutputStream(new File(base, BACKUP))) {
            f.write(out.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }
}
