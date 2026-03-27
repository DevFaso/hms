package com.bitnesttechs.hms.patient.features.profile

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.core.auth.AuthRepository
import com.bitnesttechs.hms.patient.core.models.PatientProfileDto
import com.bitnesttechs.hms.patient.core.models.PatientProfileUpdateDto
import com.bitnesttechs.hms.patient.core.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val api: ApiService,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _profile = MutableStateFlow<PatientProfileDto?>(null)
    val profile: StateFlow<PatientProfileDto?> = _profile

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _loggedOut = MutableStateFlow(false)
    val loggedOut: StateFlow<Boolean> = _loggedOut

    private val _saveResult = MutableStateFlow<String?>(null)
    val saveResult: StateFlow<String?> = _saveResult

    private val _profileImageUrl = MutableStateFlow<String?>(null)
    val profileImageUrl: StateFlow<String?> = _profileImageUrl

    init { load() }

    fun load() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val resp = api.getProfile()
                if (resp.isSuccessful) {
                    val p = resp.body()?.data
                    _profile.value = p
                    _profileImageUrl.value = p?.profileImageUrl
                }
            } catch (_: Exception) {
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateProfile(update: PatientProfileUpdateDto) {
        viewModelScope.launch {
            try {
                val resp = api.updateProfile(update)
                if (resp.isSuccessful) {
                    _profile.value = resp.body()?.data
                    _saveResult.value = "Profile updated successfully"
                } else {
                    _saveResult.value = "Update failed: ${resp.code()}"
                }
            } catch (e: Exception) {
                _saveResult.value = "Error: ${e.message}"
            }
        }
    }

    fun uploadPhoto(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                val inputStream = context.contentResolver.openInputStream(uri) ?: return@launch
                val bytes = inputStream.readBytes()
                inputStream.close()

                val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                val requestBody = bytes.toRequestBody(mimeType.toMediaTypeOrNull())
                val fileName = "profile_photo.${if (mimeType.contains("png")) "png" else "jpg"}"
                val filePart = MultipartBody.Part.createFormData("file", fileName, requestBody)

                val resp = api.uploadProfileImage(filePart)
                if (resp.isSuccessful) {
                    val imageUrl = resp.body()?.imageUrl
                    _profileImageUrl.value = imageUrl
                    _saveResult.value = "Profile photo updated"
                } else {
                    _saveResult.value = "Photo upload failed: ${resp.code()}"
                }
            } catch (e: Exception) {
                _saveResult.value = "Error: ${e.message}"
            }
        }
    }

    fun clearSaveResult() { _saveResult.value = null }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            _loggedOut.value = true
        }
    }
}
