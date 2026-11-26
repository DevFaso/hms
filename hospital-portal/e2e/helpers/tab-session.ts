import type { Page, Route } from '@playwright/test';

/**
 * The portal keeps the session (access token, profile) in memory only. A tab
 * that signed in carries a bare flag in sessionStorage, and on every page load
 * it asks the refresh cookie for a token (POST /api/auth/token/refresh with
 * the `X-Session-Restore` header), then the session bootstrap for the profile
 * (see src/app/auth/session-restore.ts).
 *
 * A test signs a page in by setting that flag and answering the start-up
 * refresh with its token. The session bootstrap stays the test's to mock.
 */
export const TAB_SESSION_KEY = 'hms.session.tab';
export const SESSION_RESTORE_HEADER = 'x-session-restore';

/** Whether this refresh is the start-up restore (not a token renewal by the interceptor). */
export function isSessionRestore(route: Route): boolean {
  return route.request().headers()[SESSION_RESTORE_HEADER] === '1';
}

export async function seedTabSession(page: Page, accessToken: string): Promise<void> {
  await page.addInitScript((key) => sessionStorage.setItem(key, '1'), TAB_SESSION_KEY);
  await page.route('**/api/auth/token/refresh', (route) =>
    isSessionRestore(route) ? route.fulfill({ json: { accessToken } }) : route.fallback(),
  );
}

/** A structurally valid, unsigned token the portal decodes (roles, expiry) but never verifies. */
export function fakeToken(claims: Record<string, unknown>, signature = 'e2e-signature'): string {
  const payload = Buffer.from(JSON.stringify(claims)).toString('base64url');
  return `eyJhbGciOiJIUzI1NiJ9.${payload}.${signature}`;
}
