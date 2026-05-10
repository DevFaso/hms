/**
 * Pharmacy AMU CSV export E2E.
 *
 * Roadmap row 5 / T-71 — covers the claims-management screen's CSV export
 * flow that the Burkina Faso AMU (Assurance Maladie Universelle) integration
 * relies on. The button at /pharmacy/claims posts to
 * GET /api/pharmacy/claims/export/csv with a Blob response; the frontend
 * triggers a browser download.
 *
 * The deterministic assertion here is that clicking "Exporter CSV" actually
 * fires the export request — which is the same contract the backend test
 * PharmacyClaimExportServiceTest enforces on its end. We do not assert on
 * the downloaded file contents because a JSON payload via Playwright's
 * route.fulfill() does not always trigger a browser-level download event;
 * the request is the stable observable.
 */
import { test, expect } from './fixtures/test-fixtures';

const CSV_BODY =
  'createdAt,patientName,coverageReference,amount,status\n' +
  '2026-05-09T10:30:00Z,Ouédraogo Aïcha,AMU-12345,12500.00,SUBMITTED\n';

test.describe('Pharmacy — AMU claims CSV export', () => {
  test.beforeEach(async ({ page }) => {
    // Auto-mock returns [] for /api/pharmacy/** by default; we just need to
    // make sure the explicit export endpoint returns something parseable so
    // the click handler does not blow up before we observe the request.
    await page.route('**/api/pharmacy/claims/export/csv**', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'text/csv; charset=utf-8',
        headers: {
          'Content-Disposition': 'attachment; filename=pharmacy-claims-2026-05-09.csv',
        },
        body: CSV_BODY,
      }),
    );
    await page.goto('/pharmacy/claims', { waitUntil: 'domcontentloaded' });
  });

  test('renders the claims page with the AMU title and export buttons', async ({ page }) => {
    await expect(page.locator('h1', { hasText: /AMU|remboursement/i })).toBeVisible();
    await expect(page.locator('button', { hasText: 'Exporter CSV' })).toBeVisible();
    await expect(page.locator('button', { hasText: 'Exporter FHIR' })).toBeVisible();
  });

  test('clicking "Exporter CSV" fires GET /api/pharmacy/claims/export/csv', async ({ page }) => {
    const exportRequest = page.waitForRequest(
      (req) =>
        req.url().includes('/api/pharmacy/claims/export/csv') && req.method() === 'GET',
      { timeout: 10_000 },
    );
    await page.locator('button', { hasText: 'Exporter CSV' }).click();
    const req = await exportRequest;
    // Sanity: the URL is shaped as expected — bare path, no body, GET only.
    expect(req.url()).toContain('/pharmacy/claims/export/csv');
    expect(req.method()).toBe('GET');
  });

  test('page mounts without route-guard redirect to /login', async ({ page }) => {
    await page.waitForTimeout(500);
    expect(page.url()).toContain('/pharmacy/claims');
  });
});
