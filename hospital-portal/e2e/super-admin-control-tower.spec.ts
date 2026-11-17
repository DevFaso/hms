import { test, expect } from './fixtures/test-fixtures';

const ID = '00000000-0000-0000-0000-000000000101';
const profile = {
  id: ID,
  username: 'control-review',
  roles: ['ROLE_SUPER_ADMIN'],
  active: true,
  hospitalIds: [],
};

test.describe('super-admin control tower', () => {
  test.use({ storageState: { cookies: [], origins: [] } });
  test.beforeEach(async ({ page }) => {
    await page.addInitScript(
      ({ profile }) => {
        const payload = btoa(
          JSON.stringify({ sub: profile.username, roles: profile.roles, exp: 9999999999 }),
        );
        localStorage.setItem('auth_token', `eyJhbGciOiJIUzI1NiJ9.${payload}.review-signature`);
        localStorage.setItem('user_profile', JSON.stringify(profile));
      },
      { profile },
    );
    await page.route('**/api/auth/session/bootstrap', (route) =>
      route.fulfill({
        json: {
          ...profile,
          userId: ID,
          authSource: 'internal',
          superAdmin: true,
          hospitalAdmin: false,
          permittedHospitalIds: [],
        },
      }),
    );
    await page.route('**/api/super-admin/summary**', (route) =>
      route.fulfill({
        json: {
          totalOrganizations: 2,
          activeOrganizations: 2,
          totalHospitals: 2,
          activeHospitals: 2,
          totalUsers: 121,
          activeUsers: 31,
          totalPatients: 85,
          totalDepartments: 14,
          totalRoles: 27,
          totalAssignments: 123,
          activeAssignments: 33,
          todayAppointmentsCount: 5,
          totalConsultations: 56,
          totalAdmissions: 16,
          totalPrescriptions: 75,
          totalLabOrders: 26,
          generatedAt: '2026-11-17T15:42:00Z',
          recentAuditEvents: [
            {
              id: 'routine',
              eventType: 'TOKEN_REFRESH',
              status: 'SUCCESS',
              userName: 'Routine actor',
              eventTimestamp: '2026-11-17T15:40:00Z',
            },
            {
              id: 'failure',
              eventType: 'TOKEN_REFRESH',
              status: 'FAILURE',
              userName: 'Failed actor',
              eventTimestamp: '2026-11-17T15:41:00Z',
            },
          ],
        },
      }),
    );
    await page.route('**/api/super-admin/platform/registry/summary', (route) =>
      route.fulfill({
        json: {
          actions: {
            totalIntegrations: 4,
            pendingIntegrations: 1,
            disabledLinks: 0,
            activeReleaseWindows: 0,
          },
        },
      }),
    );
    await page.route('**/api/super-admin/integrations', (route) =>
      route.fulfill({
        json: {
          totalIntegrations: 4,
          healthyCount: 1,
          degradedCount: 1,
          failingCount: 1,
          noHistoryCount: 1,
          integrations: [],
        },
      }),
    );
    await page.route('**/api/super-admin/recent-activity**', (route) =>
      route.fulfill({
        json: {
          consultations: [
            { id: 'record', patientName: 'Review patient', createdAt: '2026-11-17T15:00:00Z' },
          ],
        },
      }),
    );
  });

  for (const width of [1920, 1280, 390, 320]) {
    test(`admin controls and all activity tabs reflow at ${width}px`, async ({
      page,
    }, testInfo) => {
      await page.setViewportSize({ width, height: 1000 });
      await page.goto('/super-admin');
      const screen = page.locator('app-super-admin');
      await expect(screen.locator('.admin-directory a')).toHaveCount(13);
      await expect(screen.locator('.health-card')).toHaveCount(4);
      await expect(screen.locator('#integration-heading')).toHaveCSS('color', 'rgb(23, 45, 67)');
      await page.screenshot({
        path: testInfo.outputPath(`control-tower-top-${width}.png`),
        fullPage: true,
      });
      const tabs = screen.getByRole('tab');
      await expect(tabs).toHaveCount(8);
      for (const tab of await tabs.all()) {
        const bounds = await tab.boundingBox();
        expect(bounds).not.toBeNull();
        expect(bounds!.x).toBeGreaterThanOrEqual(0);
        expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(width + 1);
      }
      expect(
        await screen
          .locator('.activity-tabs')
          .evaluate((element) => element.scrollWidth <= element.clientWidth + 1),
      ).toBeTruthy();
      await tabs.first().focus();
      await page.keyboard.press('End');
      await expect(tabs.last()).toBeFocused();
      await expect(tabs.last()).toHaveAttribute('aria-selected', 'true');
      await expect(screen.locator('.audit-list')).toContainText('Failed actor');
      await expect(screen.locator('.audit-list')).not.toContainText('Routine actor');
      await screen.locator('.routine-toggle').click();
      await expect(screen.locator('.audit-list')).toContainText('Routine actor');
      expect(
        await screen
          .locator('.audit-line')
          .first()
          .evaluate((element) => getComputedStyle(element).columnGap),
      ).not.toBe('normal');
      await page.screenshot({
        path: testInfo.outputPath(`control-tower-${width}.png`),
        fullPage: true,
      });
    });
  }

  test('an all-routine audit sample is filtered, not reported as absent', async ({ page }) => {
    await page.route('**/api/super-admin/summary**', (route) =>
      route.fulfill({
        json: {
          recentAuditEvents: Array.from({ length: 10 }, (_, index) => ({
            id: `routine-${index}`,
            eventType: 'TOKEN_REFRESH',
            status: 'SUCCESS',
            userName: `Routine actor ${index}`,
            eventTimestamp: '2026-11-17T15:40:00Z',
          })),
        },
      }),
    );
    await page.goto('/super-admin');
    const screen = page.locator('app-super-admin');
    const list = screen.locator('.audit-list');
    await expect(list).toContainText(
      'Aucun \u00e9v\u00e9nement de cet \u00e9chantillon r\u00e9cent ne correspond au filtre.',
    );
    await expect(list.locator('.audit-row')).toHaveCount(0);
    await screen.locator('.routine-toggle').click();
    await expect(list.locator('.audit-row')).toHaveCount(10);
    await expect(list.locator('.activity-empty')).toHaveCount(0);
    await screen.locator('.routine-toggle').click();
    await expect(list.locator('.audit-row')).toHaveCount(0);
    await expect(list).toContainText(
      'Aucun \u00e9v\u00e9nement de cet \u00e9chantillon r\u00e9cent ne correspond au filtre.',
    );
  });

  test('failed sources are visibly unavailable, while administration remains accessible', async ({
    page,
  }) => {
    await page.route('**/api/super-admin/integrations', (route) =>
      route.fulfill({ status: 503, json: {} }),
    );
    await page.route('**/api/super-admin/recent-activity**', (route) =>
      route.fulfill({ status: 503, json: {} }),
    );
    await page.goto('/super-admin');
    const screen = page.locator('app-super-admin');
    await expect(screen.locator('.admin-directory a')).toHaveCount(13);
    await expect(screen.locator('.health-grid')).toHaveCount(0);
    await expect(screen.locator('.integration-overview .availability-note')).toBeVisible();
    await expect(screen.locator('.activity-list .availability-note')).toBeVisible();
    await expect(screen.locator('.activity-list .activity-empty')).toHaveCount(0);
  });
});
