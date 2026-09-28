package com.example.tvremote;

import android.content.Context;
import android.provider.Settings;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

/**
 * Small MQTT 3.1.1 client used only for the VU/Hisense-style pointer service.
 * The Android TV Remote v2 protocol used by the main app exposes key/IME messages,
 * but no pointer packet. The older RemoteNOW protocol exposes /actions/mouse.
 */
final class MqttMouseBridge {
    private static final int PORT = 36669;
    private static final String USER = "hisenseservice";
    private static final String PASS = "multimqttservice";
    private static final String ACTION = "/remoteapp/tv/remote_service/";
    private static final String MOUSE = "/actions/mouse";

    private final Context ctx;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile Socket socket;
    private volatile DataOutputStream out;
    private volatile boolean connected;
    private volatile String host;
    private volatile String topic;
    private final String id;

    MqttMouseBridge(Context c) {
        ctx = c.getApplicationContext();
        String a = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (a == null || a.length() == 0) a = "remotenow";
        id = "RemoteNOW_" + a;
    }

    boolean connect(String h) {
        if (h == null || h.length() == 0) return false;
        if (connected && h.equals(host)) return true;
        close();
        host = h;
        // RemoteNOW/Hisense uses the phone/client identifier before $normal.
        topic = ACTION + id + "$normal" + MOUSE;
        try {
            Socket s;
            try {
                s = tlsSocket(h, PORT);
                if (!mqttConnect(s, id)) throw new IOException("TLS MQTT rejected");
            } catch (Throwable tlsFail) {
                try { if (socket != null) socket.close(); } catch (Throwable ignored) {}
                s = new Socket();
                s.connect(new InetSocketAddress(h, PORT), 2500);
                if (!mqttConnect(s, id)) throw new IOException("MQTT rejected");
            }
            socket = s;
            out = new DataOutputStream(s.getOutputStream());
            connected = true;
            return true;
        } catch (Throwable e) {
            connected = false;
            try { if (socket != null) socket.close(); } catch (Throwable ignored) {}
            socket = null; out = null;
            TvLog.d("mouse mqtt connect: " + e);
            return false;
        }
    }

    void move(final int dx, final int dy) {
        if (dx == 0 && dy == 0) return;
        publish("REL_" + fmt(dx) + "_" + fmt(dy) + "_0000");
    }

    void click() { publish("REL_LEFT_OK"); }
    void down() { publish("REL_LEFT_DOWN"); }
    void up() { publish("REL_LEFT_UP"); }

    private static String fmt(int n) {
        int v = Math.max(-9999, Math.min(9999, n));
        return String.format(java.util.Locale.US, "%04d", v);
    }

    private void publish(final String payload) {
        if (!connected || out == null) return;
        io.execute(new Runnable() {
            public void run() {
                try {
                    byte[] p = payload.getBytes("UTF-8");
                    byte[] t = topic.getBytes("UTF-8");
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    b.write(0x30); // PUBLISH QoS 0
                    int rem = 2 + t.length + p.length;
                    writeRemaining(b, rem);
                    b.write((t.length >>> 8) & 255); b.write(t.length & 255); b.write(t);
                    b.write(p);
                    out.write(b.toByteArray()); out.flush();
                } catch (Throwable e) {
                    connected = false;
                    TvLog.d("mouse mqtt publish: " + e);
                }
            }
        });
    }

    private static void writeRemaining(ByteArrayOutputStream b, int n) {
        do {
            int d = n % 128; n /= 128;
            if (n > 0) d |= 128;
            b.write(d);
        } while (n > 0);
    }

    private static SSLSocket tlsSocket(String h, int port) throws Exception {
        TrustManager[] trust = new TrustManager[]{new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
        }};
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, trust, new java.security.SecureRandom());
        SSLSocket s = (SSLSocket) sc.getSocketFactory().createSocket();
        s.connect(new InetSocketAddress(h, port), 2500);
        s.setSoTimeout(3000);
        return s;
    }

    private static boolean mqttConnect(Socket s, String clientId) throws Exception {
        DataOutputStream o = new DataOutputStream(s.getOutputStream());
        ByteArrayOutputStream v = new ByteArrayOutputStream();
        // Variable header: MQTT 3.1.1, clean session, username/password.
        v.write(0); v.write(4); v.write('M'); v.write('Q'); v.write('T'); v.write('T'); v.write(4);
        v.write(0xC2); // clean session + username + password
        v.write(0); v.write(30); // keepalive
        byte[] cid = clientId.getBytes("UTF-8"), user = USER.getBytes("UTF-8"), pass = PASS.getBytes("UTF-8");
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        putUtf(payload, cid); putUtf(payload, user); putUtf(payload, pass);
        byte[] all = new byte[v.size() + payload.size()];
        System.arraycopy(v.toByteArray(), 0, all, 0, v.size());
        System.arraycopy(payload.toByteArray(), 0, all, v.size(), payload.size());
        o.writeByte(0x10); writeRemaining(o, all.length); o.write(all); o.flush();
        DataInputStream in = new DataInputStream(s.getInputStream());
        int h = in.readUnsignedByte(); int len = readRemaining(in);
        if ((h & 0xF0) != 0x20 || len < 2) return false;
        int ack = in.readUnsignedByte(); int code = in.readUnsignedByte();
        while (len-- > 2) in.readUnsignedByte();
        return ack == 0 && code == 0;
    }

    private static void putUtf(ByteArrayOutputStream b, byte[] x) {
        b.write((x.length >>> 8) & 255); b.write(x.length & 255); b.write(x, 0, x.length);
    }
    private static void writeRemaining(DataOutputStream o, int n) throws IOException {
        do { int d=n%128; n/=128; if(n>0)d|=128; o.writeByte(d); } while(n>0);
    }
    private static int readRemaining(DataInputStream in) throws IOException {
        int m=1,v=0,d; do { d=in.readUnsignedByte(); v+=(d&127)*m; m*=128; if(m>128*128*128*128) throw new IOException("bad MQTT length"); } while((d&128)!=0); return v;
    }

    void close() {
        connected = false;
        try { if (socket != null) socket.close(); } catch (Throwable ignored) {}
        socket = null; out = null;
    }
}
