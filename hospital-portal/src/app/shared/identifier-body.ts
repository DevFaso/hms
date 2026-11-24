/**
 * The body of a search or lookup keyed by a personal identifier (a name, a birth date, a phone
 * number, an e-mail address, an MRN, a username).
 *
 * Those values never go into a URL: a query string or a path segment lands in proxy and access
 * logs, in browser history and in front-end telemetry. The backend takes them in the body of a
 * POST that only reads; `scripts/identifier-url.test.mjs` fails on a portal call that puts one
 * back in a query string.
 *
 * Empty, blank and missing values are dropped, as the query builders dropped them before.
 */
export function compactBody(
  values: Record<string, string | null | undefined>,
): Record<string, string> {
  const body: Record<string, string> = {};
  for (const [key, value] of Object.entries(values)) {
    if (typeof value === 'string' && value.trim() !== '') {
      body[key] = value;
    }
  }
  return body;
}
