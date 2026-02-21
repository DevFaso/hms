/**
 * PatientFormPage – Page Object Model for the patient registration form.
 */
import { type Page, type Locator, expect } from '@playwright/test';

export class PatientFormPage {
  readonly page: Page;

  /* ── Locators ── */
  readonly pageTitle: Locator;
  readonly backButton: Locator;
  readonly form: Locator;
  readonly firstNameInput: Locator;
  readonly lastNameInput: Locator;
  readonly emailInput: Locator;
  readonly phoneInput: Locator;
  readonly dobInput: Locator;
  readonly genderSelect: Locator;
  readonly bloodGroupSelect: Locator;
  readonly countryInput: Locator;
  readonly cityInput: Locator;
  readonly submitButton: Locator;
  readonly successMessage: Locator;
  readonly errorMessage: Locator;

  constructor(page: Page) {
    this.page = page;
    this.pageTitle = page.locator('main .page-title');
    this.backButton = page.locator('a:has-text("Back to Patients")');
    this.form = page.locator('.form-card');
    this.firstNameInput = page.locator('#firstName');
    this.lastNameInput = page.locator('#lastName');
    this.emailInput = page.locator('#email');
    this.phoneInput = page.locator('#phonePrimary');
    this.dobInput = page.locator('#dob');
    this.genderSelect = page.locator('#gender');
    this.bloodGroupSelect = page.locator('#bloodType');
    this.countryInput = page.locator('#country');
    this.cityInput = page.locator('#city');
    this.submitButton = page.locator('button[type="submit"]');
    this.successMessage = page.locator('.success-message, .toast-success, .toast.success');
    this.errorMessage = page.locator('.error-message, .error-banner, .toast-error, .toast.error');
  }

  /** Navigate to patient form */
  async goto(): Promise<void> {
    await this.page.goto('/patients/new', { waitUntil: 'networkidle' });
  }

  /** Assert form page is loaded */
  async expectPageLoaded(): Promise<void> {
    await expect(this.pageTitle).toContainText('Register Patient');
    await expect(this.form).toBeVisible();
  }

  /** Fill the registration form */
  async fillForm(data: {
    firstName: string;
    lastName: string;
    email: string;
    phone?: string;
    dob?: string;
    gender?: string;
    bloodGroup?: string;
    country?: string;
    city?: string;
  }): Promise<void> {
    await this.firstNameInput.fill(data.firstName);
    await this.lastNameInput.fill(data.lastName);
    await this.emailInput.fill(data.email);
    if (data.phone) await this.phoneInput.fill(data.phone);
    if (data.dob) await this.dobInput.fill(data.dob);
    if (data.gender) await this.genderSelect.selectOption(data.gender);
    if (data.bloodGroup) await this.bloodGroupSelect.selectOption(data.bloodGroup);
    if (data.country) await this.countryInput.fill(data.country);
    if (data.city) await this.cityInput.fill(data.city);
  }

  /** Submit the form */
  async submit(): Promise<void> {
    await this.submitButton.click();
  }

  /** Fill form and submit */
  async registerPatient(data: {
    firstName: string;
    lastName: string;
    email: string;
    phone?: string;
    dob?: string;
    gender?: string;
    bloodGroup?: string;
    country?: string;
    city?: string;
  }): Promise<void> {
    await this.fillForm(data);
    await this.submit();
  }

  /** Go back to patients list */
  async goBack(): Promise<void> {
    await this.backButton.click();
    await this.page.waitForURL('**/patients');
  }
}
