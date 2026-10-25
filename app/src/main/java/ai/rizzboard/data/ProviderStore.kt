package ai.rizzboard.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import ai.rizzboard.ai.AppSettings
import ai.rizzboard.ai.ProviderConfig
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Provider metadata lives in app-private preferences; API keys are AES/GCM encrypted with a non-exportable Android Keystore key. */
class ProviderStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("rizzboard_private", Context.MODE_PRIVATE)
    private val alias = "rizzboard.provider.aes.v1"

    @Synchronized fun providers(): List<ProviderConfig> {
        val arr = try { JSONArray(prefs.getString("providers", "[]")) } catch (_: Exception) { JSONArray() }
        return buildList {
            for (i in 0 until arr.length()) {
                val raw = try { ProviderConfig.fromJson(arr.getJSONObject(i)) } catch (_: Exception) { continue }
                val secret = decryptSecret(prefs.getString("secret_${raw.id}", null))
                add(raw.copy(apiKey = secret.first, headers = secret.second))
            }
        }
    }

    /** Encrypt first, then persist metadata + secret in one atomic write. Returns
     *  false (with nothing modified) if secure storage fails — e.g. a device whose
     *  AndroidKeyStore misbehaves — instead of crashing the settings UI. */
    @Synchronized fun saveProvider(provider: ProviderConfig): Boolean {
        return try {
            val list = providers().filterNot { it.id == provider.id } + provider
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson(includeSecret = false)) }
            val headerJson = JSONObject().also { o -> provider.headers.forEach { (k, v) -> o.put(k, v) } }
            val secrets = JSONObject().put("apiKey", provider.apiKey).put("headers", headerJson).toString()
            val encrypted = encrypt(secrets) // may throw on Keystore problems
            prefs.edit().putString("providers", arr.toString()).putString("secret_${provider.id}", encrypted).apply()
            true
        } catch (_: Exception) { false }
    }

    @Synchronized fun deleteProvider(id: String) {
        val arr = JSONArray()
        providers().filterNot { it.id == id }.forEach { arr.put(it.toJson(includeSecret = false)) }
        prefs.edit().putString("providers", arr.toString()).remove("secret_$id").apply()
        if (settings().defaultProviderId == id) setSettings(settings().copy(defaultProviderId = null))
    }

    fun settings(): AppSettings {
        val s = try { JSONObject(prefs.getString("settings", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        return AppSettings(
            s.optString("mode", "normal"),
            s.optDouble("creativity", .35).toFloat().coerceIn(0f, 1f),
            s.optString("provider", "").takeIf { it.isNotBlank() },
            s.optDouble("keyScale", 1.0).toFloat().coerceIn(0.85f, 1.25f),
            s.optBoolean("haptics", true)
        )
    }

    fun setSettings(value: AppSettings) {
        prefs.edit().putString("settings", JSONObject()
            .put("mode", value.modeId)
            .put("creativity", value.creativity)
            .put("provider", value.defaultProviderId ?: "")
            .put("keyScale", value.keyScale.toDouble())
            .put("haptics", value.haptics)
            .toString()).apply()
    }

    fun activeProvider(): ProviderConfig? {
        val all = providers()
        val selected = settings().defaultProviderId?.let { id -> all.firstOrNull { it.id == id && it.enabled } }
        return selected ?: all.firstOrNull { it.enabled }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decryptSecret(value: String?): Pair<String, Map<String, String>> {
        if (value.isNullOrEmpty()) return "" to emptyMap()
        return try {
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), StandardCharsets.UTF_8))
            val headersJson = json.optJSONObject("headers") ?: JSONObject()
            val headers = buildMap { headersJson.keys().forEach { put(it, headersJson.optString(it)) } }
            json.optString("apiKey", "") to headers
        } catch (_: Exception) { "" to emptyMap() }
    }
}
