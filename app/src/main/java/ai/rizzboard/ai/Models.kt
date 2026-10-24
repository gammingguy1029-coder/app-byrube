package ai.rizzboard.ai

import org.json.JSONObject
import java.util.UUID

data class WritingMode(val id: String, val title: String, val instruction: String, val coach: Boolean = false)

object WritingModes {
    val all = listOf(
        WritingMode("fix", "Fix English", "Correct grammar, spelling, and awkward phrasing while preserving the writer's voice."),
        WritingMode("normal", "Normal", "Make it sound natural, relaxed, and conversational."),
        WritingMode("flirty", "Flirty", "Make it lightly, respectfully playful and flirtatious. Keep it age-appropriate and never pushy."),
        WritingMode("funny", "Funny", "Add a little wit or humor while keeping the message plausible and kind."),
        WritingMode("confident", "Confident", "Sound self-assured and clear without arrogance or pressure."),
        WritingMode("teasing", "Teasing", "Use friendly, affectionate banter without insults, cruelty, or targeting vulnerabilities."),
        WritingMode("dry", "Dry Humor", "Use subtle, understated humor; avoid overexplaining the joke."),
        WritingMode("genz", "Gen Z", "Use contemporary casual language only where it fits naturally; do not force slang."),
        WritingMode("short", "Short Reply", "Make this concise, natural, and easy to send in a chat."),
        WritingMode("sweet", "Sweet", "Make it warm, thoughtful, and sincere without becoming sentimental."),
        WritingMode("reply", "Reply Helper", "Suggest a natural response to the incoming message included by the user. Keep it relevant, concise, and do not invent shared history."),
        WritingMode("coach", "Conversation Coach", "Give a brief, supportive explanation of conversational options, then provide one example message. Help the user learn; do not judge them.", true)
    )
    fun get(id: String) = all.firstOrNull { it.id == id } ?: all[1]
}

data class ProviderConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "My provider",
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "",
    val apiKey: String = "",
    val headers: Map<String, String> = emptyMap(),
    val timeoutSeconds: Int = 30,
    val enabled: Boolean = true
) {
    fun toJson(includeSecret: Boolean = true): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("baseUrl", baseUrl).put("model", model)
        .put("apiKey", if (includeSecret) apiKey else "")
        .put("headers", JSONObject().also { o -> if (includeSecret) headers.forEach { (k, v) -> o.put(k, v) } })
        .put("timeoutSeconds", timeoutSeconds).put("enabled", enabled)

    companion object {
        fun fromJson(o: JSONObject) = ProviderConfig(
            id = o.optString("id", UUID.randomUUID().toString()), name = o.optString("name", "Provider"),
            baseUrl = o.optString("baseUrl", ""), model = o.optString("model", ""),
            apiKey = o.optString("apiKey", ""), headers = buildMap {
                val h = o.optJSONObject("headers") ?: JSONObject()
                h.keys().forEach { key -> put(key, h.optString(key)) }
            }, timeoutSeconds = o.optInt("timeoutSeconds", 30).coerceIn(5, 120), enabled = o.optBoolean("enabled", true)
        )
    }
}

data class AppSettings(val modeId: String = "normal", val creativity: Float = 0.35f, val defaultProviderId: String? = null)

sealed class AiResult {
    data class Success(val text: String) : AiResult()
    data class Failure(val message: String) : AiResult()
}
