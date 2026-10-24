package ai.rizzboard.ime

import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import ai.rizzboard.AppGraph
import ai.rizzboard.ai.AiResult
import ai.rizzboard.ai.WritingModes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Real Android IME. All target-field edits go through InputConnection; no accessibility scraping. */
class RizzInputMethodService : InputMethodService() {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var root: LinearLayout? = null
    private var editor: LinearLayout? = null
    private var mode = WritingModes.get("normal")
    private var numbers = false
    private var shifted = true
    private var aiVisible = false
    private var busy = false
    private var job: Job? = null
    private var inputText = ""
    private var generated = ""
    private var status = ""
    private var snapshot = EditSnapshot("", false)
    private var lastApplied: AppliedSnapshot? = null
    private var creative = 0.35f

    override fun onCreate() { super.onCreate(); val settings = AppGraph.store(this).settings(); mode = WritingModes.get(settings.modeId); creative = settings.creativity }
    override fun onCreateInputView(): View = buildKeyboard()
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        creative = AppGraph.store(this).settings().creativity
        if (!restarting) { mode = WritingModes.get(AppGraph.store(this).settings().modeId); aiVisible = false; busy = false; job?.cancel(); inputText = ""; generated = ""; status = ""; lastApplied = null }
    }
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        render()
    }

    override fun onDestroy() { job?.cancel(); uiScope.coroutineContext[Job]?.cancel(); super.onDestroy() }

    private fun buildKeyboard(): View {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(12)); background = bg(0xFFF4F1FA.toInt(), 20) }
        root = layout
        render()
        return layout
    }

    private fun render() {
        val container = root ?: return
        container.removeAllViews()
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(4), dp(5)) }
        val brand = TextView(this).apply { text = "r."; setTextColor(0xFF7054D9.toInt()); setTypeface(null, Typeface.BOLD); setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f); setPadding(dp(7), 0, dp(6), 0) }
        bar.addView(brand)
        val modeBtn = smallButton("${mode.title}  ▾", true) { showModes(it) }
        bar.addView(modeBtn, LinearLayout.LayoutParams(0, dp(46), 1f))
        bar.addView(smallButton(if (aiVisible) "⌄  Keyboard" else "✦  Rephrase", false) { if (aiVisible) { aiVisible = false; job?.cancel(); busy = false } else { aiVisible = true; if (generated.isEmpty()) { inputText = ""; status = "" } }; render() })
        container.addView(bar)
        if (aiVisible) renderAiPanel(container) else renderKeys(container)
    }

    private fun renderAiPanel(parent: LinearLayout) {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(7), dp(7), dp(7), dp(4)) }
        val provider = AppGraph.store(this).activeProvider()
        if (provider == null) {
            col.addView(label("Add and select an AI provider in RizzBoard settings to use rephrasing. Your regular keyboard still works.", 13, 0xFF5D586B.toInt()))
            col.addView(smallButton("Open settings", true) { launchSettings() })
        } else if (generated.isNotEmpty()) {
            col.addView(label("Preview · ${mode.title}", 13, 0xFF766F83.toInt(), true))
            col.addView(label(generated, 17, 0xFF25213A.toInt()))
            if (status.isNotBlank()) col.addView(label(status, 13, 0xFFB24454.toInt()))
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            actions.addView(smallButton("Apply", true) { applyGenerated() }, LinearLayout.LayoutParams(0, dp(50), 1f))
            actions.addView(smallButton("Regenerate", false) { requestRewrite() }, LinearLayout.LayoutParams(0, dp(50), 1f))
            actions.addView(smallButton("Restore original", false) { applyOriginal() }, LinearLayout.LayoutParams(0, dp(50), 1f))
            col.addView(actions)
            col.addView(smallButton("Discard preview", false) { generated = ""; status = ""; render() })
        } else {
            col.addView(label("Rephrase text you selected, or the text immediately before the cursor.", 14, 0xFF5D586B.toInt()))
            val available = inputText.ifBlank { readAvailableText() }
            col.addView(label(if (available.isBlank()) "Type in the message field, then come back here. You can also select text to rephrase only that part." else "Text to rephrase · ${available.take(150)}${if (available.length > 150) "…" else ""}", 15, 0xFF343047.toInt()))
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            actions.addView(smallButton(if (busy) "Cancel" else "✦  Rephrase", true) { if (busy) cancelRequest() else requestRewrite() }, LinearLayout.LayoutParams(0, dp(52), 1f))
            actions.addView(smallButton("Keyboard", false) { aiVisible = false; render() }, LinearLayout.LayoutParams(0, dp(52), 0.65f))
            col.addView(actions)
            if (lastApplied != null) col.addView(smallButton("Restore previously replaced text", false) { restoreLastApplied() })
            if (busy) col.addView(label("Working…", 13, 0xFF7054D9.toInt()))
            if (status.isNotBlank()) col.addView(label(status, 12, 0xFFB24454.toInt()))
        }
        scroll.addView(col)
        parent.addView(scroll, LinearLayout.LayoutParams(-1, dp(290)))
    }

    private fun renderKeys(parent: LinearLayout) {
        val rows: List<List<String>> = if (numbers) listOf(listOf("1","2","3","4","5","6","7","8","9","0"), listOf("@","#","$","%","&","-","+","(",")","/"), listOf("=","*","\"","'",":",";","!","?","⌫"))
            else listOf("qwertyuiop".map { it.toString() }, "asdfghjkl".map { it.toString() }, listOf("⇧") + "zxcvbnm".map { it.toString() } + listOf("⌫"))
        rows.forEachIndexed { rowIndex, row ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(3), dp(4), dp(3), dp(4)) }
            row.forEachIndexed { idx, key ->
                val special = key in listOf("⇧", "⌫")
                val actual = if (shifted && key.length == 1 && key[0].isLetter()) key.uppercase() else key
                val view = keyButton(actual, special) {
                    when (key) {
                        "⇧" -> { shifted = !shifted; render() }
                        "⌫" -> backspace()
                        else -> { currentInputConnection?.commitText(actual, 1); if (shifted) shifted = false }
                    }
                }
                val weight = if (special) 1.45f else 1f
                line.addView(view, LinearLayout.LayoutParams(0, dp(58), weight).apply { setMargins(dp(3), 0, dp(3), 0) })
            }
            parent.addView(line)
        }
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(3), dp(5), dp(3), dp(2)) }
        bottom.addView(keyButton(if (numbers) "ABC" else "?123", true) { numbers = !numbers; render() }, LinearLayout.LayoutParams(0, dp(60), 1.45f).apply { setMargins(dp(3),0,dp(3),0) })
        bottom.addView(keyButton(",", false) { currentInputConnection?.commitText(",", 1) }, LinearLayout.LayoutParams(0, dp(60), .65f).apply { setMargins(dp(3),0,dp(3),0) })
        bottom.addView(keyButton("English (US)     ␣", false) { currentInputConnection?.commitText(" ", 1) }, LinearLayout.LayoutParams(0, dp(60), 5f).apply { setMargins(dp(3),0,dp(3),0) })
        bottom.addView(keyButton(".", false) { currentInputConnection?.commitText(".", 1) }, LinearLayout.LayoutParams(0, dp(60), .65f).apply { setMargins(dp(3),0,dp(3),0) })
        bottom.addView(keyButton(enterLabel(), true) { enter() }, LinearLayout.LayoutParams(0, dp(60), 1.35f).apply { setMargins(dp(3),0,dp(3),0) })
        parent.addView(bottom)
    }

    private fun requestRewrite() {
        val ic = currentInputConnection ?: run { status = "This field isn't available for editing."; render(); return }
        val selected = try { ic.getSelectedText(0)?.toString().orEmpty() } catch (_: Exception) { "" }
        if (selected.isNotEmpty()) {
            if (selected.length > 4000) { status = "Select a shorter passage (up to 4,000 characters)."; render(); return }
            snapshot = EditSnapshot(selected, true)
            inputText = selected
        } else {
            val before = try { ic.getTextBeforeCursor(4000, 0)?.toString().orEmpty() } catch (_: Exception) { "" }
            snapshot = EditSnapshot(before.takeLast(4000), false)
            inputText = snapshot.text
        }
        if (inputText.isBlank()) { status = "Type a message or select text first."; render(); return }
        val provider = AppGraph.store(this).activeProvider()
        if (provider == null) { status = "No enabled provider configured."; render(); return }
        val activeMode = mode
        val input = inputText.take(4000)
        busy = true; generated = ""; status = ""; render()
        job?.cancel()
        job = uiScope.launch {
            when (val result = AppGraph.ai.generate(provider, activeMode, input, creative)) {
                is AiResult.Success -> { generated = result.text; status = "" }
                is AiResult.Failure -> { status = result.message }
            }
            busy = false; render()
        }
    }

    private fun cancelRequest() { job?.cancel(); job = null; busy = false; status = "Request cancelled."; render() }

    private fun applyGenerated() {
        if (generated.isBlank()) return
        if (replaceSnapshot(generated)) { lastApplied = AppliedSnapshot(snapshot, generated); generated = ""; status = "Applied. Review before sending."; aiVisible = false; render() }
        else { status = "The text field changed. Re-select the text and try again to avoid replacing the wrong text."; render() }
    }

    private fun applyOriginal() {
        if (replaceSnapshot(snapshot.text)) { generated = ""; aiVisible = false; status = "Original text restored."; render() }
        else { status = "The text field changed; original text wasn't replaced."; render() }
    }

    private fun restoreLastApplied() {
        val saved = lastApplied ?: return
        val ic = currentInputConnection ?: return
        val before = try { ic.getTextBeforeCursor(saved.generated.length, 0)?.toString().orEmpty() } catch (_: Exception) { "" }
        if (saved.generated.isEmpty() || !before.endsWith(saved.generated)) {
            status = "The edited text no longer matches the last result. It was left untouched."
        } else {
            ic.deleteSurroundingText(saved.generated.length, 0)
            ic.commitText(saved.original.text, 1)
            lastApplied = null
            status = "Original text restored."
        }
        render()
    }

    private fun readAvailableText(): String {
        val ic = currentInputConnection ?: return ""
        return try { ic.getSelectedText(0)?.toString()?.takeIf { it.isNotEmpty() } ?: ic.getTextBeforeCursor(4000, 0)?.toString().orEmpty().takeLast(4000) } catch (_: Exception) { "" }
    }

    private fun replaceSnapshot(value: String): Boolean {
        val ic = currentInputConnection ?: return false
        return try {
            if (snapshot.wasSelection) {
                val current = ic.getSelectedText(0)?.toString().orEmpty()
                if (current != snapshot.text) return false
                ic.commitText(value, 1)
            } else {
                val before = ic.getTextBeforeCursor(snapshot.text.length, 0)?.toString().orEmpty()
                if (!before.endsWith(snapshot.text)) return false
                ic.deleteSurroundingText(snapshot.text.length, 0)
                ic.commitText(value, 1)
            }
            true
        } catch (_: Exception) { false }
    }

    private fun showModes(anchor: View) {
        val popup = PopupMenu(this, anchor)
        WritingModes.all.forEachIndexed { index, m -> popup.menu.add(0, index, index, m.title) }
        popup.setOnMenuItemClickListener { item -> mode = WritingModes.all[item.itemId]; creative = AppGraph.store(this).settings().creativity; render(); true }
        popup.show()
    }
    private fun backspace() {
        val ic = currentInputConnection ?: return
        val selected = try { ic.getSelectedText(0) } catch (_: Exception) { null }
        if (!selected.isNullOrEmpty()) ic.commitText("", 1) else {
            val before = try { ic.getTextBeforeCursor(2, 0)?.toString().orEmpty() } catch (_: Exception) { "" }
            val chars = if (before.length >= 2 && Character.isSurrogatePair(before[before.length - 2], before[before.length - 1])) 2 else 1
            ic.deleteSurroundingText(chars, 0)
        }
    }
    private fun enter() {
        val ic = currentInputConnection ?: return
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) ic.performEditorAction(action) else ic.commitText("\n", 1)
    }
    private fun enterLabel() = when (currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)) {
        EditorInfo.IME_ACTION_GO -> "Go"; EditorInfo.IME_ACTION_SEARCH -> "⌕"; EditorInfo.IME_ACTION_SEND -> "➤"; EditorInfo.IME_ACTION_DONE -> "✓"; else -> "↵"
    }
    private fun launchSettings() { try { val i = packageManager.getLaunchIntentForPackage(packageName); i?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i) } catch (_: Exception) {} }
    private fun smallButton(text: String, primary: Boolean, click: (View) -> Unit) = TextView(this).apply {
        this.text = text; textSize = 14.5f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
        setTextColor(if (primary) Color.WHITE else 0xFF514B63.toInt()); background = bg(if (primary) 0xFF7054D9.toInt() else Color.WHITE, 12)
        setPadding(dp(12), dp(9), dp(12), dp(9)); setOnClickListener { click(this) }
    }
    private fun keyButton(text: String, special: Boolean, click: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = if (text.length > 5) 13.5f else 21f; gravity = Gravity.CENTER
        setTextColor(if (special) 0xFF514B63.toInt() else 0xFF282438.toInt())
        background = bg(if (special) 0xFFE4DDF2.toInt() else Color.WHITE, 10)
        elevation = dp(1).toFloat(); setOnClickListener { click() }
    }
    private fun label(text: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply { this.text = text; textSize = size.toFloat(); setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD); setPadding(dp(5), dp(5), dp(5), dp(5)) }
    private fun bg(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private data class EditSnapshot(val text: String, val wasSelection: Boolean)
    private data class AppliedSnapshot(val original: EditSnapshot, val generated: String)
}

