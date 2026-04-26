/**
 * KC-2b slice 3 — End-to-end coverage for the Keycloak / OIDC PKCE flow.
 *
 * <p>The portal ships with `environment.oidc.enabled = false` by default,
 * so the SSO button is hidden and the legacy form is the only login
 * path. The first spec asserts that safe default in CI on every PR.
 *
 * <p>The second spec drives the real Keycloak login page using the
 * `dev.doctor` user from `keycloak/realm-export.dev-users.json`. It is
 * skipped unless the environment variable `KEYCLOAK_E2E=1` is set,
 * because it requires (a) a custom build of the portal where
 * `environment.oidc.enabled = true` and (b) a reachable Keycloak
 * realm at `KEYCLOAK_ISSUER_URL` (defaults to the docker-compose
 * dev profile at `http://localhost:8081/realms/hms`).
 *
 * <p>Local run (with the keycloak docker profile up + portal rebuilt
 * with the flag on):
 * <pre>
 *   KEYCLOAK_E2E=1 npm run e2e -- --project=no-auth keycloak-login.spec.ts
 * </pre>
 */
import { test, expect } from '@playwright/test';
import process from 'node:process';

const ssoButton = 'button.btn-sso';

test.describe('KC-2b — Keycloak SSO entry point (default OFF)', () => {
  test.beforeEach(async ({ page }) => {
    // The login page makes two unauth bootstrap calls; stub them so the
    // spec does not depend on a live backend.
    await page.route('**/api/auth/csrf-token', (route) => route.fulfill({ status: 204 }));
    await page.route('**/api/auth/bootstrap-status', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ allowed: false }),
      }),
    );
    await page.goto('/login', { waitUntil: 'domcontentloaded' });
  });

  test('SSO button is hidden when environment.oidc.enabled is false', async ({ page }) => {
    // Legacy form is the only path during the rollout window.
    await expect(page.locator('#username')).toBeVisible();
    await expect(page.locator('#password')).toBeVisible();
    await expect(page.locator(ssoButton)).toHaveCount(0);
  });

  test('the divider between SSO and legacy form is hidden when the flag is off', async ({
    page,
  }) => {
    await expect(page.locator('.sso-divider')).toHaveCount(0);
  });
});

test.describe('KC-3 — legacy /api/auth/login returns 410 Gone (cutover soak)', () => {
  // Phase 2.1 (G-6): once `app.auth.oidc.required=true` flips on the
  // backend, /api/auth/login responds 410 Gone with the runbook
  // message. The portal must surface that message verbatim so users
  // see "Sign in via Single Sign-On" rather than the generic
  // "Login failed. Please try again." fallback.
  const legacyDisabledMessage =
    'Legacy username/password login is disabled. Sign in via Single Sign-On.';

  test.beforeEach(async ({ page }) => {
    await page.route('**/api/auth/csrf-token', (route) => route.fulfill({ status: 204 }));
    await page.route('**/api/auth/bootstrap-status', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ allowed: false }),
      }),
    );
    await page.route('**/api/auth/login', (route) =>
      route.fulfill({
        status: 410,
        contentType: 'application/json',
        body: JSON.stringify({ message: legacyDisabledMessage }),
      }),
    );
    await page.goto('/login', { waitUntil: 'domcontentloaded' });
  });

  test('legacy form surfaces the runbook message verbatim on 410 Gone', async ({ page }) => {
    await page.locator('#username').fill('any.user');
    await page.locator('#password').fill('any-password');
    await page.locator('button[type="submit"]').click();

    const errorBanner = page.locator('.error-banner');
    await expect(errorBanner).toBeVisible();
    await expect(errorBanner).toContainText(legacyDisabledMessage);
  });

  test('error message points the user at SSO instead of generic "try again"', async ({
    page,
  }) => {
    await page.locator('#username').fill('any.user');
    await page.locator('#password').fill('any-password');
    await page.locator('button[type="submit"]').click();

    const errorBanner = page.locator('.error-banner');
    await expect(errorBanner).toBeVisible();
    await expect(errorBanner).toContainText('Single Sign-On');
    // The generic copy must NOT appear — that would mean the portal
    // ignored err.error.message and fell back to the catch-all.
    await expect(errorBanner).not.toContainText('Login failed. Please try again.');
  });
});

const keycloakE2EEnabled = process.env.KEYCLOAK_E2E === '1';
const issuer = process.env.KEYCLOAK_ISSUER_URL ?? 'http://localhost:8081/realms/hms';
const devUser = process.env.KEYCLOAK_DEV_USER ?? 'dev.doctor';
const devPass = process.env.KEYCLOAK_DEV_PASSWORD ?? 'DevDoctor#2026';

test.describe('KC-2b — Keycloak Auth-Code+PKCE happy path (live Keycloak)', () => {
  test.skip(
    !keycloakE2EEnabled,
    'Set KEYCLOAK_E2E=1 and run a portal build with environment.oidc.enabled=true to execute this spec.',
  );

  test('clicking SSO button redirects to Keycloak, signs in, returns to the app', async ({
    page,
  }) => {
    await page.goto('/login', { waitUntil: 'domcontentloaded' });

    const sso = page.locator(ssoButton);
    await expect(sso).toBeVisible();

    await Promise.all([
      page.waitForURL((url) => url.toString().startsWith(issuer), { timeout: 15_000 }),
      sso.click(),
    ]);

    // Keycloak login page — fields are named `username` and `password`.
    await page.locator('#username').fill(devUser);
    await page.locator('#password').fill(devPass);
    await page.locator('#kc-login').click();

    // After the redirect callback, the portal should land on the dashboard.
    await page.waitForURL('**/dashboard', { timeout: 30_000 });

    // Token must have been mirrored into Web Storage by OidcAuthService.
    const tokenPresent = await page.evaluate(() => {
      const fromSession = window.sessionStorage.getItem('auth_token');
      const fromLocal = window.localStorage.getItem('auth_token');
      return Boolean(fromSession || fromLocal);
    });
    expect(tokenPresent).toBe(true);
  });
});
