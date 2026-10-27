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
import ai.rizzboard.ai.ProviderConfig
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
    private var emoji = false
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
    // Marker of the view tree currently built inside `root`. When it matches the
    // live config the tree can be reused without a full render() (see onStartInputView).
    private var builtFingerprint: String? = null

    // Cached key views — assigned when render() builds the keyboard, used for
    // in-place updates that avoid a full rebuild on shift / layout toggles.
    private var shiftKey: TextView? = null
    private var qKey: TextView? = null          // the ?123 / ABC toggle
    private var spaceKey: TextView? = null
    private var enterKey: TextView? = null
    private var lettersHost: LinearLayout? = null
    private var symbolsHost: LinearLayout? = null
    private var emojiHost: LinearLayout? = null
    private var emojiKey: TextView? = null        // bottom-row 😀 / ⌨ toggle
    /** Resolved lazily per input session (cleared in onStartInput) so repeated AI-panel
     *  renders don't re-run Keystore decryption on the UI thread. */
    private var cachedProvider: ProviderConfig? = null
    private var previewLabel: TextView? = null     // "Text to rephrase · …" label, updated in place
    private var availablePreview = ""              // last async field read for the preview label
    private var previewPending = false

    /** Cached active-provider lookup — one Keystore decrypt per input session max. */
    private fun currentProvider(): ProviderConfig? {
        if (cachedProvider == null) cachedProvider = AppGraph.store(this).activeProvider()
        return cachedProvider
    }

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
        // Providers can be edited in Settings between sessions — re-resolve lazily.
        cachedProvider = null
        availablePreview = ""; previewPending = false
        val settings = AppGraph.store(this).settings()
        creative = settings.creativity
        keyScale = settings.keyScale
        haptics = settings.haptics
        if (!restarting) {
            mode = WritingModes.get(settings.modeId)
            aiVisible = false; busy = false; job?.cancel()
            inputText = ""; generated = ""; status = ""; lastApplied = null
            emoji = false
            // Open in the number/symbol layout when the editor asks for it
            // (phone, numeric, date/time fields). Password keeps the letter layout.
            val cls = attribute?.inputType?.and(InputType.TYPE_MASK_CLASS) ?: 0
            numbers = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME
            shifted = true
        }
    }

    // Everything that could make a freshly built view tree differ from the
    // existing one. Anything NOT in this list (shifted, emoji, numbers, panel
    // text state) is handled in place by visibility /
    // updateShiftVisual/updateBottomLabels.
    private fun layoutFingerprint() = "${mode.id}|$keyScale|$haptics|$aiVisible"

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val container = root
        if (container != null && !aiVisible && builtFingerprint == layoutFingerprint()) {
            // Same mode, same settings, UI already built: skip the full
            // rebuild (removing + re-adding ~40 views is the visible lag
            // when focus hops between fields). onStartInput has already
            // reset shifted/emoji/numbers state — just re-sync the visuals.
            applyLayoutVisibility()
            updateShiftVisual()
            updateBottomLabels()
            return
        }
        render()
    }

    override fun onDestroy() { job?.cancel(); uiScope.coroutineContext[Job]?.cancel(); super.onDestroy() }

    // ---------------------------------------------------------------- layout

    private fun render() {
        val container = root ?: return
        container.removeAllViews()
        shiftKey = null; qKey = null; spaceKey = null; enterKey = null; lettersHost = null; symbolsHost = null; emojiHost = null; emojiKey = null; previewLabel = null

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(4), dp(5)) }
        val brand = TextView(this).apply { text = "r."; setTextColor(0xFF7054D9.toInt()); setTypeface(null, Typeface.BOLD); setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f); setPadding(dp(7), 0, dp(6), 0) }
        bar.addView(brand)
        val modeBtn = smallButton("${mode.title}  ▾", true) { showModes(it) }
        bar.addView(modeBtn, LinearLayout.LayoutParams(0, dp(46), 1f))
        bar.addView(smallButton(if (aiVisible) "⌄  Keyboard" else "✦  Rephrase", false) { if (aiVisible) { aiVisible = false; job?.cancel(); busy = false } else { aiVisible = true; if (generated.isEmpty()) { inputText = ""; status = "" } }; render() })
        container.addView(bar)

        if (aiVisible) renderAiPanel(container) else renderKeys(container)
        updateShiftVisual()
        updateBottomLabels()
        builtFingerprint = layoutFingerprint()
    }

    /** All three row groups are built upfront and switched by visibility — layout
     *  toggling between letters/symbols/emoji allocates nothing at keypress time. */
    private fun renderKeys(parent: LinearLayout) {
        val rowsHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val letters = buildLetterRows()
        val symbols = buildSymbolRows()
        val emojiPage = buildEmojiRows()
        symbols.visibility = if (numbers && !emoji) View.VISIBLE else View.GONE
        letters.visibility = if (!numbers && !emoji) View.VISIBLE else View.GONE
        emojiPage.visibility = if (emoji) View.VISIBLE else View.GONE
        rowsHost.addView(symbols)
        rowsHost.addView(letters)
        rowsHost.addView(emojiPage)
        lettersHost = letters
        symbolsHost = symbols
        emojiHost = emojiPage
        parent.addView(rowsHost)
        parent.addView(buildBottomRow())
    }

    /** Emoji grid — 4 rows of 10, same weight math as the symbol layout so every
     *  key is 1/10 wide and the columns align. The last row trades one slot for ⌫. */
    private fun buildEmojiRows(): LinearLayout {
        val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rows = listOf(
            listOf("😀", "😂", "🥹", "😊", "😍", "🤔", "😎", "😭", "😡", "🥳"),
            listOf("👍", "👏", "🙏", "💪", "✌️", "🫶", "❤️", "🔥", "💯", "🎉"),
            listOf("🐶", "🐱", "🍕", "☕", "📈", "⚽", "🎮", "🌙", "☀️", "🌈"),
            listOf("🌹", "🥀", "😴", "🤯", "🤣", "🥶", "⭐", "🎁", "💋")
        )
        rows.forEachIndexed { index, row ->
            val r = letterRow(dp(4))
            row.forEach { addSinkKey(r, it, 1f, big = true) }
            if (index == rows.lastIndex) r.addView(deleteKey(), rowParams(1f))
            host.addView(r)
        }
        return host
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
        // r2 holds exactly 10 symbols — no spacers, so every key is 1/10 wide and
        // column-aligned with the digit row above (all rows total weight 10).
        val l2 = letterRow(dp(4)); r2.forEach { addSinkKey(l2, it.toString(), 1f) }; host.addView(l2)
        val l3 = letterRow(dp(2)); r3.forEach { addSinkKey(l3, it, 1f) }; l3.addView(deleteKey(), rowParams(2f)); host.addView(l3)
        return host
    }

    /** Bottom row: [?123] [globe] [,] [😀] [space] [.] [enter] */
    private fun buildBottomRow(): LinearLayout {
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(4), dp(6), dp(4), dp(2)) }
        // Weights kept summing to 9.6; the emoji key squeezed the space bar down
        // to 36% of the row — rebalanced so space gets ~41% back without
        // resizing the label-bearing keys beyond readability.
        qKey = layoutKey(if (numbers || emoji) "ABC" else "?123", true) { toggleNumbers() }
        bottom.addView(qKey, rowParams(1.3f, 60))
        bottom.addView(layoutKey("🌐", true) { pickOtherKeyboard() }, rowParams(0.75f, 60))
        bottom.addView(layoutKey(",", false) { commitText(",") }, rowParams(0.65f, 60))
        emojiKey = layoutKey(if (emoji) "⌨" else "😀", true) { toggleEmoji() }
        bottom.addView(emojiKey, rowParams(0.8f, 60))
        spaceKey = layoutKey(if (numbers) "␣" else "English (US)   ␣", false) { commitText(" ") }
        bottom.addView(spaceKey, rowParams(3.95f, 60))
        bottom.addView(layoutKey(".", false) { commitText(".") }, rowParams(0.65f, 60))
        enterKey = layoutKey(enterLabel(), true) { enter() }
        bottom.addView(enterKey, rowParams(1.5f, 60))
        return bottom
    }

    // ------------------------------------------------------------- key plumbing

    private fun letterRow(padTop: Int): LinearLayout =
        LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(4), padTop, dp(4), dp(4)) }

    private fun addSpacer(row: LinearLayout, w: Float) = row.addView(View(this), rowParams(w))

    private fun letterKey(c: Char): TextView =
        layoutKey(c.uppercaseChar().toString(), false) { commitLetter(c) }

    private fun deleteKey(): TextView = layoutKey("⌫", true, repeatable = true) { backspace() }

    private fun actionKey(label: String): TextView = layoutKey(label, true) { shifted = !shifted; updateShiftVisual() }

    private fun addSinkKey(row: LinearLayout, label: String, w: Float, big: Boolean = false) {
        row.addView(layoutKey(label, false, textSizeOverride = if (big) 26f else 0f) { commitText(label) }, rowParams(w))
    }

    private fun rowParams(w: Float, baseHeight: Int = 58) = LinearLayout.LayoutParams(0, keyDp(baseHeight), w).apply { setMargins(dp(2), 0, dp(2), 0) }

    /**
     * Single key factory. Touch feedback, haptics and (optional) hold-to-repeat
     * are wired once here. Non-repeatable actions run through the view's click
     * listener (so TalkBack can activate keys) and only fire when the finger is
     * released inside the key — sliding off cancels the press.
     */
    private fun layoutKey(text: String, special: Boolean, repeatable: Boolean = false, textSizeOverride: Float = 0f, action: () -> Unit): TextView {
        val idleColor = if (special) 0xFFE4DDF2.toInt() else Color.WHITE
        val pressedColor = if (special) 0xFFCBBBEF.toInt() else 0xFFE9E4F2.toInt()
        val view = TextView(this).apply {
            this.text = text
            textSize = when {
                textSizeOverride > 0f -> textSizeOverride
                text.length > 5 -> 13.5f; special -> 19f; else -> 22f
            }
            gravity = Gravity.CENTER
            setTextColor(if (special) 0xFF514B63.toInt() else 0xFF282438.toInt())
            background = bg(idleColor, 10)
            // No elevation: per-key shadows force ~40 extra compositing layers per frame
            // (the jank Gboard-style keyboards avoid — they use flat keys).
            isClickable = true
        }
        // Click listener = the accessibility + release path. Touch events are fully
        // consumed below, so the framework never fires a second click on its own.
        view.setOnClickListener { action() }
        var repeat: Runnable? = null
        var inside = false
        fun inBounds(v: View, e: MotionEvent) = e.x >= 0f && e.x <= v.width.toFloat() && e.y >= 0f && e.y <= v.height.toFloat()
        view.setOnTouchListener { v, event ->
            val bgDrawable = v.background as? GradientDrawable
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    inside = true
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
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val nowInside = inBounds(v, event)
                    if (nowInside != inside) {
                        inside = nowInside
                        bgDrawable?.setColor(if (inside) pressedColor else idleColor)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val releasedInside = inBounds(v, event)
                    bgDrawable?.setColor(idleColor)
                    repeat?.let { v.removeCallbacks(it) }; repeat = null
                    // Capture the press state BEFORE clearing it — this flag must
                    // describe the press, not the reset happening below.
                    val pressTracked = inside
                    inside = false
                    if (pressTracked && releasedInside && !repeatable) v.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    bgDrawable?.setColor(idleColor)
                    repeat?.let { v.removeCallbacks(it) }; repeat = null
                    inside = false
                    true
                }
                else -> false
            }
        }
        return view
    }

    // ------------------------------------------------------ in-place updaters

    /** Shift-state highlight only — a single view. Letter labels are always
     *  uppercase (iOS-style), so the typing path never relabels the keyboard. */
    private fun updateShiftVisual() {
        shiftKey?.let { s ->
            (s.background as? GradientDrawable)?.setColor(if (shifted) 0xFF7054D9.toInt() else 0xFFE4DDF2.toInt())
            s.setTextColor(if (shifted) Color.WHITE else 0xFF514B63.toInt())
        }
    }

    private fun updateBottomLabels() {
        // On the emoji page the left key returns to letters, so label it ABC
        // to match what tapping it actually does.
        qKey?.text = if (numbers || emoji) "ABC" else "?123"
        emojiKey?.text = if (emoji) "⌨" else "😀"
        spaceKey?.text = if (numbers) "␣" else "English (US)   ␣"
        enterKey?.text = enterLabel()
    }

    /** In-place layout swap between letters / symbols / emoji — no rebuild. */
    private fun applyLayoutVisibility() {
        emojiHost?.visibility = if (emoji) View.VISIBLE else View.GONE
        lettersHost?.visibility = if (!emoji && !numbers) View.VISIBLE else View.GONE
        symbolsHost?.visibility = if (!emoji && numbers) View.VISIBLE else View.GONE
        updateBottomLabels()
    }

    private fun toggleNumbers() {
        // From the emoji page, ABC returns straight to letters.
        if (emoji) { emoji = false; numbers = false } else numbers = !numbers
        applyLayoutVisibility()
    }

    private fun toggleEmoji() {
        numbers = false
        emoji = !emoji
        applyLayoutVisibility()
    }

    // -------------------------------------------------------------- typing path

    private fun commitLetter(c: Char) {
        val ic = currentInputConnection ?: return
        val ch = if (shifted) c.uppercaseChar() else c
        ic.commitText(ch.toString(), 1)
        if (shifted) { shifted = false; updateShiftVisual() }
    }

    private fun commitText(s: String) {
        currentInputConnection?.commitText(s, 1)
    }

    /** Deletes one full text cluster backwards so emojis with variation selectors
     *  (❤️), skin-tone modifiers (👍🏽), ZWJ sequences (👨‍👩‍👦) and flags (🇺🇸)
     *  disappear in a single tap instead of leaving invisible leftovers. */
    private fun backspace() {
        val ic = currentInputConnection ?: return
        val selected = try { ic.getSelectedText(0) } catch (_: Exception) { null }
        if (!selected.isNullOrEmpty()) { ic.commitText("", 1); return }
        val before = try { ic.getTextBeforeCursor(32, 0)?.toString().orEmpty() } catch (_: Exception) { "" }
        if (before.isEmpty()) { ic.deleteSurroundingText(1, 0); return }

        var i = before.length
        var del = 0

        // 1) Trailing variation selectors / keycap marks / skin-tone modifiers
        //    belong to the base code point on their left — take them with it.
        while (i > 0) {
            val s = if (i >= 2 && Character.isSurrogatePair(before[i - 2], before[i - 1])) i - 2 else i - 1
            val cp = before.codePointAt(s)
            if (cp in 0xFE00..0xFE0F || cp == 0x20E3 || cp in 0x1F3FB..0x1F3FF) { del += i - s; i = s } else break
        }

        // 2) The base code point itself.
        var base = -1
        if (i > 0) {
            val s = if (i >= 2 && Character.isSurrogatePair(before[i - 2], before[i - 1])) i - 2 else i - 1
            base = before.codePointAt(s)
            del += i - s; i = s
        }

        // 3) ZWJ chains (👨‍👩‍👦) and flag pairs (🇺🇸) keep the cluster going leftward.
        while (i > 0) {
            val s = if (i >= 2 && Character.isSurrogatePair(before[i - 2], before[i - 1])) i - 2 else i - 1
            val cp = before.codePointAt(s)
            val continues = cp == 0x200D || (base in 0x1F1E6..0x1F1FF && cp in 0x1F1E6..0x1F1FF)
            if (!continues) break
            del += i - s; i = s
            if (cp == 0x200D && i > 0) {  // the ZWJ needs the base emoji on its left too
                val s2 = if (i >= 2 && Character.isSurrogatePair(before[i - 2], before[i - 1])) i - 2 else i - 1
                base = before.codePointAt(s2)
                del += i - s2; i = s2
            } else break
        }

        ic.deleteSurroundingText(del.coerceAtLeast(1), 0)
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
        val provider = currentProvider()
        if (provider == null) {
            col.addView(label("Add and select an AI provider in Rube Rizz settings to use rephrasing. Your regular keyboard still works.", 13, 0xFF5D586B.toInt()))
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
            // The field read (binder IPC) runs after render() completes — the panel
            // paints instantly with a cached/placeholder value, then the label updates in place.
            if (inputText.isBlank() && !previewPending) {
                previewPending = true
                uiScope.launch(Dispatchers.Main) {
                    availablePreview = readAvailableText()
                    previewPending = false
                    if (aiVisible && inputText.isBlank()) {
                        previewLabel?.let { lbl ->
                            if (lbl.isAttachedToWindow) lbl.text = previewAvailabilityText(availablePreview)
                        }
                    }
                }
            }
            previewLabel = label(previewAvailabilityText(inputText.ifBlank { availablePreview }), 15, 0xFF343047.toInt())
            col.addView(previewLabel)
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
        val provider = currentProvider()
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

    private fun previewAvailabilityText(text: String): String =
        if (text.isBlank()) "Type in the message field, then come back here. You can also select text to rephrase only that part."
        else "Text to rephrase · ${text.take(150)}${if (text.length > 150) "…" else ""}"

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
        popup.setOnMenuItemClickListener { item ->
            // Dismiss before re-rendering: render() tears down the view tree the
            // popup is anchored to, which can otherwise confuse the dismissing popup.
            popup.dismiss()
            mode = WritingModes.all[item.itemId]
            creative = AppGraph.store(this).settings().creativity
            render()
            true
        }
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
