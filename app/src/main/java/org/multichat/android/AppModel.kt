package org.multichat.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

class AppModel(app: Application) : AndroidViewModel(app) {
    val store = SecureStore(app)
    var profile by mutableStateOf(runCatching { Profile.parse(store.get("profile")) }.getOrDefault(Profile())); private set
    var settings by mutableStateOf(runCatching { Settings.parse(JSONObject(store.get("settings"))) }.getOrDefault(Settings())); private set
    var channels by mutableStateOf(runCatching { JSONArray(store.get("channels")).objects().map(Channel::parse).take(10) }.getOrDefault(emptyList())); private set
    var events by mutableStateOf<List<Event>>(emptyList()); private set
    var chatStatus by mutableStateOf("未接続"); private set
    var obs by mutableStateOf(ObsState()); private set
    var notice by mutableStateOf("")
    var busy by mutableStateOf(false); private set
    var sending by mutableStateOf(false); private set
    var hiddenDuplicates by mutableIntStateOf(0); private set
    var twitchLogin by mutableStateOf(store.get("twitch-login")); private set
    var alertRevision by mutableIntStateOf(0); private set
    private val api = NetworkApi()
    private val translator = LocalTranslator()
    private val translationSlots = Semaphore(2)
    private val pendingTranslations = mutableSetOf<String>()
    private val dedupe = EventDeduplicator()
    private var chatSocket: WebSocket? = null
    private var obsSocket: WebSocket? = null
    private var chatGeneration = 0
    private var obsGeneration = 0
    private var revision = 0
    private var operationGeneration = 0
    private var operationJob: Job? = null
    private var validationJob: Job? = null
    private var chatRetry: Job? = null
    private var obsRetry: Job? = null
    private var foreground = false
    private var speechReady = false
    private var speech: TextToSpeech? = null
    private var notificationID = 0
    init {
        speech = TextToSpeech(app) { status -> speechReady = status == TextToSpeech.SUCCESS; if(speechReady) speech?.language = Locale.JAPAN }
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("alerts", "配信アラート", NotificationManager.IMPORTANCE_DEFAULT))
    }
    private fun guarded(block: suspend () -> Unit) {
        if(busy) return
        busy = true
        val operation=++operationGeneration
        operationJob=viewModelScope.launch { try { block() } catch(e: CancellationException) { throw e } catch(e: Exception) { notice = if(e is ApiFailure) e.message.orEmpty() else "処理できませんでした。設定と通信状態を確認してください" } finally { if(operation==operationGeneration) busy = false } }
    }
    fun changeSettings(value: Settings) { store.put("settings",value.json().toString()); settings=value; if(!value.ttsEnabled) speech?.stop() }
    fun saveProfile(value: Profile) {
        value.validated(); operationGeneration++; operationJob?.cancel(); busy=false; disconnectChat(); disconnectObs(); revision++
        if(value.serverURL != profile.serverURL) {
            channels.forEach { store.put("alert-${it.id}",""); if(it.accountID.isNotBlank()) store.put("kick-${it.accountID}","") }
            channels = emptyList(); saveChannels(); events=emptyList(); dedupe.clear(); hiddenDuplicates=0
        }
        if(value.obsRelayURL != profile.obsRelayURL) store.put("obs-token","")
        if(value.twitchClientID != profile.twitchClientID || value.twitchRedirectURI != profile.twitchRedirectURI) clearTwitch()
        store.put("pending-auth",""); store.put("profile",value.json().toString()); profile=value
        changeSettings(settings.copy(setupDone=true))
        if(foreground) { connectChat(); connectObs(); syncChannels() }
    }
    fun start() { if(foreground) return; foreground=true; restoreTwitch(); connectChat(); connectObs(); if(profile.serverURL.isNotBlank()) syncChannels() }
    fun stop() { foreground=false; disconnectChat(); disconnectObs(); speech?.stop() }
    fun reconnect() { disconnectChat(); connectChat(); syncChannels() }
    private fun endpoint(path: String) = URI(profile.serverURL).resolve(path).toASCIIString()
    private fun saveChannels() = store.put("channels",JSONArray(channels.map { it.json() }).toString())
    fun updateChannel(channel: Channel, alertURL: String) {
        require(alertURL.isBlank() || safeWidgetURL(alertURL)) { "アラートURLにはHTTPSを使ってください" }
        store.put("alert-${channel.id}",alertURL.trim()); channels=channels.map { if(it.id==channel.id) channel else it }; saveChannels(); alertRevision++
    }
    fun alertURL(channel: Channel) = store.get("alert-${channel.id}")
    fun refreshAlerts() { alertRevision++ }
    private fun upsert(channel: Channel) {
        val old=channels.firstOrNull { (channel.accountID.isNotBlank() && it.accountID==channel.accountID) || (it.platform==channel.platform && it.identifier.equals(channel.identifier,true)) || (channel.watchID.isNotBlank() && it.platform==channel.platform && it.watchID==channel.watchID) }
        if(old != null && old.accountID.isNotBlank() && channel.accountID.isBlank()) return
        if(old != null) channels=channels.map { if(it.id==old.id) channel.copy(id=old.id,enabled=old.enabled,alertProvider=old.alertProvider,accountID=channel.accountID.ifBlank { old.accountID },watchID=channel.watchID.ifBlank { old.watchID }) else it }
        else if(channels.size<10) channels=channels+channel
        saveChannels()
    }
    fun syncChannels() = guarded {
        if(profile.serverURL.isBlank()) return@guarded
        val version=revision
        val accounts=JSONArray(api.request(endpoint("/api/accounts"))).objects()
        if(version!=revision) return@guarded
        accounts.forEach { j -> Platform.parse(j.string("platform"))?.let { upsert(Channel(name=j.string("displayName"),platform=it,identifier=j.string("channelIdentifier"),accountID=j.string("id"))) } }
        for(platform in listOf(Platform.TWITCH,Platform.YOUTUBE)) {
            try {
                val watches=JSONArray(api.request(endpoint("/api/${platform.wire}/watch-channels"))).objects()
                if(version!=revision) return@guarded
                watches.forEach { j -> upsert(Channel(name=j.string("displayName"),platform=platform,identifier=j.string(if(platform==Platform.TWITCH) "login" else "channelIdentifier"),watchID=j.string("id"))) }
            } catch(e: ApiFailure) { if(e.code!=404) throw e }
        }
    }
    fun addWatch(platform: Platform, value: String) = guarded {
        require(channels.size<10 && value.isNotBlank() && platform!=Platform.KICK)
        val version=revision
        val body=JSONObject().put(if(platform==Platform.TWITCH) "login" else "channel",if(platform==Platform.TWITCH) value.trim().removePrefix("@") else value.trim())
        val j=JSONObject(api.request(endpoint("/api/${platform.wire}/watch-channels"),"POST",body))
        if(version!=revision) return@guarded
        upsert(Channel(name=j.string("displayName"),platform=platform,identifier=j.string(if(platform==Platform.TWITCH) "login" else "channelIdentifier"),watchID=j.string("id")))
        notice="チャンネルを追加しました"
    }
    fun removeChannel(channel: Channel) = guarded {
        val version=revision
        if(channel.watchID.isNotBlank()) api.request(endpoint("/api/${channel.platform.wire}/watch-channels/${encode(channel.watchID)}"),"DELETE")
        else if(channel.accountID.isNotBlank()) api.request(endpoint("/api/accounts/${encode(channel.accountID)}"),"DELETE")
        if(version!=revision) return@guarded
        channels=channels.filterNot { it.id==channel.id }; saveChannels()
        store.put("alert-${channel.id}",""); if(channel.accountID.isNotBlank()) store.put("kick-${channel.accountID}",""); alertRevision++
    }
    fun serverLoginURL(platform: Platform): String {
        require(profile.serverURL.isNotBlank()) { "先に接続先を設定してください" }
        require(channels.size<10 || channels.any { it.platform==platform }) { "最大10チャンネルです" }
        val pending=PendingAuth.create(platform.wire); store.put("pending-auth",pending.json())
        return endpoint("/oauth/${platform.wire}/start")+"?return_to="+encode("multichat://oauth-complete?state=${pending.nonce}")
    }
    fun twitchLoginURL(): String {
        require(profile.twitchClientID.isNotBlank() && profile.twitchRedirectURI.isNotBlank()) { "接続セットアップでTwitch Client IDと戻り先を設定してください" }
        val pending=PendingAuth.create("twitch-direct"); store.put("pending-auth",pending.json())
        return "https://id.twitch.tv/oauth2/authorize?response_type=token&client_id=${encode(profile.twitchClientID)}&redirect_uri=${encode(profile.twitchRedirectURI)}&scope=user%3Awrite%3Achat&state=${pending.nonce}&force_verify=true"
    }
    fun callback(raw: String) {
        if(raw.length>32768) return
        val uri=runCatching { URI(raw) }.getOrNull() ?: return
        val params=runCatching { callbackParameters(uri) }.getOrNull() ?: return
        val direct=uri.scheme=="obsremote"
        if(!direct && !(uri.scheme=="multichat" && uri.host=="oauth-complete")) return
        val purpose=if(direct) "twitch-direct" else params["platform"].orEmpty().lowercase(Locale.ROOT)
        val pending=PendingAuth.parse(store.get("pending-auth"))
        if(pending?.matches(params["state"].orEmpty(),purpose,System.currentTimeMillis())!=true) { notice="認証の戻り先を確認できません。アプリからログインをやり直してください"; return }
        store.put("pending-auth","")
        if(params.containsKey("error")) { notice="ログインは完了しませんでした"; return }
        val version=revision
        viewModelScope.launch {
            try {
                if(direct) {
                    val token=params["access_token"].orEmpty(); require(token.isNotBlank())
                    val j=JSONObject(api.request("https://id.twitch.tv/oauth2/validate",headers=mapOf("Authorization" to "OAuth $token")))
                    require(j.string("client_id")==profile.twitchClientID && j.string("user_id").isNotBlank())
                    require(j.optJSONArray("scopes")?.let { a -> (0 until a.length()).any { a.getString(it)=="user:write:chat" } }==true)
                    if(version!=revision) return@launch
                    store.put("twitch-token",token); store.put("twitch-user-id",j.string("user_id")); store.put("twitch-login",j.string("login")); twitchLogin=j.string("login")
                } else {
                    val platform=Platform.parse(purpose) ?: return@launch
                    val account=params["account_id"].orEmpty(); val channel=params["channel"].orEmpty()
                    require(account.isNotBlank() && channel.isNotBlank())
                    if(platform==Platform.KICK && !params["send_key"].isNullOrBlank()) store.put("kick-$account",params["send_key"].orEmpty())
                    upsert(Channel(name=params["name"].orEmpty().ifBlank { channel },platform=platform,identifier=channel,accountID=account)); syncChannels()
                }
                notice="アカウントを連携しました"
            } catch(e: CancellationException) { throw e } catch(_: Exception) { notice="ログインを確認できませんでした。連携をやり直してください" }
        }
    }
    private fun restoreTwitch() {
        val token=store.get("twitch-token")
        if(token.isBlank() || validationJob?.isActive==true) return
        val version=revision
        validationJob=viewModelScope.launch {
            try {
                val j=JSONObject(api.request("https://id.twitch.tv/oauth2/validate",headers=mapOf("Authorization" to "OAuth $token")))
                if(version!=revision || token!=store.get("twitch-token")) return@launch
                if(j.string("client_id")!=profile.twitchClientID || j.string("user_id").isBlank()) { clearTwitch(); return@launch }
                store.put("twitch-user-id",j.string("user_id")); store.put("twitch-login",j.string("login")); twitchLogin=j.string("login")
            } catch(e: CancellationException) { throw e } catch(e: ApiFailure) {
                if(e.code==401 && version==revision && token==store.get("twitch-token")) clearTwitch()
            } catch(_: Exception) { /* Keep saved login through temporary connectivity failures. */ }
        }
    }
    fun clearTwitch() { revision++; store.put("pending-auth",""); listOf("twitch-token","twitch-user-id","twitch-login").forEach { store.put(it,"") }; twitchLogin="" }
    fun sendComment(platform: Platform, target: String, account: String, message: String, onSent: () -> Unit = {}) {
        if(sending) return
        val clean=message.trim()
        if(clean.isBlank() || clean.codePointCount(0,clean.length)>500 || clean.toByteArray().size>2048) { notice="コメントは500文字・2048バイト以内にしてください"; return }
        val version=revision; sending=true
        viewModelScope.launch {
            try {
                if(platform==Platform.KICK) {
                    val key=store.get("kick-$account"); require(account.isNotBlank() && key.isNotBlank())
                    api.request(endpoint("/api/kick/chat"),"POST",JSONObject().put("accountId",account).put("content",clean),mapOf("X-Account-Send-Key" to key))
                } else {
                    val token=store.get("twitch-token"); val userID=store.get("twitch-user-id"); require(token.isNotBlank() && userID.isNotBlank() && target.isNotBlank())
                    val headers=mapOf("Authorization" to "Bearer $token","Client-Id" to profile.twitchClientID)
                    val users=JSONObject(api.request("https://api.twitch.tv/helix/users?login=${encode(target.trim().removePrefix("@"))}",headers=headers)).getJSONArray("data")
                    require(users.length()>0)
                    if(version!=revision) return@launch
                    val response=JSONObject(api.request("https://api.twitch.tv/helix/chat/messages","POST",JSONObject().put("broadcaster_id",users.getJSONObject(0).getString("id")).put("sender_id",userID).put("message",clean),headers))
                    require(response.optJSONArray("data")?.optJSONObject(0)?.optBoolean("is_sent")==true)
                }
                if(version==revision) { notice="送信しました"; onSent() }
            } catch(e: CancellationException) { throw e } catch(e: Exception) { notice=if(e is ApiFailure) "送信に失敗しました (HTTP ${e.code})。連携と権限を確認してください" else "送信できませんでした。接続先・送信先・アカウント連携を確認してください" }
            finally { sending=false }
        }
    }
    private fun disconnectChat() { chatGeneration++; chatRetry?.cancel(); chatRetry=null; chatSocket?.cancel(); chatSocket=null; chatStatus="未接続" }
    private fun connectChat() {
        if(!foreground || profile.serverURL.isBlank() || chatSocket!=null) return
        val generation=++chatGeneration; chatStatus="接続中…"
        val url=URI(profile.serverURL).let { URI("wss",null,it.host,it.port,"/ws",null,null).toASCIIString() }
        chatSocket=api.client.newWebSocket(Request.Builder().url(url).build(),object: WebSocketListener() {
            override fun onOpen(ws: WebSocket,response: Response) { viewModelScope.launch { if(generation==chatGeneration) chatStatus="接続済み" } }
            override fun onMessage(ws: WebSocket,text: String) { if(text.length>262144) return; viewModelScope.launch { if(generation==chatGeneration) runCatching { receive(Event.parse(JSONObject(text))) } } }
            override fun onFailure(ws: WebSocket,t: Throwable,response: Response?) { retryChat(generation) }
            override fun onClosed(ws: WebSocket,code: Int,reason: String) { retryChat(generation) }
            override fun onClosing(ws: WebSocket,code: Int,reason: String) { ws.close(code,null) }
        })
    }
    private fun retryChat(generation: Int) { viewModelScope.launch { if(generation!=chatGeneration) return@launch; chatSocket=null; chatStatus="再接続待ち"; chatRetry?.cancel(); chatRetry=viewModelScope.launch { delay(3000); if(generation==chatGeneration) connectChat() } } }
    private fun receive(event: Event) {
        if(!dedupe.accept(event,settings.integratedDedupe,settings.duplicateWindow)) { if(event.kind!="system") hiddenDuplicates++; return }
        val match=channels.firstOrNull { it.platform==event.platform && (it.identifier.removePrefix("@").equals(event.channelName.removePrefix("@"),true) || it.name.equals(event.channelName,true)) }
        if(match?.enabled==false) return
        events=(events.filterNot { it.key==event.key }+event).sortedBy { it.timestamp }.takeLast(1000)
        speechText(event,settings)?.let { speak(it) }
        if(event.isAlert) notifyAlert(event)
        if(settings.autoTranslate && event.translation.isBlank()) translate(event)
    }
    fun translate(event: Event) {
        if(event.translating || event.translation.isNotBlank() || event.key in pendingTranslations || pendingTranslations.size >= 24) return
        pendingTranslations.add(event.key)
        val version=revision
        events=events.map { if(it.key==event.key) it.copy(translating=true) else it }
        viewModelScope.launch {
            var translated=""
            try { translated=translationSlots.withPermit { translator.japanese(event.message) } }
            catch(e: CancellationException) { throw e } catch(_: Exception) { if(!settings.autoTranslate) notice="翻訳できませんでした。通信状態を確認してください" }
            finally { pendingTranslations.remove(event.key); if(version==revision) events=events.map { if(it.key==event.key) it.copy(translation=translated,translating=false) else it } }
        }
    }
    private fun speak(text: String) { if(speechReady) { speech?.setSpeechRate(settings.speechRate); speech?.speak(text,TextToSpeech.QUEUE_ADD,null,java.util.UUID.randomUUID().toString()) } }
    fun testSpeech() { if(speechReady) speak("マルチチャットの読み上げテストです") else notice="端末の読み上げエンジンを設定してください" }
    fun stopSpeech() { speech?.stop() }
    private fun notifyAlert(event: Event) {
        val app=getApplication<Application>()
        if(!NotificationManagerCompat.from(app).areNotificationsEnabled()) return
        val pending=PendingIntent.getActivity(app,0,Intent(app,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=NotificationCompat.Builder(app,"alerts").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("${event.platform.label} / ${event.channelName}").setContentText(listOf(event.userName,event.amount,event.message).filter { it.isNotBlank() }.joinToString(" ")).setContentIntent(pending).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        try { NotificationManagerCompat.from(app).notify(++notificationID,notification) } catch(_: SecurityException) { }
    }
    fun saveObsToken(token: String) { store.put("obs-token",token.trim()); disconnectObs(); connectObs() }
    fun hasObsToken() = store.get("obs-token").isNotBlank()
    fun reconnectObs() { disconnectObs(); connectObs() }
    private fun disconnectObs() { obsGeneration++; obsRetry?.cancel(); obsRetry=null; obsSocket?.cancel(); obsSocket=null; obs=ObsState() }
    private fun connectObs() {
        val token=store.get("obs-token")
        if(!foreground || profile.obsRelayURL.isBlank() || token.isBlank() || obsSocket!=null) return
        val generation=++obsGeneration; obs=ObsState(status="接続中…")
        obsSocket=api.client.newWebSocket(Request.Builder().url(profile.obsRelayURL).build(),object: WebSocketListener() {
            override fun onOpen(ws: WebSocket,response: Response) { viewModelScope.launch { if(generation==obsGeneration) ws.send(JSONObject().put("type","auth").put("role","client").put("token",token).toString()) } }
            override fun onMessage(ws: WebSocket,text: String) { if(text.length>262144) return; viewModelScope.launch {
                if(generation!=obsGeneration) return@launch
                runCatching {
                    val j=JSONObject(text); val previous=obs; obs=obs.packet(j)
                    if(obs.connected && obs.agentOnline && (!previous.connected || !previous.agentOnline)) obsAction("refresh")
                    when(j.string("type")) {
                        "result" -> notice=if(j.optBoolean("ok")) "OBS操作を実行しました" else "OBS操作に失敗しました"
                        "error" -> { notice="OBS認証または操作に失敗しました"; if(!obs.connected) disconnectObs() }
                    }
                }
            } }
            override fun onFailure(ws: WebSocket,t: Throwable,response: Response?) { retryObs(generation) }
            override fun onClosed(ws: WebSocket,code: Int,reason: String) { retryObs(generation) }
            override fun onClosing(ws: WebSocket,code: Int,reason: String) { ws.close(code,null) }
        })
    }
    private fun retryObs(generation: Int) { viewModelScope.launch { if(generation!=obsGeneration) return@launch; obsSocket=null; obs=ObsState(status="再接続待ち"); obsRetry?.cancel(); obsRetry=viewModelScope.launch { delay(3000); if(generation==obsGeneration) connectObs() } } }
    fun obsAction(action: String, scene: String = "", source: String = "", enabled: Boolean = false) {
        val allowed=if(action in setOf("refresh","twitch_fix","kick_fix")) obs.connected && obs.agentOnline else obs.ready
        if(!allowed) { notice="OBSに接続されていません"; return }
        if(action !in setOf("refresh","twitch_fix","kick_fix","start_stream","stop_stream","set_scene","set_source_visible")) return
        if(obsSocket?.send(obsCommand(action,scene,source,enabled).toString())!=true) notice="操作を送信できませんでした"
    }
    override fun onCleared() { stop(); speech?.shutdown(); translator.close(); api.client.dispatcher.executorService.shutdown(); super.onCleared() }
    companion object { fun encode(value: String) = URLEncoder.encode(value,"UTF-8") }
}
