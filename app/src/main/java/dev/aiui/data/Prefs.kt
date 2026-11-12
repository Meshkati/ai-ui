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

/** A model on one of the saved profiles. */
data class ModelChoice(val profileId: Long, val modelId: String)

data class AppPrefs(
    val displayName: String = "",
    val systemPrompt: String = "",
    val defaultProfileId: Long? = null,
    val defaultModelId: String? = null,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    /** Turns what the user says into composer text. Null when voice input is off. */
    val speechToText: ModelChoice? = null,
    /** Reads replies aloud. Null when read aloud is off. */
    val textToSpeech: ModelChoice? = null,
)

/** What the update check last learned from GitHub ([dev.aiui.update.UpdateChecker]). */
data class UpdatePrefs(
    /** The latest release tag GitHub reported, e.g. "v0.2.0". Empty before the first check. */
    val latestRelease: String = "",
    /** The newest release the chat screen already told the user about. */
    val toldRelease: String = "",
    /** When the app may ask GitHub again, in milliseconds since the epoch. */
    val nextCheck: Long = 0,
)

private val Context.dataStore by preferencesDataStore(name = "prefs")

class PrefsRepository(private val context: Context) {

    val prefs: Flow<AppPrefs> = context.dataStore.data.map { it.toAppPrefs() }

    val updates: Flow<UpdatePrefs> = context.dataStore.data.map {
        UpdatePrefs(
            latestRelease = it[LATEST_RELEASE].orEmpty(),
            toldRelease = it[TOLD_RELEASE].orEmpty(),
            nextCheck = it[NEXT_UPDATE_CHECK] ?: 0,
        )
    }

    suspend fun current(): AppPrefs = prefs.first()

    suspend fun setLatestRelease(tag: String, nextCheck: Long) {
        context.dataStore.edit {
            it[LATEST_RELEASE] = tag
            it[NEXT_UPDATE_CHECK] = nextCheck
        }
    }

    suspend fun setNextUpdateCheck(at: Long) {
        context.dataStore.edit { it[NEXT_UPDATE_CHECK] = at }
    }

    suspend fun setToldRelease(tag: String) {
        context.dataStore.edit { it[TOLD_RELEASE] = tag }
    }

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

    suspend fun setSpeechToText(choice: ModelChoice?) = setChoice(STT_PROFILE, STT_MODEL, choice)

    suspend fun setTextToSpeech(choice: ModelChoice?) = setChoice(TTS_PROFILE, TTS_MODEL, choice)

    private suspend fun setChoice(profileKey: Preferences.Key<Long>, modelKey: Preferences.Key<String>, choice: ModelChoice?) {
        context.dataStore.edit {
            if (choice == null) {
                it.remove(profileKey)
                it.remove(modelKey)
            } else {
                it[profileKey] = choice.profileId
                it[modelKey] = choice.modelId
            }
        }
    }

    private fun Preferences.choice(profileKey: Preferences.Key<Long>, modelKey: Preferences.Key<String>): ModelChoice? {
        val profileId = this[profileKey] ?: return null
        val modelId = this[modelKey] ?: return null
        return ModelChoice(profileId, modelId)
    }

    private fun Preferences.toAppPrefs() = AppPrefs(
        displayName = this[DISPLAY_NAME].orEmpty(),
        systemPrompt = this[SYSTEM_PROMPT].orEmpty(),
        defaultProfileId = this[DEFAULT_PROFILE],
        defaultModelId = this[DEFAULT_MODEL],
        theme = this[THEME]?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: ThemeMode.SYSTEM,
        dynamicColor = this[DYNAMIC_COLOR] ?: true,
        speechToText = choice(STT_PROFILE, STT_MODEL),
        textToSpeech = choice(TTS_PROFILE, TTS_MODEL),
    )

    private companion object {
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val DEFAULT_PROFILE = longPreferencesKey("default_profile")
        val DEFAULT_MODEL = stringPreferencesKey("default_model")
        val THEME = stringPreferencesKey("theme")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val STT_PROFILE = longPreferencesKey("stt_profile")
        val STT_MODEL = stringPreferencesKey("stt_model")
        val TTS_PROFILE = longPreferencesKey("tts_profile")
        val TTS_MODEL = stringPreferencesKey("tts_model")
        val LATEST_RELEASE = stringPreferencesKey("latest_release")
        val TOLD_RELEASE = stringPreferencesKey("told_release")
        val NEXT_UPDATE_CHECK = longPreferencesKey("next_update_check")
    }
}
