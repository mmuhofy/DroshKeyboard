package dev.drosh.ime.keyboard.special

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import dev.drosh.ime.keyboard.KeyboardActionListener
import dev.drosh.ime.keyboard.internal.keyboard_parser.floris.KeyCode
import dev.drosh.ime.latin.R
import dev.drosh.ime.latin.common.Constants

/**
 * Key layer for the keys that have no place in the letter layouts: modifiers,
 * Tab, Escape, Fn and the arrow keys.
 *
 * These are deliberately not added to the q/f layouts, because Turkish still
 * needs q and f as letters. The layer replaces the key grid instead, exactly
 * like the clipboard and snippet panels do, and is toggled from the quickbar
 * through [KeyCode.SPECIAL_KEYS].
 *
 * Every key sends a real hardware key event instead of soft input, because that
 * is the only way to express modifier combinations such as Ctrl+Arrow towards
 * the editor. Escape and the close button send [KeyCode.SPECIAL_KEYS] back,
 * which toggles the layout back to the alphabet.
 */
class SpecialKeysView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet?,
    defStyle: Int = 0,
) : LinearLayout(context, attrs, defStyle) {

    private lateinit var keyboardActionListener: KeyboardActionListener

    fun setKeyboardActionListener(listener: KeyboardActionListener) {
        keyboardActionListener = listener
    }

    fun startSpecialKeys(listener: KeyboardActionListener) {
        keyboardActionListener = listener
        ensureInflated()
    }

    fun stopSpecialKeys() {
        // Nothing to release: the layer keeps no editor state between openings.
    }

    private var inflated = false

    private fun ensureInflated() {
        if (inflated) return
        inflated = true

        // Plain keys, sent without modifiers.
        bind(R.id.special_key_tab, KeyEvent.KEYCODE_TAB, 0)
        bind(R.id.special_key_esc, KeyEvent.KEYCODE_ESCAPE, 0)
        bind(R.id.special_key_up, KeyEvent.KEYCODE_DPAD_UP, 0)
        bind(R.id.special_key_down, KeyEvent.KEYCODE_DPAD_DOWN, 0)
        bind(R.id.special_key_left, KeyEvent.KEYCODE_DPAD_LEFT, 0)
        bind(R.id.special_key_right, KeyEvent.KEYCODE_DPAD_RIGHT, 0)

        // Modifier keys. Pressing one sends the modifier itself, so the editor
        // sees e.g. a Ctrl+Arrow combination when the next key arrives.
        // Android has no KEYCODE_FN; the Meta key is what most keyboards label
        // Fn, so that is what gets sent here.
        bind(R.id.special_key_ctrl, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.META_CTRL_ON)
        bind(R.id.special_key_alt, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.META_ALT_ON)
        bind(R.id.special_key_fn, KeyEvent.KEYCODE_META_LEFT, KeyEvent.META_META_ON)

        findViewById<View>(R.id.special_keys_close).setOnClickListener { toggleBack() }
    }

    private fun bind(id: Int, keyCode: Int, metaState: Int) {
        findViewById<View>(id).setOnClickListener {
            if (::keyboardActionListener.isInitialized) {
                keyboardActionListener.onSpecialKeyEvent(keyCode, metaState)
            }
        }
    }

    /**
     * Leaves the layer and returns to the previous layout. Sends the same code
     * as the quickbar key, so the state machine toggles back to the alphabet.
     */
    private fun toggleBack() {
        if (::keyboardActionListener.isInitialized) {
            keyboardActionListener.onCodeInput(
                KeyCode.SPECIAL_KEYS,
                Constants.NOT_A_COORDINATE,
                Constants.NOT_A_COORDINATE,
                false,
            )
        }
    }
}