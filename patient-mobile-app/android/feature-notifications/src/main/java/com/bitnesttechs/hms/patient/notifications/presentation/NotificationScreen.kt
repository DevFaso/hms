package com.bitnesttechs.hms.patient.notifications.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.core.designsystem.*
import com.bitnesttechs.hms.patient.notifications.data.NotificationDto
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun NotificationScreen(viewModel: NotificationViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()

    Column {
        if (state.unreadCount > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    "${state.unreadCount} unread",
                    style = MaterialTheme.typography.bodySmall,
                    color = HmsTextSecondary
                )
            }
        }

        when {
            state.isLoading -> HmsLoadingView("Loading notifications...")
            state.error != null -> HmsErrorView(state.error!!) { viewModel.load() }
            state.notifications.isEmpty() -> HmsEmptyState(
                Icons.Default.NotificationsOff, "No Notifications", "You're all caught up!"
            )
            else -> {
                LazyColumn {
                    items(state.notifications, key = { it.id }) { notification ->
                        NotificationRow(
                            notification = notification,
                            onClick = {
                                if (notification.read != true) {
                                    viewModel.markAsRead(notification.id)
                                }
                            }
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(notification: NotificationDto, onClick: () -> Unit) {
    val isUnread = notification.read != true
    val iconData = notificationIcon(notification.type)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (isUnread) HmsPrimary.copy(alpha = 0.04f) else MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(iconData.second.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(iconData.first, contentDescription = null, modifier = Modifier.size(20.dp), tint = iconData.second)
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    notification.title ?: "Notification",
                    style = if (isUnread) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                    color = HmsTextPrimary,
                    modifier = Modifier.weight(1f)
                )
                notification.createdAt?.let {
                    Text(
                        formatTimeAgo(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = HmsTextTertiary
                    )
                }
            }
            notification.message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = HmsTextSecondary,
                    maxLines = 2
                )
            }
        }

        if (isUnread) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(HmsPrimary)
                    .align(Alignment.CenterVertically)
            )
        }
    }
}

private fun notificationIcon(type: String?): Pair<ImageVector, androidx.compose.ui.graphics.Color> {
    return when (type?.uppercase()) {
        "APPOINTMENT" -> Icons.Default.CalendarMonth to HmsPrimary
        "LAB_RESULT" -> Icons.Default.Science to HmsWarning
        "PRESCRIPTION", "MEDICATION" -> Icons.Default.Medication to HmsAccent
        "BILLING" -> Icons.Default.CreditCard to HmsInfo
        "MESSAGE" -> Icons.Default.Chat to HmsSuccess
        else -> Icons.Default.Notifications to HmsTextSecondary
    }
}

private fun formatTimeAgo(isoString: String): String {
    return try {
        val instant = Instant.parse(isoString)
        val formatter = DateTimeFormatter.ofPattern("MMM d")
            .withZone(ZoneId.systemDefault())
        formatter.format(instant)
    } catch (_: Exception) {
        isoString.take(10)
    }
}
