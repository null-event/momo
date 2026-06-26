package com.nullevent.aquarium

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat

/**
 * Settings screen launched from the wallpaper picker (declared via
 * `android:settingsActivity` in res/xml/wallpaper.xml). Writes into the same
 * [AquariumSettings.PREFS] store the wallpaper engine observes, so changes
 * apply live.
 */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, SettingsFragment())
            .commit()
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.sharedPreferencesName = AquariumSettings.PREFS
            setPreferencesFromResource(R.xml.aquarium_prefs, rootKey)
        }
    }
}
