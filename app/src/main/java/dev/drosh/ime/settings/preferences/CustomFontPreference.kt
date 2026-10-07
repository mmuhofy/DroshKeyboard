// SPDX-License-Identifier: GPL-3.0-only
package dev.drosh.ime.settings.preferences

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.drosh.ime.keyboard.KeyboardTypeface
import dev.drosh.ime.keyboard.KeyboardSwitcher
import dev.drosh.ime.latin.R
import dev.drosh.ime.latin.common.FileUtils
import dev.drosh.ime.latin.utils.DeviceProtectedUtils
import dev.drosh.ime.settings.Setting
import dev.drosh.ime.settings.dialogs.ConfirmationDialog
import dev.drosh.ime.settings.dialogs.InfoDialog
import java.io.File

@Composable
fun CustomFontPreference(setting: Setting, fontFile: File, title: Int) {
    val ctx = LocalContext.current
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var showErrorDialog by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = it.data?.data ?: return@rememberLauncherForActivityResult
        val tempFile = File(DeviceProtectedUtils.getFilesDir(ctx), "temp_file")
        FileUtils.copyContentUriToNewFile(uri, ctx, tempFile)
        try {
            Typeface.createFromFile(tempFile)
            fontFile.delete()
            tempFile.renameTo(fontFile)
            KeyboardTypeface.clearCache()
            KeyboardSwitcher.getInstance().setThemeNeedsReload()
        } catch (_: Exception) {
            showErrorDialog = true
            tempFile.delete()
        }
    }
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType("*/*")
    Preference(
        name = setting.title,
        onClick = {
            if (fontFile.exists())
                showDialog = true
            else launcher.launch(intent)
        },
    )
    if (showDialog)
        ConfirmationDialog(
            onDismissRequest = { showDialog = false },
            onConfirmed = { launcher.launch(intent) },
            onNeutral = {
                showDialog = false
                fontFile.delete()
                KeyboardTypeface.clearCache()
                KeyboardSwitcher.getInstance().setThemeNeedsReload()
            },
            neutralButtonText = stringResource(R.string.delete),
            confirmButtonText = stringResource(R.string.load),
            title = { Text(stringResource(title)) }
        )
    if (showErrorDialog)
        InfoDialog(stringResource(R.string.file_read_error)) { showErrorDialog = false }
}
