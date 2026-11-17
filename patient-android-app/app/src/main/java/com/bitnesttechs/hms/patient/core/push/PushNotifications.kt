package com.bitnesttechs.hms.patient.core.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.bitnesttechs.hms.patient.BuildConfig
import com.bitnesttechs.hms.patient.MainActivity
import com.bitnesttechs.hms.patient.R
import com.bitnesttechs.hms.patient.core.network.AppText
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Push set-up that runs at process start. Firebase is initialised from the
 * BuildConfig values instead of a google-services.json, and only when all
 * four are present; otherwise nothing Firebase-related runs (the manifest
 * also removes FirebaseInitProvider, so there is no "FirebaseApp
 * initialization unsuccessful" at every start).
 */
object PushSetup {
    const val CHANNEL_MESSAGES = "messages"

    fun init(context: Context, config: PushConfig = PushConfig()) {
        if (!config.enabled) return
        runCatching {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(
                    context,
                    FirebaseOptions.Builder()
                        .setApplicationId(BuildConfig.FCM_APPLICATION_ID)
                        .setApiKey(BuildConfig.FCM_API_KEY)
                        .setProjectId(BuildConfig.FCM_PROJECT_ID)
                        .setGcmSenderId(BuildConfig.FCM_SENDER_ID)
                        .build()
                )
            }
            createChannel(context)
        }.onFailure { quietLog("Push not initialised: ${it.javaClass.simpleName}") }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_MESSAGES,
            AppText.get(R.string.push_channel_messages_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = AppText.get(R.string.push_channel_messages_description) }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /**
     * Shows a received message. The text is composed server side and carries
     * no PHI ("New message"); the fallbacks are the app's own. Tapping opens
     * Messages (and the thread when the sender is known).
     */
    fun showMessageNotification(context: Context, title: String?, body: String?, data: Map<String, String>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { data.forEach { (k, v) -> putExtra(k, v) } }
        val pending = PendingIntent.getActivity(
            context,
            (data[PushTarget.EXTRA_SENDER_ID] ?: "messages").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setColor(ContextCompat.getColor(context, R.color.brand_primary))
            .setContentTitle(title?.takeIf { it.isNotBlank() } ?: AppText.get(R.string.push_new_message_title))
            .setContentText(body?.takeIf { it.isNotBlank() } ?: AppText.get(R.string.push_new_message_body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pending)
            .build()
        runCatching { manager.notify(pending.hashCode(), notification) }
    }
}

/**
 * Where a tapped notification should land. The FCM data is
 * `{"type": "CHAT_MESSAGE", "senderId": "<uuid>"}`; a notification the
 * system drew itself (app in the background) hands the same keys to the
 * launched activity as extras.
 */
data class PushTarget(val senderId: String?) {
    companion object {
        const val EXTRA_TYPE = "type"
        const val EXTRA_SENDER_ID = "senderId"
        const val TYPE_CHAT_MESSAGE = "CHAT_MESSAGE"
        private val UUID_SHAPE = Regex("^[0-9a-fA-F-]{8,64}$")

        /** Null unless these are the extras of a chat-message notification. */
        fun fromExtras(type: String?, senderId: String?): PushTarget? {
            if (type != TYPE_CHAT_MESSAGE) return null
            return PushTarget(senderId?.trim()?.takeIf { UUID_SHAPE.matches(it) })
        }
    }
}

/** The pending notification tap, consumed by the main screen once a session exists. */
object PushNavigation {
    private val _pending = MutableStateFlow<PushTarget?>(null)
    val pending: StateFlow<PushTarget?> = _pending.asStateFlow()

    fun offer(intent: Intent?) {
        intent ?: return
        PushTarget.fromExtras(
            intent.getStringExtra(PushTarget.EXTRA_TYPE),
            intent.getStringExtra(PushTarget.EXTRA_SENDER_ID)
        )?.let { offer(it) }
    }

    fun offer(target: PushTarget) {
        _pending.value = target
    }

    fun consume(): PushTarget? = _pending.value.also { _pending.value = null }

    /**
     * The tap, but only once the NavHost has a graph ([graphReady]: it has a
     * back-stack entry). Navigating earlier throws; the NavHost sits in the
     * Scaffold's content, which is composed after the screen's own effects
     * start. Not ready = left pending for the next call.
     */
    fun consumeWhenReady(graphReady: Boolean): PushTarget? = if (graphReady) consume() else null
}
