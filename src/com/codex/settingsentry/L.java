package com.codex.settingsentry;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;

/** Minimal bilingual table. The helper avoids resource qualifiers so the
 *  in-app toggle can switch text without touching the system locale. */
final class L {
    static final String EN = "en", ZH = "zh";
    private static String lang = EN;
    private L() { }
    static void load(Context context) {
        lang = prefs(context).getString("lang", defaultLang());
    }
    static String current() { return lang; }
    static void set(Context context, String value) {
        lang = value;
        prefs(context).edit().putString("lang", value).apply();
    }
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("ui", Context.MODE_PRIVATE);
    }
    private static String defaultLang() {
        try {
            return Resources.getSystem().getConfiguration().locale
                    .getLanguage().startsWith("zh") ? ZH : EN;
        } catch (Exception ignored) {
            return EN;
        }
    }
    static String t(String en, String zh) { return ZH.equals(lang) ? zh : en; }
}
