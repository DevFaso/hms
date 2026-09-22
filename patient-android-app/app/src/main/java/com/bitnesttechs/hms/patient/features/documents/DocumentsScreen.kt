package com.bitnesttechs.hms.patient.features.documents

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import com.bitnesttechs.hms.patient.R
import com.bitnesttechs.hms.patient.core.models.DocumentDto
import com.bitnesttechs.hms.patient.ui.theme.BrandBlue
import com.bitnesttechs.hms.patient.ui.theme.BrandLightBlue
import com.bitnesttechs.hms.patient.ui.theme.ErrorRed
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentsScreen(onBack: () -> Unit = {}, viewModel: DocumentsViewModel = hiltViewModel()) {
    val documents by viewModel.documents.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val loadError by viewModel.loadError.collectAsState()
    val opening by viewModel.opening.collectAsState()
    val picked by viewModel.picked.collectAsState()
    val uploading by viewModel.uploading.collectAsState()
    val uploadError by viewModel.uploadError.collectAsState()
    val deleting by viewModel.deleting.collectAsState()
    val outcome by viewModel.outcome.collectAsState()
    val context = LocalContext.current

    val snackbarHostState = remember { SnackbarHostState() }
    val openFailed = stringResource(R.string.document_open_failed)
    val noViewer = stringResource(R.string.document_no_viewer)
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        // showSnackbar suspends for the snackbar's lifetime; called inline it
        // would stall this collector and drop events beyond the flow's buffer.
        viewModel.events.collect { event ->
            when (event) {
                is DocumentEvent.Ready -> {
                    val intent = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(event.uri, event.mimeType)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    // Not wrapped in createChooser: the chooser always resolves,
                    // so a phone with no viewer for this type would get Android's
                    // generic dialog instead of the message below.
                    try {
                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        scope.launch { snackbarHostState.showSnackbar(noViewer) }
                    }
                }
                is DocumentEvent.Failed ->
                    scope.launch { snackbarHostState.showSnackbar(event.detail?.let { "$openFailed ($it)" } ?: openFailed) }
            }
        }
    }

    // Upload / delete / refresh results, one snackbar each; the detail is the
    // server's own message or the size cap, never raw JSON.
    outcome?.let { o ->
        val text = outcomeText(o)
        LaunchedEffect(o) {
            viewModel.clearOutcome()
            snackbarHostState.showSnackbar(text)
        }
    }

    // OpenDocument rather than GetContent: the grant covers a later read from
    // the application scope, and the MIME list mirrors the server's allow-list.
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.pick(it) }
    }

    var pendingDelete by remember { mutableStateOf<DocumentDto?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.documents)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BrandBlue, titleContentColor = Color.White
                )
            )
        },
        floatingActionButton = {
            // Offered whenever the list is usable (loaded, empty or not); a
            // failed first load keeps its retry page instead.
            if (!isLoading && loadError == null) {
                ExtendedFloatingActionButton(
                    onClick = { filePicker.launch(DocumentsViewModel.PICKER_MIME_TYPES) },
                    icon = { Icon(Icons.Default.UploadFile, null) },
                    text = { Text(stringResource(R.string.upload_document)) },
                    containerColor = BrandBlue,
                    contentColor = Color.White
                )
            }
        }
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BrandBlue)
            }
            return@Scaffold
        }

        loadError?.let { error ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Icon(Icons.Default.CloudOff, null, tint = ErrorRed, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.documents_load_failed, error), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { viewModel.load() }) { Text(stringResource(R.string.retry)) }
                }
            }
            return@Scaffold
        }

        if (documents.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Icon(Icons.Default.FolderOpen, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.no_documents), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.no_documents_desc), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(documents, key = { it.id }) { doc ->
                    DocumentRow(
                        doc = doc,
                        isOpening = opening == doc.id,
                        isDeleting = deleting == doc.id,
                        deleteEnabled = deleting == null,
                        onOpen = { viewModel.open(doc) },
                        onDelete = { pendingDelete = doc }
                    )
                }
                // Room for the FAB over the last row.
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    pendingDelete?.let { doc ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_document)) },
            text = { Text(stringResource(R.string.delete_document_confirm, doc.name)) },
            confirmButton = {
                TextButton(onClick = { pendingDelete = null; viewModel.delete(doc) }) {
                    Text(stringResource(R.string.delete), color = ErrorRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    picked?.let { file ->
        UploadSheet(
            file = file,
            uploading = uploading,
            error = uploadError,
            onUpload = { type, date, notes -> viewModel.upload(type, date, notes) },
            onDismiss = { viewModel.cancelPick() }
        )
    }
}

@Composable
private fun outcomeText(o: DocumentsViewModel.Outcome): String = when (o.resId) {
    // The cap is the placeholder, not a suffix.
    R.string.document_too_large -> stringResource(R.string.document_too_large, o.detail ?: "")
    else -> {
        val base = stringResource(o.resId)
        o.detail?.let { "$base ($it)" } ?: base
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UploadSheet(
    file: DocumentsViewModel.PickedFile,
    uploading: Boolean,
    error: DocumentsViewModel.Outcome?,
    onUpload: (documentType: String, collectionDate: String?, notes: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var documentType by rememberSaveable { mutableStateOf(DocumentsViewModel.DEFAULT_DOCUMENT_TYPE) }
    var typeMenu by remember { mutableStateOf(false) }
    var collectionDate by rememberSaveable { mutableStateOf<String?>(null) }
    var notes by rememberSaveable { mutableStateOf("") }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    // While the request is out, the sheet stays: a swipe, a scrim tap or back
    // would otherwise let the patient reopen and send the same file twice.
    // The lambda is one remembered instance reading the latest flag: Material3
    // keys the sheet state on it, and a fresh lambda per recomposition would
    // rebuild the state at Hidden and drop the sheet on every state change.
    val isUploading by rememberUpdatedState(uploading)
    val keepWhileUploading = remember { { _: SheetValue -> !isUploading } }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = keepWhileUploading)

    val notesMax = DocumentsViewModel.NOTES_MAX
    val canSubmit = !uploading && notes.length <= notesMax
    val dateLabel = collectionDate?.let { iso ->
        runCatching { LocalDate.parse(iso).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())) }
            .getOrDefault(iso)
    } ?: ""

    ModalBottomSheet(
        onDismissRequest = { if (!uploading) onDismiss() },
        sheetState = sheetState,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !uploading)
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(stringResource(R.string.upload_document), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(docIcon(file.name.substringAfterLast('.', "")), null, tint = BrandBlue)
                Column(Modifier.weight(1f)) {
                    Text(file.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    file.sizeBytes?.let {
                        Text(stringResource(R.string.file_size_kb, fileSizeKb(it)), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            ExposedDropdownMenuBox(expanded = typeMenu, onExpandedChange = { if (!uploading) typeMenu = !typeMenu }) {
                OutlinedTextField(
                    value = stringResource(documentTypeLabel(documentType)),
                    onValueChange = {},
                    readOnly = true,
                    enabled = !uploading,
                    label = { Text(stringResource(R.string.document_type)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeMenu) },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                    DocumentsViewModel.DOCUMENT_TYPES.forEach { type ->
                        DropdownMenuItem(
                            text = { Text(stringResource(documentTypeLabel(type))) },
                            onClick = { typeMenu = false; documentType = type }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = dateLabel,
                onValueChange = {},
                readOnly = true,
                enabled = !uploading,
                label = { Text(stringResource(R.string.collection_date_optional)) },
                trailingIcon = {
                    Row {
                        if (collectionDate != null) {
                            IconButton(onClick = { collectionDate = null }, enabled = !uploading) {
                                Icon(Icons.Default.Clear, stringResource(R.string.clear_date))
                            }
                        }
                        IconButton(onClick = { showDatePicker = true }, enabled = !uploading) {
                            Icon(Icons.Default.CalendarMonth, stringResource(R.string.pick_date))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().clickable(enabled = !uploading) { showDatePicker = true }
            )

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                enabled = !uploading,
                label = { Text(stringResource(R.string.notes_optional)) },
                placeholder = { Text(stringResource(R.string.document_notes_placeholder)) },
                isError = notes.length > notesMax,
                supportingText = { Text("${notes.length}/$notesMax") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )

            error?.let { e ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(outcomeText(e), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }

            Button(
                onClick = { onUpload(documentType, collectionDate, notes) },
                enabled = canSubmit,
                colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (uploading) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.uploading))
                } else {
                    Text(stringResource(R.string.upload))
                }
            }
            TextButton(onClick = onDismiss, enabled = !uploading, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.cancel))
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showDatePicker) {
        // No bound, like the web's date input: the collection date is history the
        // patient reports, and the server accepts any ISO date.
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = collectionDate?.let {
                runCatching { LocalDate.parse(it).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli() }.getOrNull()
            }
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        collectionDate = Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate()
                            .format(DateTimeFormatter.ISO_LOCAL_DATE)
                    }
                    showDatePicker = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.cancel)) }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun DocumentRow(
    doc: DocumentDto,
    isOpening: Boolean,
    isDeleting: Boolean,
    deleteEnabled: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(1.dp),
        modifier = Modifier.fillMaxWidth().clickable(enabled = !isOpening && !isDeleting) { onOpen() }
    ) {
        Row(
            Modifier.padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = RoundedCornerShape(10.dp), color = BrandLightBlue,
                modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(docIcon(doc.kind), null, tint = BrandBlue, modifier = Modifier.size(22.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(doc.name, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium)
                Text(
                    // A type this build does not know yet is shown as the server sent it, like the web.
                    doc.documentType?.let { t -> if (t in DocumentsViewModel.DOCUMENT_TYPES) stringResource(documentTypeLabel(t)) else t }
                        ?: stringResource(R.string.document),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val meta = listOfNotNull(
                    doc.uploadedAt.take(10).takeIf { it.isNotEmpty() },
                    doc.sizeBytes?.let { stringResource(R.string.file_size_kb, fileSizeKb(it)) }
                ).joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(meta, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                doc.uploadedByDisplayName?.takeIf { it.isNotBlank() }?.let {
                    Text(stringResource(R.string.uploaded_by, it), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                doc.notes?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
            }
            if (isOpening) {
                CircularProgressIndicator(Modifier.size(18.dp), color = BrandBlue, strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.OpenInNew, stringResource(R.string.open), tint = BrandBlue, modifier = Modifier.size(18.dp))
            }
            // The web offers Delete on every row: the server only checks that
            // the document belongs to the signed-in patient.
            if (isDeleting) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = ErrorRed, strokeWidth = 2.dp)
                }
            } else {
                IconButton(onClick = onDelete, enabled = deleteEnabled && !isOpening) {
                    Icon(Icons.Default.Delete, stringResource(R.string.delete_document), tint = ErrorRed)
                }
            }
        }
    }
}

private fun fileSizeKb(bytes: Long): String = String.format(Locale.getDefault(), "%.1f", bytes / 1024.0)

@StringRes
private fun documentTypeLabel(type: String): Int = when (type) {
    "LAB_RESULT" -> R.string.doc_type_lab_result
    "IMAGING_REPORT" -> R.string.doc_type_imaging_report
    "DISCHARGE_SUMMARY" -> R.string.doc_type_discharge_summary
    "REFERRAL_LETTER" -> R.string.doc_type_referral_letter
    "PRESCRIPTION" -> R.string.doc_type_prescription
    "INSURANCE_DOCUMENT" -> R.string.doc_type_insurance_document
    "INVOICE" -> R.string.doc_type_invoice
    "IMMUNIZATION_RECORD" -> R.string.doc_type_immunization_record
    else -> R.string.doc_type_other
}

private fun docIcon(kind: String?) = when (kind?.uppercase()) {
    "PDF" -> Icons.Default.PictureAsPdf
    "IMAGE", "JPG", "JPEG", "PNG", "GIF", "BMP", "TIFF" -> Icons.Default.Image
    "DICOM" -> Icons.Default.Biotech
    else -> Icons.Default.Description
}
