// Derived from KarooFirefly (https://github.com/derstrassi/karoofirefly),
// Copyright (c) 2026 derstrassi, MIT License. Adapted for LightPilot.

package io.github.opaunke82.lightpilot.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import timber.log.Timber

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lightpilot_settings")

class PreferencesRepository(private val context: Context) {

    companion object {
        private val SETTINGS_KEY = stringPreferencesKey("settings")
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { preferences ->
        val raw = preferences[SETTINGS_KEY]
        if (raw == null) {
            AppSettings()
        } else {
            try {
                json.decodeFromString(AppSettings.serializer(), raw).migrated()
            } catch (e: Exception) {
                Timber.e(e, "Settings could not be read, using defaults")
                AppSettings()
            }
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }

    suspend fun updateSettings(settings: AppSettings) {
        context.dataStore.edit { preferences ->
            preferences[SETTINGS_KEY] = json.encodeToString(AppSettings.serializer(), settings)
        }
    }
}
