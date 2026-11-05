package com.taskautomator;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
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

    private String savedStepsJson = null;   // null = nothing recorded yet

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
        s.setAllowFileAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));
        // Note: no setSupportMultipleWindows needed for basic use

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (bridge != null) {
                    bridge.injectScript();
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient());

        bridge = new RecorderBridge(webView);
        webView.addJavascriptInterface(bridge, "AndroidBridge");

        loadBtn.setOnClickListener(v -> {
            String url = urlInput.getText().toString().trim();
            if (TextUtils.isEmpty(url)) { Toast.makeText(this,"Enter URL",Toast.LENGTH_SHORT).show(); return; }
            if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://" + url;
            urlInput.setText(url);
            webView.loadUrl(url);
            statusText.setText("Loading: " + url);
        });

        recordBtn.setOnClickListener(v -> {
            if (webView.getUrl() == null) { Toast.makeText(this,"Load a page first",Toast.LENGTH_SHORT).show(); return; }
            webView.evaluateJavascript("window.__TA_startRecord && window.__TA_startRecord()", value -> {
                bridge.setRecording(true);
                statusText.setText("🔴 Recording… tap/type on the page.");
                finishBtn.setEnabled(true);
                recordBtn.setEnabled(false);
                runBtn.setEnabled(false);
            });
        });

        finishBtn.setOnClickListener(v -> {
            webView.evaluateJavascript("window.__TA_finishRecord && window.__TA_finishRecord()", value -> {
                bridge.setRecording(false);
                // value is a JSON string of steps, wrapped with quotes by evaluateJavascript
                savedStepsJson = unquote(value);
                int count = countSteps(savedStepsJson);
                statusText.setText("✅ Saved " + count + " steps. Tap Run to replay.");
                finishBtn.setEnabled(false);
                recordBtn.setEnabled(true);
                runBtn.setEnabled(count > 0);
            });
        });

        runBtn.setOnClickListener(v -> {
            if (savedStepsJson == null || savedStepsJson.isEmpty() || savedStepsJson.equals("[]")) {
                Toast.makeText(this,"No steps saved",Toast.LENGTH_SHORT).show();
                return;
            }
            // Re-inject in case page reloaded
            bridge.injectScript();
            final String steps = savedStepsJson;
            webView.postDelayed(() -> {
                String escaped = steps.replace("\\", "\\\\").replace("'", "\\'");
                webView.evaluateJavascript(
                    "window.__TA_replay && window.__TA_replay('" + escaped + "')", null);
                statusText.setText("▶ Replaying " + countSteps(steps) + " steps…");
            }, 500);
        });
    }

    private String unquote(String v) {
        if (v == null) return "[]";
        if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
            v = v.substring(1, v.length() - 1);
            v = v.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n");
        }
        return v;
    }

    private int countSteps(String json) {
        if (json == null || json.isEmpty()) return 0;
        int c = 0;
        for (int i = 0; i < json.length(); i++) if (json.charAt(i) == '{') c++;
        return c;
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
