package com.megarohas.tvvoice;

import android.content.Context;
import android.content.SharedPreferences;

/** Settings: where the TV is, its pinned certificate, quiet mode, normal volume, timers. */
final class Store {

    static final String TV_HOST = "tv_host";
    static final String TV_PIN = "tv_pin";             // SHA-256 of the TV's certificate, set by pairing
    static final String DRY = "dry";                   // quiet mode: hear and log, press nothing
    static final String BASE = "base_volume";
    static final String HELPER_TOKEN = "helper_token"; // shared secret with the helper on the box
    static final String CARD = "card";                 // «what's playing» card over the clock
    static final String SLEEP_AT = "sleep_at";         // wall-clock ms to put the TV to sleep, 0 = none
    static final String SLEEP_EPISODE = "sleep_episode"; // title to sleep after, "" = none
    static final String CLIPS = "clips";               // keep 4 s of audio around each detection

    private Store() {
    }

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences("tvvoice", Context.MODE_PRIVATE);
    }

    /** The TV box's address; empty until found on the network (TvRemote.host()). */
    static String host(Context c) {
        return get(c).getString(TV_HOST, "");
    }

    static boolean dry(Context c) {
        return get(c).getBoolean(DRY, true); // a new install only listens until told otherwise
    }

    static int base(Context c) {
        return get(c).getInt(BASE, 10);
    }

    static boolean clips(Context c) {
        return get(c).getBoolean(CLIPS, false);
    }

    static boolean card(Context c) {
        return get(c).getBoolean(CARD, false); // off unless switched on: it covered the clock
    }
}
