/**
 * Secure Storage — async key-value store
 *
 * Native (iOS/Android): uses @capacitor/preferences (backed by
 *   SharedPreferences on Android, UserDefaults on iOS — encrypted at rest
 *   by the OS full-disk encryption).
 * Web: falls back to localStorage (synchronous but wrapped in async API
 *   so callers don't need to branch).
 *
 * All public methods are async so the rest of the codebase stays
 * storage-engine agnostic.
 */
import { Preferences } from '@capacitor/preferences'
import { isNative } from './platform'

// ── Public API ──────────────────────────────────────────────────

/**
 * Store a value.
 * @param {string} key
 * @param {string} value
 */
export async function setItem(key, value) {
  if (isNative()) {
    await Preferences.set({ key, value })
  } else {
    localStorage.setItem(key, value)
  }
}

/**
 * Retrieve a value (returns null when missing).
 * @param {string} key
 * @returns {Promise<string | null>}
 */
export async function getItem(key) {
  if (isNative()) {
    const { value } = await Preferences.get({ key })
    return value // null when not found
  }
  return localStorage.getItem(key)
}

/**
 * Remove a value.
 * @param {string} key
 */
export async function removeItem(key) {
  if (isNative()) {
    await Preferences.remove({ key })
  } else {
    localStorage.removeItem(key)
  }
}

/**
 * Clear ALL stored values.
 */
export async function clear() {
  if (isNative()) {
    await Preferences.clear()
  } else {
    localStorage.clear()
  }
}

const secureStorage = { getItem, setItem, removeItem, clear }
export default secureStorage
