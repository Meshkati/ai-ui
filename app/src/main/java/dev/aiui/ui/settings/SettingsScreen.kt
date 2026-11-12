package dev.aiui.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.aiui.AppContainer
import dev.aiui.data.AppPrefs
import dev.aiui.data.ModelChoice
import dev.aiui.data.ThemeMode
import dev.aiui.data.audioProfileFor
import dev.aiui.ui.components.ModelPurpose
import dev.aiui.ui.components.LocalBadge
import dev.aiui.ui.components.ProfileModelPickerSheet
import dev.aiui.ui.components.clickableRow
import dev.aiui.ui.components.isLocal
import dev.aiui.update.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class SettingsViewModel(container: AppContainer) : ViewModel() {
    private val prefsRepo = container.prefs
    private val chats = container.chats
    val profiles = container.profiles.profiles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val prefs = prefsRepo.prefs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPrefs())
    private val updates = container.updates
    val installedVersion = updates.installed
    val newerVersion = updates.newer.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    var checkingForUpdate by mutableStateOf(false)
        private set

    /** What the user's last check found; null before they check. */
    var updateFound by mutableStateOf<Updates.Found?>(null)
        private set

    fun setDefault(profileId: Long, modelId: String) = viewModelScope.launch { prefsRepo.setDefaultModel(profileId, modelId) }
    fun setTheme(theme: ThemeMode) = viewModelScope.launch { prefsRepo.setTheme(theme) }
    fun setSpeechToText(choice: ModelChoice?) = viewModelScope.launch { prefsRepo.setSpeechToText(choice) }
    fun setTextToSpeech(choice: ModelChoice?) = viewModelScope.launch { prefsRepo.setTextToSpeech(choice) }
    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { prefsRepo.setDynamicColor(enabled) }
    fun deleteAllChats() = viewModelScope.launch { chats.deleteAll() }
    suspend fun exportJson(): String = chats.exportJson()

    fun checkForUpdate() {
        if (checkingForUpdate) return
        checkingForUpdate = true
        viewModelScope.launch {
            updateFound = updates.checkNow()
            checkingForUpdate = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit,
    onOpenProfile: (Long?) -> Unit,
    onOpenPersonalization: () -> Unit,
) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val newerVersion by vm.newerVersion.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showPicker by remember { mutableStateOf(false) }
    var voicePicker by remember { mutableStateOf<ModelPurpose?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = runCatching {
                    val json = vm.exportJson()
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    }
                }.isSuccess
                snackbar.showSnackbar(if (ok) "Chats exported" else "Export failed")
            }
        }
    }

    val defaultProfile = profiles.firstOrNull { it.profile.id == prefs.defaultProfileId }
    val defaultLabel = if (defaultProfile != null && prefs.defaultModelId != null) {
        "${defaultProfile.profile.name}, ${prefs.defaultModelId}"
    } else {
        "Not set: new chats use the first profile"
    }

    fun voiceLabel(choice: ModelChoice?): String =
        profiles.audioProfileFor(choice)?.let { "${it.profile.name}, ${choice?.modelId}" } ?: "Off"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Section("Profiles") {
                    profiles.forEachIndexed { index, item ->
                        if (index > 0) Divider()
                        val shown = item.enabledModels.size
                        Row_(
                            title = item.profile.name,
                            subtitle = "${item.profile.baseUrl.removePrefix("https://")}, $shown ${if (shown == 1) "model" else "models"} shown",
                            badge = isLocal(item.profile.baseUrl),
                            onClick = { onOpenProfile(item.profile.id) },
                        )
                    }
                    if (profiles.isNotEmpty()) Divider()
                    ListItem(
                        headlineContent = { Text("Add profile", color = MaterialTheme.colorScheme.primary) },
                        leadingContent = { Icon(Icons.Outlined.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickableItem { onOpenProfile(null) },
                    )
                }
            }
            item {
                Section("Defaults") {
                    Row_("Default profile and model", defaultLabel, onClick = { showPicker = true })
                    Divider()
                    Row_("Personalization", "Your name and the system prompt for new chats", onClick = onOpenPersonalization)
                }
            }
            item {
                Section("Voice") {
                    Row_("Speech to text", voiceLabel(prefs.speechToText), onClick = { voicePicker = ModelPurpose.SPEECH_TO_TEXT })
                    Divider()
                    Row_("Text to speech", voiceLabel(prefs.textToSpeech), onClick = { voicePicker = ModelPurpose.TEXT_TO_SPEECH })
                }
            }
            item {
                Section("Appearance") {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Theme", style = MaterialTheme.typography.bodyLarge)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            ThemeMode.entries.forEachIndexed { index, mode ->
                                SegmentedButton(
                                    selected = prefs.theme == mode,
                                    onClick = { vm.setTheme(mode) },
                                    shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                                ) {
                                    Text(mode.name.lowercase().replaceFirstChar { it.uppercase() })
                                }
                            }
                        }
                    }
                    Divider()
                    ListItem(
                        headlineContent = { Text("Use wallpaper colours") },
                        supportingContent = { Text("Material You dynamic colour") },
                        trailingContent = { Switch(checked = prefs.dynamicColor, onCheckedChange = vm::setDynamicColor) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
            item {
                Section("Data") {
                    Row_(
                        "Export chats",
                        "JSON file. API keys are never included.",
                        onClick = { exportLauncher.launch("ai-ui-chats-${LocalDate.now()}.json") },
                    )
                    Divider()
                    ListItem(
                        headlineContent = { Text("Delete all chats", color = MaterialTheme.colorScheme.error) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickableItem { confirmDelete = true },
                    )
                }
            }
            item {
                Section("About") {
                    val newer = newerVersion
                    if (newer != null) {
                        Row_(
                            "Download version $newer",
                            "You have ${vm.installedVersion}",
                            onClick = { runCatching { uriHandler.openUri(Updates.releasePage(newer)) } },
                        )
                    } else {
                        Row_(
                            "Check for updates",
                            updateStatus(vm.installedVersion, vm.checkingForUpdate, vm.updateFound),
                            onClick = vm::checkForUpdate,
                        )
                    }
                    Divider()
                    Row_(
                        "Source code",
                        Updates.SOURCE_URL.removePrefix("https://"),
                        onClick = { runCatching { uriHandler.openUri(Updates.SOURCE_URL) } },
                    )
                }
            }
            item {
                Text(
                    "Chats, profiles and keys stay on this phone. Release builds ask GitHub for a new version once a day.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }

    if (showPicker) {
        ProfileModelPickerSheet(
            profiles = profiles,
            currentProfileId = prefs.defaultProfileId,
            currentModelId = prefs.defaultModelId,
            onPick = { profileId, modelId -> vm.setDefault(profileId, modelId) },
            onAddProfile = { onOpenProfile(null) },
            onEditProfile = { onOpenProfile(it) },
            onDismiss = { showPicker = false },
        )
    }
    voicePicker?.let { purpose ->
        val current = if (purpose == ModelPurpose.SPEECH_TO_TEXT) prefs.speechToText else prefs.textToSpeech
        val save: (ModelChoice?) -> Unit = if (purpose == ModelPurpose.SPEECH_TO_TEXT) vm::setSpeechToText else vm::setTextToSpeech
        ProfileModelPickerSheet(
            profiles = profiles,
            currentProfileId = profiles.audioProfileFor(current)?.profile?.id,
            currentModelId = current?.modelId,
            onPick = { profileId, modelId -> save(ModelChoice(profileId, modelId)) },
            onAddProfile = { onOpenProfile(null) },
            onEditProfile = { onOpenProfile(it) },
            onDismiss = { voicePicker = null },
            purpose = purpose,
            onTurnOff = { save(null) },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete all chats?") },
            text = { Text("Every conversation and attachment is removed from this phone. Profiles and keys are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteAllChats()
                    confirmDelete = false
                }) { Text("Delete all") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

private fun updateStatus(installed: String, checking: Boolean, found: Updates.Found?): String = when {
    checking -> "Checking GitHub…"
    found == Updates.Found.UpToDate -> "Version $installed is the latest"
    found == Updates.Found.Failed -> "Couldn't reach GitHub. Try again later."
    found is Updates.Found.NotARelease -> "This build doesn't update. The latest is ${found.latest}."
    else -> "Version $installed"
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp),
        )
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
            Column { content() }
        }
    }
}

@Composable
private fun Divider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = 16.dp))

@Composable
private fun Row_(title: String, subtitle: String, badge: Boolean = false, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            Row {
                if (badge) LocalBadge()
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickableItem(onClick),
    )
}

private fun Modifier.clickableItem(onClick: () -> Unit): Modifier = clickableRow(onClick)
