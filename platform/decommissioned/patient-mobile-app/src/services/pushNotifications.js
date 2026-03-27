/**
 * Push Notifications service
 *
 * Native (iOS/Android): uses @capacitor/push-notifications to register
 *   for APNs / FCM and forward the device token to the backend.
 * Web: no-op — the app continues to poll /notifications via REST.
 *
 * Lifecycle:
 *   1. Call `pushNotifications.register()` once at app startup.
 *   2. The service requests permissions, receives the device token,
 *      and POSTs it to the backend.
 *   3. Incoming push payloads are forwarded to any registered handler.
 */
import { isNative, isPluginAvailable, getPlatform } from './platform'
import api from './api'

let PushNotifications = null
let registered = false

async function loadPlugin() {
  if (!PushNotifications) {
    const mod = await import('@capacitor/push-notifications')
    PushNotifications = mod.PushNotifications
  }
  return PushNotifications
}

const pushNotificationService = {
  /**
   * Request permission and register for push notifications.
   * Safe to call on web — returns immediately.
   */
  async register() {
    if (!isNative() || !isPluginAvailable('PushNotifications') || registered) {
      return
    }

    const plugin = await loadPlugin()

    // Request permission
    const permStatus = await plugin.requestPermissions()
    if (permStatus.receive !== 'granted') {
      console.warn('Push notification permission denied')
      return
    }

    // Register with APNs / FCM
    await plugin.register()
    registered = true

    // ── Listeners ───────────────────────────────────────────────

    // Successfully registered — send token to backend
    plugin.addListener('registration', async ({ value: token }) => {
      console.log('Push token:', token)
      try {
        await api.post('/devices/push-token', {
          token,
          platform: getPlatform(),
        })
      } catch (err) {
        console.error('Failed to send push token to backend:', err)
      }
    })

    // Registration error
    plugin.addListener('registrationError', (error) => {
      console.error('Push registration error:', error)
    })

    // Notification received while app is in foreground
    plugin.addListener('pushNotificationReceived', (notification) => {
      console.log('Push received (foreground):', notification)
      // Dispatch to any registered handler
      if (pushNotificationService._onNotification) {
        pushNotificationService._onNotification(notification)
      }
    })

    // User tapped on a notification (app was in background / killed)
    plugin.addListener('pushNotificationActionPerformed', (action) => {
      console.log('Push action performed:', action)
      if (pushNotificationService._onAction) {
        pushNotificationService._onAction(action)
      }
    })
  },

  /**
   * Set a handler for foreground notifications.
   * @param {(notification: any) => void} handler
   */
  onNotification(handler) {
    pushNotificationService._onNotification = handler
  },

  /**
   * Set a handler for notification tap actions.
   * @param {(action: any) => void} handler
   */
  onAction(handler) {
    pushNotificationService._onAction = handler
  },

  /**
   * Remove all delivered notifications from the notification tray.
   */
  async clearDelivered() {
    if (!isNative() || !isPluginAvailable('PushNotifications')) return
    const plugin = await loadPlugin()
    await plugin.removeAllDeliveredNotifications()
  },

  // Internal handler references
  _onNotification: null,
  _onAction: null,
}

export default pushNotificationService
