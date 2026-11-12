package dev.aiui.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.aiui.data.db.AttachmentEntity
import dev.aiui.data.db.AttachmentKind
import dev.aiui.data.db.MessageStatus
import java.io.File

/** Callbacks a message needs; bundled so the list stays readable. */
class MessageActions(
    val copy: (String) -> Unit,
    val share: (String) -> Unit,
    val saveReply: (String) -> Unit,
    val saveCode: (language: String, code: String) -> Unit,
    val regenerate: (Long) -> Unit,
    val edit: (UiMessage) -> Unit,
    val openAttachment: (AttachmentEntity) -> Unit,
    val readAloud: (UiMessage) -> Unit,
    val openMemory: () -> Unit,
)

/** Whether replies can be read aloud, and which one is being read now. */
data class ReadAloudState(val available: Boolean, val speakingMessageId: Long?)

@Composable
fun MessageItem(message: UiMessage, conversationBusy: Boolean, readAloud: ReadAloudState, actions: MessageActions) {
    if (message.isUser) UserMessage(message, conversationBusy, actions) else AssistantMessage(message, conversationBusy, readAloud, actions)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserMessage(message: UiMessage, busy: Boolean, actions: MessageActions) {
    var menu by remember { mutableStateOf(false) }
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 48.dp),
    ) {
        message.attachments.forEach { AttachmentView(it, actions.openAttachment) }
        if (message.text.isNotBlank()) {
            Box {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp))
                        .combinedClickable(onClick = {}, onLongClick = { menu = true }, onLongClickLabel = "Message options"),
                ) {
                    val textDirection = remember(message.text) { textDirectionOf(message.text) }
                    Text(
                        message.text,
                        style = MaterialTheme.typography.bodyLarge.copy(textDirection = textDirection),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Copy") },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                        onClick = {
                            menu = false
                            actions.copy(message.text)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Edit and resend") },
                        leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                        enabled = !busy,
                        onClick = {
                            menu = false
                            actions.edit(message)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentView(attachment: AttachmentEntity, onOpen: (AttachmentEntity) -> Unit) {
    if (attachment.kind == AttachmentKind.IMAGE) {
        AsyncImage(
            model = File(attachment.localPath),
            contentDescription = attachment.fileName,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 200.dp, height = 140.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable { onOpen(attachment) },
        )
    } else {
        FileChip(
            name = attachment.fileName,
            detail = formatSize(attachment.sizeBytes),
            isPdf = attachment.kind == AttachmentKind.PDF,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable { onOpen(attachment) },
        )
    }
}

@Composable
fun FileChip(name: String, detail: String, isPdf: Boolean, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Icon(
                if (isPdf) Icons.Outlined.PictureAsPdf else Icons.Outlined.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.padding(start = 8.dp, end = 8.dp).widthIn(max = 200.dp)) {
                Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            trailing()
        }
    }
}

@Composable
private fun AssistantMessage(message: UiMessage, busy: Boolean, readAloud: ReadAloudState, actions: MessageActions) {
    val streaming = message.status == MessageStatus.STREAMING
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (message.text.isBlank() && streaming) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Text("Working on it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (message.text.isNotBlank()) {
            SelectionContainer {
                MarkdownContent(
                    markdown = message.text,
                    onCopyCode = actions.copy,
                    onSaveCode = actions.saveCode,
                )
            }
        }

        message.attachments.forEach { image ->
            AsyncImage(
                model = File(image.localPath),
                contentDescription = image.fileName,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { actions.openAttachment(image) },
            )
        }

        if (message.memoryUpdated && !streaming) {
            Text(
                "Memory updated",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = "Open memory") { actions.openMemory() }
                    .padding(vertical = 4.dp),
            )
        }

        statusNote(message)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (message.status == MessageStatus.ERROR) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
                    SelectionContainer {
                        Text(message.error ?: "Something went wrong.", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (message.isLast) {
                        TextButton(onClick = { actions.regenerate(message.id) }, enabled = !busy, modifier = Modifier.align(Alignment.End)) {
                            Text("Retry")
                        }
                    }
                }
            }
        }

        if (!streaming) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 0.dp)) {
                val tint = MaterialTheme.colorScheme.onSurfaceVariant
                if (message.text.isNotBlank()) {
                    IconButton(onClick = { actions.copy(message.text) }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy reply", tint = tint, modifier = Modifier.size(20.dp))
                    }
                }
                if (message.isLast && message.status != MessageStatus.ERROR) {
                    IconButton(onClick = { actions.regenerate(message.id) }, enabled = !busy) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Regenerate reply", tint = tint, modifier = Modifier.size(20.dp))
                    }
                }
                val speaking = readAloud.speakingMessageId == message.id
                if (message.text.isNotBlank() && (readAloud.available || speaking)) {
                    IconButton(onClick = { actions.readAloud(message) }) {
                        Icon(
                            if (speaking) Icons.Outlined.StopCircle else Icons.AutoMirrored.Outlined.VolumeUp,
                            contentDescription = if (speaking) "Stop reading aloud" else "Read aloud",
                            tint = tint,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                if (message.text.isNotBlank()) {
                    IconButton(onClick = { actions.share(message.text) }) {
                        Icon(Icons.Outlined.Share, contentDescription = "Share reply", tint = tint, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { actions.saveReply(message.text) }) {
                        Icon(Icons.Outlined.SaveAlt, contentDescription = "Save reply as Markdown", tint = tint, modifier = Modifier.size(20.dp))
                    }
                }
                message.modelId?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = tint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

private fun statusNote(message: UiMessage): String? = when {
    message.status == MessageStatus.STOPPED -> if (message.text.isBlank()) "Stopped before the reply began." else "Stopped."
    message.status == MessageStatus.INTERRUPTED -> "Interrupted when the app closed."
    message.stopReason == "max_tokens" || message.stopReason == "length" ->
        "The reply hit this model's output limit. Raise it in the profile's model settings."
    message.stopReason == "refusal" || message.stopReason == "content_filter" -> "The model declined to answer this."
    else -> null
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
