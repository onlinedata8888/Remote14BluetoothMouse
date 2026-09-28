package com.example.tvremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Build;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.widget.EditText;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Glue between the remote.html UI (JS object "TVNative") and the real Android TV Remote v2 protocol.
 * The HTML calls key()/keys()/text()/launch()/connect()...; we call back window.__tv.onXxx().
 */
public final class TvBridge implements Discovery.Callback {
    private static TvBridge inst;
    static TvBridge getInstance() { return inst; }

    public static void install(Activity a, WebView w) {
        if (inst != null) inst.dispose();
        inst = new TvBridge(a, w);
        w.addJavascriptInterface(inst, "TVNative");
        final TvBridge self = inst;
        // remote.html is a file:// page and fetches thumbnails from https://tvthumb.local/…
        // (answered locally by shouldInterceptRequest, never hits the network). Make sure the
        // WebView never blocks that as "mixed content".
        try {
            android.webkit.WebSettings ws = w.getSettings();
            ws.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            ws.setAllowContentAccess(true);
        } catch (Throwable ignored) {}
        w.setWebViewClient(new android.webkit.WebViewClient() {
            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView view, android.webkit.WebResourceRequest req) {
                android.net.Uri u = req.getUrl();
                if (u != null && CastBridge.THUMB_HOST.equals(u.getHost())) return self.thumbResponse(u);
                return null;
            }
        });
        inst.begin();
    }

    public static void shutdown() {
        if (inst != null) {
            inst.dispose();
            inst = null;
        }
    }

    /**
     * Called from MainActivity.onBackPressed() (gesture-back AND the back button).
     * Asks the page to close its top-most layer (keyboard / dropdown / cast album / cast / mouse
     * page / 2nd page). If the page closed something it answers "1" and we stay in the app;
     * only when the main remote page is showing (answer "0") do we really leave.
     * Returns immediately; if anything goes wrong we fall back to leaving the app.
     */
    public static void handleBack(final Activity a, final WebView w) {
        if (w == null) { a.finish(); return; }
        try {
            w.evaluateJavascript(
                "(function(){try{var t=window.__tv;return (t&&t.handleBack&&t.handleBack())?'1':'0';}catch(e){return '0';}})()",
                new android.webkit.ValueCallback<String>() {
                    @Override public void onReceiveValue(String v) {
                        boolean consumed = v != null && v.indexOf('1') >= 0;
                        if (!consumed) a.finish();
                    }
                });
        } catch (Throwable t) {
            a.finish();
        }
    }

    private static final String CANCEL = "__cancel__";
    private static final int PORT_REMOTE = 6466;
    private static final int PORT_PAIR = 6467;

    private final Activity act;
    private final WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final Discovery disc;
    private final LinkedBlockingQueue<String> codeQ = new LinkedBlockingQueue<String>();
    private final ScheduledExecutorService ctl = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "tv-control");
            t.setDaemon(true);
            return t;
        }
    });

    // --- state below is only touched on the ctl thread unless marked volatile ---
    private CertStore certs;
    private Discovery.Device cur;
    private volatile RemoteSession sess;
    private volatile String state = "offline";
    private int gen;
    private int attempts;
    private boolean autoReconnect = true;
    private volatile boolean paused;
    private volatile long lastKick;
    private boolean disposed;
    private Application.ActivityLifecycleCallbacks lifecycle;
    private VoiceRecorder voice;
    private CastBridge cast;
    private MqttMouseBridge mouse;
    private BluetoothMouseBridge btMouse;

    private TvBridge(Activity a, WebView w) {
        act = a;
        web = w;
        prefs = a.getSharedPreferences("tvremote", 0);
        disc = new Discovery(a, this);
        cast = new CastBridge(a, w, this);
        mouse = new MqttMouseBridge(a);
        btMouse = new BluetoothMouseBridge(a, new BluetoothMouseBridge.Listener() {
            public void onState(boolean connected) {
                js("onMouseTransport(" + connected + ")");
            }
        });
    }

    private void begin() {
        lifecycle = new Application.ActivityLifecycleCallbacks() {
            public void onActivityPaused(Activity a) {
                if (a != act) return;
                paused = true;
                RemoteSession s = sess;
                if (s != null) s.releaseHeld();
            }

            public void onActivityResumed(Activity a) {
                if (a != act) return;
                paused = false;
                kick();
            }

            public void onActivityCreated(Activity a, Bundle b) {
            }

            public void onActivityStarted(Activity a) {
            }

            public void onActivityStopped(Activity a) {
            }

            public void onActivitySaveInstanceState(Activity a, Bundle b) {
            }

            public void onActivityDestroyed(Activity a) {
            }
        };
        act.getApplication().registerActivityLifecycleCallbacks(lifecycle);
        // generate the phone's identity in the background so the first pairing is not delayed
        Thread kg = new Thread(new Runnable() {
            public void run() {
                try {
                    ensureCerts();
                } catch (Throwable e) {
                    TvLog.d("identity: " + e);
                }
            }
        }, "tv-keygen");
        kg.setDaemon(true);
        kg.start();
    }

    private void dispose() {
        disposed = true;
        try {
            act.getApplication().unregisterActivityLifecycleCallbacks(lifecycle);
        } catch (Throwable ignored) {
        }
        codeQ.offer(CANCEL);
        disc.stop();
        if (cast != null) cast.dispose();
        if (btMouse != null) btMouse.close();
        VoiceRecorder v = voice;
        if (v != null) v.requestStop();
        RemoteSession s = sess;
        sess = null;
        if (s != null) s.close("app closed");
        ctl.shutdownNow();
    }

    private synchronized void ensureCerts() throws Exception {
        if (certs == null) certs = CertStore.loadOrCreate(act.getFilesDir());
    }

    // ------------------------------------------------------------------ JS <- native
    private void js(final String call) {
        if (disposed) return;
        ui.post(new Runnable() {
            public void run() {
                try {
                    web.evaluateJavascript("(function(){var t=window.__tv;if(t){t." + call + ";}})()", null);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static String q(String s) {
        return JSONObject.quote(s == null ? "" : s);
    }

    private void toast(final String m) {
        ui.post(new Runnable() {
            public void run() {
                Toast.makeText(act, m, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setState(String s) {
        state = s;
        pushState();
    }

    private void pushState() {
        js("onState(" + q(state) + "," + q(cur == null ? "" : cur.name) + "," + q(cur == null ? "" : cur.host) + ")");
    }

    private void pushDevices() {
        JSONArray arr = new JSONArray();
        List<Discovery.Device> l = disc.snapshot();
        try {
            for (int i = 0; i < l.size(); i++) {
                JSONObject o = new JSONObject();
                o.put("id", l.get(i).host);
                o.put("name", l.get(i).name);
                arr.put(o);
            }
        } catch (Exception ignored) {
        }
        js("onDevices(" + arr.toString() + ")");
    }

    // ------------------------------------------------------------------ discovery callback
    public void onChanged() {
        if (disposed) return;
        ctl.execute(new Runnable() {
            public void run() {
                pushDevices();
                List<Discovery.Device> l = disc.snapshot();
                // saved TV got a new IP address: follow it by name
                if (cur != null && "offline".equals(state)) {
                    for (int i = 0; i < l.size(); i++) {
                        Discovery.Device d = l.get(i);
                        if (d.name.equals(cur.name) && !d.host.equals(cur.host)) {
                            connectTo(d, true);
                            return;
                        }
                    }
                }
                // first run with exactly one TV around: select it for the user
                if (cur == null && !prefs.contains("host") && l.size() == 1) connectTo(l.get(0), true);
            }
        });
    }

    // ------------------------------------------------------------------ connection
    private final class SessionListener implements RemoteSession.Listener {
        private final int my;

        SessionListener(int my) {
            this.my = my;
        }

        public void onVolume(int level, int max, boolean muted) {
            if (my == gen) js("onVolume(" + level + "," + max + "," + muted + ")");
        }

        public void onPower(boolean on) {
            if (my == gen) js("onPower(" + on + ")");
        }

        public void onClosed(final RemoteSession s, final String reason) {
            TvLog.d("session closed: " + reason);
            if (disposed) return;
            ctl.execute(new Runnable() {
                public void run() {
                    if (my == gen && sess == s) {
                        sess = null;
                        setState("offline");
                        scheduleReconnect(my);
                    }
                }
            });
        }
    }

    /** Runs on the ctl thread. */
    private void connectTo(final Discovery.Device d, final boolean allowPair) {
        final int my = ++gen;
        RemoteSession old = sess;
        sess = null;
        if (old != null) old.close("switching");
        codeQ.offer(CANCEL); // stop any pairing dialog for another TV
        cur = d;
        if (cast != null) cast.setHost(d.host);
        setState("connecting");
        try {
            ensureCerts();
        } catch (Exception e) {
            TvLog.d("identity: " + e);
            setState("offline");
            toast("Could not create the phone's TV identity");
            return;
        }
        int result; // 0 ok, 1 unreachable, 2 not paired / rejected
        String err = "";
        RemoteSession s = null;
        try {
            s = RemoteSession.open(certs, d.host, PORT_REMOTE, new SessionListener(my));
            result = s.awaitReady(6000) ? 0 : (s.isClosed() ? 2 : 1);
            if (result == 1) err = "TV ne jawab nahi diya";
        } catch (SSLException e) {
            result = 2;
            err = String.valueOf(e);
        } catch (Exception e) {
            result = 1;
            err = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        }
        if (my != gen) {
            if (s != null) s.close("superseded");
            return;
        }
        if (result == 0) {
            sess = s;
            attempts = 0;
            prefs.edit().putString("host", d.host).putString("name", d.name).apply();
            setState("connected");
            toast("Connected: " + d.name);
            return;
        }
        if (s != null) s.close("connect failed");
        if (result == 2 && allowPair) {
            startPairing(d, my);
        } else {
            setState("offline");
            if (result == 2) {
                toast("TV did not accept the pairing. Tap the status dot to pair again.");
            } else {
                if (attempts == 0) toast("TV se connect nahi hua (" + d.host + "): " + err);
                scheduleReconnect(my);
            }
        }
    }

    private void scheduleReconnect(final int my) {
        if (paused || cur == null || !autoReconnect || disposed) return;
        long delay = Math.min(10000L, 1000L + 1500L * attempts++);
        try {
            ctl.schedule(new Runnable() {
                public void run() {
                    if (my == gen && sess == null && "offline".equals(state) && !paused && autoReconnect && cur != null)
                        connectTo(cur, true);
                }
            }, delay, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        }
    }

    /** Called when the app is used while disconnected: try again right away (rate limited). */
    private void kick() {
        long now = System.currentTimeMillis();
        if (now - lastKick < 1500) return;
        lastKick = now;
        if (disposed) return;
        ctl.execute(new Runnable() {
            public void run() {
                if (cur != null && sess == null && "offline".equals(state)) {
                    autoReconnect = true;
                    connectTo(cur, true);
                }
            }
        });
    }

    // ------------------------------------------------------------------ pairing
    private void startPairing(final Discovery.Device d, final int my) {
        setState("pairing");
        toast("TV par dikh rahe code ko app me type karo");
        codeQ.clear();
        Thread t = new Thread(new Runnable() {
            public void run() {
                runPairing(d, my);
            }
        }, "tv-pair");
        t.setDaemon(true);
        t.start();
    }

    private void runPairing(final Discovery.Device d, final int my) {
        PairingSession ps = null;
        try {
            boolean paired = false;
            while (!paired && my == gen) {
                ps = new PairingSession(certs, d.host, PORT_PAIR, "TV Remote");
                ps.begin();
                js("onPairing(" + q(d.name) + ")");
                while (!paired && my == gen) {
                    String code = codeQ.poll(300, TimeUnit.SECONDS);
                    if (code == null || CANCEL.equals(code) || my != gen) return;
                    try {
                        ps.finish(code);
                        paired = true;
                    } catch (PairingSession.WrongCodeException e) {
                        js("onPairError(" + q(e.getMessage()) + ")");
                    } catch (PairingSession.RejectedException e) {
                        js("onPairError(" + q("TV rejected the code. Enter the new code now shown on the TV.") + ")");
                        break; // start a fresh pairing session
                    }
                }
                ps.close();
                ps = null;
            }
            if (paired && my == gen) {
                js("onPaired()");
                ctl.execute(new Runnable() {
                    public void run() {
                        if (my == gen) connectTo(d, false);
                    }
                });
            }
        } catch (final Exception e) {
            TvLog.d("pairing failed: " + e);
            if (my == gen) {
                js("onPairEnd()");
                toast("Pairing failed: " + e.getMessage());
                ctl.execute(new Runnable() {
                    public void run() {
                        if (my == gen) {
                            autoReconnect = false;
                            setState("offline");
                        }
                    }
                });
            }
        } finally {
            if (ps != null) ps.close();
        }
    }

    // ------------------------------------------------------------------ JS -> native
    @JavascriptInterface
    public void ready() {
        disc.start(); // not on the control thread: discovery must never wait for anything else
        ctl.execute(new Runnable() {
            public void run() {
                pushDevices();
                pushState();
                if (cur == null && prefs.contains("host")) {
                    connectTo(new Discovery.Device(prefs.getString("name", "TV"), prefs.getString("host", "")), true);
                }
            }
        });
        try {
            ctl.schedule(new Runnable() {
                public void run() {
                    if (cur == null && disc.snapshot().isEmpty()) {
                        toast("Koi TV nahi mila - TV ka IP address daal sakte ho");
                        showIpDialog();
                    }
                }
            }, 10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    /** Native dialog to type the TV's IP address (bypasses discovery completely). */
    @JavascriptInterface
    public void manualIp() {
        showIpDialog();
    }

    private void showIpDialog() {
        ui.post(new Runnable() {
            public void run() {
                if (act.isFinishing()) return;
                final EditText et = new EditText(act);
                et.setInputType(InputType.TYPE_CLASS_TEXT);
                et.setHint("192.168.1.25");
                et.setText(prefs.getString("host", ""));
                et.setSelectAllOnFocus(true);
                new AlertDialog.Builder(act)
                        .setTitle("TV ka IP address")
                        .setMessage("TV: Settings > Network > Wi-Fi me IP address dekho. Phone aur TV same Wi-Fi par hone chahiye.")
                        .setView(et)
                        .setPositiveButton("Connect", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                connectManual(et.getText().toString().trim());
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
    }

    private void connectManual(final String ip) {
        if (!ip.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) {
            toast("Sahi IP daalo (jaise 192.168.1.25)");
            return;
        }
        final Discovery.Device d = new Discovery.Device("TV " + ip, ip);
        disc.addManual(d);
        ctl.execute(new Runnable() {
            public void run() {
                autoReconnect = true;
                attempts = 0;
                connectTo(d, true);
            }
        });
    }

    @JavascriptInterface
    public void connect(final String host) {
        ctl.execute(new Runnable() {
            public void run() {
                List<Discovery.Device> l = disc.snapshot();
                for (int i = 0; i < l.size(); i++) {
                    if (l.get(i).host.equals(host)) {
                        autoReconnect = true;
                        attempts = 0;
                        connectTo(l.get(i), true);
                        return;
                    }
                }
            }
        });
    }

    /** Tap on the status dot: reconnect (or pair again). */
    @JavascriptInterface
    public void reconnect() {
        ctl.execute(new Runnable() {
            public void run() {
                autoReconnect = true;
                attempts = 0;
                if (cur != null) connectTo(cur, true);
                else disc.rescan();
            }
        });
    }

    @JavascriptInterface
    public void rescan() {
        disc.rescan();
    }

    // Number-pad delete uses Android KEYCODE_DEL (67); key() forwards arbitrary Android key codes.
    /** dir: 1 = key down, 2 = key up, 3 = tap. */
    /**
     * Starts the real Bluetooth HID mouse. The TV must pair with the phone
     * shown as "Remote 13 Mouse" in the TV Bluetooth settings.
     */
    @JavascriptInterface
    public boolean mouseConnect() {
        if (btMouse != null) {
            if (btMouse.isConnected()) return true;
            btMouse.start();
            return btMouse.isConnected();
        }
        return false;
    }

    @JavascriptInterface
    public void mouseMove(int dx, int dy) {
        if (btMouse != null && btMouse.isConnected()) {
            btMouse.move(dx, dy);
            return;
        }
        // Keep the old MQTT path as a compatibility fallback for TVs that expose it.
        Discovery.Device d = cur;
        if (mouse == null || d == null || !mouse.connect(d.host)) return;
        mouse.move(dx, dy);
    }

    @JavascriptInterface
    public void mouseClick() {
        if (btMouse != null && btMouse.isConnected()) {
            btMouse.click();
            return;
        }
        Discovery.Device d = cur;
        if (mouse == null || d == null || !mouse.connect(d.host)) return;
        mouse.click();
    }

    @JavascriptInterface
    public void mouseDown() {
        if (btMouse != null && btMouse.isConnected()) {
            btMouse.down();
            return;
        }
        Discovery.Device d = cur;
        if (mouse == null || d == null || !mouse.connect(d.host)) return;
        mouse.down();
    }

    @JavascriptInterface
    public void mouseUp() {
        if (btMouse != null && btMouse.isConnected()) {
            btMouse.up();
            return;
        }
        Discovery.Device d = cur;
        if (mouse == null || d == null || !mouse.connect(d.host)) return;
        mouse.up();
    }

    @JavascriptInterface
    public void key(int code, int dir) {
        RemoteSession s = sess;
        if (s != null) s.key(code, dir);
        else if (dir != 2) kick();
    }

    @JavascriptInterface
    public void keys(int code, int count) {
        RemoteSession s = sess;
        if (s != null) s.keys(code, count);
        else kick();
    }

    @JavascriptInterface
    public void text(String t) {
        RemoteSession s = sess;
        if (s != null) s.text(t);
        else kick();
    }

    @JavascriptInterface
    public void launch(String link) {
        RemoteSession s = sess;
        if (s != null) s.launch(link);
        else kick();
    }

    @JavascriptInterface
    public void pairCode(String code) {
        codeQ.offer(code == null ? "" : code);
    }

    @JavascriptInterface
    public void cancelPairing() {
        ctl.execute(new Runnable() {
            public void run() {
                if ("pairing".equals(state)) {
                    gen++;
                    codeQ.offer(CANCEL);
                    autoReconnect = false;
                    setState("offline");
                }
            }
        });
    }

    /** Mic button tapped: start streaming the phone's microphone to the TV; tap again to stop. */
    @JavascriptInterface
    public void voiceToggle() {
        VoiceRecorder running;
        synchronized (this) {
            running = voice;
        }
        if (running != null) {
            running.requestStop();
            toast("Voice bhej rahe hain...");
            return;
        }
        final RemoteSession s = sess;
        if (s == null) {
            toast("TV se connect nahi hai");
            kick();
            return;
        }
        if (Build.VERSION.SDK_INT >= 23
                && act.checkSelfPermission("android.permission.RECORD_AUDIO") != PackageManager.PERMISSION_GRANTED) {
            ui.post(new Runnable() {
                public void run() {
                    act.requestPermissions(new String[]{"android.permission.RECORD_AUDIO"}, 71);
                }
            });
            toast("Mic permission allow karo, phir mic dobara dabao");
            return;
        }
        if (!s.voiceSupported()) {
            s.key(84, 3);
            toast("Is TV me voice streaming nahi hai, Search khola");
            return;
        }
        synchronized (this) {
            if (voice != null) return;
            voice = new VoiceRecorder(s, new VoiceRecorder.Sink() {
                public void toast(String m) {
                    TvBridge.this.toast(m);
                }

                public void started() {
                    // Fired only once the TV has actually opened its listening session --
                    // this is the real cue to speak, not the moment the button was tapped.
                    TvBridge.this.toast("Bolo ab... (bolna khatam hone par apne aap ruk jayega)");
                }

                public void finished() {
                    synchronized (TvBridge.this) {
                        voice = null;
                    }
                }
            });
        }
        Thread t = new Thread(voice, "tv-voice");
        t.setDaemon(true);
        t.start();
        toast("TV se connect ho raha hai...");
    }

    // ------------------------------------------------------------------ Cast / media sender
    @JavascriptInterface
    public void castPick(String kind) {
        if (cast != null) cast.pick(kind);
    }

    void castPicked(android.net.Uri uri, String kind) {
        if (cast != null) cast.handlePicked(uri, kind);
    }

    /** True once the runtime media permission for this kind is granted; otherwise asks for it. */
    @JavascriptInterface
    public boolean galleryReady(final String kind) {
        if (cast == null) return false;
        if (cast.hasMediaPermission(kind)) return true;
        ui.post(new Runnable() {
            public void run() { act.requestPermissions(CastBridge.mediaPermissionsFor(kind), 72); }
        });
        return false;
    }

    /** [{"id","name","count","cover"}] — albums / video folders / audio albums. Text only = instant. */
    @JavascriptInterface
    public String listGalleryAlbums(String kind) {
        return cast != null ? cast.listAlbums(kind) : "[]";
    }

    /** [{"id","title","sub"}] — items of one album. Text only = instant; thumbnails load lazily via tvthumb.local. */
    @JavascriptInterface
    public String listGalleryItems(String kind, String bucket) {
        return cast != null ? cast.listItems(kind, bucket) : "[]";
    }

    /** Serves https://tvthumb.local/<kind>/<id> thumbnails to the WebView on its own worker thread. */
    android.webkit.WebResourceResponse thumbResponse(android.net.Uri u) {
        try {
            java.util.List<String> seg = u.getPathSegments();
            if (seg.size() < 2 || cast == null) return null;
            java.io.InputStream in = cast.thumbStream(seg.get(0), Long.parseLong(seg.get(1)));
            if (in == null) {
                return new android.webkit.WebResourceResponse("image/gif", null, 404, "Not Found",
                        new java.util.HashMap<String, String>(), new java.io.ByteArrayInputStream(new byte[0]));
            }
            java.util.HashMap<String, String> h = new java.util.HashMap<String, String>();
            h.put("Cache-Control", "max-age=86400");
            h.put("Access-Control-Allow-Origin", "*");
            return new android.webkit.WebResourceResponse("image/jpeg", null, 200, "OK", h, in);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Casts a MediaStore item directly by its id/kind — no SAF picker Activity involved,
     * so the gallery grid in remote.html stays on screen and the user can tap the next
     * photo immediately after this one starts casting.
     */
    @JavascriptInterface
    public void castMediaStoreItem(String id, String kind) {
        if (cast != null) cast.castMediaStoreItem(id, kind);
    }

    @JavascriptInterface public void castPlay() { if (cast != null) cast.play(); }
    @JavascriptInterface public void castPause() { if (cast != null) cast.pause(); }
    @JavascriptInterface public void castStop() { if (cast != null) cast.stop(); }
    @JavascriptInterface public void castSeek(long ms) { if (cast != null) cast.seek(ms); }

    /** Opens the device Wi-Fi Display / Cast settings. This is the platform fallback for
     * devices whose vendor exposes Miracast/WFD as a system feature; it does not fake a
     * network mirror when the OS/TV does not expose a WFD receiver. */
    @JavascriptInterface
    public void mirrorStart() {
        try {
            android.content.Intent i = new android.content.Intent("android.settings.WIFI_DISPLAY_SETTINGS");
            act.startActivity(i);
            toast("Wi-Fi Display / Screen Mirroring khol raha hai…");
        } catch (Throwable first) {
            try {
                android.content.Intent i = new android.content.Intent(android.provider.Settings.ACTION_CAST_SETTINGS);
                act.startActivity(i);
                toast("Cast / Screen Mirroring settings khol raha hai…");
            } catch (Throwable second) {
                toast("Is phone par system Screen Mirroring available nahi hai");
            }
        }
    }

    @JavascriptInterface
    public void toastMsg(String m) {
        toast(m);
    }
}
