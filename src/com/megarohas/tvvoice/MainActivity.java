package com.megarohas.tvvoice;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Status, pairing with the TV and the quiet/live switch. Built in code — no layouts. */
public class MainActivity extends Activity {

    private TextView status;
    private Button model, pair, mode, cardToggle, overlay, battery;
    private LinearLayout codeRow;
    private EditText code;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(20), dp(20), dp(20));

        TextView h = new TextView(this);
        h.setText("Пульт");
        h.setTextSize(26);
        col.addView(h);

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, dp(12), 0, dp(12));
        col.addView(status);

        model = button("Скачать модель распознавания (45 МБ)", v -> {
            ModelDownload.start(this);
            refresh();
        });
        col.addView(model);
        pair = button("Сопрячь с ТВ", v -> pair());
        col.addView(pair);

        codeRow = new LinearLayout(this);
        code = new EditText(this);
        code.setHint("код с экрана ТВ");
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        code.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6), new InputFilter.AllCaps()});
        codeRow.addView(code, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        codeRow.addView(button("Готово", v -> sendCode()));
        codeRow.setVisibility(View.GONE);
        col.addView(codeRow);

        mode = button("", v -> {
            Store.get(this).edit().putBoolean(Store.DRY, !Store.dry(this)).apply();
            refresh();
        });
        col.addView(mode);
        cardToggle = button("", v -> {
            Store.get(this).edit().putBoolean(Store.CARD, !Store.card(this)).apply();
            refresh();
        });
        col.addView(cardToggle);
        overlay = button("Разрешить надписи поверх часов", v -> startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        col.addView(overlay);
        battery = button("Не ограничивать в фоне", v -> startActivity(new Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))));
        col.addView(battery);

        TextView help = new TextView(this);
        help.setText("Скажи «Пульт, …» и команду:\n"
                + "пауза / стоп · играй / продолжи\n"
                + "громче / тише (чуть громче, чуть тише) · нормальная громкость · выключи / включи звук\n"
                + "перемотай вперёд / назад — на 10 с; «перемотай назад на сорок секунд», «перемотай на семь минут вперёд»…\n"
                + "начни с начала · пропусти заставку · следующая / предыдущая серия\n"
                + "что играет · сколько осталось\n"
                + "выключи через полчаса / через час… · выключи после этой серии · отмени таймер\n"
                + "включи / выключи телевизор (или приставку)\n"
                + "окей · назад · влево · вправо · вверх · вниз · домой — и ещё 6 секунд без «пульт»\n"
                + "включи ютуб / кинопоиск / окко / плекс / вк видео\n"
                + "найди на ютубе … (можно паузой: «найди на ютубе» — и сам запрос)\n\n"
                + "Можно в два шага: «Пульт» — звук ТВ на секунду пропадёт — и сразу команда.\n"
                + "В тихом режиме ничего не нажимается: приложение только записывает, что услышало.");
        help.setPadding(0, dp(20), 0, 0);
        help.setAlpha(0.7f);
        col.addView(help);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(col);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}, 1);
        } else {
            ListenService.start(this, false);
        }
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            ListenService.start(this, false);
        }
    }

    private void refresh() {
        ListenService s = ListenService.live;
        status.setText(s != null ? s.status() : "Не запущено");
        boolean paired = s != null && s.tv.paired();
        pair.setText(paired ? "Сопрячь с ТВ заново" : "Сопрячь с ТВ");
        mode.setText(Store.dry(this) ? "Включить управление ТВ (сейчас тихий режим)" : "Перейти в тихий режим");
        cardToggle.setText(Store.card(this) ? "Убрать карточку «что играет»" : "Показывать карточку «что играет»");
        overlay.setVisibility(Settings.canDrawOverlays(this) ? View.GONE : View.VISIBLE);
        battery.setVisibility(getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName())
                ? View.GONE : View.VISIBLE);
        boolean haveModel = ModelDownload.installed(this);
        model.setVisibility(haveModel ? View.GONE : View.VISIBLE);
        model.setEnabled(!ModelDownload.status.startsWith("скачиваю"));
        if (!haveModel && !ModelDownload.status.isEmpty()) model.setText(ModelDownload.status);
    }

    private void pair() {
        final ListenService s = ListenService.live;
        if (s == null) return;
        toast("Подключаюсь к ТВ…");
        new Thread(() -> {
            try {
                s.tv.startPairing();
                ui.post(() -> {
                    codeRow.setVisibility(View.VISIBLE);
                    code.requestFocus();
                    toast("Введи код с экрана телевизора");
                });
            } catch (Exception e) {
                ui.post(() -> toast("Не вышло: " + e.getMessage()));
            }
        }).start();
    }

    private void sendCode() {
        final ListenService s = ListenService.live;
        final String c = code.getText().toString();
        if (s == null) return;
        new Thread(() -> {
            try {
                s.tv.finishPairing(c);
                ui.post(() -> {
                    codeRow.setVisibility(View.GONE);
                    toast("Готово, ТВ сопряжён");
                });
            } catch (Exception e) {
                ui.post(() -> toast("Не вышло: " + e.getMessage()));
            }
        }).start();
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
