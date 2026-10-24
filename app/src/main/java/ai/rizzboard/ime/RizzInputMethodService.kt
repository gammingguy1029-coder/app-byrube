package ai.rizzboard.ime

import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import ai.rizzboard.AppGraph
import ai.rizzboard.ai.AiResult
import ai.rizzboard.ai.WritingModes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Real Android IME. All target-field edits go through InputConnection; no accessibility scraping.
 *
 * Performance note: the view tree is rebuilt by render() only when the keyboard shape
 * actually changes (focus change, AI panel toggle, mode change). The per-keystroke path
 * never allocates views or drawables: it commits text directly and updates only the
 * affected key views in place.
 */
class RizzInputMethodService : InputMethodService() {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var root: LinearLayout? = null
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
    private var keyScale = 1f
    private var haptics = true

    // Cached key views — assigned when render() builds the keyboard, used for
    // in-place updates that avoid a full rebuild on shift / layout toggles.
    private var shiftKey: TextView? = null
    private var qKey: TextView? = null          // the ?123 / ABC toggle
    private var spaceKey: TextView? = null
    private var enterKey: TextView? = null
    private var lettersHost: LinearLayout? = null
    private var symbolsHost: LinearLayout? = null
    private val letterViews = ArrayList<Pair<TextView, Char>>(26)

    override fun onCreate() { super.onCreate(); val settings = AppGraph.store(this).settings(); mode = WritingModes.get(settings.modeId); creative = settings.creativity; keyScale = settings.keyScale; haptics = settings.haptics }

    /** Never squash the editor (fullscreen extract) — keeps the keyboard at the bottom in landscape. */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(12)); background = bg(0xFFF4F1FA.toInt(), 20) }
        root = layout
        // Inset handling: the IME window is edge-to-edge on Android 15 (targetSdk 35),
        // so the bottom row otherwise sits under the gesture bar / three-button nav.
        // Pad by exactly the reported system-bar bottom inset; on versions/oems where
        // the window already clears the nav bar this reports 0 and nothing is added,
        // which prevents double-applied padding.
        ViewCompat.setOnApplyWindowInsetsListener(layout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(dp(10), dp(10), dp(10), dp(12) + bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        render()
        return layout
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        val settings = AppGraph.store(this).settings()
        creative = settings.creativity
        keyScale = settings.keyScale
        haptics = settings.haptics
        if (!restarting) {
            mode = WritingModes.get(settings.modeId)
            aiVisible = false; busy = false; job?.cancel()
            inputText = ""; generated = ""; status = ""; lastApplied = null
            // Open in the number/symbol layout when the editor asks for it
            // (phone, numeric, date/time fields). Password keeps the letter layout.
            val cls = attribute?.inputType?.and(InputType.TYPE_MASK_CLASS) ?: 0
            numbers = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME
            shifted = true
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        render()
    }

    override fun onDestroy() { job?.cancel(); uiScope.coroutineContext[Job]?.cancel(); super.onDestroy() }

    // ---------------------------------------------------------------- layout

    private fun render() {
        val container = root ?: return
        container.removeAllViews()
        letterViews.clear()
        shiftKey = null; qKey = null; spaceKey = null; enterKey = null; lettersHost = null; symbolsHost = null

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(4), dp(5)) }
        val brand = TextView(this).apply { text = "r."; setTextColor(0xFF7054D9.toInt()); setTypeface(null, Typeface.BOLD); setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f); setPadding(dp(7), 0, dp(6), 0) }
        bar.addView(brand)
        val modeBtn = smallButton("${mode.title}  ▾", true) { showModes(it) }
        bar.addView(modeBtn, LinearLayout.LayoutParams(0, dp(46), 1f))
        bar.addView(smallButton(if (aiVisible) "⌄  Keyboard" else "✦  Rephrase", false) { if (aiVisible) { aiVisible = false; job?.cancel(); busy = false } else { aiVisible = true; if (generated.isEmpty()) { inputText = ""; status = "" } }; render() })
        container.addView(bar)

        if (aiVisible) renderAiPanel(container) else renderKeys(container)
        updateCaseLabels()
        updateBottomLabels()
    }

    /** Both row groups are built upfront and switched by visibility — layout
     *  toggling between letters/symbols allocates nothing at keypress time. */
    private fun renderKeys(parent: LinearLayout) {
        val rowsHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val letters = buildLetterRows()
        val symbols = buildSymbolRows()
        symbols.visibility = if (numbers) View.VISIBLE else View.GONE
        letters.visibility = if (numbers) View.GONE else View.VISIBLE
        rowsHost.addView(symbols)
        rowsHost.addView(letters)
        lettersHost = letters
        symbolsHost = symbols
        parent.addView(rowsHost)
        parent.addView(buildBottomRow())
    }

    private fun buildLetterRows(): LinearLayout {
        val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row1 = letterRow(dp(4))
        "qwertyuiop".forEach { c -> row1.addView(letterKey(c), rowParams(1f)) }
        host.addView(row1)

        val row2 = letterRow(dp(4))
        // Half-key side spacers center the 9-key row under the 10-key row.
        addSpacer(row2, 0.5f)
        "asdfghjkl".forEach { c -> row2.addView(letterKey(c), rowParams(1f)) }
        addSpacer(row2, 0.5f)
        host.addView(row2)

        val row3 = letterRow(dp(2))
        val shift = actionKey("⇧")
        shiftKey = shift
        row3.addView(shift, rowParams(1.5f))
        "zxcvbnm".forEach { c -> row3.addView(letterKey(c), rowParams(1f)) }
        row3.addView(deleteKey(), rowParams(1.5f))
        host.addView(row3)
        return host
    }

    private fun buildSymbolRows(): LinearLayout {
        val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val r1 = "1234567890"
        val r2 = "@#\$%&-+()/"
        val r3 = listOf("=", "*", "\"", "'", ":", ";", "!", "?")
        val l1 = letterRow(dp(4)); r1.forEach { addSinkKey(l1, it.toString(), 1f) }; host.addView(l1)
        val l2 = letterRow(dp(4)); addSpacer(l2, 0.5f); r2.forEach { addSinkKey(l2, it.toString(), 1f) }; addSpacer(l2, 0.5f); host.addView(l2)
        val l3 = letterRow(dp(2)); addSpacer(l3, 0.5f); r3.forEach { addSinkKey(l3, it, 1f) }; l3.addView(deleteKey(), rowParams(1.5f)); addSpacer(l3, 0.5f); host.addView(l3)
        return host
    }

    /** Bottom row: [?123] [globe] [,] [space] [.] [enter] */
    private fun buildBottomRow(): LinearLayout {
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(4), dp(6), dp(4), dp(2)) }
        qKey = layoutKey(if (numbers) "ABC" else "?123", true, keyDp(60)) { toggleNumbers() }
        bottom.addView(qKey, rowParams(1.4f, 60))
        bottom.addView(layoutKey("🌐", true, keyDp(60)) { pickOtherKeyboard() }, rowParams(0.9f, 60))
        bottom.addView(layoutKey(",", false, keyDp(60)) { commitText(",") }, rowParams(0.65f, 60))
        spaceKey = layoutKey(if (numbers) "␣" else "English (US)   ␣", false, keyDp(60)) { commitText(" ") }
        bottom.addView(spaceKey, rowParams(4.3f, 60))
        bottom.addView(layoutKey(".", false, keyDp(60)) { commitText(".") }, rowParams(0.65f, 60))
        enterKey = layoutKey(enterLabel(), true, keyDp(60)) { enter() }
        bottom.addView(enterKey, rowParams(1.5f, 60))
        return bottom
    }

    // ------------------------------------------------------------- key plumbing

    private fun letterRow(padTop: Int): LinearLayout =
        LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(4), padTop, dp(4), dp(4)) }

    private fun addSpacer(row: LinearLayout, w: Float) = row.addView(View(this), rowParams(w))

    private fun letterKey(c: Char): TextView {
        val view = layoutKey(c.toString(), false, keyDp(58)) { commitLetter(c) }
        letterViews.add(view to c)
        return view
    }

    private fun deleteKey(): TextView = layoutKey("⌫", true, keyDp(58), repeatable = true) { backspace() }

    private fun actionKey(label: String): TextView = layoutKey(label, true, keyDp(58)) { shifted = !shifted; updateCaseLabels() }

    private fun addSinkKey(row: LinearLayout, label: String, w: Float) {
        row.addView(layoutKey(label, false, keyDp(58)) { commitText(label) }, rowParams(w))
    }

    private fun rowParams(w: Float, baseHeight: Int = 58) = LinearLayout.LayoutParams(0, keyDp(baseHeight), w).apply { setMargins(dp(2), 0, dp(2), 0) }

    /**
     * Single key factory. Touch feedback, haptics and (optional) hold-to-repeat
     * are wired once here. The action runs on ACTION_UP; the typing path after
     * that only touches InputConnection and the one or two views that change.
     */
    private fun layoutKey(text: String, special: Boolean, height: Int, repeatable: Boolean = false, action: () -> Unit): TextView {
        val idleColor = if (special) 0xFFE4DDF2.toInt() else Color.WHITE
        val pressedColor = if (special) 0xFFCBBBEF.toInt() else 0xFFE9E4F2.toInt()
        val view = TextView(this).apply {
            this.text = text
            textSize = when { text.length > 5 -> 13.5f; special -> 19f; else -> 22f }
            gravity = Gravity.CENTER
            setTextColor(if (special) 0xFF514B63.toInt() else 0xFF282438.toInt())
            background = bg(idleColor, 10)
            elevation = 1.2f * resources.displayMetrics.density
        }
        var repeat: Runnable? = null
        view.setOnTouchListener { v, event ->
            val bgDrawable = v.background as? GradientDrawable
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    bgDrawable?.setColor(pressedColor)
                    if (haptics) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    if (repeatable) {
                        action()
                        val r = object : Runnable {
                            override fun run() { action(); v.postDelayed(this, 60) }
                        }
                        repeat = r
                        v.postDelayed(r, 350)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    bgDrawable?.setColor(idleColor)
                    repeat?.let { v.removeCallbacks(it) }; repeat = null
                    if (!repeatable) action()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    bgDrawable?.setColor(idleColor)
                    repeat?.let { v.removeCallbacks(it) }; repeat = null
                    true
                }
                else -> false
            }
        }
        return view
    }

    // ------------------------------------------------------ in-place updaters

    /** Shift state visual + letter case labels, without rebuilding the keyboard. */
    private fun updateCaseLabels() {
        val upper = shifted
        for ((view, c) in letterViews) view.text = (if (upper) c.uppercaseChar() else c).toString()
        shiftKey?.let { s ->
            (s.background as? GradientDrawable)?.setColor(if (upper) 0xFF7054D9.toInt() else 0xFFE4DDF2.toInt())
            s.setTextColor(if (upper) Color.WHITE else 0xFF514B63.toInt())
        }
    }

    private fun updateBottomLabels() {
        qKey?.text = if (numbers) "ABC" else "?123"
        spaceKey?.text = if (numbers) "␣" else "English (US)   ␣"
        enterKey?.text = enterLabel()
    }

    private fun toggleNumbers() {
        numbers = !numbers
        lettersHost?.visibility = if (numbers) View.GONE else View.VISIBLE
        symbolsHost?.visibility = if (numbers) View.VISIBLE else View.GONE
        updateBottomLabels()
    }

    // -------------------------------------------------------------- typing path

    private fun commitLetter(c: Char) {
        val ic = currentInputConnection ?: return
        val ch = if (shifted) c.uppercaseChar() else c
        ic.commitText(ch.toString(), 1)
        if (shifted) { shifted = false; updateCaseLabels() }
    }

    private fun commitText(s: String) {
        currentInputConnection?.commitText(s, 1)
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

    private fun pickOtherKeyboard() {
        try { getSystemService(InputMethodManager::class.java)?.showInputMethodPicker() } catch (_: Exception) {}
    }

    // --------------------------------------------------------------- AI panel

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

    // ----------------------------------------------------------------- chrome

    private fun showModes(anchor: View) {
        val popup = PopupMenu(this, anchor)
        WritingModes.all.forEachIndexed { index, m -> popup.menu.add(0, index, index, m.title) }
        popup.setOnMenuItemClickListener { item -> mode = WritingModes.all[item.itemId]; creative = AppGraph.store(this).settings().creativity; render(); true }
        popup.show()
    }

    private fun launchSettings() { try { val i = packageManager.getLaunchIntentForPackage(packageName); i?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i) } catch (_: Exception) {} }

    private fun smallButton(text: String, primary: Boolean, click: (View) -> Unit) = TextView(this).apply {
        this.text = text; textSize = 14.5f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
        setTextColor(if (primary) Color.WHITE else 0xFF514B63.toInt()); background = bg(if (primary) 0xFF7054D9.toInt() else Color.WHITE, 12)
        setPadding(dp(12), dp(9), dp(12), dp(9)); setOnClickListener { click(this) }
    }

    private fun label(text: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply { this.text = text; textSize = size.toFloat(); setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD); setPadding(dp(5), dp(5), dp(5), dp(5)) }
    private fun bg(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    /** Key heights scale with the "Key size" setting in the app (85%–125%). */
    private fun keyDp(value: Int) = (value * keyScale * resources.displayMetrics.density).toInt()

    private data class EditSnapshot(val text: String, val wasSelection: Boolean)
    private data class AppliedSnapshot(val original: EditSnapshot, val generated: String)
}
