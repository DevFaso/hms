import AxeBuilder from '@axe-core/playwright';
import { test, expect } from './fixtures/test-fixtures';
import { signInAs, TEST_ID } from './helpers/role-session';
import { installLocalIconFonts } from './helpers/local-icon-fonts';

test.describe('main journey form states', () => {
  test.use({ storageState: { cookies: [], origins: [] } });

  test('D1940: appointment required-field summary follows edits without another submit', async ({
    page,
    baseURL,
  }, info) => {
    await signInAs(page, 'ROLE_PATIENT', baseURL!);
    await installLocalIconFonts(page);
    await page.route('**/api/me/patient/booking/hospitals', (route) =>
      route.fulfill({ json: { data: [{ id: TEST_ID, name: 'cdx-Fixture hospital' }] } }),
    );
    await page.route(`**/api/me/patient/booking/hospitals/${TEST_ID}/departments`, (route) =>
      route.fulfill({
        json: { data: [{ id: TEST_ID, name: 'cdx-Fixture department', hasPublishedSlots: true }] },
      }),
    );
    await page.goto('/my-appointments');
    await expect(page).toHaveURL((url) => url.pathname === '/my-appointments');
    const opener = page.getByTestId('booking-open');
    await opener.click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    await dialog.locator('.booking-submit-btn').click();
    const summary = dialog.locator('.booking-error');
    await expect(summary).toContainText('Hôpital');
    await dialog.locator('#hospital').selectOption(TEST_ID);
    await expect(summary).not.toContainText('Hôpital');
    await expect(summary).toContainText('Service');
    await dialog.locator('#department').selectOption(TEST_ID);
    await expect(summary).not.toContainText('Service');
    for (const width of [390, 768, 1024, 1440]) {
      await page.setViewportSize({ width, height: 900 });
      const result = await new AxeBuilder({ page })
        .include('[role="dialog"]')
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze();
      expect(
        result.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical'),
      ).toEqual([]);
      await page.screenshot({ path: info.outputPath(`booking-refusal-${width}.png`) });
    }
    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
    await expect(opener).toBeFocused();
  });

  for (const form of [
    { route: '/prescriptions', role: 'ROLE_DOCTOR', opener: '.page-header .btn-primary' },
    { route: '/lab-results', role: 'ROLE_LAB_TECHNICIAN', opener: '.page-header .btn-primary' },
  ]) {
    test(`entry dialog accessibility: ${form.route}`, async ({ page, baseURL }) => {
      await signInAs(page, form.role, baseURL!);
      await installLocalIconFonts(page);
      await page.goto(form.route);
      await expect(page).toHaveURL((url) => url.pathname === form.route);
      const opener = page.locator(form.opener);
      await opener.click();
      const dialog = page.getByRole('dialog');
      await expect(dialog).toBeVisible();
      const result = await new AxeBuilder({ page })
        .include('[role="dialog"]')
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze();
      expect(
        result.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical'),
      ).toEqual([]);
      await page.keyboard.press('Escape');
      await expect(dialog).toHaveCount(0);
      await expect(opener).toBeFocused();
    });
  }

  for (const journey of ['billing-payment', 'imaging-report']) {
    test(`working form accessibility: ${journey}`, async ({ page, baseURL }, info) => {
      await signInAs(
        page,
        journey === 'billing-payment' ? 'ROLE_ACCOUNTANT' : 'ROLE_RADIOLOGIST',
        baseURL!,
      );
      await installLocalIconFonts(page);
      if (journey === 'billing-payment') {
        await page.route('**/api/billing-invoices/search?**', (route) =>
          route.fulfill({
            json: {
              content: [
                {
                  id: TEST_ID,
                  invoiceNumber: 'cdx-INV-1',
                  patientFullName: 'cdx Patient',
                  patientName: 'cdx Patient',
                  hospitalName: 'cdx Hospital',
                  status: 'SENT',
                  totalAmount: 1000,
                  balanceDue: 1000,
                  amountPaid: 0,
                  invoiceDate: '2026-11-23',
                  dueDate: '2026-11-23',
                  insuranceCoverageAmount: 0,
                  patientResponsibilityAmount: 1000,
                },
              ],
              totalElements: 1,
              totalPages: 1,
              number: 0,
              size: 20,
            },
          }),
        );
        await page.route(`**/api/billing-invoices/${TEST_ID}/payments`, (route) =>
          route.fulfill({ json: [] }),
        );
        await page.goto('/billing');
        await page.locator('.pay-link').click();
      } else {
        await page.route(/\/api\/imaging\/orders(?:\/hospital\/[^/?]+)?(?:\?.*)?$/, (route) =>
          route.fulfill({
            json: [
              {
                id: TEST_ID,
                hospitalId: TEST_ID,
                patientId: TEST_ID,
                patientDisplayName: 'cdx Patient',
                modality: 'XRAY',
                studyType: 'cdx Chest study',
                bodyPart: 'Thorax',
                status: 'COMPLETED',
                priority: 'ROUTINE',
                orderDate: '2026-11-23',
              },
            ],
          }),
        );
        await page.route(`**/api/imaging/results/order/${TEST_ID}/all`, (route) =>
          route.fulfill({ json: [] }),
        );
        await page.goto('/imaging');
        await page.getByTestId('imaging-card-all').click();
        await page.getByTestId(`imaging-author-${TEST_ID}`).click();
      }
      const dialog = page.getByRole('dialog');
      await expect(dialog).toBeVisible();
      await expect(dialog.locator('input, select, textarea').first()).toBeVisible();
      for (const width of [390, 768, 1024, 1440]) {
        await page.setViewportSize({ width, height: 900 });
        const result = await new AxeBuilder({ page })
          .include('[role="dialog"]')
          .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
          .analyze();
        expect(
          result.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical'),
        ).toEqual([]);
        await page.screenshot({ path: info.outputPath(`${journey}-${width}.png`) });
      }
      // Only inspect the form: never submit a clinical report or a payment in an accessibility scan.
      await page.keyboard.press('Escape');
      await expect(dialog).toHaveCount(0);
    });
  }
});
