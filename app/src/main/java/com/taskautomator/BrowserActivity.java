package com.taskautomator;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * Browser screen. Two modes:
 *  - record: opens the site in a brand-new session and records every tap / typed text
 *  - run:    opens the site in a brand-new session and replays the saved steps by itself
 */
public class BrowserActivity extends AppCompatActivity {

    private WebView webView;
    private RecorderBridge bridge;
    private TextView titleText, modeBadge, statusText;
    private LinearProgressIndicator progress;
    private MaterialButton primaryBtn, secondaryBtn;

    private ProjectStore store;
    private Project project;
    private boolean isNewProject = false;
    private boolean recordMode;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean pageLoading = false;

    // replay state
    private boolean replaying = false;
    private int replayIndex = 0;
    private int replayToken = 0;
    private int waitedMs = 0;
    private int failedSteps = 0;

    // file chooser ("Browse file" / <input type=file>)
    private ValueCallback<Uri[]> filePathCallback;
    private final Set<Integer> usedFileSteps = new HashSet<>();
    private final ActivityResultLauncher<Intent> filePicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> onFilePicked(result.getResultCode(), result.getData()));

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_browser);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        store = new ProjectStore(this);
        String mode = getIntent().getStringExtra("mode");
        recordMode = "record".equals(mode);
        project = store.get(getIntent().getStringExtra("projectId"));

        if (recordMode && project == null) {
            project = new Project();
            project.name = getIntent().getStringExtra("name");
            project.url = getIntent().getStringExtra("url");
            isNewProject = true;
        }
        if (project == null || project.url == null || project.url.isEmpty()) {
            Toast.makeText(this, "Project not found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        titleText    = findViewById(R.id.titleText);
        modeBadge    = findViewById(R.id.modeBadge);
        statusText   = findViewById(R.id.statusText);
        progress     = findViewById(R.id.progress);
        primaryBtn   = findViewById(R.id.primaryBtn);
        secondaryBtn = findViewById(R.id.secondaryBtn);
        webView      = findViewById(R.id.webView);

        titleText.setText(project.name);

        if (Build.VERSION.SDK_INT <= 28
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }

        setupWebView();

        bridge = new RecorderBridge(webView);
        webView.addJavascriptInterface(bridge, "AndroidBridge");
        bridge.setRecordListener(() -> {
            if (recordMode) {
                int n = bridge.getStepCount();
                statusText.setText("Recording · " + n + (n == 1 ? " step" : " steps") + " captured");
            }
        });
        bridge.setStepListener(ok -> {
            if (!replaying) return;
            if (!ok) failedSteps++;
            long wait = 600;
            JSONObject next = project.steps.optJSONObject(replayIndex);
            if (next != null && "file".equals(next.optString("type"))) wait = 600;   // file step = time spent in the picker
            else if (next != null) wait = Math.max(600, Math.min(next.optLong("wait", 600), 30000));
            scheduleNext(wait);
        });

        if (recordMode) {
            modeBadge.setText("● REC");
            modeBadge.setTextColor(getColor(R.color.ta_rec));
            statusText.setText("Recording · do the task on the page, then tap Finish & save");
            primaryBtn.setOnClickListener(v -> finishRecording());
            secondaryBtn.setText("Cancel");
            secondaryBtn.setOnClickListener(v -> confirmCancelRecording());
        } else {
            modeBadge.setText("▶ RUN");
            modeBadge.setTextColor(getColor(R.color.ta_accent));
            primaryBtn.setVisibility(View.GONE);
            secondaryBtn.setText("■  Stop");
            secondaryBtn.setOnClickListener(v -> {
                if (replaying) stopReplay("Stopped by you.");
                else finish();
            });
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!replaying && webView.canGoBack()) webView.goBack();
                else secondaryBtn.performClick();
            }
        });

        // Every session starts clean: no cookies, no storage, no cache, no login.
        statusText.setText(recordMode ? "Preparing a fresh session…" : "Preparing a fresh session…");
        cleanSession(() -> {
            if (recordMode) {
                bridge.clearSteps();
                bridge.setRecording(true);
                statusText.setText("Recording · do the task on the page, then tap Finish & save");
            }
            pageLoading = true;
            webView.loadUrl(project.url);
            if (!recordMode) startReplay();
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                pageLoading = true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoading = false;
                if (bridge != null) bridge.injectScript();   // steps are kept in Java, nothing is lost
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String u = request.getUrl().toString();
                // stay inside the app; ignore intent:// market:// etc.
                return !(u.startsWith("http://") || u.startsWith("https://")
                        || u.startsWith("about:") || u.startsWith("blob:") || u.startsWith("data:"));
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgressCompat(newProgress, true);
                progress.setVisibility(newProgress >= 100 ? View.INVISIBLE : View.VISIBLE);
            }

            // "Browse file" buttons: let the user pick a file; record it so a run can pick it again.
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = null;

                if (replaying) {
                    // during a run: answer with the file that was picked while recording
                    JSONObject step = null;
                    int at = -1;
                    for (int i = Math.max(0, replayIndex); i < project.steps.length(); i++) {
                        JSONObject c = project.steps.optJSONObject(i);
                        if (c != null && "file".equals(c.optString("type")) && !usedFileSteps.contains(i)) {
                            step = c; at = i; break;
                        }
                    }
                    if (step == null) { callback.onReceiveValue(null); return true; }
                    usedFileSteps.add(at);
                    JSONArray paths = step.optJSONArray("paths");
                    java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
                    if (paths != null) {
                        for (int i = 0; i < paths.length(); i++) {
                            File f = new File(paths.optString(i));
                            if (f.exists()) uris.add(Uri.fromFile(f));
                        }
                    }
                    callback.onReceiveValue(uris.isEmpty() ? null : uris.toArray(new Uri[0]));
                    return true;
                }

                filePathCallback = callback;
                try {
                    filePicker.launch(params.createIntent());
                } catch (Exception e) {
                    filePathCallback = null;
                    callback.onReceiveValue(null);
                    Toast.makeText(BrowserActivity.this, "Can't open the file picker", Toast.LENGTH_SHORT).show();
                }
                return true;
            }

            // Some sites open the download in a new window / tab: catch it and keep it inside the app.
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                final WebView popup = new WebView(BrowserActivity.this);
                popup.getSettings().setJavaScriptEnabled(true);
                popup.setDownloadListener((url, ua, cd, mime, len) -> startDownload(url, ua, cd, mime));
                popup.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                        String u = request.getUrl().toString();
                        if (u.startsWith("http://") || u.startsWith("https://")) webView.loadUrl(u);
                        handler.postDelayed(popup::destroy, 1500);
                        return true;
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popup);
                resultMsg.sendToTarget();
                return true;
            }
        });

        // Downloads: tapping a download link/button saves the file to the phone's Downloads folder.
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
                startDownload(url, userAgent, contentDisposition, mimeType));
    }

    // ---------------------------------------------------------------- file chooser

    private void onFilePicked(int resultCode, Intent data) {
        final ValueCallback<Uri[]> cb = filePathCallback;
        filePathCallback = null;
        if (cb == null) return;
        if (resultCode != RESULT_OK || data == null) { cb.onReceiveValue(null); return; }

        final java.util.ArrayList<Uri> picked = new java.util.ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++)
                picked.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            picked.add(data.getData());
        }
        if (picked.isEmpty()) { cb.onReceiveValue(null); return; }

        // copy into the app so the same file can be used again when the task runs
        new Thread(() -> {
            final java.util.ArrayList<Uri> out = new java.util.ArrayList<>();
            final JSONArray paths = new JSONArray();
            final JSONArray names = new JSONArray();
            File dir = new File(getFilesDir(), "uploads");
            dir.mkdirs();
            for (Uri u : picked) {
                try {
                    String name = displayName(u);
                    File sub = new File(dir, java.util.UUID.randomUUID().toString());
                    sub.mkdirs();
                    File f = new File(sub, name);
                    try (InputStream in = getContentResolver().openInputStream(u);
                         FileOutputStream os = new FileOutputStream(f)) {
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                    }
                    out.add(Uri.fromFile(f));
                    paths.put(f.getAbsolutePath());
                    names.put(name);
                } catch (Exception ignored) { }
            }
            handler.post(() -> {
                if (out.isEmpty()) {
                    cb.onReceiveValue(null);
                    Toast.makeText(this, "Couldn't read that file", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (recordMode) {
                    try {
                        JSONObject step = new JSONObject();
                        step.put("type", "file");
                        step.put("paths", paths);
                        step.put("names", names);
                        bridge.onAction(step.toString());
                    } catch (Exception ignored) { }
                }
                cb.onReceiveValue(out.toArray(new Uri[0]));
            });
        }).start();
    }

    private String displayName(Uri u) {
        String name = null;
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) { }
        if (name == null || name.isEmpty()) name = "file";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private void cleanSession(final Runnable then) {
        final CookieManager cm = CookieManager.getInstance();
        cm.removeAllCookies(ok -> {
            cm.flush();
            WebStorage.getInstance().deleteAllData();
            webView.clearCache(true);
            webView.clearHistory();
            webView.clearFormData();
            then.run();
        });
    }

    // ---------------------------------------------------------------- downloads

    private void startDownload(String url, String userAgent, String contentDisposition, String mimeType) {
        pageLoading = false;   // a download never replaces the page, so don't keep waiting for it
        String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
        try {
            if (url.startsWith("blob:")) {
                String js = "(function(){var x=new XMLHttpRequest();x.open('GET'," + JSONObject.quote(url)
                    + ",true);x.responseType='blob';x.onload=function(){var r=new FileReader();"
                    + "r.onloadend=function(){AndroidBridge.saveDataUrl(r.result," + JSONObject.quote(fileName)
                    + ");};r.readAsDataURL(x.response);};x.send();})();";
                webView.evaluateJavascript(js, null);
                Toast.makeText(this, "Downloading " + fileName, Toast.LENGTH_SHORT).show();
                return;
            }
            if (url.startsWith("data:")) {
                final String dataUrl = url;
                final String name = fileName;
                new Thread(() -> bridge.saveDataUrl(dataUrl, name)).start();
                return;
            }
            String cookie = CookieManager.getInstance().getCookie(url);
            Downloader.http(this, url, userAgent, cookie, webView.getUrl(), mimeType, fileName);
        } catch (Exception e) {
            Toast.makeText(this, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------------- recording

    private void finishRecording() {
        bridge.setRecording(false);
        int n = bridge.getStepCount();
        if (n == 0) {
            Toast.makeText(this, "Nothing recorded yet — do the task on the page first", Toast.LENGTH_LONG).show();
            bridge.setRecording(true);
            return;
        }
        project.steps = bridge.getSteps();
        store.save(project);
        Toast.makeText(this, "Saved “" + project.name + "” · " + n + " steps", Toast.LENGTH_LONG).show();
        if (isNewProject) {
            Intent i = new Intent(this, ProjectActivity.class);
            i.putExtra("projectId", project.id);
            startActivity(i);
        }
        finish();
    }

    private void confirmCancelRecording() {
        if (bridge.getStepCount() == 0) { finish(); return; }
        new MaterialAlertDialogBuilder(this)
            .setTitle("Discard this recording?")
            .setMessage(bridge.getStepCount() + " recorded steps will be lost.")
            .setNegativeButton("Keep recording", null)
            .setPositiveButton("Discard", (d, w) -> finish())
            .show();
    }

    // ---------------------------------------------------------------- replay

    private void startReplay() {
        replaying = true;
        replayIndex = 0;
        failedSteps = 0;
        waitedMs = 0;
        usedFileSteps.clear();
        statusText.setText("Starting…");
        scheduleNext(1000);
    }

    private void scheduleNext(long delay) {
        final int t = ++replayToken;
        handler.postDelayed(() -> runNext(t), delay);
    }

    /** Runs one recorded step, then waits for the page / the step to finish before the next one. */
    private void runNext(final int token) {
        if (!replaying || token != replayToken) return;

        final int total = project.steps.length();
        if (replayIndex >= total) {
            replaying = false;
            replayToken++;
            statusText.setText(failedSteps == 0
                    ? "✅ Done · all " + total + " steps ran"
                    : "⚠ Done · " + failedSteps + " step(s) could not find their button/field on the page");
            secondaryBtn.setText("Close");
            return;
        }

        // wait while a page is still loading (max ~15 s)
        if (pageLoading && waitedMs < 15000) {
            waitedMs += 300;
            handler.postDelayed(() -> runNext(token), 300);
            return;
        }
        waitedMs = 0;

        final JSONObject step = project.steps.optJSONObject(replayIndex);
        final int shown = replayIndex + 1;
        replayIndex++;
        statusText.setText("Running step " + shown + " of " + total);
        if (step == null) { scheduleNext(300); return; }
        if ("file".equals(step.optString("type"))) { scheduleNext(300); return; }   // handled by the file chooser

        bridge.injectScript();
        webView.evaluateJavascript("window.__TA_do && window.__TA_do(" + step.toString() + ")", null);

        // watchdog: if the page navigated away and the step never reported back, move on
        handler.postDelayed(() -> {
            if (replaying && token == replayToken) {
                failedSteps++;
                scheduleNext(300);
            }
        }, 20000);
    }

    private void stopReplay(String msg) {
        replaying = false;
        replayToken++;
        statusText.setText(msg);
        secondaryBtn.setText("Close");
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.stopLoading();
            if (webView.getParent() instanceof ViewGroup) {
                ((ViewGroup) webView.getParent()).removeView(webView);
            }
            webView.destroy();
        }
        super.onDestroy();
    }
}
