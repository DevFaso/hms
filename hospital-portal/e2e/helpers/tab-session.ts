import { createHash } from 'node:crypto';

import type { Page, Route } from '@playwright/test';

/**
 * The portal keeps the session (access token, profile) in memory only. A tab
 * that signed in carries an opaque binding in sessionStorage (a SHA-256 digest
 * of the account the token names), and on every page load it asks the refresh
 * cookie for a token (POST /api/auth/token/refresh with the `X-Session-Restore`
 * header), keeps it only when it names the bound account, reads the account's
 * credential status, then the session bootstrap for the profile (see
 * src/app/auth/session-restore.ts).
 *
 * A test signs a page in by binding the tab to its token's account and
 * answering the start-up refresh with that token. The session bootstrap stays
 * the test's to mock.
 */
export const TAB_SESSION_KEY = 'hms.session.tab';
export const SESSION_RESTORE_HEADER = 'x-session-restore';

/** Whether this refresh is the start-up restore (not a token renewal by the interceptor). */
export function isSessionRestore(route: Route): boolean {
  return route.request().headers()[SESSION_RESTORE_HEADER] === '1';
}

/** The account a token names, as the portal reads it: `uid`, else `id`, else `sub`. */
function tokenSubject(token: string): string {
  const part = token.split('.')[1].replaceAll('-', '+').replaceAll('_', '/');
  const claims = JSON.parse(Buffer.from(part, 'base64').toString('utf8')) as Record<
    string,
    unknown
  >;
  return String(claims['uid'] ?? claims['id'] ?? claims['sub']);
}

/** The tab binding of a token's account (core/browser-storage.ts tabBindingFor). */
export function tabBindingFor(token: string): string {
  return createHash('sha256')
    .update(`hms-tab:${tokenSubject(token)}`)
    .digest('hex');
}

export async function seedTabSession(page: Page, accessToken: string): Promise<void> {
  await page.addInitScript(({ key, binding }) => sessionStorage.setItem(key, binding), {
    key: TAB_SESSION_KEY,
    binding: tabBindingFor(accessToken),
  });
  await page.route('**/api/auth/token/refresh', (route) =>
    isSessionRestore(route) ? route.fulfill({ json: { accessToken } }) : route.fallback(),
  );
  await page.route('**/api/auth/credentials/me', (route) =>
    route.fulfill({ json: { forcePasswordChange: false, forceUsernameChange: false } }),
  );
}

/** A structurally valid, unsigned token the portal decodes (roles, expiry) but never verifies. */
export function fakeToken(claims: Record<string, unknown>, signature = 'e2e-signature'): string {
  const payload = Buffer.from(JSON.stringify(claims)).toString('base64url');
  return `eyJhbGciOiJIUzI1NiJ9.${payload}.${signature}`;
}
