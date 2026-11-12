package dev.aiui.ui.chat

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.aiui.AppContainer
import dev.aiui.data.AppPrefs
import dev.aiui.data.PendingAttachment
import dev.aiui.data.ProfileWithModels
import dev.aiui.data.UnsupportedAttachmentException
import dev.aiui.data.audioProfileFor
import dev.aiui.data.db.AttachmentEntity
import dev.aiui.data.db.ConversationEntity
import dev.aiui.data.db.MessageRole
import dev.aiui.data.db.ModelEntity
import dev.aiui.data.db.MessageWithAttachments
import dev.aiui.data.makesImages
import dev.aiui.llm.ApiFormat
import dev.aiui.update.Version
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/** The profile and model the next message goes to. */
data class Selection(
    val profileId: Long,
    val profileName: String,
    val modelId: String,
    val supportsAttachments: Boolean,
    /** Messages go to the Images API, and the reply is a picture. */
    val generatesImages: Boolean = false,
    /** False when the chat's profile or model no longer exists. */
    val available: Boolean = true,
)

data class UiMessage(
    val id: Long,
    val isUser: Boolean,
    val text: String,
    val status: String,
    val error: String?,
    val stopReason: String?,
    val attachments: List<AttachmentEntity>,
    val modelId: String?,
    val isLast: Boolean,
    val memoryUpdated: Boolean = false,
)

enum class VoiceInput { IDLE, RECORDING, TRANSCRIBING }

data class ChatUiState(
    val conversationId: Long? = null,
    val title: String = "",
    val profiles: List<ProfileWithModels> = emptyList(),
    val selection: Selection? = null,
    val messages: List<UiMessage> = emptyList(),
    val generating: Boolean = false,
    val conversations: List<ConversationEntity> = emptyList(),
    val generatingIds: Set<Long> = emptySet(),
    val prefs: AppPrefs = AppPrefs(),
    val loaded: Boolean = false,
    /** Speech to text is set up on a profile that still exists. */
    val canDictate: Boolean = false,
    /** Text to speech is set up on a profile that still exists. */
    val canReadAloud: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ChatViewModel(container: AppContainer) : ViewModel() {
    private val chats = container.chats
    private val profileRepo = container.profiles
    private val attachmentStore = container.attachments
    private val speech = container.speech
    private val recorder = container.voiceRecorder
    private val player = container.speechPlayer
    private val updates = container.updates

    /** A newer release on GitHub that the user hasn't been told about yet. */
    val untoldUpdate = updates.untold

    private val conversationId = MutableStateFlow<Long?>(null)
    private val pendingSelection = MutableStateFlow<Pair<Long, String>?>(null)
    val query = MutableStateFlow("")

    var composerText by mutableStateOf("")
    val pending = mutableStateListOf<PendingAttachment>()
    var editingMessageId by mutableStateOf<Long?>(null)
        private set
    var importing by mutableStateOf(false)
        private set
    var voiceInput by mutableStateOf(VoiceInput.IDLE)
        private set
    private var transcription: Job? = null

    /** The reply being read aloud, if any. */
    var speakingMessageId by mutableStateOf<Long?>(null)
        private set
    private var reading: Job? = null

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Short notices for the snackbar. */
    val notices: SharedFlow<String> = _messages.asSharedFlow()

    private val conversation = conversationId.flatMapLatest { id ->
        if (id == null) flowOf(null) else chats.observeConversation(id)
    }
    private val storedMessages = conversationId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else chats.messages(id)
    }
    private val conversationList = query.debounce(150).flatMapLatest { chats.conversations(it) }

    private val messages = combine(storedMessages, chats.liveText) { stored, live -> toUi(stored, live) }

    private val selection = combine(
        conversation,
        profileRepo.profiles,
        container.prefs.prefs,
        pendingSelection,
    ) { conv, profiles, prefs, pendingPick ->
        Triple(conv, profiles, prefs) to resolveSelection(conv, profiles, prefs, pendingPick)
    }

    val state: StateFlow<ChatUiState> = combine(
        selection,
        messages,
        chats.generating,
        conversationList,
        conversationId,
    ) { (context, selected), uiMessages, generating, list, currentId ->
        val (conv, profiles, prefs) = context
        ChatUiState(
            conversationId = currentId,
            title = conv?.title.orEmpty(),
            profiles = profiles,
            selection = selected,
            messages = uiMessages,
            generating = currentId != null && currentId in generating,
            conversations = list,
            generatingIds = generating,
            prefs = prefs,
            loaded = true,
            canDictate = profiles.audioProfileFor(prefs.speechToText) != null,
            canReadAloud = profiles.audioProfileFor(prefs.textToSpeech) != null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    init {
        viewModelScope.launch { updates.checkIfDue() }
    }

    fun markUpdateTold(version: Version) = viewModelScope.launch { updates.markTold(version) }

    fun openConversation(id: Long) {
        if (conversationId.value == id) return
        clearComposer()
        stopReading()
        conversationId.value = id
    }

    fun newChat() {
        clearComposer()
        stopReading()
        conversationId.value = null
        pendingSelection.value = null
    }

    fun selectModel(profileId: Long, modelId: String) {
        viewModelScope.launch {
            val id = conversationId.value
            if (id != null) {
                chats.setModel(id, profileId, modelId)
            } else {
                pendingSelection.value = profileId to modelId
                profileRepo.setLastModel(profileId, modelId)
            }
        }
    }

    fun send() {
        val text = composerText
        val attachments = pending.toList()
        if (text.isBlank() && attachments.isEmpty()) return
        val current = state.value
        val selected = current.selection
        if (selected == null || !selected.available) {
            notify("Choose a profile and model first.")
            return
        }
        val editing = editingMessageId
        composerText = ""
        pending.clear()
        editingMessageId = null
        viewModelScope.launch {
            val existing = conversationId.value
            if (editing != null && existing != null) {
                chats.editAndResend(existing, editing, text)
                return@launch
            }
            val id = existing ?: chats.createConversation(selected.profileId, selected.modelId).also {
                profileRepo.setLastModel(selected.profileId, selected.modelId)
                conversationId.value = it
            }
            chats.send(id, text, attachments)
        }
    }

    fun stop() {
        conversationId.value?.let { chats.stop(it) }
    }

    fun regenerate(messageId: Long) {
        val id = conversationId.value ?: return
        viewModelScope.launch { chats.regenerate(id, messageId) }
    }

    fun startEdit(message: UiMessage) {
        editingMessageId = message.id
        composerText = message.text
    }

    fun cancelEdit() {
        editingMessageId = null
        composerText = ""
    }

    fun addAttachments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            importing = true
            for (uri in uris) {
                try {
                    pending.add(attachmentStore.import(uri))
                } catch (e: UnsupportedAttachmentException) {
                    notify(e.message.orEmpty())
                } catch (e: Exception) {
                    notify("Couldn't attach that file: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            importing = false
        }
    }

    fun removeAttachment(attachment: PendingAttachment) {
        pending.remove(attachment)
        attachmentStore.delete(attachment.localPath)
    }

    fun startRecording() {
        stopReading()
        try {
            recorder.start()
            voiceInput = VoiceInput.RECORDING
        } catch (e: Exception) {
            recorder.stopAndDiscard()
            notify("Couldn't start recording: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Stops recording and adds what was said to the composer. */
    fun finishRecording() {
        val audio = recorder.stop()
        if (audio == null) {
            voiceInput = VoiceInput.IDLE
            notify("Nothing was recorded.")
            return
        }
        voiceInput = VoiceInput.TRANSCRIBING
        transcription = viewModelScope.launch {
            try {
                val text = speech.transcribe(audio)
                if (text.isBlank()) notify("No speech was heard.") else composerText = appendDictation(composerText, text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notify(e.message ?: "Couldn't transcribe the recording.")
            } finally {
                audio.delete()
                voiceInput = VoiceInput.IDLE
            }
        }
    }

    /** Starts reading [message] aloud, or stops if it is already being read. */
    fun toggleReadAloud(message: UiMessage) {
        if (speakingMessageId == message.id) {
            stopReading()
            return
        }
        val text = speakableText(message.text)
        if (text.isBlank()) {
            notify("This reply has nothing to read aloud.")
            return
        }
        val previous = reading
        speakingMessageId = message.id
        // Started after it is stored, so the cleanup below can tell whether a newer reading replaced it.
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            // The previous reading lets go of audio focus before this one takes it.
            previous?.cancelAndJoin()
            player.acquireFocus()
            try {
                speech.speak(text).collect { player.play(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notify(e.message ?: "Couldn't read the reply aloud.")
            } finally {
                player.releaseFocus()
                if (reading === coroutineContext.job) speakingMessageId = null
            }
        }
        reading = job
        job.start()
    }

    fun stopReading() {
        reading?.cancel()
        speakingMessageId = null
    }

    fun rename(id: Long, title: String) {
        viewModelScope.launch { chats.rename(id, title) }
    }

    fun delete(id: Long) {
        if (conversationId.value == id) newChat()
        viewModelScope.launch { chats.delete(id) }
    }

    fun notify(message: String) {
        _messages.tryEmit(message)
    }

    private fun clearComposer() {
        cancelVoiceInput()
        pending.forEach { attachmentStore.delete(it.localPath) }
        pending.clear()
        composerText = ""
        editingMessageId = null
    }

    private fun cancelVoiceInput() {
        recorder.stopAndDiscard()
        transcription?.cancel()
        voiceInput = VoiceInput.IDLE
    }

    override fun onCleared() {
        recorder.stopAndDiscard()
        pending.forEach { attachmentStore.delete(it.localPath) }
    }

    private fun toUi(stored: List<MessageWithAttachments>, live: Map<Long, String>): List<UiMessage> =
        stored.mapIndexed { index, item ->
            val message = item.message
            UiMessage(
                id = message.id,
                isUser = message.role == MessageRole.USER,
                text = live[message.id] ?: message.text,
                status = message.status,
                error = message.error,
                stopReason = message.stopReason,
                attachments = item.attachments,
                modelId = message.modelId,
                isLast = index == stored.lastIndex,
                memoryUpdated = message.memoryUpdated,
            )
        }

    companion object {
        /** Dictated words go after whatever is already typed. */
        fun appendDictation(typed: String, dictated: String): String =
            if (typed.isBlank()) dictated else typed.trimEnd() + " " + dictated

        fun resolveSelection(
            conversation: ConversationEntity?,
            profiles: List<ProfileWithModels>,
            prefs: AppPrefs,
            pendingPick: Pair<Long, String>?,
        ): Selection? {
            if (conversation?.profileId != null && conversation.modelId != null) {
                val profile = profiles.firstOrNull { it.profile.id == conversation.profileId }
                val model = profile?.models?.firstOrNull { it.modelId == conversation.modelId }
                return Selection(
                    profileId = conversation.profileId,
                    profileName = profile?.profile?.name ?: "Deleted profile",
                    modelId = conversation.modelId,
                    supportsAttachments = model?.supportsAttachments ?: true,
                    generatesImages = profile != null && model != null && model.makesImages(ApiFormat.fromId(profile.profile.format)),
                    available = profile != null,
                )
            }
            val candidates = listOfNotNull(pendingPick, prefs.defaultProfileId?.let { id -> prefs.defaultModelId?.let { id to it } })
            for ((profileId, modelId) in candidates) {
                val profile = profiles.firstOrNull { it.profile.id == profileId } ?: continue
                val model = profile.enabledModels.firstOrNull { it.modelId == modelId } ?: continue
                return selectionOf(profile, model)
            }
            val fallback = profiles.firstOrNull { it.preferredModel != null } ?: return null
            return selectionOf(fallback, fallback.preferredModel ?: return null)
        }

        private fun selectionOf(profile: ProfileWithModels, model: ModelEntity) = Selection(
            profileId = profile.profile.id,
            profileName = profile.profile.name,
            modelId = model.modelId,
            supportsAttachments = model.supportsAttachments,
            generatesImages = model.makesImages(ApiFormat.fromId(profile.profile.format)),
        )
    }
}
