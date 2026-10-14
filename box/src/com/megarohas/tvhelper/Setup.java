package com.megarohas.tvhelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** am broadcast -n com.megarohas.tvhelper/.Setup --es token <secret> — the Mac gives the same one to the tablet. */
public class Setup extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent i) {
        String t = i.getStringExtra("token");
        if (t != null) c.getSharedPreferences("tvhelper", Context.MODE_PRIVATE).edit().putString("token", t).apply();
        setResultData(token(c).isEmpty() ? "no token" : "token set");
    }

    static String token(Context c) {
        return c.getSharedPreferences("tvhelper", Context.MODE_PRIVATE).getString("token", "");
    }
}
