package com.megarohas.tvvoice;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase-0 acoustics test. Shows the phrases to say from the couch, records the
 * room at 16 kHz and leaves <name>.wav + <name>.tsv (what was on screen when)
 * in the app's external files dir for scoring on the Mac.
 *
 * Started only from the Mac (guarded by DUMP, which the adb shell holds), see
 * tools/mictest.sh. Extras:
 *   name    output file name without extension
 *   script  items split by '|': [!]text[@seconds][#mark]; '!' = an instruction
 *           rather than a phrase to say; a mark is logged as "MARK <mark>" so
 *           the Mac can pause/play the TV box in sync
 *   step    default seconds per item (5)
 *   source  MediaRecorder.AudioSource (6 = VOICE_RECOGNITION)
 *   ch      1 or 2 channels
 */
public class MicTestActivity extends Activity {

    static final String TAG = "tvvoice";
    static final int RATE = 16000;

    static final String PHRASES_A = "Пульт, пауза|Пульт, громче|Пульт, назад|Пульт, играй"
            + "|Пульт, тише|Пульт, вперёд|Пульт, назад на минуту|Пульт, выключи звук";
    static final String PHRASES_B = "Пульт, громче|Пульт, пауза|Пульт, вперёд|Пульт, тише"
            + "|Пульт, играй|Пульт, выключи звук|Пульт, назад|Пульт, назад на минуту";
    static final String DEFAULT_SCRIPT =
            "!Сядь, где обычно смотришь ТВ. Говори обычным голосом, как будто зовёшь кого-то в комнате@10#pause"
            + "|" + PHRASES_A
            + "|!Сейчас включится звук ТВ. Говори так же, не громче@8#play"
            + "|" + PHRASES_B
            + "|!Теперь молчим, пусть говорит телевизор@120";

    /** One screen of the script; start is milliseconds of recorded audio. */
    static final class Item {
        final String text, mark;
        final boolean say;
        final long ms;
        long start;

        Item(String text, boolean say, long ms, String mark) {
            this.text = text;
            this.say = say;
            this.ms = ms;
            this.mark = mark;
        }
    }

    private final List<Item> items = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile long frames; // frames captured so far, the test's only clock
    private volatile double levelDb = -90;
    private volatile boolean recording;
    private Thread worker;
    private File wav;
    private String name;
    private int source, channels, shown = -1;
    private long totalMs, lastLogSec = -1;
    private TextView top, center, bottom;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long t = frames * 1000 / RATE;
            if (t >= totalMs) {
                complete();
                return;
            }
            int i = Math.max(shown, 0);
            while (i + 1 < items.size() && items.get(i + 1).start <= t) i++;
            if (i != shown) show(i);
            Item it = items.get(i);
            bottom.setText(meter(levelDb) + "    " + (it.start + it.ms - t + 999) / 1000 + " с");
            long sec = t / 1000;
            if (sec != lastLogSec) {
                lastLogSec = sec;
                Log.i(TAG, "LVL " + sec + " " + Math.round(levelDb));
            }
            ui.postDelayed(this, 50);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setShowWhenLocked(true);
        setTurnScreenOn(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Color.BLACK);
        col.setPadding(dp(32), dp(24), dp(32), dp(24));
        top = text(36, 0.6f);
        center = text(96, 1f);
        center.setTypeface(Typeface.DEFAULT_BOLD);
        bottom = text(32, 0.6f);
        col.addView(top);
        col.addView(center, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        col.addView(bottom);
        setContentView(col);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        name = extra("name", "rec-" + System.currentTimeMillis());
        source = getIntent().getIntExtra("source", MediaRecorder.AudioSource.VOICE_RECOGNITION);
        channels = getIntent().getIntExtra("ch", 1) == 2 ? 2 : 1;
        items.addAll(parse(extra("script", DEFAULT_SCRIPT), getIntent().getIntExtra("step", 5)));
        for (Item it : items) {
            it.start = totalMs;
            totalMs += it.ms;
        }
        if (items.isEmpty()) {
            fail("Пустой сценарий", "ERR empty-script");
        } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("Нет доступа к микрофону", "ERR no-permission");
        } else {
            wav = new File(getExternalFilesDir(null), name + ".wav");
            recording = true;
            worker = new Thread(this::record, "mictest");
            worker.start();
            ui.post(tick);
            Log.i(TAG, "START " + name + " source=" + source + " ch=" + channels + " total=" + totalMs / 1000 + "s");
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (recording) { // left mid-test: keep what was recorded
            stopRecording();
            Log.w(TAG, "ABORTED at " + frames * 1000 / RATE + " ms");
            finish();
        }
    }

    private void show(int i) {
        shown = i;
        Item it = items.get(i);
        top.setText(it.say ? "Скажи:" : "");
        center.setTextSize(it.say ? 96 : 44);
        center.setText(it.text);
        Log.i(TAG, "ITEM " + i + " " + it.text);
        if (it.mark != null) Log.i(TAG, "MARK " + it.mark);
    }

    private void complete() {
        stopRecording();
        top.setText("");
        center.setTextSize(64);
        center.setText("Готово, спасибо!");
        bottom.setText("");
        Log.i(TAG, "DONE " + wav.getAbsolutePath());
        ui.postDelayed(this::finish, 3000);
    }

    private void stopRecording() {
        recording = false;
        ui.removeCallbacks(tick);
        try {
            worker.join(2000);
        } catch (InterruptedException ignored) {
        }
        writeTsv();
    }

    private void fail(String msg, String log) {
        recording = false;
        ui.removeCallbacks(tick);
        Log.e(TAG, log);
        top.setText("");
        center.setTextSize(40);
        center.setText(msg);
        ui.postDelayed(this::finish, 5000);
    }

    private void record() {
        int mask = channels == 2 ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO;
        int chunk = RATE / 10 * 2 * channels; // 100 ms of 16-bit audio
        int min = AudioRecord.getMinBufferSize(RATE, mask, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec = null;
        try (RandomAccessFile out = new RandomAccessFile(wav, "rw")) {
            rec = new AudioRecord(source, RATE, mask, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, chunk * 4));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("AudioRecord not initialized");
            out.setLength(0);
            out.write(new byte[44]); // header goes in once the length is known
            rec.startRecording();
            byte[] buf = new byte[chunk];
            while (recording) {
                int n = rec.read(buf, 0, buf.length);
                if (n < 0) throw new IOException("read " + n);
                out.write(buf, 0, n);
                frames += n / (2 * channels);
                levelDb = db(buf, n);
            }
            rec.stop();
            out.seek(0);
            out.write(header(out.length() - 44));
        } catch (Exception e) {
            Log.e(TAG, "record", e);
            ui.post(() -> fail("Ошибка записи: " + e.getMessage(), "ERR " + e));
        } finally {
            if (rec != null) rec.release();
        }
    }

    private void writeTsv() {
        File f = new File(getExternalFilesDir(null), name + ".tsv");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write("# rate=" + RATE + " ch=" + channels + " source=" + source + " model=" + Build.MODEL + "\n");
            for (Item it : items) {
                w.write(it.start + "\t" + (it.start + it.ms) + "\t" + (it.say ? "say" : "info") + "\t"
                        + it.text + "\t" + (it.mark == null ? "" : it.mark) + "\n");
            }
        } catch (IOException e) {
            Log.w(TAG, "tsv", e);
        }
    }

    private byte[] header(long dataLen) {
        ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        b.put(ascii("RIFF")).putInt((int) (36 + dataLen)).put(ascii("WAVE"))
                .put(ascii("fmt ")).putInt(16).putShort((short) 1).putShort((short) channels)
                .putInt(RATE).putInt(RATE * channels * 2).putShort((short) (channels * 2)).putShort((short) 16)
                .put(ascii("data")).putInt((int) dataLen);
        return b.array();
    }

    static List<Item> parse(String script, int stepSec) {
        List<Item> out = new ArrayList<>();
        for (String raw : script.split("\\|")) {
            String s = raw.trim();
            String mark = null;
            int h = s.lastIndexOf('#');
            if (h >= 0) {
                mark = s.substring(h + 1).trim();
                s = s.substring(0, h).trim();
            }
            long ms = stepSec * 1000L;
            int a = s.lastIndexOf('@');
            if (a >= 0) {
                try {
                    ms = (long) (Double.parseDouble(s.substring(a + 1).trim()) * 1000);
                    s = s.substring(0, a).trim();
                } catch (NumberFormatException ignored) {
                }
            }
            boolean say = !s.startsWith("!");
            if (!say) s = s.substring(1).trim();
            if (!s.isEmpty()) out.add(new Item(s, say, ms, mark));
        }
        return out;
    }

    private static double db(byte[] b, int n) {
        double sum = 0;
        for (int i = 0; i + 1 < n; i += 2) {
            int s = (short) ((b[i] & 0xff) | (b[i + 1] << 8));
            sum += (double) s * s;
        }
        double rms = Math.sqrt(sum / Math.max(1, n / 2));
        return rms < 1 ? -90 : 20 * Math.log10(rms / 32768.0);
    }

    /** "микрофон ▮▮▮▯▯▯▯▯▯▯" for -60…-10 dBFS, so the couch can see it hears. */
    private static String meter(double dbfs) {
        int bars = (int) Math.max(0, Math.min(10, Math.round((dbfs + 60) / 5)));
        StringBuilder sb = new StringBuilder("микрофон ");
        for (int i = 0; i < 10; i++) sb.append(i < bars ? '▮' : '▯');
        return sb.toString();
    }

    private TextView text(float sp, float alpha) {
        TextView v = new TextView(this);
        v.setTextColor(Color.WHITE);
        v.setTextSize(sp);
        v.setAlpha(alpha);
        v.setGravity(Gravity.CENTER);
        return v;
    }

    private String extra(String key, String def) {
        String v = getIntent().getStringExtra(key);
        return v == null || v.isEmpty() ? def : v;
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
