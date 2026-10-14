package com.megarohas.tvvoice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.IOException;

/**
 * Control from the Mac: adb shell am broadcast -n com.megarohas.tvvoice/.AdbControl --es <key> <value>
 *   dry true|false · base N · host IP · card true|false · clips true|false · helper_token T (make link)
 *   pair 1 · code XXXXXX · key <keycode> · say <phrase> · link <url> · model download
 * Guarded by DUMP, which the adb shell holds and regular apps can't get.
 */
public class AdbControl extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, final Intent i) {
        SharedPreferences.Editor e = Store.get(c).edit();
        if (i.hasExtra("dry")) e.putBoolean(Store.DRY, Boolean.parseBoolean(i.getStringExtra("dry")));
        if (i.hasExtra("base")) e.putInt(Store.BASE, Integer.parseInt(i.getStringExtra("base")));
        if (i.hasExtra("host")) e.putString(Store.TV_HOST, i.getStringExtra("host"));
        if (i.hasExtra("card")) e.putBoolean(Store.CARD, Boolean.parseBoolean(i.getStringExtra("card")));
        if (i.hasExtra("clips")) e.putBoolean(Store.CLIPS, Boolean.parseBoolean(i.getStringExtra("clips")));
        if (i.hasExtra("helper_token")) e.putString(Store.HELPER_TOKEN, i.getStringExtra("helper_token"));
        e.apply();
        if ("download".equals(i.getStringExtra("model"))) ModelDownload.start(c); // (re)fetch over the network
        final ListenService s = ListenService.live;
        if (s == null) {
            setResultData("сервис не запущен: am start -n com.megarohas.tvvoice/.StartActivity");
            return;
        }
        final PendingResult pr = goAsync();
        new Thread(() -> {
            String r = "";
            try {
                if (i.hasExtra("pair")) {
                    s.tv.startPairing();
                    r = "код на экране ТВ";
                } else if (i.hasExtra("code")) {
                    s.tv.finishPairing(i.getStringExtra("code"));
                    r = "сопряжено";
                } else if (i.hasExtra("key")) {
                    if (!s.tv.ready(4000)) throw new IOException("нет связи с ТВ");
                    s.tv.key(Integer.parseInt(i.getStringExtra("key")));
                    r = "нажато";
                } else if (i.hasExtra("link")) {
                    if (!s.tv.ready(4000)) throw new IOException("нет связи с ТВ");
                    s.tv.appLink(i.getStringExtra("link"));
                    r = "ссылка отправлена";
                } else if (i.hasExtra("say")) {
                    s.simulate(i.getStringExtra("say"));
                    r = "выполняю";
                }
            } catch (Exception ex) {
                r = "ошибка: " + ex.getMessage();
            }
            Log.i(ListenService.TAG, "adb: " + r);
            pr.setResultData(r + "\n" + s.status());
            pr.finish();
        }).start();
    }
}
