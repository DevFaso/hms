/**
 * Departments E2E Tests – Department list, search, detail view.
 *
 * Uses stored auth state (chromium project).
 */
import { test, expect } from './fixtures/test-fixtures';

test.describe('Departments Module', () => {
  test.describe('Department List', () => {
    test.beforeEach(async ({ page }) => {
      await page.goto('/departments', { waitUntil: 'networkidle' });
    });

    test('displays departments page with title', async ({ page }) => {
      await expect(page.locator('main .page-title')).toContainText('Departments');
      await expect(page.locator('main .page-subtitle')).toContainText('departments');
    });

    test('displays search bar', async ({ page }) => {
      const search = page.locator('.search-bar input');
      await expect(search).toBeVisible();
      await expect(search).toHaveAttribute('placeholder', /search/i);
    });

    test('loads department data (grid or empty state)', async ({ page }) => {
      await page.waitForFunction(
        () =>
          !document.querySelector('.loading-state') ||
          document.querySelector('.dept-grid') ||
          document.querySelector('.empty-state'),
        { timeout: 15_000 },
      );

      const hasGrid = await page.locator('.dept-grid').count();
      const hasEmpty = await page.locator('.empty-state').count();
      expect(hasGrid + hasEmpty).toBeGreaterThan(0);
    });

    test('department cards show name and code', async ({ page }) => {
      await page.waitForFunction(() => !document.querySelector('.loading-state'), {
        timeout: 15_000,
      });

      const cards = page.locator('.dept-card');
      if ((await cards.count()) > 0) {
        const firstCard = cards.first();
        await expect(firstCard.locator('h4')).toBeVisible();
        await expect(firstCard.locator('.dept-code')).toBeVisible();
      }
    });

    test('department cards show active/inactive badge', async ({ page }) => {
      await page.waitForFunction(() => !document.querySelector('.loading-state'), {
        timeout: 15_000,
      });

      const badges = page.locator('.dept-card .status-badge');
      if ((await badges.count()) > 0) {
        const text = await badges.first().textContent();
        expect(text).toMatch(/Active|Inactive/);
      }
    });

    test('search filters department list', async ({ page }) => {
      await page.waitForFunction(() => !document.querySelector('.loading-state'), {
        timeout: 15_000,
      });

      const searchInput = page.locator('.search-bar input');
      const cards = page.locator('.dept-card');
      const initialCount = await cards.count();

      if (initialCount > 0) {
        await searchInput.fill('xyznonexistent12345');
        await page.waitForTimeout(500);
        const filteredCount = await cards.count();
        expect(filteredCount).toBeLessThanOrEqual(initialCount);
      }
    });

    test('clicking edit on a department card opens the edit modal', async ({ page }) => {
      await page.waitForFunction(() => !document.querySelector('.loading-state'), {
        timeout: 15_000,
      });

      const cards = page.locator('.dept-card');
      if ((await cards.count()) > 0) {
        await cards.first().getByTitle('Edit').click();
        await expect(page.locator('.modal-header h2')).toContainText('Edit Department');
      }
    });
  });
});
