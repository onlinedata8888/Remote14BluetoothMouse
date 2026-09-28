
package com.example.tvremote;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import javax.net.ssl.*;

final class CastClient {
    interface Listener {
        void onStatus(String state, String title);
        void onError(String message);
        void onMediaStatus(String playerState, long positionMs, long durationMs);
    }

    private static final String TAG = "TvRemoteCast";
    private static final String NS_CONN = "urn:x-cast:com.google.cast.tp.connection";
    private static final String NS_RECEIVER = "urn:x-cast:com.google.cast.receiver";
    private static final String NS_MEDIA = "urn:x-cast:com.google.cast.media";
    private static final String SOURCE = "sender-0";
    private static final String RECEIVER = "receiver-0";
    private static final String APP_ID = "CC1AD845"; // Default Media Receiver

    private final String host;
    private final Listener listener;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private volatile boolean closed;
    private SSLSocket socket;
    private OutputStream out;
    private Thread reader;
    private int requestId = 1;
    private String transportId = RECEIVER;
    private String sessionId = "";
    private int mediaSessionId = 0;
    private ScheduledExecutorService heartbeat;
    private String pendingUrl, pendingMime, pendingTitle;
    private boolean pendingPhoto;

    CastClient(String host, Listener listener) {
        this.host = host;
        this.listener = listener;
    }

    void start() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    SSLContext ctx = SSLContext.getInstance("TLS");
                    ctx.init(null, new TrustManager[]{new X509TrustManager() {
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                        public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                        public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
                    }}, new java.security.SecureRandom());
                    Socket raw = new Socket();
                    raw.connect(new InetSocketAddress(host, 8009), 5000);
                    socket = (SSLSocket) ctx.getSocketFactory().createSocket(raw, host, 8009, true);
                    socket.setSoTimeout(120000);
                    socket.startHandshake();
                    out = socket.getOutputStream();
                    reader = new Thread(new Runnable() {
                        public void run() { readLoop(); }
                    }, "cast-reader");
                    reader.setDaemon(true);
                    reader.start();
                    heartbeat = Executors.newSingleThreadScheduledExecutor();
                    heartbeat.scheduleAtFixedRate(new Runnable() {
                        public void run() {
                            try {
                                send("urn:x-cast:com.google.cast.tp.heartbeat", RECEIVER,
                                        new JSONObject().put("type","PING"));
                            } catch(Exception ignored) {}
                        }
                    }, 5, 5, TimeUnit.SECONDS);

                    send(NS_CONN, RECEIVER, new JSONObject()
                            .put("type", "CONNECT").put("origin", new JSONObject()));
                    send(NS_RECEIVER, RECEIVER, new JSONObject().put("type", "GET_STATUS").put("requestId", nextId()));
                    listener.onStatus("connecting", "");
                } catch (Exception e) {
                    fail("Cast connection failed: " + e.getMessage());
                }
            }
        }, "cast-connect").start();
    }

    private synchronized int nextId() { return requestId++; }

    private void readLoop() {
        try {
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            while (!closed) {
                int len = in.readInt();
                if (len <= 0 || len > 2_000_000) throw new IOException("Bad Cast packet length");
                byte[] b = new byte[len];
                in.readFully(b);
                handleMessage(b);
            }
        } catch (Exception e) {
            if (!closed) fail("Cast connection closed");
        }
    }

    private void handleMessage(byte[] packet) {
        try {
            int p = 0;
            // protobuf CastMessage: field 1 version, 2 source, 3 destination,
            // 4 namespace, 5 payload type, 6 payload_utf8.
            String ns = null, payload = null;
            while (p < packet.length) {
                long tag = readVarint(packet, p); p = varintEnd(packet, p);
                int field = (int)(tag >>> 3);
                int wire = (int)(tag & 7);
                if (wire == 2) {
                    long n = readVarint(packet, p); p = varintEnd(packet, p);
                    int end = p + (int)n;
                    if (end > packet.length) return;
                    String v = new String(packet, p, (int)n, StandardCharsets.UTF_8);
                    if (field == 4) ns = v;
                    else if (field == 6) payload = v;
                    p = end;
                } else if (wire == 0) {
                    p = varintEnd(packet, p);
                } else {
                    return;
                }
            }
            if (ns == null || payload == null) return;
            JSONObject o = new JSONObject(payload);
            String type = o.optString("type", "");
            if ("urn:x-cast:com.google.cast.tp.heartbeat".equals(ns) && "PING".equals(type)) {
                send(ns, "sender-0", new JSONObject().put("type","PONG"));
                return;
            } else if ("urn:x-cast:com.google.cast.tp.heartbeat".equals(ns)) {
                return;
            }
            if (NS_RECEIVER.equals(ns) && "RECEIVER_STATUS".equals(type)) {
                JSONObject status = o.optJSONObject("status");
                if (status != null) {
                    JSONArray apps = status.optJSONArray("applications");
                    if (apps != null && apps.length() > 0) {
                        JSONObject app = apps.optJSONObject(0);
                        if (app != null && APP_ID.equals(app.optString("appId"))) {
                            sessionId = app.optString("sessionId", "");
                            transportId = app.optString("transportId", RECEIVER);
                            listener.onStatus("connected", app.optString("displayName", "Cast"));
                            send(NS_CONN, transportId, new JSONObject()
                                    .put("type", "CONNECT").put("origin", new JSONObject()));
                            if (pendingUrl != null) {
                                final String u = pendingUrl, m = pendingMime, t = pendingTitle;
                                final boolean ph = pendingPhoto;
                                pendingUrl = pendingMime = pendingTitle = null;
                                new Thread(new Runnable() {
                                    public void run() {
                                        try { Thread.sleep(250); sendMediaLoad(u,m,t,ph); } catch (Exception e) { fail(e.getMessage()); }
                                    }
                                }, "cast-load-after-launch").start();
                            }
                        }
                    }
                }
            } else if (NS_RECEIVER.equals(ns) && "LAUNCH_ERROR".equals(type)) {
                fail("TV rejected Cast launch: " + o.optString("reason", "unknown"));
            } else if (NS_MEDIA.equals(ns) && "MEDIA_STATUS".equals(type)) {
                JSONArray st = o.optJSONArray("status");
                if (st != null && st.length() > 0) {
                    JSONObject m = st.optJSONObject(0);
                    if (m != null) {
                        mediaSessionId = m.optInt("mediaSessionId", mediaSessionId);
                        String state = m.optString("playerState", "UNKNOWN");
                        long pos = Math.round(m.optDouble("currentTime", 0) * 1000);
                        JSONObject media = m.optJSONObject("media");
                        long dur = media == null ? 0 : Math.round(media.optDouble("duration", 0) * 1000);
                        listener.onMediaStatus(state, pos, dur);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Cast message parse failed", e);
        }
    }

    void load(String url, String mime, String title, boolean photo) {
        try {
            if (sessionId.isEmpty() || transportId == null || RECEIVER.equals(transportId)) {
                pendingUrl=url; pendingMime=mime; pendingTitle=title; pendingPhoto=photo;
                send(NS_RECEIVER, RECEIVER, new JSONObject()
                        .put("type", "LAUNCH").put("requestId", nextId()).put("appId", APP_ID));
                listener.onStatus("starting", title);
                return;
            }
            sendMediaLoad(url,mime,title,photo);
        } catch (Exception e) {
            fail("Could not start casting: " + e.getMessage());
        }
    }

    private void sendMediaLoad(String url, String mime, String title, boolean photo) throws Exception {
        JSONObject metadata = new JSONObject()
                .put("metadataType", photo ? 4 : 0)
                .put("title", title == null ? "Cast media" : title);
        JSONObject media = new JSONObject()
                .put("contentId", url)
                .put("streamType", photo ? "NONE" : "BUFFERED")
                .put("contentType", mime)
                .put("metadata", metadata);
        JSONObject msg = new JSONObject()
                .put("type", "LOAD")
                .put("requestId", nextId())
                .put("media", media)
                .put("autoplay", true)
                .put("customData", new JSONObject());
        if (!sessionId.isEmpty()) msg.put("sessionId", sessionId);
        send(NS_MEDIA, transportId, msg);
        listener.onStatus("loading", title);
    }

    void play() { command("PLAY"); }
    void pause() { command("PAUSE"); }
    void stop() { command("STOP"); }

    void seek(long positionMs) {
        try {
            JSONObject c = new JSONObject().put("type","SEEK").put("requestId",nextId())
                    .put("currentTime", Math.max(0, positionMs) / 1000.0);
            withSession(c);
            send(NS_MEDIA, transportId, c);
        } catch (Exception e) { fail(e.getMessage()); }
    }

    private void command(String type) {
        try {
            JSONObject c = withSession(new JSONObject().put("type",type).put("requestId",nextId()));
            send(NS_MEDIA, transportId, c);
        } catch (Exception e) { fail(e.getMessage()); }
    }

    private JSONObject withSession(JSONObject o) throws Exception {
        if (!sessionId.isEmpty()) o.put("sessionId", sessionId);
        if (mediaSessionId != 0) o.put("mediaSessionId", mediaSessionId);
        return o;
    }

    private void send(String ns, String dest, JSONObject json) throws Exception {
        final byte[] msg = castMessage(ns, dest, json.toString());
        writer.execute(new Runnable() {
            public void run() {
                try {
                    synchronized (CastClient.this) {
                        if (!closed && out != null) {
                            DataOutputStream d = new DataOutputStream(out);
                            d.writeInt(msg.length);
                            d.write(msg);
                            d.flush();
                        }
                    }
                } catch (Exception e) {
                    fail("Cast send failed");
                }
            }
        });
    }

    private static byte[] castMessage(String ns, String dest, String payload) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        fieldVarint(b, 1, 0);              // CASTV2_1_0
        fieldString(b, 2, SOURCE);
        fieldString(b, 3, dest);
        fieldString(b, 4, ns);
        fieldVarint(b, 5, 0);              // STRING
        fieldString(b, 6, payload);
        return b.toByteArray();
    }

    private static void fieldString(ByteArrayOutputStream b, int field, String s) throws Exception {
        byte[] x = s.getBytes(StandardCharsets.UTF_8);
        writeVarint(b, ((long)field << 3) | 2);
        writeVarint(b, x.length);
        b.write(x);
    }
    private static void fieldVarint(ByteArrayOutputStream b, int field, long v) throws Exception {
        writeVarint(b, ((long)field << 3));
        writeVarint(b, v);
    }
    private static void writeVarint(ByteArrayOutputStream b, long v) {
        while ((v & ~0x7fL) != 0) { b.write((int)((v & 0x7f) | 0x80)); v >>>= 7; }
        b.write((int)v);
    }
    private static long readVarint(byte[] a, int p) {
        long v=0; int sh=0;
        while (p<a.length) { int c=a[p++]&255; v|=(long)(c&127)<<sh; if((c&128)==0) break; sh+=7; }
        return v;
    }
    private static int varintEnd(byte[] a, int p) {
        while (p<a.length && (a[p++]&128)!=0) {}
        return p;
    }

    private void fail(String m) {
        if (closed) return;
        Log.e(TAG, m);
        listener.onError(m == null ? "Cast error" : m);
    }

    void close() {
        closed = true;
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        writer.shutdownNow();
        if (heartbeat != null) heartbeat.shutdownNow();
    }
}
