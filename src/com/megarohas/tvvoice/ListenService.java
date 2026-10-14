package com.megarohas.tvvoice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Listens all the time (a microphone foreground service) and turns «пульт …»
 * into actions on the TV box. Flip Clock stays on screen; feedback is a line
 * drawn over it (a beep was tried and voted down), plus the «what's playing»
 * card while something plays.
 */
public class ListenService extends Service implements TvRemote.Listener {

    static final String TAG = "tvvoice";
    static final String ACTION_REOPEN = "reopen";
    private static final int RATE = 16000;
    private static final int RING = RATE * 12; // the last 12 s: queries said in one breath, clips

    static volatile ListenService live;

    TvRemote tv;
    BoxHelper helper;
    volatile String hearing = "запускаюсь…";
    volatile String last = "";
    private volatile BoxHelper.Now now;  // refreshed every 2 s while the TV is on
    private volatile boolean helperUp;

    private final ExecutorService keys = Executors.newSingleThreadExecutor(); // TV commands in order, off the audio thread
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
    private final short[] ring = new short[RING];
    private long ringAbs; // frames ever written = frames fed to the main recognizer, so Vosk times map onto it
    private Overlay overlay;
    private NowCard card;
    private Recognizer armRec, free;
    private volatile boolean running, reopen;
    private volatile long armedUntil, dictateUntil;
    private volatile long navUntil;       // after a navigation key: the next commands need no «пульт»
    private volatile boolean armReset;    // armRec starts listening afresh (set from any thread, done on the audio one)
    private volatile String dictatePkg;
    private volatile boolean weMuted; // the TV was muted by us to hear the rest of a phrase
    private String lastAction = "";
    private boolean lastFromBare;
    private long lastActionAt;

    static void start(Context c, boolean reopenMic) {
        Intent i = new Intent(c, ListenService.class);
        if (reopenMic) i.setAction(ACTION_REOPEN);
        c.startForegroundService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("listen", "Пульт слушает", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, "listen")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Пульт слушает")
                .setContentText("«Пульт, пауза», «Пульт, громче», «Пульт, назад»…")
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                        PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(true)
                .build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        live = this;
        overlay = new Overlay(this);
        card = new NowCard(this);
        helper = new BoxHelper(this);
        tv = new TvRemote(this, this);
        tv.start();
        running = true;
        new Thread(this::listen, "listen").start();
        ticker.scheduleWithFixedDelay(this::tick, 2, 2, TimeUnit.SECONDS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_REOPEN.equals(intent.getAction())) reopen = true;
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        live = null;
        ticker.shutdownNow();
        tv.stop();
        keys.shutdownNow();
        overlay.hide();
        card.hide();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void changed() {
        // the screen polls status(); nothing to push
    }

    // ---- hearing --------------------------------------------------------------

    private void listen() {
        Model model = null;
        Recognizer rec = null;
        try {
            File dir;
            while ((dir = installModel()) == null) { // until downloaded in the app (or pushed by adb)
                hearing = ModelDownload.status.isEmpty() ? "нет модели распознавания — скачай её в приложении"
                        : ModelDownload.status;
                if (!running) return;
                SystemClock.sleep(2000);
            }
            hearing = "загружаю модель…";
            LibVosk.setLogLevel(LogLevel.WARNINGS);
            model = new Model(dir.getAbsolutePath());
            String grammar = Commands.grammar();
            try (FileWriter f = new FileWriter(new File(getExternalFilesDir(null), "grammar.json"))) {
                f.write(grammar); // for tools/eval.py --grammar: score recordings with exactly this
            }
            rec = new Recognizer(model, RATE, grammar);
            rec.setWords(true);
            armRec = new Recognizer(model, RATE, Commands.bareGrammar());
            free = new Recognizer(model, RATE); // no grammar: search queries
            short[] stereo = new short[RATE / 10 * 2];
            short[] mono = new short[RATE / 10];
            while (running) {
                AudioRecord mic = openMic();
                long zeroSince = 0;
                hearing = "слушаю";
                reopen = false;
                try {
                    while (running && !reopen) {
                        int n = mic.read(stereo, 0, stereo.length);
                        if (n < 0) throw new IllegalStateException("микрофон: ошибка " + n);
                        int frames = n / 2;
                        boolean zero = true;
                        for (int i = 0; i < frames; i++) { // both mics mixed, as tested from the couch
                            int v = (stereo[2 * i] + stereo[2 * i + 1]) / 2;
                            mono[i] = (short) v;
                            if (v != 0) zero = false;
                        }
                        long now = SystemClock.uptimeMillis();
                        // Android feeds a background app silence instead of failing: get brought forward
                        if (!zero) {
                            zeroSince = 0;
                        } else if (zeroSince == 0) {
                            zeroSince = now;
                        } else if (now - zeroSince > 5000) {
                            hearing = "микрофон отдаёт тишину — перезапускаюсь";
                            Log.w(TAG, hearing);
                            startActivity(new Intent(this, StartActivity.class).putExtra(ACTION_REOPEN, true)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                            zeroSince = now;
                        }
                        remember(mono, frames);
                        boolean fin = rec.acceptWaveForm(mono, frames); // always fed: its clock is ringAbs
                        if (dictateUntil != 0) {
                            dictate(mono, frames, now);
                            continue;
                        }
                        if (fin) heard(rec.getResult(), false);
                        if (armedUntil != 0 || navUntil > now) { // phrases without «пульт» count now
                            if (armReset) {
                                armRec.reset();
                                armReset = false;
                            }
                            if (armRec.acceptWaveForm(mono, frames)) heard(armRec.getResult(), true);
                            if (armedUntil != 0 && now > armedUntil) disarm(null);
                        }
                    }
                } finally {
                    mic.stop();
                    mic.release();
                }
            }
        } catch (Throwable t) {
            hearing = "ошибка: " + t.getMessage();
            Log.e(TAG, "listen", t);
        } finally {
            if (free != null) free.close();
            if (armRec != null) armRec.close();
            if (rec != null) rec.close();
            if (model != null) model.close();
        }
    }

    /**
     * The model arrives by adb in the external files dir, but Vosk can't open it
     * there (that storage goes through FUSE), so it is copied to internal storage
     * once — and again whenever a different one is pushed.
     */
    private File installModel() throws IOException {
        File src = new File(getExternalFilesDir(null), "model");
        File dst = new File(getFilesDir(), "model");
        File mark = new File(src, "am/final.mdl");
        if (mark.exists() && mark.length() != new File(dst, "am/final.mdl").length()) {
            hearing = "устанавливаю модель…";
            copy(src, dst);
        }
        return new File(dst, "am/final.mdl").exists() ? dst : null;
    }

    private static void copy(File from, File to) throws IOException {
        if (from.isDirectory()) {
            if (!to.isDirectory() && !to.mkdirs()) throw new IOException("не создать " + to);
            String[] names = from.list();
            if (names != null) for (String n : names) copy(new File(from, n), new File(to, n));
            return;
        }
        File tmp = new File(to.getPath() + ".part");
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        }
        if (!tmp.renameTo(to)) throw new IOException("не записать " + to);
    }

    private static AudioRecord openMic() {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord mic = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE * 8)); // 2 s of slack for a query decode
        if (mic.getState() != AudioRecord.STATE_INITIALIZED) {
            mic.release();
            throw new IllegalStateException("микрофон недоступен");
        }
        mic.startRecording();
        return mic;
    }

    /** A final result from Vosk: find «пульт» and what follows it within a second. */
    private void heard(String json, boolean armed) {
        try {
            JSONArray w = new JSONObject(json).optJSONArray("result");
            if (w == null) return;
            if (armed) { // after a lone «пульт» or a navigation key: phrases alone, several in a row
                java.util.List<String> words = new java.util.ArrayList<>();
                for (int i = 0; i < w.length(); i++) {
                    String word = w.getJSONObject(i).getString("word");
                    if (!"[unk]".equals(word) && !Commands.WAKE.equals(word)) words.add(word);
                }
                for (int i = 0; i < words.size(); ) {
                    String phrase = null, action = null;
                    for (int n = Math.min(5, words.size() - i); n >= 1 && action == null; n--) { // longest first
                        phrase = android.text.TextUtils.join(" ", words.subList(i, i + n));
                        action = Commands.action(phrase);
                        if (action != null) i += n;
                    }
                    if (action == null) {
                        i++;
                        continue;
                    }
                    boolean wasArmed = armedUntil != 0;
                    if (wasArmed) disarm(action);
                    if (action.startsWith("search:")) {
                        dictation(action.substring(7));
                        break;
                    }
                    act(phrase, action, wasArmed ? "после «пульт»" : "без «пульт»", true);
                }
                return;
            }
            for (int i = 0; i < w.length(); i++) {
                JSONObject wake = w.getJSONObject(i);
                if (!Commands.WAKE.equals(wake.getString("word"))) continue;
                StringBuilder tail = new StringBuilder();
                double end = wake.getDouble("end");
                int j = i + 1;
                for (; j < w.length(); j++) {
                    JSONObject x = w.getJSONObject(j);
                    String word = x.getString("word");
                    if (Commands.WAKE.equals(word) || "[unk]".equals(word) || x.getDouble("start") - end > 1.0) break;
                    tail.append(tail.length() > 0 ? " " : "").append(word);
                    end = x.getDouble("end");
                }
                String text = tail.toString();
                String action = Commands.action(text);
                if (text.isEmpty()) {
                    arm(secondPass(wake));
                } else if (action != null) {
                    if (armedUntil != 0) disarm(action);
                    if (action.startsWith("search:")) search(action.substring(7), end, w, j);
                    else act(text, action, "", false);
                } else {
                    log("unknown", text, ""); // heard «пульт …» but no command: shows what to add or fix
                }
                i = j - 1;
            }
        } catch (JSONException e) {
            Log.w(TAG, "bad result " + json);
        }
    }

    /**
     * What the grammar-less recognizer hears around a lone «пульт», for the log only.
     * Look-alikes land on a lone «пульт» («пункт», «пусть», «путь», «культ» from the TV or
     * the room), but the obvious filter — keep it only if this pass hears «пульт» too —
     * failed on real recordings: with music playing it threw away 9 of 10 genuine ones,
     * since one short word decodes poorly without a grammar. Collect, decide later.
     */
    private String secondPass(JSONObject wake) throws JSONException {
        short[] seg = segment((long) ((wake.getDouble("start") - 0.3) * RATE), (long) ((wake.getDouble("end") + 0.3) * RATE));
        free.reset();
        free.acceptWaveForm(seg, seg.length);
        String heard = text(free.getFinalResult());
        free.reset();
        return heard;
    }

    /** A lone «пульт»: wait a few seconds for the rest (the TV is left alone — false alarms cost nothing). */
    private void arm(String secondPass) {
        boolean dry = Store.dry(this);
        log(dry ? "dry-wake" : "wake", Commands.WAKE, "second pass: «" + secondPass + "»");
        clip("wake");
        if (dry) {
            overlay.show("(тихий режим) услышал «пульт»", 1500);
            return;
        }
        armedUntil = SystemClock.uptimeMillis() + 4000;
        armReset = true;
        overlay.show("🎙  Слушаю…", 4000);
    }

    private void disarm(String action) {
        armedUntil = 0;
        if ("mute".equals(action)) weMuted = false; // keep the silence the user asked for
        else unmuteAfterListening();
        if (action == null) overlay.hide();
    }

    private void muteToListen() {
        keys.execute(() -> {
            try {
                if (tv.connected && !tv.muted && !weMuted) {
                    tv.key(Commands.KEY_MUTE);
                    weMuted = true;
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void unmuteAfterListening() {
        keys.execute(() -> {
            try {
                if (weMuted) tv.key(Commands.KEY_MUTE); // we muted it, so the toggle brings sound back
            } catch (Exception ignored) {
            }
            weMuted = false;
        });
    }

    // ---- search: the query is free speech, so it goes through the grammar-less recognizer

    /** «пульт найди на ютубе …»: either the query is already in the ring buffer, or it's still to come. */
    private void search(String pkg, double cmdEnd, JSONArray w, int j) throws JSONException {
        double uttEnd = -1;
        for (int k = j; k < w.length(); k++) { // [unk] right after the command = the query, said in one breath
            JSONObject x = w.getJSONObject(k);
            if (!"[unk]".equals(x.getString("word")) || x.getDouble("start") - Math.max(cmdEnd, uttEnd) > 1.0) break;
            uttEnd = x.getDouble("end");
        }
        if (uttEnd < 0) {
            dictation(pkg);
            return;
        }
        short[] seg = segment((long) (cmdEnd * RATE), (long) ((uttEnd + 0.3) * RATE));
        free.reset();
        free.acceptWaveForm(seg, seg.length);
        String query = text(free.getFinalResult());
        free.reset();
        finishSearch(pkg, query);
    }

    /** Two-step search: mute the TV and take the next phrase as the query. */
    private void dictation(String pkg) {
        if (Store.dry(this)) {
            log("dry", "найди…", "search:" + pkg);
            overlay.show("(тихий режим) 🔎 Что найти?", 2000);
            return;
        }
        free.reset();
        dictatePkg = pkg;
        dictateUntil = SystemClock.uptimeMillis() + 8000;
        overlay.show("🔎  Что найти?", 8000);
        muteToListen();
    }

    private void dictate(short[] mono, int frames, long now) {
        boolean done = free.acceptWaveForm(mono, frames);
        if (!done && now < dictateUntil) return;
        String query = text(done ? free.getResult() : free.getFinalResult());
        free.reset();
        dictateUntil = 0;
        unmuteAfterListening();
        finishSearch(dictatePkg, query);
    }

    private void finishSearch(final String pkg, String said) {
        final String query = said.trim();
        boolean dry = Store.dry(this);
        log(dry ? "dry" : "cmd", "найди " + query, "search:" + pkg);
        clip("search");
        if (query.isEmpty()) {
            overlay.show("Не расслышал, что найти", 2000);
            return;
        }
        last = hhmm(System.currentTimeMillis()) + "  «найди " + query + "»" + (dry ? " (тихий режим)" : "");
        overlay.show((dry ? "(тихий режим) " : "") + "🔎  " + query, 3000);
        if (dry) return;
        keys.execute(() -> {
            try {
                if (helper.ok("/search", "pkg", pkg, "q", query)) return;
                if (!Commands.YOUTUBE.equals(pkg)) throw new IOException("такой поиск идёт через помощника на ТВ");
                if (!tv.ready(4000)) throw new IOException("ТВ не на связи");
                tv.appLink("https://www.youtube.com/results?search_query=" + Uri.encode(query));
            } catch (Exception e) {
                overlay.show("Не вышло: " + e.getMessage(), 2500);
            }
        });
    }

    private static String text(String json) {
        try {
            return new JSONObject(json).optString("text", "");
        } catch (JSONException e) {
            return "";
        }
    }

    // ---- doing ----------------------------------------------------------------

    private void act(String text, String said, String how, boolean fromBare) {
        final String action = Commands.inApp(tv.app, said); // «назад» in Apple Music = previous track
        long t = SystemClock.uptimeMillis();
        // both recognizers hear «пульт вниз» while the bare one listens: the same words, a moment apart
        if (action.equals(lastAction) && fromBare != lastFromBare && t - lastActionAt < 800) return;
        lastAction = action;
        lastFromBare = fromBare;
        lastActionAt = t;
        if (action.startsWith("nav:")) {
            if (armedUntil == 0 && navUntil <= t) armReset = true; // the bare recognizer wasn't running
            navUntil = t + 6000;
        }
        boolean dry = Store.dry(this);
        last = hhmm(System.currentTimeMillis()) + "  «пульт " + text + "»" + (dry ? " (тихий режим)" : "");
        log(dry ? "dry" : "cmd", text, how.isEmpty() ? action : action + " " + how);
        if (!"adb".equals(how)) clip(action);
        String label = Commands.label(action);
        if (dry) {
            overlay.show("(тихий режим) " + (label.isEmpty() ? "«" + text + "»" : label), 2000);
            return;
        }
        if (!label.isEmpty()) overlay.show(label, action.startsWith("nav:") ? 800 : 1500);
        keys.execute(() -> {
            try {
                boolean local = action.startsWith("info") || action.startsWith("sleep:"); // no key presses
                boolean waking = "tv:on".equals(action) || "music".equals(action); // the box may be asleep
                if (!local && !tv.ready(waking ? 8000 : 4000)) {
                    overlay.show("tv:on".equals(action) ? "Приставка спит без сети — включи её пультом" : "ТВ не на связи", 2500);
                    return;
                }
                execute(action);
            } catch (Exception e) {
                Log.w(TAG, "command " + action, e);
                overlay.show("Не вышло: " + e.getMessage(), 2500);
            }
        });
    }

    private void execute(String action) throws Exception {
        String[] p = action.split(":", 2);
        String arg = p.length > 1 ? p[1] : "";
        switch (p[0]) {
            case "pause": tv.key(Commands.KEY_PAUSE); break;
            case "nav": tv.key(Integer.parseInt(arg)); break;
            case "play": tv.key(Commands.KEY_PLAY); break;
            case "vol": {
                int n = Integer.parseInt(arg);
                Commands.press(tv, n > 0 ? Commands.KEY_VOLUME_UP : Commands.KEY_VOLUME_DOWN, Math.abs(n));
                break;
            }
            case "mute": if (!tv.muted) tv.key(Commands.KEY_MUTE); break;
            case "unmute": if (tv.muted) tv.key(Commands.KEY_MUTE); break;
            case "normal": {
                // the box ignores "set volume to N" from outside but obeys keys, so walk there
                int base = Store.base(this);
                if (tv.volume >= 0) {
                    Commands.press(tv, base > tv.volume ? Commands.KEY_VOLUME_UP : Commands.KEY_VOLUME_DOWN,
                            Math.abs(base - tv.volume));
                }
                break;
            }
            case "seek": seekBy(Long.parseLong(arg)); break;
            case "seekto": if (!helper.ok("/seek", "to", arg)) throw new IOException("тут так не умею"); break;
            case "intro": seekBy(85_000); break;
            case "next": if (!helper.ok("/next")) tv.key(Commands.KEY_NEXT); break;
            case "prev": if (!helper.ok("/prev")) tv.key(Commands.KEY_PREVIOUS); break;
            case "info": info(arg); break;
            case "sleep": sleep(arg); break;
            case "tv":
                // Google's remote service ignores the SLEEP/WAKEUP keys and POWER toggles, so look first
                if ("on".equals(arg)) {
                    if (!tv.on) tv.key(Commands.KEY_POWER);
                } else {
                    clearSleep();
                    if (tv.on) tv.key(Commands.KEY_POWER);
                }
                break;
            case "music": music(); break;
            case "amtv": {
                JSONObject r = helper.call("/amtv", "cmd", arg);
                if (r.has("error")) throw new IOException(r.optString("error"));
                break;
            }
            case "favorite": throw new IOException("«в избранное» работает в Apple Music");
            case "unfavorite": throw new IOException("«мне не нравится» работает в Apple Music");
            case "launch":
                if (helper.ok("/launch", "pkg", arg)) break;
                if (!Commands.YOUTUBE.equals(arg)) throw new IOException("не открыть " + Commands.app(arg));
                tv.appLink("https://www.youtube.com");
                break;
            default:
                throw new IllegalArgumentException(action);
        }
    }

    private static final java.util.Set<String> LAUNCHERS = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher"));

    /** «включи музыку»: wake the box (it switches the TV on over CEC), then Apple Music with the station. */
    private void music() throws Exception {
        if (!tv.on) {
            tv.key(Commands.KEY_POWER);
            long until = SystemClock.uptimeMillis() + 10_000;
            while (!tv.on && SystemClock.uptimeMillis() < until) SystemClock.sleep(200);
        }
        final String station = Store.music(this);
        launchMusic(station);
        new Thread(() -> watchMusic(station), "music-watch").start();
    }

    /**
     * After a longer sleep Google TV puts its home screen up a few seconds after waking —
     * on top of an app started meanwhile (2026-10-14: Apple Music got «play» and logged
     * «left the app» two seconds later; after a 7-second sleep it didn't happen). For 20 s,
     * when the home screen covers the music, or turns up while the music never showed,
     * start it again: by then the app is in memory and comes up at once.
     */
    private void watchMusic(String station) {
        long since = SystemClock.uptimeMillis();
        boolean seen = false;
        String last = "";
        try {
            for (int again = 0; again < 2 && SystemClock.uptimeMillis() - since < 20_000; ) {
                SystemClock.sleep(500);
                String app = tv.app;
                if (!app.equals(last)) log("music-watch", Commands.app(app), ""); // what the box shows, for tuning
                last = app;
                if (Commands.AMTV.equals(app)) {
                    seen = true;
                } else if (LAUNCHERS.contains(app) && (seen || SystemClock.uptimeMillis() - since > 4000)) {
                    again++;
                    log("music-again", "главный экран поверх музыки", "music");
                    launchMusic(station);
                    seen = false;
                    since = SystemClock.uptimeMillis();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "music watch", e);
        }
    }

    private void launchMusic(String station) throws Exception {
        for (int attempt = 1; ; attempt++) { // just woken, the box's Wi-Fi may take a moment
            try {
                JSONObject r = station.isEmpty() ? helper.call("/launch", "pkg", Commands.AMTV)
                        : helper.call("/amtv", "play", station);
                if (r.has("error")) throw new IllegalStateException(r.optString("error"));
                return;
            } catch (IOException e) {
                if (attempt == 6) throw e;
                SystemClock.sleep(2000);
            }
        }
    }

    /** Through the app's media session when the helper can, else the D-pad trick in 10-second steps. */
    private void seekBy(long ms) throws Exception {
        if (helper.ok("/seek", "by", String.valueOf(ms))) return;
        long tens = ms / 10_000;
        if (tens == 0 || Math.abs(tens) > 12) throw new IOException("тут точная перемотка не работает");
        Commands.dpadSeek(tv, (int) tens);
    }

    private void info(String what) throws Exception {
        BoxHelper.Now n = helper.now();
        if (!n.active()) {
            overlay.show("Сейчас ничего не играет", 2500);
            return;
        }
        String app = Commands.app(n.pkg);
        if ("left".equals(what) && n.duration > 0) {
            long left = Math.max(0, n.duration - n.position());
            overlay.show("⏳  До конца " + Commands.human(left) + ", закончится в "
                    + hhmm(System.currentTimeMillis() + left), 6000);
        } else {
            String where = n.duration > 0
                    ? Commands.human(n.position()) + " из " + Commands.human(n.duration)
                    : Commands.human(n.position());
            overlay.show("▶  " + (n.title.isEmpty() ? app : n.title) + "\n" + app + "  ·  " + where, 6000);
        }
    }

    private void sleep(String arg) throws Exception {
        SharedPreferences.Editor e = Store.get(this).edit();
        if ("cancel".equals(arg)) {
            clearSleep();
            overlay.show("⏻  Таймер отменён", 2000);
        } else if ("episode".equals(arg)) {
            BoxHelper.Now n = helper.now();
            if (!n.active()) throw new IOException("не вижу, что играет — скажи время");
            e.putString(Store.SLEEP_EPISODE, n.title.isEmpty() ? "?" : n.title).putLong(Store.SLEEP_AT, 0).apply();
            overlay.show("⏻  Выключу после этой серии", 2500);
        } else {
            long at = System.currentTimeMillis() + Long.parseLong(arg) * 60_000;
            e.putLong(Store.SLEEP_AT, at).putString(Store.SLEEP_EPISODE, "").apply();
            overlay.show("⏻  Выключу в " + hhmm(at), 2500);
        }
    }

    private void clearSleep() {
        Store.get(this).edit().putLong(Store.SLEEP_AT, 0).putString(Store.SLEEP_EPISODE, "").apply();
    }

    private String sleepNote() {
        SharedPreferences sp = Store.get(this);
        long at = sp.getLong(Store.SLEEP_AT, 0);
        if (at > 0) return "⏻ " + hhmm(at);
        return sp.getString(Store.SLEEP_EPISODE, "").isEmpty() ? "" : "⏻ после серии";
    }

    /** Every 2 s: what plays (for the card and timers) and whether a sleep timer is due. */
    private void tick() {
        try {
            BoxHelper.Now n = null;
            if (helper.configured() && tv.connected && tv.on) {
                try {
                    n = helper.now();
                    helperUp = true;
                } catch (IOException e) {
                    helperUp = false;
                }
            }
            BoxHelper.Now prev = now;
            now = n;
            card.update(n, sleepNote());
            checkSleep(prev, n);
        } catch (Throwable t) {
            Log.w(TAG, "tick", t);
        }
    }

    private void checkSleep(BoxHelper.Now prev, BoxHelper.Now n) {
        SharedPreferences sp = Store.get(this);
        long at = sp.getLong(Store.SLEEP_AT, 0);
        String episode = sp.getString(Store.SLEEP_EPISODE, "");
        boolean due = at > 0 && System.currentTimeMillis() >= at;
        if (!episode.isEmpty() && n != null) {
            boolean another = n.active() && !n.title.isEmpty() && !"?".equals(episode) && !n.title.equals(episode);
            boolean ended = n.duration > 0 && n.position() >= n.duration - 3000;
            boolean stopped = prev != null && prev.active() && !n.active();
            due |= another || ended || stopped;
        }
        if (!due) return;
        clearSleep();
        log("sleep", episode.isEmpty() ? "по таймеру" : "после серии", "tv:off");
        keys.execute(() -> {
            try {
                if (tv.ready(4000) && tv.on) tv.key(Commands.KEY_POWER);
            } catch (Exception e) {
                Log.w(TAG, "sleep", e);
            }
        });
    }

    /** adb testing: as if «пульт <phrase>» had just been heard. */
    void simulate(String phrase) {
        String action = Commands.action(phrase);
        if (action == null) throw new IllegalArgumentException("нет такой команды: " + phrase);
        lastAction = "";
        if (action.startsWith("search:")) dictation(action.substring(7));
        else act(phrase, action, "adb", false);
    }

    String status() {
        StringBuilder sb = new StringBuilder();
        sb.append("Микрофон: ").append(hearing).append('\n');
        if (!ModelDownload.status.isEmpty()) sb.append("Модель: ").append(ModelDownload.status).append('\n');
        if (!tv.paired()) {
            sb.append("ТВ: не сопряжён — нажми «Сопрячь с ТВ»\n");
        } else if (tv.connected) {
            sb.append("ТВ: на связи").append(tv.on ? "" : " (экран выключен)").append('\n');
        } else {
            sb.append("ТВ: нет связи").append(tv.error.isEmpty() ? "" : " — " + tv.error).append('\n');
        }
        sb.append("Помощник на ТВ: ").append(!helper.configured() ? "не настроен (make link)"
                : helperUp ? "на связи" : tv.connected && tv.on ? "не отвечает" : "—").append('\n');
        if (!tv.app.isEmpty()) sb.append("Открыто: ").append(Commands.app(tv.app)).append('\n');
        BoxHelper.Now n = now;
        if (n != null && n.active()) {
            sb.append("Играет: ").append(n.title.isEmpty() ? "—" : n.title)
                    .append(n.duration > 0 ? " (осталось " + Commands.human(n.duration - n.position()) + ")" : "").append('\n');
        }
        if (tv.volume >= 0) {
            sb.append("Громкость приставки: ").append(tv.volume).append(" из ").append(tv.volumeMax)
                    .append(tv.muted ? " (без звука)" : "").append('\n');
        }
        String timer = sleepNote();
        if (!timer.isEmpty()) sb.append("Таймер сна: ").append(timer.substring(2)).append('\n');
        sb.append("Режим: ").append(Store.dry(this) ? "тихий — слушаю и записываю, ничего не нажимаю" : "боевой").append('\n');
        if (!last.isEmpty()) sb.append("Последнее: ").append(last).append('\n');
        return sb.toString();
    }

    // ---- bookkeeping ------------------------------------------------------------

    private static String hhmm(long wallMs) {
        return new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(wallMs));
    }

    private void log(String kind, String text, String action) {
        String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date()) + "\t" + kind
                + "\t" + text + "\t" + action + "\t" + tv.app + "\t" + tv.volume + (tv.muted ? "m" : "") + "\n";
        Log.i(TAG, "EVENT " + line.trim());
        try (FileWriter f = new FileWriter(new File(getExternalFilesDir(null), "events.tsv"), true)) {
            f.write(line);
        } catch (IOException e) {
            Log.w(TAG, "events.tsv", e);
        }
    }

    private void remember(short[] s, int n) {
        synchronized (ring) {
            for (int i = 0; i < n; i++) ring[(int) ((ringAbs + i) % RING)] = s[i];
            ringAbs += n;
        }
    }

    /** Audio between two absolute frame numbers, as far as the ring still holds it. */
    private short[] segment(long from, long to) {
        synchronized (ring) {
            from = Math.max(from, Math.max(0, ringAbs - RING));
            to = Math.min(to, ringAbs);
            short[] out = new short[(int) Math.max(0, to - from)];
            for (int i = 0; i < out.length; i++) out[i] = ring[(int) ((from + i) % RING)];
            return out;
        }
    }

    /** With clips on: the last 4 s before each detection, so false alarms can be listened to later (300 at most). */
    private void clip(String what) {
        if (!Store.clips(this)) return;
        final short[] audio;
        synchronized (ring) {
            audio = segment(ringAbs - RATE * 4, ringAbs);
        }
        final String name = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + "-"
                + what.replace(':', '_').replace('.', '_') + ".wav";
        keys.execute(() -> {
            File dir = new File(getExternalFilesDir(null), "clips");
            String[] have = dir.list();
            if ((!dir.isDirectory() && !dir.mkdirs()) || (have != null && have.length >= 300)) return;
            ByteBuffer b = ByteBuffer.allocate(44 + audio.length * 2).order(ByteOrder.LITTLE_ENDIAN);
            b.put("RIFF".getBytes()).putInt(36 + audio.length * 2).put("WAVEfmt ".getBytes()).putInt(16)
                    .putShort((short) 1).putShort((short) 1).putInt(RATE).putInt(RATE * 2)
                    .putShort((short) 2).putShort((short) 16).put("data".getBytes()).putInt(audio.length * 2);
            for (short v : audio) b.putShort(v);
            try (RandomAccessFile f = new RandomAccessFile(new File(dir, name), "rw")) {
                f.write(b.array());
            } catch (IOException e) {
                Log.w(TAG, "clip", e);
            }
        });
    }
}
