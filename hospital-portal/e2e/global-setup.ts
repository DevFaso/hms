/**
 * Global Setup – Authenticates as SuperAdmin and persists storage state.
 *
 * Runs once before the "chromium" project so every authenticated test
 * starts with a valid JWT without repeating the login flow.
 *
 * Uses a mocked login response so this setup does not require the
 * Spring Boot backend to be running locally.
 */
import { test as setup, expect } from '@playwright/test';
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
  // Mock the login API so setup works without a live backend
  await page.route('**/auth/login', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(MOCK_LOGIN_RESPONSE),
    });
  });

  // Navigate to login
  await page.goto('/login', { waitUntil: 'networkidle' });

  // Fill credentials
  await page.locator('#username').fill('superadmin');
  await page.locator('#password').fill('TempPass123!');

  // Submit and wait for the mocked login response + redirect
  await Promise.all([
    page.waitForResponse('**/auth/login'),
    page.locator('button[type="submit"]').click(),
  ]);

  // Wait for navigation to dashboard (post-login redirect)
  await page.waitForURL('**/dashboard', { timeout: 15_000 });
  await expect(page.locator('.welcome-banner')).toBeVisible({ timeout: 10_000 });

  // Persist storage state (localStorage with auth_token + user_profile)
  await page.context().storageState({ path: AUTH_FILE });
});
