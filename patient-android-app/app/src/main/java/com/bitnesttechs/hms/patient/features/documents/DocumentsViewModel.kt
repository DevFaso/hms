package com.bitnesttechs.hms.patient.features.documents

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.core.models.DocumentDto
import com.bitnesttechs.hms.patient.core.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _documents = MutableStateFlow<List<DocumentDto>>(emptyList())
    val documents: StateFlow<List<DocumentDto>> = _documents

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError

    /** Id of the document being fetched, so the row can show a spinner. */
    private val _opening = MutableStateFlow<String?>(null)
    val opening: StateFlow<String?> = _opening

    private val _events = MutableSharedFlow<DocumentEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<DocumentEvent> = _events

    init { load() }

    fun load() {
        viewModelScope.launch {
            _isLoading.value = true
            _loadError.value = null
            try {
                val resp = api.getDocuments()
                if (resp.isSuccessful) {
                    _documents.value = resp.body()?.data ?: emptyList()
                } else {
                    _loadError.value = "HTTP ${resp.code()}"
                }
            } catch (e: Exception) {
                _loadError.value = e.message ?: e.javaClass.simpleName
            } finally {
                _isLoading.value = false
            }
        }
    }

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

    /** Where downloads land; wiped on logout by [TokenStorage.clearAll]. */
    companion object {
        const val CACHE_DIR = "documents"
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
