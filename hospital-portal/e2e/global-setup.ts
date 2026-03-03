/**
 * Global Setup – Authenticates as SuperAdmin and persists storage state.
 *
 * Runs once before the "chromium" project so every authenticated test
 * starts with a valid JWT without repeating the login flow.
 *
 * Uses a mocked login response so this setup does not require the
 * Spring Boot backend to be running locally.
 */
import { test as setup } from '@playwright/test';
import path from 'node:path';

const AUTH_FILE = path.join(__dirname, '.auth/user.json');

/** Fake JWT parts (header.payload.signature) – structurally valid, not verified */
const FAKE_JWT =
  'eyJhbGciOiJIUzM4NCJ9' +
  '.eyJzdWIiOiJzdXBlcmFkbWluIiwicm9sZXMiOlsiUk9MRV9TVVBFUl9BRE1JTiJdLCJpYXQiOjE3MDAwMDAwMDAsImV4cCI6OTk5OTk5OTk5OX0' +
  '.fake-signature-for-e2e-testing';

const MOCK_LOGIN_RESPONSE = {
  tokenType: 'Bearer',
  accessToken: FAKE_JWT,
  refreshToken: FAKE_JWT,
  id: '00000000-0000-0000-0000-000000000001',
  username: 'superadmin',
  email: 'superadmin@seed.dev',
  firstName: 'System',
  lastName: 'SuperAdmin',
  phoneNumber: '+22600000000',
  roles: ['ROLE_SUPER_ADMIN'],
  roleName: 'ROLE_SUPER_ADMIN',
  active: true,
};

setup('authenticate as SuperAdmin', async ({ page }) => {
  // Playwright route handlers are LIFO (last registered = first matched).
  // Register the catch-all FIRST so specific mocks registered later take priority.

  // Catch-all: return empty paginated response for all /api/** calls so the
  // Angular error interceptor never fires a redirect to /login.
  await page.route('**/api/**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ content: [], totalElements: 0, totalPages: 0, size: 20, number: 0 }),
    }),
  );

  // Bootstrap-status (called by login page on init) — overrides catch-all.
  await page.route('**/api/auth/bootstrap-status', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ allowed: false }),
    }),
  );

  // CSRF token endpoint so the SPA bootstrap call does not block — overrides catch-all.
  await page.route('**/api/auth/csrf-token', (route) => route.fulfill({ status: 204 }));

  // Login mock — registered LAST so it wins over the catch-all (LIFO).
  await page.route('**/api/auth/login', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(MOCK_LOGIN_RESPONSE),
    });
  });

  // Navigate to login — domcontentloaded is sufficient; networkidle can hang
  // when background polling keeps connections open.
  await page.goto('/login', { waitUntil: 'domcontentloaded' });

  // Fill credentials
  await page.locator('#username').fill('superadmin');
  await page.locator('#password').fill('TempPass123!');

  // Submit and wait for the mocked login response + redirect
  await Promise.all([
    page.waitForResponse('**/api/auth/login'),
    page.locator('button[type="submit"]').click(),
  ]);

  // Wait for navigation to dashboard (post-login redirect)
  await page.waitForURL('**/dashboard', { timeout: 15_000 });

  // Persist storage state BEFORE any UI assertion so the auth file is always
  // written even when the dashboard is slow to render under parallel load.
  await page.context().storageState({ path: AUTH_FILE });

  // Soft readiness check — wait for the shell to mount (header or main content)
  await page
    .locator('.hero-header, main, .dashboard')
    .first()
    .waitFor({ state: 'visible', timeout: 10_000 })
    .catch(() => {
      // Dashboard may still be loading; auth state is already saved — continue.
    });
});
