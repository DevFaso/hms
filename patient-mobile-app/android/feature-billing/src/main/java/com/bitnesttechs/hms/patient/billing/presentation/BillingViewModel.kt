package com.bitnesttechs.hms.patient.billing.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.billing.data.BillingRepository
import com.bitnesttechs.hms.patient.billing.data.InvoiceDto
import com.bitnesttechs.hms.patient.core.network.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BillingViewModel @Inject constructor(
    private val repository: BillingRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(BillingUiState())
    val uiState: StateFlow<BillingUiState> = _uiState

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            when (val result = repository.getInvoices()) {
                is Result.Success -> {
                    val invoices = result.data.content
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        invoices = invoices,
                        totalOutstanding = invoices.filter { it.status?.uppercase() != "PAID" }
                            .sumOf { it.totalAmount ?: 0.0 },
                        totalPaid = invoices.filter { it.status?.uppercase() == "PAID" }
                            .sumOf { it.totalAmount ?: 0.0 },
                        hasMorePages = result.data.number < result.data.totalPages - 1,
                        currentPage = 0
                    )
                }
                is Result.Error -> _uiState.value = _uiState.value.copy(isLoading = false, error = result.message)
                else -> {}
            }
        }
    }

    fun loadNextPage() {
        val state = _uiState.value
        if (!state.hasMorePages || state.isLoading) return
        viewModelScope.launch {
            when (val result = repository.getInvoices(page = state.currentPage + 1)) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(
                        invoices = state.invoices + result.data.content,
                        hasMorePages = result.data.number < result.data.totalPages - 1,
                        currentPage = state.currentPage + 1
                    )
                }
                is Result.Error -> _uiState.value = _uiState.value.copy(error = result.message)
                else -> {}
            }
        }
    }
}

data class BillingUiState(
    val isLoading: Boolean = false,
    val invoices: List<InvoiceDto> = emptyList(),
    val totalOutstanding: Double = 0.0,
    val totalPaid: Double = 0.0,
    val hasMorePages: Boolean = false,
    val currentPage: Int = 0,
    val error: String? = null
)
