package dev.aiui.ui.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.aiui.AppContainer
import dev.aiui.data.ProfileDraft
import dev.aiui.data.ProfileRepository
import dev.aiui.data.db.ModelEntity
import dev.aiui.llm.ApiFormat
import dev.aiui.llm.Endpoint
import dev.aiui.llm.LlmException
import dev.aiui.llm.isLocalNetworkHost
import dev.aiui.llm.validateUrl
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class Preset(val label: String, val name: String, val format: ApiFormat, val baseUrl: String, val hint: String? = null)

val Presets = listOf(
    Preset("Anthropic", "Anthropic", ApiFormat.ANTHROPIC, "https://api.anthropic.com"),
    Preset("OpenAI", "OpenAI", ApiFormat.OPENAI, "https://api.openai.com/v1"),
    Preset(
        "Ollama", "Ollama", ApiFormat.OPENAI, "http://192.168.1.2:11434/v1",
        "Replace 192.168.1.2 with your computer's address. Start Ollama with OLLAMA_HOST=0.0.0.0 so the phone can reach it.",
    ),
    Preset(
        "LM Studio", "LM Studio", ApiFormat.OPENAI, "http://192.168.1.2:1234/v1",
        "Replace 192.168.1.2 with your computer's address, and turn on Serve on Local Network in LM Studio.",
    ),
    Preset("Custom", "", ApiFormat.OPENAI, "", "Paste the base URL from the platform's console, for example Nebius."),
)

sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Ok(val modelCount: Int) : TestState
    data class Failed(val message: String) : TestState
}

data class ProfileForm(
    val isNew: Boolean,
    val loaded: Boolean = false,
    val name: String = "",
    val format: ApiFormat = ApiFormat.ANTHROPIC,
    val baseUrl: String = "",
    val presetHint: String? = null,
    /** What is in the key field. For a saved key this stays empty until revealed or replaced. */
    val apiKey: String = "",
    val hasSavedKey: Boolean = false,
    val revealedKey: String? = null,
    val removeKey: Boolean = false,
    val allowInsecure: Boolean = false,
    val extraHeaders: String = "",
    val extraBody: String = "",
    val models: List<ModelEntity> = emptyList(),
    val test: TestState = TestState.Idle,
    val saving: Boolean = false,
) {
    val urlError: String? get() {
        if (baseUrl.isBlank()) return null
        validateUrl(baseUrl)?.let { return it }
        val url = baseUrl.trim().toHttpUrlOrNull() ?: return null
        if (!url.isHttps && !allowInsecure && !isLocalNetworkHost(url.host)) {
            return "Plain HTTP is only allowed to addresses on your network. Use https, or allow insecure connections below."
        }
        return null
    }

    val needsInsecureSwitch: Boolean get() {
        val url = baseUrl.trim().toHttpUrlOrNull() ?: return allowInsecure
        return allowInsecure || (!url.isHttps && !isLocalNetworkHost(url.host))
    }

    val headersError: String? get() = ProfileRepository.jsonObjectError(extraHeaders)
    val bodyError: String? get() = ProfileRepository.jsonObjectError(extraBody)

    val canSave: Boolean get() =
        loaded && !saving && baseUrl.isNotBlank() && urlError == null && headersError == null && bodyError == null
}

class ProfileEditViewModel(container: AppContainer, private val profileId: Long?) : ViewModel() {
    private val repo = container.profiles

    private val _form = MutableStateFlow(ProfileForm(isNew = profileId == null, loaded = profileId == null))
    val form = _form.asStateFlow()

    private val _done = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val done = _done.asSharedFlow()

    init {
        if (profileId != null) {
            viewModelScope.launch {
                val profile = repo.get(profileId)
                if (profile == null) {
                    _done.tryEmit(Unit)
                    return@launch
                }
                _form.value = ProfileForm(
                    isNew = false,
                    loaded = true,
                    name = profile.name,
                    format = ApiFormat.fromId(profile.format),
                    baseUrl = profile.baseUrl,
                    hasSavedKey = profile.apiKeyEncrypted != null,
                    allowInsecure = profile.allowInsecure,
                    extraHeaders = profile.extraHeaders.takeIf { it != "{}" }.orEmpty(),
                    extraBody = profile.extraBody.takeIf { it != "{}" }.orEmpty(),
                    models = repo.models(profileId),
                )
            }
        }
    }

    fun update(transform: (ProfileForm) -> ProfileForm) = _form.update(transform)

    fun applyPreset(preset: Preset) = _form.update {
        val previousPreset = Presets.any { p -> p.name == it.name }
        it.copy(
            name = if (it.name.isBlank() || previousPreset) preset.name else it.name,
            format = preset.format,
            baseUrl = preset.baseUrl,
            presetHint = preset.hint,
            test = TestState.Idle,
        )
    }

    fun reveal() {
        val id = profileId ?: return
        viewModelScope.launch {
            val key = repo.revealKey(id)
            _form.update { it.copy(revealedKey = key, apiKey = key.orEmpty(), removeKey = false) }
        }
    }

    fun removeKey() = _form.update { it.copy(apiKey = "", revealedKey = null, removeKey = true) }

    fun test() {
        val form = _form.value
        if (form.baseUrl.isBlank() || form.urlError != null) return
        _form.update { it.copy(test = TestState.Running) }
        viewModelScope.launch {
            val result = runCatching { repo.listRemoteModels(endpoint(form)) }
            _form.update { current ->
                result.fold(
                    onSuccess = { remote ->
                        current.copy(
                            models = ProfileRepository.mergeModels(current.models, remote, profileId ?: 0),
                            test = TestState.Ok(remote.size),
                        )
                    },
                    onFailure = { error ->
                        val message = (error as? LlmException)?.message ?: error.message ?: error.javaClass.simpleName
                        current.copy(test = TestState.Failed(message))
                    },
                )
            }
        }
    }

    fun setModelEnabled(modelId: String, enabled: Boolean) = updateModel(modelId) { it.copy(enabled = enabled) }

    fun setAllModels(enabled: Boolean) = _form.update { form -> form.copy(models = form.models.map { it.copy(enabled = enabled) }) }

    fun updateModel(modelId: String, transform: (ModelEntity) -> ModelEntity) = _form.update { form ->
        form.copy(models = form.models.map { if (it.modelId == modelId) transform(it) else it })
    }

    fun addManualModel(modelId: String) {
        val id = modelId.trim()
        if (id.isEmpty()) return
        _form.update { form ->
            if (form.models.any { it.modelId == id }) {
                form.copy(models = form.models.map { if (it.modelId == id) it.copy(enabled = true) else it })
            } else {
                form.copy(models = form.models + ModelEntity(profileId = profileId ?: 0, modelId = id, enabled = true, manual = true))
            }
        }
    }

    fun removeModel(modelId: String) = _form.update { form -> form.copy(models = form.models.filterNot { it.modelId == modelId }) }

    fun save() {
        val form = _form.value
        if (!form.canSave) return
        _form.update { it.copy(saving = true) }
        viewModelScope.launch {
            val host = form.baseUrl.trim().toHttpUrlOrNull()?.host.orEmpty()
            repo.save(
                ProfileDraft(
                    id = profileId,
                    name = form.name.ifBlank { host.ifBlank { "Profile" } },
                    format = form.format,
                    baseUrl = form.baseUrl,
                    newApiKey = newKey(form),
                    extraHeaders = form.extraHeaders,
                    extraBody = form.extraBody,
                    allowInsecure = form.allowInsecure,
                    models = form.models,
                ),
            )
            _done.tryEmit(Unit)
        }
    }

    fun delete() {
        val id = profileId ?: return
        viewModelScope.launch {
            repo.delete(id)
            _done.tryEmit(Unit)
        }
    }

    private fun newKey(form: ProfileForm): String? = when {
        form.apiKey.isNotBlank() && form.apiKey != form.revealedKey -> form.apiKey
        form.removeKey -> ""
        else -> null
    }

    private suspend fun endpoint(form: ProfileForm): Endpoint {
        val key = newKey(form) ?: profileId?.let { repo.revealKey(it) }
        return Endpoint(
            format = form.format,
            baseUrl = form.baseUrl.trim(),
            apiKey = key?.ifBlank { null },
            extraHeaders = ProfileRepository.parseHeaders(form.extraHeaders),
            extraBody = ProfileRepository.parseObject(form.extraBody),
            allowInsecure = form.allowInsecure,
        )
    }
}
