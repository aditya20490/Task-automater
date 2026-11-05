package com.taskautomator;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** One saved automation: a website + the recorded steps. */
class Project {
    String id = UUID.randomUUID().toString();
    String name = "";
    String url = "";
    long created = System.currentTimeMillis();
    JSONArray steps = new JSONArray();

    JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            o.put("url", url);
            o.put("created", created);
            o.put("steps", steps);
        } catch (JSONException ignored) { }
        return o;
    }

    static Project fromJson(JSONObject o) {
        Project p = new Project();
        p.id = o.optString("id", p.id);
        p.name = o.optString("name", "Untitled");
        p.url = o.optString("url", "");
        p.created = o.optLong("created", p.created);
        JSONArray s = o.optJSONArray("steps");
        p.steps = s != null ? s : new JSONArray();
        return p;
    }
}

/** Saves projects on the phone (survives closing the app). */
class ProjectStore {
    private static final String PREFS = "projects";
    private static final String KEY = "list";
    private final SharedPreferences sp;

    ProjectStore(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    List<Project> all() {
        List<Project> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(sp.getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(Project.fromJson(o));
            }
        } catch (JSONException ignored) { }
        return out;
    }

    Project get(String id) {
        if (id == null) return null;
        for (Project p : all()) if (p.id.equals(id)) return p;
        return null;
    }

    void save(Project p) {
        List<Project> list = all();
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(p.id)) { list.set(i, p); found = true; break; }
        }
        if (!found) list.add(0, p);
        write(list);
    }

    void delete(String id) {
        List<Project> list = all();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) { list.remove(i); break; }
        }
        write(list);
    }

    private void write(List<Project> list) {
        JSONArray arr = new JSONArray();
        for (Project p : list) arr.put(p.toJson());
        sp.edit().putString(KEY, arr.toString()).commit();
    }
}

/** One entry in the Downloads list. */
class DownloadItem {
    String id = UUID.randomUUID().toString();
    String name = "";
    String url = "";
    String mime = "application/octet-stream";
    String uri = "";       // where the file can be opened from
    String path = "";      // real file path (only used on old phones)
    long size = 0;         // total bytes (0 = unknown)
    long done = 0;         // bytes downloaded so far
    String status = "downloading";   // downloading | done | failed
    String error = "";
    long time = System.currentTimeMillis();

    JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            o.put("url", url);
            o.put("mime", mime);
            o.put("uri", uri);
            o.put("path", path);
            o.put("size", size);
            o.put("done", done);
            o.put("status", status);
            o.put("error", error);
            o.put("time", time);
        } catch (JSONException ignored) { }
        return o;
    }

    static DownloadItem fromJson(JSONObject o) {
        DownloadItem d = new DownloadItem();
        d.id = o.optString("id", d.id);
        d.name = o.optString("name", "file");
        d.url = o.optString("url", "");
        d.mime = o.optString("mime", d.mime);
        d.uri = o.optString("uri", "");
        d.path = o.optString("path", "");
        d.size = o.optLong("size", 0);
        d.done = o.optLong("done", 0);
        d.status = o.optString("status", "done");
        d.error = o.optString("error", "");
        d.time = o.optLong("time", d.time);
        return d;
    }
}

/** Remembers every download so the Downloads screen can show them. */
class DownloadStore {
    private static final Object LOCK = new Object();
    private static final String PREFS = "downloads";
    private static final String KEY = "list";

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static List<DownloadItem> all(Context c) {
        synchronized (LOCK) {
            List<DownloadItem> out = new ArrayList<>();
            try {
                JSONArray arr = new JSONArray(sp(c).getString(KEY, "[]"));
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) out.add(DownloadItem.fromJson(o));
                }
            } catch (JSONException ignored) { }
            return out;
        }
    }

    private static void write(Context c, List<DownloadItem> list) {
        JSONArray arr = new JSONArray();
        for (DownloadItem d : list) arr.put(d.toJson());
        sp(c).edit().putString(KEY, arr.toString()).commit();
    }

    static void add(Context c, DownloadItem d) {
        synchronized (LOCK) {
            List<DownloadItem> list = all(c);
            list.add(0, d);
            write(c, list);
        }
    }

    static void update(Context c, DownloadItem d) {
        synchronized (LOCK) {
            List<DownloadItem> list = all(c);
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id.equals(d.id)) { list.set(i, d); break; }
            }
            write(c, list);
        }
    }

    static void remove(Context c, String id) {
        synchronized (LOCK) {
            List<DownloadItem> list = all(c);
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id.equals(id)) { list.remove(i); break; }
            }
            write(c, list);
        }
    }

    /** Clears finished + failed entries from the list (files stay on the phone). */
    static void clearFinished(Context c) {
        synchronized (LOCK) {
            List<DownloadItem> list = all(c);
            List<DownloadItem> keep = new ArrayList<>();
            for (DownloadItem d : list) if ("downloading".equals(d.status)) keep.add(d);
            write(c, keep);
        }
    }
}

/** Writes a downloaded file into the phone's Downloads folder (never overwrites, never fails on a same name). */
class FileSaver {

    static class Target {
        Uri uri;
        File file;       // only on old phones
        String name;
        OutputStream out;
    }

    static Target create(Context c, String mime, String name) throws IOException {
        Target t = new Target();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, mime);
            v.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("Could not create file");
            t.uri = uri;
            t.name = name;
            Cursor cur = c.getContentResolver().query(uri,
                    new String[]{MediaStore.Downloads.DISPLAY_NAME}, null, null, null);
            if (cur != null) {
                try {
                    if (cur.moveToFirst()) {
                        String n = cur.getString(0);
                        if (n != null && !n.isEmpty()) t.name = n;
                    }
                } finally { cur.close(); }
            }
            t.out = c.getContentResolver().openOutputStream(uri);
            if (t.out == null) throw new IOException("Could not open file");
        } else {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            String base = name;
            String ext = "";
            int dot = name.lastIndexOf('.');
            if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
            File f = new File(dir, name);
            int n = 1;
            while (f.exists()) {
                f = new File(dir, base + " (" + n + ")" + ext);
                n++;
            }
            t.file = f;
            t.name = f.getName();
            t.out = new FileOutputStream(f);
            t.uri = FileProvider.getUriForFile(c, c.getPackageName() + ".fileprovider", f);
        }
        return t;
    }

    static void finish(Context c, Target t) {
        try { t.out.close(); } catch (IOException ignored) { }
        if (t.file != null) {
            MediaScannerConnection.scanFile(c, new String[]{t.file.getAbsolutePath()}, null, null);
        } else {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.IS_PENDING, 0);
            c.getContentResolver().update(t.uri, v, null, null);
        }
    }

    static void abort(Context c, Target t) {
        try { t.out.close(); } catch (Exception ignored) { }
        try {
            if (t.file != null) //noinspection ResultOfMethodCallIgnored
                t.file.delete();
            else c.getContentResolver().delete(t.uri, null, null);
        } catch (Exception ignored) { }
    }
}

/** Does the actual downloading itself (no Android DownloadManager), and logs everything in the Downloads list. */
class Downloader {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<String> ACTIVE = Collections.synchronizedSet(new HashSet<String>());

    static boolean isActive(String id) { return ACTIVE.contains(id); }

    static void toast(final Context c, final String msg) {
        MAIN.post(() -> Toast.makeText(c, msg, Toast.LENGTH_LONG).show());
    }

    /** For files the page already gave us as data (blob: / data: downloads). */
    static void saveBytes(Context c, byte[] data, String mime, String name) {
        final Context app = c.getApplicationContext();
        DownloadItem d = new DownloadItem();
        d.name = name;
        d.mime = mime;
        d.size = data.length;
        DownloadStore.add(app, d);
        FileSaver.Target t = null;
        try {
            t = FileSaver.create(app, mime, name);
            t.out.write(data);
            FileSaver.finish(app, t);
            d.name = t.name;
            d.uri = t.uri.toString();
            d.path = t.file != null ? t.file.getAbsolutePath() : "";
            d.done = data.length;
            d.status = "done";
            DownloadStore.update(app, d);
            toast(app, "Downloaded: " + d.name);
        } catch (Exception e) {
            if (t != null) FileSaver.abort(app, t);
            d.status = "failed";
            d.error = String.valueOf(e.getMessage());
            DownloadStore.update(app, d);
            toast(app, "Download failed");
        }
    }

    /** For normal links: downloads in the background and keeps going even if you leave the screen. */
    static void http(Context c, final String url, final String userAgent, final String cookie,
                     final String referer, String mime, String name) {
        final Context app = c.getApplicationContext();
        final DownloadItem d = new DownloadItem();
        d.name = name;
        d.url = url;
        if (mime != null && !mime.isEmpty()) d.mime = mime;
        DownloadStore.add(app, d);
        ACTIVE.add(d.id);
        toast(app, "Downloading " + name);
        new Thread(() -> run(app, d, url, userAgent, cookie, referer)).start();
    }

    private static void run(Context app, DownloadItem d, String startUrl, String userAgent,
                            String cookie, String referer) {
        HttpURLConnection conn = null;
        FileSaver.Target t = null;
        try {
            String url = startUrl;
            String ck = cookie;
            for (int hop = 0; ; hop++) {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(60000);
                conn.setInstanceFollowRedirects(false);
                if (userAgent != null) conn.setRequestProperty("User-Agent", userAgent);
                if (ck != null) conn.setRequestProperty("Cookie", ck);
                if (referer != null) conn.setRequestProperty("Referer", referer);
                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (loc == null || hop >= 6) throw new IOException("Too many redirects");
                    url = new URL(new URL(url), loc).toString();
                    ck = android.webkit.CookieManager.getInstance().getCookie(url);
                    continue;
                }
                if (code >= 400) throw new IOException("Server said " + code);
                break;
            }

            long len = conn.getContentLengthLong();
            d.size = len > 0 ? len : 0;
            String ct = conn.getContentType();
            if (ct != null && "application/octet-stream".equals(d.mime)) {
                int semi = ct.indexOf(';');
                String m = (semi >= 0 ? ct.substring(0, semi) : ct).trim();
                if (!m.isEmpty()) d.mime = m;
            }

            t = FileSaver.create(app, d.mime, d.name);
            d.name = t.name;
            DownloadStore.update(app, d);

            InputStream in = conn.getInputStream();
            try {
                byte[] buf = new byte[32768];
                int n;
                long last = 0;
                while ((n = in.read(buf)) > 0) {
                    t.out.write(buf, 0, n);
                    d.done += n;
                    long now = SystemClock.uptimeMillis();
                    if (now - last > 500) {
                        last = now;
                        DownloadStore.update(app, d);
                    }
                }
            } finally { in.close(); }

            FileSaver.finish(app, t);
            d.uri = t.uri.toString();
            d.path = t.file != null ? t.file.getAbsolutePath() : "";
            d.size = d.done;
            d.status = "done";
            DownloadStore.update(app, d);
            toast(app, "Downloaded: " + d.name);
        } catch (Exception e) {
            if (t != null) FileSaver.abort(app, t);
            d.status = "failed";
            d.error = String.valueOf(e.getMessage());
            DownloadStore.update(app, d);
            toast(app, "Download failed: " + d.name);
        } finally {
            if (conn != null) conn.disconnect();
            ACTIVE.remove(d.id);
        }
    }
}

/** Small dialog helpers so the screens stay short. */
class UiUtil {

    interface OnProject { void ok(String name, String url); }
    interface OnText { void ok(String text); }

    static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static String normalizeUrl(String u) {
        u = u == null ? "" : u.trim();
        if (u.isEmpty()) return "";
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
        return u;
    }

    private static TextInputLayout field(Context c, String hint, String value, int inputType) {
        TextInputLayout til = new TextInputLayout(c);
        til.setHint(hint);
        TextInputEditText et = new TextInputEditText(til.getContext());
        et.setInputType(inputType);
        et.setSingleLine(true);
        et.setText(value);
        til.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return til;
    }

    private static String text(TextInputLayout til) {
        return til.getEditText() == null ? "" : til.getEditText().getText().toString().trim();
    }

    static void projectDialog(Context c, String title, String name, String url, OnProject cb) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(c, 22), dp(c, 8), dp(c, 22), 0);

        final TextInputLayout nameL = field(c, "Project name", name,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        final TextInputLayout urlL = field(c, "Website URL", url,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 12);
        box.addView(nameL);
        box.addView(urlL, lp);

        final AlertDialog dlg = new MaterialAlertDialogBuilder(c)
                .setTitle(title)
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null)
                .create();
        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String u = normalizeUrl(text(urlL));
            String host = u.isEmpty() ? null : Uri.parse(u).getHost();
            if (host == null || host.isEmpty()) {
                urlL.setError("Enter a valid website address");
                return;
            }
            String n = text(nameL);
            if (n.isEmpty()) n = host;
            dlg.dismiss();
            cb.ok(n, u);
        }));
        dlg.show();
    }

    static void textDialog(Context c, String title, String hint, String value, OnText cb) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(c, 22), dp(c, 8), dp(c, 22), 0);
        final TextInputLayout l = field(c, hint, value, InputType.TYPE_CLASS_TEXT);
        box.addView(l);
        new MaterialAlertDialogBuilder(c)
                .setTitle(title)
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> cb.ok(
                        l.getEditText() == null ? "" : l.getEditText().getText().toString()))
                .show();
    }
}
