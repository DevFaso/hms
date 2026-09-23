package com.bitnesttechs.hms.patient.features.billing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.core.models.InvoiceDto
import com.bitnesttechs.hms.patient.core.models.PatientPaymentRequest
import com.bitnesttechs.hms.patient.core.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One-shot outcomes the screen turns into a snackbar. */
sealed class BillingEvent {
    data object PaymentRecorded : BillingEvent()
    data class PaymentFailed(val detail: String?) : BillingEvent()
}

@HiltViewModel
class BillingViewModel @Inject constructor(private val api: ApiService) : ViewModel() {
    val invoices = MutableStateFlow<List<InvoiceDto>>(emptyList())
    val isLoading = MutableStateFlow(true)

    /**
     * A failed load is an error, not an empty list: the previous version
     * swallowed every exception and rendered an outage as "No invoices".
     */
    val loadError = MutableStateFlow<String?>(null)
    val isPaying = MutableStateFlow(false)

    private val _events = MutableSharedFlow<BillingEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<BillingEvent> = _events

    /** The wrapper's `message` field, which is what the web shows; never the raw JSON. */
    private fun serverMessage(body: String?): String? = body?.let {
        runCatching { org.json.JSONObject(it).optString("message").takeIf { m -> m.isNotBlank() } }.getOrNull()
    }

    val totalOutstanding: Double get() = invoices.value
        .filter { !it.isPaid && !it.isCancelled }
        .sumOf { it.balanceDue }

    init { load() }

    fun load() {
        viewModelScope.launch {
            isLoading.value = true
            loadError.value = null
            try {
                val resp = api.getInvoices(size = 50)
                if (resp.isSuccessful) {
                    invoices.value = resp.body()?.data ?: emptyList()
                } else {
                    loadError.value = "HTTP ${resp.code()}"
                }
            } catch (e: Exception) {
                loadError.value = e.message ?: e.javaClass.simpleName
            } finally {
                isLoading.value = false
            }
        }
    }

    /**
     * Records a payment the patient made through an external channel, the
     * same contract the web portal and the iOS app use. The backend accepts
     * SENT or PARTIALLY_PAID invoices only and rejects anything above the
     * balance, so its message is surfaced rather than replaced.
     */
    fun pay(invoiceId: String, amount: Double, method: String, reference: String?, notes: String?) {
        viewModelScope.launch {
            isPaying.value = true
            try {
                val resp = api.payInvoice(
                    invoiceId,
                    PatientPaymentRequest(
                        amount = amount,
                        paymentMethod = method,
                        transactionReference = reference?.ifBlank { null },
                        notes = notes?.ifBlank { null }
                    )
                )
                if (resp.isSuccessful) {
                    _events.tryEmit(BillingEvent.PaymentRecorded)
                    load()
                } else {
                    _events.tryEmit(BillingEvent.PaymentFailed(serverMessage(resp.errorBody()?.string()) ?: "HTTP ${resp.code()}"))
                }
            } catch (e: Exception) {
                _events.tryEmit(BillingEvent.PaymentFailed(e.message))
            } finally {
                isPaying.value = false
            }
        }
    }
}
