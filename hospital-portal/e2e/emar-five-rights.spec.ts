/**
 * Happy-path Playwright check for the inpatient eMAR five-rights barcode-scan
 * loop (P1 #8).
 *
 * Routes every backend call required by the eMAR page so the test runs against
 * a stubbed surface — letting CI verify the UI without real auth or seeded
 * inpatient data. The flow is: load due doses → pick the first one → fill in
 * the four scan inputs → click Verify (server says all-pass) → click Record
 * GIVEN. The Karma spec covers component-level branches; this spec is here to
 * catch route, template, and binding regressions end-to-end.
 *
 * Skips gracefully when no clinician session is available (smoke runs).
 */
import { test, expect } from '@playwright/test';

test.describe('eMAR five-rights barcode-scan loop', () => {
  test('verifies all-pass and records GIVEN end-to-end', async ({ page }) => {
    const taskId = '00000000-0000-0000-0000-00000000aaaa';
    let verifyIntercepted = false;
    let administerIntercepted = false;

    await page.route('**/api/nurse/medications/mar*', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify([
            {
              id: taskId,
              patientId: '00000000-0000-0000-0000-00000000bbbb',
              patientName: 'Alice Patient',
              medication: 'Amoxicillin',
              dose: '500 mg',
              route: 'PO',
              dueTime: new Date().toISOString(),
              status: 'PENDING',
            },
          ]),
        });
        return;
      }
      await route.continue();
    });

    await page.route(`**/api/nurse/medications/mar/${taskId}/verify`, async (route) => {
      verifyIntercepted = true;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          marId: taskId,
          outcomes: { PATIENT: true, DRUG: true, DOSE: true, ROUTE: true, TIME: true },
          failedChecks: [],
          failureReasons: {},
          allPassed: true,
          verifiedAt: new Date().toISOString(),
        }),
      });
    });

    await page.route(`**/api/nurse/medications/mar/${taskId}/administer`, async (route) => {
      administerIntercepted = true;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          id: taskId,
          patientId: '00000000-0000-0000-0000-00000000bbbb',
          patientName: 'Alice Patient',
          medication: 'Amoxicillin',
          dose: '500 mg',
          route: 'PO',
          dueTime: new Date().toISOString(),
          status: 'GIVEN',
        }),
      });
    });

    const response = await page.goto('/emar', { waitUntil: 'domcontentloaded' });
    if (response && response.status() === 401) {
      test.skip(true, 'No authenticated session available in this environment');
    }
    if (page.url().includes('/login')) {
      test.skip(true, 'Login redirect — environment lacks a clinician session');
    }

    const queue = page.locator('[data-testid="emar-queue"]');
    if ((await queue.count()) === 0) {
      test.skip(true, 'eMAR did not render — environment lacks the nurse role');
    }

    await page.locator(`[data-testid="emar-task-${taskId}"]`).click();
    await page
      .locator('[data-testid="emar-patient-scan"]')
      .fill('00000000-0000-0000-0000-00000000bbbb');
    await page.locator('[data-testid="emar-med-scan"]').fill('AMOX-500');
    await page.locator('[data-testid="emar-dose"]').fill('500 mg');
    await page.locator('[data-testid="emar-route"]').fill('PO');

    await page.locator('[data-testid="emar-verify"]').click();
    await expect(page.locator('[data-testid="emar-right-PATIENT"]')).toBeVisible();
    expect(verifyIntercepted).toBe(true);

    await page.locator('[data-testid="emar-administer-given"]').click();
    await expect.poll(() => administerIntercepted).toBe(true);
  });
});
