import { test as setup } from '@playwright/test';
import path from 'node:path';

const FAKE_JWT =
  'eyJhbGciOiJIUzM4NCJ9' +
  '.eyJzdWIiOiJzdXBlcmFkbWluIiwicm9sZXMiOlsiUk9MRV9TVVBFUl9BRE1JTiJdLCJpYXQiOjE3MDAwMDAwMDAsImV4cCI6OTk5OTk5OTk5OX0' +
  '.fake-signature-for-e2e-testing';

const MOCK_LOGIN_RESPONSE = {
  tokenType: 'Bearer',
  accessToken: FAKE_JWT,
  token: FAKE_JWT,
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

setup('debug login', async ({ page }) => {
  page.on('console', msg => console.log('[BROWSER]', msg.type(), msg.text()));
  page.on('pageerror', err => console.log('[PAGE ERROR]', err.message));
  page.on('request', req => {
    if (req.url().includes('/api/')) console.log('[REQ]', req.method(), req.url());
  });
  page.on('response', res => {
    if (res.url().includes('/api/')) console.log('[RES]', res.status(), res.url());
  });
  page.on('framenavigated', frame => {
    if (frame === page.mainFrame()) console.log('[NAV]', frame.url());
  });

  await page.route('**/api/**', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ content: [], totalElements: 0, totalPages: 0, size: 20, number: 0 }) }),
  );
  await page.route('**/api/auth/bootstrap-status', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ allowed: false }) }),
  );
  await page.route('**/api/auth/csrf-token', (route) => route.fulfill({ status: 204 }));
  await page.route('**/api/auth/login', async (route) => {
    console.log('[MOCK] login intercepted!');
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(MOCK_LOGIN_RESPONSE) });
  });

  await page.goto('http://localhost:4200/login', { waitUntil: 'domcontentloaded' });
  await page.locator('#username').fill('superadmin');
  await page.locator('#password').fill('TempPass123!');

  await Promise.all([
    page.waitForResponse('**/api/auth/login'),
    page.locator('button[type="submit"]').click(),
  ]);

  console.log('Login response received, current URL:', page.url());
  
  await page.waitForTimeout(3000);
  console.log('After 3s wait, URL:', page.url());
  
  const token = await page.evaluate(() => localStorage.getItem('auth_token'));
  console.log('Token in localStorage:', token ? token.substring(0, 50) + '...' : null);
  
  const errMsg = await page.locator('[role="alert"]').textContent().catch(() => null);
  console.log('Error message:', errMsg);
});
