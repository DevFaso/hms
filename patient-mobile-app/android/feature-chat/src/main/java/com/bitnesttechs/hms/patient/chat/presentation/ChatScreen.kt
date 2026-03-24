package com.bitnesttechs.hms.patient.chat.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.chat.data.ChatMessageDto
import com.bitnesttechs.hms.patient.chat.data.ConversationDto
import com.bitnesttechs.hms.patient.core.designsystem.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    currentUserId: String,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val conversations by viewModel.conversations.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    var selectedConversation by remember { mutableStateOf<ConversationDto?>(null) }

    LaunchedEffect(currentUserId) {
        viewModel.loadConversations(currentUserId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selectedConversation?.participantName ?: "Messages") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = HmsSurface),
                navigationIcon = {
                    if (selectedConversation != null) {
                        TextButton(onClick = { selectedConversation = null }) { Text("Back") }
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (selectedConversation != null) {
                ChatThread(
                    currentUserId = currentUserId,
                    otherUserId = selectedConversation!!.participantId ?: "",
                    viewModel = viewModel
                )
            } else {
                when {
                    isLoading && conversations.isEmpty() -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    error != null && conversations.isEmpty() -> {
                        Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(error ?: "Error", color = HmsError)
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(onClick = { viewModel.loadConversations(currentUserId) }) { Text("Retry") }
                        }
                    }
                    conversations.isEmpty() -> {
                        Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Chat, null, tint = HmsTextTertiary, modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("No Messages", style = MaterialTheme.typography.titleMedium, color = HmsTextSecondary)
                        }
                    }
                    else -> {
                        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(conversations) { conv ->
                                ConversationCard(conv) { selectedConversation = conv }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationCard(conv: ConversationDto, onClick: () -> Unit) {
    HmsCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val initials = conv.participantName?.split(" ")
                ?.mapNotNull { it.firstOrNull()?.uppercase() }
                ?.take(2)?.joinToString("") ?: "?"
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape).background(HmsInfo.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(initials, style = MaterialTheme.typography.titleSmall, color = HmsInfo)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row {
                    Text(conv.participantName ?: "Unknown", style = MaterialTheme.typography.titleSmall, color = HmsTextPrimary, modifier = Modifier.weight(1f))
                    conv.lastMessageTime?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = HmsTextTertiary) }
                }
                Row {
                    Text(conv.lastMessage ?: "", style = MaterialTheme.typography.bodySmall, color = HmsTextSecondary, maxLines = 1, modifier = Modifier.weight(1f))
                    if ((conv.unreadCount ?: 0) > 0) {
                        Box(
                            modifier = Modifier.clip(CircleShape).background(HmsPrimary).padding(horizontal = 6.dp, vertical = 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("${conv.unreadCount}", style = MaterialTheme.typography.labelSmall, color = HmsSurface)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatThread(currentUserId: String, otherUserId: String, viewModel: ChatViewModel) {
    val messages by viewModel.messages.collectAsState()
    val isSending by viewModel.isSending.collectAsState()
    var messageText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(otherUserId) {
        viewModel.loadMessages(currentUserId, otherUserId)
        viewModel.markAsRead(otherUserId, currentUserId)
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { message ->
                ChatBubble(message, isMine = message.senderId == currentUserId)
            }
        }

        Divider()

        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = messageText,
                onValueChange = { messageText = it },
                placeholder = { Text("Type a message...") },
                modifier = Modifier.weight(1f),
                maxLines = 4
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = {
                    val text = messageText.trim()
                    if (text.isNotEmpty()) {
                        messageText = ""
                        viewModel.sendMessage(currentUserId, otherUserId, text)
                    }
                },
                enabled = messageText.isNotBlank() && !isSending
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send, "Send",
                    tint = if (messageText.isNotBlank()) HmsPrimary else HmsTextTertiary
                )
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessageDto, isMine: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
    ) {
        Column(horizontalAlignment = if (isMine) Alignment.End else Alignment.Start) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isMine) HmsPrimary else HmsBackground)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .widthIn(max = 280.dp)
            ) {
                Text(
                    message.content ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isMine) HmsSurface else HmsTextPrimary
                )
            }
            message.timestamp?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = HmsTextTertiary)
            }
        }
    }
}
