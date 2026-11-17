package com.bitnesttechs.hms.patient.features.familyaccess

import com.bitnesttechs.hms.patient.core.network.FailureText
import com.bitnesttechs.hms.patient.core.network.AppText
import com.bitnesttechs.hms.patient.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.core.models.GrantProxyRequest
import com.bitnesttechs.hms.patient.core.models.ProxyResponse
import com.bitnesttechs.hms.patient.core.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FamilyAccessUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val grantedByMe: List<ProxyResponse> = emptyList(),
    val accessIHave: List<ProxyResponse> = emptyList(),
    val actionResult: String? = null
)

@HiltViewModel
class FamilyAccessViewModel @Inject constructor(
    private val api: ApiService
) : ViewModel() {

    private val _uiState = MutableStateFlow(FamilyAccessUiState())
    val uiState: StateFlow<FamilyAccessUiState> = _uiState.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val grantedResp = api.getProxiesGrantedByMe()
                val accessResp = api.getProxyAccessIHave()
                _uiState.value = FamilyAccessUiState(
                    isLoading = false,
                    grantedByMe = grantedResp.body()?.data ?: emptyList(),
                    accessIHave = accessResp.body()?.data ?: emptyList()
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = AppText.get(R.string.proxy_load_failed, FailureText.of(e)))
            }
        }
    }

    fun grantProxy(request: GrantProxyRequest) {
        viewModelScope.launch {
            try {
                val resp = api.grantProxy(request)
                if (resp.isSuccessful) {
                    _uiState.value = _uiState.value.copy(actionResult = AppText.get(R.string.proxy_granted_ok))
                    load()
                } else {
                    _uiState.value = _uiState.value.copy(actionResult = AppText.get(R.string.proxy_grant_failed, FailureText.http(resp.code())))
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(actionResult = AppText.get(R.string.proxy_grant_failed, FailureText.of(e)))
            }
        }
    }

    fun revokeProxy(proxyId: String) {
        viewModelScope.launch {
            try {
                val resp = api.revokeProxy(proxyId)
                if (resp.isSuccessful) {
                    _uiState.value = _uiState.value.copy(actionResult = AppText.get(R.string.proxy_revoked_ok))
                    load()
                } else {
                    _uiState.value = _uiState.value.copy(actionResult = AppText.get(R.string.proxy_revoke_failed, FailureText.http(resp.code())))
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(actionResult = AppText.get(R.string.proxy_revoke_failed, FailureText.of(e)))
            }
        }
    }

    fun clearActionResult() {
        _uiState.value = _uiState.value.copy(actionResult = null)
    }
}
