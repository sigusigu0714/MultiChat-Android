package org.multichat.android

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.UUID

/** One session owns every enabled overlay, including the five standalone slots. */
class WidgetQueueSession(private val status: (String) -> Unit) {
    private val players = mutableMapOf<String, AlertWidget>()
    private var failed = false
    private val queue: AlertPlaybackQueue = AlertPlaybackQueue(object : AlertPlaybackQueue.Player {
        override fun start(ticket: AlertPlaybackQueue.Ticket) {
            if(failed) return
            val widget = players[ticket.source] ?: error("Missing widget")
            widget.alpha=1f
            widget.web.evaluateJavascript("window.__mcAlertQueue?.grant(${ticket.sequence})",null)
            report()
        }
        override fun stop(ticket: AlertPlaybackQueue.Ticket) {
            players[ticket.source]?.blankForQueue {
                queue.stopped(ticket)
                report()
            }
        }
    }, 256)
    fun attach(widget: AlertWidget, url: String): Boolean {
        widget.alpha=0f
        if(!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            fault(); return false
        }
        val origin=java.net.URI(url).let { "${it.scheme}://${it.authority}" }
        val id=UUID.randomUUID().toString()
        players[id]=widget;queue.register(id)
        WebViewCompat.addWebMessageListener(widget.web,"mcAlertBridge",setOf(origin)) { _, message, _, mainFrame, _ ->
            if(mainFrame && !failed && players[id]===widget) {
                val raw=message.data
                if(raw!=null && raw.length<=256) runCatching {
                    val event=JSONObject(raw);val seq=event.optLong("sequence")
                    when(event.optString("type")) {
                        "ready" -> report()
                        "fault" -> fault()
                        "stalled" -> if(queue.active()?.source==id && queue.active()?.sequence==seq) status("通知の終了待ちが長くなっています。停止した場合は設定から再読み込みしてください。")
                        "request" -> if(seq in 1..9007199254740991L) {
                            when(queue.enqueue(id,seq)) {
                                AlertPlaybackQueue.Admission.FULL -> widget.web.evaluateJavascript("window.__mcAlertQueue?.retry($seq)",null)
                                AlertPlaybackQueue.Admission.ACCEPTED, AlertPlaybackQueue.Admission.DUPLICATE -> widget.web.evaluateJavascript("window.__mcAlertQueue?.accepted($seq)",null)
                                else -> Unit
                            }
                            report()
                        }
                        "done" -> queue.active()?.takeIf { it.source==id && it.sequence==seq }?.let {
                            widget.alpha=0f;queue.completed(it);report()
                        }
                    }
                }
            }
        }
        val script=widget.context.assets.open("alert-queue.js").bufferedReader().use {it.readText()}
        WebViewCompat.addDocumentStartJavaScript(widget.web,script,setOf(origin))
        return true
    }
    private fun report() {
        if(!failed) status("順番再生：${if(queue.active()!=null) "再生中" else "待機中"}・待ち ${queue.waitingCount()} 件")
    }
    private fun fault() {
        if(failed)return
        failed=true
        status("順番再生を停止しました。未対応の形式または終了を確認できません。設定で再読み込み、または順番再生をオフにしてください。")
        players.values.forEach { it.alpha=0f;it.blankForQueue {} }
    }
    fun close(done: () -> Unit) {
        failed=true
        val values=players.values.toList()
        players.clear()
        if(values.isEmpty()) {done();return}
        var remaining=values.size
        values.forEach { widget -> widget.blankForQueue { if(--remaining==0)done() } }
    }
}
