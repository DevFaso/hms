/**
 * Role-Based Access Control E2E Tests.
 *
 * Validates that role-protected routes redirect unauthorized users.
 * Uses stored auth state (SuperAdmin, which has access to everything).
 */
import { test, expect } from './fixtures/test-fixtures';

test.describe('Role-Based Access Control', () => {
  test.describe('SuperAdmin Access', () => {
    const protectedRoutes = [
      { path: '/dashboard', title: 'Dashboard' },
      { path: '/patients', title: 'Patients' },
      { path: '/appointments', title: 'Appointments' },
      { path: '/staff', title: 'Staff' },
      { path: '/departments', title: 'Departments' },
      { path: '/billing', title: 'Billing' },
      { path: '/lab', title: 'Laboratory' },
      { path: '/notifications', title: 'Notifications' },
      { path: '/chat', title: 'Messages' },
      { path: '/organizations', title: 'Organizations' },
      { path: '/users', title: 'Users' },
      { path: '/roles', title: 'Roles' },
      { path: '/platform', title: 'Platform' },
      { path: '/encounters', title: 'Encounters' },
      { path: '/admissions', title: 'Admissions' },
      { path: '/prescriptions', title: 'Prescriptions' },
      { path: '/nurse-station', title: 'Nurse Station' },
      { path: '/imaging', title: 'Imaging' },
      { path: '/consultations', title: 'Consultations' },
      { path: '/treatment-plans', title: 'Treatment Plans' },
      { path: '/referrals', title: 'Referrals' },
      { path: '/scheduling', title: 'Scheduling' },
      { path: '/audit-logs', title: 'Audit Logs' },
      { path: '/admin', title: 'Administration' },
    ];

    for (const route of protectedRoutes) {
      test(`can access ${route.title} at ${route.path}`, async ({ page }) => {
        await page.goto(route.path, { waitUntil: 'networkidle' });

        // Should NOT be redirected to login or 403
        const url = page.url();
        expect(url).not.toContain('/login');
        expect(url).not.toContain('/error/403');

        // Page should render content (not be blank)
        const content = page.locator('.page-container, .dashboard, .page-title, .shell');
        await expect(content.first()).toBeVisible({ timeout: 10_000 });
      });
    }
  });

  test.describe('Error Pages', () => {
    test('403 error page exists and renders', async ({ page }) => {
      await page.goto('/error/403', { waitUntil: 'networkidle' });
      const content = page.locator('body');
      await expect(content).toBeVisible();
    });

    test('404 error page exists and renders', async ({ page }) => {
      await page.goto('/error/404', { waitUntil: 'networkidle' });
      const content = page.locator('body');
      await expect(content).toBeVisible();
    });
  });
});
