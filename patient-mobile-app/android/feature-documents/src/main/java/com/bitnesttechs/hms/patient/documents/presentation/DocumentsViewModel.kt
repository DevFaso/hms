package com.bitnesttechs.hms.patient.documents.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.documents.data.DocumentDto
import com.bitnesttechs.hms.patient.documents.data.DocumentsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DocumentsViewModel @Inject constructor(
    private val repository: DocumentsRepository
) : ViewModel() {

    private val _documents = MutableStateFlow<List<DocumentDto>>(emptyList())
    val documents: StateFlow<List<DocumentDto>> = _documents

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore

    private var currentPage = 0
    private val pageSize = 20

    init { loadDocuments(reset = true) }

    fun loadDocuments(reset: Boolean = false) {
        if (reset) {
            currentPage = 0
            _hasMore.value = true
            _documents.value = emptyList()
        }
        if (!_hasMore.value || _isLoading.value) return

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val response = repository.getDocuments(currentPage, pageSize)
                val page = response.data
                val newList = if (reset) page.content else _documents.value + page.content
                _documents.value = newList
                _hasMore.value = currentPage < page.totalPages - 1
                currentPage++
            } catch (e: Exception) {
                _error.value = e.message
            }
            _isLoading.value = false
        }
    }

    fun refresh() = loadDocuments(reset = true)

    fun formatFileSize(bytes: Int?): String {
        if (bytes == null) return ""
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1_048_576 -> "${bytes / 1024} KB"
            else -> String.format("%.1f MB", bytes / 1_048_576.0)
        }
    }
}
