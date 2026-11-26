import { test, expect } from '../fixtures/test-fixtures';
import { installLocalIconFonts } from './local-icon-fonts';
import { signInAs, TEST_ID } from './role-session';

const SUBJECT = '00000000-0000-0000-0000-000000004022';
const STAFF = '00000000-0000-0000-0000-000000004023';
const ASSIGNMENT = '00000000-0000-0000-0000-000000004021';

/** Populated management views; every response is synthetic and external traffic is blocked by signInAs. */
export function registerStaffStateLayoutChecks(): void {
  test.describe('D4021: visible inactive staff and governed reactivation', () => {
    test.use({ storageState: { cookies: [], origins: [] } });
    for (const width of [390, 1440]) {
      test(`staff badge, recovery and assignment review at ${width}`, async ({
        page,
      }, testInfo) => {
        await page.setViewportSize({ width, height: 1000 });
        await signInAs(page, 'ROLE_SUPER_ADMIN', testInfo.project.use.baseURL as string);
        await installLocalIconFonts(page);
        await page.addInitScript(() => localStorage.setItem('lang', 'fr'));
        let active = true;
        const assignmentWrites: string[] = [];
        page.on('request', (request) => {
          if (/\/api\/assignments(?:[/?]|$)/.test(request.url()) && request.method() !== 'GET') {
            assignmentWrites.push(request.method() + ' ' + new URL(request.url()).pathname);
          }
        });
        await page.route(/\/api\/staff(?:[/?]|$)/, (route) => {
          if (route.request().method() === 'DELETE') {
            active = false;
            return route.fulfill({ status: 204 });
          }
          return route.fulfill({
            json: [
              {
                id: STAFF,
                userId: SUBJECT,
                assignmentId: ASSIGNMENT,
                hospitalId: TEST_ID,
                hospitalName: 'H?pital de d?monstration',
                name: 'Alimata D?monstration',
                username: 'demo.staff',
                email: 'staff@example.test',
                jobTitle: 'NURSE',
                roleCode: 'ROLE_NURSE',
                employmentType: 'FULL_TIME',
                active,
                createdAt: '2026-11-25T10:00:00',
              },
            ],
          });
        });
        await page.route(/\/api\/hospitals(?:\?.*)?$/, (route) =>
          route.fulfill({
            json: [
              {
                id: TEST_ID,
                name: 'H?pital de d?monstration',
                code: 'DEMO',
                active: true,
                facilityType: 'HOSPITAL',
                lifecycleState: 'ACTIVE',
              },
            ],
          }),
        );
        await page.route(/\/api\/roles(?:\?.*)?$/, (route) =>
          route.fulfill({
            json: [
              { id: 'r-nurse', code: 'ROLE_NURSE', name: 'ROLE_NURSE', authority: 'ROLE_NURSE' },
            ],
          }),
        );
        await page.route(new RegExp('/api/assignments/' + ASSIGNMENT + '(?:\\?.*)?$'), (route) =>
          route.fulfill({
            json: {
              id: ASSIGNMENT,
              userId: SUBJECT,
              userName: 'Alimata D?monstration',
              hospitalId: TEST_ID,
              hospitalName: 'H?pital de d?monstration',
              roleId: 'r-nurse',
              roleCode: 'ROLE_NURSE',
              roleName: 'ROLE_NURSE',
              active: false,
              confirmationVerified: false,
            },
          }),
        );
        await page.goto('/staff');
        await page.locator('[data-test="staff-deactivate"]').click();
        await page.locator('[data-test="staff-deactivate-confirm"]').click();
        await expect(page.locator('.staff-status.inactive')).toBeVisible();
        await expect(page.locator('[data-test="staff-deactivate"]')).toHaveCount(0);
        const recovery = page.locator('[data-test="staff-reactivate"]');
        await expect(recovery).toHaveAttribute(
          'href',
          '/admin-assignments?reactivate=' + ASSIGNMENT,
        );
        await testInfo.attach(`staff-inactive-${width}`, {
          body: await page.screenshot(),
          contentType: 'image/png',
        });
        await recovery.click();
        await expect(page.locator('[role="dialog"], dialog[open]').first()).toBeVisible();
        expect(assignmentWrites).toEqual([]);
        await testInfo.attach(`assignment-review-${width}`, {
          body: await page.screenshot(),
          contentType: 'image/png',
        });
      });

      test(`directory inactive filter uses hospital access at ${width}`, async ({
        page,
      }, testInfo) => {
        await page.setViewportSize({ width, height: 1000 });
        await signInAs(page, 'ROLE_SUPER_ADMIN', testInfo.project.use.baseURL as string);
        await installLocalIconFonts(page);
        await page.addInitScript(() => localStorage.setItem('lang', 'fr'));
        await page.route(/\/api\/users(?:\?.*)?$/, (route) =>
          route.fulfill({
            json: {
              content: [
                {
                  id: SUBJECT,
                  username: 'demo.staff',
                  email: 'staff@example.test',
                  firstName: 'Alimata',
                  lastName: 'D?monstration',
                  active: true,
                  accessActive: false,
                  roleName: null,
                  roleCount: 0,
                  deleted: false,
                  profileType: 'STAFF',
                  manageable: true,
                  reactivationAssignmentId: ASSIGNMENT,
                },
              ],
              number: 0,
              totalElements: 1,
              totalPages: 1,
            },
          }),
        );
        await page.goto('/users');
        await page.locator('.inactive-pill').click();
        await expect(page.locator('tbody .status-badge.inactive')).toBeVisible();
        await expect(page.locator('[data-testid="user-reactivate-access"]')).toHaveAttribute(
          'href',
          '/admin-assignments?reactivate=' + ASSIGNMENT,
        );
        await expect(page.locator('.access-hint')).toBeVisible();
        await testInfo.attach(`directory-inactive-${width}`, {
          body: await page.screenshot(),
          contentType: 'image/png',
        });
      });
    }
  });
}
