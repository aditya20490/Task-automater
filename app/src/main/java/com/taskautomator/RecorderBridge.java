package com.taskautomator;

import android.webkit.JavascriptInterface;
import android.webkit.WebView;

public class RecorderBridge {

    private final WebView webView;
    private boolean recording = false;

    public RecorderBridge(WebView webView) {
        this.webView = webView;
    }

    @JavascriptInterface
    public boolean isRecording() { return recording; }

    public void setRecording(boolean r) { this.recording = r; }

    /** JS calls this when an action is captured, so Java can update UI later if needed. */
    @JavascriptInterface
    public void onAction(String json) {
        // You can extend this to save to SharedPreferences, log, etc.
    }

    /** Injects the recorder+replayer script into the page. */
    public void injectScript() {
        String js = "(function(){" +
            "if(window.__TA_ATTACHED) return;" +
            "window.__TA_ATTACHED = true;" +
            "window.__TA_STEPS = [];" +
            "window.__TA_RECORDING = false;" +
            "function buildSelector(el){" +
            "  if(!el) return 'body';" +
            "  if(el.id) return '#' + CSS.escape(el.id);" +
            "  let path=[];" +
            "  while(el && el.nodeType===1 && path.length<6){" +
            "    let sel=el.tagName.toLowerCase();" +
            "    if(el.className && typeof el.className==='string'){" +
            "      const c=el.className.trim().split(/\\s+/).filter(Boolean)[0];" +
            "      if(c) sel+='.'+CSS.escape(c);" +
            "    }" +
            "    const p=el.parentNode;" +
            "    if(p){" +
            "      const sib=Array.from(p.children).filter(x=>x.tagName===el.tagName);" +
            "      if(sib.length>1) sel+=':nth-of-type('+(sib.indexOf(el)+1)+')';" +
            "    }" +
            "    path.unshift(sel); el=p;" +
            "  }" +
            "  return path.join(' > ');" +
            "}" +
            "document.addEventListener('click',function(e){" +
            "  if(!window.__TA_RECORDING) return;" +
            "  window.__TA_STEPS.push({type:'click',selector:buildSelector(e.target),x:e.clientX,y:e.clientY});" +
            "},true);" +
            "document.addEventListener('change',function(e){" +
            "  if(!window.__TA_RECORDING) return;" +
            "  const t=e.target;" +
            "  if(t.tagName==='INPUT'||t.tagName==='TEXTAREA'){" +
            "    window.__TA_STEPS.push({type:'type',selector:buildSelector(t),value:t.value});" +
            "  }" +
            "},true);" +
            "document.addEventListener('submit',function(e){" +
            "  if(!window.__TA_RECORDING) return;" +
            "  window.__TA_STEPS.push({type:'submit',selector:buildSelector(e.target)});" +
            "},true);" +
            "window.__TA_startRecord=function(){window.__TA_STEPS=[];window.__TA_RECORDING=true;return 'recording';};" +
            "window.__TA_finishRecord=function(){window.__TA_RECORDING=false;return JSON.stringify(window.__TA_STEPS);};" +
            "window.__TA_replay=function(stepsJson){" +
            "  const steps=JSON.parse(stepsJson);" +
            "  let i=0;" +
            "  function next(){" +
            "    if(i>=steps.length) return;" +
            "    const s=steps[i++];" +
            "    try{" +
            "      if(s.type==='click'){const el=document.querySelector(s.selector);if(el)el.click();}" +
            "      else if(s.type==='type'){const el=document.querySelector(s.selector);if(el){el.focus();el.value=s.value;el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}}" +
            "      else if(s.type==='submit'){const el=document.querySelector(s.selector);if(el){if(el.submit)el.submit();else el.dispatchEvent(new Event('submit',{bubbles:true}));}}" +
            "    }catch(err){}" +
            "    setTimeout(next,400);" +
            "  }" +
            "  next();" +
            "};" +
            "})();";
        webView.evaluateJavascript(js, null);
    }
}
