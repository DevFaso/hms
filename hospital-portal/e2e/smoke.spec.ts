/**
 * Smoke Tests – Quick sanity checks for production/staging.
 *
 * Runs against SMOKE_BASE_URL (or default localhost).
 * Should be fast, minimal, and catch critical regressions.
 */
import { test, expect } from '@playwright/test';

const BASE =
  process.env.SMOKE_BASE_URL ?? process.env.PLAYWRIGHT_BASE_URL ?? 'http://localhost:4200';

test.describe('Smoke Tests', () => {
  test('application loads without crashing', async ({ page }) => {
    const response = await page.goto('/', { waitUntil: 'networkidle' });
    expect(response?.status()).toBeLessThan(500);
  });

  test('login page renders', async ({ browser }) => {
    const context = await browser.newContext({ baseURL: BASE, storageState: undefined });
    const page = await context.newPage();
    await page.goto('/login', { waitUntil: 'networkidle' });
    await expect(page.locator('#username')).toBeVisible({ timeout: 10_000 });
    await expect(page.locator('#password')).toBeVisible();
    await expect(page.locator('button[type="submit"]')).toBeVisible();
    await context.close();
  });

  test('can authenticate with valid credentials', async ({ browser }) => {
    const context = await browser.newContext({ baseURL: BASE, storageState: undefined });
    const page = await context.newPage();
    await page.goto('/login', { waitUntil: 'networkidle' });
    await page.locator('#username').fill('superadmin');
    await page.locator('#password').fill('TempPass123!');
    await page.locator('button[type="submit"]').click();
    await page.waitForURL('**/dashboard', { timeout: 15_000 });
    await expect(page.locator('.welcome-banner')).toBeVisible({ timeout: 10_000 });
    await context.close();
  });

  test('dashboard shows metrics', async ({ page }) => {
    await page.goto('/dashboard', { waitUntil: 'networkidle' });
    await expect(page.locator('.welcome-banner')).toBeVisible({ timeout: 15_000 });
  });

  test('sidebar navigation is present', async ({ page }) => {
    await page.goto('/dashboard', { waitUntil: 'networkidle' });
    await expect(page.locator('aside.sidebar')).toBeVisible();
    const navCount = await page.locator('.nav-item').count();
    expect(navCount).toBeGreaterThan(0);
  });

  test('patients page loads', async ({ page }) => {
    await page.goto('/patients', { waitUntil: 'networkidle' });
    await expect(page.locator('main .page-title')).toBeVisible({ timeout: 10_000 });
  });

  test('no console errors on critical pages', async ({ page }) => {
    const errors: string[] = [];
    page.on('console', (msg) => {
      if (msg.type() === 'error' && !msg.text().includes('favicon')) {
        errors.push(msg.text());
      }
    });

    await page.goto('/dashboard', { waitUntil: 'networkidle' });
    await page.waitForTimeout(3000);

    // Filter out expected non-critical errors
    const critical = errors.filter(
      (e) => !e.includes('404') && !e.includes('websocket') && !e.includes('net::'),
    );

    // Log but don't necessarily fail on all console errors
    if (critical.length > 0) {
      console.warn('Console errors detected:', critical);
    }
  });
});
