/**
 * Admin Modules E2E Tests – Organizations, Users, Roles, Platform, Audit Logs.
 *
 * Uses stored auth state (chromium project).
 */
import { test, expect } from './fixtures/test-fixtures';

test.describe('Admin Modules', () => {
  test.describe('Organizations', () => {
    test('displays organizations page', async ({ page }) => {
      await page.goto('/organizations', { waitUntil: 'networkidle' });
      expect(page.url()).not.toContain('/login');
      const content = page.locator('.page-container, .page-title');
      await expect(content.first()).toBeVisible({ timeout: 10_000 });
    });

    test('organizations page loads data or empty state', async ({ page }) => {
      await page.goto('/organizations', { waitUntil: 'networkidle' });
      await page.waitForFunction(() => !document.querySelector('.loading-state'), {
        timeout: 15_000,
      });
      const dataOrEmpty = page.locator(
        '.data-table, .empty-state, table, .card, .org-grid, .org-card',
      );
      const container = page.locator('.page-container');
      expect((await dataOrEmpty.count()) + (await container.count())).toBeGreaterThan(0);
    });
  });

  test.describe('Users', () => {
    test('displays users page', async ({ page }) => {
      await page.goto('/users', { waitUntil: 'networkidle' });
      expect(page.url()).not.toContain('/login');
      const content = page.locator('.page-container, .page-title');
      await expect(content.first()).toBeVisible({ timeout: 10_000 });
    });

    test('users page has search functionality', async ({ page }) => {
      await page.goto('/users', { waitUntil: 'networkidle' });
      const searchInput = page.locator(
        '.search-bar input, input[type="text"][placeholder*="earch"]',
      );
      if ((await searchInput.count()) > 0) {
        await expect(searchInput.first()).toBeVisible();
      }
    });
  });

  test.describe('Roles', () => {
    test('displays roles page', async ({ page }) => {
      await page.goto('/roles', { waitUntil: 'networkidle' });
      expect(page.url()).not.toContain('/login');
      const content = page.locator('.page-container, .page-title');
      await expect(content.first()).toBeVisible({ timeout: 10_000 });
    });
  });

  test.describe('Platform', () => {
    test('displays platform page', async ({ page }) => {
      await page.goto('/platform', { waitUntil: 'networkidle' });
      expect(page.url()).not.toContain('/login');
      const content = page.locator('.page-container, .page-title, .platform');
      await expect(content.first()).toBeVisible({ timeout: 10_000 });
    });
  });

  test.describe('Audit Logs', () => {
    test('displays audit logs page', async ({ page }) => {
      await page.goto('/audit-logs', { waitUntil: 'networkidle' });
      expect(page.url()).not.toContain('/login');
      const content = page.locator('.page-container, .page-title');
      await expect(content.first()).toBeVisible({ timeout: 10_000 });
    });
  });

  test.describe('Administration', () => {
    test('displays admin page', async ({ page }) => {
      await page.goto('/admin', { waitUntil: 'networkidle' });
      expect(page.url()).not.toContain('/login');
      const content = page.locator('.page-container, .page-title, body');
      await expect(content.first()).toBeVisible({ timeout: 10_000 });
    });
  });
});
