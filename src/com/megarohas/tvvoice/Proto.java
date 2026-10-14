package com.megarohas.tvvoice;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough protobuf for the Android TV Remote protocol: varint and
 * length-delimited fields, nested messages, and the varint length prefix every
 * message carries on the wire. Field numbers live next to their use in TvRemote.
 */
final class Proto {

    private Proto() {
    }

    /** Message builder: new W().varint(1, 2).msg(10, new W().str(1, "x")).bytes() */
    static final class W {
        private final ByteArrayOutputStream b = new ByteArrayOutputStream();

        W varint(int field, long v) {
            raw((long) field << 3);
            raw(v);
            return this;
        }

        W bytes(int field, byte[] v) {
            raw(((long) field << 3) | 2);
            raw(v.length);
            b.write(v, 0, v.length);
            return this;
        }

        W str(int field, String s) {
            return bytes(field, s.getBytes(StandardCharsets.UTF_8));
        }

        W msg(int field, W m) {
            return bytes(field, m.bytes());
        }

        byte[] bytes() {
            return b.toByteArray();
        }

        private void raw(long v) {
            while ((v & ~0x7FL) != 0) {
                b.write((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
            }
            b.write((int) v);
        }
    }

    /** Parsed message: field number → values (Long for varints, byte[] for the rest). */
    static final class Msg {
        private final Map<Integer, List<Object>> f = new HashMap<>();

        static Msg parse(byte[] d) throws IOException {
            Msg m = new Msg();
            int[] pos = {0};
            while (pos[0] < d.length) {
                long tag = varint(d, pos);
                int field = (int) (tag >>> 3);
                Object v;
                switch ((int) (tag & 7)) {
                    case 0:
                        v = varint(d, pos);
                        break;
                    case 1:
                        v = slice(d, pos, 8);
                        break;
                    case 2:
                        v = slice(d, pos, (int) varint(d, pos));
                        break;
                    case 5:
                        v = slice(d, pos, 4);
                        break;
                    default:
                        throw new IOException("bad wire type in tag " + tag);
                }
                List<Object> l = m.f.get(field);
                if (l == null) m.f.put(field, l = new ArrayList<>());
                l.add(v);
            }
            return m;
        }

        boolean has(int field) {
            return f.containsKey(field);
        }

        long num(int field, long def) {
            List<Object> l = f.get(field);
            return l != null && l.get(0) instanceof Long ? (Long) l.get(0) : def;
        }

        byte[] raw(int field) {
            List<Object> l = f.get(field);
            return l != null && l.get(0) instanceof byte[] ? (byte[]) l.get(0) : null;
        }

        String str(int field) {
            byte[] r = raw(field);
            return r == null ? "" : new String(r, StandardCharsets.UTF_8);
        }

        /** A nested message, or an empty one when the field is absent. */
        Msg msg(int field) throws IOException {
            byte[] r = raw(field);
            return r == null ? new Msg() : parse(r);
        }

        @Override
        public String toString() {
            return "fields " + f.keySet();
        }
    }

    static void write(OutputStream out, byte[] m) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(m.length + 5);
        long v = m.length;
        while ((v & ~0x7FL) != 0) {
            b.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        b.write((int) v);
        b.write(m, 0, m.length);
        out.write(b.toByteArray()); // one write per message: the socket may be shared
        out.flush();
    }

    static Msg read(InputStream in) throws IOException {
        long len = 0;
        for (int shift = 0; ; shift += 7) {
            int c = in.read();
            if (c < 0) throw new EOFException("connection closed");
            len |= (long) (c & 0x7F) << shift;
            if ((c & 0x80) == 0) break;
            if (shift > 28) throw new IOException("bad length prefix");
        }
        if (len > 1 << 20) throw new IOException("message too large: " + len);
        byte[] d = new byte[(int) len];
        new DataInputStream(in).readFully(d);
        return Msg.parse(d);
    }

    private static long varint(byte[] d, int[] pos) throws IOException {
        long v = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (pos[0] >= d.length) throw new IOException("truncated varint");
            int c = d[pos[0]++] & 0xFF;
            v |= (long) (c & 0x7F) << shift;
            if ((c & 0x80) == 0) return v;
        }
        throw new IOException("varint too long");
    }

    private static byte[] slice(byte[] d, int[] pos, int n) throws IOException {
        if (n < 0 || pos[0] + n > d.length) throw new IOException("truncated field");
        byte[] r = new byte[n];
        System.arraycopy(d, pos[0], r, 0, n);
        pos[0] += n;
        return r;
    }
}
