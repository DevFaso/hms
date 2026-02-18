/**
 * Custom Playwright test fixtures for HMS E2E tests.
 *
 * Provides:
 *  - `loginPage`   – LoginPage POM
 *  - `shellPage`   – ShellPage POM (sidebar + topbar)
 *  - `dashboardPage` – DashboardPage POM
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
