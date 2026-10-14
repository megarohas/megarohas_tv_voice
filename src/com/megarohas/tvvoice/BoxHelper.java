package com.megarohas.tvvoice;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Client of the helper app on the TV box (box/): media sessions, launching apps, search. */
final class BoxHelper {

    private static final int PORT = 8765;

    /** What plays on the TV, with the position extrapolated from when it was fetched. */
    static final class Now {
        String pkg = "", state = "none", title = "", subtitle = "";
        long position, duration = -1, at;

        boolean active() {
            return "playing".equals(state) || "paused".equals(state) || "buffering".equals(state);
        }

        long position() {
            return "playing".equals(state) ? position + SystemClock.uptimeMillis() - at : position;
        }
    }

    private final Context ctx;

    BoxHelper(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    boolean configured() {
        return !Store.get(ctx).getString(Store.HELPER_TOKEN, "").isEmpty();
    }

    /** GET /<path>?token=…&k=v…; an {"error": …} answer comes back as the JSON, not an exception. */
    JSONObject call(String path, String... kv) throws IOException {
        if (!configured()) throw new IOException("помощник на приставке не настроен");
        String host = Store.host(ctx);
        if (host.isEmpty()) throw new IOException("адрес ТВ ещё не найден");
        StringBuilder url = new StringBuilder("http://").append(host).append(':').append(PORT).append(path)
                .append("?token=").append(Uri.encode(Store.get(ctx).getString(Store.HELPER_TOKEN, "")));
        for (int i = 0; i + 1 < kv.length; i += 2) url.append('&').append(kv[i]).append('=').append(Uri.encode(kv[i + 1]));
        HttpURLConnection c = (HttpURLConnection) new URL(url.toString()).openConnection();
        c.setConnectTimeout(1500);
        c.setReadTimeout(3000);
        try {
            int code = c.getResponseCode();
            try (InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                for (int n; in != null && (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
                return new JSONObject(new String(b.toByteArray(), StandardCharsets.UTF_8));
            }
        } catch (org.json.JSONException e) {
            throw new IOException("помощник ответил непонятно");
        } finally {
            c.disconnect();
        }
    }

    /** True if the helper did it; false if it couldn't (nothing playing, app can't seek…). */
    boolean ok(String path, String... kv) {
        try {
            return !call(path, kv).has("error");
        } catch (IOException e) {
            return false;
        }
    }

    Now now() throws IOException {
        JSONObject o = call("/now");
        Now n = new Now();
        n.at = SystemClock.uptimeMillis();
        n.pkg = o.optString("pkg");
        n.state = o.optString("state", "none");
        n.title = o.optString("title");
        n.subtitle = o.optString("subtitle");
        n.position = o.optLong("position");
        n.duration = o.optLong("duration", -1);
        return n;
    }
}
