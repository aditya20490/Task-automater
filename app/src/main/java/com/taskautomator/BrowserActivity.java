package com.taskautomator;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import org.json.JSONObject;

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
            if (next != null) wait = Math.max(600, Math.min(next.optLong("wait", 600), 30000));
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
        });

        // Downloads: tapping a download link/button saves the file to the phone's Downloads folder.
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
                startDownload(url, userAgent, contentDisposition, mimeType));
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
                bridge.saveDataUrl(url, fileName);
                return;
            }
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            if (mimeType != null && !mimeType.isEmpty()) r.setMimeType(mimeType);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) r.addRequestHeader("Cookie", cookie);
            if (userAgent != null) r.addRequestHeader("User-Agent", userAgent);
            String ref = webView.getUrl();
            if (ref != null) r.addRequestHeader("Referer", ref);
            r.setTitle(fileName);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            dm.enqueue(r);
            Toast.makeText(this, "Downloading " + fileName, Toast.LENGTH_SHORT).show();
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
