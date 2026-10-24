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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import dev.drosh.ime.latin.common.Constants.Separators
import dev.drosh.ime.latin.utils.Log
import dev.drosh.ime.latin.utils.SpecialKeyEntry
import dev.drosh.ime.latin.utils.SpecialKeysStore
import dev.drosh.ime.latin.utils.Theme
import dev.drosh.ime.latin.utils.getActivity
import dev.drosh.ime.latin.utils.prefs
import dev.drosh.ime.latin.utils.previewDark
import dev.drosh.ime.settings.SearchSettingsScreen
import dev.drosh.ime.settings.SettingsActivity
import dev.drosh.ime.settings.initPreview
import dev.drosh.ime.settings.preferences.Preference
import kotlin.math.roundToInt

private const val DRAG_REMOVE_THRESHOLD_DP = 90

/**
 * Settings for the quickbar's special keys row.
 *
 * The screen is a small editor: the preview on top shows the row as it will
 * look, the card below holds every key. Drag a key from the card into the row
 * to add it, drag a row key away to remove it, drag sideways to reorder, and
 * tap a row key to enter scale mode where a horizontal drag widens or
 * narrows it. A "Custom key" button appends user-created combinations
 * (modifier + character).
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

    var entries by remember { mutableStateOf(SpecialKeysStore.parse(prefs)) }
    var selected by remember { mutableStateOf<String?>(null) }
    var rowWidthPx by remember { mutableStateOf(0f) }
    var hintRes by remember { mutableStateOf(R.string.special_keys_default_note) }
    var showCustomDialog by remember { mutableStateOf(false) }
    val density = LocalDensity.current.density

    fun persist(next: List<SpecialKeyEntry>) {
        entries = next
        SpecialKeysStore.write(prefs, next)
    }

    val active = entries.filter { it.enabled }
    val pool = entries.filterNot { it.enabled }

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
                                selected = selected == entry.id,
                                onClick = {
                                    selected = if (selected == entry.id) null else entry.id
                                    hintRes = if (selected == null) R.string.special_keys_default_note
                                    else R.string.special_keys_scale_hint
                                },
                                onDrag = { dx, dy ->
                                    when {
                                        selected == entry.id -> {
                                            // scale mode: widen / narrow
                                            val delta = if (rowWidthPx > 0f) dx / rowWidthPx else 0f
                                            persist(entries.map {
                                                if (it.id == entry.id)
                                                    it.copy(width = it.width.plus(delta).coerceIn(SpecialKeysStore.MIN_WIDTH, SpecialKeysStore.MAX_WIDTH))
                                                else it
                                            })
                                        }
                                        dy > DRAG_REMOVE_THRESHOLD_DP * density -> {
                                            // dragged onto the pool card: remove
                                            persist(entries.map {
                                                if (it.id == entry.id) it.copy(enabled = false) else it
                                            })
                                            selected = null
                                            hintRes = R.string.special_keys_default_note
                                        }
                                        else -> {
                                            // reorder by accumulated horizontal travel
                                            val step = (rowWidthPx / active.size.coerceAtLeast(1))
                                            val shift = (dx / step.coerceAtLeast(1f)).roundToInt()
                                            if (shift != 0) moveEntry(entries, entry.id, shift)?.let { persist(it) }
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
                    name = stringResource(R.string.special_keys_add_custom),
                    description = stringResource(R.string.special_keys_add_custom_summary),
                    onClick = { showCustomDialog = true },
                )
                Preference(
                    name = stringResource(R.string.special_keys_reset),
                    onClick = {
                        SpecialKeysStore.reset(prefs)
                        entries = SpecialKeysStore.parse(prefs)
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
                        pool.forEach { entry ->
                            SpecialKeyPoolRow(
                                label = entryLabel(entry, ctx),
                                sub = entrySub(entry),
                                removable = entry is SpecialKeyEntry.CustomKey,
                                onClick = {
                                    // tap adds to the end of the row
                                    persist(entries.map {
                                        if (it.id == entry.id) it.copy(enabled = true) else it
                                    })
                                },
                                onDrag = { dx ->
                                    // drag adds; the horizontal travel picks the position
                                    val shift = (dx / (rowWidthPx / (active.size + 1).coerceAtLeast(1))).roundToInt()
                                    val target = shift.coerceIn(0, active.size)
                                    persist(entries.map {
                                        if (it.id == entry.id) it.copy(enabled = true) else it
                                    }.toMutableList().also { list ->
                                        val from = list.indexOfFirst { it.id == entry.id }
                                        if (from >= 0) list.add(target.coerceIn(0, list.size - 1), list.removeAt(from))
                                    })
                                },
                                onRemove = {
                                    SpecialKeysStore.removeCustomKey(prefs, entry.id)
                                    entries = SpecialKeysStore.parse(prefs)
                                },
                            )
                        }
                        if (pool.isEmpty()) {
                            Text(
                                text = stringResource(R.string.special_keys_pool_empty),
                                color = Color(0xFF6B7080),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(16.dp),
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

    if (showCustomDialog) {
        CustomKeyDialog(
            onDismiss = { showCustomDialog = false },
            onConfirmed = { metaState, keyCode ->
                SpecialKeysStore.addCustomKey(prefs, metaState, keyCode)
                entries = SpecialKeysStore.parse(prefs)
                showCustomDialog = false
            },
        )
    }
}

@Composable
private fun CustomKeyDialog(
    onDismiss: () -> Unit,
    onConfirmed: (Int, Int) -> Unit,
) {
    // modifier toggles + a character: the combination the chip will send
    var ctrl by remember { mutableStateOf(true) }
    var alt by remember { mutableStateOf(false) }
    var shift by remember { mutableStateOf(false) }
    var character by remember { mutableStateOf("k") }

    val metaState = buildList {
        if (ctrl) add(android.view.KeyEvent.META_CTRL_ON)
        if (alt) add(android.view.KeyEvent.META_ALT_ON)
        if (shift) add(android.view.KeyEvent.META_SHIFT_ON)
    }.reduceOrNull { acc, bit -> acc or bit } ?: 0
    val keyCode = character.firstOrNull()?.code ?: 0
    val valid = keyCode > 0 && metaState != 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.special_keys_custom_title)) },
        text = {
            Column {
                Text(stringResource(R.string.special_keys_custom_summary), fontSize = 12.sp)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 8.dp),
                ) {
                    ModifierToggle("Ctrl", ctrl) { ctrl = it }
                    ModifierToggle("Alt", alt) { alt = it }
                    ModifierToggle("Shift", shift) { shift = it }
                }
                androidx.compose.material3.OutlinedTextField(
                    value = character,
                    onValueChange = { character = it.take(1) },
                    label = { Text(stringResource(R.string.special_keys_custom_character)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirmed(metaState, keyCode) }) {
                Text(stringResource(R.string.snippet_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.snippet_cancel)) }
        },
    )
}

private inline fun buildInt(block: androidx.compose.runtime.MutableList<Int>.() -> Unit): Int {
    val list = mutableListOf<Int>()
    list.block()
    return list.firstOrNull() ?: 0
}

@Composable
private fun ModifierToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Text(
        text = label,
        color = if (checked) Color(0xFFB2C8FF) else Color(0xFF6B7080),
        fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal,
        fontSize = 13.sp,
        modifier = Modifier
            .background(
                if (checked) Color(0xFF23304A) else Color(0xFF1E2024),
                RoundedCornerShape(50),
            )
            .pointerInput(label, checked) {
                detectTapGestures(onTap = { onChange(!checked) })
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun SpecialKeyChipPreview(
    entry: SpecialKeyEntry,
    selected: Boolean,
    onClick: () -> Unit,
    onDrag: (Float, Float) -> Unit,
) {
    val sticky = (entry as? SpecialKeyEntry.BuiltIn)?.key?.isSticky == true
    Box(
        modifier = Modifier
            .background(
                when {
                    selected -> Color(0xFF3B3E46)
                    sticky -> Color(0xFF23304A)
                    else -> Color(0xFF1E2024)
                },
                RoundedCornerShape(50),
            )
            .pointerInput(entry.id) {
                detectTapGestures(onTap = { onClick() })
            }
            .pointerInput(entry.id, selected) {
                detectDragGestures { _, dragAmount -> onDrag(dragAmount.x, dragAmount.y) }
            }
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(
            text = entryLabel(entry, ctx),
            color = if (sticky) Color(0xFFB2C8FF) else Color(0xFFE6E6EE),
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun SpecialKeyPoolRow(
    label: String,
    sub: String,
    removable: Boolean,
    onClick: () -> Unit,
    onDrag: (Float) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(Color(0xFF1E2024), RoundedCornerShape(12.dp))
            .pointerInput(label) {
                detectTapGestures(onTap = { onClick() })
            }
            .pointerInput(label) {
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
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, color = Color(0xFFE6E6EE), fontSize = 14.sp)
            Text(text = sub, color = Color(0xFF6B7080), fontSize = 11.sp)
        }
        if (removable) {
            Text(
                text = "✕",
                color = Color(0xFFB26B70),
                fontSize = 14.sp,
                modifier = Modifier
                    .pointerInput(label) {
                        detectTapGestures(onTap = { onRemove() })
                    }
                    .padding(6.dp),
            )
        }
    }
}

private fun entryLabel(entry: SpecialKeyEntry, ctx: android.content.Context): String = when (entry) {
    is SpecialKeyEntry.BuiltIn -> ctx.getString(entry.key.labelResId)
    is SpecialKeyEntry.CustomKey -> entry.label
}

private fun entrySub(entry: SpecialKeyEntry): String = when (entry) {
    is SpecialKeyEntry.BuiltIn -> when {
        entry.key.isSticky -> "sticky"
        entry.key.isFn -> "F1-F12"
        entry.key.isArrow -> "arrow"
        entry.key.emitText != null -> "text"
        else -> ""
    }
    is SpecialKeyEntry.CustomKey -> "combination"
}

private fun moveEntry(
    entries: List<SpecialKeyEntry>,
    id: String,
    shift: Int,
): List<SpecialKeyEntry>? {
    val active = entries.filter { it.enabled }
    val from = active.indexOfFirst { it.id == id }
    if (from < 0) return null
    val to = (from + shift).coerceIn(0, active.size - 1)
    if (to == from) return null
    val moved = active.toMutableList()
    moved.add(to, moved.removeAt(from))
    val rest = entries.filterNot { it.enabled }
    return moved + rest
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
