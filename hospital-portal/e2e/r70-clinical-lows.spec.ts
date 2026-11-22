import { test, expect } from './fixtures/test-fixtures';
import { signInAs, TEST_ID } from './helpers/role-session';
import { installLocalIconFonts } from './helpers/local-icon-fonts';
import { heightFor, LAYOUT_WIDTHS } from './helpers/layout-audit';

test.use({ storageState: { cookies: [], origins: [] } });
test('D3310/D3312: a newborn without a neonatal cot and a wide partograph at four widths', async ({
  page,
  baseURL,
}, testInfo) => {
  await signInAs(page, 'ROLE_MIDWIFE', baseURL!);
  await installLocalIconFonts(page);
  const patient = {
    id: TEST_ID,
    firstName: 'Local',
    lastName: 'Patient',
    dateOfBirth: '2000-01-01',
    mrn: 'LOCAL-001',
    hospitalId: TEST_ID,
  };
  await page.route('**/api/patients/search**', (r) => r.fulfill({ json: [patient] }));
  await page.route('**/api/patients/*/labor/episodes?*', (r) =>
    r.fulfill({
      json: [
        {
          id: 'episode',
          patientId: TEST_ID,
          hospitalId: TEST_ID,
          status: 'DELIVERED',
          deliveryRecorded: true,
          admittedAt: '2026-11-22T08:00:00',
        },
      ],
    }),
  );
  await page.route('**/api/patients/*/labor/episodes/*/entries', (r) =>
    r.fulfill({
      json: [
        {
          id: 'entry',
          observationTime: '2026-11-22T08:10:00',
          fetalHeartRateBpm: 140,
          notes: 'Local observation with a long note to inspect table scrolling.',
          alerts: [],
        },
      ],
    }),
  );
  await page.route('**/api/patients/*/labor/episodes/*/delivery', (r) =>
    r.fulfill({
      json: {
        id: 'delivery',
        birthDateTime: '2026-11-22T09:00:00',
        deliveryMode: 'VAGINAL',
        newborns: [{ patientId: 'newborn', displayName: 'Local Newborn', mrn: 'LOCAL-002' }],
        alerts: [],
      },
    }),
  );
  await page.route('**/api/admissions/patient/*/current', (r) =>
    r.fulfill({ json: { id: 'stay', roomBed: null } }),
  );
  await page.route('**/api/beds/available**', (r) =>
    r.fulfill({ json: [{ id: 'cot', label: 'Maternity/M-01', wardType: 'MATERNITY' }] }),
  );
  await page.goto('/maternity');
  await page.getByRole('button', { name: 'Travail et accouchement', exact: true }).click();
  await page.locator('#labor-patient').fill('Local');
  await page.getByRole('option').filter({ hasText: 'Local' }).first().click();
  const empty = page.getByTestId('maternity-stay-no-neonatal-cot');
  await expect(empty).toBeVisible();
  const region = page.getByRole('region', { name: 'Partogramme', exact: true });
  await expect(region).toHaveAttribute('tabindex', '0');
  for (const width of LAYOUT_WIDTHS) {
    await page.setViewportSize({ width, height: heightFor(width) });
    await page.evaluate(() => {
      const backdrop = document.querySelector<HTMLElement>('.sidebar-backdrop');
      if (backdrop && getComputedStyle(backdrop).display !== 'none') backdrop.click();
    });
    await expect(page.locator('.sidebar-backdrop')).toBeHidden();
    expect(await empty.evaluate((el) => el.scrollWidth <= el.clientWidth + 1)).toBe(true);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(
      true,
    );
    await page.screenshot({
      path: testInfo.outputPath(`clinical-${width}.png`),
    });
  }
});
