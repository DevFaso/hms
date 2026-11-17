package com.bitnesttechs.hms.patient.features.messages

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.bitnesttechs.hms.patient.R
import com.bitnesttechs.hms.patient.core.models.ChatAttachmentDto
import com.bitnesttechs.hms.patient.core.models.ChatAttachmentKind
import java.io.File

/**
 * One attachment inside a chat bubble. A photo downloads as soon as it is on
 * screen and shows as a thumbnail (tap: full screen); a voice note downloads
 * when played (these are low-bandwidth consults) and plays in-app from the
 * cached file; anything else is a file row handed to an external viewer.
 */
@Composable
fun ChatAttachmentView(
    attachment: ChatAttachmentDto,
    isMine: Boolean,
    file: File?,
    failed: Boolean,
    playing: Boolean,
    onFetch: () -> Unit,
    onToggleAudio: () -> Unit,
    onOpenPhoto: (File) -> Unit,
    onOpenFile: () -> Unit
) {
    val content = if (isMine) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    when (attachment.kindEnum) {
        ChatAttachmentKind.PHOTO -> {
            LaunchedEffect(attachment.id) { if (file == null && !failed) onFetch() }
            val label = attachment.displayName ?: stringResource(R.string.chat_attachment_photo)
            val shape = RoundedCornerShape(10.dp)
            when {
                file != null -> AsyncImage(
                    model = file,
                    contentDescription = label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(200.dp)
                        .clip(shape)
                        .clickable(onClickLabel = stringResource(R.string.chat_attachment_view_photo), role = Role.Image) {
                            onOpenPhoto(file)
                        }
                )
                failed -> AttachmentRow(
                    icon = { Icon(Icons.Default.BrokenImage, null, tint = content) },
                    text = stringResource(R.string.chat_attachment_unavailable),
                    color = content,
                    onClick = onFetch
                )
                else -> Box(
                    Modifier.size(200.dp).clip(shape).background(content.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = content, strokeWidth = 2.dp)
                }
            }
        }
        ChatAttachmentKind.AUDIO -> {
            val duration = attachment.durationSeconds?.takeIf { it > 0 }
                ?.let { "%d:%02d".format(it / 60, it % 60) }
            val title = if (duration != null) stringResource(R.string.chat_attachment_voice_duration, duration)
                else stringResource(R.string.chat_attachment_voice)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onToggleAudio) {
                    Icon(
                        if (playing) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = stringResource(
                            if (playing) R.string.chat_attachment_stop else R.string.chat_attachment_play
                        ),
                        tint = content
                    )
                }
                Text(
                    if (failed) stringResource(R.string.chat_attachment_unavailable) else title,
                    color = content,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        ChatAttachmentKind.OTHER -> AttachmentRow(
            icon = { Icon(Icons.Default.AttachFile, null, tint = content) },
            text = if (failed) stringResource(R.string.chat_attachment_unavailable)
                else attachment.displayName ?: stringResource(R.string.chat_attachment_file),
            color = content,
            onClick = onOpenFile
        )
    }
}

@Composable
private fun AttachmentRow(icon: @Composable () -> Unit, text: String, color: Color, onClick: () -> Unit) {
    Row(
        Modifier.widthIn(max = 240.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        icon()
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The photo at full size over a black backdrop; the back gesture or the close button dismisses it. */
@Composable
fun FullScreenPhoto(file: File, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(
                model = file,
                contentDescription = stringResource(R.string.chat_attachment_photo),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Icon(Icons.Default.Close, stringResource(R.string.close), tint = Color.White)
            }
        }
    }
}

/** A cached attachment of a kind the app does not draw itself, handed to whatever viewer the phone has. */
fun openAttachmentFile(context: Context, file: File, attachment: ChatAttachmentDto) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, attachment.contentType ?: "application/octet-stream")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.chat_attachment_no_viewer), Toast.LENGTH_LONG).show()
    }
}
