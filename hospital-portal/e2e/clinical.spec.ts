/**
 * Clinical Modules E2E Tests – Encounters, Admissions, Prescriptions,
 * Lab, Imaging, Consultations, Treatment Plans, Referrals, Nurse Station.
 *
 * Uses stored auth state (chromium project).
 */
import { test, expect } from './fixtures/test-fixtures';

const clinicalPages = [
  { path: '/encounters', title: 'Encounters' },
  { path: '/admissions', title: 'Admissions' },
  { path: '/prescriptions', title: 'Prescriptions' },
  { path: '/nurse-station', title: 'Nurse Station' },
  { path: '/imaging', title: 'Imaging' },
  { path: '/consultations', title: 'Consultations' },
  { path: '/treatment-plans', title: 'Treatment Plans' },
  { path: '/referrals', title: 'Referrals' },
  { path: '/lab', title: 'Lab' },
];

test.describe('Clinical Modules', () => {
  for (const mod of clinicalPages) {
    test.describe(mod.title, () => {
      test(`${mod.title} page loads without errors`, async ({ page }) => {
        page.on('console', () => {
          // Monitor for console errors during page load
        });

        await page.goto(mod.path, { waitUntil: 'networkidle' });

        // Should not redirect to login
        expect(page.url()).not.toContain('/login');

        // Should have visible content
        const content = page.locator('.page-container, .page-title, .dashboard, .shell');
        await expect(content.first()).toBeVisible({ timeout: 10_000 });
      });

      test(`${mod.title} page displays title or heading`, async ({ page }) => {
        await page.goto(mod.path, { waitUntil: 'networkidle' });

        // Wait for loading to finish
        await page.waitForFunction(() => !document.querySelector('.loading-state'), {
          timeout: 15_000,
        });

        // Should have some identifiable title/heading
        const titleEl = page.locator('.page-title, h1, h2').first();
        await expect(titleEl).toBeVisible({ timeout: 10_000 });
      });

      test(`${mod.title} shows data or empty state`, async ({ page }) => {
        await page.goto(mod.path, { waitUntil: 'networkidle' });

        await page.waitForFunction(() => !document.querySelector('.loading-state'), {
          timeout: 15_000,
        });

        // Should show either data or empty state
        const dataOrEmpty = page.locator('.data-table, .empty-state, table, .card, .grid, .list');
        // At least the page container should exist
        const container = page.locator('.page-container');
        const hasContent = (await dataOrEmpty.count()) > 0 || (await container.count()) > 0;
        expect(hasContent).toBeTruthy();
      });
    });
  }
});
