package com.taskautomator;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private RecorderBridge bridge;
    private EditText urlInput;
    private Button loadBtn, recordBtn, finishBtn, runBtn;
    private TextView statusText;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean pageLoading = false;

    // replay state
    private boolean replaying = false;
    private int replayIndex = 0;
    private int replayToken = 0;
    private int waitedMs = 0;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        urlInput   = findViewById(R.id.urlInput);
        loadBtn    = findViewById(R.id.loadBtn);
        recordBtn  = findViewById(R.id.recordBtn);
        finishBtn  = findViewById(R.id.finishBtn);
        runBtn     = findViewById(R.id.runBtn);
        statusText = findViewById(R.id.statusText);
        webView    = findViewById(R.id.webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));

        bridge = new RecorderBridge(webView);
        webView.addJavascriptInterface(bridge, "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                pageLoading = true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoading = false;
                bridge.injectScript();   // re-attach on every page; steps are kept in Java
            }
        });
        webView.setWebChromeClient(new WebChromeClient());

        bridge.setListener(() -> {
            if (!replaying) return;
            final int t = ++replayToken;           // invalidates the watchdog of the step that just finished
            handler.postDelayed(() -> runNext(t), 1000);
        });

        loadBtn.setOnClickListener(v -> {
            String url = urlInput.getText().toString().trim();
            if (TextUtils.isEmpty(url)) { Toast.makeText(this, "Enter URL", Toast.LENGTH_SHORT).show(); return; }
            if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://" + url;
            urlInput.setText(url);
            webView.loadUrl(url);
            statusText.setText("Loading: " + url);
        });

        recordBtn.setOnClickListener(v -> {
            if (webView.getUrl() == null) { Toast.makeText(this, "Load a page first", Toast.LENGTH_SHORT).show(); return; }
            bridge.clearSteps();
            bridge.injectScript();
            bridge.setRecording(true);
            statusText.setText("🔴 Recording… tap/type on the page.");
            finishBtn.setEnabled(true);
            recordBtn.setEnabled(false);
            runBtn.setEnabled(false);
        });

        finishBtn.setOnClickListener(v -> {
            bridge.setRecording(false);
            int count = bridge.getStepCount();
            statusText.setText("✅ Saved " + count + " steps. Tap Run to replay.");
            finishBtn.setEnabled(false);
            recordBtn.setEnabled(true);
            runBtn.setEnabled(count > 0);
        });

        runBtn.setOnClickListener(v -> {
            if (bridge.getStepCount() == 0) {
                Toast.makeText(this, "No steps saved", Toast.LENGTH_SHORT).show();
                return;
            }
            bridge.setRecording(false);
            replaying = true;
            replayIndex = 0;
            waitedMs = 0;
            recordBtn.setEnabled(false);
            runBtn.setEnabled(false);
            final int t = ++replayToken;
            handler.postDelayed(() -> runNext(t), 500);
        });
    }

    /** Runs one recorded step, then waits for the page to finish its job before the next one. */
    private void runNext(final int token) {
        if (!replaying || token != replayToken) return;

        final int total = bridge.getStepCount();
        if (replayIndex >= total) {
            finishReplay("✅ Replay finished (" + total + " steps).");
            return;
        }
        // wait while a page is still loading (max ~15 s)
        if (pageLoading && waitedMs < 15000) {
            waitedMs += 300;
            handler.postDelayed(() -> runNext(token), 300);
            return;
        }
        waitedMs = 0;

        final int idx = replayIndex++;
        statusText.setText("▶ Step " + (idx + 1) + " of " + total);
        bridge.injectScript();
        webView.evaluateJavascript("window.__TA_do && window.__TA_do(" + bridge.getStep(idx) + ")", null);

        // watchdog: if the page navigated away and the step never reported back, move on
        handler.postDelayed(() -> {
            if (replaying && token == replayToken) {
                final int t = ++replayToken;
                runNext(t);
            }
        }, 15000);
    }

    private void finishReplay(String msg) {
        replaying = false;
        replayToken++;
        statusText.setText(msg);
        recordBtn.setEnabled(true);
        runBtn.setEnabled(bridge.getStepCount() > 0);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
