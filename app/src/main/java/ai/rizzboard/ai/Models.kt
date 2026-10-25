package ai.rizzboard.ai

import org.json.JSONObject
import java.util.UUID

data class WritingMode(val id: String, val title: String, val instruction: String, val coach: Boolean = false)

object WritingModes {
    val all = listOf(
        WritingMode(
            "fix", "Fix English",
            "Correct grammar, spelling, punctuation, and awkward phrasing. Fix only what is actually " +
            "wrong or clunky in the original; do not restyle, expand, or shorten for taste. Keep slang, " +
            "capitalization quirks, and emoji when they are intentional. The result must clearly belong to the same writer."
        ),
        WritingMode(
            "normal", "Normal",
            "Rewrite so it reads like a fluent, relaxed text between people who chat often. Smooth rhythm, " +
            "natural connectors, no stiff or essay-like sentences. Keep it the same length or slightly shorter; " +
            "add nothing the sender did not imply."
        ),
        WritingMode(
            "flirty", "Flirty",
            "Make the message unmistakably flirty and magnetic — playful teasing, genuine compliments, " +
            "confident curiosity, and warm energy that clearly signals interest. Raise the temperature as high " +
            "as tasteful texting allows: cheeky, bold, a little provocative, tasteful emoji like 😉😏😊 when they fit. " +
            "Hard limits: no explicit or graphic sexual content, nothing degrading, no pressure or demands, " +
            "no possessiveness; keep it fun and clearly consensual-vibes for adults. Mirror the sender's apparent " +
            "interest level, never invent shared memories or facts."
        ),
        WritingMode(
            "funny", "Funny",
            "Inject wit: a sharp observation, unexpected twist, or punchy one-liner with good comedic timing. " +
            "Humor must stay plausible for the conversation and kind toward everyone mentioned. Prefer one strong " +
            "joke over several weak ones; no forced puns or cringe."
        ),
        WritingMode(
            "confident", "Confident",
            "Make it self-assured and decisive: cut hedging (maybe, I think, sorta, sorry if...), favor strong " +
            "verbs and short declarative sentences, state wants plainly. Confidence without arrogance, pressure, " +
            "or bravado — nothing that reads as demanding."
        ),
        WritingMode(
            "teasing", "Teasing",
            "Playful, affectionate banter: friendly jabs, mock outrage, callback-style humor such as 'oh really now' " +
            "energy. Tease the situation or the message, never the person's insecurities, and keep the underlying " +
            "warmth obvious so it can't read as mean."
        ),
        WritingMode(
            "dry", "Dry Humor",
            "Understated deadpan: deliver the point as if it were completely normal, flat delivery, resilience " +
            "against overexplaining. No exclamation marks, no emoji, minimal punctuation drama; the humor comes from " +
            "restraint and timing, not from labeling the joke."
        ),
        WritingMode(
            "genz", "Gen Z",
            "Contemporary casual internet-texting vibe: relaxed flow, light slang only where it lands naturally, " +
            "reactions like 'lowkey', 'fr', 'ngl' sparingly and never stacked. No cringe forcing, no dated slang; " +
            "if slang would sound try-hard in this sentence, stay plain."
        ),
        WritingMode(
            "short", "Short Reply",
            "Compress to the fewest natural words that keep the meaning and tone — ideally one clean sentence. " +
            "No filler, no pleasantries beyond what is necessary; it should feel effortless to send in a fast chat."
        ),
        WritingMode(
            "sweet", "Sweet",
            "Warm, thoughtful, sincere: gentle wording, soft appreciation, care without over-the-top sentimentality. " +
            "One honest warm beat is better than three flowery ones; keep it grounded in what the sender actually said."
        ),
        WritingMode(
            "reply", "Reply Helper",
            "Write a natural suggested reply to the incoming message the sender included. Keep it relevant and " +
            "concise, answer or acknowledge the actual content, and do not invent shared history, plans, or feelings " +
            "the sender has not expressed."
        ),
        WritingMode(
            "coach", "Conversation Coach",
            "Give one brief, supportive note on what makes this exchange tick (reading the room, timing, tone options), " +
            "then provide one concrete example message the user could send. Friendly teacher energy, zero judgment, " +
            "no lectures."
        )
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

data class AppSettings(val modeId: String = "normal", val creativity: Float = 0.35f, val defaultProviderId: String? = null, val keyScale: Float = 1f, val haptics: Boolean = true)

sealed class AiResult {
    data class Success(val text: String) : AiResult()
    data class Failure(val message: String) : AiResult()
}
