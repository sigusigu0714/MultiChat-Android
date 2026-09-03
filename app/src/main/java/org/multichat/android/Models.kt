package org.multichat.android

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.text.Normalizer
import java.time.Instant
import java.util.UUID

fun JSONObject.string(key: String, fallback: String = "") = if (isNull(key)) fallback else optString(key, fallback)
fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

enum class Platform(val label: String, val wire: String) {
    TWITCH("Twitch", "twitch"), KICK("KICK", "kick"), YOUTUBE("YouTube", "youtube");
    companion object { fun parse(value: String) = entries.firstOrNull { it.wire.equals(value, true) } }
}
data class Profile(val serverURL: String = "", val obsRelayURL: String = "", val twitchClientID: String = "", val twitchRedirectURI: String = "") {
    fun json() = JSONObject().put("serverURL", serverURL).put("obsRelayURL", obsRelayURL).put("twitchClientID", twitchClientID).put("twitchRedirectURI", twitchRedirectURI)
    fun validated(): Profile {
        validEndpoint(serverURL, "https", "チャットサーバー")
        validEndpoint(obsRelayURL, "wss", "OBSリレー")
        validEndpoint(twitchRedirectURI, "https", "Twitch戻り先")
        require(twitchClientID.isBlank() == twitchRedirectURI.isBlank()) { "Twitch Client IDと戻り先は両方設定してください" }
        require(twitchClientID.isBlank() || twitchClientID.matches(Regex("[A-Za-z0-9]+"))) { "Twitch Client IDが正しくありません" }
        return this
    }
    companion object {
        fun parse(text: String): Profile {
            require(text.toByteArray(Charsets.UTF_8).size <= 16384) { "設定ファイルは16KB以内にしてください" }
            val j = JSONObject(text)
            val allowed = setOf("serverURL", "obsRelayURL", "twitchClientID", "twitchRedirectURI")
            require(j.keys().asSequence().all { it in allowed }) { "この設定には未対応の項目が含まれています" }
            require(allowed.all { !j.has(it) || j.opt(it) is String }) { "設定値は文字列にしてください" }
            return Profile(j.string("serverURL").trim(), j.string("obsRelayURL").trim(), j.string("twitchClientID").trim(), j.string("twitchRedirectURI").trim()).validated()
        }
    }
}
fun validEndpoint(value: String, scheme: String, label: String) {
    if (value.isBlank()) return
    val uri = runCatching { URI(value) }.getOrNull()
    require(uri != null && uri.scheme == scheme && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "$label は認証情報やクエリを含まない $scheme:// URLにしてください" }
}
fun safeWidgetURL(value: String): Boolean = runCatching { URI(value).let { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null } }.getOrDefault(false)

data class Channel(val id: String = UUID.randomUUID().toString(), val name: String, val platform: Platform, val identifier: String, val accountID: String = "", val watchID: String = "", val enabled: Boolean = true, val alertProvider: String = "Streamlabs") {
    fun json() = JSONObject().put("id", id).put("name", name).put("platform", platform.wire).put("identifier", identifier).put("accountID", accountID).put("watchID", watchID).put("enabled", enabled).put("alertProvider", alertProvider)
    companion object { fun parse(j: JSONObject) = Channel(j.string("id", UUID.randomUUID().toString()), j.string("name"), Platform.parse(j.string("platform")) ?: error("platform"), j.string("identifier"), j.string("accountID"), j.string("watchID"), j.optBoolean("enabled", true), j.string("alertProvider", "Streamlabs")) }
}
data class Badge(val name: String, val url: String)
data class Emote(val name: String, val url: String, val start: Int? = null, val end: Int? = null)
data class Event(val id: String = UUID.randomUUID().toString(), val platform: Platform, val channelName: String, val kind: String = "chat", val userName: String, val userID: String = "", val message: String, val amount: String = "", val avatar: String = "", val badges: List<Badge> = emptyList(), val emotes: List<Emote> = emptyList(), val sourceMessageID: String = "", val timestamp: Long = System.currentTimeMillis(), val translation: String = "", val translating: Boolean = false) {
    val isAlert get() = kind != "chat" && kind != "system"
    val key get() = platform.wire + "|" + id
    companion object {
        fun parse(j: JSONObject): Event = Event(id = j.string("id", UUID.randomUUID().toString()), platform = Platform.parse(j.string("platform")) ?: error("platform"), channelName = j.string("channelName"), kind = j.string("kind", "chat"), userName = j.string("userName"), userID = j.string("userID"), message = j.string("message"), amount = j.string("amountText"), avatar = j.string("userAvatarURL"), badges = j.optJSONArray("badges")?.objects()?.map { Badge(it.string("name"), it.string("imageURL")) } ?: emptyList(), emotes = j.optJSONArray("emotes")?.objects()?.map { Emote(it.string("name"), it.string("imageURL"), if (it.isNull("start")) null else it.optInt("start"), if (it.isNull("end")) null else it.optInt("end")) } ?: emptyList(), sourceMessageID = j.string("sourceMessageID"), timestamp = runCatching { Instant.parse(j.string("timestamp")).toEpochMilli() }.getOrDefault(System.currentTimeMillis()), translation = j.string("translatedMessage"))
    }
}

data class Settings(val theme: String = "system", val awake: Boolean = true, val alertsVisible: Boolean = true, val ttsEnabled: Boolean = false, val readNames: Boolean = true, val readAlerts: Boolean = true, val speechRate: Float = 1f, val ignoredUsers: String = "", val fontSize: Float = 17f, val density: String = "standard", val autoTranslate: Boolean = false, val showOriginal: Boolean = true, val integratedDedupe: Boolean = true, val duplicateWindow: Float = 2.5f, val targetChannel: String = "", val setupDone: Boolean = false) {
    fun json() = JSONObject().put("theme",theme).put("awake",awake).put("alertsVisible",alertsVisible).put("ttsEnabled",ttsEnabled).put("readNames",readNames).put("readAlerts",readAlerts).put("speechRate",speechRate.toDouble()).put("ignoredUsers",ignoredUsers).put("fontSize",fontSize.toDouble()).put("density",density).put("autoTranslate",autoTranslate).put("showOriginal",showOriginal).put("integratedDedupe",integratedDedupe).put("duplicateWindow",duplicateWindow.toDouble()).put("targetChannel",targetChannel).put("setupDone",setupDone)
    companion object { fun parse(j: JSONObject) = Settings(j.string("theme","system"),j.optBoolean("awake",true),j.optBoolean("alertsVisible",true),j.optBoolean("ttsEnabled"),j.optBoolean("readNames",true),j.optBoolean("readAlerts",true),j.optDouble("speechRate",1.0).toFloat().coerceIn(.25f,2f),j.string("ignoredUsers"),j.optDouble("fontSize",17.0).toFloat().coerceIn(12f,30f),j.string("density","standard"),j.optBoolean("autoTranslate"),j.optBoolean("showOriginal",true),j.optBoolean("integratedDedupe",true),j.optDouble("duplicateWindow",2.5).toFloat().coerceIn(.5f,10f),j.string("targetChannel"),j.optBoolean("setupDone")) }
}

class EventDeduplicator {
    private val ids = linkedSetOf<String>()
    private val fingerprints = linkedMapOf<String, Long>()
    fun clear() { ids.clear(); fingerprints.clear() }
    fun accept(event: Event, integrated: Boolean = true, windowSeconds: Float = 2.5f, now: Long = System.currentTimeMillis()): Boolean {
        if (event.kind == "system") return false
        // Event IDs suppress replay even when a provider has no sourceMessageID.
        val id = event.platform.wire + "|" + event.sourceMessageID.ifBlank { event.id }
        if (!ids.add(id)) return false
        while (ids.size > 5000) ids.remove(ids.first())
        if (integrated && event.platform == Platform.TWITCH && event.kind == "chat") {
            val fingerprint = normalize(event.userName) + "|" + normalize(event.message)
            val old = fingerprints[fingerprint]
            fingerprints.entries.removeAll { now - it.value > maxOf(windowSeconds * 4000, 10000f) }
            if (old != null && kotlin.math.abs(event.timestamp - old) <= windowSeconds * 1000) return false
            fingerprints[fingerprint] = event.timestamp
            while (fingerprints.size > 5000) fingerprints.remove(fingerprints.keys.first())
        }
        return true
    }
    companion object { fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "").lowercase(java.util.Locale.ROOT).trim().replace(Regex("\\s+"), " ") }
}

data class MessagePart(val text: String, val emoteURL: String? = null)
fun messageParts(message: String, emotes: List<Emote>): List<MessagePart> {
    data class Span(val start: Int, val end: Int, val emote: Emote)
    val spans = mutableListOf<Span>()
    for (e in emotes) {
        if (!safeWidgetURL(e.url)) continue
        if (e.start != null && e.end != null && e.start >= 0 && e.end >= e.start && e.end < message.length && !(e.start > 0 && message[e.start].isLowSurrogate()) && !message[e.end].isHighSurrogate()) {
            spans += Span(e.start, e.end + 1, e)
        } else if (e.name.isNotEmpty()) {
            Regex("(?<!\\S)" + Regex.escape(e.name) + "(?!\\S)").findAll(message).forEach { spans += Span(it.range.first, it.range.last + 1, e) }
        }
    }
    val result = mutableListOf<MessagePart>(); var cursor = 0
    for (span in spans.sortedBy { it.start }) {
        if (span.start < cursor) continue
        if (span.start > cursor) result += MessagePart(message.substring(cursor, span.start))
        result += MessagePart(message.substring(span.start, span.end), span.emote.url)
        cursor = span.end
    }
    if (cursor < message.length) result += MessagePart(message.substring(cursor))
    return result
}
fun speechText(event: Event, settings: Settings): String? {
    if (!settings.ttsEnabled || (event.isAlert && !settings.readAlerts)) return null
    val excluded = settings.ignoredUsers.split(Regex("[,、\\s]+")).map { it.trim().lowercase(java.util.Locale.ROOT) }
    if (event.userName.lowercase(java.util.Locale.ROOT) in excluded) return null
    val body = event.message.replace(Regex("https?://\\S+"), "URL").replace(Regex("(.)\\1{5,}"), "$1$1$1")
    val short = if (body.codePointCount(0, body.length) > 180) body.substring(0, body.offsetByCodePoints(0,180)) + " 以下省略" else body
    return listOf(if (settings.readNames) event.userName else "", event.amount, short).filter { it.isNotBlank() }.joinToString("、")
}

data class ObsSource(val name: String, val enabled: Boolean)
data class ObsState(val connected: Boolean = false, val agentOnline: Boolean = false, val obsOnline: Boolean = false, val streaming: Boolean = false, val currentScene: String = "", val scenes: List<String> = emptyList(), val sources: List<ObsSource> = emptyList(), val status: String = "未接続") {
    val ready get() = connected && agentOnline && obsOnline
    fun packet(j: JSONObject): ObsState = when(j.string("type")) {
        "auth_ok" -> copy(connected=true, agentOnline=j.optBoolean("agentOnline"), status="認証済み")
        "agent_status" -> if (j.optBoolean("online")) copy(agentOnline=true) else ObsState(connected=connected,status="OBSエージェント未接続")
        "state" -> copy(obsOnline=j.optBoolean("obsOnline"), streaming=j.optBoolean("streaming"), currentScene=j.string("currentScene"), scenes=j.optJSONArray("scenes")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(), sources=j.optJSONArray("sources")?.objects()?.map { ObsSource(it.string("name"),it.optBoolean("enabled")) } ?: emptyList(),status=if(j.optBoolean("obsOnline")) "接続済み" else "OBS未接続")
        else -> this
    }
}
fun obsCommand(action: String, scene: String = "", source: String = "", enabled: Boolean = false) = JSONObject().put("type","command").put("id",UUID.randomUUID().toString()).put("action",action).apply {
    if(action == "set_scene" || action == "set_source_visible") put("sceneName",scene)
    if(action == "set_source_visible") { put("sourceName",source); put("enabled",enabled) }
}
