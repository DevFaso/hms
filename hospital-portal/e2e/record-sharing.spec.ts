/**
 * Record Sharing & Consent Management E2E Tests.
 *
 * Covers page rendering, consent list loading, grant form interaction,
 * and record sharing / export flows via mocked API routes.
 *
 * Uses stored auth state (chromium project).
 */
import { test, expect } from './fixtures/test-fixtures';

// ── Mock payloads ────────────────────────────────────────────────────────────

const MOCK_CONSENT = {
  id: 'c-e2e-1',
  patient: { id: 'p1', firstName: 'Jane', lastName: 'Doe' },
  fromHospital: { id: 'h1', name: 'Hospital Alpha' },
  toHospital: { id: 'h2', name: 'Hospital Beta' },
  consentGiven: true,
  consentTimestamp: '2026-01-15T10:00:00',
  purpose: 'Referral follow-up',
  consentExpiration: null,
  consentType: 'TREATMENT',
  scope: 'ENCOUNTERS,PRESCRIPTIONS',
};

const CONSENT_PAGE = {
  content: [MOCK_CONSENT],
  totalElements: 1,
  totalPages: 1,
  size: 20,
  number: 0,
  first: true,
  last: true,
  empty: false,
};

const MOCK_PATIENT_RECORD = {
  patientId: 'p1',
  fullName: 'Jane Doe',
  fromHospitalId: 'h1',
  fromHospitalName: 'Hospital Alpha',
  toHospitalId: 'h2',
  toHospitalName: 'Hospital Beta',
  encounters: [],
  allergiesDetailed: [],
  treatments: [],
  labOrders: [],
  labResults: [],
  prescriptions: [],
  insuranceInfo: [],
  problems: [],
  surgicalHistory: [],
  advanceDirectives: [],
  encounterHistory: [],
};

const MOCK_RESOLVE_RESULT = {
  shareScope: 'INTRA_ORG',
  shareScopeLabel: 'Intra-organisation share',
  resolvedFromHospitalId: 'h1',
  resolvedFromHospitalName: 'Hospital Alpha',
  requestingHospitalId: 'h2',
  requestingHospitalName: 'Hospital Beta',
  consentActive: true,
  resolvedAt: '2026-04-16T12:00:00',
  patientRecord: MOCK_PATIENT_RECORD,
};

// ── Consent Management page ─────────────────────────────────────────────────

test.describe('Consent Management', () => {
  test.beforeEach(async ({ page }) => {
    // Override patient-consents list with a populated response
    await page.route('**/api/patient-consents?**', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(CONSENT_PAGE),
      }),
    );
  });

  test('page loads and shows consent table', async ({ page }) => {
    await page.goto('/consent-management', { waitUntil: 'domcontentloaded' });

    // Wait for loading to finish
    await page.waitForFunction(() => !document.querySelector('.loading-state'), {
      timeout: 15_000,
    });

    // Table should be visible with data
    const table = page.locator('.data-table table');
    await expect(table).toBeVisible({ timeout: 10_000 });
  });

  test('consent row displays patient and hospital info', async ({ page }) => {
    await page.goto('/consent-management', { waitUntil: 'domcontentloaded' });

    await page.waitForFunction(() => !document.querySelector('.loading-state'), {
      timeout: 15_000,
    });

    // Check that the consent row renders hospital names
    const row = page.locator('.data-table tbody tr').first();
    await expect(row).toBeVisible({ timeout: 10_000 });
    await expect(row).toContainText('Hospital Alpha');
    await expect(row).toContainText('Hospital Beta');
  });

  test('grant consent form opens and closes', async ({ page }) => {
    await page.goto('/consent-management', { waitUntil: 'domcontentloaded' });

    await page.waitForFunction(() => !document.querySelector('.loading-state'), {
      timeout: 15_000,
    });

    // Click grant button
    const grantBtn = page.locator('button', { hasText: /grant/i }).first();
    await grantBtn.click();

    // Modal should be visible
    const modal = page.locator('.modal-overlay');
    await expect(modal).toBeVisible({ timeout: 5_000 });

    // Cancel to close
    const cancelBtn = modal.locator('button', { hasText: /cancel/i });
    await cancelBtn.click();

    await expect(modal).not.toBeVisible({ timeout: 5_000 });
  });

  test('shows empty state when no consents exist', async ({ page }) => {
    // Override with empty page
    await page.route('**/api/patient-consents?**', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          content: [],
          totalElements: 0,
          totalPages: 0,
          size: 20,
          number: 0,
          first: true,
          last: true,
          empty: true,
        }),
      }),
    );

    await page.goto('/consent-management', { waitUntil: 'domcontentloaded' });

    await page.waitForFunction(() => !document.querySelector('.loading-state'), {
      timeout: 15_000,
    });

    const emptyState = page.locator('.empty-state');
    await expect(emptyState).toBeVisible({ timeout: 10_000 });
  });

  test('shows error state when API fails', async ({ page }) => {
    // Override with 500 error
    await page.route('**/api/patient-consents?**', (route) =>
      route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ message: 'Internal Server Error' }),
      }),
    );

    await page.goto('/consent-management', { waitUntil: 'domcontentloaded' });

    await page.waitForFunction(() => !document.querySelector('.loading-state'), {
      timeout: 15_000,
    });

    const errorState = page.locator('.error-state');
    await expect(errorState).toBeVisible({ timeout: 10_000 });

    // Retry button should exist
    const retryBtn = errorState.locator('button');
    await expect(retryBtn).toBeVisible();
  });
});

// ── Record Sharing API mocks ────────────────────────────────────────────────

test.describe('Record Sharing', () => {
  test.beforeEach(async ({ page }) => {
    // Mock the resolve endpoint
    await page.route('**/api/records/resolve**', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_RESOLVE_RESULT),
      }),
    );

    // Mock the share endpoint
    await page.route('**/api/records/share', (route) => {
      if (route.request().method() === 'POST') {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(MOCK_PATIENT_RECORD),
        });
      }
      return route.continue();
    });

    // Mock the export endpoint
    await page.route('**/api/records/export**', (route) => {
      if (route.request().method() === 'POST') {
        return route.fulfill({
          status: 200,
          contentType: 'application/pdf',
          body: Buffer.from('mock-pdf-content'),
        });
      }
      return route.continue();
    });

    // Mock consent grant
    await page.route('**/api/patient-consents/grant', (route) => {
      if (route.request().method() === 'POST') {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(MOCK_CONSENT),
        });
      }
      return route.continue();
    });

    // Mock consent revoke
    await page.route('**/api/patient-consents/revoke**', (route) => {
      if (route.request().method() === 'POST') {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({}),
        });
      }
      return route.continue();
    });

    // Mock consent list
    await page.route('**/api/patient-consents?**', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(CONSENT_PAGE),
      }),
    );
  });

  test('consent management page renders without console errors', async ({ page }) => {
    const errors: string[] = [];
    page.on('console', (msg) => {
      if (msg.type() === 'error') errors.push(msg.text());
    });

    await page.goto('/consent-management', { waitUntil: 'domcontentloaded' });

    await page.waitForFunction(() => !document.querySelector('.loading-state'), {
      timeout: 15_000,
    });

    // Filter out known non-critical errors
    const criticalErrors = errors.filter(
      (e) => !e.includes('404') && !e.includes('Failed to fetch') && !e.includes('net::ERR'),
    );
    expect(criticalErrors).toHaveLength(0);
  });

  test('record sharing endpoints respond correctly', async ({ page }) => {
    // Verify mock routes are set up by making a direct API call via page.evaluate
    const resolveResponse = await page.evaluate(async () => {
      const res = await fetch('/api/records/resolve?patientId=p1&requestingHospitalId=h2');
      return { status: res.status, ok: res.ok };
    });

    expect(resolveResponse.status).toBe(200);
    expect(resolveResponse.ok).toBeTruthy();
  });

  test('share endpoint accepts POST request', async ({ page }) => {
    const shareResponse = await page.evaluate(async () => {
      const res = await fetch('/api/records/share', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          patientId: 'p1',
          fromHospitalId: 'h1',
          toHospitalId: 'h2',
        }),
      });
      return { status: res.status, ok: res.ok };
    });

    expect(shareResponse.status).toBe(200);
  });

  test('export endpoint returns blob response', async ({ page }) => {
    const exportResponse = await page.evaluate(async () => {
      const res = await fetch(
        '/api/records/export?patientId=p1&fromHospitalId=h1&toHospitalId=h2&format=pdf',
        { method: 'POST' },
      );
      return { status: res.status, contentType: res.headers.get('content-type') };
    });

    expect(exportResponse.status).toBe(200);
    expect(exportResponse.contentType).toContain('application/pdf');
  });
});
