package com.megarohas.tvvoice;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Fetches the small Russian Vosk model (a 45 MB zip) straight into internal
 * storage, unpacking on the fly; ListenService picks it up as soon as it's there.
 */
final class ModelDownload {

    static final String URL = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip";

    static volatile String status = ""; // progress or the last error, for the screen and the service
    private static boolean running;

    private ModelDownload() {
    }

    static boolean installed(Context c) {
        return new File(c.getFilesDir(), "model/am/final.mdl").exists();
    }

    static synchronized void start(Context ctx) {
        if (running) return;
        running = true;
        final Context app = ctx.getApplicationContext();
        status = "скачиваю модель…";
        new Thread(() -> {
            File tmp = new File(app.getFilesDir(), "model.download");
            try {
                delete(tmp);
                HttpURLConnection c = (HttpURLConnection) new URL(URL).openConnection();
                c.setConnectTimeout(15_000);
                c.setReadTimeout(30_000);
                final long total = c.getContentLengthLong();
                final long[] got = {0};
                InputStream counting = new FilterInputStream(c.getInputStream()) {
                    @Override
                    public int read(byte[] b, int off, int len) throws IOException {
                        int n = super.read(b, off, len);
                        if (n > 0) {
                            got[0] += n;
                            status = "скачиваю модель: " + got[0] / 1_000_000 + (total > 0 ? " из " + total / 1_000_000 : "") + " МБ";
                        }
                        return n;
                    }
                };
                try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(counting, 1 << 16))) {
                    byte[] buf = new byte[1 << 16];
                    String root = tmp.getCanonicalPath() + File.separator;
                    for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                        String name = e.getName();
                        String rel = name.substring(name.indexOf('/') + 1); // drop "vosk-model-small-ru-0.22/"
                        if (rel.isEmpty()) continue;
                        File out = new File(tmp, rel);
                        if (!out.getCanonicalPath().startsWith(root)) throw new IOException("подозрительный архив");
                        if (e.isDirectory()) {
                            out.mkdirs();
                            continue;
                        }
                        out.getParentFile().mkdirs();
                        try (OutputStream o = new FileOutputStream(out)) {
                            for (int n; (n = zip.read(buf)) > 0; ) o.write(buf, 0, n);
                        }
                    }
                } finally {
                    c.disconnect();
                }
                if (!new File(tmp, "am/final.mdl").exists()) throw new IOException("в архиве нет модели");
                File dst = new File(app.getFilesDir(), "model");
                delete(dst);
                if (!tmp.renameTo(dst)) throw new IOException("не удалось сохранить");
                status = "";
            } catch (Exception e) {
                status = "модель не скачалась: " + e.getMessage();
                delete(tmp);
            } finally {
                synchronized (ModelDownload.class) {
                    running = false;
                }
            }
        }, "model-download").start();
    }

    private static void delete(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) delete(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
