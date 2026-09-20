package com.bitnesttechs.hms.patient.core.di

import javax.inject.Qualifier

/**
 * A [kotlinx.coroutines.CoroutineScope] tied to the application, not to a
 * screen.
 *
 * Writes the user has already confirmed must not be cancelled because the UI
 * that started them went away. `viewModelScope` is the wrong home for them:
 * navigating back clears the owning entry's ViewModelStore, which cancels any
 * request still in flight — the user sees the screen close and assumes the
 * action succeeded.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
