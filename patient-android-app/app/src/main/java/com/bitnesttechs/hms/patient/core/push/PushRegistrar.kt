package com.bitnesttechs.hms.patient.core.push

import android.content.Context
import android.util.Log
import com.bitnesttechs.hms.patient.BuildConfig
import com.bitnesttechs.hms.patient.core.auth.TokenStorage
import com.bitnesttechs.hms.patient.core.di.ApplicationScope
import com.bitnesttechs.hms.patient.core.locale.LocaleHelper
import com.bitnesttechs.hms.patient.core.models.PushDeviceRequest
import com.bitnesttechs.hms.patient.core.network.ApiService
import com.google.firebase.messaging.FirebaseMessaging
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Whether this build carries the four Firebase values (see app/build.gradle.kts). */
open class PushConfig @Inject constructor() {
    open val enabled: Boolean
        get() = listOf(
            BuildConfig.FCM_APPLICATION_ID,
            BuildConfig.FCM_API_KEY,
            BuildConfig.FCM_PROJECT_ID,
            BuildConfig.FCM_SENDER_ID
        ).all { it.isNotBlank() }
}

/** The current FCM registration token, or null when there is none to be had. */
interface PushTokenSource {
    suspend fun token(): String?
}

/**
 * A random id generated once per app install and kept in app storage, not
 * tied to the user: `PUT /me/push-devices/{installationId}` re-binds it to
 * whoever is signed in. Also remembers whether the notification permission
 * was already asked for.
 */
interface PushInstallationStore {
    fun installationId(): String
    var permissionAsked: Boolean
}

class FirebasePushTokenSource @Inject constructor(private val config: PushConfig) : PushTokenSource {
    override suspend fun token(): String? {
        if (!config.enabled) return null
        return runCatching { FirebaseMessaging.getInstance().token.await() }
            .onFailure { quietLog("No FCM token: ${it.javaClass.simpleName}") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

}

class SharedPrefsPushInstallationStore @Inject constructor(
    @ApplicationContext context: Context
) : PushInstallationStore {
    private val prefs = context.getSharedPreferences("push_prefs", Context.MODE_PRIVATE)

    @Synchronized
    override fun installationId(): String =
        prefs.getString(KEY_INSTALLATION_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_INSTALLATION_ID, it).apply()
        }

    override var permissionAsked: Boolean
        get() = prefs.getBoolean(KEY_PERMISSION_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_PERMISSION_ASKED, value).apply()

    private companion object {
        const val KEY_INSTALLATION_ID = "installation_id"
        const val KEY_PERMISSION_ASKED = "notification_permission_asked"
    }
}

/** The app language that the backend composes the notification text in. */
open class AppLanguage @Inject constructor(@ApplicationContext private val context: Context) {
    open fun current(): String = LocaleHelper.getLanguage(context)
}

/**
 * Binds this installation's FCM token to the signed-in patient
 * (`PUT /me/push-devices/{installationId}`) and unbinds it on sign-out
 * (`DELETE`, sent with the bearer captured before the session is cleared).
 *
 * Harmless by construction: when the build has no Firebase values nothing
 * happens at all, and every call is fully silent on ANY failure (the backend
 * endpoints may not be deployed yet: a 404 or 405 is expected), makes ONE
 * attempt (per sign-in, per new token, per language change, per cold start)
 * and never blocks a sign-in or a sign-out.
 */
@Singleton
class PushRegistrar @Inject constructor(
    private val api: ApiService,
    private val tokenStorage: TokenStorage,
    private val tokenSource: PushTokenSource,
    private val store: PushInstallationStore,
    private val config: PushConfig,
    private val language: AppLanguage,
    @ApplicationScope private val scope: CoroutineScope
) {
    val enabled: Boolean get() = config.enabled

    /** Fire and forget: after a sign-in, at a cold start, after a language change. */
    fun registerAsync() {
        if (!config.enabled) return
        scope.launch { register() }
    }

    /** The platform handed a new token (FirebaseMessagingService.onNewToken). */
    fun onNewToken(token: String) {
        if (!config.enabled) return
        scope.launch { register(token) }
    }

    /** One attempt; silent whatever happens. */
    suspend fun register(knownToken: String? = null) {
        if (!config.enabled || !tokenStorage.isLoggedIn) return
        runCatching {
            val token = knownToken?.takeIf { it.isNotBlank() } ?: tokenSource.token() ?: return
            val response = api.registerPushDevice(
                store.installationId(),
                PushDeviceRequest(
                    token = token,
                    platform = "ANDROID",
                    locale = LocaleHelper.normalize(language.current()),
                    appVersion = BuildConfig.VERSION_NAME
                )
            )
            if (!response.isSuccessful) quietLog("Push registration not accepted (${response.code()})")
        }.onFailure { quietLog("Push registration skipped: ${it.javaClass.simpleName}") }
    }

    /**
     * Sign-out, BEFORE /auth/logout and with that request's explicit bearer
     * (the interceptor then leaves it alone and never refreshes). Silent.
     */
    suspend fun unregister(bearer: String) {
        if (!config.enabled) return
        runCatching {
            val response = api.unregisterPushDevice(bearer, store.installationId())
            if (!response.isSuccessful) quietLog("Push unregistration not accepted (${response.code()})")
        }.onFailure { quietLog("Push unregistration skipped: ${it.javaClass.simpleName}") }
    }

    /** Ask for POST_NOTIFICATIONS once, the first time it is relevant (Messages). */
    fun shouldAskNotificationPermission(): Boolean = config.enabled && !store.permissionAsked

    fun markNotificationPermissionAsked() {
        store.permissionAsked = true
    }

}

/** Debug-level at most, and never a reason to fail: push must stay silent. */
internal fun quietLog(message: String) {
    runCatching { Log.d("Push", message) }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PushModule {
    @Binds
    abstract fun tokenSource(impl: FirebasePushTokenSource): PushTokenSource

    @Binds
    abstract fun installationStore(impl: SharedPrefsPushInstallationStore): PushInstallationStore
}
