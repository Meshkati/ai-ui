package dev.aiui.data

import dev.aiui.data.db.AppDatabase
import dev.aiui.data.db.ModelEntity
import dev.aiui.data.db.ProfileEntity
import dev.aiui.llm.ApiFormat
import dev.aiui.llm.Endpoint
import dev.aiui.llm.LenientJson
import dev.aiui.llm.LlmClient
import dev.aiui.llm.RemoteModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

data class ProfileWithModels(
    val profile: ProfileEntity,
    val models: List<ModelEntity>,
) {
    val enabledModels: List<ModelEntity> get() = models.filter { it.enabled }

    /** The model the picker shows under the profile name: the last one used, else the first switched on. */
    val preferredModel: ModelEntity?
        get() = enabledModels.firstOrNull { it.modelId == profile.lastModelId } ?: enabledModels.firstOrNull()
}

/** What the user edits on the Profile screen, saved in one go. */
data class ProfileDraft(
    val id: Long?,
    val name: String,
    val format: ApiFormat,
    val baseUrl: String,
    /** Null keeps the saved key; an empty string removes it. */
    val newApiKey: String?,
    val extraHeaders: String,
    val extraBody: String,
    val allowInsecure: Boolean,
    val models: List<ModelEntity>,
)

class ProfileRepository(
    private val db: AppDatabase,
    private val vault: KeyVault,
    private val clients: Map<ApiFormat, LlmClient>,
) {
    val profiles: Flow<List<ProfileWithModels>> =
        combine(db.profiles().observeAll(), db.models().observeAll()) { profiles, models ->
            val byProfile = models.groupBy { it.profileId }
            profiles.map { ProfileWithModels(it, byProfile[it.id].orEmpty()) }
        }

    suspend fun get(id: Long): ProfileEntity? = db.profiles().get(id)

    suspend fun models(profileId: Long): List<ModelEntity> = db.models().forProfile(profileId)

    suspend fun model(profileId: Long, modelId: String): ModelEntity? = db.models().get(profileId, modelId)

    suspend fun setLastModel(profileId: Long, modelId: String) = db.profiles().setLastModel(profileId, modelId)

    suspend fun revealKey(profileId: Long): String? = withContext(Dispatchers.IO) {
        db.profiles().get(profileId)?.apiKeyEncrypted?.let { vault.decrypt(it) }
    }

    suspend fun endpointFor(profile: ProfileEntity): Endpoint = withContext(Dispatchers.IO) {
        Endpoint(
            format = ApiFormat.fromId(profile.format),
            baseUrl = profile.baseUrl,
            apiKey = profile.apiKeyEncrypted?.let { vault.decrypt(it) },
            extraHeaders = parseHeaders(profile.extraHeaders),
            extraBody = parseObject(profile.extraBody),
            allowInsecure = profile.allowInsecure,
        )
    }

    suspend fun listRemoteModels(endpoint: Endpoint): List<RemoteModel> =
        clients.getValue(endpoint.format).listModels(endpoint)

    suspend fun save(draft: ProfileDraft): Long = withContext(Dispatchers.IO) {
        val existing = draft.id?.let { db.profiles().get(it) }
        val encryptedKey = when {
            draft.newApiKey == null -> existing?.apiKeyEncrypted
            draft.newApiKey.isBlank() -> null
            else -> vault.encrypt(draft.newApiKey.trim())
        }
        val entity = ProfileEntity(
            id = existing?.id ?: 0,
            name = draft.name.trim(),
            format = draft.format.id,
            baseUrl = draft.baseUrl.trim().trimEnd('/'),
            apiKeyEncrypted = encryptedKey,
            extraHeaders = draft.extraHeaders.ifBlank { "{}" },
            extraBody = draft.extraBody.ifBlank { "{}" },
            allowInsecure = draft.allowInsecure,
            lastModelId = existing?.lastModelId,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
        )
        val id = if (existing == null) db.profiles().insert(entity) else entity.id.also { db.profiles().update(entity) }
        db.models().deleteForProfile(id)
        db.models().upsertAll(draft.models.mapIndexed { index, model -> model.copy(profileId = id, position = index) })
        id
    }

    suspend fun delete(id: Long) = db.profiles().delete(id)

    companion object {
        /** Merges a fresh model list into the draft, keeping switches, settings and hand-added IDs. */
        fun mergeModels(current: List<ModelEntity>, remote: List<RemoteModel>, profileId: Long): List<ModelEntity> {
            val byId = current.associateBy { it.modelId }
            val enableNew = current.isEmpty() && remote.size <= AUTO_ENABLE_LIMIT
            val merged = remote.map { model ->
                byId[model.id]?.copy(
                    displayName = model.displayName ?: byId[model.id]?.displayName,
                    maxOutputTokens = byId[model.id]?.maxOutputTokens ?: model.maxOutputTokens,
                    manual = false,
                ) ?: ModelEntity(
                    profileId = profileId,
                    modelId = model.id,
                    displayName = model.displayName,
                    enabled = enableNew,
                    maxOutputTokens = model.maxOutputTokens,
                )
            }
            val remoteIds = remote.map { it.id }.toSet()
            val manual = current.filter { it.manual && it.modelId !in remoteIds }
            return merged + manual
        }

        private const val AUTO_ENABLE_LIMIT = 12

        fun parseObject(text: String): JsonObject =
            runCatching { LenientJson.parseToJsonElement(text.ifBlank { "{}" }).jsonObject }
                .getOrDefault(JsonObject(emptyMap()))

        fun parseHeaders(text: String): Map<String, String> =
            parseObject(text).mapNotNull { (key, value) ->
                (value as? JsonPrimitive)?.contentOrNull?.let { key to it }
            }.toMap()

        /** Error text for a JSON-object field, or null when it is valid or blank. */
        fun jsonObjectError(text: String): String? {
            if (text.isBlank()) return null
            return runCatching { LenientJson.parseToJsonElement(text).jsonObject; null }
                .getOrElse { "Must be a JSON object, like {\"key\": \"value\"}" }
        }
    }
}
