// SPDX-License-Identifier: GPL-3.0-only
package dev.drosh.ime.keyboard.mouse

/**
 * Wire protocol between Drosh Keyboard's mouse pad and the Drosh terminal
 * app. Travels as `InputConnection.performPrivateCommand(ACTION, bundle)` —
 * the editor-side channel that exists exactly for app-specific IME features.
 *
 * The Drosh app (terminal module) implements the identical constants; both
 * sides are signed with the same key, so no third party can reach it either.
 *
 * The keyboard never emits mouse escape sequences itself — it only sends
 * deltas and button events, and the app translates them through its own
 * emulator (which knows the active mouse protocol, SGR vs X10, and the
 * screen metrics).
 */
object MouseProtocol {
    const val ACTION = "dev.drosh.MOUSE"

    const val OP_OPEN = "open"
    const val OP_CLOSE = "close"
    const val OP_MOVE = "move"
    const val OP_DOWN = "down"
    const val OP_UP = "up"
    const val OP_CLICK = "click"

    const val KEY_OP = "op"

    /** Pixel deltas for [OP_MOVE]; the app converts them to cells. */
    const val KEY_DX = "dx"
    const val KEY_DY = "dy"

    /** 0 = left, 2 = right. Only 0 can be held for a drag (selection). */
    const val KEY_BUTTON = "button"

    /** Set on [OP_MOVE] while a button is held, so the app emits drag motion. */
    const val KEY_HELD = "held"
}
