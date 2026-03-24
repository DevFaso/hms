package com.bitnesttechs.hms.patient.documents.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.core.designsystem.*
import com.bitnesttechs.hms.patient.documents.data.DocumentDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentsScreen(viewModel: DocumentsViewModel = hiltViewModel()) {
    val documents by viewModel.documents.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val hasMore by viewModel.hasMore.collectAsState()
    var selectedDoc by remember { mutableStateOf<DocumentDto?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Documents") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = HmsSurface)
            )
        }
    ) { padding ->
        when {
            isLoading && documents.isEmpty() -> HmsLoadingView("Loading documents...", modifier = Modifier.padding(padding))
            error != null && documents.isEmpty() -> HmsErrorView(
                message = error ?: "Failed to load documents",
                onRetry = { viewModel.refresh() },
                modifier = Modifier.padding(padding)
            )
            documents.isEmpty() -> HmsEmptyState(
                icon = Icons.Default.Description,
                title = "No Documents",
                message = "You have no documents yet.",
                modifier = Modifier.padding(padding)
            )
            selectedDoc != null -> {
                DocumentDetailContent(
                    document = selectedDoc!!,
                    viewModel = viewModel,
                    onBack = { selectedDoc = null },
                    modifier = Modifier.padding(padding)
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(documents, key = { it.id }) { doc ->
                        DocumentCard(doc, viewModel) { selectedDoc = doc }
                    }
                    if (hasMore) {
                        item {
                            LaunchedEffect(Unit) { viewModel.loadDocuments() }
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp), color = HmsPrimary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentCard(doc: DocumentDto, viewModel: DocumentsViewModel, onClick: () -> Unit) {
    val icon = docIcon(doc.mimeType)

    HmsCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = HmsPrimary.copy(alpha = 0.1f),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(24.dp), tint = HmsPrimary)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        doc.documentName ?: doc.fileName ?: "Document",
                        style = MaterialTheme.typography.titleSmall,
                        color = HmsTextPrimary,
                        maxLines = 1
                    )
                    if (doc.isConfidential == true) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.Lock, null, Modifier.size(14.dp), tint = HmsWarning)
                    }
                }
                (doc.category ?: doc.documentType)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = HmsTextSecondary)
                }
                Row {
                    (doc.uploadDate ?: doc.createdAt)?.let {
                        Text(it.take(10), style = MaterialTheme.typography.labelSmall, color = HmsTextTertiary)
                    }
                    doc.fileSize?.let {
                        Text(" • ${viewModel.formatFileSize(it)}", style = MaterialTheme.typography.labelSmall, color = HmsTextTertiary)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentDetailContent(document: DocumentDto, viewModel: DocumentsViewModel, onBack: () -> Unit, modifier: Modifier) {
    Column(modifier = modifier) {
        TopAppBar(
            title = { Text("Document Details") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = HmsSurface)
        )
        LazyColumn(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            item {
                HmsCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = HmsPrimary.copy(alpha = 0.1f),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.size(56.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(docIcon(document.mimeType), null, Modifier.size(32.dp), tint = HmsPrimary)
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(document.documentName ?: "Document", style = MaterialTheme.typography.titleMedium)
                            document.documentType?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = HmsTextSecondary) }
                            StatusChip(document.status ?: "uploaded")
                        }
                    }
                }
            }
            item {
                HmsSectionHeader(title = "File Information")
                HmsCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        document.fileName?.let { InfoRow("File Name", it) }
                        document.mimeType?.let { InfoRow("Type", it) }
                        document.fileSize?.let { InfoRow("Size", viewModel.formatFileSize(it)) }
                        document.category?.let { InfoRow("Category", it) }
                        if (document.isConfidential == true) {
                            Row {
                                Icon(Icons.Default.Lock, null, Modifier.size(16.dp), tint = HmsWarning)
                                Spacer(Modifier.width(8.dp))
                                Text("Confidential Document", style = MaterialTheme.typography.bodySmall, color = HmsWarning)
                            }
                        }
                    }
                }
            }
            item {
                HmsSectionHeader(title = "Upload Details")
                HmsCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        document.uploadedBy?.let { InfoRow("Uploaded By", it) }
                        document.uploadedByRole?.let { InfoRow("Role", it) }
                        document.hospitalName?.let { InfoRow("Hospital", it) }
                        document.departmentName?.let { InfoRow("Department", it) }
                        (document.uploadDate ?: document.createdAt)?.let { InfoRow("Date", it.take(10)) }
                    }
                }
            }
            document.description?.let { desc ->
                item {
                    HmsSectionHeader(title = "Description")
                    HmsCard { Text(desc, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodySmall, color = HmsTextTertiary, modifier = Modifier.width(100.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = HmsTextPrimary)
    }
}

@Composable
private fun StatusChip(status: String) {
    val color = when (status.lowercase()) {
        "uploaded", "active" -> HmsSuccess
        "pending" -> HmsWarning
        "archived" -> HmsTextTertiary
        else -> HmsInfo
    }
    Surface(color = color.copy(alpha = 0.12f), shape = MaterialTheme.shapes.small) {
        Text(status.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

private fun docIcon(mimeType: String?): androidx.compose.ui.graphics.vector.ImageVector {
    val mime = mimeType?.lowercase() ?: ""
    return when {
        mime.contains("pdf") -> Icons.Default.PictureAsPdf
        mime.contains("image") -> Icons.Default.Image
        mime.contains("word") || mime.contains("document") -> Icons.Default.Article
        mime.contains("spreadsheet") || mime.contains("excel") -> Icons.Default.TableChart
        else -> Icons.Default.Description
    }
}
