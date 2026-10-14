package com.megarohas.tvvoice;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocket;

/**
 * Android TV Remote protocol v2, the one the Google TV phone app speaks: pairing
 * once on :6467 (the TV shows a 6-character code), then a long-lived session on
 * :6466 that carries key presses and reports the box's volume and foreground app.
 * Field numbers follow polo.proto and remotemessage.proto from
 * github.com/tronikos/androidtvremote2.
 */
final class TvRemote {

    interface Listener {
        void changed();
    }

    private static final String TAG = "tvvoice";
    private static final int PAIR_PORT = 6467, REMOTE_PORT = 6466;
    // PING | KEY | IME | POWER | VOLUME | APP_LINK; IME is what makes the box report its foreground app
    private static final int FEATURES = 1 | 2 | 4 | 32 | 64 | 512;
    private static final int SHORT_PRESS = 3;

    private final Context ctx;
    private final Listener listener;
    private final Object writeLock = new Object();
    private volatile OutputStream out;
    private volatile Socket sock;
    private volatile Thread loop;
    private volatile boolean running;
    private SSLSocket pairing;
    private int features = FEATURES;

    volatile boolean connected, on;
    volatile int volume = -1, volumeMax = -1;
    volatile boolean muted;
    volatile String app = "";
    volatile String error = "";

    TvRemote(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    /** The remembered address, or the first Android TV found on the network. */
    String host() throws IOException {
        if (Store.host(ctx).isEmpty()) rediscover();
        String h = Store.host(ctx);
        if (h.isEmpty()) throw new IOException("не нашёл ТВ в сети — он включён?");
        return h;
    }

    boolean paired() {
        return !Store.get(ctx).getString(Store.TV_PIN, "").isEmpty();
    }

    // ---- pairing ------------------------------------------------------------

    /** Step 1: after this the TV shows a code and waits for {@link #finishPairing}. */
    synchronized void startPairing() throws Exception {
        cancelPairing();
        SSLSocket s = Identity.connect(host(), PAIR_PORT, 10_000);
        pairing = s;
        Proto.W hex6 = new Proto.W().varint(1, 3).varint(2, 6); // encoding HEXADECIMAL, 6 symbols
        polo(s, 10, new Proto.W().str(1, "atvremote").str(2, "Пульт (" + Build.MODEL + ")"), 11);
        polo(s, 20, new Proto.W().msg(1, hex6).varint(3, 1), 20); // options: input encodings, role INPUT
        polo(s, 30, new Proto.W().msg(1, hex6).varint(2, 1), 31); // configuration → ack, code goes on screen
        s.setSoTimeout(15_000);
    }

    /** Step 2: the code proves both sides hold the keys they showed each other. */
    synchronized void finishPairing(String code) throws Exception {
        SSLSocket s = pairing;
        if (s == null) throw new IOException("сначала начни сопряжение");
        code = code.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        if (!code.matches("[0-9A-F]{6}")) throw new IOException("код — 6 символов 0–9 и A–F с экрана ТВ");
        Certificate tv = Identity.peer(s);
        try {
            polo(s, 40, new Proto.W().bytes(1, secret(Identity.publicKey(), (RSAPublicKey) tv.getPublicKey(), code)), 41);
        } finally {
            cancelPairing();
        }
        Store.get(ctx).edit().putString(Store.TV_PIN, Identity.fingerprint(tv)).apply();
        Log.i(TAG, "paired with " + host());
        kick();
    }

    synchronized void cancelPairing() {
        close(pairing);
        pairing = null;
    }

    /** One pairing round trip: OuterMessage{protocol_version 2, status OK, payload} and the expected reply. */
    private static void polo(SSLSocket s, int field, Proto.W body, int expect) throws Exception {
        Proto.write(s.getOutputStream(), new Proto.W().varint(1, 2).varint(2, 200).msg(field, body).bytes());
        Proto.Msg r = Proto.read(s.getInputStream());
        long status = r.num(2, 0);
        if (status != 200) throw new IOException(status == 402 ? "ТВ не принял код" : "ТВ ответил ошибкой " + status);
        if (!r.has(expect)) throw new IOException("ТВ ответил не тем: " + r);
    }

    /** SHA-256 over both RSA keys and the code's last 4 symbols; its first byte must equal the first 2. */
    static byte[] secret(RSAPublicKey client, RSAPublicKey tv, String code) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        d.update(magnitude(client.getModulus()));
        d.update(magnitude(client.getPublicExponent()));
        d.update(magnitude(tv.getModulus()));
        d.update(magnitude(tv.getPublicExponent()));
        d.update(hex(code.substring(2)));
        byte[] h = d.digest();
        if ((h[0] & 0xFF) != Integer.parseInt(code.substring(0, 2), 16)) {
            throw new IOException("код не сходится — перепроверь символы на экране ТВ");
        }
        return h;
    }

    private static byte[] magnitude(BigInteger v) {
        byte[] b = v.toByteArray(); // big-endian two's complement: drop the sign byte
        return b.length > 1 && b[0] == 0 ? Arrays.copyOfRange(b, 1, b.length) : b;
    }

    private static byte[] hex(String s) {
        byte[] r = new byte[s.length() / 2];
        for (int i = 0; i < r.length; i++) r[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return r;
    }

    // ---- session ------------------------------------------------------------

    void start() {
        running = true;
        loop = new Thread(this::run, "tvremote");
        loop.start();
    }

    void stop() {
        running = false;
        close(sock);
        Thread t = loop;
        if (t != null) t.interrupt();
        cancelPairing();
    }

    /** Reconnect now rather than after the backoff. */
    void kick() {
        close(sock);
        Thread t = loop;
        if (t != null) t.interrupt();
    }

    /** True once a session is up, waiting a little for one (e.g. right after the box woke). */
    boolean ready(long ms) {
        if (connected) return true;
        kick();
        long until = SystemClock.uptimeMillis() + ms;
        while (!connected && SystemClock.uptimeMillis() < until) pause(100);
        return connected;
    }

    void key(int code) throws IOException {
        send(new Proto.W().msg(10, new Proto.W().varint(1, code).varint(2, SHORT_PRESS)));
    }

    void appLink(String link) throws IOException {
        send(new Proto.W().msg(90, new Proto.W().str(1, link)));
    }

    private void run() {
        long backoff = 1000;
        int failures = 0;
        while (running) {
            boolean was = false;
            if (!paired()) {
                error = "ТВ не сопряжён";
            } else {
                try {
                    session();
                } catch (Exception e) {
                    if (running) error = e.getMessage() != null ? e.getMessage() : e.toString();
                }
                was = connected;
                if (was) Log.i(TAG, "tv session ended: " + error);
                connected = false;
                listener.changed();
                failures = was ? 0 : failures + 1;
                if (failures == 3) rediscover(); // the box may have a new DHCP address
            }
            if (!running) break;
            pause(backoff);
            backoff = was ? 1000 : Math.min(backoff * 2, 30_000);
        }
    }

    private void session() throws Exception {
        SSLSocket s = Identity.connect(host(), REMOTE_PORT, 5000);
        sock = s;
        try {
            if (!Identity.fingerprint(Identity.peer(s)).equals(Store.get(ctx).getString(Store.TV_PIN, ""))) {
                throw new IOException("по этому адресу отвечает не наш ТВ");
            }
            s.setSoTimeout(20_000); // the box pings every few seconds; silence means a dead link
            out = s.getOutputStream();
            InputStream in = s.getInputStream();
            while (running) {
                Proto.Msg m = Proto.read(in);
                if (m.has(1)) { // remote_configure: agree on features, introduce ourselves
                    features = (int) (FEATURES & m.msg(1).num(1, FEATURES));
                    send(new Proto.W().msg(1, new Proto.W().varint(1, features).msg(2, new Proto.W()
                            .str(1, Build.MODEL).str(2, Build.MANUFACTURER).varint(3, 1).str(4, "1")
                            .str(5, "atvremote").str(6, "1.0.0"))));
                } else if (m.has(2)) { // remote_set_active
                    send(new Proto.W().msg(2, new Proto.W().varint(1, features)));
                    connected = true;
                    error = "";
                    Log.i(TAG, "tv session up");
                    listener.changed();
                } else if (m.has(8)) { // remote_ping_request: echo val1
                    send(new Proto.W().msg(9, new Proto.W().varint(1, m.msg(8).num(1, 0))));
                } else if (m.has(20)) { // remote_ime_key_inject carries the foreground app
                    String pkg = m.msg(20).msg(1).str(12);
                    if (!pkg.isEmpty()) {
                        app = pkg;
                        listener.changed();
                    }
                } else if (m.has(40)) { // remote_start: screen on/off
                    on = m.msg(40).num(1, 0) != 0;
                    listener.changed();
                } else if (m.has(50)) { // remote_set_volume_level
                    Proto.Msg v = m.msg(50);
                    volumeMax = (int) v.num(6, -1);
                    volume = (int) v.num(7, -1);
                    muted = v.num(8, 0) != 0;
                    listener.changed();
                } else if (m.has(3)) {
                    Log.w(TAG, "tv reported an error: " + m.msg(3));
                }
            }
        } finally {
            out = null;
            close(s);
        }
    }

    private void send(Proto.W m) throws IOException {
        OutputStream o = out;
        if (o == null) throw new IOException("нет связи с ТВ");
        synchronized (writeLock) {
            Proto.write(o, m.bytes());
        }
    }

    /** Finds the box by its mDNS service when the remembered address stops answering. */
    private void rediscover() {
        final NsdManager nsd = ctx.getSystemService(NsdManager.class);
        final CountDownLatch found = new CountDownLatch(1);
        final String[] host = {null};
        NsdManager.DiscoveryListener dl = new NsdManager.DiscoveryListener() {
            @Override
            public void onServiceFound(NsdServiceInfo si) {
                nsd.resolveService(si, new NsdManager.ResolveListener() {
                    @Override
                    public void onServiceResolved(NsdServiceInfo r) {
                        host[0] = r.getHost().getHostAddress();
                        found.countDown();
                    }

                    @Override
                    public void onResolveFailed(NsdServiceInfo r, int code) {
                    }
                });
            }

            @Override
            public void onStartDiscoveryFailed(String type, int code) {
                found.countDown();
            }

            @Override
            public void onStopDiscoveryFailed(String type, int code) {
            }

            @Override
            public void onDiscoveryStarted(String type) {
            }

            @Override
            public void onDiscoveryStopped(String type) {
            }

            @Override
            public void onServiceLost(NsdServiceInfo si) {
            }
        };
        try {
            nsd.discoverServices("_androidtvremote2._tcp", NsdManager.PROTOCOL_DNS_SD, dl);
            found.await(8, TimeUnit.SECONDS);
            nsd.stopServiceDiscovery(dl);
        } catch (Exception e) {
            Log.w(TAG, "mdns", e);
        }
        if (host[0] != null && !host[0].equals(Store.host(ctx))) {
            Log.i(TAG, "tv at " + host[0]); // the certificate pin still guards against a stranger
            Store.get(ctx).edit().putString(Store.TV_HOST, host[0]).apply();
        }
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            // kick(): go again right away
        }
    }

    private static void close(Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (Exception ignored) {
        }
    }
}
