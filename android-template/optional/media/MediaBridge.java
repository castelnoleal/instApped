package com.castel.generatedapp;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.util.Size;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Music support, exposed to the page as window.CastelMedia.
 * Only compiled into apps whose HTML uses CastelMedia (or that set the mediaPlayer option);
 * MainActivity loads it by reflection.
 *
 *   available()            -> true (synchronous)
 *   hasPermission()        -> boolean: may the app read the device's music library?
 *   scan(cb)               -> asks for permission if needed, then window[cb](json):
 *                             {tracks:[{id,title,artist,album,albumArtist,track,year,duration,size,mime,added,name,folder,url,artUrl}]}
 *                             or {error:"permission", permanent:boolean} / {error:"..."}.
 *                             url/artUrl are served by the app (HTTP range requests work, so seeking works).
 *   openSettings()         -> opens this app's Android settings page (to grant a permanently denied permission)
 *   update(json)           -> sync the notification / lock screen / headset controls:
 *                             {state:"playing"|"paused"|"none", title, artist, album, duration(ms), position(ms),
 *                              rate, mediaId (device track id, for artwork), art (small data: URL), hasPrev, hasNext}
 *   stop()                 -> remove the notification
 *
 * Controls pressed in the notification, on the lock screen or on a headset call
 * window.CastelMediaEvents(action, value) with action play|pause|toggle|next|prev|seek|stop.
 * While music plays, the page keeps running when the app is in the background.
 */
public class MediaBridge implements MainActivity.MediaHooks {
    static final String TAG = "CastelAppFactory";
    private static final Pattern PATH = Pattern.compile("^/__castel_media/([A-Za-z0-9]+)/(audio|art)/(\\d+)$");
    private final MainActivity activity;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final String token;
    private volatile boolean playing;
    private volatile boolean sessionActive;
    private final Map<Long, String> mimeById = new HashMap<>();

    public MediaBridge(MainActivity activity) {
        this.activity = activity;
        byte[] b = new byte[12];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        token = sb.toString();
        MediaPlaybackService.bridge = this;
    }

    // ---------------------------------------------------------------- JavaScript API

    @JavascriptInterface public boolean available() { return true; }

    @JavascriptInterface public boolean hasPermission() {
        return ContextCompat.checkSelfPermission(activity, readPermission()) == PackageManager.PERMISSION_GRANTED;
    }

    @JavascriptInterface public void scan(String callback) {
        if (!validCallback(callback)) return;
        activity.runOnUiThread(() -> {
            if (!activity.isOwnPage()) { reply(callback, "{\"error\":\"Music access is only available on this app's own pages.\"}"); return; }
            String perm = readPermission();
            activity.requestAndroidPermissions(new String[]{perm}, granted -> {
                if (!granted.contains(perm)) {
                    boolean permanent = !activity.shouldShowRequestPermissionRationale(perm) && activity.askedBefore(perm);
                    activity.markAsked(perm);
                    reply(callback, "{\"error\":\"permission\",\"permanent\":" + permanent + "}");
                    return;
                }
                activity.markAsked(perm);
                executor.execute(() -> reply(callback, query()));
            });
        });
    }

    @JavascriptInterface public void openSettings() {
        activity.runOnUiThread(() -> {
            try {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.getPackageName(), null));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(i);
            } catch (Exception e) { Log.w(TAG, "MEDIA_SETTINGS_FAILED", e); }
        });
    }

    @JavascriptInterface public void update(String json) {
        try {
            JSONObject o = new JSONObject(json == null ? "{}" : json);
            MediaPlaybackService.State s = new MediaPlaybackService.State();
            s.state = o.optString("state", "none");
            s.title = o.optString("title", "");
            s.artist = o.optString("artist", "");
            s.album = o.optString("album", "");
            s.duration = Math.max(0, o.optLong("duration", 0));
            s.position = Math.max(0, o.optLong("position", 0));
            s.rate = (float) o.optDouble("rate", 1.0);
            s.mediaId = o.optLong("mediaId", -1);
            s.art = o.optString("art", "");
            s.hasPrev = o.optBoolean("hasPrev", true);
            s.hasNext = o.optBoolean("hasNext", true);
            boolean nowPlaying = "playing".equals(s.state);
            boolean wasPlaying = playing;
            playing = nowPlaying;
            if (nowPlaying) sessionActive = true;
            if ("none".equals(s.state)) sessionActive = false;
            activity.runOnUiThread(() -> {
                MediaPlaybackService.push(activity, s);
                if (wasPlaying != nowPlaying) activity.onMediaPlayingChanged(nowPlaying);
            });
        } catch (Exception e) {
            Log.w(TAG, "MEDIA_UPDATE_FAILED", e);
        }
    }

    @JavascriptInterface public void stop() {
        playing = false;
        sessionActive = false;
        activity.runOnUiThread(() -> { MediaPlaybackService.shutdown(activity); activity.onMediaPlayingChanged(false); });
    }

    // ---------------------------------------------------------------- Native → page

    /** Called by the media service (notification, lock screen, headset buttons). */
    void dispatch(String action, long value) {
        String js = "window.CastelMediaEvents&&window.CastelMediaEvents(" + JSONObject.quote(action) + "," + value + ")";
        activity.runOnUiThread(() -> activity.runMediaScript(js));
    }

    /** The notification was dismissed or the service stopped. */
    void sessionEnded() {
        sessionActive = false;
        playing = false;
        activity.runOnUiThread(() -> activity.onMediaPlayingChanged(false));
    }

    // ---------------------------------------------------------------- MainActivity hooks

    @Override public boolean keepRunning() { return playing || sessionActive; }

    @Override public void close() {
        MediaPlaybackService.bridge = null;
        MediaPlaybackService.shutdown(activity);
        executor.shutdownNow();
    }

    @Override public WebResourceResponse intercept(WebResourceRequest request) {
        Uri url = request.getUrl();
        if (url == null || !"appassets.androidplatform.net".equalsIgnoreCase(url.getHost())) return null;
        String path = url.getPath();
        if (path == null || !path.startsWith("/__castel_media/")) return null;
        Matcher m = PATH.matcher(path);
        if (!m.matches() || !token.equals(m.group(1))) return error(404, "Not Found");
        if (!hasPermission()) return error(403, "Forbidden");
        long id;
        try { id = Long.parseLong(m.group(3)); } catch (NumberFormatException e) { return error(404, "Not Found"); }
        Uri item = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
        if ("art".equals(m.group(2))) return art(item, id);
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            WebResourceResponse r = new WebResourceResponse("text/plain", null, 204, "No Content", cors(new HashMap<>()), new ByteArrayInputStream(new byte[0]));
            return r;
        }
        return audio(item, id, request.getRequestHeaders());
    }

    // ---------------------------------------------------------------- MediaStore

    private static String readPermission() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private String query() {
        ContentResolver cr = activity.getContentResolver();
        Uri collection = Build.VERSION.SDK_INT >= 29
                ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String folderCol = Build.VERSION.SDK_INT >= 29 ? MediaStore.Audio.Media.RELATIVE_PATH : MediaStore.Audio.Media.DATA;
        String albumArtistCol = "album_artist";
        String[] projection = {
                MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.YEAR, MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.DISPLAY_NAME,
                folderCol, albumArtistCol
        };
        // Music, plus any audio in a Music/Download folder; skip ringtones, alarms and very short clips.
        String selection = "(" + MediaStore.Audio.Media.IS_MUSIC + " != 0 OR " + folderCol + " LIKE '%Music%' OR " + folderCol + " LIKE '%Download%')"
                + " AND " + MediaStore.Audio.Media.IS_RINGTONE + " = 0 AND " + MediaStore.Audio.Media.IS_ALARM + " = 0"
                + " AND " + MediaStore.Audio.Media.IS_NOTIFICATION + " = 0"
                + " AND (" + MediaStore.Audio.Media.DURATION + " IS NULL OR " + MediaStore.Audio.Media.DURATION + " >= 5000)";
        JSONArray out = new JSONArray();
        Cursor c = null;
        try {
            try {
                c = cr.query(collection, projection, selection, null, MediaStore.Audio.Media.DATE_ADDED + " DESC");
            } catch (IllegalArgumentException e) {
                // Very old devices without album_artist.
                projection[projection.length - 1] = MediaStore.Audio.Media.ARTIST;
                c = cr.query(collection, projection, selection, null, MediaStore.Audio.Media.DATE_ADDED + " DESC");
            }
            if (c == null) return "{\"tracks\":[]}";
            String base = "https://appassets.androidplatform.net/__castel_media/" + token + "/";
            synchronized (mimeById) { mimeById.clear(); }
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String artist = str(c, 2);
                if ("<unknown>".equals(artist)) artist = "";
                String album = str(c, 3);
                String folder = str(c, 12);
                if (Build.VERSION.SDK_INT < 29 && folder.contains("/")) {
                    String parent = folder.substring(0, folder.lastIndexOf('/'));
                    folder = parent.substring(parent.lastIndexOf('/') + 1);
                } else {
                    folder = folder.replaceAll("/+$", "");
                    if (folder.contains("/")) folder = folder.substring(folder.lastIndexOf('/') + 1);
                }
                if (album.equals(folder) && album.matches("(?i)music|download|downloads|0")) album = "";
                String albumArtist = str(c, 13);
                if ("<unknown>".equals(albumArtist) || albumArtist.equals(artist)) albumArtist = "";
                String mime = str(c, 9);
                synchronized (mimeById) { mimeById.put(id, mime); }
                int track = c.isNull(6) ? 0 : c.getInt(6) % 1000;
                JSONObject t = new JSONObject();
                t.put("id", id);
                t.put("title", str(c, 1));
                t.put("artist", artist);
                t.put("album", album);
                t.put("albumArtist", albumArtist);
                t.put("track", track);
                t.put("year", c.isNull(7) ? "" : String.valueOf(c.getInt(7)));
                t.put("duration", c.isNull(5) ? 0 : c.getLong(5));
                t.put("size", c.isNull(8) ? 0 : c.getLong(8));
                t.put("mime", mime);
                t.put("added", c.isNull(10) ? 0 : c.getLong(10) * 1000L);
                t.put("name", str(c, 11));
                t.put("folder", folder);
                t.put("url", base + "audio/" + id);
                t.put("artUrl", base + "art/" + id);
                out.put(t);
            }
            return new JSONObject().put("tracks", out).toString();
        } catch (Exception e) {
            Log.w(TAG, "MEDIA_SCAN_FAILED", e);
            return "{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}";
        } finally {
            if (c != null) c.close();
        }
    }

    private static String str(Cursor c, int i) {
        String s = c.isNull(i) ? "" : c.getString(i);
        return s == null ? "" : s.trim();
    }

    // ---------------------------------------------------------------- Serving files

    private WebResourceResponse audio(Uri item, long id, Map<String, String> headers) {
        try {
            android.content.res.AssetFileDescriptor afd = activity.getContentResolver().openAssetFileDescriptor(item, "r");
            if (afd == null) return error(404, "Not Found");
            long length = afd.getLength();
            if (length < 0) length = afd.getParcelFileDescriptor().getStatSize();
            String mime;
            synchronized (mimeById) { mime = mimeById.get(id); }
            if (mime == null || mime.isEmpty()) mime = activity.getContentResolver().getType(item);
            if (mime == null || mime.isEmpty()) mime = "audio/mpeg";
            long start = 0, end = length - 1;
            boolean partial = false;
            String range = null;
            if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) if ("range".equalsIgnoreCase(h.getKey())) range = h.getValue();
            if (range != null && length > 0) {
                Matcher r = Pattern.compile("bytes=(\\d*)-(\\d*)").matcher(range.trim());
                if (r.find()) {
                    String a = r.group(1), b = r.group(2);
                    if (a.isEmpty() && !b.isEmpty()) { start = Math.max(0, length - Long.parseLong(b)); }
                    else {
                        start = a.isEmpty() ? 0 : Long.parseLong(a);
                        if (!b.isEmpty()) end = Math.min(length - 1, Long.parseLong(b));
                    }
                    if (start >= length || start > end) {
                        afd.close();
                        Map<String, String> h = cors(new HashMap<>());
                        h.put("Content-Range", "bytes */" + length);
                        return new WebResourceResponse(mime, null, 416, "Range Not Satisfiable", h, new ByteArrayInputStream(new byte[0]));
                    }
                    partial = true;
                }
            }
            FileInputStream in = afd.createInputStream();
            long skip = start;
            while (skip > 0) { long n = in.skip(skip); if (n <= 0) break; skip -= n; }
            long count = end - start + 1;
            InputStream body = new Limited(in, count, afd);
            Map<String, String> h = cors(new HashMap<>());
            h.put("Accept-Ranges", "bytes");
            h.put("Content-Length", String.valueOf(count));
            h.put("Cache-Control", "no-store");
            if (partial) h.put("Content-Range", "bytes " + start + "-" + end + "/" + length);
            return new WebResourceResponse(mime, null, partial ? 206 : 200, partial ? "Partial Content" : "OK", h, body);
        } catch (Exception e) {
            Log.w(TAG, "MEDIA_STREAM_FAILED", e);
            return error(404, "Not Found");
        }
    }

    private WebResourceResponse art(Uri item, long id) {
        try {
            Bitmap bmp = loadArt(activity, item, 512);
            if (bmp == null) return error(404, "Not Found");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 88, bos);
            Map<String, String> h = cors(new HashMap<>());
            h.put("Cache-Control", "max-age=86400");
            return new WebResourceResponse("image/jpeg", null, 200, "OK", h, new ByteArrayInputStream(bos.toByteArray()));
        } catch (Exception e) {
            return error(404, "Not Found");
        }
    }

    /** Embedded artwork for a track, or its album's artwork. Null when there is none. */
    static Bitmap loadArt(android.content.Context ctx, Uri item, int size) {
        try {
            if (Build.VERSION.SDK_INT >= 29) return ctx.getContentResolver().loadThumbnail(item, new Size(size, size), null);
            android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
            try {
                mmr.setDataSource(ctx, item);
                byte[] pic = mmr.getEmbeddedPicture();
                if (pic == null) return null;
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
                int sample = 1;
                while (o.outWidth / (sample * 2) >= size && o.outHeight / (sample * 2) >= size) sample *= 2;
                o = new android.graphics.BitmapFactory.Options();
                o.inSampleSize = sample;
                return android.graphics.BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
            } finally {
                try { mmr.release(); } catch (Exception ignored) { }
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, String> cors(Map<String, String> h) {
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Access-Control-Allow-Headers", "Range");
        h.put("Access-Control-Expose-Headers", "Content-Range, Content-Length, Accept-Ranges");
        return h;
    }

    private static WebResourceResponse error(int code, String reason) {
        return new WebResourceResponse("text/plain", "utf-8", code, reason, cors(new HashMap<>()), new ByteArrayInputStream(new byte[0]));
    }

    private static boolean validCallback(String cb) { return cb != null && cb.matches("[A-Za-z_$][A-Za-z0-9_$.]*"); }

    private void reply(String callback, String json) {
        String js = "window." + callback + "&&window." + callback + "(" + JSONObject.quote(json) + ")";
        activity.runOnUiThread(() -> activity.runMediaScript(js));
    }

    /** Reads at most `left` bytes, then closes the file. */
    private static final class Limited extends FilterInputStream {
        private long left;
        private final java.io.Closeable owner;
        Limited(InputStream in, long left, java.io.Closeable owner) { super(in); this.left = left; this.owner = owner; }
        @Override public int read() throws IOException {
            if (left <= 0) return -1;
            int b = super.read();
            if (b >= 0) left--;
            return b;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = super.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }
        @Override public int available() throws IOException { return (int) Math.min(super.available(), left); }
        @Override public void close() throws IOException {
            try { super.close(); } finally { try { owner.close(); } catch (Exception ignored) { } }
        }
    }
}
