/**
 * Global Setup – Authenticates as SuperAdmin and persists storage state.
 *
 * Runs once before the "chromium" project so every authenticated test
 * starts with a valid JWT without repeating the login flow.
 */
import { test as setup, expect } from '@playwright/test';
import path from 'node:path';

const AUTH_FILE = path.join(__dirname, '.auth/user.json');

setup('authenticate as SuperAdmin', async ({ page }) => {
  // Navigate to login
  await page.goto('/login', { waitUntil: 'networkidle' });

  // Fill credentials
  await page.locator('#username').fill('superadmin');
  await page.locator('#password').fill('TempPass123!');

  // Submit
  await page.locator('button[type="submit"]').click();

  // Wait for navigation to dashboard (post-login redirect)
  await page.waitForURL('**/dashboard', { timeout: 15_000 });
  await expect(page.locator('.welcome-banner')).toBeVisible({ timeout: 10_000 });

  // Persist storage state (localStorage with auth_token + user_profile)
  await page.context().storageState({ path: AUTH_FILE });
});
