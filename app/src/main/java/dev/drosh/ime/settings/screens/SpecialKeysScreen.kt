// SPDX-License-Identifier: GPL-3.0-only
package dev.drosh.ime.settings.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import dev.drosh.ime.latin.R
import dev.drosh.ime.latin.common.Constants.Separators
import dev.drosh.ime.latin.utils.Log
import dev.drosh.ime.latin.utils.SpecialKey
import dev.drosh.ime.latin.utils.SpecialKey.Companion.PREF
import dev.drosh.ime.latin.utils.SpecialKey.Companion.default
import dev.drosh.ime.latin.utils.Theme
import dev.drosh.ime.latin.utils.getActivity
import dev.drosh.ime.latin.utils.prefs
import dev.drosh.ime.latin.utils.previewDark
import dev.drosh.ime.settings.SearchSettingsScreen
import dev.drosh.ime.settings.Setting
import dev.drosh.ime.settings.SettingsActivity
import dev.drosh.ime.settings.initPreview
import dev.drosh.ime.settings.preferences.Preference
import dev.drosh.ime.settings.preferences.ReorderSwitchPreference

/**
 * Settings for the quickbar's special keys row: preview, per-key enable,
 * drag-reorder and reset. Mirrors the quickbar mockup.
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
                // preview
                Text(
                    text = stringResource(R.string.special_keys_preview),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp),
                )
                Surface(
                    color = Color(0xFF15171C),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                ) {
                    SpecialKeysPreviewRow()
                }
                Text(
                    text = stringResource(R.string.special_keys_default_note),
                    color = Color(0xFF8A90A0),
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )

                // reorder (native drag dialog with switches) + reset
                val reorderSetting = remember {
                    Setting(ctx, PREF, R.string.special_keys_reorder) { setting ->
                        ReorderSwitchPreference(setting, default)
                    }
                }
                reorderSetting.Preference()
                Preference(
                    name = stringResource(R.string.special_keys_reset),
                    onClick = {
                        prefs.edit { remove(PREF) }
                    },
                )

                // active / hidden lists, recomposing on every preference change
                val active = remember(prefs, b?.value) { enabledKeysWithState(prefs).filter { it.second } }
                val hidden = remember(prefs, b?.value) { enabledKeysWithState(prefs).filterNot { it.second } }

                Text(
                    text = stringResource(R.string.special_keys_active),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp),
                )
                active.forEach { (key, _) ->
                    SpecialKeyRow(
                        label = stringResource(key.labelResId),
                        sub = specialKeySub(key),
                        checked = true,
                        onToggle = {
                            setSpecialKeyEnabled(prefs, key, false)
                        },
                    )
                }

                Text(
                    text = stringResource(R.string.special_keys_hidden),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp),
                )
                hidden.forEach { (key, _) ->
                    SpecialKeyRow(
                        label = stringResource(key.labelResId),
                        sub = specialKeySub(key),
                        checked = false,
                        onToggle = {
                            setSpecialKeyEnabled(prefs, key, true)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SpecialKeysPreviewRow() {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val active = remember(prefs) {
        prefs.getString(PREF, default)!!.split(Separators.ENTRY)
            .filter { it.split(Separators.KV).last().toBoolean() }
            .mapNotNull { runCatching { SpecialKey.valueOf(it.split(Separators.KV).first()) }.getOrNull() }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(10.dp),
    ) {
        Text(
            text = "⇄",
            color = Color(0xFFB2C8FF),
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            modifier = Modifier
                .background(Color(0xFF1E2024), RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 7.dp),
        )
        active.forEach { key ->
            val isSticky = key.isSticky
            Text(
                text = stringResource(key.labelResId),
                color = if (isSticky) Color(0xFFB2C8FF) else Color(0xFFE6E6EE),
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier
                    .background(
                        if (isSticky) Color(0xFF23304A) else Color(0xFF1E2024),
                        RoundedCornerShape(50),
                    )
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun SpecialKeyRow(
    label: String,
    sub: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .background(Color(0xFF1E2024), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = "≡",
            color = Color(0xFF6B7080),
            fontSize = 16.sp,
            modifier = Modifier.padding(end = 10.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, color = Color(0xFFE6E6EE), fontSize = 14.sp)
            Text(text = sub, color = Color(0xFF6B7080), fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

private fun specialKeySub(key: SpecialKey): String = when {
    key.isSticky -> "sticky — applies to the next key"
    key.isFn -> "opens the F1-F12 layer"
    key.isArrow -> "arrow key"
    key.emitText != null -> "sent through the terminal text channel"
    else -> ""
}

private fun enabledKeysWithState(prefs: android.content.SharedPreferences): List<Pair<SpecialKey, Boolean>> =
    prefs.getString(PREF, default)!!
        .split(Separators.ENTRY)
        .mapNotNull { entry ->
            val parts = entry.split(Separators.KV)
            if (parts.size != 2) return@mapNotNull null
            runCatching { SpecialKey.valueOf(parts[0]) }.getOrNull()?.let { it to parts[1].toBoolean() }
        }

private fun setSpecialKeyEnabled(
    prefs: android.content.SharedPreferences,
    key: SpecialKey,
    enabled: Boolean,
) {
    val current = enabledKeysWithState(prefs).toMutableList()
    val index = current.indexOfFirst { it.first == key }
    if (index < 0) return
    current[index] = key to enabled
    val value = current.joinToString(Separators.ENTRY) { it.first.name + Separators.KV + it.second }
    prefs.edit { putString(PREF, value) }
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
