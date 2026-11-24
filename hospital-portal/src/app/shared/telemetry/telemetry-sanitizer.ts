/**
 * Scrubs every front-end telemetry item (Grafana Faro: traces, events, logs, exceptions,
 * measurements and their meta) before it leaves the browser.
 *
 * - Every URL loses its query string and fragment, and each path segment that identifies
 *   something (a UUID, an e-mail address, anything carrying a digit) becomes a placeholder:
 *   `/api/patients/3f2c…/vitals?from=…` is exported as `/api/patients/:uuid/vitals`.
 * - Every other string has the URLs inside it scrubbed the same way, and any UUID or e-mail
 *   address it carries replaced.
 * - Request and response headers and bodies are never exported: any field or span attribute
 *   named like one is dropped.
 *
 * Wired as Faro's `beforeSend` in `main.ts`. Pure and dependency-free, so it is unit-tested
 * without Faro.
 */

const UUID = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi;
const EMAIL = /[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/gi;
const ABSOLUTE_URL = /\b[a-z][a-z0-9+.-]*:\/\/[^\s"'<>()]+/gi;
/** A path followed by a query or fragment inside free text (`… for /api/x?name=… : 400`). */
const PATH_WITH_QUERY = /(\/[^\s"'<>()?#]*)[?#][^\s"'<>()]*/g;
/** A header or a body, as a field name or a span attribute key. */
const HEADER_OR_BODY =
  /(^|[._-])(headers?|body|request_?body|response_?body|cookies?|authorization)($|[._-])/i;
/** A static asset keeps its file name: it identifies a build, not a person. */
const STATIC_ASSET = /\.(js|mjs|css|map|woff2?|ttf|otf|png|jpe?g|gif|svg|ico|webp|json|txt|html)$/i;
/** `v1`, `R4`: an API version, not an id. */
const VERSION_SEGMENT = /^[vr]\d{1,2}$/i;

function placeholderFor(segment: string): string {
  let decoded = segment;
  try {
    decoded = decodeURIComponent(segment);
  } catch {
    // A malformed escape is judged as written.
  }
  if (new RegExp(`^${UUID.source}$`, 'i').test(decoded)) return ':uuid';
  if (decoded.includes('@')) return ':email';
  if (STATIC_ASSET.test(decoded) || VERSION_SEGMENT.test(decoded)) return segment;
  if (/\d/.test(decoded)) return ':id';
  return segment;
}

/** A URL, absolute or relative, without its query or fragment and with its ids replaced. */
export function sanitizeUrl(raw: string): string {
  const match = /^([a-z][a-z0-9+.-]*:\/\/[^/?#]*)?([^?#]*)/i.exec(raw);
  const origin = match?.[1] ?? '';
  const path = match?.[2] ?? '';
  const scrubbed = path
    .split('/')
    .map((segment) => (segment === '' ? segment : placeholderFor(segment)))
    .join('/');
  return origin + scrubbed;
}

function looksLikeUrl(value: string): boolean {
  return /^[a-z][a-z0-9+.-]*:\/\//i.test(value) || value.startsWith('/');
}

/** The sentence punctuation a URL in free text runs into (`…?name=Awa: 400`), kept as text. */
function trailingPunctuation(url: string): string {
  return /[.,;:!]+$/.exec(url)?.[0] ?? '';
}

function withoutTrailingPunctuation(url: string): string {
  return url.slice(0, url.length - trailingPunctuation(url).length);
}

/** Free text with the URLs inside it scrubbed, and any UUID or e-mail address replaced. */
export function sanitizeText(value: string): string {
  if (looksLikeUrl(value) && !/\s/.test(value)) {
    return sanitizeUrl(value);
  }
  return value
    .replace(
      ABSOLUTE_URL,
      (url) => sanitizeUrl(withoutTrailingPunctuation(url)) + trailingPunctuation(url),
    )
    .replace(
      PATH_WITH_QUERY,
      (whole: string, path: string) => sanitizeUrl(path) + trailingPunctuation(whole),
    )
    .replace(UUID, ':uuid')
    .replace(EMAIL, ':email');
}

function isHeaderOrBody(key: string): boolean {
  return HEADER_OR_BODY.test(key);
}

/** An OTLP span attribute (`{ key, value }`) named like a header or a body. */
function isHeaderOrBodyAttribute(value: unknown): boolean {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return false;
  const key = (value as { key?: unknown }).key;
  return typeof key === 'string' && 'value' in value && isHeaderOrBody(key);
}

function sanitizeValue(value: unknown): unknown {
  if (typeof value === 'string') {
    return sanitizeText(value);
  }
  if (Array.isArray(value)) {
    return value
      .filter((entry) => !isHeaderOrBodyAttribute(entry))
      .map((entry) => sanitizeValue(entry));
  }
  if (value !== null && typeof value === 'object') {
    // Errors and other class instances are read through their own enumerable fields only.
    const out: Record<string, unknown> = {};
    for (const [key, entry] of Object.entries(value as Record<string, unknown>)) {
      if (isHeaderOrBody(key)) continue;
      out[key] = sanitizeValue(entry);
    }
    return out;
  }
  return value;
}

/**
 * The item as it may leave the browser. Faro's `beforeSend` hook: never returns null, so no
 * telemetry is lost, only what identifies a person or a record.
 */
export function sanitizeTelemetryItem<T>(item: T): T {
  return sanitizeValue(item) as T;
}
