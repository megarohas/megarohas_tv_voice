package com.megarohas.tvhelper;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * A tiny HTTP server for the tablet: GET /<command>?token=…&…, JSON back.
 *   /now                        what plays: app, title, state, position, duration
 *   /seek?by=ms | /seek?to=ms   precise seeking through the app's media session
 *   /next /prev /play /pause
 *   /launch?pkg=…               open an app
 *   /search?pkg=…&q=…           search inside YouTube or VK Видео
 *   /amtv?play=…|cmd=…          the Apple Music app (megarohas_amtv): a station / next, prev, pause…
 * Every request needs the token the Mac gave both sides (see Setup).
 */
final class Server extends Thread {

    static final int PORT = 8765;

    private final MediaWatcher watcher;
    private volatile ServerSocket socket;

    Server(MediaWatcher watcher) {
        super("tvhelper-http");
        this.watcher = watcher;
    }

    void shutdown() {
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void run() {
        try (ServerSocket ss = new ServerSocket(PORT)) {
            socket = ss;
            while (!ss.isClosed()) {
                try (Socket s = ss.accept()) {
                    s.setSoTimeout(3000);
                    serve(s);
                } catch (Exception e) {
                    if (!ss.isClosed()) Log.w(MediaWatcher.TAG, "request", e);
                }
            }
        } catch (Exception e) {
            Log.e(MediaWatcher.TAG, "server", e);
        }
    }

    private void serve(Socket s) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        String line = in.readLine();
        if (line == null) return;
        while (true) { // headers are of no interest
            String h = in.readLine();
            if (h == null || h.isEmpty()) break;
        }
        String[] parts = line.split(" ");
        String target = parts.length > 1 ? parts[1] : "/";
        int qm = target.indexOf('?');
        String path = qm < 0 ? target : target.substring(0, qm);
        Map<String, String> q = query(qm < 0 ? "" : target.substring(qm + 1));
        int code = 200;
        JSONObject body;
        String token = Setup.token(watcher);
        if (token.isEmpty() || !same(token, q.get("token"))) {
            code = 403;
            body = new JSONObject().put("error", "bad token");
        } else {
            try {
                body = handle(path, q);
            } catch (Exception e) {
                code = 500;
                body = new JSONObject().put("error", String.valueOf(e.getMessage()));
            }
        }
        byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
        OutputStream out = s.getOutputStream();
        out.write(("HTTP/1.1 " + code + (code == 200 ? " OK" : " Error") + "\r\nContent-Type: application/json; charset=utf-8"
                + "\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }

    private JSONObject handle(String path, Map<String, String> q) throws Exception {
        MediaController c = watcher.current();
        switch (path) {
            case "/now":
                return now(c, -1);
            case "/seek": {
                if (c == null) return new JSONObject().put("error", "ничего не играет");
                PlaybackState st = c.getPlaybackState();
                if (st == null || (st.getActions() & PlaybackState.ACTION_SEEK_TO) == 0) {
                    return new JSONObject().put("error", "no-seek"); // the tablet falls back to the D-pad
                }
                long dur = duration(c);
                long to = q.containsKey("to") ? Long.parseLong(q.get("to")) : position(st) + Long.parseLong(q.get("by"));
                if (dur > 0) to = Math.min(to, dur - 1000);
                to = Math.max(0, to);
                c.getTransportControls().seekTo(to);
                return now(c, to);
            }
            case "/next":
                if (c == null) return new JSONObject().put("error", "ничего не играет");
                c.getTransportControls().skipToNext();
                return new JSONObject().put("ok", true);
            case "/prev":
                if (c == null) return new JSONObject().put("error", "ничего не играет");
                c.getTransportControls().skipToPrevious();
                return new JSONObject().put("ok", true);
            case "/play":
                if (c != null) c.getTransportControls().play();
                return new JSONObject().put("ok", c != null);
            case "/pause":
                if (c != null) c.getTransportControls().pause();
                return new JSONObject().put("ok", c != null);
            case "/launch": {
                String pkg = q.get("pkg");
                Intent i = watcher.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
                if (i == null) i = watcher.getPackageManager().getLaunchIntentForPackage(pkg);
                if (i == null) return new JSONObject().put("error", "нет приложения " + pkg);
                start(watcher, i);
                return new JSONObject().put("ok", true);
            }
            case "/search": {
                String pkg = q.get("pkg"), text = q.get("q");
                Intent i;
                if ("com.google.android.youtube.tv".equals(pkg)) {
                    i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query="
                            + Uri.encode(text))).setPackage(pkg);
                } else {
                    i = new Intent(Intent.ACTION_SEARCH).setPackage(pkg).putExtra(SearchManager.QUERY, text);
                }
                start(watcher, i);
                return new JSONObject().put("ok", true);
            }
            case "/amtv": {
                // singleTask and WebView-based (no media session): it takes its orders as extras, in onNewIntent
                Intent i = new Intent().setClassName("com.megarohas.amtv", "com.megarohas.amtv.MainActivity");
                if (q.containsKey("play")) i.putExtra("play", q.get("play"));
                if (q.containsKey("cmd")) i.putExtra("cmd", q.get("cmd"));
                start(watcher, i);
                return new JSONObject().put("ok", true);
            }
            default:
                return new JSONObject().put("error", "unknown " + path);
        }
    }

    /** position: -1 = as reported, otherwise the place we just asked to seek to. */
    private static JSONObject now(MediaController c, long position) throws Exception {
        JSONObject o = new JSONObject();
        if (c == null) return o.put("state", "none");
        PlaybackState st = c.getPlaybackState();
        MediaMetadata md = c.getMetadata();
        o.put("pkg", c.getPackageName());
        o.put("state", st == null ? "none" : name(st.getState()));
        o.put("position", position >= 0 ? position : st == null ? 0 : position(st));
        o.put("duration", duration(c));
        o.put("actions", st == null ? 0 : st.getActions());
        if (md != null) {
            String title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
            if (title == null) title = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            String sub = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (sub == null) sub = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            o.put("title", title == null ? "" : title);
            o.put("subtitle", sub == null ? "" : sub);
        }
        return o;
    }

    private static long position(PlaybackState st) {
        long p = st.getPosition();
        if (st.getState() == PlaybackState.STATE_PLAYING && st.getLastPositionUpdateTime() > 0) {
            p += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
        }
        return Math.max(0, p);
    }

    private static long duration(MediaController c) {
        MediaMetadata md = c.getMetadata();
        return md == null ? -1 : md.getLong(MediaMetadata.METADATA_KEY_DURATION);
    }

    private static String name(int state) {
        switch (state) {
            case PlaybackState.STATE_PLAYING: return "playing";
            case PlaybackState.STATE_PAUSED: return "paused";
            case PlaybackState.STATE_BUFFERING: return "buffering";
            case PlaybackState.STATE_STOPPED: return "stopped";
            default: return "other";
        }
    }

    private static void start(Context ctx, Intent i) {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }

    private static Map<String, String> query(String s) throws Exception {
        Map<String, String> m = new HashMap<>();
        for (String kv : s.split("&")) {
            if (kv.isEmpty()) continue;
            int eq = kv.indexOf('=');
            m.put(URLDecoder.decode(eq < 0 ? kv : kv.substring(0, eq), "UTF-8"),
                    eq < 0 ? "" : URLDecoder.decode(kv.substring(eq + 1), "UTF-8"));
        }
        return m;
    }

    private static boolean same(String a, String b) {
        return b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
