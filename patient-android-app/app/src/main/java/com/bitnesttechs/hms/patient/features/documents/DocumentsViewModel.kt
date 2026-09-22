package com.bitnesttechs.hms.patient.features.documents

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.R
import com.bitnesttechs.hms.patient.core.di.ApplicationScope
import com.bitnesttechs.hms.patient.core.models.DocumentDto
import com.bitnesttechs.hms.patient.core.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import javax.inject.Inject

/** What happens after the patient taps a document. */
sealed class DocumentEvent {
    /** The bytes are in the app cache; the screen hands them to a viewer. */
    data class Ready(val uri: Uri, val mimeType: String) : DocumentEvent()
    data class Failed(val detail: String?) : DocumentEvent()
}

@HiltViewModel
class DocumentsViewModel @Inject constructor(
    private val api: ApiService,
    @ApplicationContext private val appContext: Context,
    @ApplicationScope private val applicationScope: CoroutineScope
) : ViewModel() {

    data class Outcome(@StringRes val resId: Int, val detail: String? = null)

    /** What the picker returned, checked against the server's rules before the sheet opens. */
    data class PickedFile(val uri: Uri, val name: String, val sizeBytes: Long?, val mimeType: String?)

    private val _documents = MutableStateFlow<List<DocumentDto>>(emptyList())
    val documents: StateFlow<List<DocumentDto>> = _documents

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError

    /** Id of the document being fetched, so the row can show a spinner. */
    private val _opening = MutableStateFlow<String?>(null)
    val opening: StateFlow<String?> = _opening

    /** Non-null while the upload sheet is open. */
    private val _picked = MutableStateFlow<PickedFile?>(null)
    val picked: StateFlow<PickedFile?> = _picked

    private val _uploading = MutableStateFlow(false)
    val uploading: StateFlow<Boolean> = _uploading

    /** Shown inside the sheet, which stays open so the patient can try again. */
    private val _uploadError = MutableStateFlow<Outcome?>(null)
    val uploadError: StateFlow<Outcome?> = _uploadError

    /** Id of the document whose delete is out, so its row shows a spinner. */
    private val _deleting = MutableStateFlow<String?>(null)
    val deleting: StateFlow<String?> = _deleting

    private val _outcome = MutableStateFlow<Outcome?>(null)
    val outcome: StateFlow<Outcome?> = _outcome
    fun clearOutcome() { _outcome.value = null }

    private val _events = MutableSharedFlow<DocumentEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<DocumentEvent> = _events

    init { load() }

    /**
     * [quiet] keeps the current list on screen while it refreshes after an
     * upload or a delete: the full-screen spinner and the error page are
     * for the first load only, a refresh failure is a snackbar.
     */
    fun load(quiet: Boolean = false) {
        viewModelScope.launch {
            if (!quiet) {
                _isLoading.value = true
                _loadError.value = null
            }
            try {
                val resp = api.getDocuments()
                if (resp.isSuccessful) {
                    _documents.value = resp.body()?.data?.content ?: emptyList()
                } else if (quiet) {
                    _outcome.value = Outcome(R.string.documents_refresh_failed, "HTTP ${resp.code()}")
                } else {
                    _loadError.value = "HTTP ${resp.code()}"
                }
            } catch (e: Exception) {
                val detail = e.message ?: e.javaClass.simpleName
                if (quiet) _outcome.value = Outcome(R.string.documents_refresh_failed, detail)
                else _loadError.value = detail
            } finally {
                if (!quiet) _isLoading.value = false
            }
        }
    }

    // ── Upload ────────────────────────────────────────────────────────────

    /**
     * Reads the picker's name, size and MIME type and applies the server's
     * own rules (extension allow-list, size cap) before the sheet opens, so
     * a file the backend would refuse is turned away with a reason instead
     * of a failed request.
     */
    fun pick(uri: Uri) {
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) { describe(uri) }
            if (file == null) {
                _outcome.value = Outcome(R.string.document_file_unreadable)
                return@launch
            }
            val ext = file.name.substringAfterLast('.', "").lowercase()
            when {
                ext !in ALLOWED_EXTENSIONS -> _outcome.value = Outcome(R.string.document_type_unsupported)
                (file.sizeBytes ?: 0L) > MAX_UPLOAD_BYTES ->
                    _outcome.value = Outcome(R.string.document_too_large, MAX_UPLOAD_MB.toString())
                else -> {
                    _uploadError.value = null
                    _picked.value = file
                }
            }
        }
    }

    /** Closes the sheet; refused while the request is out (the screen also blocks it). */
    fun cancelPick() {
        if (_uploading.value) return
        _picked.value = null
        _uploadError.value = null
    }

    /**
     * Runs in [applicationScope]: once the bytes are on their way the server
     * may store the document whether or not this screen is still open, and a
     * back press must not leave the patient believing nothing was sent.
     */
    fun upload(documentType: String, collectionDate: String?, notes: String?) {
        val file = _picked.value ?: return
        if (_uploading.value) return
        if (documentType !in DOCUMENT_TYPES) return
        val trimmedNotes = notes?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmedNotes != null && trimmedNotes.length > NOTES_MAX) return
        _uploading.value = true
        _uploadError.value = null
        applicationScope.launch {
            try {
                // Bounded read: a provider that reported no size (pick() lets it
                // through) must not have a huge file buffered whole before the
                // cap is checked, so at most one byte past the cap is read.
                val bytes = withContext(Dispatchers.IO) {
                    appContext.contentResolver.openInputStream(file.uri)?.use { readAtMost(it, MAX_UPLOAD_BYTES + 1) }
                }
                if (bytes == null || bytes.isEmpty()) {
                    _uploadError.value = Outcome(R.string.document_file_unreadable)
                    return@launch
                }
                if (bytes.size > MAX_UPLOAD_BYTES) {
                    _uploadError.value = Outcome(R.string.document_too_large, MAX_UPLOAD_MB.toString())
                    return@launch
                }
                val mediaType = (file.mimeType ?: "application/octet-stream").toMediaTypeOrNull()
                val part = MultipartBody.Part.createFormData("file", file.name, bytes.toRequestBody(mediaType))
                val text = "text/plain".toMediaTypeOrNull()
                val resp = api.uploadDocument(
                    file = part,
                    documentType = documentType.toRequestBody(text),
                    collectionDate = collectionDate?.takeIf { it.isNotBlank() }?.toRequestBody(text),
                    notes = trimmedNotes?.toRequestBody(text)
                )
                if (resp.isSuccessful) {
                    resp.body()?.data?.let { saved -> _documents.value = listOf(saved) + _documents.value }
                    _picked.value = null
                    _outcome.value = Outcome(R.string.document_uploaded)
                    load(quiet = true)
                } else {
                    _uploadError.value = Outcome(
                        R.string.document_upload_failed,
                        serverMessage(resp.errorBody()?.string()) ?: "HTTP ${resp.code()}"
                    )
                }
            } catch (e: Exception) {
                _uploadError.value = Outcome(R.string.document_upload_failed, e.message)
            } finally {
                _uploading.value = false
            }
        }
    }

    /** Reads up to [limit] bytes; the caller treats a full buffer of [limit] as "over the cap". */
    private fun readAtMost(input: java.io.InputStream, limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var remaining = limit
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n < 0) break
            out.write(buffer, 0, n)
            remaining -= n
        }
        return out.toByteArray()
    }

    private fun describe(uri: Uri): PickedFile? {
        val resolver = appContext.contentResolver
        var name: String? = null
        var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0) name = cursor.getString(nameIdx)
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                    }
                }
        }
        val mime = runCatching { resolver.getType(uri) }.getOrNull()?.substringBefore(';')?.trim()
        var displayName = (name?.trim()?.takeIf { it.isNotEmpty() } ?: uri.lastPathSegment ?: return null)
            .substringAfterLast('/')
        // The server validates the extension, so a photo the provider named
        // without one gets the extension its MIME type implies.
        if (!displayName.contains('.')) {
            android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                ?.let { displayName = "$displayName.$it" }
        }
        return PickedFile(uri, displayName, size, mime)
    }

    // ── Delete ────────────────────────────────────────────────────────────

    /**
     * Soft delete, one at a time, in [applicationScope]: the server may have
     * already removed the document when the screen goes away.
     */
    fun delete(doc: DocumentDto) {
        if (_deleting.value != null) return
        _deleting.value = doc.id
        applicationScope.launch {
            try {
                val resp = api.deleteDocument(doc.id)
                if (resp.isSuccessful) {
                    _documents.value = _documents.value.filterNot { it.id == doc.id }
                    _outcome.value = Outcome(R.string.document_deleted)
                    load(quiet = true)
                } else {
                    _outcome.value = Outcome(
                        R.string.document_delete_failed,
                        serverMessage(resp.errorBody()?.string()) ?: "HTTP ${resp.code()}"
                    )
                }
            } catch (e: Exception) {
                _outcome.value = Outcome(R.string.document_delete_failed, e.message)
            } finally {
                _deleting.value = null
            }
        }
    }

    /** The wrapper's `message` field, which is what the web shows; never the raw JSON. */
    private fun serverMessage(body: String?): String? = body
        ?.let { runCatching { org.json.JSONObject(it).optString("message") }.getOrNull() }
        ?.takeIf { it.isNotBlank() }

    // ── Open ──────────────────────────────────────────────────────────────

    /**
     * type/subtype only: setDataAndType does not normalise, and a
     * "text/plain; charset=UTF-8" would match no viewer's filter. The backend
     * stores whatever the uploader declared, so a PDF uploaded as
     * application/octet-stream falls back to the extension's type.
     */
    private fun viewerMimeType(declared: okhttp3.MediaType?, name: String): String {
        val fromServer = declared?.let { "${it.type}/${it.subtype}" }
        if (fromServer != null && fromServer != "application/octet-stream") return fromServer
        val ext = name.substringAfterLast('.', "").lowercase()
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: fromServer ?: "application/octet-stream"
    }

    companion object {
        /** Where downloads land; wiped on logout by [TokenStorage.clearAll]. */
        const val CACHE_DIR = "documents"

        /**
         * Spring's multipart cap (`spring.servlet.multipart.max-file-size=10MB`
         * in application.properties) is the one that bites first: the
         * document service's own limit is 20 MB, and the 413 handler's text
         * says 5 MB. The client turns a larger file away with the real number.
         */
        const val MAX_UPLOAD_MB = 10
        const val MAX_UPLOAD_BYTES = MAX_UPLOAD_MB * 1024L * 1024L

        /** patient_uploaded_documents.notes is varchar(2048). */
        const val NOTES_MAX = 2048

        /** FileUploadService.ALLOWED_ATTACHMENT_EXTENSIONS, which the server checks by name. */
        val ALLOWED_EXTENSIONS = setOf("pdf", "jpg", "jpeg", "png", "gif", "bmp", "tiff", "txt", "rtf", "doc", "docx")

        /** What the system picker offers, one MIME type per allowed extension. */
        val PICKER_MIME_TYPES = arrayOf(
            "application/pdf", "image/jpeg", "image/png", "image/gif", "image/bmp", "image/tiff",
            "text/plain", "application/rtf", "text/rtf", "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        )

        /** PatientDocumentType, in the web's order; OTHER is the default like the web. */
        val DOCUMENT_TYPES = listOf(
            "LAB_RESULT", "IMAGING_REPORT", "DISCHARGE_SUMMARY", "REFERRAL_LETTER", "PRESCRIPTION",
            "INSURANCE_DOCUMENT", "INVOICE", "IMMUNIZATION_RECORD", "OTHER"
        )
        const val DEFAULT_DOCUMENT_TYPE = "OTHER"
    }

    /**
     * Downloads through the authenticated client into the app's cache and
     * exposes the file via FileProvider. The old code fired ACTION_VIEW at
     * the raw URL, which sent an external viewer to an endpoint that
     * requires the patient's token, so every open failed.
     */
    fun open(doc: DocumentDto) {
        if (_opening.value != null) return
        viewModelScope.launch {
            _opening.value = doc.id
            try {
                val resp = api.downloadDocument(doc.id)
                val body = resp.body()
                if (!resp.isSuccessful || body == null) {
                    resp.errorBody()?.close() // a @Streaming error body still holds the connection
                    _events.tryEmit(DocumentEvent.Failed("HTTP ${resp.code()}"))
                    return@launch
                }
                val mime = viewerMimeType(body.contentType(), doc.name)
                val file = withContext(Dispatchers.IO) {
                    val dir = File(appContext.cacheDir, CACHE_DIR).apply { mkdirs() }
                    // The server's display name is trusted for the extension only;
                    // the id keeps two documents with the same name apart.
                    val ext = doc.name.substringAfterLast('.', "").take(8).filter { it.isLetterOrDigit() }
                    val target = File(dir, doc.id + if (ext.isNotEmpty()) ".$ext" else "")
                    body.byteStream().use { input -> target.outputStream().use { input.copyTo(it) } }
                    target
                }
                val uri = FileProvider.getUriForFile(appContext, appContext.packageName + ".fileprovider", file)
                _events.tryEmit(DocumentEvent.Ready(uri, mime))
            } catch (e: Exception) {
                _events.tryEmit(DocumentEvent.Failed(e.message))
            } finally {
                _opening.value = null
            }
        }
    }
}
