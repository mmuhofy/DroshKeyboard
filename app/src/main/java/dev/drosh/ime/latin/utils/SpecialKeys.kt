// SPDX-License-Identifier: GPL-3.0-only
package dev.drosh.ime.latin.utils

import android.content.SharedPreferences
import dev.drosh.ime.keyboard.internal.keyboard_parser.floris.KeyCode
import dev.drosh.ime.latin.R
import dev.drosh.ime.latin.common.Constants.Separators

/**
 * The special keys row of the quickbar: a configurable set of keys for a
 * terminal — the sticky modifiers, the control characters, arrows and the
 * symbols a command line needs.
 *
 * Every key has exactly one behavior, so a key can be moved or disabled
 * freely:
 * - [isSticky] arms and applies to the next key press (Ctrl, Alt)
 * - [emitText] commits through the terminal's text channel — the channel
 *   proven for control characters (ESC, TAB) and control codes
 * - [isArrow] sends a real key event; the terminal picks the right escape
 *   sequence for its cursor mode
 * - [isFn] switches the row to the F1-F12 layer
 */
enum class SpecialKey {
    CTRL, ALT, ESC, TAB, ENTER, FN, LEFT, UP, DOWN, RIGHT,
    PIPE, TILDE, SLASH, DASH, UNDERSCORE, BACKSLASH, BACKTICK;

    /** The string this key commits, or null when it is not a text key. */
    val emitText: String? get() = when (this) {
        ESC -> "\u001b"
        TAB -> "\t"
        ENTER -> "\n"
        PIPE -> "|"
        TILDE -> "~"
        SLASH -> "/"
        DASH -> "-"
        UNDERSCORE -> "_"
        BACKSLASH -> "\\"
        BACKTICK -> "`"
        else -> null
    }

    val isSticky: Boolean get() = this == CTRL || this == ALT
    val isArrow: Boolean get() = this == LEFT || this == UP || this == DOWN || this == RIGHT
    val isFn: Boolean get() = this == FN

    /** The [KeyCode] this key sends, or [KeyCode.UNSPECIFIED]. */
    val code: Int get() = when (this) {
        LEFT -> KeyCode.ARROW_LEFT
        UP -> KeyCode.ARROW_UP
        DOWN -> KeyCode.ARROW_DOWN
        RIGHT -> KeyCode.ARROW_RIGHT
        else -> KeyCode.UNSPECIFIED
    }

    /** Label shown on the key and in the settings lists. */
    val labelResId: Int get() = when (this) {
        CTRL -> R.string.ctrl
        ALT -> R.string.alt
        ESC -> R.string.esc
        TAB -> R.string.tab
        ENTER -> R.string.enter
        FN -> R.string.fn
        LEFT -> R.string.left
        RIGHT -> R.string.right
        UP -> R.string.up
        DOWN -> R.string.down
        PIPE -> R.string.pipe
        TILDE -> R.string.tilde
        SLASH -> R.string.slash
        DASH -> R.string.dash
        UNDERSCORE -> R.string.underscore
        BACKSLASH -> R.string.backslash
        BACKTICK -> R.string.backtick
    }

    companion object {
        const val PREF = "special_keys"

        /** Ctrl, Alt, Esc, Tab and Enter: the terminal's daily five. */
        val defaultEnabled = setOf(CTRL, ALT, ESC, TAB, ENTER)

        private val fnKeys = listOf(
            KeyCode.F1, KeyCode.F2, KeyCode.F3, KeyCode.F4, KeyCode.F5, KeyCode.F6,
            KeyCode.F7, KeyCode.F8, KeyCode.F9, KeyCode.F10, KeyCode.F11, KeyCode.F12,
        )

        /** All keys in canonical order, enabled flag per key. */
        val default: String get() = entries.joinToString(Separators.ENTRY) {
            it.name + Separators.KV + (it in defaultEnabled)
        }

        /** Enabled keys in stored order (prefs order wins, unknown keys dropped). */
        fun enabledKeys(prefs: SharedPreferences): List<SpecialKey> =
            prefs.getString(PREF, default)!!
                .split(Separators.ENTRY)
                .mapNotNull { entry ->
                    val parts = entry.split(Separators.KV)
                    if (parts.size != 2 || parts[1].toBoolean()) {
                        runCatching { SpecialKey.valueOf(parts[0]) }.getOrNull()
                    } else null
                }

        fun fnKeyCode(index: Int): Int =
            fnKeys.getOrElse(index) { KeyCode.UNSPECIFIED }

        const val FN_KEY_COUNT = 12
    }
}
