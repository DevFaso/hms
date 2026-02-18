/**
 * LoginPage – Page Object Model for the HMS login screen.
 */
import { type Page, type Locator, expect } from '@playwright/test';

export class LoginPage {
  readonly page: Page;

  /* ── Locators ── */
  readonly usernameInput: Locator;
  readonly passwordInput: Locator;
  readonly submitButton: Locator;
  readonly errorBanner: Locator;
  readonly rememberCheckbox: Locator;
  readonly togglePassword: Locator;
  readonly loginCard: Locator;
  readonly title: Locator;
  readonly subtitle: Locator;
  readonly forgotPasswordLink: Locator;
  readonly spinner: Locator;

  constructor(page: Page) {
    this.page = page;
    this.usernameInput = page.locator('#username');
    this.passwordInput = page.locator('#password');
    this.submitButton = page.locator('button[type="submit"]');
    this.errorBanner = page.locator('.error-banner');
    this.rememberCheckbox = page.locator('input[name="remember"]');
    this.togglePassword = page.locator('.toggle-password');
    this.loginCard = page.locator('.login-card');
    this.title = page.locator('.login-header h1');
    this.subtitle = page.locator('.subtitle');
    this.forgotPasswordLink = page.locator('.forgot-link').first();
    this.spinner = page.locator('.spinner');
  }

  /** Navigate to login page */
  async goto(): Promise<void> {
    await this.page.goto('/login', { waitUntil: 'networkidle' });
  }

  /** Fill username and password */
  async fillCredentials(username: string, password: string): Promise<void> {
    await this.usernameInput.fill(username);
    await this.passwordInput.fill(password);
  }

  /** Submit the login form */
  async submit(): Promise<void> {
    await this.submitButton.click();
  }

  /** Full login flow: navigate, fill, submit, wait for dashboard */
  async login(username: string, password: string): Promise<void> {
    await this.goto();
    await this.fillCredentials(username, password);
    await this.submit();
    await this.page.waitForURL('**/dashboard', { timeout: 15_000 });
  }

  /** Login expecting failure */
  async loginExpectingError(username: string, password: string): Promise<void> {
    await this.fillCredentials(username, password);
    await this.submit();
    await expect(this.errorBanner).toBeVisible({ timeout: 10_000 });
  }

  /** Check the login page is fully rendered */
  async expectPageLoaded(): Promise<void> {
    await expect(this.loginCard).toBeVisible();
    await expect(this.usernameInput).toBeVisible();
    await expect(this.passwordInput).toBeVisible();
    await expect(this.submitButton).toBeVisible();
    await expect(this.subtitle).toContainText('Hospital Management System');
  }
}
