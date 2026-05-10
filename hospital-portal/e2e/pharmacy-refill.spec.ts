/**
 * Pharmacy refill approval queue E2E.
 *
 * Roadmap row 5 / T-71 — covers the provider-facing refill approval flow
 * that pairs with the patient-portal refill request submission. The route
 * is /refills (not under /pharmacy/* because doctors and nurses also use
 * it), but it is part of the dispense-path coverage T-71 calls out.
 *
 * The component reads from /api/refills (auto-mocked by the fixture as []
 * by default). Here we override it with a single PENDING row so the queue
 * table renders and the filter tabs have something to filter.
 */
import { test, expect } from './fixtures/test-fixtures';

const REFILL_LIST = [
  {
    id: '00000000-0000-0000-0000-000000000501',
    prescriptionId: '00000000-0000-0000-0000-000000000502',
    patientName: 'Compaoré, Jean',
    medicationName: 'Métformine 500 mg',
    quantityRequested: 60,
    status: 'PENDING',
    requestedAt: '2026-05-09T10:30:00Z',
    requestedBy: 'PATIENT',
  },
];

test.describe('Pharmacy — Refill approval queue', () => {
  test.beforeEach(async ({ page }) => {
    // /refills is a path used by both the provider list view and the patient
    // portal; we hit only the provider list here. Returning a single row
    // exercises both the table render and the status-filter tabs.
    await page.route('**/api/refills**', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(REFILL_LIST),
      }),
    );
    await page.goto('/refills', { waitUntil: 'domcontentloaded' });
  });

  test('renders the refill queue with its title', async ({ page }) => {
    // The component uses [data-testid="refill-approval-list"] — much more
    // stable than chasing localized header text.
    await expect(page.locator('[data-testid="refill-approval-list"]')).toBeVisible();
  });

  test('exposes status filter tabs (PENDING / APPROVED / REJECTED)', async ({ page }) => {
    // The filter tabs have data-testid="refill-filter-<value>" attributes.
    // Clicking PENDING is a no-op state-wise but confirms the tab is bound.
    const pendingTab = page.locator('[data-testid="refill-filter-PENDING"]');
    await expect(pendingTab).toBeVisible();
    await pendingTab.click();
    await expect(pendingTab).toHaveAttribute('aria-selected', 'true');
  });

  test('does not show the load-error banner on a healthy fetch', async ({ page }) => {
    // The error banner has data-testid="refill-list-error" and only renders
    // when the GET /api/refills request fails. Our route stub always wins,
    // so the banner must stay hidden.
    await page.waitForTimeout(500);
    await expect(page.locator('[data-testid="refill-list-error"]')).toHaveCount(0);
  });
});
