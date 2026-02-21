/**
 * Auth E2E Tests – Login, logout, session management, validation.
 *
 * These tests run WITHOUT stored auth state (no-auth project)
 * so they test the actual login flow from scratch.
 *
 * Login Flow tests mock the backend API so they work without a live server.
 */
import { test, expect } from '@playwright/test';
import { LoginPage } from './pages/login.page';

/** Fake JWT – structurally valid (3 dot-separated parts), not cryptographically verified */
const FAKE_JWT =
  'eyJhbGciOiJIUzM4NCJ9' +
  '.eyJzdWIiOiJzdXBlcmFkbWluIiwicm9sZXMiOlsiUk9MRV9TVVBFUl9BRE1JTiJdLCJpYXQiOjE3MDAwMDAwMDAsImV4cCI6OTk5OTk5OTk5OX0' +
  '.fake-signature-for-e2e-testing';

const MOCK_LOGIN_RESPONSE = {
  tokenType: 'Bearer',
  accessToken: FAKE_JWT,
  refreshToken: FAKE_JWT,
  id: '00000000-0000-0000-0000-000000000001',
  username: 'superadmin',
  email: 'superadmin@seed.dev',
  firstName: 'System',
  lastName: 'SuperAdmin',
  phoneNumber: '+22600000000',
  roles: ['ROLE_SUPER_ADMIN'],
  roleName: 'ROLE_SUPER_ADMIN',
  active: true,
};

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
    // Mock the backend so these tests work without a live Spring Boot server.
    // They test Angular's login flow behaviour (token storage, redirect) not the API itself.
    test.beforeEach(async ({ page }) => {
      await page.route('**/auth/login', async (route) => {
        const body = route.request().postDataJSON() as { username?: string };
        if (body?.username === 'superadmin') {
          await route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify(MOCK_LOGIN_RESPONSE),
          });
        } else {
          await route.fulfill({
            status: 401,
            contentType: 'application/json',
            body: JSON.stringify({ message: 'Bad credentials' }),
          });
        }
      });
    });

    test('successful login redirects to dashboard', async ({ page }) => {
      await loginPage.fillCredentials('superadmin', 'TempPass123!');
      await Promise.all([
        page.waitForResponse('**/auth/login'),
        loginPage.submit(),
      ]);

      await page.waitForURL('**/dashboard', { timeout: 15_000 });
      await expect(page.locator('.welcome-banner')).toBeVisible({ timeout: 10_000 });
    });

    test('stores auth token in localStorage after login', async ({ page }) => {
      await loginPage.fillCredentials('superadmin', 'TempPass123!');
      await Promise.all([
        page.waitForResponse('**/auth/login'),
        loginPage.submit(),
      ]);
      await page.waitForURL('**/dashboard', { timeout: 15_000 });

      const token = await page.evaluate(() => localStorage.getItem('auth_token'));
      expect(token).toBeTruthy();
      expect(token!.split('.')).toHaveLength(3); // JWT format
    });

    test('stores user profile in localStorage after login', async ({ page }) => {
      await loginPage.fillCredentials('superadmin', 'TempPass123!');
      await Promise.all([
        page.waitForResponse('**/auth/login'),
        loginPage.submit(),
      ]);
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
      // Override the mock to add a delay so the spinner is observable
      await page.route('**/auth/login', async (route) => {
        await new Promise((r) => setTimeout(r, 1000));
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(MOCK_LOGIN_RESPONSE),
        });
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
