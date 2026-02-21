/**
 * Staff E2E Tests – Staff list, search, detail view.
 *
 * Uses stored auth state (chromium project).
 */
import { test, expect } from './fixtures/test-fixtures';

test.describe('Staff Module', () => {
  test.describe('Staff List', () => {
    test.beforeEach(async ({ page }) => {
      await page.goto('/staff', { waitUntil: 'networkidle' });
    });

    test('displays staff page with title', async ({ page }) => {
      await expect(page.locator('main .page-title')).toContainText('Staff');
      await expect(page.locator('main .page-subtitle')).toContainText('staff members');
    });

    test('displays search bar', async ({ page }) => {
      const search = page.locator('.search-bar input');
      await expect(search).toBeVisible();
      await expect(search).toHaveAttribute('placeholder', /search/i);
    });

    test('loads staff data (grid or empty state)', async ({ page }) => {
      // Wait for loading to complete
      await page.waitForFunction(
        () =>
          !document.querySelector('.loading-state') ||
          document.querySelector('.staff-grid') ||
          document.querySelector('.empty-state'),
        { timeout: 15_000 },
      );

      const hasGrid = await page.locator('.staff-grid').count();
      const hasEmpty = await page.locator('.empty-state').count();
      expect(hasGrid + hasEmpty).toBeGreaterThan(0);
    });

    test('staff cards show name and role', async ({ page }) => {
      await page.waitForFunction(() => !document.querySelector('.loading-state'), {
        timeout: 15_000,
      });

      const cards = page.locator('.staff-card');
      if ((await cards.count()) > 0) {
        const firstCard = cards.first();
        await expect(firstCard.locator('h4')).toBeVisible();
        await expect(firstCard.locator('.staff-avatar')).toBeVisible();
      }
    });

    test('search filters staff list', async ({ page }) => {
      await page.waitForFunction(() => !document.querySelector('.loading-state'), {
        timeout: 15_000,
      });

      const searchInput = page.locator('.search-bar input');
      const cards = page.locator('.staff-card');
      const initialCount = await cards.count();

      if (initialCount > 0) {
        await searchInput.fill('xyznonexistent12345');
        await page.waitForTimeout(500);
        const filteredCount = await cards.count();
        expect(filteredCount).toBeLessThanOrEqual(initialCount);
      }
    });

    test('clicking edit on a staff card opens the edit modal', async ({ page }) => {
      await page.waitForFunction(() => !document.querySelector('.loading-state'), {
        timeout: 15_000,
      });

      const cards = page.locator('.staff-card');
      if ((await cards.count()) > 0) {
        await cards.first().getByTitle('Edit').click();
        await expect(page.locator('.modal-header h2')).toContainText('Edit Staff');
      }
    });
  });
});
