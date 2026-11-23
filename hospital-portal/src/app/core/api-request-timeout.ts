import { HttpContextToken, HttpRequest } from '@angular/common/http';

export const API_REQUEST_TIMEOUT_MS = 20_000;
export const API_UPLOAD_TIMEOUT_MS = 120_000;
export const API_EXPORT_TIMEOUT_MS = 180_000;
export type ApiTimeoutProfile = 'default' | 'upload' | 'export';

/** Callers can select a bounded budget independently of progress reporting or response encoding. */
export const API_TIMEOUT_PROFILE = new HttpContextToken<ApiTimeoutProfile | null>(() => null);

export const API_TIMEOUT_BUDGETS: Record<ApiTimeoutProfile, number> = {
  default: API_REQUEST_TIMEOUT_MS,
  upload: API_UPLOAD_TIMEOUT_MS,
  export: API_EXPORT_TIMEOUT_MS,
};

/** All multipart/raw file uploads and binary downloads inherit their transfer budget. */
export function withApiTimeoutProfile(req: HttpRequest<unknown>): HttpRequest<unknown> {
  let profile = req.context.get(API_TIMEOUT_PROFILE);
  if (profile === null) {
    const upload =
      (typeof FormData !== 'undefined' && req.body instanceof FormData) ||
      (typeof Blob !== 'undefined' && req.body instanceof Blob) ||
      req.reportProgress;
    if (upload) profile = 'upload';
    else if (req.responseType === 'blob') profile = 'export';
    else profile = 'default';
  }
  return req.clone({ context: req.context.set(API_TIMEOUT_PROFILE, profile) });
}
