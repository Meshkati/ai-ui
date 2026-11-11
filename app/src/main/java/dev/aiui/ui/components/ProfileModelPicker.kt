package dev.aiui.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aiui.data.ProfileWithModels
import dev.aiui.llm.isLocalNetworkHost
import dev.aiui.ui.theme.MonoFamily
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Two steps, as designed: choose a profile, then one of its models. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileModelPickerSheet(
    profiles: List<ProfileWithModels>,
    currentProfileId: Long?,
    currentModelId: String?,
    onPick: (profileId: Long, modelId: String) -> Unit,
    onAddProfile: () -> Unit,
    onEditProfile: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var openProfileId by remember { mutableStateOf<Long?>(null) }
    val close: (() -> Unit) -> Unit = { after ->
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            after()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        AnimatedContent(
            targetState = openProfileId,
            transitionSpec = {
                if (targetState != null) {
                    slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                } else {
                    slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
                }
            },
            label = "picker-step",
        ) { profileId ->
            val profile = profiles.firstOrNull { it.profile.id == profileId }
            if (profile == null) {
                ProfileStep(
                    profiles = profiles,
                    currentProfileId = currentProfileId,
                    onOpen = { openProfileId = it },
                    onAddProfile = { close(onAddProfile) },
                )
            } else {
                ModelStep(
                    profile = profile,
                    currentModelId = currentModelId.takeIf { currentProfileId == profile.profile.id },
                    onBack = { openProfileId = null },
                    onPick = { modelId -> close { onPick(profile.profile.id, modelId) } },
                    onEdit = { close { onEditProfile(profile.profile.id) } },
                )
            }
        }
    }
}

@Composable
private fun StepHeader(step: String, title: String, onBack: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = if (onBack == null) 24.dp else 8.dp, end = 24.dp, bottom = 8.dp),
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to profiles")
            }
        }
        Column {
            Text(step, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun ProfileStep(
    profiles: List<ProfileWithModels>,
    currentProfileId: Long?,
    onOpen: (Long) -> Unit,
    onAddProfile: () -> Unit,
) {
    Column(Modifier.padding(bottom = 16.dp)) {
        StepHeader("Step 1 of 2", "Choose a profile")
        if (profiles.isEmpty()) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "No profiles yet. A profile is one base URL and API key.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onAddProfile) { Text("Add profile") }
            }
            return@Column
        }
        LazyColumn(Modifier.heightIn(max = 480.dp)) {
            items(profiles, key = { it.profile.id }) { item ->
                val selected = item.profile.id == currentProfileId
                val preferred = item.preferredModel
                ListItem(
                    headlineContent = { Text(item.profile.name) },
                    supportingContent = {
                        Text(
                            preferred?.modelId ?: "No models switched on",
                            fontFamily = MonoFamily,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingContent = { Avatar(item.profile.name, highlighted = selected) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isLocal(item.profile.baseUrl)) LocalBadge()
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                        }
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    ),
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .clickableRow { onOpen(item.profile.id) },
                )
            }
        }
        Text(
            "The model under each name is the one you used last.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        TextButton(onClick = onAddProfile, modifier = Modifier.padding(horizontal = 12.dp)) {
            Text("Add profile")
        }
    }
}

@Composable
private fun ModelStep(
    profile: ProfileWithModels,
    currentModelId: String?,
    onBack: () -> Unit,
    onPick: (String) -> Unit,
    onEdit: () -> Unit,
) {
    Column(Modifier.padding(bottom = 16.dp)) {
        StepHeader("Step 2 of 2 · ${profile.profile.name}", "Choose a model", onBack)
        val models = profile.enabledModels
        if (models.isEmpty()) {
            Text(
                "No models are switched on in this profile.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
        LazyColumn(Modifier.heightIn(max = 480.dp)) {
            items(models, key = { it.modelId }) { model ->
                val selected = model.modelId == currentModelId
                ListItem(
                    headlineContent = {
                        Text(model.modelId, fontFamily = MonoFamily, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = when {
                        !model.supportsAttachments -> ({ Text("Text only") })
                        model.displayName != null && model.displayName != model.modelId -> ({ Text(model.displayName) })
                        else -> null
                    },
                    trailingContent = if (selected) ({ Icon(Icons.Filled.Check, contentDescription = "Selected") }) else null,
                    colors = ListItemDefaults.colors(
                        containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    ),
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .clickableRow { onPick(model.modelId) },
                )
            }
        }
        TextButton(onClick = onEdit, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text("Edit the ${profile.profile.name} profile")
        }
    }
}

@Composable
fun Avatar(name: String, highlighted: Boolean = false, size: Int = 40) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = if (highlighted) colors.primary else colors.surfaceContainerHighest,
        contentColor = if (highlighted) colors.onPrimary else colors.onSurfaceVariant,
        modifier = Modifier.size(size.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                name.trim().firstOrNull()?.uppercase() ?: "?",
                style = if (size >= 56) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
fun LocalBadge() {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.padding(end = 8.dp),
    ) {
        Text("Local", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

fun isLocal(baseUrl: String): Boolean = baseUrl.toHttpUrlOrNull()?.host?.let(::isLocalNetworkHost) ?: false

fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
