package com.gathin.tvhome;

import java.util.List;
import java.util.regex.Pattern;

/** The files ThemeProvider may write or delete under Config.dir, by the URI's path segments. */
final class ThemeFiles {
    static final String WALLPAPERS = "wallpapers";
    /** The names tools/theme.sh accepts for a collection image, and the types WallpaperRotation reads. */
    private static final Pattern IMAGE = Pattern.compile("[A-Za-z0-9_.-]+\\.(jpg|jpeg|png|webp)",
            Pattern.CASE_INSENSITIVE);

    private ThemeFiles() {}

    /** A theme kept side by side: themes/NAME, with NAME of letters, digits, _ and -. */
    private static final Pattern SLOT = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    static boolean slotName(String name) {
        return name != null && SLOT.matcher(name).matches();
    }

    /**
     * The path relative to {@link Config#baseDir}, e.g. "config.json", "wallpapers/dusk.jpg" or, for a theme kept
     * side by side, "themes/classic/config.json"; null if refused.
     */
    static String path(List<String> segments) {
        if (segments != null && segments.size() > 2 && Config.THEMES.equals(segments.get(0)) && slotName(segments.get(1))) {
            String inner = themePath(segments.subList(2, segments.size()));
            return inner == null ? null : Config.THEMES + "/" + segments.get(1) + "/" + inner;
        }
        return themePath(segments);
    }

    /** "wallpapers" alone, or under themes/NAME: the collection folder a delete empties; null for anything else. */
    static String collection(List<String> segments) {
        if (isCollection(segments)) return WALLPAPERS;
        if (segments != null && segments.size() == 3 && Config.THEMES.equals(segments.get(0)) && slotName(segments.get(1))
                && WALLPAPERS.equals(segments.get(2))) {
            return Config.THEMES + "/" + segments.get(1) + "/" + WALLPAPERS;
        }
        return null;
    }

    private static String themePath(List<String> segments) {
        if (segments == null) return null;
        if (segments.size() == 1) {
            String name = segments.get(0);
            return Config.FILE_NAME.equals(name) || "wallpaper.jpg".equals(name) || "theme_revision".equals(name)
                    || HeaderExtras.CLOCK_FONT.equals(name) || NavSound.FILE.equals(name)
                    || HebrewFont.FILE.equals(name) || Ps5Home.AVATAR.equals(name)
                    || OrderImport.IMPORT.equals(name) || OrderImport.BACKUP.equals(name) ? name : null;
        }
        if (segments.size() == 2 && Ps5Home.ART.equals(segments.get(0)) && collectionImage(segments.get(1))) {
            return Ps5Home.ART + "/" + segments.get(1);
        }
        if (segments.size() == 2 && WALLPAPERS.equals(segments.get(0))
                && (collectionImage(segments.get(1)) || WallpaperCaptions.FILE_NAME.equals(segments.get(1)))) {
            return WALLPAPERS + "/" + segments.get(1);
        }
        return null;
    }

    /** True for "wallpapers" alone: a delete of it clears the collection's images. */
    static boolean isCollection(List<String> segments) {
        return segments != null && segments.size() == 1 && WALLPAPERS.equals(segments.get(0));
    }

    static boolean collectionImage(String name) {
        return name != null && !name.startsWith(".") && IMAGE.matcher(name).matches();
    }
}
