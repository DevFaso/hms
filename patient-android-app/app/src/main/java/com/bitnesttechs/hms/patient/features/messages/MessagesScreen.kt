package com.bitnesttechs.hms.patient.features.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.ui.theme.BrandBlue
import com.bitnesttechs.hms.patient.ui.theme.BrandLightBlue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(
    onThreadClick: (String) -> Unit,
    viewModel: MessagesViewModel = hiltViewModel()
) {
    val conversations by viewModel.conversations.collectAsState()
    val careTeamMembers by viewModel.careTeamMembers.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isLoadingCareTeam by viewModel.isLoadingCareTeam.collectAsState()
    var showProviderPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Messages") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BrandBlue,
                    titleContentColor = Color.White)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    viewModel.loadCareTeam()
                    showProviderPicker = true
                },
                containerColor = BrandBlue
            ) {
                Icon(Icons.Default.Edit, "New message", tint = Color.White)
            }
        }
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BrandBlue)
            }
            return@Scaffold
        }
        if (conversations.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Message, null, Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("No messages", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(onClick = {
                        viewModel.loadCareTeam()
                        showProviderPicker = true
                    }) {
                        Icon(Icons.Default.Edit, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Message a Provider")
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(conversations) { convo ->
                    ListItem(
                        headlineContent = {
                            Text(convo.conversationUserName, fontWeight = FontWeight.Medium)
                        },
                        supportingContent = convo.lastMessageContent?.let {
                            { Text(it, maxLines = 1) }
                        },
                        leadingContent = {
                            Box(
                                Modifier.size(44.dp).clip(CircleShape).background(BrandLightBlue),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Person, null, tint = BrandBlue)
                            }
                        },
                        trailingContent = {
                            Column(horizontalAlignment = Alignment.End) {
                                convo.lastMessageTimestamp?.let {
                                    Text(it.take(10), style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (convo.unreadCount > 0) {
                                    Spacer(Modifier.height(4.dp))
                                    Badge { Text(convo.unreadCount.toString()) }
                                }
                            }
                        },
                        modifier = Modifier.clickable {
                            onThreadClick(convo.conversationUserId)
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (showProviderPicker) {
        ModalBottomSheet(onDismissRequest = { showProviderPicker = false }) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Select a Provider",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                if (careTeamMembers.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        if (isLoadingCareTeam) {
                            CircularProgressIndicator(color = BrandBlue)
                        } else {
                            Text("No providers found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    careTeamMembers.forEach { member ->
                        ListItem(
                            headlineContent = { Text(member.name, fontWeight = FontWeight.Medium) },
                            supportingContent = {
                                val info = listOfNotNull(member.role, member.specialty, member.department)
                                    .joinToString(" · ")
                                if (info.isNotEmpty()) Text(info)
                            },
                            leadingContent = {
                                Box(
                                    Modifier.size(40.dp).clip(CircleShape).background(BrandLightBlue),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Person, null, tint = BrandBlue)
                                }
                            },
                            modifier = Modifier.clickable {
                                showProviderPicker = false
                                onThreadClick(member.id)
                            }
                        )
                        HorizontalDivider()
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageThreadScreen(
    threadId: String,
    onBack: () -> Unit,
    viewModel: MessageThreadViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isSending by viewModel.isSending.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(threadId) { viewModel.loadThread(threadId) }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Messages") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BrandBlue,
                    titleContentColor = Color.White)
            )
        },
        bottomBar = {
            Surface(shadowElevation = 4.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text("Type a message…") },
                        modifier = Modifier.weight(1f),
                        maxLines = 4,
                        shape = RoundedCornerShape(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                viewModel.sendMessage(inputText.trim())
                                inputText = ""
                            }
                        },
                        enabled = inputText.isNotBlank() && !isSending
                    ) {
                        if (isSending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Send, null, tint = BrandBlue)
                    }
                }
            }
        }
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BrandBlue)
            }
            return@Scaffold
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { msg ->
                val isMine = msg.senderId == viewModel.currentUserId
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
                ) {
                    Surface(
                        shape = RoundedCornerShape(
                            topStart = 16.dp, topEnd = 16.dp,
                            bottomStart = if (isMine) 16.dp else 4.dp,
                            bottomEnd = if (isMine) 4.dp else 16.dp
                        ),
                        color = if (isMine) BrandBlue else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.widthIn(max = 280.dp)
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(
                                msg.content,
                                color = if (isMine) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                msg.timestamp.take(16),
                                color = if (isMine) Color.White.copy(alpha = 0.7f)
                                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.align(Alignment.End)
                            )
                        }
                    }
                }
            }
        }
    }
}
