// SPDX-License-Identifier: GPL-3.0-only
package dev.drosh.ime.settings.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import dev.drosh.ime.latin.R
import dev.drosh.ime.latin.utils.Log
import dev.drosh.ime.latin.utils.SpecialKey
import dev.drosh.ime.latin.utils.SpecialKey.Companion.MAX_WIDTH
import dev.drosh.ime.latin.utils.SpecialKey.Companion.MIN_WIDTH
import dev.drosh.ime.latin.utils.SpecialKey.Companion.PREF
import dev.drosh.ime.latin.utils.SpecialKey.Companion.reset
import dev.drosh.ime.latin.utils.Theme
import dev.drosh.ime.latin.utils.getActivity
import dev.drosh.ime.latin.utils.prefs
import dev.drosh.ime.latin.utils.previewDark
import dev.drosh.ime.settings.SearchSettingsScreen
import dev.drosh.ime.settings.SettingsActivity
import dev.drosh.ime.settings.initPreview
import dev.drosh.ime.settings.preferences.Preference
import kotlin.math.abs
import kotlin.math.roundToInt

private const val DRAG_REMOVE_THRESHOLD_DP = 90

/**
 * Settings for the quickbar's special keys row.
 *
 * The screen is a small editor: the preview on top shows the row as it will
 * look, the card below holds every key. Drag a key from the card into the row
 * to add it, drag a row key away to remove it, drag sideways to reorder, and
 * tap a row key to enter scale mode where a horizontal drag widens or
 * narrows it.
 */
@Composable
fun SpecialKeysScreen(
    onClickBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val b = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")

    // all keys with state and width; the row shows the enabled ones in order
    var entries by remember { mutableStateOf(SpecialKey.parse(prefs)) }
    var selected by remember { mutableStateOf<SpecialKey?>(null) }
    var rowWidthPx by remember { mutableStateOf(0f) }
    var hintRes by remember { mutableStateOf(R.string.special_keys_default_note) }
    val density = LocalDensity.current.density

    fun persist(next: List<SpecialKey.Entry>) {
        entries = next
        SpecialKey.write(prefs, next)
    }

    val active = entries.filter { it.enabled }
    val pool = SpecialKey.entries.filter { key -> entries.none { it.key == key && it.enabled } }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.special_keys_title),
        settings = emptyList(),
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
        ) { innerPadding ->
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .then(Modifier.padding(innerPadding))
            ) {
                Text(
                    text = stringResource(R.string.special_keys_preview),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp),
                )

                // ---------- preview row ----------
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .background(Color(0xFF15171C), RoundedCornerShape(16.dp))
                        .onSizeChanged { rowWidthPx = it.width.toFloat() }
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(8.dp),
                    ) {
                        active.forEach { entry ->
                            SpecialKeyChipPreview(
                                entry = entry,
                                selected = selected == entry.key,
                                onClick = {
                                    selected = if (selected == entry.key) null else entry.key
                                    hintRes = if (selected == null) R.string.special_keys_default_note
                                    else R.string.special_keys_scale_hint
                                },
                                onDrag = { dx, dy ->
                                    when {
                                        selected == entry.key -> {
                                            // scale mode: widen / narrow
                                            val delta = if (rowWidthPx > 0f) dx / rowWidthPx else 0f
                                            persist(entries.map {
                                                if (it.key == entry.key)
                                                    it.copy(width = (it.width + delta).coerceIn(MIN_WIDTH, MAX_WIDTH))
                                                else it
                                            })
                                        }
                                        dy > DRAG_REMOVE_THRESHOLD_DP * density -> {
                                            // dragged onto the pool card: remove
                                            persist(entries.map {
                                                if (it.key == entry.key) it.copy(enabled = false) else it
                                            })
                                            selected = null
                                            hintRes = R.string.special_keys_default_note
                                        }
                                        else -> {
                                            // reorder by accumulated horizontal travel
                                            val step = (rowWidthPx / active.size.coerceAtLeast(1))
                                            val shift = (dx / step.coerceAtLeast(1f)).roundToInt()
                                            if (shift != 0) moveEntry(entries, entry.key, shift)?.let { persist(it) }
                                        }
                                    }
                                },
                            )
                        }
                    }
                    if (active.isEmpty()) {
                        Text(
                            text = stringResource(R.string.special_keys_row_empty),
                            color = Color(0xFF6B7080),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }

                Text(
                    text = stringResource(hintRes),
                    color = Color(0xFF8A90A0),
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )

                Preference(
                    name = stringResource(R.string.special_keys_reset),
                    onClick = {
                        reset(prefs)
                        entries = SpecialKey.parse(prefs)
                        selected = null
                    },
                )

                // ---------- pool card ----------
                Text(
                    text = stringResource(R.string.special_keys_hidden),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 6.dp),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .background(Color(0xFF15171C), RoundedCornerShape(16.dp))
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        pool.forEach { key ->
                            SpecialKeyPoolRow(
                                key = key,
                                onClick = {
                                    // tap adds to the end of the row
                                    persist(entries.map {
                                        if (it.key == key) it.copy(enabled = true) else it
                                    })
                                },
                                onDrag = { dx ->
                                    // drag adds; the horizontal travel picks the position
                                    val shift = (dx / (rowWidthPx / (active.size + 1).coerceAtLeast(1))).roundToInt()
                                    val target = shift.coerceIn(0, active.size)
                                    persist(entries.map {
                                        if (it.key == key) it.copy(enabled = true) else it
                                    }.toMutableList().also { list ->
                                        val from = list.indexOfFirst { it.key == key }
                                        if (from >= 0) list.add(target.coerceIn(0, list.size - 1), list.removeAt(from))
                                    })
                                },
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.special_keys_pool_hint),
                    color = Color(0xFF8A90A0),
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SpecialKeyChipPreview(
    entry: SpecialKey.Entry,
    selected: Boolean,
    onClick: () -> Unit,
    onDrag: (Float, Float) -> Unit,
) {
    val armedTint = entry.key.isSticky
    Box(
        modifier = Modifier
            .background(
                if (selected) Color(0xFF3B3E46) else if (armedTint) Color(0xFF23304A) else Color(0xFF1E2024),
                RoundedCornerShape(50),
            )
            .pointerInput(entry.key) {
                detectTapGestures(onTap = { onClick() })
            }
            .pointerInput(entry.key, selected) {
                detectDragGestures { _, dragAmount -> onDrag(dragAmount.x, dragAmount.y) }
            }
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(
            text = stringResource(entry.key.labelResId),
            color = if (armedTint) Color(0xFFB2C8FF) else Color(0xFFE6E6EE),
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun SpecialKeyPoolRow(
    key: SpecialKey,
    onClick: () -> Unit,
    onDrag: (Float) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(Color(0xFF1E2024), RoundedCornerShape(12.dp))
            .pointerInput(key) {
                detectTapGestures(onTap = { onClick() })
            }
            .pointerInput(key) {
                detectDragGestures { _, dragAmount -> onDrag(dragAmount.x) }
            }
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(
            text = "≡",
            color = Color(0xFF6B7080),
            fontSize = 15.sp,
            modifier = Modifier.padding(end = 10.dp),
        )
        Text(
            text = stringResource(key.labelResId),
            color = Color(0xFFE6E6EE),
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = specialKeySub(key),
            color = Color(0xFF6B7080),
            fontSize = 11.sp,
        )
    }
}

private fun moveEntry(
    entries: List<SpecialKey.Entry>,
    key: SpecialKey,
    shift: Int,
): List<SpecialKey.Entry>? {
    val active = entries.filter { it.enabled }
    val from = active.indexOfFirst { it.key == key }
    if (from < 0) return null
    val to = (from + shift).coerceIn(0, active.size - 1)
    if (to == from) return null
    val moved = active.toMutableList()
    moved.add(to, moved.removeAt(from))
    val rest = entries.filterNot { it.enabled }
    return moved + rest
}

private fun specialKeySub(key: SpecialKey): String = when {
    key.isSticky -> "sticky"
    key.isFn -> "F1-F12"
    key.isArrow -> "arrow"
    key.emitText != null -> "text"
    else -> ""
}

@Composable
private fun Modifier.onWidthMeasured(onWidth: (Float) -> Unit): Modifier {
    return this.then(
        Modifier.background(Color.Transparent).onGloballyPositionedCompat(onWidth)
    )
}

@Preview
@Composable
private fun PreviewSpecialKeysScreen() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            SpecialKeysScreen { }
        }
    }
}
