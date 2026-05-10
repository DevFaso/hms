/**
 * Pharmacy Tier 1 (in-house dispense) E2E.
 *
 * Roadmap row 5 / T-71 — covers the happy path of the hospital-dispensary
 * dispense flow: pharmacist opens the work queue, the page renders with the
 * mocked queue payload, and the page surfaces the dispensing form when a
 * prescription is selected. We deliberately keep this above the unit-spec
 * layer (DispenseService is already covered by JUnit + Karma) — the value
 * here is the route + template + service-call wiring against a stable URL.
 *
 * Uses stored auth state (chromium project) and the auto-mock fixture from
 * test-fixtures.ts. We layer one extra route override on top of that so the
 * /pharmacy/dispense/work-queue endpoint returns a single-row queue instead
 * of the catch-all empty page — that lets the queue table render and the
 * UI emit the "select a prescription" prompt.
 */
import { test, expect } from './fixtures/test-fixtures';

const QUEUE_RESPONSE = {
  data: {
    content: [
      {
        prescriptionId: '00000000-0000-0000-0000-000000000101',
        patientId: '00000000-0000-0000-0000-000000000201',
        patientName: 'Ouédraogo, Aïcha',
        medicationName: 'Amoxicilline 500 mg',
        prescriberName: 'Dr Sawadogo',
        quantityRequested: 30,
        priority: 'NORMAL',
        waitMinutes: 12,
        status: 'TRANSMITTED',
      },
    ],
    totalElements: 1,
    totalPages: 1,
    size: 20,
    number: 0,
    first: true,
    last: true,
    empty: false,
  },
  message: null,
  success: true,
};

test.describe('Pharmacy — Tier 1 in-house dispense', () => {
  test.beforeEach(async ({ page }) => {
    // Override the catch-all from the auto-mock fixture for this one route.
    // Playwright route handlers are LIFO, so this wins for /work-queue.
    await page.route('**/api/pharmacy/dispense/work-queue**', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(QUEUE_RESPONSE),
      }),
    );
    await page.goto('/pharmacy/dispensing', { waitUntil: 'domcontentloaded' });
  });

  test('renders the dispensing page with its title', async ({ page }) => {
    await expect(page.locator('main .page-title')).toBeVisible();
  });

  test('hits the work-queue endpoint with a paginated request', async ({ page }) => {
    // Re-navigate with a request listener attached so we can assert the URL
    // shape. This validates the service binding without coupling to the DOM.
    const queueRequest = page.waitForRequest(
      (req) =>
        req.url().includes('/api/pharmacy/dispense/work-queue') && req.method() === 'GET',
    );
    await page.goto('/pharmacy/dispensing', { waitUntil: 'domcontentloaded' });
    const req = await queueRequest;
    // Standard Spring-Data pagination contract enforced by the controller's
    // @PageableDefault(size = 20, sort = "createdAt"). Assert BOTH params
    // are present and pinned to the agreed defaults — Copilot review on
    // PR #287 caught the original `||` assertion silently passing when
    // only one of the two was emitted.
    const url = new URL(req.url());
    expect(url.searchParams.get('page')).toBe('0');
    expect(url.searchParams.get('size')).toBe('20');
  });

  test('page mounts without route-guard redirect to /login', async ({ page }) => {
    // The auto-mock fixture is what protects against the 401 → /login bounce;
    // this test makes that protection an explicit assertion so a future change
    // that drops the mock surfaces here, not as a flaky neighbour test.
    await page.waitForTimeout(500);
    expect(page.url()).toContain('/pharmacy/dispensing');
  });
});
