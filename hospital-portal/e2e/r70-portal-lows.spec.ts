import { test, expect } from './fixtures/test-fixtures';
import { signInAs, TEST_ID } from './helpers/role-session';
import { installLocalIconFonts } from './helpers/local-icon-fonts';
import { heightFor, LAYOUT_WIDTHS } from './helpers/layout-audit';

test.use({ storageState: { cookies: [], origins: [] } });
test('D3455: current contact details remain readable at all four widths', async ({
  page,
  baseURL,
}, testInfo) => {
  await signInAs(page, 'ROLE_PATIENT', baseURL!);
  await installLocalIconFonts(page);
  const nextDay = new Date();
  nextDay.setDate(nextDay.getDate() + 1);
  const today = nextDay.toISOString().slice(0, 10);
  await page.route('**/api/me/patient/appointments', (route) =>
    route.fulfill({
      json: {
        data: [
          {
            id: TEST_ID,
            appointmentDate: today,
            startTime: '16:00',
            endTime: '16:30',
            staffName: 'Local Clinician',
            departmentName: 'General',
            status: 'SCHEDULED',
            preCheckedIn: false,
          },
        ],
      },
    }),
  );
  await page.route('**/api/me/patient/profile', (route) =>
    route.fulfill({
      json: {
        data: {
          id: TEST_ID,
          firstName: 'Local',
          lastName: 'Patient',
          phoneNumberPrimary: '+22670000000',
          email: 'local-patient-long-contact@example.invalid',
          addressLine1: 'cdx-Street',
          city: 'Ouagadougou',
          country: 'Burkina Faso',
        },
      },
    }),
  );
  await page.route('**/api/me/patient/appointments/*/questionnaires', (route) =>
    route.fulfill({ json: { data: [] } }),
  );
  await page.goto('/my-appointments');
  await page.locator('.portal-appt-card').first().click();
  await page.getByTestId('precheckin-open').click();
  const contact = page.getByTestId('precheckin-current-contact');
  await expect(contact).toContainText('+22670000000');
  await expect(contact).toContainText('cdx-Street, Ouagadougou, Burkina Faso');
  for (const width of LAYOUT_WIDTHS) {
    await page.setViewportSize({ width, height: heightFor(width) });
    await page.evaluate(() => {
      const backdrop = document.querySelector<HTMLElement>('.sidebar-backdrop');
      if (backdrop && getComputedStyle(backdrop).display !== 'none') backdrop.click();
    });
    await expect(page.locator('.sidebar-backdrop')).toBeHidden();
    await expect(contact).toBeVisible();
    expect(await contact.evaluate((el) => el.scrollWidth <= el.clientWidth + 1)).toBe(true);
    const box = await page.locator('.pre-checkin-panel').boundingBox();
    expect(box!.x).toBeGreaterThanOrEqual(0);
    expect(box!.x + box!.width).toBeLessThanOrEqual(width + 1);
    await page.screenshot({
      path: testInfo.outputPath(`portal-${width}.png`),
    });
  }
});
