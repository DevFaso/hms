/**
 * Custom Playwright test fixtures for HMS E2E tests.
 *
 * Provides:
 *  - `loginPage`   – LoginPage POM
 *  - `shellPage`   – ShellPage POM (sidebar + topbar)
 *  - `dashboardPage` – DashboardPage POM (with mocked backend API)
 *  - `patientsPage`  – PatientListPage POM
 *  - `apiHelper`     – Direct API helper for test data setup
 */
import { test as base, expect } from '@playwright/test';

import { LoginPage } from '../pages/login.page';
import { ShellPage } from '../pages/shell.page';
import { DashboardPage } from '../pages/dashboard.page';
import { PatientListPage } from '../pages/patient-list.page';
import { PatientFormPage } from '../pages/patient-form.page';
import { ApiHelper } from '../helpers/api-helper';

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

/* ────── Fixture types ────── */
type HmsFixtures = {
  loginPage: LoginPage;
  shellPage: ShellPage;
  dashboardPage: DashboardPage;
  patientsPage: PatientListPage;
  patientFormPage: PatientFormPage;
  apiHelper: ApiHelper;
};

/* ────── Extend base test ────── */
export const test = base.extend<HmsFixtures>({
  loginPage: async ({ page }, use) => {
    await use(new LoginPage(page));
  },
  shellPage: async ({ page }, use) => {
    await use(new ShellPage(page));
  },
  dashboardPage: async ({ page }, use) => {
    // Mock backend API calls so dashboard tests work without a live server.
    // The interceptor prepends /api, so URLs arrive as /api/super-admin/summary etc.
    await page.route('**/super-admin/summary**', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(MOCK_ADMIN_SUMMARY) }),
    );
    await page.route('**/me/clinical-dashboard**', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(MOCK_CLINICAL_DASHBOARD) }),
    );
    await page.route('**/me/critical-alerts**', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: [] }) }),
    );
    await page.route('**/me/inbox-counts**', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ messages: 0, notifications: 0 }) }),
    );
    await page.route('**/me/on-call**', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ isOnCall: false }) }),
    );
    await page.route('**/me/roomed-patients**', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: [] }) }),
    );
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

export { expect };
