// SPDX-License-Identifier: GPL-3.0-only
package dev.drosh.ime.latin.utils

import android.content.SharedPreferences
import androidx.core.content.edit
import dev.drosh.ime.keyboard.internal.KeyboardIconsSet
import dev.drosh.ime.keyboard.internal.keyboard_parser.floris.KeyCode
import dev.drosh.ime.latin.R
import dev.drosh.ime.latin.common.Constants.Separators

/**
 * The special keys row of the quickbar: a configurable set of keys for a
 * terminal — the sticky modifiers, the control characters, arrows and the
 * symbols a command line needs.
 *
 * Every key has exactly one behavior, so a key can be moved, resized or
 * disabled freely:
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

    /** Icon name for the key, or null when the key shows a text label. */
    val iconName: String? get() = when (this) {
        TAB -> KeyboardIconsSet.NAME_TAB_KEY
        ENTER -> KeyboardIconsSet.NAME_ENTER_KEY
        else -> null
    }

    /** One entry of the stored key set: the key, its state and its width. */
    data class Entry(val key: SpecialKey, val enabled: Boolean, val width: Float = 1f)

    companion object {
        const val PREF = "special_keys"

        /** Ctrl, Alt, Esc, Tab and Enter: the terminal's daily five. */
        val defaultEnabled = setOf(CTRL, ALT, ESC, TAB, ENTER)

        private val fnKeys = listOf(
            KeyCode.F1, KeyCode.F2, KeyCode.F3, KeyCode.F4, KeyCode.F5, KeyCode.F6,
            KeyCode.F7, KeyCode.F8, KeyCode.F9, KeyCode.F10, KeyCode.F11, KeyCode.F12,
        )

        /** Widths are clamped so a single key can never flood the row. */
        const val MIN_WIDTH = 0.6f
        const val MAX_WIDTH = 2.5f

        /** All keys in canonical order, enabled flag and width per key. */
        val default: String get() = entries.joinToString(Separators.ENTRY) {
            it.name + Separators.KV + (it in defaultEnabled)
        }

        /** Parses the stored value, tolerating older `KEY=state` entries. */
        fun parse(prefs: SharedPreferences): List<Entry> =
            prefs.getString(PREF, default)!!
                .split(Separators.ENTRY)
                .mapNotNull { entry ->
                    val main = entry.split(Separators.KV)
                    if (main.isEmpty()) return@mapNotNull null
                    val key = runCatching { SpecialKey.valueOf(main[0]) }.getOrNull()
                        ?: return@mapNotNull null
                    val enabled = main.getOrNull(1)?.toBoolean() ?: (key in defaultEnabled)
                    val width = main.getOrNull(2)?.toFloatOrNull()?.coerceIn(MIN_WIDTH, MAX_WIDTH) ?: 1f
                    Entry(key, enabled, width)
                }

        fun write(prefs: SharedPreferences, entries: List<Entry>) {
            val value = entries.joinToString(Separators.ENTRY) {
                it.key.name + Separators.KV + it.enabled + Separators.KV + it.width
            }
            prefs.edit { putString(PREF, value) }
        }

        fun reset(prefs: SharedPreferences) {
            prefs.edit { remove(PREF) }
        }

        /** Enabled keys in stored order. */
        fun enabledKeys(prefs: SharedPreferences): List<SpecialKey> =
            parse(prefs).filter { it.enabled }.map { it.key }

        fun fnKeyCode(index: Int): Int = fnKeys.getOrElse(index) { KeyCode.UNSPECIFIED }

        const val FN_KEY_COUNT = 12
    }
}
