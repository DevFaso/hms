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
      await expect(page.getByTestId('api-timeout-write')).toHaveCount(0);
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

test('a patient document upload without progress reporting survives the ordinary deadline', async ({
  page,
  baseURL,
}) => {
  await signInAs(page, 'ROLE_PATIENT', baseURL!);
  let uploadCalls = 0;
  let release!: () => void;
  const held = new Promise<void>((resolve) => (release = resolve));
  await page.route('**/api/me/patient/documents**', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({ json: { data: { content: [], totalElements: 0 } } });
      return;
    }
    uploadCalls++;
    await held;
    await route.fulfill({
      json: {
        data: {
          id: 'slow-document',
          displayName: 'slow-report.pdf',
          documentType: 'OTHER',
          fileSizeBytes: 6,
        },
      },
    });
  });
  try {
    await page.goto('/my-documents');
    await expect(page.getByTestId('document-upload-toggle')).toBeVisible();
    await page.clock.install();
    await page.getByTestId('document-upload-toggle').click();
    await page.locator('#portal-document-file').setInputFiles({
      name: 'slow-report.pdf',
      mimeType: 'application/pdf',
      buffer: Buffer.from('report'),
    });
    await page.locator('.upload-form button.btn-primary').click();
    await expect.poll(() => uploadCalls).toBe(1);
    await page.clock.fastForward(30000);
    await expect(page.getByTestId('api-timeout-banner')).toHaveCount(0);
    await expect(page.locator('.upload-form button.btn-primary')).toBeDisabled();
    release();
    await expect(page.locator('.doc-name')).toHaveText('slow-report.pdf');
    expect(uploadCalls).toBe(1);
  } finally {
    release();
  }
});

test('a generated PDF export completes beyond the ordinary deadline', async ({ page, baseURL }) => {
  await signInAs(page, 'ROLE_SUPER_ADMIN', baseURL!);
  let exportCalls = 0;
  let release!: () => void;
  const held = new Promise<void>((resolve) => (release = resolve));
  await page.route('**/api/lab-test-definitions/export/pdf', async (route) => {
    exportCalls++;
    await held;
    await route.fulfill({ contentType: 'application/pdf', body: '%PDF-1.4 slow export' });
  });
  try {
    await page.goto('/lab-test-config');
    await expect(page.locator('.btn-pdf')).toBeVisible();
    await page.clock.install();
    const download = page.waitForEvent('download');
    await page.locator('.btn-pdf').click();
    await expect.poll(() => exportCalls).toBe(1);
    await page.clock.fastForward(30000);
    await expect(page.getByTestId('api-timeout-banner')).toHaveCount(0);
    release();
    expect((await download).suggestedFilename()).toBe('lab-test-definitions.pdf');
    expect(exportCalls).toBe(1);
  } finally {
    release();
  }
});

test('a stalled refresh retains the open patient view, its input and the existing session', async ({
  page,
  baseURL,
}) => {
  await signInAs(page, 'ROLE_SUPER_ADMIN', baseURL!);
  let unauthorized = false;
  let refreshCalls = 0;
  let logoutCalls = 0;
  let release!: () => void;
  const held = new Promise<void>((resolve) => (release = resolve));
  await page.route('**/api/patients**', async (route) => {
    if (!unauthorized) {
      unauthorized = true;
      await route.fulfill({ status: 401, json: { message: 'Expired access token' } });
    } else {
      await route.fulfill({
        json: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 },
      });
    }
  });
  await page.route('**/api/auth/token/refresh', async (route) => {
    refreshCalls++;
    await held;
    await route
      .fulfill({ status: 503, json: { message: 'Temporarily unavailable' } })
      .catch(() => undefined);
  });
  await page.route('**/api/auth/logout', async (route) => {
    logoutCalls++;
    await route.fulfill({ status: 204 });
  });
  try {
    await page.clock.install();
    await page.goto('/patients');
    const search = page.locator('.filter-toolbar .search-bar input');
    await expect(search).toBeVisible();
    await expect.poll(() => refreshCalls).toBe(1);
    const token = await page.evaluate(() => localStorage.getItem('auth_token'));
    await search.fill('Keep this input');
    await page.clock.fastForward(30000);
    await expect(page).toHaveURL(/\/patients(?:[?#]|$)/);
    await expect(search).toHaveValue('Keep this input');
    await expect(page.getByTestId('api-timeout-banner')).toBeVisible();
    await expect(page.getByTestId('api-timeout-write')).toHaveCount(0);
    expect(await page.evaluate(() => localStorage.getItem('auth_token'))).toBe(token);
    expect(logoutCalls).toBe(0);
    expect(refreshCalls).toBe(1);
  } finally {
    release();
  }
});

test('a late billing summary cannot replace the range the user most recently applied', async ({
  page,
  baseURL,
}) => {
  await signInAs(page, 'ROLE_SUPER_ADMIN', baseURL!);
  let summaries = 0;
  let release!: () => void;
  const held = new Promise<void>((resolve) => (release = resolve));
  await page.route('**/api/billing-invoices/**', async (route) => {
    if (route.request().url().includes('/summary')) {
      const requestNumber = ++summaries;
      if (requestNumber === 1) await held;
      await route.fulfill({
        json: {
          total: requestNumber === 1 ? 99 : 2,
          paid: 0,
          outstanding: 0,
          overdue: 0,
          totalRevenue: 0,
          balance: 0,
        },
      });
    } else {
      await route.fulfill({
        json: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 },
      });
    }
  });
  try {
    await page.goto('/billing');
    await expect.poll(() => summaries).toBe(1);
    const total = page.locator('.summary-cards .card-value').first();
    await expect(total).toHaveText('—');
    await page.locator('input[type="date"]').first().fill('2026-02-01');
    await page.locator('.server-filters button.btn-secondary').first().click();
    await expect.poll(() => summaries).toBe(2);
    await expect(total).toHaveText('2');
    const response = page.waitForResponse(
      (value) =>
        value.url().includes('/billing-invoices/summary') && !value.url().includes('fromDate'),
    );
    release();
    await (await response).finished();
    await page.evaluate(
      () =>
        new Promise<void>((resolve) =>
          requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
        ),
    );
    await expect(total).toHaveText('2');
  } finally {
    release();
  }
});

test('a business failure after a successful refresh keeps the user on the patient screen', async ({
  page,
  baseURL,
}) => {
  await signInAs(page, 'ROLE_SUPER_ADMIN', baseURL!);
  const payload = Buffer.from(
    JSON.stringify({ sub: 'layout-guard', roles: ['ROLE_SUPER_ADMIN'], exp: 9999999999 }),
  ).toString('base64url');
  const token = `eyJhbGciOiJIUzI1NiJ9.${payload}.refreshed-signature`;
  let firstUrl = '';
  let logoutCalls = 0;
  await page.route('**/api/auth/token/refresh', (route) =>
    route.fulfill({ json: { accessToken: token } }),
  );
  await page.route('**/api/auth/logout', async (route) => {
    logoutCalls++;
    await route.fulfill({ status: 204 });
  });
  await page.route('**/api/patients**', async (route) => {
    if (!firstUrl) {
      firstUrl = route.request().url();
      await route.fulfill({ status: 401, json: { message: 'Expired access token' } });
    } else {
      await route.fulfill({ status: 500, json: { message: 'Temporarily unavailable' } });
    }
  });
  const retried = page.waitForResponse(
    (value) =>
      value.url() === firstUrl &&
      value.status() === 500 &&
      value.request().headers()['authorization'] === `Bearer ${token}`,
  );
  await page.goto('/patients');
  await (await retried).finished();
  await page.evaluate(
    () =>
      new Promise<void>((resolve) =>
        requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
      ),
  );
  await expect(page).toHaveURL(/\/patients(?:[?#]|$)/);
  await expect(page.locator('.filter-toolbar .search-bar input')).toBeVisible();
  expect(await page.evaluate(() => localStorage.getItem('auth_token'))).toBe(token);
  expect(logoutCalls).toBe(0);
});

test('an uncertain upload warning is cleared when the patient signs out', async ({
  page,
  baseURL,
}) => {
  await signInAs(page, 'ROLE_PATIENT', baseURL!);
  let uploadCalls = 0;
  let release!: () => void;
  const held = new Promise<void>((resolve) => (release = resolve));
  await page.route('**/api/me/patient/documents**', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({ json: { data: { content: [], totalElements: 0 } } });
      return;
    }
    uploadCalls++;
    await held;
    await route.fulfill({ status: 503 }).catch(() => undefined);
  });
  await page.route('**/api/auth/logout', (route) => route.fulfill({ status: 204 }));
  try {
    await page.goto('/my-documents');
    await page.clock.install();
    await page.getByTestId('document-upload-toggle').click();
    await page
      .locator('#portal-document-file')
      .setInputFiles({
        name: 'slow-report.pdf',
        mimeType: 'application/pdf',
        buffer: Buffer.from('report'),
      });
    await page.locator('.upload-form button.btn-primary').click();
    await expect.poll(() => uploadCalls).toBe(1);
    await page.clock.fastForward(121000);
    await expect(page.getByTestId('api-timeout-write')).toBeVisible();
    await page.getByTestId('shell-profile-btn').click();
    await page.getByTestId('shell-profile-menu').locator('button.logout').click();
    await expect(page).toHaveURL(/\/login(?:[?#]|$)/);
    await expect(page.getByTestId('api-timeout-banner')).toHaveCount(0);
    expect(await page.evaluate(() => localStorage.getItem('auth_token'))).toBeNull();
  } finally {
    release();
  }
});
