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
            "natural connectors, no stiff or essay-like sentences. Keep the sender's emotional temperature " +
            "legible on the surface — if the original feels excited, tired, or happy, the reader should feel it. " +
            "Same length or slightly shorter; add nothing the sender did not imply."
        ),
        WritingMode(
            "flirty", "Flirty",
            "Your job is not to polish the words — it is to make the person on the other end FEEL something: " +
            "butterflies, a smile she hides, the need to reread the message twice. Write charged, warm, high-intensity " +
            "flirting: the kind of banter where the teasing is real but the devotion underneath is even more obvious — " +
            "that electric half-rivalry, half-lovestruck dynamic where two people challenge each other and would still " +
            "drop everything for each other. Craft rules: anchor to ONE specific vivid detail from the conversation; " +
            "build playful tension, then land on a line of sincerity that hits; own the attraction confidently instead " +
            "of hinting at it; mirror the sender's interest and turn it up one notch; end on a hook that invites a reply. " +
            "Sparing tasteful emoji (😉😏😌🔥). Hard limits: adult, clearly consensual vibes, no explicit or graphic " +
            "content, nothing degrading, no pressure or demands, never invent shared memories or facts."
        ),
        WritingMode(
            "funny", "Funny",
            "Inject wit: a sharp observation, unexpected twist, or punchy one-liner with good comedic timing. " +
            "The reader should genuinely smile, not just notice that a joke was attempted. Humor stays plausible " +
            "for the conversation and kind toward everyone mentioned; one strong joke beats several weak ones."
        ),
        WritingMode(
            "confident", "Confident",
            "Make it self-assured and decisive: cut hedging (maybe, I think, kinda, sorry if...), favor strong " +
            "verbs and short declarative sentences. She should feel certainty radiating off the message — calm, " +
            "direct, zero neediness. Confidence without arrogance, pressure, or bravado."
        ),
        WritingMode(
            "teasing", "Teasing",
            "Tease so she FEELS adored through the tease: playful jabs, mock outrage, 'you wish' energy — and an " +
            "undercurrent of real care she cannot miss. Structure it like a comic beat: setup, jab, warm landing " +
            "line that shows the fondness was the point all along. Tease the situation or the message, never her " +
            "insecurities; the warmth must outweigh the jab so it reads as chemistry, not meanness."
        ),
        WritingMode(
            "dry", "Dry Humor",
            "Understated deadpan: deliver the point as if it were completely normal, flat delivery, no overexplaining. " +
            "No exclamation marks, no emoji, minimal punctuation drama; the humor comes from restraint and timing. " +
            "The reader gets the joke on the second beat."
        ),
        WritingMode(
            "genz", "Gen Z",
            "Contemporary casual internet-texting vibe: relaxed flow, light slang only where it lands naturally " +
            "(reactions like 'lowkey', 'fr', 'ngl' sparingly, never stacked). Make the mood feel current and casual, " +
            "not performed; if slang would sound try-hard in this sentence, stay plain."
        ),
        WritingMode(
            "short", "Short Reply",
            "Compress to the fewest natural words that keep the meaning AND the feeling — a short text can still " +
            "carry emotional weight if the one detail chosen is the right one. No filler; it should feel effortless " +
            "to send in a fast chat."
        ),
        WritingMode(
            "sweet", "Sweet",
            "Make her chest get warm, not just read something nice. Quiet, sincere emotion: one true specific " +
            "detail from what the sender actually said beats a paragraph of generic compliments. Gentle wording, " +
            "an unhurried line that feels brave to send, care without sentimental wallpaper. One honest warm beat " +
            "is stronger than three flowery ones."
        ),
        WritingMode(
            "reply", "Reply Helper",
            "Write a natural suggested reply to the incoming message the sender included. Keep it relevant and " +
            "concise, answer or acknowledge the actual content, let the sender's own feelings show through, and " +
            "do not invent shared history, plans, or emotions the sender has not expressed."
        ),
        WritingMode(
            "coach", "Conversation Coach",
            "Core lesson to teach: the goal is not converting text, it is making the other person FEEL something. " +
            "Give one brief supportive note on which emotion would land best here (butterflies, warmth, playful " +
            "challenge) and how one concrete detail carries it, then provide one example message that demonstrates " +
            "it. Friendly teacher energy, zero judgment, no lectures."
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
