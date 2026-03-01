/**
 * Mobile Responsive E2E Tests – Validates responsive behavior at mobile viewport.
 *
 * Uses Pixel 5 device preset (393×851).
 */
import { test, expect } from './fixtures/test-fixtures';

const BASE = process.env.PLAYWRIGHT_BASE_URL ?? 'http://localhost:4200';

test.describe('Mobile Responsive', () => {
  test('login page is usable at mobile viewport', async ({ browser }) => {
    const context = await browser.newContext({
      baseURL: BASE,
      storageState: undefined,
      viewport: { width: 393, height: 851 },
    });
    const page = await context.newPage();
    await page.goto('/login', { waitUntil: 'domcontentloaded' });
    await expect(page.locator('.login-card')).toBeVisible();
    await expect(page.locator('#username')).toBeVisible();
    await expect(page.locator('#password')).toBeVisible();
    await expect(page.locator('button[type="submit"]')).toBeVisible();
    await context.close();
  });

  test('dashboard renders at mobile viewport', async ({ page }) => {
    await page.goto('/dashboard', { waitUntil: 'domcontentloaded' });
    await expect(page.locator('.hero-header')).toBeVisible({ timeout: 15_000 });
  });

  test('mobile menu toggle is visible', async ({ page }) => {
    await page.goto('/dashboard', { waitUntil: 'domcontentloaded' });
    const mobileToggle = page.locator('.mobile-toggle');
    // On mobile viewports, the mobile toggle should be visible or the sidebar collapsed
    const isToggleVisible = await mobileToggle.isVisible().catch(() => false);
    const isSidebarCollapsed = (await page.locator('.shell.collapsed').count()) > 0;
    expect(isToggleVisible || isSidebarCollapsed).toBeTruthy();
  });

  test('patients page renders at mobile viewport', async ({ page }) => {
    await page.goto('/patients', { waitUntil: 'domcontentloaded' });
    await expect(page.locator('main .page-title')).toBeVisible({ timeout: 10_000 });
  });

  test('topbar is visible at mobile viewport', async ({ page }) => {
    await page.goto('/dashboard', { waitUntil: 'domcontentloaded' });
    await expect(page.locator('header.topbar')).toBeVisible();
  });
});
