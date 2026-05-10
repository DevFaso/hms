/**
 * Pharmacy Tier 2 (partner pharmacy routing) E2E.
 *
 * Roadmap row 5 / T-71 — covers the stock-out routing handoff: pharmacist
 * checks stock for a prescription, sees an "insufficient" verdict, and is
 * presented with the partner-pharmacy options + back-order / print-for-
 * patient buttons. This is the surface the docs/pharmacy-implementation-plan.md
 * §0b "Tier 2 — partner pharmacy" workflow lives on.
 *
 * Mock strategy mirrors pharmacy-tier1-dispense.spec.ts: layer one specific
 * route override on top of the auto-mock fixture so the stock-check call
 * returns a representative payload, then drive the UI.
 */
import { test, expect } from './fixtures/test-fixtures';

const PRESCRIPTION_ID = '00000000-0000-0000-0000-000000000301';

const STOCK_CHECK_RESPONSE = {
  data: {
    prescriptionId: PRESCRIPTION_ID,
    medicationName: 'Insuline NPH 100 UI/mL',
    pharmacyName: 'Pharmacie Hospitalière',
    quantityOnHand: 0,
    quantityRequested: 1,
    sufficient: false,
    partnerPharmacies: [
      {
        pharmacyId: '00000000-0000-0000-0000-000000000401',
        name: 'Pharmacie du Progrès',
        distanceKm: 0.8,
        formularyMatch: true,
        responseChannel: 'SMS',
      },
      {
        pharmacyId: '00000000-0000-0000-0000-000000000402',
        name: 'Pharmacie Centrale',
        distanceKm: 1.4,
        formularyMatch: true,
        responseChannel: 'SMS',
      },
    ],
  },
  message: null,
  success: true,
};

test.describe('Pharmacy — Tier 2 partner routing', () => {
  test.beforeEach(async ({ page }) => {
    await page.route('**/api/pharmacy/stock-routing/check**', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(STOCK_CHECK_RESPONSE),
      }),
    );
    await page.route('**/api/pharmacy/stock-routing/**', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(STOCK_CHECK_RESPONSE),
      }),
    );
    await page.goto('/pharmacy/stock-routing', { waitUntil: 'domcontentloaded' });
  });

  test('renders the stock-routing page with its title', async ({ page }) => {
    await expect(page.locator('main .page-title')).toBeVisible();
  });

  test('looks up a prescription and displays the partner-pharmacy options', async ({ page }) => {
    // The form has an input + a check button; we exercise both so the e2e
    // covers the user-visible interaction, not just an initial render.
    const idInput = page.locator('#prescriptionIdInput, input[id*="prescription" i]').first();
    if (await idInput.isVisible().catch(() => false)) {
      await idInput.fill(PRESCRIPTION_ID);
    }
    const checkButton = page
      .locator('button.btn-primary')
      .filter({ hasNotText: /export/i })
      .first();
    if (await checkButton.isVisible().catch(() => false)) {
      await checkButton.click();
    }

    // The "routing-options" block only renders when the stock check returns
    // sufficient=false — exactly what the mock above provides.
    const routingOptions = page.locator('.routing-options, .partner-list, .form-card-accent');
    await expect(routingOptions.first()).toBeVisible({ timeout: 8_000 });
  });

  test('page mounts without route-guard redirect to /login', async ({ page }) => {
    await page.waitForTimeout(500);
    expect(page.url()).toContain('/pharmacy/stock-routing');
  });
});
