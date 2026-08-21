/**
 * Pharmacy refill approval queue E2E.
 *
 * Roadmap row 5 / T-71 — covers the provider-facing refill approval flow
 * that pairs with the patient-portal refill request submission. The route
 * is /refills (not under /pharmacy/* because doctors and nurses also use
 * it), but it is part of the dispense-path coverage T-71 calls out.
 *
 * The component reads from /api/refills (auto-mocked by the fixture as []
 * by default). Here we override it with a single pending row so the queue
 * table renders and the filter tabs have something to filter.
 *
 * NOTE: this file navigates straight to /refills, so it cannot prove the
 * queue is *reachable*. It wasn't, for the whole life of the feature — no
 * sidebar entry existed and both dashboard tiles pointed at /prescriptions.
 * The sidebar entry is pinned by shell.spec.ts and the tile routes by
 * dashboard.spec.ts, because this fixture does not seed a role-bearing token.
 */
import { test, expect } from './fixtures/test-fixtures';

// Mirrors MedicationRefillResponseDTO. The previous stub invented fields
// (patientName, quantityRequested, requestedBy) the API never returns, and a
// `PENDING` status that is not in the RefillStatus enum.
const REFILL_ROWS = [
  {
    id: '00000000-0000-0000-0000-000000000501',
    prescriptionId: '00000000-0000-0000-0000-000000000502',
    medicationName: 'Métformine 500 mg',
    patientId: '00000000-0000-0000-0000-000000000503',
    status: 'REQUESTED',
    preferredPharmacy: 'Pharmacie du Centre',
    notes: 'Bientôt à court',
    providerNotes: null,
    requestedAt: '2026-05-09T10:30:00Z',
    updatedAt: '2026-05-09T10:30:00Z',
  },
];

// RefillApprovalService.list() reads `r.data.content` from an ApiWrapper-
// over-PageEnvelope. Copilot review on PR #287 caught the original bare-array
// stub — that shape would have TypeError'd on .map() inside the component.
const REFILL_LIST_RESPONSE = {
  data: {
    content: REFILL_ROWS,
    totalElements: REFILL_ROWS.length,
    totalPages: 1,
    size: 20,
    number: 0,
    first: true,
    last: true,
    empty: false,
  },
  success: true,
};

test.describe('Pharmacy — Refill approval queue', () => {
  test.beforeEach(async ({ page }) => {
    // /refills is a path used by both the provider list view and the patient
    // portal; we hit only the provider list here. Returning a single row
    // exercises both the table render and the status-filter tabs.
    await page.route('**/api/refills**', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(REFILL_LIST_RESPONSE),
      }),
    );
    await page.goto('/refills', { waitUntil: 'domcontentloaded' });
  });

  test('renders the refill queue with its title', async ({ page }) => {
    // The component uses [data-testid="refill-approval-list"] — much more
    // stable than chasing localized header text.
    await expect(page.locator('[data-testid="refill-approval-list"]')).toBeVisible();
  });

  test('exposes a status filter tab per RefillStatus the queue triages', async ({ page }) => {
    // The filter tabs have data-testid="refill-filter-<value>" attributes and
    // are keyed on the enum, so a renamed status breaks this rather than
    // silently rendering a tab that filters nothing.
    const requestedTab = page.locator('[data-testid="refill-filter-REQUESTED"]');
    await expect(requestedTab).toBeVisible();
    await requestedTab.click();
    await expect(requestedTab).toHaveAttribute('aria-selected', 'true');
  });

  test('offers a hold filter so deferred requests stay findable', async ({ page }) => {
    const pausedTab = page.locator('[data-testid="refill-filter-PAUSED"]');
    await expect(pausedTab).toBeVisible();
    await pausedTab.click();
    await expect(pausedTab).toHaveAttribute('aria-selected', 'true');
  });

  test('offers all three decisions on a pending request', async ({ page }) => {
    const id = REFILL_ROWS[0].id;
    await expect(page.locator(`[data-testid="refill-approve-${id}"]`)).toBeVisible();
    await expect(page.locator(`[data-testid="refill-pause-${id}"]`)).toBeVisible();
    await expect(page.locator(`[data-testid="refill-reject-${id}"]`)).toBeVisible();
  });

  test('does not show the load-error banner on a healthy fetch', async ({ page }) => {
    // The error banner has data-testid="refill-list-error" and only renders
    // when the GET /api/refills request fails. Our route stub always wins,
    // so the banner must stay hidden.
    await page.waitForTimeout(500);
    await expect(page.locator('[data-testid="refill-list-error"]')).toHaveCount(0);
  });
});
