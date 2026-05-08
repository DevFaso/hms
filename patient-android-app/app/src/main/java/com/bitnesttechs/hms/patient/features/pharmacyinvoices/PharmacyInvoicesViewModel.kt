package com.bitnesttechs.hms.patient.features.pharmacyinvoices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.core.models.PharmacyClaimDto
import com.bitnesttechs.hms.patient.core.models.PharmacyPaymentDto
import com.bitnesttechs.hms.patient.core.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PharmacyInvoicesViewModel @Inject constructor(
    private val api: ApiService
) : ViewModel() {

    private val _payments = MutableStateFlow<List<PharmacyPaymentDto>>(emptyList())
    val payments: StateFlow<List<PharmacyPaymentDto>> = _payments.asStateFlow()

    private val _claims = MutableStateFlow<List<PharmacyClaimDto>>(emptyList())
    val claims: StateFlow<List<PharmacyClaimDto>> = _claims.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val totalPaid: Double get() = _payments.value.sumOf { it.amount }
    val totalClaimed: Double get() = _claims.value.sumOf { it.amount }

    init { load() }

    fun load() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val paymentsResp = api.getPharmacyPayments()
                if (paymentsResp.isSuccessful) {
                    _payments.value = paymentsResp.body()?.data?.content ?: emptyList()
                }

                val claimsResp = api.getPharmacyClaims()
                if (claimsResp.isSuccessful) {
                    _claims.value = claimsResp.body()?.data?.content ?: emptyList()
                }
            } catch (ex: Exception) {
                _error.value = ex.localizedMessage
            } finally {
                _isLoading.value = false
            }
        }
    }
}
