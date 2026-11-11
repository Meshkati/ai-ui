package dev.aiui.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppPrefs(
    val displayName: String = "",
    val systemPrompt: String = "",
    val defaultProfileId: Long? = null,
    val defaultModelId: String? = null,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
)

private val Context.dataStore by preferencesDataStore(name = "prefs")

class PrefsRepository(private val context: Context) {

    val prefs: Flow<AppPrefs> = context.dataStore.data.map { it.toAppPrefs() }

    suspend fun current(): AppPrefs = prefs.first()

    suspend fun setPersonalization(displayName: String, systemPrompt: String) {
        context.dataStore.edit {
            it[DISPLAY_NAME] = displayName.trim()
            it[SYSTEM_PROMPT] = systemPrompt
        }
    }

    suspend fun setDefaultModel(profileId: Long, modelId: String) {
        context.dataStore.edit {
            it[DEFAULT_PROFILE] = profileId
            it[DEFAULT_MODEL] = modelId
        }
    }

    suspend fun setTheme(theme: ThemeMode) {
        context.dataStore.edit { it[THEME] = theme.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.dataStore.edit { it[DYNAMIC_COLOR] = enabled }
    }

    private fun Preferences.toAppPrefs() = AppPrefs(
        displayName = this[DISPLAY_NAME].orEmpty(),
        systemPrompt = this[SYSTEM_PROMPT].orEmpty(),
        defaultProfileId = this[DEFAULT_PROFILE],
        defaultModelId = this[DEFAULT_MODEL],
        theme = this[THEME]?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: ThemeMode.SYSTEM,
        dynamicColor = this[DYNAMIC_COLOR] ?: true,
    )

    private companion object {
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val DEFAULT_PROFILE = longPreferencesKey("default_profile")
        val DEFAULT_MODEL = stringPreferencesKey("default_model")
        val THEME = stringPreferencesKey("theme")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    }
}
