// SPDX-License-Identifier: GPL-3.0-only
package dev.drosh.ime.latin.utils

import android.content.SharedPreferences
import android.view.KeyEvent
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
 * - [SpecialKey.isSticky] arms and applies to the next key press (Ctrl, Alt)
 * - [SpecialKey.emitText] commits through the terminal's text channel — the
 *   channel proven for control characters (ESC, TAB) and control codes
 * - [SpecialKey.isArrow] sends a real key event; the terminal picks the right
 *   escape sequence for its cursor mode
 * - [SpecialKey.isFn] switches the row to the F1-F12 layer
 *
 * The stored value also holds user-created combinations (`Ctrl+K`, ...) under
 * a `CUSTOM_` id: modifier bits plus a key code, appended to the row like any
 * other key.
 */

enum class SpecialKey {
    CTRL, SHIFT, ALT, ESC, TAB, ENTER, FN, LEFT, UP, DOWN, RIGHT,
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

    val isSticky: Boolean get() = this == CTRL || this == SHIFT || this == ALT
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
        SHIFT -> R.string.shift
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
}

/** One entry of the stored key set: built-in key or custom combination. */
sealed interface SpecialKeyEntry {
    val id: String
    val enabled: Boolean
    val width: Float

    data class BuiltIn(
        val key: SpecialKey,
        override val enabled: Boolean,
        override val width: Float = 1f,
    ) : SpecialKeyEntry {
        override val id: String get() = key.name
    }

    data class CustomKey(
        override val id: String,
        val metaState: Int,
        val keyCode: Int,
        override val enabled: Boolean,
        override val width: Float = 1f,
        val label: String,
    ) : SpecialKeyEntry
}

/**
 * Returns a copy of this entry with a new enabled flag and/or width. The
 * sealed interface has no `copy()` of its own, so the editor uses this to
 * mutate entries without knowing the concrete variant.
 */
fun SpecialKeyEntry.withState(
    enabled: Boolean = this.enabled,
    width: Float = this.width,
): SpecialKeyEntry = when (this) {
    is SpecialKeyEntry.BuiltIn -> copy(enabled = enabled, width = width)
    is SpecialKeyEntry.CustomKey -> copy(enabled = enabled, width = width)
}

object SpecialKeysStore {
    const val PREF = "special_keys"
    /** Prefix of user-created combination keys in the stored value. */
    const val CUSTOM_PREFIX = "CUSTOM_"

    /** Ctrl, Alt, Esc, Tab and Enter: the terminal's daily five. */
    val defaultEnabled = setOf(SpecialKey.CTRL, SpecialKey.ALT, SpecialKey.ESC, SpecialKey.TAB, SpecialKey.ENTER)

    private val fnKeys = listOf(
        KeyCode.F1, KeyCode.F2, KeyCode.F3, KeyCode.F4, KeyCode.F5, KeyCode.F6,
        KeyCode.F7, KeyCode.F8, KeyCode.F9, KeyCode.F10, KeyCode.F11, KeyCode.F12,
    )

    /** Widths are clamped so a single key can never flood the row. */
    const val MIN_WIDTH = 0.6f
    const val MAX_WIDTH = 2.5f

    /** All built-in keys in canonical order, enabled flag per key. */
    val default: String get() = SpecialKey.entries.joinToString(Separators.ENTRY) {
        it.name + Separators.KV + (it in defaultEnabled)
    }

    fun buildCustomLabel(metaState: Int, keyCode: Int): String {
        val parts = buildList {
            if (metaState and KeyEvent.META_CTRL_ON != 0) add("Ctrl")
            if (metaState and KeyEvent.META_ALT_ON != 0) add("Alt")
            if (metaState and KeyEvent.META_SHIFT_ON != 0) add("Shift")
        }
        val ch = keyCode.toChar()
        val key = if (ch.isLetterOrDigit()) ch.uppercaseChar().toString() else "0x${keyCode.toString(16)}"
        return (parts + key).joinToString("+")
    }

    /** Parses the stored value, tolerating older `KEY=state` entries. */
    fun parse(prefs: SharedPreferences): List<SpecialKeyEntry> =
        prefs.getString(PREF, default)!!
            .split(Separators.ENTRY)
            .mapNotNull { entry ->
                val main = entry.split(Separators.KV)
                if (main.isEmpty()) return@mapNotNull null
                val enabled = main.getOrNull(1)?.toBoolean() ?: false
                val width = main.getOrNull(2)?.toFloatOrNull()?.coerceIn(MIN_WIDTH, MAX_WIDTH) ?: 1f
                val key = runCatching { SpecialKey.valueOf(main[0]) }.getOrNull()
                if (key != null) return@mapNotNull SpecialKeyEntry.BuiltIn(key, enabled, width)
                if (main[0].startsWith(CUSTOM_PREFIX)) {
                    val parts = main[0].removePrefix(CUSTOM_PREFIX).split("_")
                    val meta = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                    val code = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                    return@mapNotNull SpecialKeyEntry.CustomKey(
                        main[0], meta, code, enabled, width,
                        main.getOrNull(3) ?: buildCustomLabel(meta, code),
                    )
                }
                null
            }

    fun write(prefs: SharedPreferences, entries: List<SpecialKeyEntry>) {
        val value = entries.joinToString(Separators.ENTRY) { entry ->
            when (entry) {
                is SpecialKeyEntry.BuiltIn ->
                    entry.key.name + Separators.KV + entry.enabled + Separators.KV + entry.width
                is SpecialKeyEntry.CustomKey ->
                    entry.id + Separators.KV + entry.enabled + Separators.KV + entry.width + Separators.KV + entry.label
            }
        }
        prefs.edit { putString(PREF, value) }
    }

    fun addCustomKey(prefs: SharedPreferences, metaState: Int, keyCode: Int) {
        val id = "$CUSTOM_PREFIX${metaState}_$keyCode"
        val entries = parse(prefs).toMutableList()
        if (entries.any { it.id == id }) return
        entries.add(
            SpecialKeyEntry.CustomKey(
                id, metaState, keyCode, enabled = true, width = 1f,
                label = buildCustomLabel(metaState, keyCode),
            )
        )
        write(prefs, entries)
    }

    fun removeCustomKey(prefs: SharedPreferences, id: String) {
        write(prefs, parse(prefs).filterNot { it.id == id })
    }

    fun reset(prefs: SharedPreferences) {
        prefs.edit { remove(PREF) }
    }

    /** Built-in enabled keys in stored order (custom keys excluded). */
    fun enabledKeys(prefs: SharedPreferences): List<SpecialKey> =
        parse(prefs).filter { it.enabled }.mapNotNull { (it as? SpecialKeyEntry.BuiltIn)?.key }

    fun fnKeyCode(index: Int): Int = fnKeys.getOrElse(index) { KeyCode.UNSPECIFIED }

    const val FN_KEY_COUNT = 12
}
