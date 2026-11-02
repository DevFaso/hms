import { HttpErrorResponse } from '@angular/common/http';

/**
 * QA D1184 (round 22): a stored file the disk no longer holds answers 404
 * with a localized reason ("Le fichier stocké n'est plus disponible."), but a
 * `responseType: 'blob'` request receives that JSON body as a Blob, so every
 * download screen showed only its generic "could not be downloaded" toast.
 *
 * Resolves to the server's own message of a 4xx refusal sent to a blob
 * request, or `null` (not a 4xx, not a blob, not JSON, no message), so the
 * caller falls back to its translated text. Never rejects.
 */
export async function blobRefusalReason(err: unknown): Promise<string | null> {
  if (!(err instanceof HttpErrorResponse) || err.status < 400 || err.status >= 500) return null;
  const body: unknown = err.error;
  let text: string | null = null;
  if (body instanceof Blob) {
    try {
      text = await body.text();
    } catch {
      return null;
    }
  } else if (typeof body === 'string') {
    text = body;
  } else if (body && typeof body === 'object') {
    const message = (body as { message?: unknown }).message;
    return typeof message === 'string' && message.trim() ? message : null;
  }
  if (!text) return null;
  try {
    const message = (JSON.parse(text) as { message?: unknown }).message;
    return typeof message === 'string' && message.trim() ? message : null;
  } catch {
    return null;
  }
}
