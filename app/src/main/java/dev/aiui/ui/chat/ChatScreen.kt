package dev.aiui.ui.chat

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aiui.data.db.AttachmentEntity
import dev.aiui.ui.components.ProfileModelPickerSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onOpenSettings: () -> Unit,
    onAddProfile: () -> Unit,
    onEditProfile: (Long) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var showAttach by rememberSaveable { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf<String?>(null) }
    var cameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }

    LaunchedEffect(vm) { vm.notices.collect { snackbar.showSnackbar(it) } }

    // Follow the newest message unless the user has scrolled up to read; sending re-enables following.
    // The list is reversed, so item 0 is the newest message at the bottom.
    var followNewest by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) followNewest = listState.firstVisibleItemIndex == 0
        }
    }
    LaunchedEffect(state.conversationId) { followNewest = true }
    val newest = state.messages.lastOrNull()
    LaunchedEffect(state.messages.size, newest?.id, newest?.text?.length) {
        if (followNewest && state.messages.isNotEmpty()) listState.scrollToItem(0)
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val content = pendingSave
        pendingSave = null
        if (uri != null && content != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                    }.isSuccess
                }
                snackbar.showSnackbar(if (saved) "Saved" else "Couldn't save the file")
            }
        }
    }
    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) {
        vm.addAttachments(it)
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        vm.addAttachments(it)
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val uri = cameraUri
        if (taken && uri != null) vm.addAttachments(listOf(uri))
    }

    val selection = state.selection
    val guardAttach: (() -> Unit) -> Unit = { action ->
        when {
            selection?.generatesImages == true ->
                vm.notify("${selection.modelId} makes images from text, so it doesn't take attachments.")
            selection?.supportsAttachments == false ->
                vm.notify("${selection.modelId} is set as text only. Turn on attachments for it in the profile.")
            else -> action()
        }
    }
    val openPhotos = { photoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val openFiles = { fileLauncher.launch(arrayOf("*/*")) }
    val takePhoto = {
        val uri = newCameraUri(context)
        cameraUri = uri
        cameraLauncher.launch(uri)
    }
    // The app declares the camera permission for QR scanning, so the system camera
    // refuses to open for it until that permission is granted.
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) takePhoto() else vm.notify("Allow camera access in Settings to take photos.")
    }
    val openCamera = {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            takePhoto()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    val actions = remember(vm) {
        MessageActions(
            copy = { copyToClipboard(context, it) },
            share = { shareText(context, it) },
            saveReply = {
                pendingSave = it
                saveLauncher.launch("reply.md")
            },
            saveCode = { language, code ->
                pendingSave = code
                saveLauncher.launch("snippet.${extensionFor(language)}")
            },
            regenerate = vm::regenerate,
            edit = vm::startEdit,
            openAttachment = { openAttachment(context, it) },
            readAloud = vm::toggleReadAloud,
        )
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startRecording() else vm.notify("Allow microphone access in Settings to dictate.")
    }
    val onMic = {
        when {
            vm.voiceInput == VoiceInput.RECORDING -> vm.finishRecording()
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> vm.startRecording()
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val readAloud = ReadAloudState(available = state.canReadAloud, speakingMessageId = vm.speakingMessageId)

    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            val modelCount = state.profiles.sumOf { it.enabledModels.size }
            ConversationDrawer(
                conversations = state.conversations,
                currentId = state.conversationId,
                generatingIds = state.generatingIds,
                query = query,
                onQueryChange = { vm.query.value = it },
                onNewChat = {
                    vm.newChat()
                    scope.launch { drawerState.close() }
                },
                onOpen = {
                    vm.openConversation(it)
                    scope.launch { drawerState.close() }
                },
                onRename = vm::rename,
                onDelete = vm::delete,
                displayName = state.prefs.displayName,
                profileSummary = "${plural(state.profiles.size, "profile")}, ${plural(modelCount, "model")}",
                onOpenSettings = {
                    scope.launch { drawerState.close() }
                    onOpenSettings()
                },
            )
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Outlined.Menu, contentDescription = "Open chats")
                        }
                    },
                    title = {
                        Surface(
                            onClick = { showPicker = true },
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surface,
                        ) {
                            Column(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        selection?.modelId ?: "Choose a model",
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    Icon(Icons.Filled.ArrowDropDown, contentDescription = "Change profile or model")
                                }
                                if (selection != null) {
                                    Text(
                                        selection.profileName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (selection.available) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        },
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = vm::newChat) {
                            Icon(Icons.Outlined.Edit, contentDescription = "New chat")
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding(),
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    when {
                        state.loaded && state.profiles.isEmpty() -> NoProfiles(onAddProfile)
                        state.messages.isEmpty() -> EmptyChat(
                            displayName = state.prefs.displayName,
                            onPhoto = { guardAttach(openPhotos) },
                            onPdf = { guardAttach(openFiles) },
                            onCode = { vm.composerText = "Explain this code:\n\n" },
                        )
                        else -> LazyColumn(
                            state = listState,
                            reverseLayout = true,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.Bottom),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(state.messages.asReversed(), key = { it.id }) { message ->
                                MessageItem(message, conversationBusy = state.generating, readAloud = readAloud, actions = actions)
                            }
                        }
                    }
                }
                Composer(
                    text = vm.composerText,
                    onTextChange = { vm.composerText = it },
                    placeholder = when {
                        vm.voiceInput == VoiceInput.RECORDING -> "Listening. Tap the mic when you're done."
                        vm.voiceInput == VoiceInput.TRANSCRIBING -> "Transcribing"
                        selection?.generatesImages == true -> "Describe an image"
                        else -> "Message"
                    },
                    pending = vm.pending,
                    importing = vm.importing,
                    editing = vm.editingMessageId != null,
                    generating = state.generating,
                    enabled = selection?.available == true,
                    canDictate = state.canDictate,
                    voiceInput = vm.voiceInput,
                    onMic = onMic,
                    onCancelEdit = vm::cancelEdit,
                    onAttach = { guardAttach { showAttach = true } },
                    onRemove = vm::removeAttachment,
                    onSend = {
                        followNewest = true
                        vm.send()
                    },
                    onStop = vm::stop,
                )
            }
        }
    }

    if (showPicker) {
        ProfileModelPickerSheet(
            profiles = state.profiles,
            currentProfileId = selection?.profileId,
            currentModelId = selection?.modelId,
            onPick = vm::selectModel,
            onAddProfile = onAddProfile,
            onEditProfile = onEditProfile,
            onDismiss = { showPicker = false },
        )
    }
    if (showAttach) {
        AttachSheet(
            onCamera = openCamera,
            onPhotos = openPhotos,
            onFiles = openFiles,
            onDismiss = { showAttach = false },
        )
    }
}

@Composable
private fun NoProfiles(onAddProfile: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
    ) {
        Icon(Icons.Outlined.Hub, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Text("Add a profile to start", style = MaterialTheme.typography.headlineSmall)
        Text(
            "A profile is one base URL and API key: your Anthropic or OpenAI account, a platform like Nebius, " +
                "or Ollama or LM Studio running on your computer.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onAddProfile) { Text("Add profile") }
    }
}

@Composable
private fun EmptyChat(displayName: String, onPhoto: () -> Unit, onPdf: () -> Unit, onCode: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
    ) {
        Text(
            if (displayName.isBlank()) "What are we working on?" else "What are we working on, ${displayName.substringBefore(' ')}?",
            style = MaterialTheme.typography.headlineMedium,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Starter("Ask about a photo", Icons.Outlined.Image, onPhoto)
            Starter("Summarise a PDF", Icons.Outlined.PictureAsPdf, onPdf)
            Starter("Explain some code", Icons.Outlined.Code, onCode)
        }
    }
}

@Composable
private fun Starter(label: String, icon: ImageVector, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, shape = MaterialTheme.shapes.medium) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(label, modifier = Modifier.padding(start = 10.dp))
    }
}

private const val MAX_PICK = 6

private fun plural(count: Int, word: String) = "$count $word" + if (count == 1) "" else "s"

private fun newCameraUri(context: Context): Uri {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val file = File(dir, "photo-${UUID.randomUUID()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}

private fun copyToClipboard(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("AI UI", text))
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null))
}

private fun openAttachment(context: Context, attachment: AttachmentEntity) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(attachment.localPath))
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, attachment.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(view)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent.createChooser(view, attachment.fileName))
    }
}
