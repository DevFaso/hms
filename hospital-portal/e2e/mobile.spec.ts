/**
 * Mobile Responsive E2E Tests – Validates responsive behavior at mobile viewport.
 *
 * Uses Pixel 5 device preset (393×851).
 */
import { test, expect } from './fixtures/test-fixtures';

const BASE = process.env.PLAYWRIGHT_BASE_URL ?? 'http://localhost:4200';

test.describe('Mobile Responsive', () => {
  // Apply mobile viewport to every test in this describe block so that CSS
  // media queries (e.g. .mobile-toggle display:flex at max-width:768px) fire
  // correctly. Without this, tests use the default desktop viewport.
  test.use({ viewport: { width: 393, height: 851 } });

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
    // .mobile-toggle is display:flex only at max-width:768px (CSS media query).
    // With the mobile viewport set above, this assertion is always reliable.
    await expect(page.locator('.mobile-toggle')).toBeVisible({ timeout: 10_000 });
  });

  test('patients page renders at mobile viewport', async ({ page }) => {
    await page.goto('/patients', { waitUntil: 'domcontentloaded' });
    await expect(page.locator('main .page-title')).toBeVisible({ timeout: 10_000 });
  });

  test.describe('at 375px (D812)', () => {
    test.use({ viewport: { width: 375, height: 812 } });

    // Measures the HEADER, not only the page: the page did not overflow
    // while the topbar was 736px wide and the profile menu sat off-screen.
    test('the topbar fits and the profile menu is on screen', async ({ page }) => {
      await page.goto('/dashboard', { waitUntil: 'domcontentloaded' });
      const topbar = page.locator('header.topbar');
      await expect(topbar).toBeVisible({ timeout: 15_000 });
      const widths = await topbar.evaluate((el) => ({ sw: el.scrollWidth, cw: el.clientWidth }));
      expect(widths.sw).toBeLessThanOrEqual(widths.cw);
      const profile = await page.locator('.profile-btn').boundingBox();
      expect(profile).not.toBeNull();
      expect(profile!.x + profile!.width).toBeLessThanOrEqual(375);
    });

    test('the drawer offers sign-out', async ({ page }) => {
      await page.goto('/dashboard', { waitUntil: 'domcontentloaded' });
      await page.locator('.mobile-toggle').click();
      await expect(page.getByTestId('sidebar-logout')).toBeVisible();
    });
  });

  test('topbar is visible at mobile viewport', async ({ page }) => {
    await page.goto('/dashboard', { waitUntil: 'domcontentloaded' });
    await expect(page.locator('header.topbar')).toBeVisible();
  });
});
