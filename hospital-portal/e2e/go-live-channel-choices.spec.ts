import { test, expect } from './fixtures/test-fixtures';
import { signInAs, TEST_ID } from './helpers/role-session';
import { installLocalIconFonts } from './helpers/local-icon-fonts';
import { heightFor, LAYOUT_WIDTHS } from './helpers/layout-audit';

test.use({ storageState: { cookies: [], origins: [] } });

test('the desk records one choice with attributed canonical feedback', async ({
  page,
  baseURL,
}, info) => {
  await signInAs(page, 'ROLE_RECEPTIONIST', baseURL!);
  await installLocalIconFonts(page);
  await page.route(`**/api/patients/${TEST_ID}*`, (route) => {
    if (new URL(route.request().url()).pathname !== `/api/patients/${TEST_ID}`)
      return route.fallback();
    return route.fulfill({
      json: {
        id: TEST_ID,
        firstName: 'cdx-Local',
        lastName: 'Patient',
        dateOfBirth: '1990-01-01',
        phoneNumberPrimary: '+22670000000',
        active: true,
        hospitalId: TEST_ID,
      },
    });
  });
  let writes = 0;
  await page.route(`**/api/patients/${TEST_ID}/channel-preferences*`, async (route) => {
    const choices = [
      'APPOINTMENT_REMINDER',
      'LAB_RESULT',
      'MEDICATION_REFILL',
      'BILLING',
      'DISCHARGE_SUMMARY',
    ].flatMap((notificationType) =>
      ['SMS', 'EMAIL'].map((channel) => ({ notificationType, channel, enabled: true })),
    );
    if (route.request().method() === 'GET') return route.fulfill({ json: choices });
    expect(route.request().postDataJSON()).toEqual([
      { notificationType: 'LAB_RESULT', channel: 'SMS', enabled: false },
    ]);
    writes++;
    return route.fulfill({
      json: choices.map((choice) =>
        choice.notificationType === 'LAB_RESULT' && choice.channel === 'SMS'
          ? {
              ...choice,
              enabled: false,
              recordedByName: 'cdx-Receptionist',
              recordedAt: '2026-11-22T08:00:00',
            }
          : choice,
      ),
    });
  });
  await page.goto(`/patients/${TEST_ID}`);
  await page.getByTestId('administration-section-button').click();
  await page.getByTestId('channel-LAB_RESULT-SMS').uncheck();
  await page.getByTestId('channel-prefs-save').click();
  const attribution = page.getByTestId('choice-recorder-LAB_RESULT-SMS');
  await expect(attribution).toContainText('cdx-Receptionist');
  await expect(attribution.locator('time')).toHaveAttribute('datetime', '2026-11-22T08:00:00Z');
  expect(writes).toBe(1);
  for (const width of LAYOUT_WIDTHS) {
    await page.setViewportSize({ width, height: heightFor(width) });
    await expect(attribution).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(
      true,
    );
    await page.screenshot({ path: info.outputPath(`desk-${width}.png`) });
  }
});

test('self-service sends only edited choices and adopts concurrent refusals', async ({
  page,
  baseURL,
}, info) => {
  await signInAs(page, 'ROLE_PATIENT', baseURL!);
  await installLocalIconFonts(page);
  let writes = 0;
  await page.route('**/api/notifications/preferences', async (route) => {
    if (route.request().method() === 'GET') return route.fulfill({ json: [] });
    expect(route.request().postDataJSON()).toEqual([
      { notificationType: 'APPOINTMENT_REMINDER', channel: 'SMS', enabled: false },
    ]);
    writes++;
    return route.fulfill({
      json: [
        { notificationType: 'APPOINTMENT_REMINDER', channel: 'SMS', enabled: false },
        { notificationType: 'BILLING', channel: 'EMAIL', enabled: false },
      ],
    });
  });
  await page.goto('/notification-settings');
  const first = page.locator('.pref-table tbody tr').first();
  await first.locator('label.toggle').nth(2).click();
  await page.locator('.settings-actions button').click();
  await expect(
    page.locator('.pref-table tbody tr').nth(3).getByRole('checkbox').nth(1),
  ).not.toBeChecked();
  expect(writes).toBe(1);
  for (const width of LAYOUT_WIDTHS) {
    await page.setViewportSize({ width, height: heightFor(width) });
    await expect(page.locator('.pref-table')).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(
      true,
    );
    await page.screenshot({ path: info.outputPath(`preferences-${width}.png`) });
  }
});

test('operator sees the original refusal recorder, time and subject', async ({
  page,
  baseURL,
}, info) => {
  await signInAs(page, 'ROLE_HOSPITAL_ADMIN', baseURL!);
  await installLocalIconFonts(page);
  await page.route('**/api/patient-notification-deliveries?**', (route) =>
    route.fulfill({
      json: {
        data: {
          content: [
            {
              id: TEST_ID,
              patientId: TEST_ID,
              patientName: 'cdx-Patient',
              eventType: 'LAB_RESULT_AVAILABLE',
              channel: 'SMS',
              status: 'SKIPPED',
              attempts: 0,
              errorCode: 'OPTED_OUT',
              retryable: false,
              manualRetries: 0,
              createdAt: '2026-11-22T09:00:00',
              consentRecordedByName: 'cdx-Receptionist',
              consentRecordedAt: '2026-11-21T08:00:00',
              subjectReference: 'LAB-cdx-42',
            },
          ],
          page: 0,
          size: 25,
          totalElements: 1,
          countsByStatus: { PENDING: 0, RETRYING: 0, SENT: 0, FAILED: 0, SKIPPED: 1 },
        },
      },
    }),
  );
  await page.goto('/patient-notifications');
  const recorder = page.getByTestId(`pn-consent-recorder-${TEST_ID}`);
  await expect(recorder).toContainText('cdx-Receptionist');
  await expect(recorder).toContainText('21');
  await expect(page.locator('.pn-table')).toContainText('LAB-cdx-42');
  for (const width of LAYOUT_WIDTHS) {
    await page.setViewportSize({ width, height: heightFor(width) });
    await expect(recorder).toBeAttached();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(
      true,
    );
    await page.screenshot({ path: info.outputPath(`refusal-${width}.png`) });
  }
});
