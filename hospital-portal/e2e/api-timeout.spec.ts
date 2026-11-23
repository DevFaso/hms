import AxeBuilder from '@axe-core/playwright';
import { test, expect } from './fixtures/test-fixtures';
import { signInAs } from './helpers/role-session';
import en from '../src/assets/i18n/en.json';
import fr from '../src/assets/i18n/fr.json';
import es from '../src/assets/i18n/es.json';

test.use({ storageState: { cookies: [], origins: [] } });

for (const [lang, strings] of Object.entries({ en, fr, es })) {
  test(`D3602: stalled sign-in is bounded, translated, preserves input and allows manual retry (${lang})`, async ({
    page,
    baseURL,
  }, info) => {
    const origin = new URL(baseURL!).origin;
    await page.route('**/*', (route) =>
      new URL(route.request().url()).origin === origin
        ? route.fallback()
        : route.abort('blockedbyclient'),
    );
    await page.addInitScript((lang) => {
      localStorage.clear();
      localStorage.setItem('lang', lang);
    }, lang);
    await page.route('**/api/auth/bootstrap-status', (route) =>
      route.fulfill({ json: { allowed: false } }),
    );
    let calls = 0;
    let release!: () => void;
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    await page.route('**/api/auth/login', async (route) => {
      calls++;
      if (calls === 1) await held;
      await route
        .fulfill({ status: 401, json: { message: strings.LOGIN.LOGIN_FAILED } })
        .catch(() => undefined);
    });
    try {
      await page.goto('/login');
      await expect(page.locator('h1')).toHaveText(strings.LOGIN.TITLE);
      await page.clock.install();
      await page.locator('#username').fill('timeout-test');
      await page.locator('#password').fill('not-a-real-password');
      await page.locator('button.btn-login').click();
      await expect.poll(() => calls).toBe(1);
      await expect(page.locator('button.btn-login')).toBeDisabled();
      await page.clock.fastForward(21000);
      const banner = page.getByTestId('api-timeout-banner');
      await expect(banner).toBeVisible();
      await expect(banner).toContainText(strings.API_TIMEOUT.TITLE);
      await expect(banner).toContainText(strings.API_TIMEOUT.KEEP_INPUT);
      expect(
        (await new AxeBuilder({ page }).include('[data-testid="api-timeout-banner"]').analyze())
          .violations,
      ).toEqual([]);
      await expect(page.locator('button.btn-login')).toBeEnabled();
      await expect(page.locator('#username')).toHaveValue('timeout-test');
      expect(calls).toBe(1);
      await page.screenshot({ path: info.outputPath(`login-timeout-${lang}.png`) });
      await banner.locator('button').last().click();
      await page.locator('button.btn-login').click();
      await expect.poll(() => calls).toBe(2);
      await expect(page.locator('button.btn-login')).toBeEnabled();
      await expect(page.locator('#username')).toHaveValue('timeout-test');
    } finally {
      release();
    }
  });
}

for (const path of ['/billing', '/patients']) {
  test(`D3602: a stalled data view shows a retry; reload recovers ${path}`, async ({
    page,
    baseURL,
  }, info) => {
    await signInAs(page, 'ROLE_SUPER_ADMIN', baseURL!);
    let stalled = true;
    let calls = 0;
    let release!: () => void;
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    const endpoint = path === '/billing' ? '**/api/billing-invoices/**' : '**/api/patients**';
    await page.route(endpoint, async (route) => {
      calls++;
      if (stalled) await held;
      await route
        .fulfill({
          json: route.request().url().includes('/summary')
            ? { total: 7, paid: 2, outstanding: 5, overdue: 1, totalRevenue: 1500, balance: 2500 }
            : { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 },
        })
        .catch(() => undefined);
    });
    await page.clock.install();
    try {
      await page.goto(path);
      await expect(page.locator('#main-content h1').first()).toBeVisible();
      await expect.poll(() => calls).toBeGreaterThan(0);
      if (path === '/billing') {
        await expect(page.locator('.summary-cards .card-value').first()).toHaveText('—');
        await expect(page.locator('.summary-cards')).not.toContainText('0 F');
      }
      await page.clock.fastForward(21000);
      await expect(page.getByTestId('api-timeout-banner')).toBeVisible();
      if (path === '/billing') {
        await expect(page.getByTestId('billing-load-failed')).toBeVisible();
        await expect(page.locator('.summary-cards .card-value').first()).toHaveText('—');
      }
      for (const width of [390, 768, 1024, 1440]) {
        await page.setViewportSize({ width, height: 900 });
        await expect(page.getByTestId('api-timeout-retry')).toBeInViewport();
        await page.screenshot({ path: info.outputPath(`timeout-${path.slice(1)}-${width}.png`) });
      }
      stalled = false;
      release();
      await page.getByTestId('api-timeout-retry').click();
      await expect(page.getByTestId('api-timeout-banner')).toHaveCount(0);
      await expect(page.locator('#main-content h1').first()).toBeVisible();
      if (path === '/billing') {
        await expect(page.locator('.summary-cards .card-value').first()).toHaveText('7');
        await expect(page.getByTestId('billing-load-failed')).toHaveCount(0);
      }
    } finally {
      stalled = false;
      release();
    }
  });
}
