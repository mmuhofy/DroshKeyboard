// SPDX-License-Identifier: GPL-3.0-only
package dev.drosh.ime.settings

import android.content.Context
import dev.drosh.ime.keyboard.internal.KeyboardIconsSet
import dev.drosh.ime.latin.settings.Settings
import dev.drosh.ime.latin.utils.SubtypeSettings

// file is meant for making compose previews work

fun initPreview(context: Context) {
    Settings.init(context)
    SubtypeSettings.init(context)
    Settings.getInstance().loadSettings(context)
    SettingsActivity.settingsContainer = SettingsContainer(context)
    KeyboardIconsSet.instance.loadIcons(context)
}
