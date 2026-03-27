package com.bitnesttechs.hms.patient.features.healthrecords

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.core.models.ImmunizationDto
import com.bitnesttechs.hms.patient.core.models.ReferralDto
import com.bitnesttechs.hms.patient.core.models.TreatmentPlanDto
import com.bitnesttechs.hms.patient.core.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HealthRecordsViewModel @Inject constructor(
    private val api: ApiService
) : ViewModel() {

    private val _immunizations = MutableStateFlow<List<ImmunizationDto>>(emptyList())
    val immunizations: StateFlow<List<ImmunizationDto>> = _immunizations

    private val _treatmentPlans = MutableStateFlow<List<TreatmentPlanDto>>(emptyList())
    val treatmentPlans: StateFlow<List<TreatmentPlanDto>> = _treatmentPlans

    private val _referrals = MutableStateFlow<List<ReferralDto>>(emptyList())
    val referrals: StateFlow<List<ReferralDto>> = _referrals

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    init { load() }

    fun load() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val immDef = async { api.getImmunizations() }
                val tpDef = async { api.getTreatmentPlans() }
                val refDef = async { api.getReferrals() }
                immDef.await().body()?.data?.let { _immunizations.value = it }
                tpDef.await().body()?.data?.let { _treatmentPlans.value = it }
                refDef.await().body()?.data?.let { _referrals.value = it }
            } catch (_: Exception) {
            } finally {
                _isLoading.value = false
            }
        }
    }
}
