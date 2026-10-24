/*
 * Copyright (C) 2011 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package dev.drosh.ime.latin.suggestions

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.OnLongClickListener
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import dev.drosh.ime.event.HapticEvent
import dev.drosh.ime.keyboard.KeyboardSwitcher
import dev.drosh.ime.keyboard.KeyboardTypeface
import dev.drosh.ime.keyboard.internal.KeyboardIconsSet
import dev.drosh.ime.keyboard.internal.keyboard_parser.floris.KeyCode
import dev.drosh.ime.latin.AudioAndHapticFeedbackManager
import dev.drosh.ime.latin.dictionary.Dictionary
import dev.drosh.ime.latin.R
import dev.drosh.ime.latin.SuggestedWords
import dev.drosh.ime.latin.SuggestedWords.SuggestedWordInfo
import dev.drosh.ime.latin.common.ColorType
import dev.drosh.ime.latin.common.Colors
import dev.drosh.ime.latin.common.Constants
import dev.drosh.ime.latin.define.DebugFlags
import dev.drosh.ime.latin.settings.DebugSettings
import dev.drosh.ime.latin.settings.Defaults
import dev.drosh.ime.latin.settings.Settings
import dev.drosh.ime.latin.utils.ToolbarKey
import dev.drosh.ime.latin.utils.ToolbarMode
import dev.drosh.ime.latin.utils.addPinnedKey
import dev.drosh.ime.latin.utils.createToolbarKey
import dev.drosh.ime.latin.utils.SpecialKey
import dev.drosh.ime.latin.utils.SpecialKeyEntry
import dev.drosh.ime.latin.utils.SpecialKeysStore
import dev.drosh.ime.latin.utils.dpToPx
import dev.drosh.ime.latin.utils.getEnabledToolbarKeys
import dev.drosh.ime.latin.utils.getPinnedToolbarKeys
import dev.drosh.ime.latin.utils.prefs
import dev.drosh.ime.latin.utils.removeFirst
import dev.drosh.ime.latin.utils.removePinnedKey
import dev.drosh.ime.latin.utils.setToolbarButtonsActivatedStateOnPrefChange
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min
import androidx.core.view.isGone
import dev.drosh.ime.latin.utils.onClickToolbarKey
import dev.drosh.ime.latin.utils.onLongClickToolbarKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("InflateParams")
class SuggestionStripView(context: Context, attrs: AttributeSet?, defStyle: Int) :
    RelativeLayout(context, attrs, defStyle), View.OnClickListener, OnLongClickListener, OnSharedPreferenceChangeListener {

    /** Construct a [SuggestionStripView] for showing suggestions to be picked by the user. */
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, R.attr.suggestionStripViewStyle)

    interface Listener {
        fun pickSuggestionManually(word: SuggestedWordInfo?)
        fun onCodeInput(primaryCode: Int, x: Int, y: Int, isKeyRepeat: Boolean)
        fun removeSuggestion(word: String?)
        fun removeExternalSuggestions()
        fun onSwipeDownOnToolbar()
        /**
         * Sends a raw hardware key event, optionally with modifier bits, to the
         * editor. Used by the functional keys of the quickbar.
         */
        fun onSpecialKeyEvent(keyCode: Int, metaState: Int)
        /**
         * Commits raw text straight to the editor. Used by the special keys of
         * the quickbar: a terminal receives ESC, Tab and the C0 codes of the
         * sticky modifiers reliably through the text channel, while key events
         * are asynchronous and lose their meta bits on the way.
         */
        fun onSpecialTextInput(text: String)
        /**
         * Arms a sticky modifier (e.g. [KeyEvent.META_CTRL_ON]) that applies to
         * the next key press, or `0` to disarm. Used by Ctrl and Alt in the
         * special keys row: a terminal needs the modifier attached to the key
         * that follows it.
         */
        fun onArmSpecialMeta(metaState: Int)

        /**
         * Arms a user-created combination (e.g. Ctrl+K) stored in the special
         * keys settings: the next key press is sent as the whole combination.
         */
        fun onArmCustomMeta(id: String, metaState: Int, keyCode: Int)
    }

    private val moreSuggestionsContainer: View
    private val wordViews = ArrayList<TextView>()
    private val debugInfoViews = ArrayList<TextView>()
    private val dividerViews = ArrayList<View>()

    init {
        val inflater = LayoutInflater.from(context)
        inflater.inflate(R.layout.suggestions_strip, this)
        moreSuggestionsContainer = inflater.inflate(R.layout.more_suggestions, null)

        val colors = Settings.getValues().mColors
        colors.setBackground(this, ColorType.STRIP_BACKGROUND)
        repeat(SuggestedWords.MAX_SUGGESTIONS) {
            val word = TextView(context, null, R.attr.suggestionWordStyle)
            word.contentDescription = resources.getString(R.string.spoken_empty_suggestion)
            word.setOnClickListener(this)
            word.setOnLongClickListener(this)
            colors.setBackground(word, ColorType.STRIP_BACKGROUND)
            KeyboardTypeface.applyToTextView(word)
            wordViews.add(word)
            val divider = inflater.inflate(R.layout.suggestion_divider, null)
            dividerViews.add(divider)
            val info = TextView(context, null, R.attr.suggestionWordStyle)
            info.setTextColor(colors.get(ColorType.KEY_TEXT))
            info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, DEBUG_INFO_TEXT_SIZE_IN_DIP)
            debugInfoViews.add(info)
        }

        DEBUG_SUGGESTIONS = context.prefs().getBoolean(DebugSettings.PREF_SHOW_SUGGESTION_INFOS, Defaults.PREF_SHOW_SUGGESTION_INFOS)
    }

    // toolbar views, drawables and setup
    private val toolbar: ViewGroup = findViewById(R.id.toolbar)
    private val toolbarContainer: View = findViewById(R.id.toolbar_container)
    private val pinnedKeys: ViewGroup = findViewById(R.id.pinned_keys)
    private val suggestionsStrip: ViewGroup = findViewById(R.id.suggestions_strip)
    private val toolbarExpandKey = findViewById<ImageButton>(R.id.suggestions_strip_toolbar_key)
    private val functionalKeys: ViewGroup = findViewById(R.id.functional_keys)
    private var stripStage = STRIP_STAGE_SPECIAL

    // F1-F12 layer inside the special keys row
    private var fnLayerActive = false
    private var specialKeysSignature: String? = null
    /** True when suggestions changed while another quickbar mode was up. */
    private var pendingSuggestions = false

    // sticky modifier armed from the special keys row (KeyEvent meta mask)
    private var armedMetaState = 0
    private val specialKeyChipBackground: Drawable? =
        androidx.core.content.ContextCompat.getDrawable(context, R.drawable.special_key_background)
    private val armedKeyTextColor = 0xFF131D36.toInt()
    private val defaultSpecialKeyTextColor: Int =
        Settings.getValues().mColors.get(ColorType.KEY_TEXT)
    private val incognitoIcon = KeyboardIconsSet.instance.getNewDrawable(ToolbarKey.INCOGNITO.name, context)
    private val toolbarArrowIcon = KeyboardIconsSet.instance.getNewDrawable(KeyboardIconsSet.NAME_TOOLBAR_KEY, context)
    private val defaultToolbarBackground: Drawable = toolbarExpandKey.background
    private val enabledToolKeyBackground = GradientDrawable()
    private var direction = 1 // 1 if LTR, -1 if RTL

    private val toolbarKeyLayoutParams = LinearLayout.LayoutParams(
        resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_edge_key_width),
        LinearLayout.LayoutParams.MATCH_PARENT
    )

    init {
        val colors = Settings.getValues().mColors

        // expand key: toggles between the special keys row and the app's
        // quickbar (toolbar). One button, two modes.
        // weird way of setting size (default is config_suggestions_strip_edge_key_width)
        // but better not change it or people will complain
        val toolbarHeight = min(toolbarExpandKey.layoutParams.height, resources.getDimension(R.dimen.config_suggestions_strip_height).toInt())
        toolbarExpandKey.layoutParams.height = toolbarHeight
        toolbarExpandKey.layoutParams.width = toolbarHeight // we want it square
        colors.setBackground(toolbarExpandKey, ColorType.STRIP_BACKGROUND) // necessary because background is re-used for defaultToolbarBackground
        colors.setColor(toolbarExpandKey, ColorType.TOOL_BAR_EXPAND_KEY)
        colors.setColor(toolbarExpandKey.background, ColorType.TOOL_BAR_EXPAND_KEY_BACKGROUND)

        // background indicator for pinned keys
        val color = colors.get(ColorType.TOOL_BAR_KEY_ENABLED_BACKGROUND) or -0x1000000 // ignore alpha (in Java this is more readable 0xFF000000)
        enabledToolKeyBackground.colors = intArrayOf(color, Color.TRANSPARENT)
        enabledToolKeyBackground.gradientType = GradientDrawable.RADIAL_GRADIENT
        enabledToolKeyBackground.gradientRadius = resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_height) / 2.1f

        val mToolbarMode = if (isGone) ToolbarMode.HIDDEN else Settings.getValues().mToolbarMode
        if (mToolbarMode == ToolbarMode.HIDDEN) {
            // toolbar mode hidden: the quickbar is always the special keys row
            stripStage = STRIP_STAGE_SPECIAL
        } else if (mToolbarMode == ToolbarMode.TOOLBAR_KEYS) {
            // user prefers the app's quickbar by default
            setToolbarVisibility(true)
        }

        // toolbar keys setup (no need to hide them any more when locked, because then suggestion strip is gone anyway
        for (key in getEnabledToolbarKeys(context.prefs())) {
            val button = createToolbarKey(context, key)
            button.layoutParams = toolbarKeyLayoutParams
            setupKey(button, colors)
            toolbar.addView(button)
        }
        for (pinnedKey in getPinnedToolbarKeys(context.prefs())) {
            val button = createToolbarKey(context, pinnedKey)
            button.layoutParams = toolbarKeyLayoutParams
            setupKey(button, colors)
            pinnedKeys.addView(button)
            val pinnedKeyInToolbar = toolbar.findViewWithTag<View>(pinnedKey)
            if (pinnedKeyInToolbar != null && Settings.getValues().mQuickPinToolbarKeys)
                pinnedKeyInToolbar.background = enabledToolKeyBackground
        }
        toolbarContainer.doOnNextLayout {
            // set min with of the toolbar so the weight of the toolbar keys actually does something
            // todo: results in requestLayout() improperly called by android.widget.LinearLayout during layout: running second layout pass
            toolbar.minimumWidth = toolbarContainer.width
        }

        updateKeys()
    }

    private lateinit var listener: Listener
    private var suggestedWords = SuggestedWords.getEmptyInstance()
    private var startIndexOfMoreSuggestions = 0
    private var isExternalSuggestionVisible = false // Required to disable the more suggestions if other suggestions are visible
    private val layoutHelper = SuggestionStripLayoutHelper(context, attrs, defStyle, wordViews, dividerViews, debugInfoViews)
    private val moreSuggestionsView = moreSuggestionsContainer.findViewById<MoreSuggestionsView>(R.id.more_suggestions_view).apply {
        val slidingListener = object : SimpleOnGestureListener() {
            override fun onScroll(down: MotionEvent?, me: MotionEvent, deltaX: Float, deltaY: Float): Boolean {
                if (down == null) return false
                val dy = me.y - down.y
                val dx = me.x - down.x

                if (Settings.getValues().mToolbarSwipeDownToHide && dy > 50.dpToPx(resources) && abs(dy) > abs(dx)) {
                    listener.onSwipeDownOnToolbar()
                    return true
                }

                return if (!isExternalSuggestionVisible && toolbarContainer.visibility != VISIBLE && deltaY > 0 && dy < (-10).dpToPx(resources)) showMoreSuggestions()
                else false
            }
        }
        gestureDetector = GestureDetector(context, slidingListener)
    }

    // public stuff

    val isShowingMoreSuggestionPanel get() = moreSuggestionsView.isShowingInParent

    /** A connection back to the input method. */
    fun setListener(newListener: Listener, inputView: View) {
        listener = newListener
        moreSuggestionsView.listener = newListener
        moreSuggestionsView.mainKeyboardView = inputView.findViewById(R.id.keyboard_view)
    }

    fun setRtl(isRtlLanguage: Boolean) {
        val newLayoutDirection: Int
        if (!Settings.getValues().mVarToolbarDirection)
            newLayoutDirection = LAYOUT_DIRECTION_LOCALE
        else {
            newLayoutDirection = if (isRtlLanguage) LAYOUT_DIRECTION_RTL else LAYOUT_DIRECTION_LTR
            direction = if (isRtlLanguage) -1 else 1
            toolbarExpandKey.scaleX = (if (toolbarContainer.visibility != VISIBLE) 1f else -1f) * direction
        }
        layoutDirection = newLayoutDirection
        suggestionsStrip.layoutDirection = newLayoutDirection
    }

    /**
     * The quickbar has three modes, cycled by the expand key:
     * 1. the special keys row (Ctrl, Alt, Esc, ...),
     * 2. the app's quickbar (mic, clipboard, mouse, snippets, ...),
     * 3. the suggestion strip (word suggestions while typing).
     * Callers that only care about showing or hiding the toolbar keep using
     * this; a suggestions call lands in the suggestions mode automatically.
     */
    fun setToolbarVisibility(toolbarVisible: Boolean) {
        setStripStage(if (toolbarVisible) STRIP_STAGE_TOOLBAR else STRIP_STAGE_SPECIAL)
    }

    private fun setStripStage(stage: Int) {
        stripStage = stage
        val toolbarVisible = stage == STRIP_STAGE_TOOLBAR
        val specialVisible = stage == STRIP_STAGE_SPECIAL
        val suggestionsVisible = stage == STRIP_STAGE_SUGGESTIONS
        pinnedKeys.isVisible = toolbarVisible
        suggestionsStrip.isVisible = suggestionsVisible
        toolbarContainer.isVisible = toolbarVisible
        functionalKeys.isVisible = specialVisible

        if (DEBUG_SUGGESTIONS) {
            for (view in debugInfoViews) {
                view.visibility = suggestionsStrip.visibility
            }
        }

        toolbarExpandKey.scaleX = (if (toolbarVisible) -1f else 1f) * direction
        if (specialVisible) renderSpecialKeys()
    }

    /** Cycles: special keys -> app quickbar -> suggestions -> special keys. */
    private fun advanceStripStage() {
        setStripStage(
            when (stripStage) {
                STRIP_STAGE_SPECIAL -> STRIP_STAGE_TOOLBAR
                STRIP_STAGE_TOOLBAR -> STRIP_STAGE_SUGGESTIONS
                else -> STRIP_STAGE_SPECIAL
            }
        )
    }

    /**
     * A suggestions update arrives while the user is in another mode: stay in
     * that mode, but keep the words cached so switching to the suggestions
     * mode shows them immediately.
     */
    fun setSuggestions(suggestions: SuggestedWords, isRtlLanguage: Boolean) {
        setRtl(isRtlLanguage)
        suggestedWords = suggestions
        pendingSuggestions = true
        if (stripStage == STRIP_STAGE_SUGGESTIONS) {
            layoutSuggestions()
        }
    }

    /** Lays out the cached suggestions into the strip. */
    private fun layoutSuggestions() {
        pendingSuggestions = false
        clear()
        startIndexOfMoreSuggestions = layoutHelper.layoutAndReturnStartIndexOfMoreSuggestions(
            context, suggestedWords, suggestionsStrip, this
        )
        isExternalSuggestionVisible = false
        updateKeys()
    }

    /**
     * Shows the suggestions mode (used by LatinIME when the user typed and
     * the strip should react), without stealing the mode if the user is
     * deliberately elsewhere.
     */
    fun showSuggestionsMode() {
        setStripStage(STRIP_STAGE_SUGGESTIONS)
        layoutSuggestions()
    }

    fun setExternalSuggestionView(view: View?, addCloseButton: Boolean) {
        clear()
        isExternalSuggestionVisible = true

        if (addCloseButton) {
            val wrapper = LinearLayout(context)
            suggestionsStrip.doOnNextLayout {
                wrapper.layoutParams = LinearLayout.LayoutParams(suggestionsStrip.width - 30.dpToPx(resources), LayoutParams.MATCH_PARENT)
            }
            wrapper.addView(view)
            suggestionsStrip.addView(wrapper)

            val closeButton = createToolbarKey(context, ToolbarKey.CLOSE_HISTORY)
            closeButton.layoutParams = toolbarKeyLayoutParams
            setupKey(closeButton, Settings.getValues().mColors)
            closeButton.setOnClickListener {
                listener.removeExternalSuggestions()
            }
            suggestionsStrip.addView(closeButton)
        } else {
            suggestionsStrip.addView(view)
        }

        // A clipboard suggestion is an offer, not a mode change: it is laid
        // out here and shown whenever the user cycles into the suggestions
        // mode. Auto-hide would yank the user out of the special keys row.
    }

    fun setMoreSuggestionsHeight(remainingHeight: Int) {
        layoutHelper.setMoreSuggestionsHeight(remainingHeight)
    }

    fun dismissMoreSuggestionsPanel() {
        moreSuggestionsView.dismissPopupKeysPanel()
    }

    // overrides: necessarily public, but not used from outside

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        setToolbarButtonsActivatedStateOnPrefChange(pinnedKeys, key)
        setToolbarButtonsActivatedStateOnPrefChange(toolbar, key)
        if (key == Settings.PREF_ALWAYS_INCOGNITO_MODE)
            GlobalScope.launch { delay(10); withContext(Dispatchers.Main) { updateKeys() } }
    }

    override fun onVisibilityChanged(view: View, visibility: Int) {
        super.onVisibilityChanged(view, visibility)
        // workaround for a bug with inline suggestions views that just keep showing up otherwise, https://github.com/HeliBorg/HeliBoard/pull/386
        if (view === this)
            suggestionsStrip.visibility = visibility
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        dismissMoreSuggestionsPanel()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Called by the framework when the size is known. Show the important notice if applicable.
        // This may be overridden by showing suggestions later, if applicable.
    }

    override fun dispatchPopulateAccessibilityEvent(event: AccessibilityEvent): Boolean {
        // Don't populate accessibility event with suggested words and voice key.
        return true
    }

    override fun onInterceptTouchEvent(motionEvent: MotionEvent): Boolean {
        // Detecting sliding up finger to show MoreSuggestionsView.
        return moreSuggestionsView.shouldInterceptTouchEvent(motionEvent)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(motionEvent: MotionEvent): Boolean {
        moreSuggestionsView.touchEvent(motionEvent)
        return true
    }

    override fun onClick(view: View) {
        val tag = view.tag
        if (tag is ToolbarKey) {
            onClickToolbarKey(view) { listener.onCodeInput(it, Constants.SUGGESTION_STRIP_COORDINATE, Constants.SUGGESTION_STRIP_COORDINATE, false) }
            return
        }
        AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, this, HapticEvent.KEY_PRESS)
        if (view === toolbarExpandKey) {
            advanceStripStage()
            return
        }

        // tag for word views is set in SuggestionStripLayoutHelper (setupWordViewsTextAndColor, layoutPunctuationSuggestions)
        if (tag is Int) {
            if (tag >= suggestedWords.size()) {
                return
            }
            val wordInfo = suggestedWords.getInfo(tag)
            listener.pickSuggestionManually(wordInfo)
        }
    }

    override fun onLongClick(view: View): Boolean {
        if (view.tag is ToolbarKey) {
            onLongClickToolbarKey(view)
            return true
        }
        AudioAndHapticFeedbackManager.getInstance().performHapticFeedback(this, HapticEvent.KEY_LONG_PRESS)
        return if (view is TextView && wordViews.contains(view)) {
            onLongClickSuggestion(view)
        } else {
            showMoreSuggestions()
        }
    }

    // actually private stuff

    private fun onLongClickToolbarKey(view: View) {
        val tag = view.tag as? ToolbarKey ?: return
        if (!Settings.getValues().mQuickPinToolbarKeys || view.parent === pinnedKeys) {
            onLongClickToolbarKey(view) { code, isRepeat -> listener.onCodeInput(code, Constants.SUGGESTION_STRIP_COORDINATE, Constants.SUGGESTION_STRIP_COORDINATE, isRepeat) }
        } else if (view.parent === toolbar) {
            AudioAndHapticFeedbackManager.getInstance().performHapticFeedback(this, HapticEvent.KEY_LONG_PRESS)
            val pinnedKeyView = pinnedKeys.findViewWithTag<View>(tag)
            if (pinnedKeyView == null) {
                addKeyToPinnedKeys(tag)
                toolbar.findViewWithTag<View>(tag).background = enabledToolKeyBackground
                addPinnedKey(context.prefs(), tag)
            } else {
                removePinnedKey(context.prefs(), tag)
                toolbar.findViewWithTag<View>(tag).background = defaultToolbarBackground.constantState?.newDrawable(resources)
                pinnedKeys.removeView(pinnedKeyView)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility") // no need for View#performClick, we only return false mostly anyway
    private fun onLongClickSuggestion(wordView: TextView): Boolean {
        var showIcon = true
        if (wordView.tag is Int) {
            val index = wordView.tag as Int
            val type = suggestedWords.getInfo(index).mSourceDict
            if (type == Dictionary.DICTIONARY_USER_TYPED || type == Dictionary.DICTIONARY_HARDCODED)
                showIcon = false
        }
        if (showIcon) {
            val icon = KeyboardIconsSet.instance.getNewDrawable(KeyboardIconsSet.NAME_BIN, context)!!
            Settings.getValues().mColors.setColor(icon, ColorType.REMOVE_SUGGESTION_ICON)
            val w = icon.intrinsicWidth
            val h = icon.intrinsicHeight
            wordView.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
            wordView.ellipsize = TextUtils.TruncateAt.END
            val downOk = AtomicBoolean(false)
            wordView.setOnTouchListener { _, motionEvent ->
                if (motionEvent.action == MotionEvent.ACTION_UP && downOk.get()) {
                    val x = motionEvent.x
                    val y = motionEvent.y
                    if (0 < x && x < w && 0 < y && y < h) {
                        removeSuggestion(wordView)
                        wordView.cancelLongPress()
                        wordView.isPressed = false
                        return@setOnTouchListener true
                    }
                } else if (motionEvent.action == MotionEvent.ACTION_DOWN) {
                    val x = motionEvent.x
                    val y = motionEvent.y
                    if (0 < x && x < w && 0 < y && y < h) {
                        downOk.set(true)
                    }
                }
                false
            }
        }
        if (DebugFlags.DEBUG_ENABLED && (isShowingMoreSuggestionPanel || !showMoreSuggestions())) {
            showSourceDict(wordView)
            return true
        }
        return showMoreSuggestions()
    }

    private fun showMoreSuggestions(): Boolean {
        if (suggestedWords.size() <= startIndexOfMoreSuggestions) {
            return false
        }
        if (!moreSuggestionsView.show(
                suggestedWords, startIndexOfMoreSuggestions, moreSuggestionsContainer, layoutHelper, this
        ))
            return false
        for (i in 0..<startIndexOfMoreSuggestions) {
            wordViews[i].isPressed = false
        }
        return true
    }

    private fun showSourceDict(wordView: TextView) {
        val word = wordView.text.toString()
        val index = wordView.tag as? Int ?: return
        if (index >= suggestedWords.size()) return
        val info = suggestedWords.getInfo(index)
        if (info.word != word) return

        val text = info.mSourceDict.mDictType + ":" + info.mSourceDict.mLocale
        if (isShowingMoreSuggestionPanel) {
            moreSuggestionsView.dismissPopupKeysPanel()
        }
        KeyboardSwitcher.getInstance().showToast(text, true)
    }

    private fun removeSuggestion(wordView: TextView) {
        val word = wordView.text.toString()
        listener.removeSuggestion(word)
        moreSuggestionsView.dismissPopupKeysPanel()
        // show suggestions, but without the removed word
        val suggestedWordInfos = ArrayList<SuggestedWordInfo>()
        for (i in 0..<suggestedWords.size()) {
            val info = suggestedWords.getInfo(i)
            if (info.word != word) suggestedWordInfos.add(info)
        }
        suggestedWords.mRawSuggestions?.removeFirst { it.word == word }

        val newSuggestedWords = SuggestedWords(
            suggestedWordInfos, suggestedWords.mRawSuggestions, suggestedWords.typedWordInfo, suggestedWords.mTypedWordValid,
            suggestedWords.mWillAutoCorrect, suggestedWords.mIsObsoleteSuggestions, suggestedWords.mInputStyle, suggestedWords.mSequenceNumber
        )
        setSuggestions(newSuggestedWords, direction != 1)

        // Show the toolbar if no suggestions are left and the "Auto show toolbar" setting is enabled
        if (this.suggestedWords.isEmpty && Settings.getValues().mAutoShowToolbar) {
            setToolbarVisibility(true)
        }
    }

    private fun clear() {
        suggestionsStrip.removeAllViews()
        if (DEBUG_SUGGESTIONS) removeAllDebugInfoViews()
        if (stripStage == STRIP_STAGE_SUGGESTIONS)
            suggestionsStrip.isVisible = true
        dismissMoreSuggestionsPanel()
        for (word in wordViews) {
            word.setOnTouchListener(null)
        }
    }

    private fun removeAllDebugInfoViews() {
        for (debugInfoView in debugInfoViews) {
            val parent = debugInfoView.parent
            if (parent is ViewGroup) {
                parent.removeView(debugInfoView)
            }
        }
    }

    fun updateVoiceKey() {
        val show = Settings.getValues().mShowsVoiceInputKey
        toolbar.findViewWithTag<View>(ToolbarKey.VOICE)?.isVisible = show
        pinnedKeys.findViewWithTag<View>(ToolbarKey.VOICE)?.isVisible = show
    }

    /**
     * Builds the special keys row from the user's key set. Every key has
     * exactly one behavior — sticky arm (Ctrl, Alt), text commit (Esc, Tab,
     * Enter, symbols), arrow, or the Fn layer — so keys can be reordered,
     * resized and disabled freely.
     */
    private fun renderSpecialKeys() {
        val entries = SpecialKeysStore.parse(context.prefs()).filter { it.enabled }
        val signature = entries.joinToString { "${it.id}:${it.width}" } + "|fn=$fnLayerActive"
        if (signature == specialKeysSignature) return
        specialKeysSignature = signature
        functionalKeys.removeAllViews()
        if (fnLayerActive) {
            repeat(SpecialKeysStore.FN_KEY_COUNT) { index ->
                functionalKeys.addView(makeSpecialChip("F${index + 1}", 1f) {
                    performKeyFeedback(it)
                    listener.onCodeInput(
                        SpecialKeysStore.fnKeyCode(index),
                        Constants.NOT_A_COORDINATE,
                        Constants.NOT_A_COORDINATE,
                        false,
                    )
                    // any F key closes the layer, like the mockup
                    fnLayerActive = false
                    renderSpecialKeys()
                })
            }
            return
        }
        for (entry in entries) {
            functionalKeys.addView(makeSpecialChip(entry))
        }
        refreshArmedKeyVisual()
    }

    private fun newChipBackground(): Drawable? =
        specialKeyChipBackground?.constantState?.newDrawable(resources)

    private fun makeSpecialChip(entry: SpecialKeyEntry): View = when (entry) {
        is SpecialKeyEntry.BuiltIn -> {
            val key = entry.key
            val iconName = key.iconName
            if (iconName != null) {
                val chip = ImageButton(context, null, R.attr.suggestionWordStyle)
                chip.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT)
                    .apply { weight = entry.width }
                val icon = KeyboardIconsSet.instance.getNewDrawable(iconName, context)
                chip.setImageDrawable(icon)
                chip.scaleType = ImageView.ScaleType.CENTER
                chip.setPadding(6.dpToPx(resources), 4.dpToPx(resources), 6.dpToPx(resources), 4.dpToPx(resources))
                chip.background = newChipBackground()
                chip.isClickable = true
                chip.setOnClickListener { view -> onSpecialChipClick(view, entry) }
                chip.tag = entry
                chip
            } else {
                val chip = makeSpecialChip(context.getString(key.labelResId), entry.width) { view ->
                    onSpecialChipClick(view, entry)
                }
                chip.tag = entry
                chip
            }
        }
        is SpecialKeyEntry.CustomKey -> {
            val chip = makeSpecialChip(entry.label, entry.width) { view ->
                performKeyFeedback(view)
                // a custom combination is a one-shot: the next key press is
                // consumed and sent as the whole combination
                listener.onArmCustomMeta(entry.id, entry.metaState, entry.keyCode)
            }
            chip.tag = entry
            chip
        }
    }

    private fun onSpecialChipClick(view: View, entry: SpecialKeyEntry) {
        val builtIn = entry as? SpecialKeyEntry.BuiltIn ?: return
        when {
            builtIn.key.isSticky -> armSticky(view, builtIn)
            builtIn.key.isFn -> {
                performKeyFeedback(view)
                fnLayerActive = true
                renderSpecialKeys()
            }
            builtIn.key.isArrow -> {
                performKeyFeedback(view)
                disarmModifier()
                listener.onCodeInput(
                    builtIn.key.code,
                    Constants.NOT_A_COORDINATE,
                    Constants.NOT_A_COORDINATE,
                    false,
                )
            }
            builtIn.key.emitText != null -> {
                performKeyFeedback(view)
                disarmModifier()
                val text = builtIn.key.emitText
                if (text != null) listener.onSpecialTextInput(text)
            }
        }
    }

    private fun makeSpecialChip(label: String, width: Float, onClick: (View) -> Unit): TextView {
        val chip = TextView(context, null, R.attr.suggestionWordStyle)
        chip.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT)
            .apply { weight = width }
        chip.gravity = android.view.Gravity.CENTER
        chip.text = label
        chip.textSize = 13f
        chip.setPadding(4.dpToPx(resources), 0, 4.dpToPx(resources), 0)
        chip.isClickable = true
        chip.background = newChipBackground()
        chip.setTextColor(defaultSpecialKeyTextColor)
        KeyboardTypeface.applyToTextView(chip)
        chip.setOnClickListener { view -> view?.let { onClick(it) } }
        return chip
    }

    private fun armSticky(view: View, entry: SpecialKeyEntry.BuiltIn) {
        performKeyFeedback(view)
        val metaState = when (entry.key) {
            SpecialKey.CTRL -> KeyEvent.META_CTRL_ON
            SpecialKey.SHIFT -> KeyEvent.META_SHIFT_ON
            SpecialKey.ALT -> KeyEvent.META_ALT_ON
            else -> return
        }
        armedMetaState = if (armedMetaState == metaState) 0 else metaState
        refreshArmedKeyVisual()
        listener.onArmSpecialMeta(armedMetaState)
    }

    private fun performKeyFeedback(view: View) {
        AudioAndHapticFeedbackManager.getInstance()
            .performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, view, HapticEvent.KEY_PRESS)
    }

    /** Clears the sticky modifier, e.g. when Esc or Tab is pressed instead. */
    private fun disarmModifier() {
        if (armedMetaState == 0) return
        armedMetaState = 0
        refreshArmedKeyVisual()
        listener.onArmSpecialMeta(0)
    }

    /**
     * Mirrors the armed sticky modifier coming from the IME, which clears it
     * once the next key press was sent with the meta bits attached.
     */
    fun setArmedModifier(metaState: Int) {
        if (armedMetaState == metaState) return
        armedMetaState = metaState
        refreshArmedKeyVisual()
    }

    private fun refreshArmedKeyVisual() {
        applyArmedVisual(functionalKeys.findViewWithTag(SpecialKey.CTRL), armedMetaState == KeyEvent.META_CTRL_ON)
        applyArmedVisual(functionalKeys.findViewWithTag(SpecialKey.ALT), armedMetaState == KeyEvent.META_ALT_ON)
    }

    /**
     * The chip background is a selector with an activated state, so arming
     * only flips the flag and the text/icon color; the pressed state comes
     * for free from the same drawable.
     */
    private fun applyArmedVisual(view: View?, armed: Boolean) {
        view ?: return
        view.isActivated = armed
        when (view) {
            is TextView -> view.setTextColor(if (armed) armedKeyTextColor else defaultSpecialKeyTextColor)
            is ImageView -> view.setColorFilter(if (armed) armedKeyTextColor else defaultSpecialKeyTextColor)
        }
    }

    private fun updateKeys() {
        updateVoiceKey()
        val settingsValues = Settings.getValues()

        val toolbarIsExpandable = settingsValues.mToolbarMode == ToolbarMode.EXPANDABLE
        if (settingsValues.mIncognitoModeEnabled) {
            toolbarExpandKey.setImageDrawable(incognitoIcon)
            toolbarExpandKey.isVisible = true
        } else {
            toolbarExpandKey.setImageDrawable(toolbarArrowIcon)
            toolbarExpandKey.isVisible = toolbarIsExpandable
        }

        toolbarExpandKey.setOnClickListener(if (!toolbarIsExpandable) null else this)
        renderSpecialKeys()
        // mode decides what is visible; the stage setter owns the layout
        setStripStage(stripStage)
        isExternalSuggestionVisible = false
    }

    private fun addKeyToPinnedKeys(pinnedKey: ToolbarKey) {
        val original = toolbar.findViewWithTag<ImageButton>(pinnedKey) ?: return
        // copy the original key to a new ImageButton
        val copy = ImageButton(context, null, R.attr.suggestionWordStyle)
        copy.tag = pinnedKey
        copy.scaleType = original.scaleType
        copy.scaleX = original.scaleX
        copy.scaleY = original.scaleY
        copy.contentDescription = original.contentDescription
        copy.setImageDrawable(original.drawable)
        copy.layoutParams = original.layoutParams
        copy.isActivated = original.isActivated
        setupKey(copy, Settings.getValues().mColors)
        pinnedKeys.addView(copy)
    }

    private fun setupKey(view: ImageButton, colors: Colors) {
        view.setOnClickListener(this)
        view.setOnLongClickListener(this)
        (view.layoutParams as LinearLayout.LayoutParams).weight = 1f
        colors.setColor(view, ColorType.TOOL_BAR_KEY)
        colors.setBackground(view, ColorType.STRIP_BACKGROUND)
    }

    companion object {
        /** Quickbar shows the special keys row. */
        private const val STRIP_STAGE_SPECIAL = 0
        /** Quickbar shows the app's toolbar (mic, clipboard, mouse, ...). */
        private const val STRIP_STAGE_TOOLBAR = 1
        /** Quickbar shows the suggestion strip (word suggestions). */
        private const val STRIP_STAGE_SUGGESTIONS = 2

        @JvmField
        var DEBUG_SUGGESTIONS = false
        private const val DEBUG_INFO_TEXT_SIZE_IN_DIP = 6.5f
        private val TAG = SuggestionStripView::class.java.simpleName
    }
}
