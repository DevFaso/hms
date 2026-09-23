package com.bitnesttechs.hms.patient.features.documents

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one document upload or delete that may be in flight, process-wide.
 *
 * The request runs in the application scope so leaving the screen cannot
 * abandon it, but a screen reopened mid-request gets a fresh ViewModel
 * whose first list read could return the pre-commit state (the new document
 * missing, the deleted row still there) with no refresh gesture. That
 * ViewModel waits on this job before reading, so the list always ends in
 * the server's true state.
 */
@Singleton
class DocumentRequestTracker @Inject constructor() {
    private val _inFlight = MutableStateFlow<Job?>(null)
    val inFlight: StateFlow<Job?> = _inFlight.asStateFlow()

    /** Records [job] until it completes; returns false without recording when another request is still out. */
    fun start(job: Job): Boolean {
        if (!_inFlight.compareAndSet(null, job)) return false
        job.invokeOnCompletion { _inFlight.compareAndSet(job, null) }
        return true
    }
}
