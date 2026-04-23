package com.bitnesttechs.hms.patient.core.config

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.bitnesttechs.hms.patient.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.featureFlagDataStore by preferencesDataStore(name = "medihub_feature_flags")

/**
 * Runtime feature flag store (KC-3).
 *
 * Flags are additive and default OFF until the underlying feature is ready
 * (see `docs/tasks-keycloak.md`, prerequisite P-2). The compile-time default
 * is read from `BuildConfig.KEYCLOAK_SSO_ENABLED_DEFAULT` and can be
 * overridden at runtime (for QA / debug builds) via [setKeycloakSsoEnabled].
 */
@Singleton
class FeatureFlagManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.featureFlagDataStore

    val keycloakSsoEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_KEYCLOAK_SSO] ?: BuildConfig.KEYCLOAK_SSO_ENABLED_DEFAULT
    }

    suspend fun isKeycloakSsoEnabled(): Boolean = keycloakSsoEnabled.first()

    suspend fun setKeycloakSsoEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_KEYCLOAK_SSO] = enabled }
    }

    companion object {
        private val KEY_KEYCLOAK_SSO: Preferences.Key<Boolean> =
            booleanPreferencesKey("keycloak_sso_enabled")
    }
}
