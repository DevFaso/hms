import { test, expect } from './fixtures/test-fixtures';
import { signInAs, TEST_ID } from './helpers/role-session';
import { installLocalIconFonts } from './helpers/local-icon-fonts';

test.use({ storageState: { cookies: [], origins: [] } });
test('own request reaches the hospital queue, needs a reason, then downloads the approved PDF', async ({
  page,
  baseURL,
}, info) => {
  await signInAs(page, 'ROLE_PATIENT', baseURL!);
  await installLocalIconFonts(page);
  let request: Record<string, unknown> | null = null;
  await page.route('**/api/me/patient/health-summary', (route) =>
    route.fulfill({
      json: {
        data: {
          profile: {
            firstName: 'cdx',
            lastName: 'Patient',
            dateOfBirth: '1990-01-01',
            hospitalRecords: [
              {
                hospitalId: TEST_ID,
                hospitalName:
                  'cdx Centre hospitalier régional de Ouagadougou — consultation et suivi des dossiers',
              },
            ],
          },
          allergies: [],
          activeDiagnoses: [],
          recentLabResults: [],
          currentMedications: [],
          latestVitals: [],
          immunizations: [],
        },
      },
    }),
  );
  await page.route('**/api/me/patient/roi-requests', (route) => {
    if (route.request().method() === 'POST') {
      expect(route.request().postDataJSON()).toEqual({
        hospitalId: TEST_ID,
        requestKind: 'COPY',
        purpose: 'cdx own copy',
        scopeDescription: 'FULL_RECORD',
      });
      request = {
        ...route.request().postDataJSON(),
        id: TEST_ID,
        patientId: TEST_ID,
        patientName: 'cdx Patient',
        requesterType: 'PATIENT',
        requesterName: 'cdx Patient',
        hospitalName:
          'cdx Centre hospitalier régional de Ouagadougou — consultation et suivi des dossiers',
        status: 'PENDING',
        requestedOn: '2026-11-23',
        dueOn: '2026-12-23',
      };
      return route.fulfill({ json: request });
    }
    return route.fulfill({ json: request ? [request] : [] });
  });
  await page.goto('/my-records');
  await page.locator('#rights-hospital').selectOption(TEST_ID);
  await page.locator('#rights-purpose').fill('cdx own copy');
  await page.getByTestId('record-rights').locator('button[type="submit"]').click();
  await expect(page.getByTestId('record-rights').locator('article')).toContainText('cdx own copy');
  for (const width of [390, 768, 1024, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(
      true,
    );
    const fits = await page
      .getByTestId('record-rights')
      .locator('input, select, textarea')
      .evaluateAll((controls) =>
        controls.every((el) => {
          const box = el.getBoundingClientRect();
          const form = el.closest('form')!.getBoundingClientRect();
          return box.left >= form.left && box.right <= form.right;
        }),
      );
    expect(fits).toBe(true);
    const screenshot = info.outputPath(`request-${width}.png`);
    await page.screenshot({ path: screenshot, fullPage: true });
    if (width === 390 || width === 1440)
      await info.attach(`record-request-${width}`, { path: screenshot, contentType: 'image/png' });
  }
  await signInAs(page, 'ROLE_DOCTOR', baseURL!);
  await page.route('**/api/roi-requests**', (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith('/record.pdf')) {
      expect(request?.['status']).toBe('FULFILLED');
      return route.fulfill({
        contentType: 'application/pdf',
        body: '%PDF-1.4 cdx-mocked-export',
        headers: { 'Cache-Control': 'no-store' },
      });
    }
    if (route.request().method() === 'PUT') {
      expect(route.request().postDataJSON()).toEqual({
        note: 'cdx identity and full-copy scope checked',
      });
      request = {
        ...request,
        status: 'FULFILLED',
        decisionNote: 'cdx identity and full-copy scope checked',
        decidedAt: '2026-11-23T09:00:00',
        decidedByName: 'cdx Clinician',
      };
      return route.fulfill({ json: request });
    }
    const rows = request?.['status'] === url.searchParams.get('status') ? [request] : [];
    return route.fulfill({
      json: { content: rows, totalElements: rows.length, totalPages: 1, number: 0, size: 200 },
    });
  });
  await page.goto('/roi');
  await expect(page.locator('tbody')).toContainText('cdx own copy');
  await page.getByTestId('fulfil-btn').click();
  await page.getByTestId('submit-decision').click();
  expect(request?.['status']).toBe('PENDING');
  await page
    .locator('textarea[name="roiDecisionNote"]')
    .fill('cdx identity and full-copy scope checked');
  await page.getByTestId('submit-decision').click();
  await expect(page.getByTestId('decision-modal')).toHaveCount(0);
  await page.locator('.tabs button').nth(2).click();
  const downloaded = page.waitForEvent('download');
  await page.getByTestId('roi-export-copy').click();
  expect((await downloaded).suggestedFilename()).toBe('record-copy-2026-11-23.pdf');
});
