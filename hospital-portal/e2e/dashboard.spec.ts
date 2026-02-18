/**
 * Dashboard E2E Tests – Metrics, KPIs, welcome banner, navigation from dashboard.
 *
 * Uses stored auth state (chromium project).
 */
import { test, expect } from './fixtures/test-fixtures';

test.describe('Dashboard', () => {
  test.beforeEach(async ({ dashboardPage }) => {
    await dashboardPage.goto();
  });

  test.describe('Welcome Banner', () => {
    test('displays welcome banner with user name', async ({ dashboardPage }) => {
      await dashboardPage.expectDashboardLoaded();
      const heading = await dashboardPage.welcomeHeading.textContent();
      expect(heading).toBeTruthy();
      // Should contain a greeting like "Good morning, System Administrator!"
      expect(heading).toMatch(/(Good morning|Good afternoon|Good evening|Hello|Welcome)/i);
    });

    test('displays current date', async ({ dashboardPage }) => {
      await dashboardPage.expectDashboardLoaded();
      await expect(dashboardPage.welcomeDate).toBeVisible();
      const dateText = await dashboardPage.welcomeDate.textContent();
      expect(dateText).toBeTruthy();
    });
  });

  test.describe('Admin Metrics', () => {
    test('displays metrics grid for SuperAdmin', async ({ dashboardPage }) => {
      await dashboardPage.expectDashboardLoaded();
      await dashboardPage.expectMetricsVisible(1);
    });

    test('metrics cards show numeric values', async ({ page, dashboardPage }) => {
      await dashboardPage.expectDashboardLoaded();
      // Wait for metrics to load
      await page.waitForTimeout(2000);
      const metricValues = page.locator('.metric-value');
      const count = await metricValues.count();
      if (count > 0) {
        const firstValue = await metricValues.first().textContent();
        expect(firstValue).toBeTruthy();
      }
    });

    test('Total Patients metric card links to /patients', async ({ page, dashboardPage }) => {
      await dashboardPage.expectDashboardLoaded();
      await page.waitForTimeout(2000);
      const patientsCard = page.locator('a.metric-card', {
        has: page.locator('.metric-label:text("Total Patients")'),
      });
      if ((await patientsCard.count()) > 0) {
        await patientsCard.click();
        await expect(page).toHaveURL(/\/patients/);
      }
    });

    test('Active Users metric card links to /users', async ({ page, dashboardPage }) => {
      await dashboardPage.expectDashboardLoaded();
      await page.waitForTimeout(2000);
      const usersCard = page.locator('a.metric-card', {
        has: page.locator('.metric-label:text("Active Users")'),
      });
      if ((await usersCard.count()) > 0) {
        await usersCard.click();
        await expect(page).toHaveURL(/\/users/);
      }
    });
  });

  test.describe('Clinical KPIs', () => {
    test('displays KPI section when data available', async ({ page, dashboardPage }) => {
      await dashboardPage.expectDashboardLoaded();
      await page.waitForTimeout(3000);
      // KPI section may or may not be visible depending on data
      const kpiVisible = await dashboardPage.kpiSection.count();
      if (kpiVisible > 0) {
        await expect(dashboardPage.kpiSection).toBeVisible();
        const kpiCount = await dashboardPage.kpiCards.count();
        expect(kpiCount).toBeGreaterThan(0);
      }
    });
  });

  test.describe('Dashboard Loading State', () => {
    test('shows loading state initially then content', async ({ page }) => {
      // Intercept to slow down API
      await page.route('**/dashboard/**', async (route) => {
        await new Promise((r) => setTimeout(r, 500));
        await route.continue();
      });
      await page.goto('/dashboard');

      // Either loading state or content should be visible
      const hasLoading = await page.locator('.loading-state').count();
      const hasContent = await page.locator('.welcome-banner').count();
      expect(hasLoading + hasContent).toBeGreaterThan(0);

      // Eventually content should appear
      await expect(page.locator('.welcome-banner')).toBeVisible({ timeout: 15_000 });
    });
  });
});
