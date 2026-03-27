/**
 * Biometric Authentication service
 *
 * Native: uses capacitor-native-biometric (Face ID / Touch ID / Fingerprint).
 * Web: always reports biometrics as unavailable — callers should
 *      fall back to username/password.
 *
 * Usage:
 *   const available = await biometricAuth.isAvailable()
 *   if (available) await biometricAuth.authenticate('Confirm your identity')
 *
 * Credential storage:
 *   After a successful password login, store credentials so biometric
 *   re-auth can replay them without asking the user to type again:
 *     biometricAuth.storeCredentials(username, password)
 *   On next launch:
 *     const creds = await biometricAuth.getCredentials()
 */
import { isNative, isPluginAvailable } from './platform'

// Lazy-import so we don't blow up on web where the native bridge is absent
let NativeBiometric = null
async function loadPlugin() {
  if (!NativeBiometric) {
    const mod = await import('capacitor-native-biometric')
    NativeBiometric = mod.NativeBiometric
  }
  return NativeBiometric
}

const SERVER_ID = 'com.bitnesttechs.hms.patient'

const biometricAuth = {
  /**
   * Check whether the device supports biometric auth.
   * @returns {Promise<{ available: boolean, biometryType?: string }>}
   */
  async isAvailable() {
    if (!isNative() || !isPluginAvailable('NativeBiometric')) {
      return { available: false }
    }
    try {
      const plugin = await loadPlugin()
      const result = await plugin.isAvailable()
      return {
        available: result.isAvailable,
        biometryType: biometryTypeLabel(result.biometryType),
      }
    } catch {
      return { available: false }
    }
  },

  /**
   * Prompt the user with biometric challenge.
   * Rejects if the user cancels or the check fails.
   * @param {string} [reason] — shown on the native prompt
   */
  async authenticate(reason = 'Please authenticate') {
    const plugin = await loadPlugin()
    await plugin.verifyIdentity({
      reason,
      title: 'Authentication Required',
      subtitle: reason,
      useFallback: true, // allow device passcode fallback
      maxAttempts: 3,
    })
  },

  /**
   * Store login credentials in the OS keychain / keystore,
   * protected by biometric gate.
   * @param {string} username
   * @param {string} password
   */
  async storeCredentials(username, password) {
    if (!isNative()) return
    const plugin = await loadPlugin()
    await plugin.setCredentials({
      username,
      password,
      server: SERVER_ID,
    })
  },

  /**
   * Retrieve previously stored credentials.
   * @returns {Promise<{ username: string, password: string } | null>}
   */
  async getCredentials() {
    if (!isNative()) return null
    try {
      const plugin = await loadPlugin()
      const creds = await plugin.getCredentials({ server: SERVER_ID })
      return creds // { username, password }
    } catch {
      return null
    }
  },

  /**
   * Remove stored credentials.
   */
  async deleteCredentials() {
    if (!isNative()) return
    try {
      const plugin = await loadPlugin()
      await plugin.deleteCredentials({ server: SERVER_ID })
    } catch {
      // ignore — may not exist
    }
  },
}

/** Map numeric biometryType to readable label */
function biometryTypeLabel(type) {
  switch (type) {
    case 1:
      return 'Touch ID'
    case 2:
      return 'Face ID'
    case 3:
      return 'Fingerprint'
    case 4:
      return 'Face Authentication'
    case 5:
      return 'Iris Authentication'
    default:
      return 'Biometric'
  }
}

export default biometricAuth
