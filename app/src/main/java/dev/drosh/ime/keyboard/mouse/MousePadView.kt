/*
 * SPDX-License-Identifier: GPL-3.0-only
 */
package dev.drosh.ime.keyboard.mouse

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 * Gestures, deliberately minimal — text selection stays the terminal's own
 * long-press gesture, and is not duplicated here:
 * - press + drag              → pointer move (no button held)
 * - short tap                 → left click
 * - two-finger single tap     → right click
 *
 * While a finger is down the pad reports a move every [MOVE_COALESCE_MS],
 * even with a zero delta: that is the keep-alive the app uses to keep
 * auto-scrolling when the pointer is pushed against the top or bottom edge
 * and held there.
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

    private val mainHandler = Handler(Looper.getMainLooper())
    private var moveFlusher: Runnable? = null

    private var started = false
    private var padHold = false
    private var accX = 0
    private var accY = 0
    private var downAt = 0L
    private var movedPx = 0
    private var lastX = 0f
    private var lastY = 0f
    private var activePointerId = -1
    private var secondPointerId = -1
    private var lastMoveSent = 0L

    fun setKeyboardActionListener(listener: KeyboardActionListener) {
        keyboardActionListener = listener
    }

    fun startMouse(actionListener: KeyboardActionListener) {
        keyboardActionListener = actionListener
        findViewById<ImageButton>(R.id.mouse_pad_close).setOnClickListener { close() }
        surface = findViewById(R.id.mouse_pad_surface)
        haptic = findViewById(R.id.mouse_pad_haptic)
        surface.isHapticFeedbackEnabled = false
        surface.setOnTouchListener { _, event -> onSurfaceTouch(event); true }
        // reset only after the views exist: resetState touches the surface
        resetState()
        started = true
        send(MouseProtocol.OP_OPEN)
    }

    fun stopMouse() {
        if (!started) return // never started: nothing to release or hide
        started = false
        stopMoveFlusher()
        padHold = false
        surface.setOnTouchListener(null)
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
        padHold = false
        accX = 0
        accY = 0
        movedPx = 0
        activePointerId = -1
        secondPointerId = -1
        setArmed(false)
    }

    private fun send(op: String, dx: Int = 0, dy: Int = 0, button: Int = -1) {
        val data = Bundle().apply {
            putString(MouseProtocol.KEY_OP, op)
            if (op == MouseProtocol.OP_MOVE) {
                putInt(MouseProtocol.KEY_DX, dx)
                putInt(MouseProtocol.KEY_DY, dy)
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
        if (!::surface.isInitialized) return
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
                    downAt = SystemClock.elapsedRealtime()
                    movedPx = 0
                    accX = 0
                    accY = 0
                    padHold = true
                    setArmed(true)
                    startMoveFlusher()
                } else if (secondPointerId == -1) {
                    secondPointerId = event.getPointerId(event.actionIndex)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (activePointerId == -1 || !padHold) return true
                val index = event.findPointerIndex(activePointerId)
                if (index < 0) return true
                val x = event.getX(index)
                val y = event.getY(index)
                val dx = x - lastX
                val dy = y - lastY
                lastX = x
                lastY = y
                movedPx += Math.abs(dx.toInt()) + Math.abs(dy.toInt())
                accX += dx.toInt()
                accY += dy.toInt()
                flushMove()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val quick = SystemClock.elapsedRealtime() - downAt < TAP_MS && movedPx < TAP_PX
                val hadSecondFinger = secondPointerId != -1
                padHold = false
                stopMoveFlusher()
                flushMove()

                when {
                    quick && hadSecondFinger -> {
                        // two-finger single tap = right click
                        hapticFeedback()
                        flashHaptic()
                        send(MouseProtocol.OP_CLICK, button = 2)
                    }
                    quick -> {
                        hapticFeedback()
                        flashHaptic()
                        send(MouseProtocol.OP_CLICK, button = 0)
                    }
                }
                resetState()
            }
        }
        return true
    }

    /**
     * Reports the accumulated deltas at most every [MOVE_COALESCE_MS], so a
     * fast drag does not flood the binder. A periodic flusher keeps reporting
     * while a finger is down even when it stops moving: that is what tells
     * the app the pad is still held against the edge (auto-scroll).
     */
    private fun startMoveFlusher() {
        stopMoveFlusher()
        val flusher = object : Runnable {
            override fun run() {
                if (!padHold) return
                // forced: a zero-delta report is the keep-alive that tells the
                // app the pad is still held against the edge
                flushMove(force = true)
                mainHandler.postDelayed(this, MOVE_COALESCE_MS)
            }
        }
        moveFlusher = flusher
        mainHandler.postDelayed(flusher, MOVE_COALESCE_MS)
    }

    private fun stopMoveFlusher() {
        moveFlusher?.let { mainHandler.removeCallbacks(it) }
        moveFlusher = null
    }

    private fun flushMove(force: Boolean = false) {
        if (!force) {
            if (accX == 0 && accY == 0) return
            if (SystemClock.elapsedRealtime() - lastMoveSent < MOVE_COALESCE_MS) return
        }
        lastMoveSent = SystemClock.elapsedRealtime()
        send(MouseProtocol.OP_MOVE, dx = accX, dy = accY)
        accX = 0
        accY = 0
    }

    private companion object {
        const val TAP_MS = 260L
        const val TAP_PX = 12
        const val MOVE_COALESCE_MS = 32L
    }
}
