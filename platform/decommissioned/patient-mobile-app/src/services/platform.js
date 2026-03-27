/**
 * Platform detection helpers
 *
 * Uses Capacitor to detect native vs web environment.
 * All helpers are synchronous — safe to call anywhere.
 */
import { Capacitor } from '@capacitor/core'

/** true when running inside iOS / Android native shell */
export const isNative = () => Capacitor.isNativePlatform()

/** true when running as a plain web app (browser / PWA) */
export const isWeb = () => !Capacitor.isNativePlatform()

/** true when running inside iOS native shell */
export const isIOS = () => Capacitor.getPlatform() === 'ios'

/** true when running inside Android native shell */
export const isAndroid = () => Capacitor.getPlatform() === 'android'

/** Returns 'ios' | 'android' | 'web' */
export const getPlatform = () => Capacitor.getPlatform()

/**
 * Check whether a specific Capacitor plugin is available on the
 * current platform (i.e. the native bridge is registered).
 * @param {string} pluginName — e.g. 'PushNotifications', 'Camera'
 */
export const isPluginAvailable = (pluginName) =>
  Capacitor.isPluginAvailable(pluginName)
