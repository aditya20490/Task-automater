package com.taskautomator;

import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class RecorderBridge {

    public interface StepListener { void onStepDone(); }

    private final WebView webView;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final JSONArray steps = new JSONArray();   // steps live HERE, not in the web page
    private volatile boolean recording = false;
    private volatile StepListener listener;

    public RecorderBridge(WebView webView) {
        this.webView = webView;
    }

    // ---------- called from JavaScript ----------

    @JavascriptInterface
    public boolean isRecording() { return recording; }

    @JavascriptInterface
    public void onAction(String json) {
        if (!recording) return;
        try {
            JSONObject o = new JSONObject(json);
            synchronized (lock) {
                int n = steps.length();
                // typing fires many events: keep only the latest value per field
                if ("type".equals(o.optString("type")) && n > 0) {
                    JSONObject last = steps.optJSONObject(n - 1);
                    if (last != null && "type".equals(last.optString("type"))
                            && last.optString("selector").equals(o.optString("selector"))) {
                        steps.put(n - 1, o);
                        return;
                    }
                }
                steps.put(o);
            }
        } catch (JSONException ignored) { }
    }

    @JavascriptInterface
    public void stepDone() {
        final StepListener l = listener;
        if (l != null) main.post(l::onStepDone);
    }

    // ---------- called from Java ----------

    public void setRecording(boolean r) { this.recording = r; }

    public void setListener(StepListener l) { this.listener = l; }

    public void clearSteps() {
        synchronized (lock) {
            while (steps.length() > 0) steps.remove(steps.length() - 1);
        }
    }

    public int getStepCount() {
        synchronized (lock) { return steps.length(); }
    }

    public String getStep(int i) {
        synchronized (lock) {
            JSONObject o = steps.optJSONObject(i);
            return o == null ? "{}" : o.toString();
        }
    }

    /** Injects the recorder + step-runner into the current page (safe to call many times). */
    public void injectScript() {
        String js = "(function(){" +
            "if(window.__TA_ATTACHED) return;" +
            "window.__TA_ATTACHED = true;" +
            "function sel(el){" +
            "  if(!el||el.nodeType!==1) return 'body';" +
            "  var path=[];" +
            "  while(el && el.nodeType===1 && el!==document.documentElement && path.length<8){" +
            "    if(el.id){ path.unshift('#'+CSS.escape(el.id)); break; }" +
            "    var s=el.tagName.toLowerCase();" +
            "    var c=(typeof el.className==='string')?el.className.trim().split(/\\s+/).filter(Boolean)[0]:null;" +
            "    if(c) s+='.'+CSS.escape(c);" +
            "    var p=el.parentNode;" +
            "    if(p && p.children){" +
            "      var sib=Array.prototype.filter.call(p.children,function(x){return x.tagName===el.tagName;});" +
            "      if(sib.length>1) s+=':nth-of-type('+(sib.indexOf(el)+1)+')';" +
            "    }" +
            "    path.unshift(s); el=p;" +
            "  }" +
            "  return path.join(' > ');" +
            "}" +
            "function send(o){" +
            "  try{ if(window.AndroidBridge && AndroidBridge.isRecording()) AndroidBridge.onAction(JSON.stringify(o)); }catch(e){}" +
            "}" +
            "document.addEventListener('click',function(e){" +
            "  send({type:'click',selector:sel(e.target)});" +
            "},true);" +
            "document.addEventListener('input',function(e){" +
            "  var t=e.target;" +
            "  if(t && (t.tagName==='INPUT'||t.tagName==='TEXTAREA')) send({type:'type',selector:sel(t),value:t.value});" +
            "},true);" +
            "document.addEventListener('keydown',function(e){" +
            "  if(e.key==='Enter' && e.target && (e.target.tagName==='INPUT')) send({type:'key',key:'Enter',selector:sel(e.target)});" +
            "},true);" +
            "window.__TA_do=function(s){" +
            "  var tries=0;" +
            "  function done(){ try{ AndroidBridge.stepDone(); }catch(e){} }" +
            "  (function attempt(){" +
            "    var el=null;" +
            "    try{ el=document.querySelector(s.selector); }catch(e){}" +
            "    if(!el){ if(++tries<30){ setTimeout(attempt,300); } else { done(); } return; }" +
            "    try{" +
            "      if(el.scrollIntoView) el.scrollIntoView({block:'center'});" +
            "      if(s.type==='click'){ el.click(); }" +
            "      else if(s.type==='type'){" +
            "        el.focus();" +
            "        var proto=(el.tagName==='TEXTAREA')?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;" +
            "        var setter=Object.getOwnPropertyDescriptor(proto,'value').set;" +
            "        setter.call(el,s.value);" +
            "        el.dispatchEvent(new Event('input',{bubbles:true}));" +
            "        el.dispatchEvent(new Event('change',{bubbles:true}));" +
            "      }" +
            "      else if(s.type==='key'){" +
            "        if(el.form){ if(el.form.requestSubmit) el.form.requestSubmit(); else el.form.submit(); }" +
            "        else { ['keydown','keypress','keyup'].forEach(function(n){" +
            "          el.dispatchEvent(new KeyboardEvent(n,{key:'Enter',code:'Enter',keyCode:13,bubbles:true}));}); }" +
            "      }" +
            "    }catch(err){}" +
            "    done();" +
            "  })();" +
            "};" +
            "})();";
        webView.evaluateJavascript(js, null);
    }
}
