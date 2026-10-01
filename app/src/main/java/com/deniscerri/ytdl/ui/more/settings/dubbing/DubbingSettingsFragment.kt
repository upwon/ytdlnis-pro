package com.deniscerri.ytdl.ui.more.settings.dubbing

import android.annotation.SuppressLint
import android.os.Bundle
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.ui.more.settings.BaseSettingsFragment
import com.deniscerri.ytdl.ui.more.settings.SettingsRegistry

class DubbingSettingsFragment : BaseSettingsFragment() {
    override val title: Int = R.string.dubbing_settings

    @SuppressLint("RestrictedApi")
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val preferenceXMLRes = R.xml.dubbing_preferences
        setPreferencesFromResource(preferenceXMLRes, rootKey)
        SettingsRegistry.bindFragment(this, preferenceXMLRes)
    }
}
