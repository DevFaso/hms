/**
 * Auth E2E Tests – Login, logout, session management, validation.
 *
 * These tests run WITHOUT stored auth state (no-auth project)
 * so they test the actual login flow from scratch.
 */
import { test, expect } from '@playwright/test';
import { LoginPage } from './pages/login.page';

test.describe('Authentication', () => {
  let loginPage: LoginPage;

  test.beforeEach(async ({ page }) => {
    loginPage = new LoginPage(page);
    await loginPage.goto();
  });

  test.describe('Login Page Rendering', () => {
    test('displays login form with all elements', async () => {
      await loginPage.expectPageLoaded();
    });

    test('has correct page title', async ({ page }) => {
      await expect(page).toHaveTitle(/HMS|Hospital|Login/i);
    });

    test('username and password fields are empty by default', async () => {
      await expect(loginPage.usernameInput).toHaveValue('');
      await expect(loginPage.passwordInput).toHaveValue('');
    });

    test('password field is masked by default', async () => {
      await expect(loginPage.passwordInput).toHaveAttribute('type', 'password');
    });

    test('remember me checkbox is checked by default', async () => {
      await expect(loginPage.rememberCheckbox).toBeChecked();
    });

    test('submit button is enabled', async () => {
      await expect(loginPage.submitButton).toBeEnabled();
    });

    test('displays HMS subtitle', async () => {
      await expect(loginPage.subtitle).toContainText('Hospital Management System');
    });
  });

  test.describe('Password Visibility Toggle', () => {
    test('toggles password visibility on click', async () => {
      await loginPage.passwordInput.fill('TestPassword');
      await expect(loginPage.passwordInput).toHaveAttribute('type', 'password');

      await loginPage.togglePassword.click();
      await expect(loginPage.passwordInput).toHaveAttribute('type', 'text');

      await loginPage.togglePassword.click();
      await expect(loginPage.passwordInput).toHaveAttribute('type', 'password');
    });
  });

  test.describe('Form Validation', () => {
    test('shows error when submitting empty form', async () => {
      await loginPage.submit();
      await expect(loginPage.errorBanner).toBeVisible();
      await expect(loginPage.errorBanner).toContainText(/required/i);
    });

    test('shows error with only username filled', async () => {
      await loginPage.usernameInput.fill('someuser');
      await loginPage.submit();
      await expect(loginPage.errorBanner).toBeVisible();
    });

    test('shows error with only password filled', async () => {
      await loginPage.passwordInput.fill('somepass');
      await loginPage.submit();
      await expect(loginPage.errorBanner).toBeVisible();
    });
  });

  test.describe('Login Flow', () => {
    test('successful login redirects to dashboard', async ({ page }) => {
      await loginPage.fillCredentials('superadmin', 'TempPass123!');
      await loginPage.submit();

      await page.waitForURL('**/dashboard', { timeout: 15_000 });
      await expect(page.locator('.welcome-banner')).toBeVisible({ timeout: 10_000 });
    });

    test('stores auth token in localStorage after login', async ({ page }) => {
      await loginPage.fillCredentials('superadmin', 'TempPass123!');
      await loginPage.submit();
      await page.waitForURL('**/dashboard', { timeout: 15_000 });

      const token = await page.evaluate(() => localStorage.getItem('auth_token'));
      expect(token).toBeTruthy();
      expect(token!.split('.')).toHaveLength(3); // JWT format
    });

    test('stores user profile in localStorage after login', async ({ page }) => {
      await loginPage.fillCredentials('superadmin', 'TempPass123!');
      await loginPage.submit();
      await page.waitForURL('**/dashboard', { timeout: 15_000 });

      const profile = await page.evaluate(() => {
        const raw = localStorage.getItem('user_profile');
        return raw ? JSON.parse(raw) : null;
      });
      expect(profile).toBeTruthy();
      expect(profile.username).toBe('superadmin');
      expect(profile.roles).toBeDefined();
      expect(Array.isArray(profile.roles)).toBe(true);
    });

    test('failed login shows error message', async () => {
      await loginPage.fillCredentials('invaliduser', 'wrongpassword');
      await loginPage.submit();
      await expect(loginPage.errorBanner).toBeVisible({ timeout: 10_000 });
    });

    test('login button shows spinner during request', async ({ page }) => {
      // Slow down the network to observe loading state
      await page.route('**/auth/login', async (route) => {
        await new Promise((r) => setTimeout(r, 1000));
        await route.continue();
      });

      await loginPage.fillCredentials('superadmin', 'TempPass123!');
      await loginPage.submit();

      // Spinner should appear while loading
      await expect(loginPage.spinner).toBeVisible({ timeout: 2_000 });
    });
  });

  test.describe('Session & Redirect', () => {
    test('unauthenticated user is redirected to login', async ({ page }) => {
      await page.goto('/dashboard');
      await page.waitForURL('**/login', { timeout: 10_000 });
    });

    test('unauthenticated access to /patients redirects to login', async ({ page }) => {
      await page.goto('/patients');
      await page.waitForURL('**/login', { timeout: 10_000 });
    });

    test('unauthenticated access to /staff redirects to login', async ({ page }) => {
      await page.goto('/staff');
      await page.waitForURL('**/login', { timeout: 10_000 });
    });
  });
});
