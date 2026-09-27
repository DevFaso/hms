package com.bitnesttechs.hms.patient.features.notifications

import com.bitnesttechs.hms.patient.features.messages.ChatTime
import com.bitnesttechs.hms.patient.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.core.models.NotificationDto
import com.bitnesttechs.hms.patient.ui.theme.BrandPrimary
import com.bitnesttechs.hms.patient.ui.theme.BrandSoft

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(onBack: () -> Unit = {}, viewModel: NotificationsViewModel = hiltViewModel()) {
    val notifications by viewModel.notifications.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val unreadCount by viewModel.unreadCount.collectAsState()
    var selectedNotification by remember { mutableStateOf<NotificationDto?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.notifications)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BrandPrimary, titleContentColor = Color.White
                ),
                actions = {
                    if (unreadCount > 0L) {
                        TextButton(onClick = { viewModel.markAllRead() }) {
                            Text(stringResource(R.string.mark_all_read), color = Color.White,
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BrandPrimary)
            }
            return@Scaffold
        }

        if (notifications.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Notifications, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.no_notifications), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            return@Scaffold
        }

        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(notifications) { notif ->
                NotificationRow(notif, onTap = {
                    selectedNotification = notif
                    if (!notif.isRead) viewModel.markRead(notif.id)
                })
                HorizontalDivider()
            }
        }
    }

    // ── Notification detail dialog ────────────────────────────────────────────
    selectedNotification?.let { notif ->
        AlertDialog(
            onDismissRequest = { selectedNotification = null },
            icon = {
                Icon(
                    imageVector = notificationIcon(notif.type),
                    contentDescription = null,
                    tint = BrandPrimary,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = { Text(notif.title, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text(notif.message, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        ChatTime.bubbleLabel(notif.createdAt, java.time.LocalDateTime.now(), java.util.Locale.getDefault()) ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedNotification = null }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }
}

@Composable
private fun NotificationRow(notif: NotificationDto, onTap: () -> Unit) {
    val bgColor = if (notif.isRead) Color.Transparent else BrandSoft

    Surface(color = bgColor) {
        Row(
            Modifier
                .clickable { onTap() }
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (notif.isRead) MaterialTheme.colorScheme.surfaceVariant else BrandPrimary,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = notificationIcon(notif.type),
                        contentDescription = null,
                        tint = if (notif.isRead) MaterialTheme.colorScheme.onSurfaceVariant else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(notif.title, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (!notif.isRead) FontWeight.SemiBold else FontWeight.Normal)
                Spacer(Modifier.height(2.dp))
                Text(notif.message, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                Spacer(Modifier.height(4.dp))
                Text(notif.createdAt.take(10), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!notif.isRead) {
                Box(Modifier.size(8.dp).padding(top = 6.dp)) {
                    Surface(shape = RoundedCornerShape(50), color = BrandPrimary,
                        modifier = Modifier.size(8.dp)) {}
                }
            }
        }
    }
}

private fun notificationIcon(type: String?) = when (type?.uppercase()) {
    "APPOINTMENT" -> Icons.Default.CalendarToday
    "LAB" -> Icons.Default.Science
    "MEDICATION" -> Icons.Default.Medication
    "BILLING" -> Icons.Default.Receipt
    "MESSAGE" -> Icons.Default.Chat
    else -> Icons.Default.Notifications
}
