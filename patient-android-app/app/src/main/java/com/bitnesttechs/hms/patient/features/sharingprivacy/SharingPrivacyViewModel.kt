package com.bitnesttechs.hms.patient.features.sharingprivacy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.core.models.AccessLogDto
import com.bitnesttechs.hms.patient.core.models.ConsentDto
import com.bitnesttechs.hms.patient.core.models.GrantConsentRequest
import com.bitnesttechs.hms.patient.core.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SharingPrivacyViewModel @Inject constructor(
    private val api: ApiService
) : ViewModel() {

    private val _consents = MutableStateFlow<List<ConsentDto>>(emptyList())
    val consents: StateFlow<List<ConsentDto>> = _consents

    private val _accessLog = MutableStateFlow<List<AccessLogDto>>(emptyList())
    val accessLog: StateFlow<List<AccessLogDto>> = _accessLog

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    init { load() }

    fun load() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val consentDef = async { api.getConsents() }
                val logDef = async { api.getAccessLog() }
                val consentResp = consentDef.await()
                val logResp = logDef.await()
                if (consentResp.isSuccessful) {
                    _consents.value = consentResp.body()?.data?.content ?: emptyList()
                } else {
                    _error.value = "Unable to load consent records"
                }
                if (logResp.isSuccessful) {
                    _accessLog.value = logResp.body()?.data?.content ?: emptyList()
                } else {
                    _error.value = "Unable to load access log"
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Unable to load sharing records"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun grantConsent(consentId: String, request: GrantConsentRequest) {
        viewModelScope.launch {
            try {
                val resp = api.grantConsent(consentId, request)
                if (resp.isSuccessful) resp.body()?.data?.let { newConsent ->
                    _consents.value = _consents.value.map {
                        if (it.id == newConsent.id) newConsent else it
                    }.let { list ->
                        if (list.none { it.id == newConsent.id }) list + newConsent else list
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun revokeConsent(consent: ConsentDto) {
        viewModelScope.launch {
            try {
                val fromHospitalId = consent.fromHospital?.id ?: return@launch
                val toHospitalId = consent.toHospital?.id ?: return@launch
                val resp = api.revokeConsent(fromHospitalId, toHospitalId)
                if (resp.isSuccessful) {
                    _consents.value = _consents.value.map {
                        if (it.id == consent.id) it.copy(status = "REVOKED", consentGiven = false) else it
                    }
                }
            } catch (_: Exception) {}
        }
    }
}
