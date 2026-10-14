package com.megarohas.tvvoice;

import org.json.JSONArray;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What can follow «пульт» and what each phrase means. Actions are strings,
 * "kind" or "kind:argument" (seek:-30000, launch:<package>, sleep:45…), carried
 * out by ListenService. Every word must exist in the Vosk model's vocabulary —
 * tools/vocab-check.py checks this file against it.
 */
final class Commands {

    static final String WAKE = "пульт";
    static final String YOUTUBE = "com.google.android.youtube.tv";

    // Android key codes (the remote protocol uses the same numbers)
    static final int KEY_DPAD_LEFT = 21, KEY_DPAD_RIGHT = 22, KEY_VOLUME_UP = 24, KEY_VOLUME_DOWN = 25,
            KEY_POWER = 26, KEY_NEXT = 87, KEY_PREVIOUS = 88, KEY_PLAY = 126, KEY_PAUSE = 127, KEY_MUTE = 164;

    private static final Map<String, String> ACTIONS = new LinkedHashMap<>();
    private static final Map<String, String> APPS = new HashMap<>();

    static {
        add("pause", "пауза", "стоп", "останови");
        add("play", "играй", "продолжи", "поехали");
        add("vol:2", "громче");
        add("vol:1", "чуть громче");
        add("vol:-2", "тише");
        add("vol:-1", "чуть тише");
        add("mute", "выключи звук", "без звука");
        add("unmute", "включи звук");
        add("normal", "нормальная громкость");

        add("seek:10000", "вперёд", "перемотай вперёд");
        add("seek:-10000", "назад", "перемотай назад");
        String[][] amounts = {
                {"на десять секунд", "10000"}, {"на тридцать секунд", "30000"},
                {"на минуту", "60000"}, {"на одну минуту", "60000"}, {"на две минуты", "120000"},
                {"на три минуты", "180000"}, {"на пять минут", "300000"}, {"на десять минут", "600000"},
                {"на пятнадцать минут", "900000"}, {"на двадцать минут", "1200000"},
        };
        for (String[] a : amounts) {
            add("seek:" + a[1], "вперёд " + a[0], "перемотай вперёд " + a[0]);
            add("seek:-" + a[1], "назад " + a[0], "перемотай назад " + a[0]);
        }
        add("seekto:0", "в начало");
        add("intro", "пропусти заставку");
        add("next", "следующая серия");
        add("prev", "предыдущая серия");
        add("info:now", "что играет", "что сейчас играет");
        add("info:left", "сколько осталось", "сколько осталось до конца");

        String[][] times = {
                {"через пять минут", "5"}, {"через десять минут", "10"}, {"через пятнадцать минут", "15"},
                {"через двадцать минут", "20"}, {"через полчаса", "30"}, {"через тридцать минут", "30"},
                {"через сорок минут", "40"}, {"через сорок пять минут", "45"}, {"через час", "60"},
                {"через полтора часа", "90"}, {"через два часа", "120"},
        };
        for (String[] t : times) add("sleep:" + t[1], "выключи " + t[0], "выключи телевизор " + t[0]);
        add("sleep:episode", "выключи после этой серии", "выключи после серии");
        add("sleep:cancel", "отмени таймер");
        add("tv:on", "включи телевизор");
        add("tv:off", "выключи телевизор");


        app(YOUTUBE, "YouTube", "включи ютуб", "включи ютюб");
        app("ru.kinopoisk.tv", "Кинопоиск", "включи кинопоиск");
        app("ru.more.play", "Okko", "включи окко");
        app("com.plexapp.android", "Plex", "включи плекс");
        app("com.vk.tv", "VK Видео", "включи вк видео");
        APPS.put("ru.rutube.app", "Rutube");
        APPS.put("org.videolan.vlc", "VLC");

        // the query itself follows these, in the same breath or after a pause
        add("search:" + YOUTUBE, "найди", "найди на ютубе", "найди на ютюбе");
        add("search:com.vk.tv", "найди в вк видео");
    }

    private Commands() {
    }

    private static void add(String action, String... phrases) {
        for (String p : phrases) ACTIONS.put(p, action);
    }

    private static void app(String pkg, String name, String... phrases) {
        APPS.put(pkg, name);
        add("launch:" + pkg, phrases);
    }

    /** Main grammar: «пульт» alone (arms the two-step mode) or «пульт <phrase>». */
    static String grammar() {
        JSONArray g = new JSONArray().put(WAKE);
        for (String p : ACTIONS.keySet()) g.put(WAKE + " " + p);
        return g.put("[unk]").toString();
    }

    /** Grammar while armed after a lone «пульт»: the phrase on its own. */
    static String bareGrammar() {
        JSONArray g = new JSONArray();
        for (String p : ACTIONS.keySet()) g.put(p);
        return g.put("[unk]").toString();
    }

    static String action(String phrase) {
        return ACTIONS.get(phrase);
    }

    static String app(String pkg) {
        String name = APPS.get(pkg);
        return name != null ? name : pkg;
    }

    static String label(String action) {
        String[] p = action.split(":", 2);
        String arg = p.length > 1 ? p[1] : "";
        switch (p[0]) {
            case "pause": return "⏸  Пауза";
            case "play": return "▶  Играю";
            case "vol": return arg.startsWith("-") ? "🔉  Тише" : "🔊  Громче";
            case "mute": return "🔇  Без звука";
            case "unmute": return "🔈  Звук включён";
            case "normal": return "🔈  Обычная громкость";
            case "seek": {
                long ms = Long.parseLong(arg);
                return (ms < 0 ? "⏪  −" : "⏩  +") + span(Math.abs(ms));
            }
            case "seekto": return "⏮  В начало";
            case "intro": return "⏩  Пропускаю заставку";
            case "next": return "⏭  Следующая серия";
            case "prev": return "⏮  Предыдущая серия";
            case "tv": return "on".equals(arg) ? "Включаю телевизор" : "⏻  Выключаю";
            case "launch": return "▶  " + app(arg);
            case "search": return "🔎  Что найти?";
            default: return "";
        }
    }

    /** 40 с · 1 мин 30 с · 1 ч 30 мин */
    static String span(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + " с";
        StringBuilder b = new StringBuilder();
        if (s >= 3600) b.append(s / 3600).append(" ч");
        if (s / 60 % 60 > 0) b.append(b.length() > 0 ? " " : "").append(s / 60 % 60).append(" мин");
        if (s % 60 > 0) b.append(b.length() > 0 ? " " : "").append(s % 60).append(" с");
        return b.toString();
    }

    /** 2 ч 05 мин · 36 мин · 40 с */
    static String human(long ms) {
        long s = Math.max(0, ms) / 1000;
        if (s < 60) return s + " с";
        long min = (s + 30) / 60;
        return min < 60 ? min + " мин" : min / 60 + " ч " + String.format(java.util.Locale.ROOT, "%02d", min % 60) + " мин";
    }

    static void press(TvRemote tv, int key, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            if (i > 0) Thread.sleep(150);
            tv.key(key);
        }
    }

    /**
     * Seeking without the helper, measured on YouTube for this box: FF/REW keys only
     * pause into a scrubber, while the D-pad works — the first press opens the seek
     * bar, every next one moves 10 s, and PLAY commits the position.
     */
    static void dpadSeek(TvRemote tv, int tens) throws Exception {
        int key = tens > 0 ? KEY_DPAD_RIGHT : KEY_DPAD_LEFT;
        for (int i = 0; i <= Math.abs(tens); i++) {
            tv.key(key);
            Thread.sleep(300);
        }
        Thread.sleep(400);
        tv.key(KEY_PLAY);
    }
}
