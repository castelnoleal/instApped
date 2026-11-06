package com.castel.generatedapp;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Local library folders for the page: window.CastelFiles.
 *
 * Only compiled into apps whose page uses window.CastelFiles (or the localFiles option); MainActivity loads it by
 * reflection. The page gets a real folder on the phone, like desktop apps that keep a "pics" folder next to them:
 *  - by default the app's own folder in shared storage (Android/data/PACKAGE/files), reachable over USB and by file
 *    managers, with no permission needed;
 *  - or any folder the person picks (Storage Access Framework), e.g. Internal storage/KodeX, so files they paste
 *    there with any file manager are used straight away.
 * Files are served to the page (and to pages it frames) at https://appassets.androidplatform.net/castel-files/PATH,
 * so <img src> works without copying anything into the WebView. Downloads run natively (no CORS, no page memory).
 */
public class LocalFiles implements MainActivity.FilesHooks {
    private static final String TAG = "CastelAppFactory";
    static final int PICK_FOLDER = 2101, PICK_IMPORT_FOLDER = 2102, PICK_ZIP = 2103;
    private static final String PREFS = "instapped.files", KEY_TREE = "tree", KEY_LABEL = "label";
    private static final long MAX_TEXT = 4L * 1024 * 1024, MAX_FILE = 60L * 1024 * 1024;

    private final MainActivity activity;
    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private final ExecutorService jobs = Executors.newSingleThreadExecutor();
    private final Map<String, AtomicBoolean> running = new ConcurrentHashMap<>();
    private volatile Store store;
    private String pendingCallback, pendingDest;

    public LocalFiles(MainActivity activity) {
        this.activity = activity;
        SharedPreferences p = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String tree = p.getString(KEY_TREE, null);
        Store s = null;
        if (tree != null) {
            try {
                Uri u = Uri.parse(tree);
                if (hasPermission(u)) s = new TreeStore(activity.getContentResolver(), u, p.getString(KEY_LABEL, "Chosen folder"));
            } catch (Exception e) { Log.w(TAG, "LOCAL_FILES_TREE_LOST", e); }
        }
        store = s != null ? s : appStore();
    }

    private Store appStore() {
        File root = activity.getExternalFilesDir(null);
        if (root == null) root = new File(activity.getFilesDir(), "library");
        root.mkdirs();
        return new FileStore(root, "Android/data/" + activity.getPackageName() + "/files");
    }

    private boolean hasPermission(Uri tree) {
        for (android.content.UriPermission up : activity.getContentResolver().getPersistedUriPermissions())
            if (up.getUri().equals(tree) && up.isReadPermission() && up.isWritePermission()) return true;
        return false;
    }

    // ------------------------------------------------------------------ paths
    /** "a/b/c.jpg" with no leading slash, no "..", no empty parts. Null when unsafe. */
    static String clean(String rel) {
        if (rel == null) return null;
        String r = rel.replace('\\', '/').trim();
        while (r.startsWith("/")) r = r.substring(1);
        while (r.endsWith("/")) r = r.substring(0, r.length() - 1);
        if (r.isEmpty()) return "";
        for (String part : r.split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.indexOf('\0') >= 0) return null;
        }
        return r;
    }

    // ------------------------------------------------------------------ serving
    @Override
    public WebResourceResponse serve(String path) {
        try {
            String rel = clean(URLDecoder.decode(path == null ? "" : path, "UTF-8"));
            if (rel == null || rel.isEmpty()) return notFound();
            InputStream in = store.open(rel);
            if (in == null) return notFound();
            WebResourceResponse r = new WebResourceResponse(mime(rel), null, in);
            Map<String, String> h = new HashMap<>();
            h.put("Access-Control-Allow-Origin", "*");
            h.put("Cache-Control", "no-cache");
            r.setResponseHeaders(h);
            return r;
        } catch (Exception e) {
            return notFound();
        }
    }

    private static WebResourceResponse notFound() {
        WebResourceResponse r = new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
        r.setStatusCodeAndReasonPhrase(404, "Not Found");
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        r.setResponseHeaders(h);
        return r;
    }

    static String mime(String p) {
        String s = p.toLowerCase(Locale.ROOT);
        if (s.endsWith(".jpg") || s.endsWith(".jpeg")) return "image/jpeg";
        if (s.endsWith(".png")) return "image/png";
        if (s.endsWith(".webp")) return "image/webp";
        if (s.endsWith(".gif")) return "image/gif";
        if (s.endsWith(".json")) return "application/json";
        if (s.endsWith(".txt") || s.endsWith(".md") || s.endsWith(".conf") || s.endsWith(".ydk")) return "text/plain";
        if (s.endsWith(".mp3")) return "audio/mpeg";
        if (s.endsWith(".ogg")) return "audio/ogg";
        if (s.endsWith(".wav")) return "audio/wav";
        if (s.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }

    // ------------------------------------------------------------------ the page's API
    /** {"kind":"app"|"folder","root":"…","label":"…"} */
    @JavascriptInterface
    public String info() {
        try {
            JSONObject o = new JSONObject();
            o.put("kind", store instanceof TreeStore ? "folder" : "app");
            o.put("root", store.describe());
            o.put("label", store instanceof TreeStore ? ((TreeStore) store).label : "App folder");
            o.put("base", "https://appassets.androidplatform.net/castel-files/");
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    /** Creates folders, comma-separated ("KodeX/pics,KodeX/rulings"). True when all exist afterwards. */
    @JavascriptInterface
    public boolean ensure(String dirs) {
        if (dirs == null) return false;
        boolean ok = true;
        for (String d : dirs.split(",")) {
            String rel = clean(d);
            if (rel == null || rel.isEmpty()) { ok = false; continue; }
            try { ok &= store.mkdirs(rel); } catch (Exception e) { ok = false; }
        }
        return ok;
    }

    /** File names (not folders) directly inside a folder, as a JSON array. */
    @JavascriptInterface
    public String list(String dir) {
        String rel = clean(dir);
        JSONArray a = new JSONArray();
        if (rel == null) return a.toString();
        try { for (String n : store.listFiles(rel)) a.put(n); } catch (Exception e) { Log.w(TAG, "LOCAL_FILES_LIST", e); }
        return a.toString();
    }

    @JavascriptInterface
    public boolean exists(String path) {
        String rel = clean(path);
        try { return rel != null && !rel.isEmpty() && store.exists(rel); } catch (Exception e) { return false; }
    }

    /** The address the page loads a file from (it doesn't check that the file exists). */
    @JavascriptInterface
    public String url(String path) {
        String rel = clean(path);
        if (rel == null || rel.isEmpty()) return "";
        StringBuilder b = new StringBuilder("https://appassets.androidplatform.net/castel-files/");
        String[] parts = rel.split("/");
        for (int i = 0; i < parts.length; i++) { if (i > 0) b.append('/'); b.append(Uri.encode(parts[i])); }
        return b.toString();
    }

    @JavascriptInterface
    public String readText(String path) {
        String rel = clean(path);
        if (rel == null || rel.isEmpty()) return "";
        try (InputStream in = store.open(rel)) {
            if (in == null) return "";
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384]; int n; long total = 0;
            while ((n = in.read(buf)) != -1) { total += n; if (total > MAX_TEXT) return ""; out.write(buf, 0, n); }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) { return ""; }
    }

    @JavascriptInterface
    public boolean writeText(String path, String text) {
        String rel = clean(path);
        if (rel == null || rel.isEmpty() || text == null || text.length() > MAX_TEXT) return false;
        return write(rel, text.getBytes(StandardCharsets.UTF_8));
    }

    /** Writes a file from base64 (with or without a "data:…;base64," prefix). */
    @JavascriptInterface
    public boolean writeBase64(String path, String b64) {
        String rel = clean(path);
        if (rel == null || rel.isEmpty() || b64 == null) return false;
        try {
            int comma = b64.startsWith("data:") ? b64.indexOf(',') : -1;
            byte[] bytes = android.util.Base64.decode(comma >= 0 ? b64.substring(comma + 1) : b64, android.util.Base64.DEFAULT);
            if (bytes.length > MAX_FILE) return false;
            return write(rel, bytes);
        } catch (Exception e) { return false; }
    }

    private boolean write(String rel, byte[] bytes) {
        try (OutputStream out = store.create(rel)) {
            if (out == null) return false;
            out.write(bytes);
            return true;
        } catch (Exception e) { Log.w(TAG, "LOCAL_FILES_WRITE", e); return false; }
    }

    /** Deletes a file, or a folder with everything in it. */
    @JavascriptInterface
    public boolean remove(String path) {
        String rel = clean(path);
        if (rel == null || rel.isEmpty()) return false;
        try { return store.delete(rel); } catch (Exception e) { return false; }
    }

    /** {"files":n,"bytes":n} for the files directly inside a folder. */
    @JavascriptInterface
    public String usage(String dir) {
        String rel = clean(dir);
        long[] u = {0, 0};
        if (rel != null) { try { u = store.usage(rel); } catch (Exception ignored) { } }
        return "{\"files\":" + u[0] + ",\"bytes\":" + u[1] + "}";
    }

    /**
     * Downloads many files in the background: json = [{"u":"https://…","p":"KodeX/pics/123.jpg"}, …].
     * Files that already exist are skipped, so a stopped download carries on where it left off.
     * Progress: window.__castelFiles(job, done, total, failed, finished) a few times a second.
     * perSecond limits the request rate (servers often throttle bursts); 0 means no limit.
     */
    @JavascriptInterface
    public void download(String job, String json, int perSecond) {
        if (job == null || !job.matches("[A-Za-z0-9_-]{1,40}")) return;
        final List<String[]> items = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String u = o.optString("u"), rel = clean(o.optString("p"));
                if (rel != null && !rel.isEmpty() && u.matches("(?i)https?://.+")) items.add(new String[]{u, rel});
            }
        } catch (Exception e) { progress(job, 0, 0, 0, true); return; }
        final AtomicBoolean on = new AtomicBoolean(true);
        AtomicBoolean old = running.put(job, on);
        if (old != null) old.set(false);
        final int total = items.size(), gap = perSecond > 0 ? Math.max(1, 1000 / perSecond) : 0;
        jobs.execute(() -> {
            AtomicInteger done = new AtomicInteger(), failed = new AtomicInteger();
            long[] last = {0}, next = {System.currentTimeMillis()};
            List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
            for (String[] it : items) {
                if (!on.get()) break;
                try {
                    if (store.exists(it[1]) && store.size(it[1]) > 0) { done.incrementAndGet(); continue; }
                } catch (Exception ignored) { }
                if (gap > 0) {
                    long wait = next[0] - System.currentTimeMillis();
                    if (wait > 0) { try { Thread.sleep(wait); } catch (InterruptedException e) { break; } }
                    next[0] = Math.max(next[0], System.currentTimeMillis()) + gap;
                }
                fs.add(io.submit(() -> {
                    if (!on.get()) return;
                    try { fetchTo(it[0], it[1]); } catch (Exception e) { failed.incrementAndGet(); }
                    done.incrementAndGet();
                }));
                long now = System.currentTimeMillis();
                if (now - last[0] > 300) { last[0] = now; progress(job, done.get(), total, failed.get(), false); }
                // keep the queue short so "stop" is quick
                while (fs.size() > 12) { try { fs.remove(0).get(); } catch (Exception ignored) { } }
            }
            for (java.util.concurrent.Future<?> f : fs) { try { f.get(60, TimeUnit.SECONDS); } catch (Exception ignored) { } }
            running.remove(job, on);
            progress(job, done.get(), total, failed.get(), true);
        });
    }

    @JavascriptInterface
    public void cancel(String job) {
        AtomicBoolean on = job == null ? null : running.get(job);
        if (on != null) on.set(false);
    }

    private void fetchTo(String url, String rel) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(30000); c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "instApped-LocalFiles/1.0 (" + activity.getPackageName() + ")");
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
            ByteArrayOutputStream mem = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[16384]; int n; long total = 0;
                while ((n = in.read(buf)) != -1) { total += n; if (total > MAX_FILE) throw new IllegalStateException("too large"); mem.write(buf, 0, n); }
            }
            if (mem.size() == 0) throw new IllegalStateException("empty");
            if (!write(rel, mem.toByteArray())) throw new IllegalStateException("write failed");
        } finally { c.disconnect(); }
    }

    private void progress(String job, int done, int total, int failed, boolean finished) {
        activity.runPageScript("window.__castelFiles&&window.__castelFiles(" + JSONObject.quote(job) + "," + done + "," + total + "," + failed + "," + finished + ")");
    }

    private void callback(String cb, boolean ok, String detail) {
        if (cb == null || !cb.matches("[A-Za-z_$][A-Za-z0-9_$.]*")) return;
        activity.runPageScript(cb + "(" + ok + "," + JSONObject.quote(detail == null ? "" : detail) + ")");
    }

    /** Lets the person pick a folder on the phone (e.g. Internal storage/KodeX) to keep the library in. */
    @JavascriptInterface
    public void chooseFolder(String callback) {
        activity.runOnUiThread(() -> {
            try {
                pendingCallback = callback; pendingDest = null;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                activity.startActivityForResult(i, PICK_FOLDER);
            } catch (Exception e) { callback(callback, false, "No folder picker on this device"); }
        });
    }

    /** Back to the app's own folder. */
    @JavascriptInterface
    public void useAppFolder() {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_TREE).remove(KEY_LABEL).apply();
        store = appStore();
    }

    /** Copies everything in a folder the person picks into `dest` (subfolders kept). Callback(ok, "count"). */
    @JavascriptInterface
    public void importFolder(String dest, String callback) {
        String rel = clean(dest);
        if (rel == null) { callback(callback, false, "Bad folder"); return; }
        activity.runOnUiThread(() -> {
            try {
                pendingCallback = callback; pendingDest = rel;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                activity.startActivityForResult(i, PICK_IMPORT_FOLDER);
            } catch (Exception e) { callback(callback, false, "No folder picker on this device"); }
        });
    }

    /** Unpacks a ZIP the person picks into `dest`. Callback(ok, "count"). */
    @JavascriptInterface
    public void importZip(String dest, String callback) {
        String rel = clean(dest);
        if (rel == null) { callback(callback, false, "Bad folder"); return; }
        activity.runOnUiThread(() -> {
            try {
                pendingCallback = callback; pendingDest = rel;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
                activity.startActivityForResult(i, PICK_ZIP);
            } catch (Exception e) { callback(callback, false, "No file picker on this device"); }
        });
    }

    @Override
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != PICK_FOLDER && requestCode != PICK_IMPORT_FOLDER && requestCode != PICK_ZIP) return false;
        final String cb = pendingCallback, dest = pendingDest;
        pendingCallback = null; pendingDest = null;
        final Uri uri = data == null ? null : data.getData();
        if (resultCode != Activity.RESULT_OK || uri == null) { callback(cb, false, "cancelled"); return true; }
        ContentResolver cr = activity.getContentResolver();
        if (requestCode == PICK_FOLDER) {
            try {
                cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                String label = treeLabel(uri);
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TREE, uri.toString()).putString(KEY_LABEL, label).apply();
                store = new TreeStore(cr, uri, label);
                callback(cb, true, label);
            } catch (Exception e) { Log.w(TAG, "LOCAL_FILES_PICK", e); callback(cb, false, "That folder can't be used"); }
            return true;
        }
        jobs.execute(() -> {
            try {
                int n = requestCode == PICK_ZIP ? unzip(cr, uri, dest) : copyTree(cr, uri, dest);
                callback(cb, true, String.valueOf(n));
            } catch (Exception e) { Log.w(TAG, "LOCAL_FILES_IMPORT", e); callback(cb, false, e.getMessage() == null ? "Import failed" : e.getMessage()); }
        });
        return true;
    }

    private static String treeLabel(Uri tree) {
        try {
            String id = DocumentsContract.getTreeDocumentId(tree);
            int c = id.indexOf(':');
            String vol = c > 0 ? id.substring(0, c) : "", path = c >= 0 ? id.substring(c + 1) : id;
            return ("primary".equals(vol) ? "Internal storage" : vol) + (path.isEmpty() ? "" : "/" + path);
        } catch (Exception e) { return "Chosen folder"; }
    }

    private int unzip(ContentResolver cr, Uri zip, String dest) throws Exception {
        int n = 0;
        try (InputStream raw = cr.openInputStream(zip); ZipInputStream z = new ZipInputStream(raw)) {
            ZipEntry e;
            byte[] buf = new byte[16384];
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = clean(e.getName());
                if (name == null || name.isEmpty() || name.startsWith("__MACOSX") || name.endsWith(".DS_Store")) continue;
                String rel = dest.isEmpty() ? name : dest + "/" + name;
                try (OutputStream out = store.create(rel)) {
                    if (out == null) continue;
                    int r; long total = 0;
                    while ((r = z.read(buf)) != -1) { total += r; if (total > MAX_FILE) throw new IllegalStateException("A file in the ZIP is too large"); out.write(buf, 0, r); }
                }
                n++;
            }
        }
        return n;
    }

    private int copyTree(ContentResolver cr, Uri tree, String dest) throws Exception {
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        return copyChildren(cr, tree, rootId, dest, 0);
    }

    private int copyChildren(ContentResolver cr, Uri tree, String docId, String dest, int depth) throws Exception {
        if (depth > 4) return 0;
        int n = 0;
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        try (Cursor c = cr.query(kids, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (c == null) return 0;
            byte[] buf = new byte[16384];
            while (c.moveToNext()) {
                String id = c.getString(0), name = c.getString(1), type = c.getString(2);
                String safe = clean(name);
                if (safe == null || safe.isEmpty() || safe.contains("/")) continue;
                String rel = dest.isEmpty() ? safe : dest + "/" + safe;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(type)) { n += copyChildren(cr, tree, id, rel, depth + 1); continue; }
                try (InputStream in = cr.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id)); OutputStream out = store.create(rel)) {
                    if (in == null || out == null) continue;
                    int r;
                    while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                }
                n++;
            }
        }
        return n;
    }

    @Override
    public void close() {
        for (AtomicBoolean on : running.values()) on.set(false);
        io.shutdownNow();
        jobs.shutdownNow();
    }

    // ------------------------------------------------------------------ storage back ends
    interface Store {
        String describe();
        boolean exists(String rel) throws Exception;
        long size(String rel) throws Exception;
        List<String> listFiles(String rel) throws Exception;
        InputStream open(String rel) throws Exception;
        OutputStream create(String rel) throws Exception;
        boolean mkdirs(String rel) throws Exception;
        boolean delete(String rel) throws Exception;
        long[] usage(String rel) throws Exception;
    }

    /** A plain folder (the app's own folder in shared storage). */
    static final class FileStore implements Store {
        final File root; final String label;
        FileStore(File root, String label) { this.root = root; this.label = label; }
        File f(String rel) { return rel.isEmpty() ? root : new File(root, rel); }
        public String describe() { return label; }
        public boolean exists(String rel) { return f(rel).exists(); }
        public long size(String rel) { return f(rel).length(); }
        public List<String> listFiles(String rel) {
            List<String> out = new ArrayList<>();
            File[] fs = f(rel).listFiles();
            if (fs != null) for (File x : fs) if (x.isFile()) out.add(x.getName());
            return out;
        }
        public InputStream open(String rel) throws Exception { File x = f(rel); return x.isFile() ? new FileInputStream(x) : null; }
        public OutputStream create(String rel) throws Exception {
            File x = f(rel), dir = x.getParentFile();
            if (dir != null) dir.mkdirs();
            final File tmp = new File(dir, "." + x.getName() + ".part");
            final File target = x;
            return new FileOutputStream(tmp) {
                boolean closed;
                @Override public void close() throws java.io.IOException {
                    if (closed) return;
                    closed = true;
                    try { getFD().sync(); } catch (Exception ignored) { }
                    super.close();
                    if (target.exists() && !target.delete()) throw new java.io.IOException("Can't replace " + target.getName());
                    if (!tmp.renameTo(target)) throw new java.io.IOException("Can't save " + target.getName());
                }
            };
        }
        public boolean mkdirs(String rel) { File x = f(rel); return x.isDirectory() || x.mkdirs(); }
        public boolean delete(String rel) { return del(f(rel)); }
        static boolean del(File x) {
            File[] kids = x.isDirectory() ? x.listFiles() : null;
            if (kids != null) for (File k : kids) del(k);
            return !x.exists() || x.delete();
        }
        public long[] usage(String rel) {
            long n = 0, b = 0;
            File[] fs = f(rel).listFiles();
            if (fs != null) for (File x : fs) if (x.isFile()) { n++; b += x.length(); }
            return new long[]{n, b};
        }
    }

    /** A folder the person picked (Storage Access Framework). Folder listings are cached, so lookups stay quick. */
    static final class TreeStore implements Store {
        final ContentResolver cr; final Uri tree; final String label, rootId;
        private final Map<String, Map<String, String[]>> dirs = new ConcurrentHashMap<>();   // dir rel -> name -> {docId, mime, size}
        TreeStore(ContentResolver cr, Uri tree, String label) { this.cr = cr; this.tree = tree; this.label = label; this.rootId = DocumentsContract.getTreeDocumentId(tree); }
        public String describe() { return label; }
        private static String parent(String rel) { int i = rel.lastIndexOf('/'); return i < 0 ? "" : rel.substring(0, i); }
        private static String leaf(String rel) { int i = rel.lastIndexOf('/'); return i < 0 ? rel : rel.substring(i + 1); }
        /** Document id of a folder, or null. */
        private String dirId(String rel) throws Exception {
            if (rel.isEmpty()) return rootId;
            String[] e = children(parent(rel)).get(leaf(rel));
            return e != null && DocumentsContract.Document.MIME_TYPE_DIR.equals(e[1]) ? e[0] : null;
        }
        private Map<String, String[]> children(String rel) throws Exception {
            Map<String, String[]> m = dirs.get(rel);
            if (m != null) return m;
            m = new ConcurrentHashMap<>();
            String id = rel.isEmpty() ? rootId : dirId(rel);
            if (id != null) {
                Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
                try (Cursor c = cr.query(kids, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
                    if (c != null) while (c.moveToNext()) m.put(c.getString(1), new String[]{c.getString(0), c.getString(2), c.isNull(3) ? "0" : String.valueOf(c.getLong(3))});
                }
                dirs.put(rel, m);
            }
            return m;
        }
        private String[] entry(String rel) throws Exception { return children(parent(rel)).get(leaf(rel)); }
        public boolean exists(String rel) throws Exception { return rel.isEmpty() || entry(rel) != null; }
        public long size(String rel) throws Exception { String[] e = entry(rel); return e == null ? 0 : Long.parseLong(e[2]); }
        public List<String> listFiles(String rel) throws Exception {
            dirs.remove(rel);   // a listing is a rescan: pick up files pasted since
            List<String> out = new ArrayList<>();
            for (Map.Entry<String, String[]> e : children(rel).entrySet()) if (!DocumentsContract.Document.MIME_TYPE_DIR.equals(e.getValue()[1])) out.add(e.getKey());
            Collections.sort(out);
            return out;
        }
        public InputStream open(String rel) throws Exception {
            String[] e = entry(rel);
            if (e == null || DocumentsContract.Document.MIME_TYPE_DIR.equals(e[1])) return null;
            return cr.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, e[0]));
        }
        public synchronized boolean mkdirs(String rel) throws Exception {
            if (rel.isEmpty()) return true;
            if (dirId(rel) != null) return true;
            String p = parent(rel);
            if (!mkdirs(p)) return false;
            String pid = dirId(p);
            if (pid == null) return false;
            Uri made = DocumentsContract.createDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, pid), DocumentsContract.Document.MIME_TYPE_DIR, leaf(rel));
            if (made == null) return false;
            children(p).put(leaf(rel), new String[]{DocumentsContract.getDocumentId(made), DocumentsContract.Document.MIME_TYPE_DIR, "0"});
            return true;
        }
        public OutputStream create(String rel) throws Exception {
            String p = parent(rel), name = leaf(rel);
            Uri made;
            synchronized (this) {
                if (!mkdirs(p)) return null;
                String[] old = children(p).get(name);
                if (old != null) {
                    // replace in place (keeps the name; createDocument would make "name (1)")
                    Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, old[0]);
                    final Map<String, String[]> m = children(p);
                    return wrap(cr.openOutputStream(doc, "wt"), m, name, old[0], old[1]);
                }
                made = DocumentsContract.createDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, dirId(p)), mime(name), name);
                if (made == null) return null;
                children(p).put(name, new String[]{DocumentsContract.getDocumentId(made), mime(name), "0"});
            }
            return wrap(cr.openOutputStream(made, "w"), children(p), name, DocumentsContract.getDocumentId(made), mime(name));
        }
        private static OutputStream wrap(OutputStream out, Map<String, String[]> m, String name, String id, String type) {
            if (out == null) return null;
            return new java.io.FilterOutputStream(out) {
                long n;
                @Override public void write(int b) throws java.io.IOException { out.write(b); n++; }
                @Override public void write(byte[] b, int off, int len) throws java.io.IOException { out.write(b, off, len); n += len; }
                @Override public void close() throws java.io.IOException { super.close(); m.put(name, new String[]{id, type, String.valueOf(n)}); }
            };
        }
        public boolean delete(String rel) throws Exception {
            String[] e = entry(rel);
            if (e == null) return true;
            boolean ok = DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, e[0]));
            if (ok) { children(parent(rel)).remove(leaf(rel)); dirs.remove(rel); }
            return ok;
        }
        public long[] usage(String rel) throws Exception {
            long n = 0, b = 0;
            for (String[] e : children(rel).values()) if (!DocumentsContract.Document.MIME_TYPE_DIR.equals(e[1])) { n++; b += Long.parseLong(e[2]); }
            return new long[]{n, b};
        }
    }
}
