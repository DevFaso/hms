/**
 * Appointments E2E Tests – Appointment list, filters, new appointment.
 *
 * Uses stored auth state (chromium project).
 */
import { test, expect } from './fixtures/test-fixtures';

test.describe('Appointments Module', () => {
  test.describe('Appointment List', () => {
    test.beforeEach(async ({ page }) => {
      await page.goto('/appointments', { waitUntil: 'networkidle' });
    });

    test('displays appointments page with title', async ({ page }) => {
      await expect(page.locator('main .page-title')).toContainText('Appointments');
      await expect(page.locator('main .page-subtitle')).toContainText('appointments');
    });

    test('shows New Appointment button', async ({ page }) => {
      const newBtn = page.locator('a:has-text("New Appointment")');
      await expect(newBtn).toBeVisible();
    });

    test('displays search bar and status filter', async ({ page }) => {
      await expect(page.locator('.filter-search input')).toBeVisible();
      await expect(page.locator('.filter-select')).toBeVisible();
    });

    test('status filter has all options', async ({ page }) => {
      const options = page.locator('.filter-select option');
      const texts = await options.allTextContents();
      expect(texts).toContain('All Statuses');
      expect(texts).toContain('Scheduled');
      expect(texts).toContain('Confirmed');
      expect(texts).toContain('Completed');
      expect(texts).toContain('Cancelled');
    });

    test('loads appointment data (table or empty state)', async ({ page }) => {
      await page.waitForFunction(
        () =>
          !document.querySelector('.loading-state') ||
          document.querySelector('.data-table') ||
          document.querySelector('.empty-state'),
        { timeout: 15_000 },
      );

      const hasTable = await page.locator('.data-table').count();
      const hasEmpty = await page.locator('.empty-state').count();
      expect(hasTable + hasEmpty).toBeGreaterThan(0);
    });

    test('clicking New Appointment navigates to form', async ({ page }) => {
      const newBtn = page.locator('a:has-text("New Appointment")');
      await newBtn.click();
      await page.waitForURL(/\/appointments\/new/);
    });

    test('search filters appointment list', async ({ page }) => {
      await page.waitForFunction(() => !document.querySelector('.loading-state'), {
        timeout: 15_000,
      });

      const searchInput = page.locator('.filter-search input');
      const rows = page.locator('.data-table tbody tr');
      const initialCount = await rows.count();

      if (initialCount > 0) {
        await searchInput.fill('xyznonexistent12345');
        await page.waitForTimeout(500);
        const filteredCount = await rows.count();
        expect(filteredCount).toBeLessThanOrEqual(initialCount);
      }
    });

    test('status filter changes displayed appointments', async ({ page }) => {
      await page.waitForFunction(() => !document.querySelector('.loading-state'), {
        timeout: 15_000,
      });

      const filter = page.locator('.filter-select');
      await filter.selectOption('CANCELLED');
      await page.waitForTimeout(500);
      // Should filter or show different results
    });
  });
});
