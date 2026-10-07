// SPDX-License-Identifier: GPL-3.0-only

package dev.drosh.ime.keyboard.clipboard

interface OnKeyEventListener {

    fun onKeyDown(clipId: Long)

    fun onKeyUp(clipId: Long)

}