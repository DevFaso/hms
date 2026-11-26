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
        sessionStorage.setItem('auth_token', `eyJhbGciOiJIUzI1NiJ9.${payload}.review-signature`);
        sessionStorage.setItem('user_profile', JSON.stringify(profile));
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
          totalLabResults: 11,
          totalLabTestDefinitions: 10,
          totalTreatmentPlans: 1,
          totalReferrals: 1,
          generatedAt: '2026-11-17T15:42:00Z',
          recentAuditEvents: [
            {
              id: 'routine',
              eventType: 'TOKEN_REFRESH',
              status: 'SUCCESS',
              userName: 'Routine actor',
              resourceName: 'Unknown Resource',
              eventTimestamp: '2026-11-17T15:40:00Z',
            },
            {
              id: 'failure',
              eventType: 'TOKEN_REFRESH',
              status: 'FAILURE',
              userName: 'Failed actor',
              resourceName: 'BILLING_INVOICE',
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
    await page.route('**/api/super-admin/audit-search**', (route) => {
      // The filters travel in the POST body, never in the URL.
      const filter = (route.request().postDataJSON() ?? {}) as Record<string, unknown>;
      const rows = [
        {
          id: 'routine',
          eventType: 'TOKEN_REFRESH',
          status: 'SUCCESS',
          userName: 'Routine actor',
          resourceName: 'Unknown Resource',
          eventTimestamp: '2026-11-17T15:40:00Z',
        },
        {
          id: 'failure-outside-summary',
          eventType: 'DATA_ACCESS',
          status: 'FAILURE',
          userName: 'Failed actor',
          resourceName: 'BILLING_INVOICE',
          hospitalName: 'Hospital B',
          eventTimestamp: '2026-11-17T15:41:00Z',
        },
      ].filter(
        (row) =>
          (filter['excludeRoutineSuccesses'] !== true || row.status !== 'SUCCESS') &&
          (!filter['status'] || row.status === filter['status']),
      );
      return route.fulfill({
        json: {
          content: rows,
          totalElements: rows.length,
          pageNumber: 0,
          // The page stays in the query.
          pageSize: Number(new URL(route.request().url()).searchParams.get('size')),
          totalPages: 1,
        },
      });
    });
    await page.route('**/api/super-admin/recent-activity**', (route) =>
      route.fulfill({
        json: Object.fromEntries(
          [
            'consultations',
            'admissions',
            'prescriptions',
            'labOrders',
            'labResults',
            'labTestDefinitions',
            'treatmentPlans',
            'referrals',
          ].map((key) => [
            key,
            [
              {
                id: key,
                patientName: 'Review patient',
                patientFullName: 'Review patient',
                name: 'Shared test',
                hospitalName: 'Hospital B',
                patientMrn: 'MRN-0042',
                status: 'ACTIVE',
                medicationName: 'Medicine',
                labTestName: 'Test',
                released: true,
                [(
                  {
                    consultations: 'requestedAt',
                    admissions: 'admissionDateTime',
                    prescriptions: 'createdAt',
                    labOrders: 'orderDatetime',
                    labResults: 'resultDate',
                    labTestDefinitions: 'createdAt',
                    treatmentPlans: 'timelineStartDate',
                    referrals: 'submittedAt',
                  } as Record<string, string>
                )[key]]: key === 'treatmentPlans' ? '2026-11-17' : '2026-11-17T15:00:00Z',
              },
            ],
          ]),
        ),
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
      // QA r65 D2777: the quick-link directory repeated the sidebar and is gone.
      await expect(screen.locator('.admin-directory')).toHaveCount(0);
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
      for (const tab of await tabs.all()) {
        await tab.click();
        await expect(screen.locator('.activity-row')).toHaveCount(1);
        await expect(screen.locator('.activity-subject')).toContainText('MRN-0042');
        await expect(screen.locator('.activity-timestamp .field-label')).not.toBeEmpty();
        if ((await tab.getAttribute('id')) === 'activity-tab-treatmentPlans') {
          await expect(screen.locator('.activity-timestamp span:last-child')).not.toContainText(
            ':',
          );
        }
        await expect(screen.locator('.activity-toolbar a')).toHaveAttribute('href', /^\//);
      }
      await tabs.first().focus();
      await page.keyboard.press('End');
      await expect(tabs.last()).toBeFocused();
      await expect(tabs.last()).toHaveAttribute('aria-selected', 'true');
      const activityBox = await screen.locator('.clinical-activity').boundingBox();
      const auditBox = await screen.locator('.audit-panel').boundingBox();
      expect(auditBox!.y).toBeGreaterThanOrEqual(activityBox!.y + activityBox!.height);
      for (const selector of ['.activity-list', '.audit-list']) {
        expect(
          await screen.locator(selector).evaluate((element) => {
            const style = getComputedStyle(element);
            return ['auto', 'scroll'].includes(style.overflowY);
          }),
        ).toBe(false);
      }
      await expect(screen.locator('.audit-list')).toContainText('Failed actor');
      await expect(screen.locator('.audit-list')).not.toContainText('Routine actor');
      await screen.locator('.audit-routine input').click();
      await expect(screen.locator('.audit-list')).toContainText('Routine actor');
      await expect(screen.locator('.audit-details')).toHaveCount(1);
      await screen.locator('.audit-details summary').click();
      await expect(screen.locator('.audit-details code')).toHaveText('Facture');
      expect(
        await screen
          .locator('.audit-row')
          .first()
          .evaluate((element) => getComputedStyle(element).columnGap),
      ).not.toBe('normal');
      await page.screenshot({
        path: testInfo.outputPath(`control-tower-${width}.png`),
        fullPage: true,
      });
    });
  }

  test('audit history queries carry filters into full search and expand the server page', async ({
    page,
  }) => {
    await page.route('**/api/super-admin/audit-search**', (route) => {
      const params = new URL(route.request().url()).searchParams;
      const size = Number(params.get('size'));
      const content = Array.from({ length: size }, (_, i) => ({
        id: `history-${i}`,
        userName: 'History actor',
        eventType: 'DATA_ACCESS',
        status: 'FAILURE',
        eventTimestamp: '2026-11-17T15:40:00Z',
      }));
      return route.fulfill({
        json: {
          content,
          totalElements: 23,
          totalPages: Math.ceil(23 / size),
          pageNumber: 0,
          pageSize: size,
        },
      });
    });
    await page.goto('/super-admin');
    const screen = page.locator('app-super-admin');
    const list = screen.locator('.audit-list');
    await expect(list.locator('.audit-row')).toHaveCount(5);
    await screen.locator('.audit-filters button').nth(1).click();
    const link = list.locator('a.audit-footer');
    await expect(link).toHaveAttribute('href', /status=FAILURE/);
    await expect(link).toHaveAttribute('href', /excludeRoutineSuccesses=true/);
    await expect(link).toHaveAttribute('href', /fromDate=/);
    await list.locator('button[aria-expanded]').click();
    await expect(list.locator('.audit-row')).toHaveCount(10);
    await screen.locator('.audit-period select').selectOption({ label: '7 derniers jours' });
    await expect(list.locator('.audit-row')).toHaveCount(5);
  });

  test('failed sources are visibly unavailable, while administration remains accessible', async ({
    page,
  }) => {
    await page.route('**/api/super-admin/audit-search**', (route) =>
      route.fulfill({ status: 503, json: {} }),
    );
    await page.route('**/api/super-admin/integrations', (route) =>
      route.fulfill({ status: 503, json: {} }),
    );
    await page.route('**/api/super-admin/recent-activity**', (route) =>
      route.fulfill({ status: 503, json: {} }),
    );
    await page.goto('/super-admin');
    const screen = page.locator('app-super-admin');
    // QA r65 D2777: the quick-link directory repeated the sidebar and is gone.
    await expect(screen.locator('.admin-directory')).toHaveCount(0);
    await expect(screen.locator('.health-grid')).toHaveCount(0);
    await expect(screen.locator('.integration-overview .availability-note')).toBeVisible();
    await expect(screen.locator('.activity-list .availability-note')).toBeVisible();
    await expect(screen.locator('.activity-list .activity-empty')).toHaveCount(0);
    await expect(screen.locator('.audit-list .availability-note')).toBeVisible();
  });
});
