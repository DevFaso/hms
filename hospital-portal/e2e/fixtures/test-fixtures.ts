/**
 * Custom Playwright test fixtures for HMS E2E tests.
 *
 * Provides:
 *  - loginPage      – LoginPage POM
 *  - shellPage      – ShellPage POM (sidebar + topbar)
 *  - dashboardPage  – DashboardPage POM (with mocked backend API)
 *  - patientsPage   – PatientListPage POM
 *  - apiHelper      – Direct API helper for test data setup
 *  - autoMockApis   – Auto fixture: intercepts ALL /api/** calls with
 *                     empty-state 200 responses so the error interceptor
 *                     never sees a 401 and redirects to /login.
 */
import { test as base, type Page, type Route } from '@playwright/test';

import { LoginPage } from '../pages/login.page';
import { ShellPage } from '../pages/shell.page';
import { DashboardPage } from '../pages/dashboard.page';
import { PatientListPage } from '../pages/patient-list.page';
import { PatientFormPage } from '../pages/patient-form.page';
import { ApiHelper } from '../helpers/api-helper';

// ─── Shared mock payloads ────────────────────────────────────────────────────

/** Empty paginated Spring-Data page response. */
const EMPTY_PAGE = {
  content: [],
  totalElements: 0,
  totalPages: 0,
  size: 20,
  number: 0,
  first: true,
  last: true,
  empty: true,
};

/** Minimal mock for /api/super-admin/summary so .metrics-grid always renders */
const MOCK_ADMIN_SUMMARY = {
  totalPatients: 42,
  activeUsers: 15,
  totalHospitals: 3,
  pendingAppointments: 7,
  activeBeds: 120,
  todayAdmissions: 5,
  auditEvents: [],
};

/** Minimal mock for /api/me/clinical-dashboard */
const MOCK_CLINICAL_DASHBOARD = {
  kpis: [],
  alerts: [],
  roomedPatients: [],
  todayAppointments: [],
  recentPatients: [],
  quickActions: [],
};

// ─── Helpers ─────────────────────────────────────────────────────────────────

/** Fulfil a Playwright route with a JSON 200 response. */
function jsonOk(route: Route, body: unknown): Promise<void> {
  return route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(body),
  });
}

/**
 * Register comprehensive API mock routes on a page.
 *
 * Playwright routes have LIFO priority — last registered wins.
 * The catch-all is registered FIRST so specific overrides added AFTER
 * take priority for their exact URLs.
 */
async function registerApiMocks(page: Page): Promise<void> {
  // ── Catch-all: registered first (lowest priority in LIFO) ─────────────────
  // Any /api/** URL not matched by a later route gets an empty-page response,
  // preventing the Angular error interceptor from ever seeing a 401 and
  // redirecting to /login.
  await page.route('**/api/**', (r) => jsonOk(r, EMPTY_PAGE));

  // ── Array-based list endpoints (services return T[], not Page<T>) ─────────
  // Returning [] prevents TypeError when component does data.filter() / data.map()
  await page.route('**/api/assignments**', (r) => jsonOk(r, []));
  await page.route('**/api/messages**', (r) => jsonOk(r, []));
  await page.route('**/api/chat**', (r) => jsonOk(r, []));
  await page.route('**/api/notifications**', (r) => jsonOk(r, []));
  await page.route('**/api/audit**', (r) => jsonOk(r, []));
  await page.route('**/api/nurse-station**', (r) => jsonOk(r, []));
  await page.route('**/api/nurse**', (r) => jsonOk(r, []));
  await page.route('**/api/referrals**', (r) => jsonOk(r, []));
  await page.route('**/api/treatment-plans**', (r) => jsonOk(r, []));
  await page.route('**/api/consultations**', (r) => jsonOk(r, []));
  await page.route('**/api/imaging**', (r) => jsonOk(r, []));
  await page.route('**/api/lab**', (r) => jsonOk(r, []));
  await page.route('**/api/prescriptions**', (r) => jsonOk(r, []));
  await page.route('**/api/admissions**', (r) => jsonOk(r, []));
  await page.route('**/api/encounters**', (r) => jsonOk(r, []));
  await page.route('**/api/invoices**', (r) => jsonOk(r, []));
  await page.route('**/api/billing**', (r) => jsonOk(r, []));
  await page.route('**/api/appointments**', (r) => jsonOk(r, []));
  await page.route('**/api/staff**', (r) => jsonOk(r, []));
  await page.route('**/api/patients**', (r) => jsonOk(r, []));
  await page.route('**/api/organizations**', (r) => jsonOk(r, []));
  await page.route('**/api/hospitals**', (r) => jsonOk(r, []));

  // ── Paginated endpoints (services return Page<T> with .content) ──────────
  await page.route('**/api/users**', (r) => jsonOk(r, EMPTY_PAGE));
  await page.route('**/api/departments**', (r) => jsonOk(r, EMPTY_PAGE));

  // ── Non-paginated (object / array) ────────────────────────────────────────
  await page.route('**/api/lookup**', (r) => jsonOk(r, {}));
  await page.route('**/api/dashboard**', (r) => jsonOk(r, {}));
  await page.route('**/api/platform**', (r) => jsonOk(r, { features: [], settings: {} }));
  await page.route('**/api/feature-flags**', (r) => jsonOk(r, []));
  await page.route('**/api/permissions**', (r) => jsonOk(r, []));
  await page.route('**/api/roles**', (r) => jsonOk(r, []));

  // ── Dashboard / me (highest priority — registered last) ───────────────────
  await page.route('**/me/roomed-patients**', (r) => jsonOk(r, { data: [] }));
  await page.route('**/me/on-call**', (r) => jsonOk(r, { isOnCall: false }));
  await page.route('**/me/inbox-counts**', (r) => jsonOk(r, { messages: 0, notifications: 0 }));
  await page.route('**/me/critical-alerts**', (r) => jsonOk(r, { data: [] }));
  await page.route('**/me/clinical-dashboard**', (r) => jsonOk(r, MOCK_CLINICAL_DASHBOARD));
  await page.route('**/super-admin/summary**', (r) => jsonOk(r, MOCK_ADMIN_SUMMARY));
}

// ─── Fixture types ────────────────────────────────────────────────────────────

interface HmsFixtures {
  loginPage: LoginPage;
  shellPage: ShellPage;
  dashboardPage: DashboardPage;
  patientsPage: PatientListPage;
  patientFormPage: PatientFormPage;
  apiHelper: ApiHelper;
  /** Auto fixture — registered for every test; no need to destructure. */
  autoMockApis: void;
}

// ─── Extend base test ─────────────────────────────────────────────────────────

export const test = base.extend<HmsFixtures>({
  // Auto-fixture: runs before every test in the chromium project.
  // Intercepts all /api/** calls with empty-state 200 JSON so the Angular
  // error interceptor never redirects to /login mid-test.
  autoMockApis: [
    async ({ page }, use) => {
      await registerApiMocks(page);
      await use();
    },
    { auto: true },
  ],

  loginPage: async ({ page }, use) => {
    await use(new LoginPage(page));
  },

  shellPage: async ({ page }, use) => {
    await use(new ShellPage(page));
  },

  dashboardPage: async ({ page }, use) => {
    // registerApiMocks already ran via autoMockApis; just mount the POM.
    await use(new DashboardPage(page));
  },

  patientsPage: async ({ page }, use) => {
    await use(new PatientListPage(page));
  },

  patientFormPage: async ({ page }, use) => {
    await use(new PatientFormPage(page));
  },

  apiHelper: async ({ request }, use) => {
    const helper = new ApiHelper(request);
    await helper.authenticate('superadmin', 'TempPass123!');
    await use(helper);
  },
});

export { expect } from '@playwright/test';
