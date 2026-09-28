# Cast integration

The second-page **Cast** button now uses the same functional flow as the old Cast Remote project, adapted to this WebView/Android TV Remote v2 project.

## Flow

1. The currently connected TV IP is reused as the Cast target.
2. The Cast button opens Photos / Videos / Audio selection through Android Storage Access Framework.
3. The selected `content://` URI is exposed by the phone through a small local HTTP server with byte-range support.
4. `CastClient` connects to the TV's Google Cast v2 endpoint on TCP 8009 using TLS.
5. It launches the Default Media Receiver (`CC1AD845`) and sends a Cast `LOAD` message.
6. Playback status is returned to the WebView.
7. Play/Pause, Seek and Stop are wired to the Cast media namespace.

The old project's Display tab was only an informational placeholder (“Pick Photos, Videos, or Audio to start casting”), so this integration does not claim to implement screen mirroring.

## Source files

- `app/java/com/example/tvremote/CastClient.java`
- `app/java/com/example/tvremote/CastBridge.java`
- `app/java/com/example/tvremote/CastPickerActivity.java`
- `app/java/com/example/tvremote/MediaHttpServer.java`
- `app/java/com/example/tvremote/TvBridge.java`
- `app/assets/remote.html`

`build_apk.sh` already compiles every Java file in `app/java/com/example/tvremote/*.java`, so these classes are included when the normal build script is run.

Protocol background: Google Cast v2 uses TLS on port 8009 and length-prefixed protobuf `CastMessage` packets; the sender then uses the receiver/media namespaces for launch and media control.

## Gallery browsing (update)

Tapping **Cast → Photos / Videos / Audio** now browses the *whole* device gallery, not a handful of items:

- `TvBridge.listGalleryPage(kind, bucket, offset, limit)` — paginated MediaStore query; the WebView loads 60 items at a time and fetches the next page automatically as you scroll (infinite scroll). The old hard `limit = 120` cap is gone.
- `TvBridge.listGalleryAlbums(kind)` — returns every album/folder (Camera, Screenshots, WhatsApp, Downloads …) with counts. The UI shows them as chips: **All** + one chip per folder.
- Tap any thumbnail to cast it; the grid stays open so you can pick another one.
- If the media permission dialog was just answered, the grid reloads itself when the app regains focus.
- `listGalleryMedia(kind)` is kept as a backwards-compatible wrapper (first page of everything).

## Gallery speed + UI (v2, replaces the paginated grid above)

The paginated/base64 grid was slow: every page decoded ~60 thumbnails on the UI thread, base64-encoded them and pushed one giant JSON string through the JS bridge. It was replaced with the same approach the old native Cast Remote (Compose + Coil) used:

- `TvBridge.listGalleryAlbums(kind)` / `listGalleryItems(kind, bucket)` return **text only** (id, name, count, cover id) — instant even for thousands of items.
- Thumbnails are ordinary `<img>` tags pointing at `https://tvthumb.local/<kind>/<id>`. `TvBridge` installs a `WebViewClient.shouldInterceptRequest` that answers those URLs from `CastBridge.thumbStream()` on the WebView's own worker thread (256px JPEG, 24 MB LRU byte cache). Nothing goes over the network.
- The page only requests thumbnails for cells near the viewport (`IntersectionObserver`), so a 600-photo album opens in ~70 ms and requests ~35 thumbnails.
- UI mirrors the old app: tabs (Photos / Videos / Audio) → **Albums / Folders grid** (cover, name, count, plus an "All" card) → 3-column photo grid, or 2-column cards for videos/audio → mini player (seek / pause / stop) that slides up while casting.
