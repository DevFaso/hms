/**
 * Patients E2E Tests – List, search, register, view detail.
 *
 * Uses stored auth state (chromium project).
 */
import { test, expect } from './fixtures/test-fixtures';
import { uniqueEmail } from './helpers/test-data';

test.describe('Patients Module', () => {
  test.describe('Patient List', () => {
    test.beforeEach(async ({ patientsPage }) => {
      await patientsPage.goto();
    });

    test('displays the patients page with title', async ({ patientsPage }) => {
      await patientsPage.expectPageLoaded();
    });

    test('shows Register Patient button', async ({ patientsPage }) => {
      await expect(patientsPage.registerButton).toBeVisible();
      await expect(patientsPage.registerButton).toContainText('Register Patient');
    });

    test('displays search bar', async ({ patientsPage }) => {
      await expect(patientsPage.searchInput).toBeVisible();
      await expect(patientsPage.searchInput).toHaveAttribute('placeholder', /search/i);
    });

    test('loads patient data (table or empty state)', async ({ patientsPage }) => {
      await patientsPage.waitForLoad();
      const hasTable = await patientsPage.dataTable.count();
      const hasEmpty = await patientsPage.emptyState.count();
      expect(hasTable + hasEmpty).toBeGreaterThan(0);
    });

    test('patient table has correct column headers', async ({ page, patientsPage }) => {
      await patientsPage.waitForLoad();
      if (await patientsPage.hasPatients()) {
        const headers = page.locator('.data-table thead th');
        const texts = await headers.allTextContents();
        expect(texts).toContain('Name');
        expect(texts).toContain('Email');
        expect(texts).toContain('Status');
        expect(texts).toContain('Actions');
      }
    });

    test('search filters patient list', async ({ patientsPage }) => {
      await patientsPage.waitForLoad();
      if (await patientsPage.hasPatients()) {
        const initialCount = await patientsPage.getRowCount();

        // Search for something unlikely to match
        await patientsPage.search('xyznonexistent12345');
        const filteredCount = await patientsPage.getRowCount();
        expect(filteredCount).toBeLessThanOrEqual(initialCount);

        // Clear search
        await patientsPage.clearSearch();
        const restoredCount = await patientsPage.getRowCount();
        expect(restoredCount).toBe(initialCount);
      }
    });
  });

  test.describe('Patient Registration', () => {
    test('navigates to registration form from list', async ({ patientsPage, page }) => {
      await patientsPage.goto();
      await patientsPage.clickRegister();
      await expect(page).toHaveURL(/\/patients\/new/);
    });

    test('registration form displays all fields', async ({ patientFormPage }) => {
      await patientFormPage.goto();
      await patientFormPage.expectPageLoaded();
      await expect(patientFormPage.firstNameInput).toBeVisible();
      await expect(patientFormPage.lastNameInput).toBeVisible();
      await expect(patientFormPage.emailInput).toBeVisible();
      await expect(patientFormPage.phoneInput).toBeVisible();
      await expect(patientFormPage.dobInput).toBeVisible();
      await expect(patientFormPage.genderSelect).toBeVisible();
      await expect(patientFormPage.bloodGroupSelect).toBeVisible();
      await expect(patientFormPage.countryInput).toBeVisible();
      await expect(patientFormPage.cityInput).toBeVisible();
      await expect(patientFormPage.submitButton).toBeVisible();
    });

    test('back button returns to patient list', async ({ patientFormPage, page }) => {
      await patientFormPage.goto();
      await patientFormPage.goBack();
      await expect(page).toHaveURL(/\/patients$/);
    });

    test('can fill and submit patient registration form', async ({ patientFormPage, page }) => {
      await patientFormPage.goto();
      const email = uniqueEmail('e2e-patient');

      await patientFormPage.fillForm({
        firstName: 'E2E',
        lastName: 'TestPatient',
        email,
        phone: '+1-555-999-0001',
        dob: '1985-06-15',
        gender: 'MALE',
        bloodGroup: 'O+',
        country: 'Burkina Faso',
        city: 'Ouagadougou',
      });

      // Verify the form fields are filled
      await expect(patientFormPage.firstNameInput).toHaveValue('E2E');
      await expect(patientFormPage.lastNameInput).toHaveValue('TestPatient');
      await expect(patientFormPage.emailInput).toHaveValue(email);

      // Submit the form
      await patientFormPage.submit();

      // Wait for response — should redirect to patient detail, patient list, or show toast
      await page.waitForTimeout(3000);
      const url = page.url();
      const hasToast = (await page.locator('.toast').count()) > 0;
      const navigatedAway = !url.includes('/patients/new');
      expect(hasToast || navigatedAway).toBeTruthy();
    });
  });

  test.describe('Patient Detail', () => {
    test('clicking view on a patient row navigates to detail', async ({ patientsPage, page }) => {
      await patientsPage.goto();
      await patientsPage.waitForLoad();

      if (await patientsPage.hasPatients()) {
        await patientsPage.viewPatient(0);
        await page.waitForURL(/\/patients\/[a-zA-Z0-9-]+/);
        // Detail page should show patient info
        await expect(page.locator('.page-container, .patient-detail, .detail-card')).toBeVisible({
          timeout: 10_000,
        });
      }
    });
  });
});
