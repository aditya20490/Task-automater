package com.taskautomator;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
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

/** Writes a downloaded file into the phone's Downloads folder. */
class FileSaver {
    static void save(Context c, byte[] data, String mime, String name) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, mime);
            v.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("Could not create file");
            try (OutputStream os = c.getContentResolver().openOutputStream(uri)) {
                if (os == null) throw new IOException("Could not open file");
                os.write(data);
            }
            v.clear();
            v.put(MediaStore.Downloads.IS_PENDING, 0);
            c.getContentResolver().update(uri, v, null, null);
        } else {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            try (FileOutputStream os = new FileOutputStream(new File(dir, name))) {
                os.write(data);
            }
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
