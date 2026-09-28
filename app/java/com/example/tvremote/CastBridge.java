package com.example.tvremote;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.database.Cursor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.content.Context;
import android.content.ContentUris;
import android.graphics.Bitmap;
import android.util.Size;
import android.util.LruCache;
import java.io.ByteArrayOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

final class CastBridge {
    private final Activity act;
    private final WebView web;
    private final TvBridge parent;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final MediaHttpServer server;
    private CastClient client;
    private String host;

    CastBridge(Activity a, WebView w, TvBridge p) {
        act=a; web=w; parent=p; server=new MediaHttpServer(a);
    }

    void setHost(String h) { host=h; }

    @JavascriptInterface
    public void pick(String kind) {
        if (host == null || host.length()==0) {
            toast("TV se connect nahi hai");
            return;
        }
        Intent i = new Intent(act, CastPickerActivity.class);
        i.putExtra("kind", kind == null ? "all" : kind);
        act.startActivity(i);
    }

    static void picked(final Uri uri, final String kind) {
        if (TvBridge.getInstance() != null) {
            TvBridge.getInstance().castPicked(uri, kind);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Gallery (fast path).
    //
    // The old approach decoded every thumbnail, base64-encoded it and pushed it through the JS
    // bridge inside one giant JSON string — all while blocking the UI thread. That is what made
    // the grid crawl.
    //
    // Now the list calls only return TEXT (id / title / count), which is instant. Thumbnails are
    // fetched lazily by the WebView itself through https://tvthumb.local/<kind>/<id>, which
    // TvBridge.shouldInterceptRequest() -> CastBridge.thumbStream() answers from a background
    // thread. The browser only asks for cells that are actually on screen (loading="lazy"),
    // exactly like Coil's AsyncImage did in the old native Cast Remote app.
    // ------------------------------------------------------------------------------------------
    static final String THUMB_HOST = "tvthumb.local";
    private static final int THUMB_PX = 256;
    private static final LruCache<String, byte[]> thumbCache = new LruCache<String, byte[]>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String k, byte[] v) { return v.length; }
    };

    private static Uri collectionFor(String kind) {
        if ("video".equals(kind)) return MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        if ("audio".equals(kind)) return MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        return MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
    }

    /** Albums (photos), folders (videos) or albums (audio) with a cover id and item count.
     *  JSON: [{"id":"<bucket|albumId>","name":"Camera","count":123,"cover":"<mediaId>"}] */
    String listAlbums(String kind) {
        JSONArray out = new JSONArray();
        if (!hasMediaPermission(kind)) return out.toString();
        Uri collection = collectionFor(kind);
        String idCol, nameCol;
        if ("video".equals(kind)) {
            idCol = MediaStore.Video.Media.BUCKET_ID; nameCol = MediaStore.Video.Media.BUCKET_DISPLAY_NAME;
        } else if ("audio".equals(kind)) {
            idCol = MediaStore.Audio.Media.ALBUM_ID; nameCol = MediaStore.Audio.Media.ALBUM;
        } else {
            idCol = MediaStore.Images.Media.BUCKET_ID; nameCol = MediaStore.Images.Media.BUCKET_DISPLAY_NAME;
        }
        java.util.LinkedHashMap<String, Object[]> map = new java.util.LinkedHashMap<String, Object[]>();
        Cursor c = null;
        try {
            c = act.getContentResolver().query(collection,
                    new String[]{ MediaStore.MediaColumns._ID, idCol, nameCol },
                    null, null, MediaStore.MediaColumns.DATE_ADDED + " DESC");
            if (c != null) {
                while (c.moveToNext()) {
                    String bid = c.getString(1);
                    if (bid == null) continue;
                    Object[] e = map.get(bid);
                    if (e == null) {
                        String n = c.getString(2);
                        map.put(bid, new Object[]{ n == null ? "Other" : n, Integer.valueOf(1), Long.valueOf(c.getLong(0)) });
                    } else {
                        e[1] = Integer.valueOf(((Integer) e[1]).intValue() + 1);
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        for (java.util.Map.Entry<String, Object[]> en : map.entrySet()) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", en.getKey());
                o.put("name", (String) en.getValue()[0]);
                o.put("count", ((Integer) en.getValue()[1]).intValue());
                o.put("cover", String.valueOf(en.getValue()[2]));
                out.put(o);
            } catch (Exception ignored) {}
        }
        return out.toString();
    }

    /** Items of one album/folder. Text only (fast). JSON array of {id,title,sub}. */
    String listItems(String kind, String bucket) {
        JSONArray out = new JSONArray();
        if (!hasMediaPermission(kind)) return out.toString();
        Uri collection = collectionFor(kind);
        String nameCol, bucketCol, extraCol = null;
        if ("video".equals(kind)) {
            nameCol = MediaStore.Video.Media.DISPLAY_NAME; bucketCol = MediaStore.Video.Media.BUCKET_ID;
            extraCol = MediaStore.Video.Media.DURATION;
        } else if ("audio".equals(kind)) {
            nameCol = MediaStore.Audio.Media.TITLE; bucketCol = MediaStore.Audio.Media.ALBUM_ID;
            extraCol = MediaStore.Audio.Media.ARTIST;
        } else {
            nameCol = MediaStore.Images.Media.DISPLAY_NAME; bucketCol = MediaStore.Images.Media.BUCKET_ID;
        }
        String[] proj = extraCol == null
                ? new String[]{ MediaStore.MediaColumns._ID, nameCol }
                : new String[]{ MediaStore.MediaColumns._ID, nameCol, extraCol };
        String sel = null; String[] args = null;
        if (bucket != null && bucket.length() > 0) { sel = bucketCol + "=?"; args = new String[]{ bucket }; }
        Cursor c = null;
        try {
            c = act.getContentResolver().query(collection, proj, sel, args,
                    MediaStore.MediaColumns.DATE_ADDED + " DESC, " + MediaStore.MediaColumns._ID + " DESC");
            if (c != null) {
                while (c.moveToNext()) {
                    JSONObject o = new JSONObject();
                    o.put("id", c.getLong(0));
                    String n = c.getString(1);
                    o.put("title", n == null ? "" : n);
                    if (extraCol != null) {
                        if ("video".equals(kind)) {
                            long ms = c.getLong(2); long t = ms / 1000;
                            o.put("sub", (t / 60) + ":" + (t % 60 < 10 ? "0" : "") + (t % 60));
                        } else {
                            String ar = c.getString(2);
                            o.put("sub", ar == null || "<unknown>".equals(ar) ? "" : ar);
                        }
                    }
                    out.put(o);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return out.toString();
    }

    /** Called from the WebView's resource thread for https://tvthumb.local/<kind>/<id>.
     *  Returns a small JPEG (cached) or null. Never touches the UI thread. */
    java.io.InputStream thumbStream(String kind, long id) {
        String key = kind + ":" + id;
        byte[] data = thumbCache.get(key);
        if (data == null) {
            Bitmap bmp = null;
            Uri item = ContentUris.withAppendedId(collectionFor(kind), id);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    bmp = act.getContentResolver().loadThumbnail(item, new Size(THUMB_PX, THUMB_PX), null);
                } else if ("video".equals(kind)) {
                    bmp = MediaStore.Video.Thumbnails.getThumbnail(act.getContentResolver(), id, MediaStore.Video.Thumbnails.MINI_KIND, null);
                } else if (!"audio".equals(kind)) {
                    bmp = MediaStore.Images.Thumbnails.getThumbnail(act.getContentResolver(), id, MediaStore.Images.Thumbnails.MINI_KIND, null);
                }
            } catch (Exception ignored) {}
            if (bmp == null) return null;
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.JPEG, 78, bos);
                data = bos.toByteArray();
            } finally { bmp.recycle(); }
            thumbCache.put(key, data);
        }
        return new java.io.ByteArrayInputStream(data);
    }

    boolean hasMediaPermission(String kind) {
        String perm;
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if ("video".equals(kind)) perm = android.Manifest.permission.READ_MEDIA_VIDEO;
            else if ("audio".equals(kind)) perm = android.Manifest.permission.READ_MEDIA_AUDIO;
            else perm = android.Manifest.permission.READ_MEDIA_IMAGES;
        } else {
            perm = android.Manifest.permission.READ_EXTERNAL_STORAGE;
        }
        return act.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** Same list of permission strings as {@link #hasMediaPermission}, exposed for the
     *  runtime permission request triggered from JS before the grid loads. */
    static String[] mediaPermissionsFor(String kind) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if ("video".equals(kind)) return new String[]{android.Manifest.permission.READ_MEDIA_VIDEO};
            if ("audio".equals(kind)) return new String[]{android.Manifest.permission.READ_MEDIA_AUDIO};
            if ("all".equals(kind) || kind == null) return new String[]{
                    android.Manifest.permission.READ_MEDIA_IMAGES,
                    android.Manifest.permission.READ_MEDIA_VIDEO,
                    android.Manifest.permission.READ_MEDIA_AUDIO};
            return new String[]{android.Manifest.permission.READ_MEDIA_IMAGES};
        }
        return new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    /** Casts a MediaStore item by id — the gallery-grid equivalent of handlePicked(),
     *  but without ever starting a picker Activity. */
    void castMediaStoreItem(String id, String kind) {
        if (id == null) return;
        Uri collection;
        if ("video".equals(kind)) collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        else if ("audio".equals(kind)) collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        else collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        Uri itemUri = Uri.withAppendedPath(collection, id);
        handlePicked(itemUri, kind);
    }

    void handlePicked(Uri uri, String kind) {
        if (uri == null) return;
        if (host == null || host.length()==0) { toast("TV se connect nahi hai"); return; }
        try {
            act.getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {}

        String mimeType = act.getContentResolver().getType(uri);
        if (mimeType == null) mimeType = "application/octet-stream";
        final String mime = mimeType;
        final String title = displayName(uri);

        final String url;
        try {
            url = server.publish(uri, mime, title);
        } catch (Exception e) {
            toast("Media publish fail ho gaya: " + e.getMessage());
            return;
        }

        final boolean photo = mime.startsWith("image/");
        if (client != null) client.close();

        client = new CastClient(host, new CastClient.Listener() {
            public void onStatus(final String state, final String title) {
                js("onCastStatus(" + q(state) + "," + q(title) + ")");
            }
            public void onError(final String message) {
                js("onCastError(" + q(message) + ")");
            }
            public void onMediaStatus(final String state, final long pos, final long dur) {
                js("onCastMediaStatus(" + q(state) + "," + pos + "," + dur + ")");
            }
        });
        toast("TV se Cast connect ho raha hai...");
        client.start();
        // Give the Cast receiver a moment to establish; CastClient also handles the launch.
        new Thread(new Runnable() {
            public void run() {
                try { Thread.sleep(1200); } catch(Exception ignored) {}
                if (client != null) client.load(url,mime,title,photo);
            }
        }, "cast-load").start();
    }

    void play() { if(client!=null) client.play(); }
    void pause() { if(client!=null) client.pause(); }
    void stop() {
        if(client!=null) { client.stop(); client.close(); client=null; }
        server.stop();
        js("onCastStatus(\"stopped\",\"\")");
    }
    void seek(long ms) { if(client!=null) client.seek(ms); }

    void dispose() {
        if(client!=null) { client.close(); client=null; }
        server.stop();
    }

    private void js(final String s) {
        ui.post(new Runnable() {
            public void run() {
                try{ web.evaluateJavascript("(function(){var t=window.__tv;if(t){t."+s+";}})()",null); }catch(Exception ignored){}
            }
        });
    }
    private static String q(String s) {
        return org.json.JSONObject.quote(s==null?"":s);
    }
    private String displayName(Uri u) {
        Cursor c=null;
        try {
            c=act.getContentResolver().query(u,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null);
            if(c!=null && c.moveToFirst()) return c.getString(0);
        } catch(Exception ignored){} finally{if(c!=null)c.close();}
        return "Cast media";
    }
    private void toast(final String m) {
        ui.post(new Runnable() {
            public void run() { android.widget.Toast.makeText(act,m,android.widget.Toast.LENGTH_SHORT).show(); }
        });
    }
}
