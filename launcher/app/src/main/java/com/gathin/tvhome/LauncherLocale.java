package com.gathin.tvhome;

import android.content.Context;
import android.content.res.Configuration;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * locale: the language the launcher shows itself in, instead of the TV's. A China-market Xiaomi offers Chinese and
 * English only, so a Vietnamese owner saw the launcher in Chinese although it is translated into Vietnamese.
 * Each activity wraps its base context here; an empty or malformed tag leaves the TV's language.
 */
final class LauncherLocale {
    private static final Pattern TAG = Pattern.compile("[a-z]{2,3}(-[A-Za-z0-9]{2,8})?");

    private LauncherLocale() {
    }

    /** The Locale for a config tag such as "vi" or "pt-BR", or null for "" or anything that is not a plain tag. */
    static Locale parse(String tag) {
        if (tag == null || !TAG.matcher(tag.trim()).matches()) return null;
        return Locale.forLanguageTag(tag.trim());
    }

    private static final String PREFS = "launcher_language";
    /** Bumped by set(), so an activity built in the old language knows to rebuild. */
    static int revision;

    /**
     * The language button in the quick settings panel: a tag ("iw", "en") that wins over the theme's locale, kept in
     * the launcher's own storage so it outlives theme pushes; "" goes back to the theme's or the TV's language.
     */
    static void set(Context context, String tag) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("tag", tag).commit();
        revision++;
    }

    /** base with the configured language, or base itself when locale is off. */
    static Context wrap(Context base) {
        String chosen = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("tag", "");
        Locale locale = parse(chosen != null && !chosen.isEmpty() ? chosen : Config.load(base).locale);
        if (locale == null) return base;
        // Formatters that take no Context (the clock's date, the lunar line) follow the default locale.
        Locale.setDefault(locale);
        Configuration c = new Configuration(base.getResources().getConfiguration());
        c.setLocale(locale);
        return base.createConfigurationContext(c);
    }
}
