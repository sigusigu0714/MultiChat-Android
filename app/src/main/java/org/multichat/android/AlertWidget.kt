package org.multichat.android

import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Stable desktop viewport; only the view transform changes when an alert appears. */
class AlertWidget(context: Context, private val fitContent: Boolean) : FrameLayout(context) {
    val web = WebView(context)
    private var canvasWidth = 1920f
    private var canvasHeight = 1080f
    private var focus: RectF? = null
    private var destroyed = false
    private var measuring = false
    private val blankCallbacks = mutableListOf<() -> Unit>()
    private var blanking = false
    fun blankForQueue(done: () -> Unit) {
        if(destroyed) {done();return}
        blankCallbacks.add(done)
        if(!blanking) {blanking=true;web.stopLoading();web.loadUrl("about:blank")}
    }
    var fittedContent: RectF? = null; private set
    private val measureContent = object : Runnable {
        override fun run() {
            if(destroyed || !fitContent || !isAttachedToWindow) return
            if(measuring) { postDelayed(this,400); return }
            measuring=true
            web.evaluateJavascript(CONTENT_BOUNDS) { raw ->
                measuring=false
                if(!destroyed) {
                    runCatching { applyMeasurement(JSONObject(raw)) }
                    removeCallbacks(this); postDelayed(this,400)
                }
            }
        }
    }
    init {
        clipChildren=true; clipToPadding=true
        web.setBackgroundColor(Color.TRANSPARENT)
        web.settings.javaScriptEnabled=true;web.settings.domStorageEnabled=true
        web.settings.mediaPlaybackRequiresUserGesture=false
        web.settings.allowFileAccess=false;web.settings.allowContentAccess=false
        web.settings.mixedContentMode=android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        web.isFocusable=false;web.isClickable=false
        web.webViewClient=object:WebViewClient() {
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest)=!safeWidgetURL(request.url.toString())
            override fun onPageFinished(view:WebView,url:String?) {
                if(blanking && url=="about:blank") {
                    blanking=false
                    val callbacks=blankCallbacks.toList();blankCallbacks.clear();callbacks.forEach {it()}
                    return
                }
                removeCallbacks(measureContent);post(measureContent)
            }
        }
        addView(web,LayoutParams(-1,-1))
    }
    fun loadUrl(url:String) { web.loadUrl(url) }
    fun reload() { focus=null;fittedContent=null;web.reload() }
    fun stopLoading() { web.stopLoading() }
    fun destroy() { destroyed=true;removeCallbacks(measureContent);web.stopLoading();removeView(web);web.destroy() }
    override fun onAttachedToWindow() { super.onAttachedToWindow();if(fitContent)post(measureContent) }
    override fun onDetachedFromWindow() { removeCallbacks(measureContent);super.onDetachedFromWindow() }
    override fun onMeasure(widthSpec:Int,heightSpec:Int) {
        if(!fitContent) { super.onMeasure(widthSpec,heightSpec);return }
        val density=resources.displayMetrics.density
        web.measure(MeasureSpec.makeMeasureSpec((canvasWidth*density).roundToInt(),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec((canvasHeight*density).roundToInt(),MeasureSpec.EXACTLY))
        setMeasuredDimension(MeasureSpec.getSize(widthSpec),MeasureSpec.getSize(heightSpec))
    }
    override fun onLayout(changed:Boolean,left:Int,top:Int,right:Int,bottom:Int) {
        if(!fitContent) { super.onLayout(changed,left,top,right,bottom);return }
        web.layout(0,0,web.measuredWidth,web.measuredHeight)
        fit()
    }
    private fun applyMeasurement(data:JSONObject) {
        fun number(name:String)=data.optDouble(name,Double.NaN).toFloat()
        val cw=number("width");val ch=number("height")
        if(!cw.isFinite() || !ch.isFinite() || cw<=0 || ch<=0)return
        val nextWidth=max(canvasWidth,min(cw,8192f));val nextHeight=max(canvasHeight,min(ch,8192f))
        val l=number("left");val t=number("top");val r=number("right");val b=number("bottom")
        focus=if(listOf(l,t,r,b).all {it.isFinite()} && r>l && b>t) {
            RectF(max(0f,l-24),max(0f,t-24),min(nextWidth,r+24),min(nextHeight,b+24)).takeIf {it.width()>1 && it.height()>1}
        } else null
        if(nextWidth!=canvasWidth || nextHeight!=canvasHeight) {canvasWidth=nextWidth;canvasHeight=nextHeight;requestLayout()}
        else fit()
    }
    private fun fit() {
        if(width<=0 || height<=0 || web.width<=0)return
        val box=focus ?: RectF(0f,0f,canvasWidth,canvasHeight)
        val density=resources.displayMetrics.density
        val margin=8*density
        val scale=min((width-2*margin)/(box.width()*density),(height-2*margin)/(box.height()*density))
        if(!scale.isFinite() || scale<=0)return
        val x=width/2f-box.centerX()*density*scale;val y=height/2f-box.centerY()*density*scale
        web.pivotX=0f;web.pivotY=0f;web.scaleX=scale;web.scaleY=scale;web.translationX=x;web.translationY=y
        fittedContent=focus?.let {RectF(x+it.left*density*scale,y+it.top*density*scale,x+it.right*density*scale,y+it.bottom*density*scale)}
    }
    companion object {
        val CONTENT_BOUNDS = """(() => {
  const root = document.documentElement, body = document.body;
  const result = {width:Math.max(innerWidth,root.scrollWidth,body?body.scrollWidth:0),
    height:Math.max(innerHeight,root.scrollHeight,body?body.scrollHeight:0),viewportWidth:innerWidth};
  let box = null, visited = 0;
  const add = (r,ox,oy,sx,sy) => {
    const l=ox+r.left*sx,t=oy+r.top*sy,rr=ox+r.right*sx,b=oy+r.bottom*sy;
    if (![l,t,rr,b].every(Number.isFinite) || rr-l<1 || b-t<1 || rr<0 || b<0 || l>8192 || t>8192) return;
    if (!box) box={left:l,top:t,right:rr,bottom:b};
    else {box.left=Math.min(box.left,l);box.top=Math.min(box.top,t);box.right=Math.max(box.right,rr);box.bottom=Math.max(box.bottom,b);}
  };
  const scan = (doc,ox,oy,sx,sy,depth) => {
    const win=doc.defaultView;if(!doc.body || !win || depth>3) return;
    const cache=new WeakMap();
    const visible=el => {
      if(!el || el.nodeType!==1) return true;
      if(cache.has(el)) return cache.get(el);
      const s=win.getComputedStyle(el);
      const ok=s.display!=='none' && s.visibility!=='hidden' && Number(s.opacity)>.02 && visible(el.parentElement);
      cache.set(el,ok);return ok;
    };
    const walker=doc.createTreeWalker(doc.body,NodeFilter.SHOW_ELEMENT|NodeFilter.SHOW_TEXT);
    let node;
    while((node=walker.nextNode()) && ++visited<=3000) {
      if(node.nodeType===3) {
        const p=node.parentElement;
        if(!p || /^(SCRIPT|STYLE|NOSCRIPT|OPTION)${'$'}/.test(p.tagName) || !node.textContent.trim() || !visible(p))continue;
        const range=doc.createRange();range.selectNodeContents(node);
        for(const r of range.getClientRects())add(r,ox,oy,sx,sy);
      } else if(visible(node)) {
        const r=node.getBoundingClientRect();if(r.width<1 || r.height<1)continue;
        if(node.tagName==='IFRAME') {
          try {
            const child=node.contentDocument;
            if(child && child.body && node.contentWindow.innerWidth>0) {
              scan(child,ox+r.left*sx,oy+r.top*sy,sx*r.width/node.contentWindow.innerWidth,sy*r.height/node.contentWindow.innerHeight,depth+1);
              continue;
            }
          }catch(_){}
          // Cross-origin frames cannot be inspected; preserve their full rectangle.
          add(r,ox,oy,sx,sy);continue;
        }
        const style=win.getComputedStyle(node);
        const image=/^(IMG|VIDEO|CANVAS|SVG)${'$'}/.test(node.tagName.toUpperCase());
        const background=style.backgroundImage!=='none' ||
          (style.backgroundColor!=='transparent' && !/rgba\([^)]*,\s*0\s*\)/.test(style.backgroundColor));
        if(image || background)add(r,ox,oy,sx,sy);
      }
    }
  };
  scan(document,0,0,1,1,0);
  if(box) Object.assign(result,box);
  return result;
})()
"""
    }
}
