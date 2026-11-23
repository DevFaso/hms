import { test, expect } from './fixtures/test-fixtures';
import { signInAs, TEST_ID } from './helpers/role-session';
import { installLocalIconFonts } from './helpers/local-icon-fonts';

test.use({ storageState: { cookies: [], origins: [] } });

test('operator retries only the generation on screen and sees the next attempt without contact data', async ({
  page,
  baseURL,
}, info) => {
  await signInAs(page, 'ROLE_HOSPITAL_ADMIN', baseURL!);
  await installLocalIconFonts(page);
  let retried = 0;
  const failed = {
    id: TEST_ID,
    patientId: TEST_ID,
    patientName: 'cdx-Fixture patient',
    eventType: 'LAB_RESULT_AVAILABLE',
    channel: 'SMS',
    status: 'FAILED',
    attempts: 5,
    manualRetries: 3,
    errorCode: 'PROVIDER_UNREACHABLE',
    retryable: true,
    createdAt: '2026-11-23T08:00:00',
  };
  await page.route('**/api/patient-notification-deliveries**', async (route) => {
    if (route.request().method() === 'POST') {
      expect(route.request().postDataJSON()).toEqual({ expectedManualRetries: 3 });
      retried++;
      return route.fulfill({
        json: { data: { ...failed, status: 'PENDING', manualRetries: 4, retryable: false } },
      });
    }
    return route.fulfill({
      json: {
        data: {
          content: retried ? [] : [failed],
          page: 0,
          size: 25,
          totalElements: retried ? 0 : 1,
          countsByStatus: {
            FAILED: retried ? 0 : 1,
            PENDING: retried ? 1 : 0,
            RETRYING: 0,
            SENT: 0,
            SKIPPED: 0,
          },
        },
      },
    });
  });
  await page.goto('/patient-notifications');
  await page.getByTestId(`pn-retry-${TEST_ID}`).dblclick();
  await expect(page.getByTestId('pn-empty')).toBeVisible();
  expect(retried).toBe(1);

  await page.unroute('**/api/patient-notification-deliveries**');
  await page.route('**/api/patient-notification-deliveries**', (route) =>
    route.fulfill({
      json: {
        data: {
          content: [
            {
              ...failed,
              status: 'RETRYING',
              retryable: false,
              manualRetries: 4,
              nextAttemptAt: '2026-11-23T09:30:00',
            },
            { ...failed, id: 'cdx-unknown', errorCode: 'OUTCOME_UNKNOWN', retryable: false },
          ],
          page: 0,
          size: 25,
          totalElements: 2,
          countsByStatus: { FAILED: 1, PENDING: 0, RETRYING: 1, SENT: 0, SKIPPED: 0 },
        },
      },
    }),
  );
  await page.reload();
  await page.getByTestId('pn-status-filter').selectOption('ALL');
  await expect(page.getByTestId(`pn-next-attempt-${TEST_ID}`)).toContainText('23');
  await expect(page.getByTestId('pn-retry-cdx-unknown')).toHaveCount(0);
  await expect(page.getByTestId('pn-maybe-delivered-cdx-unknown')).toBeAttached();
  for (const width of [390, 768, 1024, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(
      true,
    );
    await page.screenshot({ path: info.outputPath(`operator-${width}.png`) });
  }
});
