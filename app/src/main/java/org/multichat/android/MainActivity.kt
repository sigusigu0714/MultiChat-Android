package org.multichat.android

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.ComposeView
import coil.ImageLoader
import coil.Coil
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder

class MainActivity : ComponentActivity() {
    val model: AppModel by viewModels()
    private lateinit var alertHost: AlertHost
    private val notificationPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Coil.setImageLoader(ImageLoader.Builder(this).components { if(Build.VERSION.SDK_INT>=28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory()) }.build())
        WebView.setWebContentsDebuggingEnabled(false)
        val root=FrameLayout(this)
        alertHost=AlertHost(this) { model.alertQueueStatus=it }
        val compose=ComposeView(this).apply { setContent { MultiChatApp(model, ::updateOverlay, ::requestNotifications) } }
        root.addView(compose,FrameLayout.LayoutParams(-1,-1)); root.addView(alertHost,FrameLayout.LayoutParams(-1,-1)); setContentView(root)
        intent?.dataString?.let { model.callback(it); intent.data=null }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); intent.dataString?.let { model.callback(it); intent.data=null } }
    override fun onStart() { super.onStart(); model.start(); if(::alertHost.isInitialized) alertHost.resume() }
    override fun onStop() { model.stop(); if(::alertHost.isInitialized) alertHost.pause(); super.onStop() }
    override fun onDestroy() { if(::alertHost.isInitialized) alertHost.destroy(); super.onDestroy() }
    private fun requestNotifications() { if(Build.VERSION.SDK_INT>=33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    private fun updateOverlay(show: Boolean) {
        if(!::alertHost.isInitialized) return
        if(model.settings.awake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        alertHost.update(model.activeAlertURLs(),model.alertRevision,show && model.settings.alertsVisible,model.sequentialAlerts)
    }
}
class AlertHost(context: android.content.Context, private val queueStatus:(String)->Unit = {}) : FrameLayout(context) {
    private val widgets=mutableMapOf<String,Pair<String,AlertWidget>>()
    private var revision=-1
    private var paused=false
    private var savedURLs=emptyMap<String,String>()
    private var savedVisible=false
    private var savedRevision=-1
    private var savedSequential=false
    private var session:WidgetQueueSession?=null
    private var rebuilding=false
    private var queueGeneration=0
    init { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = false
    fun update(urls: Map<String,String>, newRevision: Int, visible: Boolean, sequential:Boolean=false) {
        val changed=urls!=savedURLs || newRevision!=savedRevision || sequential!=savedSequential
        savedSequential=sequential
        savedURLs=urls; savedVisible=visible; savedRevision=newRevision
        if(paused || rebuilding) return
        alpha=if(visible) 1f else 0f
        if(changed && session!=null) {
            rebuilding=true
            val generation=++queueGeneration
            val old=session;session=null
            old?.close {
                if(generation!=queueGeneration)return@close
                widgets.values.forEach {removeView(it.second);it.second.destroy()};widgets.clear()
                rebuilding=false
                update(savedURLs,savedRevision,savedVisible,savedSequential)
            }
            return
        }
        if(sequential && session==null) {
            if(widgets.isNotEmpty()) {
                rebuilding=true
                val generation=++queueGeneration
                val old=widgets.values.map {it.second};var remaining=old.size
                old.forEach {widget -> widget.blankForQueue {
                    if(generation!=queueGeneration)return@blankForQueue
                    removeView(widget);widget.destroy()
                    if(--remaining==0) {widgets.clear();rebuilding=false;update(savedURLs,savedRevision,savedVisible,savedSequential)}
                } }
                return
            }
            session=WidgetQueueSession(queueStatus)
        }
        (widgets.keys-urls.keys).forEach { key -> widgets.remove(key)?.second?.let { removeView(it); it.destroy() } }
        urls.forEach { (key,url) ->
            val old=widgets[key]
            if(old==null || old.first!=url) {
                old?.second?.let { removeView(it); it.destroy() }
                if(safeWidgetURL(url)) {
                    val host=java.net.URI(url).host.lowercase()
                    val web=AlertWidget(context,host=="streamelements.com" || host.endsWith(".streamelements.com"))
                    if(session?.attach(web,url)!=false) web.loadUrl(url)
                    widgets[key]=url to web; addView(web,LayoutParams(-1,-1))
                }
            } else if(revision!=newRevision) old.second.reload()
        }
        revision=newRevision
    }
    fun pause() { paused=true;queueGeneration++;rebuilding=false;session?.close {};session=null;widgets.values.forEach { removeView(it.second); it.second.stopLoading(); it.second.destroy() }; widgets.clear(); removeAllViews(); visibility=GONE }
    fun resume() { paused=false; visibility=VISIBLE; update(savedURLs,savedRevision,savedVisible,savedSequential) }
    fun destroy() { queueGeneration++;rebuilding=false;session=null;widgets.values.forEach { removeView(it.second); it.second.destroy() }; widgets.clear(); removeAllViews() }
}
