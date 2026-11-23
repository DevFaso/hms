import {
  HttpContext,
  HttpContextToken,
  HttpErrorResponse,
  HttpRequest,
} from '@angular/common/http';

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
  let profile = req.context.has(API_TIMEOUT_PROFILE) ? req.context.get(API_TIMEOUT_PROFILE) : null;
  if (profile === null) {
    const upload =
      (typeof FormData !== 'undefined' && req.body instanceof FormData) ||
      (typeof Blob !== 'undefined' && req.body instanceof Blob) ||
      req.reportProgress;
    if (upload) profile = 'upload';
    else if (req.responseType === 'blob' || req.responseType === 'arraybuffer') profile = 'export';
    else profile = 'default';
  }
  const context = new HttpContext();
  for (const token of req.context.keys()) context.set(token, req.context.get(token));
  return req.clone({ context: context.set(API_TIMEOUT_PROFILE, profile) });
}

/** A transport failure says nothing about whether the refresh cookie is still valid. */
export function isRefreshRejection(error: unknown): boolean {
  return error instanceof HttpErrorResponse && [400, 401, 403].includes(error.status);
}

export function isSignInRequest(req: HttpRequest<unknown>): boolean {
  return /(?:^|\/)auth\/(?:login|mfa\/verify)\/?(?:[?#]|$)/i.test(req.url);
}

/** Session protocol POSTs are not uncertain clinical or financial writes. */
export function timeoutNoticeMethod(req: HttpRequest<unknown>): string | null {
  if (/(?:^|\/)auth\/logout\/?(?:[?#]|$)/i.test(req.url)) return null;
  if (
    isSignInRequest(req) ||
    /(?:^|\/)auth\/(?:token\/refresh|verify-password)\/?(?:[?#]|$)/i.test(req.url)
  )
    return 'GET';
  return req.method;
}
