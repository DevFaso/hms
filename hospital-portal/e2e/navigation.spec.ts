/**
 * Navigation E2E Tests – Sidebar navigation, routing, shell behavior.
 *
 * These tests use stored auth state (chromium project).
 */
import { test, expect } from './fixtures/test-fixtures';

test.describe('Shell & Navigation', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/dashboard', { waitUntil: 'networkidle' });
  });

  test.describe('Shell Rendering', () => {
    test('shell layout is visible with sidebar and topbar', async ({ shellPage }) => {
      await shellPage.expectShellVisible();
    });

    test('sidebar shows HMS logo', async ({ shellPage }) => {
      await expect(shellPage.logoText).toBeVisible();
      await expect(shellPage.logoText).toContainText('HMS');
    });

    test('topbar displays page title', async ({ shellPage }) => {
      await expect(shellPage.pageTitle).toBeVisible();
      await expect(shellPage.pageTitle).toContainText('Hospital Management System');
    });

    test('profile button shows user name', async ({ shellPage }) => {
      await expect(shellPage.profileBtn).toBeVisible();
    });

    test('notification button is visible', async ({ shellPage }) => {
      await expect(shellPage.notificationBtn).toBeVisible();
    });
  });

  test.describe('Sidebar Navigation', () => {
    test('SuperAdmin sees all navigation items', async ({ shellPage }) => {
      const labels = await shellPage.getNavLabels();
      expect(labels).toContain('Dashboard');
      expect(labels).toContain('Patients');
      expect(labels).toContain('Appointments');
      expect(labels).toContain('Staff');
      expect(labels).toContain('Departments');
      expect(labels).toContain('Billing');
      expect(labels).toContain('Laboratory');
      expect(labels).toContain('Notifications');
      expect(labels).toContain('Messages');
      expect(labels).toContain('Organizations');
      expect(labels).toContain('Users');
      expect(labels).toContain('Roles');
      expect(labels).toContain('Platform');
    });

    test('clicking Dashboard navigates to /dashboard', async ({ page, shellPage }) => {
      await shellPage.navigateTo('Dashboard');
      await expect(page).toHaveURL(/\/dashboard/);
    });

    test('clicking Patients navigates to /patients', async ({ page, shellPage }) => {
      await shellPage.navigateTo('Patients');
      await expect(page).toHaveURL(/\/patients/);
      await expect(page.locator('main .page-title')).toContainText('Patients');
    });

    test('clicking Staff navigates to /staff', async ({ page, shellPage }) => {
      await shellPage.navigateTo('Staff');
      await expect(page).toHaveURL(/\/staff/);
    });

    test('clicking Departments navigates to /departments', async ({ page, shellPage }) => {
      await shellPage.navigateTo('Departments');
      await expect(page).toHaveURL(/\/departments/);
    });

    test('clicking Appointments navigates to /appointments', async ({ page, shellPage }) => {
      await shellPage.navigateTo('Appointments');
      await expect(page).toHaveURL(/\/appointments/);
    });

    test('clicking Billing navigates to /billing', async ({ page, shellPage }) => {
      await shellPage.navigateTo('Billing');
      await expect(page).toHaveURL(/\/billing/);
    });

    test('clicking Laboratory navigates to /lab', async ({ page, shellPage }) => {
      await shellPage.navigateTo('Laboratory');
      await expect(page).toHaveURL(/\/lab/);
    });

    test('clicking Notifications navigates to /notifications', async ({ page, shellPage }) => {
      await shellPage.navigateTo('Notifications');
      await expect(page).toHaveURL(/\/notifications/);
    });

    test('clicking Messages navigates to /chat', async ({ page, shellPage }) => {
      await shellPage.navigateTo('Messages');
      await expect(page).toHaveURL(/\/chat/);
    });

    test('clicking Organizations navigates to /organizations', async ({ page, shellPage }) => {
      await shellPage.navigateTo('Organizations');
      await expect(page).toHaveURL(/\/organizations/);
    });

    test('clicking Users navigates to /users', async ({ page, shellPage }) => {
      await shellPage.navigateTo('Users');
      await expect(page).toHaveURL(/\/users/);
    });

    test('clicking Roles navigates to /roles', async ({ page, shellPage }) => {
      await shellPage.navigateTo('Roles');
      await expect(page).toHaveURL(/\/roles/);
    });

    test('clicking Platform navigates to /platform', async ({ page, shellPage }) => {
      await shellPage.navigateTo('Platform');
      await expect(page).toHaveURL(/\/platform/);
    });

    test('clicking Encounters navigates to /encounters', async ({ page, shellPage }) => {
      await shellPage.navigateTo('Encounters');
      await expect(page).toHaveURL(/\/encounters/);
    });

    test('clicking Admissions navigates to /admissions', async ({ page, shellPage }) => {
      await shellPage.navigateTo('Admissions');
      await expect(page).toHaveURL(/\/admissions/);
    });

    test('clicking Prescriptions navigates to /prescriptions', async ({ page, shellPage }) => {
      await shellPage.navigateTo('Prescriptions');
      await expect(page).toHaveURL(/\/prescriptions/);
    });
  });

  test.describe('Sidebar Collapse', () => {
    test('toggle collapses sidebar', async ({ shellPage }) => {
      const initialCollapsed = await shellPage.isSidebarCollapsed();
      await shellPage.toggleSidebar();
      const newCollapsed = await shellPage.isSidebarCollapsed();
      expect(newCollapsed).toBe(!initialCollapsed);
    });

    test('collapsed sidebar hides nav labels', async ({ page, shellPage }) => {
      // Ensure expanded first
      if (await shellPage.isSidebarCollapsed()) {
        await shellPage.toggleSidebar();
      }
      await expect(page.locator('.nav-label').first()).toBeVisible();

      // Collapse
      await shellPage.toggleSidebar();
      await expect(page.locator('.nav-label').first()).not.toBeVisible();
    });

    test('collapsed sidebar hides logo text', async ({ shellPage }) => {
      if (await shellPage.isSidebarCollapsed()) {
        await shellPage.toggleSidebar();
      }
      await expect(shellPage.logoText).toBeVisible();
      await shellPage.toggleSidebar();
      await expect(shellPage.logoText).not.toBeVisible();
    });
  });

  test.describe('Notifications Panel', () => {
    test('opens notification panel on click', async ({ shellPage }) => {
      await shellPage.openNotifications();
      await expect(shellPage.notifPanel).toBeVisible();
    });

    test('closes notification panel', async ({ shellPage }) => {
      await shellPage.openNotifications();
      await shellPage.closeNotifications();
      await expect(shellPage.notifPanel).not.toBeVisible();
    });
  });

  test.describe('Error Pages', () => {
    test('navigating to unknown route redirects', async ({ page }) => {
      await page.goto('/some-nonexistent-route');
      // Catch-all redirects to login, which may further redirect authenticated users to dashboard
      await page.waitForURL(/\/(login|dashboard)/, { timeout: 10_000 });
    });
  });
});
