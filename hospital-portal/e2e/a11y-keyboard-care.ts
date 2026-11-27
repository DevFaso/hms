import { test, expect } from './fixtures/test-fixtures';
import { tabTo, announcedRefusal, accessibleAtWidths } from './helpers/keyboard-journey';
import { signInAs, TEST_ID } from './helpers/role-session';
import { installLocalIconFonts } from './helpers/local-icon-fonts';

test.use({ storageState: { cookies: [], origins: [] } });

test('checkout: keyboard-only refusal, dialog focus trap, Escape return and one mocked payment', async ({
  page,
  baseURL,
}) => {
  await signInAs(page, 'ROLE_ACCOUNTANT', baseURL!);
  await installLocalIconFonts(page);
  let paid = false;
  const requests: Record<string, unknown>[] = [];
  const invoice = () => ({
    id: TEST_ID,
    invoiceNumber: 'cdx-keyboard-invoice',
    patientFullName: 'cdx Patient',
    patientName: 'cdx Patient',
    hospitalName: 'cdx Hospital',
    status: paid ? 'PAID' : 'SENT',
    totalAmount: 1000,
    balanceDue: paid ? 0 : 1000,
    amountPaid: paid ? 1000 : 0,
    invoiceDate: '2026-11-24',
    dueDate: '2026-11-24',
    insuranceCoverageAmount: 0,
    patientResponsibilityAmount: 1000,
  });
  await page.route('**/api/billing-invoices/search?**', (route) =>
    route.fulfill({
      json: {
        content: [invoice()],
        totalElements: 1,
        totalPages: 1,
        number: 0,
        size: 20,
      },
    }),
  );
  await page.route(`**/api/billing-invoices/${TEST_ID}/payments`, async (route) => {
    if (route.request().method() === 'POST') {
      requests.push(route.request().postDataJSON());
      paid = true;
      await route.fulfill({ json: invoice() });
    } else await route.fulfill({ json: [] });
  });
  await page.goto('/billing');
  const opener = page.locator('.pay-link');
  await tabTo(page, opener);
  await page.keyboard.press('Enter');
  let dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();
  const submit = dialog.locator('.modal-actions .btn-primary');
  const first = dialog.locator('button, input, select, textarea').first();
  await tabTo(page, submit);
  await page.keyboard.press('Tab');
  await expect(first).toBeFocused();
  await page.keyboard.press('Shift+Tab');
  await expect(submit).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(dialog).toHaveCount(0);
  await expect(opener).toBeFocused();
  await page.keyboard.press('Enter');
  dialog = page.getByRole('dialog');
  const amount = dialog.locator('#payment-amount');
  await tabTo(page, amount);
  await page.keyboard.press('ControlOrMeta+A');
  await page.keyboard.press('Backspace');
  await tabTo(page, dialog.locator('.modal-actions .btn-primary'));
  await page.keyboard.press('Enter');
  await announcedRefusal(page, amount);
  expect(requests).toHaveLength(0);
  await accessibleAtWidths(page, '[role="dialog"]');
  await page.keyboard.type('1000');
  await tabTo(page, dialog.locator('.modal-actions .btn-primary'));
  await page.keyboard.press('Enter');
  await expect.poll(() => requests.length).toBe(1);
  await expect(dialog).toHaveCount(0);
  expect(requests[0]).toMatchObject({ amount: 1000, method: 'CASH' });
});

for (const unitPrice of [0, 125]) {
  test(`dispensing: keyboard-only ${unitPrice ? 'priced unpaid-release confirmation' : 'required attestation'}, Escape return and one mocked dispense`, async ({
    page,
    baseURL,
  }) => {
    await signInAs(page, 'ROLE_PHARMACIST', baseURL!);
    await installLocalIconFonts(page);
    let dispensed = false;
    const requests: Record<string, unknown>[] = [];
    const rx = {
      id: TEST_ID,
      medicationName: 'cdx Synthetic medicine',
      dosage: '1',
      frequency: 'DAILY',
      quantity: 2,
      remainingQuantity: 2,
      quantityUnit: 'comprimé',
      status: 'TRANSMITTED',
      patient: { id: TEST_ID, firstName: 'cdx', lastName: 'Patient' },
    };
    const pharmacy = {
      id: TEST_ID,
      hospitalId: TEST_ID,
      name: 'cdx Synthetic pharmacy',
      active: true,
    };
    await page.route('**/api/pharmacy-registry?**', (route) =>
      route.fulfill({
        json: {
          content: [pharmacy],
          totalElements: 1,
          totalPages: 1,
          number: 0,
          size: 20,
        },
      }),
    );
    await page.route('**/api/pharmacy/dispense/settings', (route) =>
      route.fulfill({
        json: {
          data: { queueClaimEnabled: false, readyForCollectionEnabled: false },
        },
      }),
    );
    await page.route('**/api/pharmacy/dispense/work-queue?**', (route) =>
      route.fulfill({
        json: {
          data: {
            content: dispensed ? [] : [rx],
            totalElements: dispensed ? 0 : 1,
            totalPages: 1,
            number: 0,
            size: 20,
          },
        },
      }),
    );
    await page.route(
      `**/api/pharmacy/dispense/prescription/${TEST_ID}/dispensable-lots?**`,
      (route) =>
        route.fulfill({
          json: {
            data: [
              {
                id: TEST_ID,
                inventoryItemId: TEST_ID,
                lotNumber: 'cdx-LOT',
                expiryDate: '2099-12-31',
                initialQuantity: 10,
                remainingQuantity: 10,
                unit: 'comprimé',
                barcodeValue: 'cdx-barcode',
              },
            ],
          },
        }),
    );
    // Synthetic prices exercise the real unpaid-release confirmation UI only.
    // Every API response is mocked; no real stock, release or payment is changed.
    await page.route(`**/api/pharmacy/inventory/items/pharmacy/${TEST_ID}*`, (route) =>
      route.fulfill({
        json: {
          data: {
            content: [
              {
                id: TEST_ID,
                pharmacyId: TEST_ID,
                medicationName: rx.medicationName,
                quantityOnHand: 10,
                unit: 'comprimé',
                unitPrice,
                // Review of #1380 (F3): an item with no price is documented free (an unpriced one asks for an
                // urgency reason).
                ...(unitPrice ? {} : { freeOfChargeReason: 'cdx synthetic free item' }),
                active: true,
              },
            ],
            totalElements: 1,
            totalPages: 1,
            number: 0,
            size: 20,
          },
        },
      }),
    );
    await page.route(`**/api/pharmacy/billing-deferrals/counter/${TEST_ID}`, (route) =>
      route.fulfill({ json: { data: { onOpenStay: false, open: [] } } }),
    );
    await page.route('**/api/pharmacy/dispense', async (route) => {
      expect(route.request().method()).toBe('POST');
      requests.push(route.request().postDataJSON());
      dispensed = true;
      await route.fulfill({
        json: { data: { id: TEST_ID, status: 'DISPENSED', prescriptionId: TEST_ID } },
      });
    });
    await page.goto('/pharmacy/dispensing');
    const opener = page.getByTestId(`rx-dispense-${TEST_ID}`);
    await tabTo(page, opener);
    await page.keyboard.press('Enter');
    const form = page.getByTestId('dispense-form');
    await expect(form).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(form).toHaveCount(0);
    await expect(opener).toBeFocused();
    await page.keyboard.press('Enter');
    const attest = page.getByTestId('verify-attest');
    const submit = page.getByTestId('dispense-submit');
    await tabTo(page, submit);
    await page.keyboard.press('Enter');
    await announcedRefusal(page, attest);
    expect(requests).toHaveLength(0);
    await accessibleAtWidths(page, '[data-testid="dispense-form"]');
    await page.keyboard.press('Space');
    await expect(attest).toBeChecked();
    await tabTo(page, submit);
    await page.keyboard.press('Enter');
    if (unitPrice > 0) {
      const choice = page.getByTestId('deferral-use-urgency');
      await announcedRefusal(page, choice);
      await expect(page.getByTestId('dispense-defer-charge')).toContainText('250');
      expect(requests).toHaveLength(0);
      await page.keyboard.press('Space');
      const reason = page.getByTestId('deferral-urgency-reason');
      await tabTo(page, submit);
      await page.keyboard.press('Enter');
      await announcedRefusal(page, reason);
      expect(requests).toHaveLength(0);
      await page.keyboard.type('cdx synthetic unpaid-release keyboard confirmation');
      expect(requests).toHaveLength(0);
      await tabTo(page, submit);
      expect(requests).toHaveLength(0);
      await page.keyboard.press('Enter');
    }
    await expect.poll(() => requests.length).toBe(1);
    await expect(form).toHaveCount(0);
    if (unitPrice > 0) {
      expect(requests[0].billingUrgencyReason).toBe(
        'cdx synthetic unpaid-release keyboard confirmation',
      );
    }
    expect(requests[0]).toMatchObject({
      prescriptionId: TEST_ID,
      quantityDispensed: 2,
      unit: 'comprimé',
      verifiedBy: TEST_ID,
    });
  });
}
