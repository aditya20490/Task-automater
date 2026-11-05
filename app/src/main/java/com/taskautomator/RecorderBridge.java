package com.taskautomator;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URLDecoder;

public class RecorderBridge {

    public interface StepListener { void onStepDone(boolean ok); }

    private final WebView webView;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final JSONArray steps = new JSONArray();   // steps live HERE, not in the web page
    private volatile boolean recording = false;
    private volatile long lastTime = 0;
    private volatile StepListener stepListener;
    private volatile Runnable recordListener;

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
            long now = System.currentTimeMillis();
            long wait = Math.max(300, Math.min(now - lastTime, 30000));
            lastTime = now;
            synchronized (lock) {
                int n = steps.length();
                // typing fires many events: keep only the latest value per field
                if ("type".equals(o.optString("type")) && n > 0) {
                    JSONObject last = steps.optJSONObject(n - 1);
                    if (last != null && "type".equals(last.optString("type"))
                            && last.optString("selector").equals(o.optString("selector"))) {
                        o.put("wait", last.optLong("wait", wait));
                        steps.put(n - 1, o);
                        notifyRecorded();
                        return;
                    }
                }
                o.put("wait", wait);
                steps.put(o);
            }
            notifyRecorded();
        } catch (JSONException ignored) { }
    }

    @JavascriptInterface
    public void stepDone(final boolean ok) {
        final StepListener l = stepListener;
        if (l != null) main.post(() -> l.onStepDone(ok));
    }

    /** Called by the page (for blob:/data: downloads) with the file as a data: URL. */
    @JavascriptInterface
    public void saveDataUrl(String dataUrl, String fileName) {
        final Context c = webView.getContext();
        try {
            int comma = dataUrl.indexOf(',');
            String header = dataUrl.substring(0, comma);
            String body = dataUrl.substring(comma + 1);
            String mime = "application/octet-stream";
            if (header.startsWith("data:")) {
                String m = header.substring(5);
                int semi = m.indexOf(';');
                if (semi >= 0) m = m.substring(0, semi);
                if (!m.isEmpty()) mime = m;
            }
            byte[] bytes = header.contains(";base64")
                    ? Base64.decode(body, Base64.DEFAULT)
                    : URLDecoder.decode(body, "UTF-8").getBytes("UTF-8");
            FileSaver.save(c, bytes, mime, fileName);
            toast(c, "Downloaded: " + fileName);
        } catch (Exception e) {
            toast(c, "Download failed");
        }
    }

    // ---------- called from Java ----------

    public void setRecording(boolean r) {
        this.recording = r;
        if (r) lastTime = System.currentTimeMillis();
    }

    public void setStepListener(StepListener l) { this.stepListener = l; }

    public void setRecordListener(Runnable r) { this.recordListener = r; }

    public int getStepCount() {
        synchronized (lock) { return steps.length(); }
    }

    public void clearSteps() {
        synchronized (lock) {
            while (steps.length() > 0) steps.remove(steps.length() - 1);
        }
    }

    /** A copy of everything recorded so far. */
    public JSONArray getSteps() {
        synchronized (lock) {
            try { return new JSONArray(steps.toString()); }
            catch (JSONException e) { return new JSONArray(); }
        }
    }

    private void notifyRecorded() {
        final Runnable r = recordListener;
        if (r != null) main.post(r);
    }

    private void toast(final Context c, final String msg) {
        main.post(() -> Toast.makeText(c, msg, Toast.LENGTH_LONG).show());
    }

    /** Injects the recorder + step-runner into the current page (safe to call many times). */
    public void injectScript() {
        String js = "(function(){" +
            "if(window.__TA_ATTACHED) return;" +
            "window.__TA_ATTACHED = true;" +
            "var INTERACTIVE='a,button,input,select,textarea,label,summary,[role=button],[role=link],[role=tab],[role=menuitem],[role=checkbox],[role=option],[onclick]';" +
            "function esc(s){ return CSS.escape(s); }" +
            "function goodClass(c){ return c && c.length<30 && !/\\d{3,}/.test(c) && !/^(active|focus|hover|open|show|selected|ng-|js-|is-|has-)/i.test(c); }" +
            "function txt(el){" +
            "  var t=(el.innerText||el.value||(el.getAttribute&&(el.getAttribute('aria-label')||el.getAttribute('title')||el.getAttribute('placeholder')))||'');" +
            "  return String(t).trim().replace(/\\s+/g,' ').slice(0,50);" +
            "}" +
            "function sel(el){" +
            "  if(!el||el.nodeType!==1) return 'body';" +
            "  var path=[];" +
            "  while(el && el.nodeType===1 && el!==document.documentElement && path.length<8){" +
            "    if(el.id && !/\\d{4,}/.test(el.id)){ path.unshift('#'+esc(el.id)); break; }" +
            "    var s=el.tagName.toLowerCase();" +
            "    var attr=null;" +
            "    ['data-testid','data-test','data-id','name','aria-label','placeholder','title'].some(function(a){" +
            "      var v=el.getAttribute&&el.getAttribute(a);" +
            "      if(v && v.length<60 && v.indexOf('\\\\')<0){ attr='['+a+'='+JSON.stringify(v)+']'; return true; }" +
            "      return false;" +
            "    });" +
            "    if(attr){ s+=attr; }" +
            "    else {" +
            "      var c=(typeof el.className==='string')?el.className.trim().split(/\\s+/).filter(goodClass)[0]:null;" +
            "      if(c) s+='.'+esc(c);" +
            "    }" +
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
            // ---------- recording ----------
            "document.addEventListener('click',function(e){" +
            "  var t=e.target; if(!t||t.nodeType!==1) return;" +
            "  var el=(t.closest&&t.closest(INTERACTIVE))||t;" +
            "  send({type:'click',selector:sel(el),tag:el.tagName.toLowerCase(),text:txt(el)," +
            "        xr:e.clientX/window.innerWidth,yr:e.clientY/window.innerHeight,sy:window.scrollY});" +
            "},true);" +
            "document.addEventListener('input',function(e){" +
            "  var t=e.target;" +
            "  if(t && (t.tagName==='INPUT'||t.tagName==='TEXTAREA')){" +
            "    send({type:'type',selector:sel(t),value:t.value," +
            "          label:(t.placeholder||t.name||(t.getAttribute&&t.getAttribute('aria-label'))||t.type||'')," +
            "          secret:(t.type==='password')});" +
            "  }" +
            "},true);" +
            "document.addEventListener('change',function(e){" +
            "  var t=e.target;" +
            "  if(t && t.tagName==='SELECT') send({type:'select',selector:sel(t),value:t.value});" +
            "},true);" +
            "document.addEventListener('keydown',function(e){" +
            "  if(e.key==='Enter' && e.target && e.target.tagName==='INPUT') send({type:'key',key:'Enter',selector:sel(e.target)});" +
            "},true);" +
            // ---------- replaying ----------
            "function fire(el,n){ try{ el.dispatchEvent(new MouseEvent(n,{bubbles:true,cancelable:true,view:window})); }catch(e){} }" +
            "function find(s,last){" +
            "  var el=null; try{ el=document.querySelector(s.selector); }catch(e){}" +
            "  if(el) return el;" +
            "  if(s.text && s.tag){" +
            "    var list=document.getElementsByTagName(s.tag);" +
            "    for(var i=0;i<list.length;i++){ if(txt(list[i])===s.text) return list[i]; }" +
            "  }" +
            "  if(last && s.type==='click' && typeof s.xr==='number'){" +
            "    window.scrollTo(0,s.sy||0);" +
            "    var e2=document.elementFromPoint(s.xr*window.innerWidth,s.yr*window.innerHeight);" +
            "    if(e2) return (e2.closest&&e2.closest(INTERACTIVE))||e2;" +
            "  }" +
            "  return null;" +
            "}" +
            "window.__TA_do=function(s){" +
            "  var tries=0;" +
            "  function done(ok){ try{ AndroidBridge.stepDone(ok); }catch(e){} }" +
            "  (function attempt(){" +
            "    var el=find(s, tries>=29);" +
            "    if(!el){ if(++tries<30){ setTimeout(attempt,300); } else { done(false); } return; }" +
            "    try{" +
            "      if(el.scrollIntoView) el.scrollIntoView({block:'center'});" +
            "      if(s.type==='click'){ fire(el,'mousedown'); fire(el,'mouseup'); el.click(); }" +
            "      else if(s.type==='type'){" +
            "        el.focus();" +
            "        var proto=(el.tagName==='TEXTAREA')?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;" +
            "        Object.getOwnPropertyDescriptor(proto,'value').set.call(el,s.value);" +
            "        el.dispatchEvent(new Event('input',{bubbles:true}));" +
            "        el.dispatchEvent(new Event('change',{bubbles:true}));" +
            "      }" +
            "      else if(s.type==='select'){" +
            "        el.value=s.value; el.dispatchEvent(new Event('change',{bubbles:true}));" +
            "      }" +
            "      else if(s.type==='key'){" +
            "        if(el.form){ if(el.form.requestSubmit) el.form.requestSubmit(); else el.form.submit(); }" +
            "        else { ['keydown','keypress','keyup'].forEach(function(n){" +
            "          el.dispatchEvent(new KeyboardEvent(n,{key:'Enter',code:'Enter',keyCode:13,bubbles:true})); }); }" +
            "      }" +
            "    }catch(err){ done(false); return; }" +
            "    done(true);" +
            "  })();" +
            "};" +
            "})();";
        webView.evaluateJavascript(js, null);
    }
}
