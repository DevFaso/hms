package com.bitnesttechs.hms.patient.features.billing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.core.models.InvoiceDto
import com.bitnesttechs.hms.patient.core.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BillingViewModel @Inject constructor(private val api: ApiService) : ViewModel() {
    val invoices = MutableStateFlow<List<InvoiceDto>>(emptyList())
    val isLoading = MutableStateFlow(true)

    val totalOutstanding: Double get() = invoices.value
        .filter { !it.isPaid && !it.isCancelled }
        .sumOf { it.balanceDue }

    init { load() }

    fun load() {
        viewModelScope.launch {
            isLoading.value = true
            try { invoices.value = api.getInvoices(size = 50).body()?.data?.content ?: emptyList() }
            catch (_: Exception) {}
            finally { isLoading.value = false }
        }
    }
}
