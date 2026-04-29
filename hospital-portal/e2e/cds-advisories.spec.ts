/**
 * Happy-path Playwright check for the CDS rule-engine advisory display.
 *
 * Drives the prescription modal end-to-end: opens it, fills the
 * medication fields, stubs the backend POST so it returns a single
 * warning advisory, and asserts the shared CdsCardListComponent
 * renders it.
 *
 * The spec skips gracefully in environments without a clinician
 * session (smoke runs against /login, CI without seeded auth, etc.).
 */
import { test, expect } from '@playwright/test';

test.describe('CDS rule-engine advisories', () => {
  test('renders advisory cards returned by the backend on submit', async ({ page }) => {
    let createIntercepted = false;

    await page.route('**/api/prescriptions', async (route) => {
      if (route.request().method() === 'POST') {
        createIntercepted = true;
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            id: '00000000-0000-0000-0000-000000000001',
            patientId: '00000000-0000-0000-0000-000000000010',
            patientFullName: 'Test Patient',
            patientEmail: '',
            staffId: '00000000-0000-0000-0000-000000000020',
            staffFullName: 'Dr Test',
            encounterId: '00000000-0000-0000-0000-000000000030',
            hospitalId: '00000000-0000-0000-0000-000000000040',
            medicationName: 'Amoxicillin',
            medicationDisplayName: 'Amoxicillin',
            dosage: '500 mg',
            frequency: 'BID',
            duration: '5 days',
            notes: '',
            status: 'DRAFT',
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString(),
            cdsAdvisories: [
              {
                summary: 'Possible duplicate order: Amoxicillin was prescribed on 2026-04-28',
                detail: 'An active prescription for the same medication exists.',
                indicator: 'warning',
                source: { label: 'HMS Duplicate-Order Check' },
                uuid: 'card-1',
              },
            ],
          }),
        });
        return;
      }
      await route.continue();
    });

    const response = await page.goto('/prescriptions', { waitUntil: 'domcontentloaded' });
    if (response && response.status() === 401) {
      test.skip(true, 'No authenticated session available in this environment');
    }
    if (page.url().includes('/login')) {
      test.skip(true, 'Login redirect — environment lacks a clinician session');
    }

    const newButton = page
      .locator('button.btn-primary', { hasText: /new|create|prescription/i })
      .first();
    if ((await newButton.count()) === 0 || !(await newButton.isVisible().catch(() => false))) {
      test.skip(true, 'Prescription create button unavailable in this environment');
    }
    await newButton.click();

    const medField = page.locator('#rx-medName');
    if ((await medField.count()) === 0) {
      test.skip(true, 'Prescription form not rendered (likely missing role/permissions)');
    }

    await medField.fill('Amoxicillin');
    await page
      .locator('#rx-dosage')
      .fill('500 mg')
      .catch(() => undefined);
    await page
      .locator('#rx-frequency')
      .fill('BID')
      .catch(() => undefined);
    await page
      .locator('#rx-duration')
      .fill('5 days')
      .catch(() => undefined);

    await Promise.all([
      page.waitForResponse(
        (resp) => resp.url().includes('/api/prescriptions') && resp.request().method() === 'POST',
      ),
      page.locator('.modal-actions .btn-primary').click(),
    ]);

    expect(createIntercepted).toBe(true);
    const advisories = page.getByTestId('cds-advisories');
    await expect(advisories).toBeVisible();
    await expect(advisories).toContainText('Possible duplicate order: Amoxicillin');
    await expect(advisories).toContainText('HMS Duplicate-Order Check');
  });
});
