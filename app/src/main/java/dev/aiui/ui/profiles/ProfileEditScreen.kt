package dev.aiui.ui.profiles

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aiui.data.db.ModelEntity
import dev.aiui.data.makesImages
import dev.aiui.llm.ApiFormat
import dev.aiui.ui.components.QrScanDialog
import dev.aiui.ui.components.clickableRow
import dev.aiui.ui.theme.AccentTheme
import dev.aiui.ui.theme.Accents
import dev.aiui.ui.theme.MonoFamily
import dev.aiui.ui.theme.accentColorScheme
import dev.aiui.ui.theme.isDark

@Composable
fun ProfileEditScreen(vm: ProfileEditViewModel, onDone: () -> Unit) {
    val form by vm.form.collectAsStateWithLifecycle()
    val defaultAccent = MaterialTheme.colorScheme.primary
    // The editor previews the colour the chat will use with this profile.
    AccentTheme(form.accentColor) { ProfileEditContent(vm, form, defaultAccent, onDone) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditContent(vm: ProfileEditViewModel, form: ProfileForm, defaultAccent: Color, onDone: () -> Unit) {
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    var addingModel by remember { mutableStateOf(false) }
    var editingModel by remember { mutableStateOf<ModelEntity?>(null) }
    var advanced by remember { mutableStateOf(form.extraHeaders.isNotBlank() || form.extraBody.isNotBlank()) }
    var modelFilter by remember { mutableStateOf("") }
    var keyError by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf<ScanTarget?>(null) }

    LaunchedEffect(vm) { vm.done.collect { onDone() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (form.isNew) "New profile" else "Profile") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back without saving") }
                },
                actions = {
                    if (!form.isNew) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete profile")
                        }
                    }
                },
            )
        },
        bottomBar = {
            Button(
                onClick = vm::save,
                enabled = form.canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .height(48.dp),
            ) { Text(if (form.saving) "Saving" else "Save") }
        },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (form.isNew) {
                Text("Start from", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Presets.forEach { preset ->
                        FilterChip(
                            selected = form.baseUrl == preset.baseUrl && form.format == preset.format && preset.baseUrl.isNotBlank(),
                            onClick = { vm.applyPreset(preset) },
                            label = { Text(preset.label) },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = form.name,
                onValueChange = { value -> vm.update { it.copy(name = value) } },
                label = { Text("Name") },
                placeholder = { Text("Shown in the profile list") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            AccentPicker(
                selected = form.accentColor,
                defaultColor = defaultAccent,
                onSelect = { seed -> vm.update { it.copy(accentColor = seed) } },
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("API format", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ApiFormat.entries.forEachIndexed { index, format ->
                        SegmentedButton(
                            selected = form.format == format,
                            onClick = { vm.update { it.copy(format = format, test = TestState.Idle) } },
                            shape = SegmentedButtonDefaults.itemShape(index, ApiFormat.entries.size),
                        ) { Text(format.label) }
                    }
                }
            }

            OutlinedTextField(
                value = form.baseUrl,
                onValueChange = { value -> vm.update { it.copy(baseUrl = value.trim(), test = TestState.Idle) } },
                label = { Text("Base URL") },
                singleLine = true,
                isError = form.urlError != null,
                supportingText = (form.urlError ?: form.presetHint)?.let { text -> { Text(text) } },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = MonoFamily),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                trailingIcon = { ScanButton(ScanTarget.BaseUrl, onClick = { scanning = ScanTarget.BaseUrl }) },
                modifier = Modifier.fillMaxWidth(),
            )

            if (form.needsInsecureSwitch) {
                ListItem(
                    headlineContent = { Text("Allow insecure connection") },
                    supportingContent = { Text("Sends your key and messages unencrypted to a public address.") },
                    trailingContent = {
                        Switch(checked = form.allowInsecure, onCheckedChange = { value -> vm.update { it.copy(allowInsecure = value) } })
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.errorContainer),
                )
            }

            ApiKeyField(
                form = form,
                onChange = { value -> vm.update { it.copy(apiKey = value, removeKey = false, test = TestState.Idle) } },
                onReveal = {
                    keyError = null
                    authenticate(context, form.name, onSuccess = vm::reveal, onError = { keyError = it })
                },
                onRemove = vm::removeKey,
                onScan = { scanning = ScanTarget.ApiKey },
                error = keyError,
            )

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = vm::test,
                    enabled = form.baseUrl.isNotBlank() && form.urlError == null && form.test != TestState.Running,
                ) { Text("Test connection") }
                when (val test = form.test) {
                    TestState.Running -> CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    is TestState.Ok -> StatusLine(Icons.Filled.CheckCircle, "Connected, ${test.modelCount} models", MaterialTheme.colorScheme.primary)
                    else -> Unit
                }
            }
            (form.test as? TestState.Failed)?.let {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(
                        it.message + "\n\nYou can still add model IDs by hand below.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            ModelsSection(
                models = form.models,
                format = form.format,
                filter = modelFilter,
                onFilter = { modelFilter = it },
                onToggle = vm::setModelEnabled,
                onAll = vm::setAllModels,
                onEdit = { editingModel = it },
                onAdd = { addingModel = true },
            )

            TextButton(onClick = { advanced = !advanced }) {
                Icon(if (advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
                Text("Advanced", modifier = Modifier.padding(start = 8.dp))
            }
            if (advanced) {
                OutlinedTextField(
                    value = form.extraHeaders,
                    onValueChange = { value -> vm.update { it.copy(extraHeaders = value) } },
                    label = { Text("Extra headers (JSON)") },
                    placeholder = { Text("{\"anthropic-beta\": \"...\"}") },
                    isError = form.headersError != null,
                    supportingText = { Text(form.headersError ?: "Sent with every request; overrides the app's own headers.") },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFamily),
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.extraBody,
                    onValueChange = { value -> vm.update { it.copy(extraBody = value) } },
                    label = { Text("Extra body fields (JSON)") },
                    placeholder = { Text("{\"temperature\": 0.7}") },
                    isError = form.bodyError != null,
                    supportingText = { Text(form.bodyError ?: "Merged into every chat request, for vendor-specific options.") },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFamily),
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    scanning?.let { target ->
        QrScanDialog(
            hint = target.hint,
            onResult = { text ->
                scanning = null
                val value = text.trim()
                when (target) {
                    ScanTarget.BaseUrl -> vm.update { it.copy(baseUrl = value, test = TestState.Idle) }
                    ScanTarget.ApiKey -> vm.update { it.copy(apiKey = value, removeKey = false, test = TestState.Idle) }
                }
            },
            onDismiss = { scanning = null },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${form.name.ifBlank { "this profile" }}?") },
            text = { Text("Its API key and model list are removed. Chats that used it stay readable.") },
            confirmButton = { TextButton(onClick = vm::delete) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    if (addingModel) {
        var id by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addingModel = false },
            title = { Text("Add a model ID") },
            text = {
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    label = { Text("Model ID") },
                    placeholder = { Text("e.g. llama3.2:3b") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = MonoFamily),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.addManualModel(id)
                    addingModel = false
                }, enabled = id.isNotBlank()) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { addingModel = false }) { Text("Cancel") } },
        )
    }
    editingModel?.let { model ->
        ModelSettingsDialog(
            model = model,
            format = form.format,
            onSave = { updated ->
                vm.updateModel(model.modelId) { updated }
                editingModel = null
            },
            onRemove = {
                vm.removeModel(model.modelId)
                editingModel = null
            },
            onDismiss = { editingModel = null },
        )
    }
}

@Composable
private fun ApiKeyField(
    form: ProfileForm,
    onChange: (String) -> Unit,
    onReveal: () -> Unit,
    onRemove: () -> Unit,
    onScan: () -> Unit,
    error: String?,
) {
    val savedAndHidden = form.hasSavedKey && form.revealedKey == null && !form.removeKey
    Column {
        OutlinedTextField(
            value = form.apiKey,
            onValueChange = onChange,
            label = { Text("API key") },
            placeholder = {
                Text(
                    when {
                        savedAndHidden -> "Saved and hidden. Type to replace it."
                        else -> "Optional for local servers"
                    },
                )
            },
            singleLine = true,
            visualTransformation = if (form.revealedKey != null) VisualTransformation.None else PasswordVisualTransformation(),
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = MonoFamily),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                Row {
                    if (savedAndHidden) {
                        IconButton(onClick = onReveal) { Icon(Icons.Outlined.Visibility, contentDescription = "Show saved key") }
                    }
                    ScanButton(ScanTarget.ApiKey, onClick = onScan)
                }
            },
            supportingText = {
                Text(error ?: "Encrypted on this phone with a hardware-backed key. Showing it asks for your fingerprint or screen lock.")
            },
            isError = error != null,
            modifier = Modifier.fillMaxWidth(),
        )
        if (form.hasSavedKey && !form.removeKey) {
            TextButton(onClick = onRemove) { Text("Remove saved key") }
        } else if (form.removeKey) {
            Text(
                "The saved key will be removed when you save.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}

private enum class ScanTarget(val action: String, val hint: String) {
    BaseUrl("Scan base URL from a QR code", "Point at a QR code with the base URL"),
    ApiKey("Scan API key from a QR code", "Point at a QR code with the API key"),
}

/** The profile's colour: the app's own colours, or one of [Accents]. */
@Composable
private fun AccentPicker(selected: Int?, defaultColor: Color, onSelect: (Int?) -> Unit) {
    val dark = MaterialTheme.colorScheme.isDark
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Colour", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .selectableGroup()
                .horizontalScroll(rememberScrollState()),
        ) {
            AccentSwatch("Default", defaultColor, selected == null) { onSelect(null) }
            Accents.forEach { accent ->
                val color = remember(accent.seed, dark) { accentColorScheme(accent.seed, dark).primary }
                AccentSwatch(accent.name, color, selected == accent.seed) { onSelect(accent.seed) }
            }
        }
    }
}

@Composable
private fun AccentSwatch(name: String, color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name },
    ) {
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = if (color.luminance() > 0.4f) Color.Black else Color.White)
        }
    }
}

@Composable
private fun ScanButton(target: ScanTarget, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(Icons.Outlined.QrCodeScanner, contentDescription = target.action) }
}

@Composable
private fun ModelsSection(
    models: List<ModelEntity>,
    format: ApiFormat,
    filter: String,
    onFilter: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onAll: (Boolean) -> Unit,
    onEdit: (ModelEntity) -> Unit,
    onAdd: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Models shown in the picker",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            if (models.size > 1) {
                val allOn = models.all { it.enabled }
                TextButton(onClick = { onAll(!allOn) }) { Text(if (allOn) "None" else "All") }
            }
        }
        if (models.isEmpty()) {
            Text(
                "Test the connection to load this server's models, or add model IDs by hand.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (models.size > 12) {
            OutlinedTextField(
                value = filter,
                onValueChange = onFilter,
                placeholder = { Text("Filter ${models.size} models") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        models
            .filter { filter.isBlank() || it.modelId.contains(filter.trim(), ignoreCase = true) }
            .forEach { model ->
                ListItem(
                    headlineContent = { Text(model.modelId, fontFamily = MonoFamily, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = modelDetails(model, format)?.let { text -> { Text(text) } },
                    trailingContent = { Switch(checked = model.enabled, onCheckedChange = { onToggle(model.modelId, it) }) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickableRow { onEdit(model) },
                )
            }
        TextButton(onClick = onAdd) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Text("Add a model ID by hand", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

private fun modelDetails(model: ModelEntity, format: ApiFormat): String? = listOfNotNull(
    model.displayName?.takeIf { it != model.modelId },
    "Generates images".takeIf { model.makesImages(format) },
    "Text only".takeIf { !model.supportsAttachments },
    model.maxOutputTokens?.let { "Up to $it output tokens" },
    "Added by hand".takeIf { model.manual },
).joinToString(" · ").ifBlank { null }

@Composable
private fun ModelSettingsDialog(
    model: ModelEntity,
    format: ApiFormat,
    onSave: (ModelEntity) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var maxTokens by remember { mutableStateOf(model.maxOutputTokens?.toString().orEmpty()) }
    var attachments by remember { mutableStateOf(model.supportsAttachments) }
    var images by remember { mutableStateOf(model.makesImages(format)) }
    val parsedTokens = maxTokens.trim().toIntOrNull()
    val tokensValid = maxTokens.isBlank() || (parsedTokens != null && parsedTokens > 0)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(model.modelId, fontFamily = MonoFamily, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = maxTokens,
                    onValueChange = { maxTokens = it.filter(Char::isDigit) },
                    label = { Text("Max output tokens") },
                    placeholder = { Text("16000") },
                    supportingText = { Text("Required by the Anthropic API. Leave blank for 16,000.") },
                    isError = !tokensValid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Accepts images and files", modifier = Modifier.weight(1f))
                    Switch(checked = attachments, onCheckedChange = { attachments = it })
                }
                if (format == ApiFormat.OPENAI) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Generates images", modifier = Modifier.weight(1f))
                        Switch(checked = images, onCheckedChange = { images = it })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val imagesChanged = images != model.makesImages(format)
                    onSave(
                        model.copy(
                            maxOutputTokens = parsedTokens,
                            supportsAttachments = attachments,
                            generatesImages = if (imagesChanged) images else model.generatesImages,
                        ),
                    )
                },
                enabled = tokensValid,
            ) { Text("Done") }
        },
        dismissButton = {
            Row {
                if (model.manual) TextButton(onClick = onRemove) { Text("Remove") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun StatusLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

private fun authenticate(context: Context, profileName: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
    val activity = context.findFragmentActivity() ?: return onError("Can't show the key here.")
    val authenticators = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
    if (BiometricManager.from(activity).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
        // No fingerprint or screen lock is set up, so there is nothing to check against.
        onSuccess()
        return
    }
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    onError(errString.toString())
                }
            }
        },
    )
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Show API key")
            .setSubtitle(profileName.ifBlank { "Saved profile" })
            .setAllowedAuthenticators(authenticators)
            .build(),
    )
}

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
