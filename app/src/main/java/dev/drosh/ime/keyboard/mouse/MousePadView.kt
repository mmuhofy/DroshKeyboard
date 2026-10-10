/*
 * SPDX-License-Identifier: GPL-3.0-only
 */
package dev.drosh.ime.keyboard.mouse

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import dev.drosh.ime.keyboard.KeyboardActionListener
import dev.drosh.ime.keyboard.internal.keyboard_parser.floris.KeyCode
import dev.drosh.ime.latin.R
import dev.drosh.ime.latin.common.Constants

/**
 * Mouse pad replacing the key grid, mirroring the SnippetsView lifecycle.
 *
 * The pad is a pure input surface: it produces pixel deltas and button
 * events, and the Drosh terminal app — the only editor that understands
 * [MouseProtocol] — turns them into real mouse events on its emulator.
 * Sensitivity (pixels per cell) lives entirely on the app side, so this
 * view never interprets the deltas itself.
 *
 * Gestures, deliberately minimal:
 * - press + drag              → pointer move (no button held)
 * - short tap                 → left click
 * - two-finger single tap     → right click
 * - double-tap, then drag     → selection (left button held), release ends it
 *
 * UNTESTED — verify on device.
 */
class MousePadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet?,
    defStyle: Int = 0,
) : LinearLayout(context, attrs, defStyle) {

    private lateinit var keyboardActionListener: KeyboardActionListener
    private lateinit var surface: FrameLayout
    private lateinit var haptic: View

    /** Non-zero while the left button is held (selection mode). */
    private var dragging = false
    private var started = false
    private var lastTapAt = 0L
    private var tapCandidate = false
    private var accX = 0
    private var accY = 0
    private var downAt = 0L
    private var movedPx = 0
    private var lastX = 0f
    private var lastY = 0f
    private var activePointerId = -1
    private var secondPointerId = -1
    private var secondPointerDownPx = 0
    private var lastMoveSent = 0L

    fun setKeyboardActionListener(listener: KeyboardActionListener) {
        keyboardActionListener = listener
    }

    fun startMouse(actionListener: KeyboardActionListener) {
        keyboardActionListener = actionListener
        resetState()
        findViewById<ImageButton>(R.id.mouse_pad_close).setOnClickListener { close() }
        surface = findViewById(R.id.mouse_pad_surface)
        haptic = findViewById(R.id.mouse_pad_haptic)
        surface.isHapticFeedbackEnabled = false
        surface.setOnTouchListener { _, event -> onSurfaceTouch(event); true }
        started = true
        send(MouseProtocol.OP_OPEN)
    }

    fun stopMouse() {
        if (!started) return // never started: nothing to release or hide
        started = false
        surface.setOnTouchListener(null)
        // never leave a held button behind when the pad closes
        if (dragging) {
            send(MouseProtocol.OP_UP, button = 0)
            dragging = false
            setArmed(false)
        }
        send(MouseProtocol.OP_CLOSE)
        resetState()
    }

    private fun close() {
        keyboardActionListener.onCodeInput(
            KeyCode.MOUSE,
            Constants.NOT_A_COORDINATE,
            Constants.NOT_A_COORDINATE,
            false,
        )
    }

    private fun resetState() {
        dragging = false
        tapCandidate = false
        activePointerId = -1
        secondPointerId = -1
        accX = 0
        accY = 0
        movedPx = 0
        lastMoveSent = 0L
    }

    private fun send(
        op: String,
        dx: Int = 0,
        dy: Int = 0,
        button: Int = -1,
        held: Boolean = false,
    ) {
        val data = Bundle().apply {
            putString(MouseProtocol.KEY_OP, op)
            if (op == MouseProtocol.OP_MOVE) {
                putInt(MouseProtocol.KEY_DX, dx)
                putInt(MouseProtocol.KEY_DY, dy)
                if (held) putBoolean(MouseProtocol.KEY_HELD, true)
            }
            if (button >= 0) putInt(MouseProtocol.KEY_BUTTON, button)
        }
        keyboardActionListener.onMouseInput(data)
    }

    private fun hapticFeedback() {
        keyboardActionListener.onCustomRequest(
            KeyboardActionListener.CustomAction.PERFORM_HAPTIC
        )
    }

    private fun flashHaptic() {
        haptic.animate().cancel()
        haptic.alpha = 0.35f
        haptic.animate().alpha(0f).setDuration(180).start()
    }

    private fun setArmed(armed: Boolean) {
        surface.setBackgroundResource(
            if (armed) R.drawable.mouse_pad_surface_armed else R.drawable.mouse_pad_surface
        )
    }

    private fun onSurfaceTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (activePointerId == -1) {
                    activePointerId = event.getPointerId(event.actionIndex)
                    lastX = event.x
                    lastY = event.y
                    downAt = android.os.SystemClock.elapsedRealtime()
                    movedPx = 0
                    accX = 0
                    accY = 0
                    tapCandidate = true
                    setArmed(true)
                } else if (secondPointerId == -1) {
                    secondPointerId = event.getPointerId(event.actionIndex)
                    secondPointerDownPx = movedPx
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (activePointerId == -1) return true
                val index = event.findPointerIndex(activePointerId)
                if (index < 0) return true
                val x = event.getX(index)
                val y = event.getY(index)
                val dx = x - lastX
                val dy = y - lastY
                lastX = x
                lastY = y
                movedPx += Math.abs(dx.toInt()) + Math.abs(dy.toInt())
                // selection mode ignores taps: the drag is the point
                if (dragging) tapCandidate = false
                accX += dx.toInt()
                accY += dy.toInt()
                maybeFlushMove()
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // note: the second finger lifting is handled on the final UP
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val quick = android.os.SystemClock.elapsedRealtime() - downAt < TAP_MS
                        && movedPx < TAP_PX
                val hadSecondFinger = secondPointerId != -1
                // flush whatever movement was not sent yet
                maybeFlushMove(force = true)

                if (dragging) {
                    // selection ends: release the held button
                    send(MouseProtocol.OP_UP, button = 0)
                    dragging = false
                    setArmed(false)
                } else if (quick && hadSecondFinger) {
                    // two-finger single tap = right click
                    hapticFeedback()
                    flashHaptic()
                    send(MouseProtocol.OP_CLICK, button = 2)
                } else if (quick) {
                    hapticFeedback()
                    flashHaptic()
                    val now = android.os.SystemClock.elapsedRealtime()
                    send(MouseProtocol.OP_CLICK, button = 0)
                    if (now - lastTapAt < DOUBLE_TAP_MS) {
                        // second tap within the window: hold the left button
                        // and let the following drag select
                        dragging = true
                        send(MouseProtocol.OP_DOWN, button = 0)
                        tapCandidate = false
                        lastTapAt = 0L
                    } else {
                        lastTapAt = now
                    }
                } else if (!dragging) {
                    setArmed(false)
                }
                resetState()
            }
        }
        return true
    }

    /**
     * Emits accumulated pixel deltas, coalesced so a fast drag does not
     * flood the binder. The app converts the pixels to cells itself.
     */
    private fun maybeFlushMove(force: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (accX == 0 && accY == 0) return
        if (!force && now - lastMoveSent < MOVE_COALESCE_MS) return
        lastMoveSent = now
        send(MouseProtocol.OP_MOVE, dx = accX, dy = accY, held = dragging)
        accX = 0
        accY = 0
    }

    private companion object {
        const val TAP_MS = 260L
        const val TAP_PX = 12
        const val DOUBLE_TAP_MS = 320L
        const val MOVE_COALESCE_MS = 32L
    }
}
