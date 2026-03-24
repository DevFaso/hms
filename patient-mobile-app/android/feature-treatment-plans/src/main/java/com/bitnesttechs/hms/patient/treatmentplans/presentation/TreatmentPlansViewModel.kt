package com.bitnesttechs.hms.patient.treatmentplans.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.treatmentplans.data.TreatmentPlanDto
import com.bitnesttechs.hms.patient.treatmentplans.data.TreatmentPlansRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TreatmentPlansViewModel @Inject constructor(
    private val repository: TreatmentPlansRepository
) : ViewModel() {

    private val _plans = MutableStateFlow<List<TreatmentPlanDto>>(emptyList())
    val plans: StateFlow<List<TreatmentPlanDto>> = _plans

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore

    private var currentPage = 0
    private val pageSize = 20

    init {
        loadPlans(reset = true)
    }

    fun loadPlans(reset: Boolean = false) {
        if (reset) {
            currentPage = 0
            _hasMore.value = true
            _plans.value = emptyList()
        }
        if (!_hasMore.value || _isLoading.value) return

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val response = repository.getTreatmentPlans(currentPage, pageSize)
                val page = response.data
                val newList = if (reset) page.content else _plans.value + page.content
                _plans.value = newList
                _hasMore.value = currentPage < page.totalPages - 1
                currentPage++
            } catch (e: Exception) {
                _error.value = e.message
            }
            _isLoading.value = false
        }
    }

    fun refresh() = loadPlans(reset = true)
}
