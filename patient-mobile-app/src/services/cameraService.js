/**
 * Camera service
 *
 * Native (iOS/Android): uses @capacitor/camera for photos and gallery access.
 * Web: falls back to a standard <input type="file"> picker via the
 *      Capacitor web implementation.
 *
 * Usage:
 *   const photo = await cameraService.takePicture()
 *   const photo = await cameraService.pickFromGallery()
 *   // photo.dataUrl  → base64 data URI (ready for <img src>)
 *   // photo.blob     → Blob (ready for FormData upload)
 */
import { isPluginAvailable } from './platform'

let Camera = null
let CameraResultType = null
let CameraSource = null

async function loadPlugin() {
  if (!Camera) {
    const mod = await import('@capacitor/camera')
    Camera = mod.Camera
    CameraResultType = mod.CameraResultType
    CameraSource = mod.CameraSource
  }
  return { Camera, CameraResultType, CameraSource }
}

const cameraService = {
  /**
   * Open the device camera and take a photo.
   * @param {object} [opts]
   * @param {number} [opts.quality=90] — JPEG quality 0-100
   * @param {number} [opts.width]  — max width in px
   * @param {number} [opts.height] — max height in px
   * @returns {Promise<{ dataUrl: string, blob: Blob, format: string }>}
   */
  async takePicture(opts = {}) {
    return capturePhoto({ source: 'CAMERA', ...opts })
  },

  /**
   * Open the device photo gallery / file picker.
   * @param {object} [opts] — same options as takePicture
   * @returns {Promise<{ dataUrl: string, blob: Blob, format: string }>}
   */
  async pickFromGallery(opts = {}) {
    return capturePhoto({ source: 'PHOTOS', ...opts })
  },

  /**
   * Let the user choose camera or gallery via the OS action sheet.
   * @param {object} [opts] — same options as takePicture
   * @returns {Promise<{ dataUrl: string, blob: Blob, format: string }>}
   */
  async pickOrCapture(opts = {}) {
    return capturePhoto({ source: 'PROMPT', ...opts })
  },

  /**
   * Check if camera plugin is available on the current platform.
   */
  isAvailable() {
    return isPluginAvailable('Camera')
  },
}

// ── internal ────────────────────────────────────────────────────

async function capturePhoto({ source = 'PROMPT', quality = 90, width, height } = {}) {
  const { Camera: cam, CameraResultType: resultType, CameraSource: camSource } =
    await loadPlugin()

  const sourceMap = {
    CAMERA: camSource.Camera,
    PHOTOS: camSource.Photos,
    PROMPT: camSource.Prompt,
  }

  const image = await cam.getPhoto({
    quality,
    allowEditing: false,
    resultType: resultType.DataUrl,
    source: sourceMap[source] ?? camSource.Prompt,
    ...(width && { width }),
    ...(height && { height }),
  })

  // Convert data URL to Blob for easy upload
  const blob = dataUrlToBlob(image.dataUrl)

  return {
    dataUrl: image.dataUrl,
    blob,
    format: image.format, // 'jpeg' | 'png' | etc.
  }
}

/**
 * Convert a data-URL string to a Blob.
 * @param {string} dataUrl
 * @returns {Blob}
 */
function dataUrlToBlob(dataUrl) {
  const [header, base64] = dataUrl.split(',')
  const mime = header.match(/:(.*?);/)?.[1] || 'image/jpeg'
  const bytes = atob(base64)
  const buffer = new Uint8Array(bytes.length)
  for (let i = 0; i < bytes.length; i++) {
    buffer[i] = bytes.charCodeAt(i)
  }
  return new Blob([buffer], { type: mime })
}

export default cameraService
