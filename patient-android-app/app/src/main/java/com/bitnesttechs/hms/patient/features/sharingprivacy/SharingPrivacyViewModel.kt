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

    init { load() }

    fun load() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val consentDef = async { api.getConsents() }
                val logDef = async { api.getAccessLog() }
                consentDef.await().body()?.data?.let { _consents.value = it }
                logDef.await().body()?.data?.content?.let { _accessLog.value = it }
            } catch (_: Exception) {
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun grantConsent(request: GrantConsentRequest) {
        viewModelScope.launch {
            try {
                val resp = api.grantConsent(request)
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

    fun revokeConsent(consentId: Long) {
        viewModelScope.launch {
            try {
                val resp = api.revokeConsent(consentId)
                if (resp.isSuccessful) {
                    _consents.value = _consents.value.map {
                        if (it.id == consentId) it.copy(status = "REVOKED") else it
                    }
                }
            } catch (_: Exception) {}
        }
    }
}
