package com.bitnesttechs.hms.patient.core.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * FCM entry point. Only ever called when the build carries the Firebase
 * values (otherwise no FirebaseApp exists and no token is ever issued).
 */
@AndroidEntryPoint
class EKeneyaMessagingService : FirebaseMessagingService() {

    @Inject lateinit var registrar: PushRegistrar

    /** A new token: bind it to the signed-in patient (nothing when signed out). */
    override fun onNewToken(token: String) {
        registrar.onNewToken(token)
    }

    /**
     * A message while the app is in the foreground (in the background the
     * system draws the notification payload itself). Only chat messages are
     * sent today.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data[PushTarget.EXTRA_TYPE] != PushTarget.TYPE_CHAT_MESSAGE) return
        PushSetup.showMessageNotification(
            context = this,
            title = message.notification?.title ?: data["title"],
            body = message.notification?.body ?: data["body"],
            data = data.filterKeys { it == PushTarget.EXTRA_TYPE || it == PushTarget.EXTRA_SENDER_ID }
        )
    }
}
