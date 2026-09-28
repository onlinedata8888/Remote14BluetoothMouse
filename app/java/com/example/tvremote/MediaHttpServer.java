
package com.example.tvremote;

import android.content.Context;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.database.Cursor;
import java.io.*;
import java.net.*;
import java.util.UUID;

final class MediaHttpServer {
    private final Context context;
    private ServerSocket server;
    private Thread thread;
    private volatile boolean stopped;
    private Uri uri;
    private String mime;
    private String title;

    MediaHttpServer(Context c) { context = c.getApplicationContext(); }

    synchronized String publish(Uri u, String mt, String name) throws Exception {
        stop();
        uri = u; mime = mt == null || mt.length()==0 ? "application/octet-stream" : mt;
        title = name == null ? "media" : name;
        server = new ServerSocket(0);
        stopped = false;
        thread = new Thread(new Runnable() {
            public void run() { loop(); }
        }, "cast-http");
        thread.setDaemon(true);
        thread.start();
        String ip = wifiIp();
        if (ip == null) throw new IOException("Phone Wi-Fi IP not found");
        return "http://" + ip + ":" + server.getLocalPort() + "/media/" + UUID.randomUUID();
    }

    private void loop() {
        while (!stopped && server != null) {
            try {
                final Socket s = server.accept();
                Thread t = new Thread(new Runnable() {
                    public void run() { handle(s); }
                }, "cast-http-client");
                t.setDaemon(true); t.start();
            } catch (Exception e) { if (!stopped) {} }
        }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(15000);
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "ISO-8859-1"));
            String requestLine = r.readLine();
            if (requestLine == null) { s.close(); return; }
            String line;
            String range = null;
            while ((line = r.readLine()) != null && line.length() > 0) {
                if (line.toLowerCase().startsWith("range:")) range = line.substring(6).trim();
            }
            long size = sizeOf(uri);
            long start = 0, end = size > 0 ? size - 1 : -1;
            if (range != null && range.startsWith("bytes=") && size > 0) {
                String x = range.substring(6).split(",")[0].trim();
                String[] q = x.split("-",2);
                try {
                    start = Long.parseLong(q[0]);
                    if (q.length > 1 && q[1].length() > 0) end = Long.parseLong(q[1]);
                } catch (Exception ignored) {}
                if (start < 0) start = 0;
                if (end >= size) end = size - 1;
                if (start > end) { write(s, "HTTP/1.1 416 Range Not Satisfiable\r\n\r\n".getBytes("ISO-8859-1")); return; }
            }

            String method = requestLine.startsWith("HEAD") ? "HEAD" : "GET";
            if (size > 0) {
                long len = end >= 0 ? end - start + 1 : -1;
                String status = (range != null ? "206 Partial Content" : "200 OK");
                String h = "HTTP/1.1 " + status + "\r\n" +
                        "Content-Type: " + mime + "\r\n" +
                        "Content-Length: " + len + "\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        (range != null ? "Content-Range: bytes " + start + "-" + end + "/" + size + "\r\n" : "") +
                        "Connection: close\r\n\r\n";
                write(s,h.getBytes("ISO-8859-1"));
                if ("GET".equals(method)) stream(s,start,len);
            } else {
                // Some SAF providers do not expose length. Chunked streaming still works for images/audio,
                // but Chromecast video generally needs a known length/range-capable provider.
                String h = "HTTP/1.1 200 OK\r\nContent-Type: " + mime + "\r\nConnection: close\r\n\r\n";
                write(s,h.getBytes("ISO-8859-1"));
                if ("GET".equals(method)) stream(s,0,-1);
            }
        } catch (Exception ignored) {
        } finally { try{s.close();}catch(Exception ignored){} }
    }

    private void stream(Socket s, long skip, long len) throws Exception {
        InputStream in = context.getContentResolver().openInputStream(uri);
        if (in == null) return;
        try {
            long left = skip;
            while (left > 0) { long n=in.skip(left); if(n<=0) { if(in.read()<0) return; n=1;} left-=n; }
            byte[] buf = new byte[64*1024];
            long remain = len;
            int n;
            while ((n=in.read(buf)) >= 0 && (remain < 0 || remain > 0)) {
                if (remain >= 0 && n > remain) n=(int)remain;
                s.getOutputStream().write(buf,0,n);
                if (remain >= 0) remain -= n;
                if (n == 0) break;
            }
            s.getOutputStream().flush();
        } finally { try{in.close();}catch(Exception ignored){} }
    }

    private void write(Socket s, byte[] b) throws IOException { s.getOutputStream().write(b); s.getOutputStream().flush(); }

    private long sizeOf(Uri u) {
        Cursor c=null;
        try {
            c=context.getContentResolver().query(u,new String[]{OpenableColumns.SIZE},null,null,null);
            if(c!=null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch(Exception ignored){} finally{if(c!=null)c.close();}
        return -1;
    }

    private String wifiIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ns = java.net.NetworkInterface.getNetworkInterfaces();
            while(ns.hasMoreElements()){
                java.net.NetworkInterface n=ns.nextElement();
                if(!n.isUp() || n.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> as=n.getInetAddresses();
                while(as.hasMoreElements()){
                    java.net.InetAddress a=as.nextElement();
                    if(!a.isLoopbackAddress() && a instanceof java.net.Inet4Address) return a.getHostAddress();
                }
            }
        } catch(Exception ignored){}
        return null;
    }

    synchronized void stop() {
        stopped=true;
        try{if(server!=null)server.close();}catch(Exception ignored){}
        server=null;
    }
}
