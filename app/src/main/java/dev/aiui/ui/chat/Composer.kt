package dev.aiui.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.aiui.data.PendingAttachment
import dev.aiui.data.db.AttachmentKind
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun Composer(
    text: String,
    onTextChange: (String) -> Unit,
    placeholder: String,
    pending: List<PendingAttachment>,
    importing: Boolean,
    editing: Boolean,
    generating: Boolean,
    enabled: Boolean,
    onCancelEdit: () -> Unit,
    onAttach: () -> Unit,
    onRemove: (PendingAttachment) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(6.dp)) {
            if (editing) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp),
                ) {
                    Text(
                        "Editing. Sending replaces this message and every reply after it.",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.primary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCancelEdit) { Text("Cancel") }
                }
            }
            if (pending.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    items(pending, key = { it.localPath }) { attachment ->
                        PendingChip(attachment, onRemove = { onRemove(attachment) })
                    }
                }
            }
            TextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = { Text(placeholder) },
                textStyle = LocalTextStyle.current.copy(textDirection = textDirectionOf(text)),
                maxLines = 8,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onAttach, enabled = !editing) {
                    Icon(Icons.Outlined.Add, contentDescription = "Attach a file or image")
                }
                Spacer(Modifier.weight(1f))
                if (importing) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp).padding(end = 4.dp))
                    Spacer(Modifier.size(12.dp))
                }
                if (generating) {
                    FilledIconButton(onClick = onStop) {
                        Icon(Icons.Filled.Stop, contentDescription = "Stop generating")
                    }
                } else {
                    FilledIconButton(
                        onClick = onSend,
                        enabled = enabled && !importing && (text.isNotBlank() || pending.isNotEmpty()),
                    ) {
                        Icon(
                            if (editing) Icons.AutoMirrored.Filled.ArrowForward else Icons.Filled.ArrowUpward,
                            contentDescription = if (editing) "Resend edited message" else "Send message",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingChip(attachment: PendingAttachment, onRemove: () -> Unit) {
    val remove: @Composable () -> Unit = {
        IconButton(onClick = onRemove, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Remove ${attachment.fileName}", modifier = Modifier.size(18.dp))
        }
    }
    if (attachment.kind == AttachmentKind.IMAGE) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                AsyncImage(
                    model = File(attachment.localPath),
                    contentDescription = attachment.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                remove()
            }
        }
    } else {
        FileChip(
            name = attachment.fileName,
            detail = formatSize(attachment.sizeBytes),
            isPdf = attachment.kind == AttachmentKind.PDF,
            trailing = remove,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachSheet(
    onCamera: () -> Unit,
    onPhotos: () -> Unit,
    onFiles: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val choose: (() -> Unit) -> Unit = { action ->
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            Text("Attach", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp, bottom = 16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AttachTile("Camera", Icons.Outlined.CameraAlt, Modifier.weight(1f)) { choose(onCamera) }
                AttachTile("Photos", Icons.Outlined.Image, Modifier.weight(1f)) { choose(onPhotos) }
                AttachTile("Files", Icons.Outlined.Description, Modifier.weight(1f)) { choose(onFiles) }
            }
            Text(
                "Images, PDFs, and text or code files. Images are resized to 2,048 px on the long edge.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, top = 16.dp),
            )
        }
    }
}

@Composable
private fun AttachTile(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .height(88.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize(),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
