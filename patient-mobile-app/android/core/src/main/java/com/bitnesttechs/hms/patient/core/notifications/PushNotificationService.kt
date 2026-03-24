package com.bitnesttechs.hms.patient.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * FCM push notification service.
 *
 * NOTE: Firebase dependencies must be added to app/build.gradle.kts:
 *   implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
 *   implementation("com.google.firebase:firebase-messaging-ktx")
 *
 * Also requires:
 * - google-services.json in the app/ directory
 * - id("com.google.gms.google-services") plugin in app/build.gradle.kts
 *
 * Once Firebase is configured:
 * 1. Uncomment @AndroidEntryPoint and extend FirebaseMessagingService
 * 2. Uncomment onNewToken and onMessageReceived
 * 3. Register this service in AndroidManifest.xml under the <application> tag:
 *      <service android:name=".core.notifications.PushNotificationService"
 *               android:exported="false">
 *        <intent-filter>
 *          <action android:name="com.google.firebase.MESSAGING_EVENT" />
 *        </intent-filter>
 *      </service>
 */
// TODO: Uncomment once Firebase is configured
// @AndroidEntryPoint
class PushNotificationService /* : FirebaseMessagingService() */ {

    companion object {
        private const val TAG = "PushNotificationService"
        const val CHANNEL_ID = "hms_notifications"
        const val CHANNEL_NAME = "HMS Notifications"

        fun createNotificationChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Health notifications from HMS"
                enableVibration(true)
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    // TODO: Uncomment once Firebase is configured
    /*
    @Inject
    lateinit var notificationApi: com.bitnesttechs.hms.patient.notifications.data.NotificationApi

    override fun onNewToken(token: String) {
        Log.d(TAG, "FCM new token received")
        kotlinx.coroutines.GlobalScope.launch {
            runCatching {
                notificationApi.registerDeviceToken(
                    com.bitnesttechs.hms.patient.notifications.data.DeviceTokenRequest(
                        token = token,
                        platform = "android"
                    )
                )
            }.onFailure { Log.w(TAG, "Failed to register FCM token", it) }
        }
    }

    override fun onMessageReceived(message: com.google.firebase.messaging.RemoteMessage) {
        Log.d(TAG, "FCM message: from=${message.from}")
        // Notification is shown automatically if app is in background.
        // Handle foreground notification here if needed.
    }
    */
}
