package com.bitnesttechs.hms.patient.features.pharmacyinvoices

import android.content.Context
import com.bitnesttechs.hms.patient.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.core.models.PharmacyClaimDto
import com.bitnesttechs.hms.patient.core.models.PharmacyPaymentDto
import com.bitnesttechs.hms.patient.core.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PharmacyInvoicesViewModel @Inject constructor(
    private val api: ApiService,
    @ApplicationContext private val context: Context
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

            val failures = mutableListOf<String>()
            try {
                val paymentsResp = api.getPharmacyPayments()
                if (paymentsResp.isSuccessful) {
                    _payments.value = paymentsResp.body()?.data?.content ?: emptyList()
                } else {
                    _payments.value = emptyList()
                    failures += context.getString(R.string.pharmacy_payments_load_failed, "HTTP ${paymentsResp.code()}")
                }
            } catch (ex: Exception) {
                _payments.value = emptyList()
                failures += context.getString(R.string.pharmacy_payments_load_failed, ex.localizedMessage ?: context.getString(R.string.error_generic))
            }

            try {
                val claimsResp = api.getPharmacyClaims()
                if (claimsResp.isSuccessful) {
                    _claims.value = claimsResp.body()?.data?.content ?: emptyList()
                } else {
                    _claims.value = emptyList()
                    failures += context.getString(R.string.pharmacy_claims_load_failed, "HTTP ${claimsResp.code()}")
                }
            } catch (ex: Exception) {
                _claims.value = emptyList()
                failures += context.getString(R.string.pharmacy_claims_load_failed, ex.localizedMessage ?: context.getString(R.string.error_generic))
            } finally {
                _error.value = failures.takeIf { it.isNotEmpty() }?.joinToString("\n")
                _isLoading.value = false
            }
        }
    }
}
