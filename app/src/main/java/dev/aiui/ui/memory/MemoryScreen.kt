package dev.aiui.ui.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.aiui.AppContainer
import dev.aiui.data.AppPrefs
import dev.aiui.ui.components.SectionDivider
import dev.aiui.ui.components.SettingsSection
import dev.aiui.ui.components.clickableRow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MemoryViewModel(container: AppContainer) : ViewModel() {
    private val prefsRepo = container.prefs
    private val memory = container.memory
    val prefs = prefsRepo.prefs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPrefs())
    val memories = memory.memories.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setMemory(enabled: Boolean) = viewModelScope.launch { prefsRepo.setMemory(enabled) }
    fun setMemoryUpdates(enabled: Boolean) = viewModelScope.launch { prefsRepo.setMemoryUpdates(enabled) }
    fun forget(id: Long) = viewModelScope.launch { memory.delete(id) }
    fun forgetAll() = viewModelScope.launch { memory.deleteAll() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(vm: MemoryViewModel, onBack: () -> Unit) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val memories by vm.memories.collectAsStateWithLifecycle()
    var confirmForgetAll by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Memory") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                SettingsSection("Memory") {
                    ListItem(
                        headlineContent = { Text("Use memory") },
                        supportingContent = { Text("Saved facts are sent with every chat") },
                        trailingContent = { Switch(checked = prefs.memory, onCheckedChange = { vm.setMemory(it) }) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                    SectionDivider()
                    ListItem(
                        headlineContent = { Text("Let models update memory") },
                        supportingContent = { Text("Turn off if a model saves the wrong things") },
                        trailingContent = {
                            Switch(checked = prefs.memoryUpdates, onCheckedChange = { vm.setMemoryUpdates(it) }, enabled = prefs.memory)
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
            item {
                SettingsSection("Saved") {
                    if (memories.isEmpty()) {
                        Text(
                            "Nothing yet. Ask a model to remember something and it appears here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    memories.forEachIndexed { index, memory ->
                        if (index > 0) SectionDivider()
                        ListItem(
                            headlineContent = { Text(memory.text) },
                            trailingContent = {
                                IconButton(onClick = { vm.forget(memory.id) }) {
                                    Icon(Icons.Outlined.Delete, contentDescription = "Forget")
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                    if (memories.isNotEmpty()) {
                        SectionDivider()
                        ListItem(
                            headlineContent = { Text("Forget everything", color = MaterialTheme.colorScheme.error) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickableRow { confirmForgetAll = true },
                        )
                    }
                }
            }
            item {
                Text(
                    "Memories stay on this phone. They are sent to whichever provider you chat with.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }

    if (confirmForgetAll) {
        AlertDialog(
            onDismissRequest = { confirmForgetAll = false },
            title = { Text("Forget everything?") },
            text = { Text("Every saved memory is removed. Chats are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.forgetAll()
                    confirmForgetAll = false
                }) { Text("Forget all") }
            },
            dismissButton = { TextButton(onClick = { confirmForgetAll = false }) { Text("Cancel") } },
        )
    }
}
