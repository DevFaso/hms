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
                hospitalName: 'Hôpital de démonstration',
                name: 'Alimata Démonstration',
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
                name: 'Hôpital de démonstration',
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
              userName: 'Alimata Démonstration',
              hospitalId: TEST_ID,
              hospitalName: 'Hôpital de démonstration',
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
          body: await page.screenshot({ animations: 'disabled' }),
          contentType: 'image/png',
        });
        await recovery.click();
        await expect(page.locator('[role="dialog"], dialog[open]').first()).toBeVisible();
        expect(assignmentWrites).toEqual([]);
        await testInfo.attach(`assignment-review-${width}`, {
          body: await page.screenshot({ animations: 'disabled' }),
          contentType: 'image/png',
        });
      });

      test(`hospital admin chooses a retired role and explicitly re-invites at ${width}`, async ({
        page,
      }, testInfo) => {
        await page.setViewportSize({ width, height: 1000 });
        await signInAs(page, 'ROLE_HOSPITAL_ADMIN', testInfo.project.use.baseURL as string);
        await installLocalIconFonts(page);
        await page.addInitScript(() => localStorage.setItem('lang', 'fr'));
        const other = '00000000-0000-0000-0000-000000004024';
        const writes: string[] = [];
        page.on('request', (request) => {
          if (/\/api\/assignments(?:[/?]|$)/.test(request.url()) && request.method() !== 'GET')
            writes.push(new URL(request.url()).pathname);
        });
        const row = {
          id: ASSIGNMENT,
          userId: SUBJECT,
          userName: 'Synthetic colleague',
          hospitalId: TEST_ID,
          hospitalName: 'Hospital A',
          roleId: 'nurse',
          roleCode: 'ROLE_NURSE',
          active: false,
          confirmationVerified: false,
        };
        await page.route(/\/api\/users(?:\?.*)?$/, (route) =>
          route.fulfill({
            json: {
              content: [
                {
                  id: SUBJECT,
                  firstName: 'Synthetic',
                  lastName: 'Colleague',
                  active: true,
                  accessActive: false,
                  manageable: false,
                  deleted: false,
                  roleCount: 0,
                  reactivationOptions: [
                    {
                      assignmentId: ASSIGNMENT,
                      hospitalName: 'Hospital A',
                      roleCode: 'ROLE_NURSE',
                    },
                    { assignmentId: other, hospitalName: 'Hospital A', roleCode: 'ROLE_MIDWIFE' },
                  ],
                },
              ],
              number: 0,
              totalPages: 1,
              totalElements: 1,
            },
          }),
        );
        await page.route(/\/api\/assignments(?:\?.*)?$/, (route) =>
          route.fulfill({
            json: {
              content: [],
              number: 0,
              totalPages: 0,
              totalElements: 0,
            },
          }),
        );
        await page.route(new RegExp('/api/assignments/' + ASSIGNMENT + '(?:\\?.*)?$'), (route) =>
          route.fulfill({ json: row }),
        );
        await page.route('**/api/assignments/' + ASSIGNMENT + '/regenerate-code**', (route) =>
          route.fulfill({ json: row }),
        );
        await page.route(/\/api\/roles(?:\?.*)?$/, (route) => route.fulfill({ json: [] }));
        await page.route(/\/api\/hospitals(?:\?.*)?$/, (route) => route.fulfill({ json: [] }));
        await page.goto('/users');
        const choices = page.locator('[data-testid="user-recovery-options"]');
        await choices.locator('summary').click();
        await expect(choices.locator('a')).toHaveCount(2);
        await expect(choices).toContainText('Hospital A');
        expect(writes).toEqual([]);
        await testInfo.attach(`recovery-choice-${width}`, {
          body: await page.screenshot({ animations: 'disabled' }),
          contentType: 'image/png',
        });
        await choices.locator('a').first().click();
        const reinvite = page.locator('[data-testid="assignment-reinvite"]');
        await expect(reinvite).toBeVisible();
        expect(writes).toEqual([]);
        await reinvite.click();
        await expect(page.locator('#admin-assignments-dialog-2-title')).toBeVisible();
        expect(writes).toEqual([]);
        await testInfo.attach(`reinvitation-confirm-${width}`, {
          body: await page.screenshot({ animations: 'disabled' }),
          contentType: 'image/png',
        });
        await page.locator('[role="dialog"] .modal-actions .btn-primary').click();
        await expect.poll(() => writes.length).toBe(1);
        expect(writes).toEqual(['/api/assignments/' + ASSIGNMENT + '/regenerate-code']);
      });

      test(`directory support eligibility is independent of the displayed role at ${width}`, async ({
        page,
      }, testInfo) => {
        await page.setViewportSize({ width, height: 1000 });
        await signInAs(page, 'ROLE_SUPER_ADMIN', testInfo.project.use.baseURL as string);
        await installLocalIconFonts(page);
        await page.addInitScript(() => localStorage.setItem('lang', 'fr'));
        const writes: string[] = [];
        page.on('request', (request) => {
          if (request.method() !== 'GET' && /\/impersonation(?:\/|$)/.test(request.url()))
            writes.push(request.url());
        });
        await page.route(/\/api\/users(?:\?.*)?$/, (route) =>
          route.fulfill({
            json: {
              content: [false, true].map((eligible, index) => ({
                id: index === 0 ? SUBJECT : STAFF,
                username: eligible ? 'demo.ordinary' : 'demo.protected',
                email: 'synthetic@example.test',
                firstName: 'Synthetic',
                lastName: eligible ? 'Ordinary' : 'Protected',
                active: true,
                accessActive: true,
                deleted: false,
                manageable: true,
                roleName: 'ROLE_NURSE',
                roleCount: 1,
                profileType: 'STAFF',
                impersonationEligible: eligible,
              })),
              number: 0,
              totalElements: 2,
              totalPages: 1,
            },
          }),
        );
        await page.goto('/users');
        await expect(page.locator('tbody tr')).toHaveCount(2);
        await expect(page.locator('[data-test="impersonate-demo.protected"]')).toHaveCount(0);
        await page.locator('[data-test="impersonate-demo.ordinary"]').click();
        await expect(page.locator('[data-test="impersonate-modal"]')).toBeVisible();
        expect(writes).toEqual([]);
        await testInfo.attach(`support-eligibility-${width}`, {
          body: await page.screenshot({ animations: 'disabled' }),
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
                  lastName: 'Démonstration',
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
          body: await page.screenshot({ animations: 'disabled' }),
          contentType: 'image/png',
        });
      });
    }
  });
}
