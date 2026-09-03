package org.multichat.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.common.model.DownloadConditions
import java.net.URI
import java.net.URLDecoder

// Every value, including channel names and OAuth state, is encrypted at rest.
class SecureStore(context: Context) {
    private val preferences = context.getSharedPreferences("secure-v1", Context.MODE_PRIVATE)
    private val alias = "multichat.android.storage.v1"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun get(name: String): String {
        val value = preferences.getString(name, null) ?: return ""
        return runCatching {
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }.getOrElse { preferences.edit().remove(name).commit(); "" }
    }
    @Synchronized fun put(name: String, value: String) {
        if (value.isEmpty()) { preferences.edit().remove(name).commit(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(preferences.edit().putString(name, encoded).commit()) { "設定を保存できませんでした" }
    }
}
class ApiFailure(val code: Int) : Exception("サーバー処理に失敗しました (HTTP $code)")
class NetworkApi(val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(25,TimeUnit.SECONDS).pingInterval(25,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()) {
    suspend fun request(url: String, method: String = "GET", body: JSONObject? = null, headers: Map<String,String> = emptyMap()): String = withContext(Dispatchers.IO) {
        require(URI(url).scheme == "https") { "HTTPS接続が必要です" }
        val builder = Request.Builder().url(url)
        headers.forEach { (key,value) -> builder.header(key,value) }
        if(method != "GET") builder.method(method, body?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType()))
        client.newCall(builder.build()).execute().use { response ->
            if(!response.isSuccessful) throw ApiFailure(response.code)
            val source = response.body?.source() ?: return@withContext ""
            // API responses are bounded; credentials or remote error bodies never reach logs/UI.
            require(!source.request(2L * 1024 * 1024 + 1)) { "サーバーの応答が大きすぎます" }
            source.readUtf8()
        }
    }
}
data class PendingAuth(val nonce: String, val purpose: String, val created: Long) {
    fun json() = JSONObject().put("nonce",nonce).put("purpose",purpose).put("created",created).toString()
    fun matches(state: String, expectedPurpose: String, now: Long): Boolean = state.isNotBlank() && java.security.MessageDigest.isEqual(nonce.toByteArray(), state.toByteArray()) && purpose == expectedPurpose && now - created in 0..600000
    companion object {
        fun create(purpose: String) = PendingAuth(ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) },purpose,System.currentTimeMillis())
        fun parse(value: String): PendingAuth? = runCatching { JSONObject(value).let { PendingAuth(it.getString("nonce"),it.getString("purpose"),it.getLong("created")) } }.getOrNull()
    }
}
fun callbackParameters(uri: URI): Map<String,String> {
    val result = mutableMapOf<String,String>()
    listOfNotNull(uri.rawQuery, uri.rawFragment).flatMap { it.split('&') }.filter { it.isNotBlank() }.forEach {
        val pair = it.split('=',limit=2)
        val key = URLDecoder.decode(pair[0],"UTF-8")
        require(!result.containsKey(key)) { "重複したコールバック項目です" }
        result[key] = URLDecoder.decode(pair.getOrElse(1) { "" },"UTF-8")
    }
    return result
}
class LocalTranslator {
    private val identifier = LanguageIdentification.getClient()
    suspend fun japanese(message: String): String {
        if (message.isBlank()) return ""
        val language = identifier.identifyLanguage(message).await()
        if(language == "ja" || language == "und") return ""
        val source = TranslateLanguage.fromLanguageTag(language) ?: return ""
        val translator = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(TranslateLanguage.JAPANESE).build())
        return try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            translator.translate(message).await()
        } finally { translator.close() }
    }
    fun close() = identifier.close()
}
