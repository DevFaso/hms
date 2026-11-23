import { test, expect } from './fixtures/test-fixtures';
import { signInAs } from './helpers/role-session';

test.use({ storageState: { cookies: [], origins: [] } });

test('D1636: production shell paints within three seconds while fonts and bundles are stalled', async ({
  page,
  baseURL,
}, info) => {
  await signInAs(page, 'ROLE_RECEPTIONIST', baseURL!);
  await page.clock.install();
  let release!: () => void;
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.route('**/*.js', async (route) => {
    await held;
    await route.continue();
  });
  await page.route('https://fonts.googleapis.com/**', async (route) => {
    await held;
    await route.fulfill({ contentType: 'text/css', body: '' });
  });
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Network.enable');
  await cdp.send('Network.emulateNetworkConditions', {
    offline: false,
    latency: 400,
    downloadThroughput: 50000,
    uploadThroughput: 50000,
    connectionType: 'cellular3g',
  });
  const started = Date.now();
  try {
    await page.goto('/patients', { waitUntil: 'commit' });
    await expect(page.locator('#boot-screen')).toBeVisible({
      timeout: 3000 - Math.min(2500, Date.now() - started),
    });
    expect(Date.now() - started).toBeLessThan(3000);
    await info.attach('startup-timing', {
      contentType: 'application/json',
      body: JSON.stringify({
        shellVisibleMs: Date.now() - started,
        latencyMs: 400,
        downloadBytesPerSecond: 50000,
        delayedAssets: ['application bundles', 'optional remote font stylesheets'],
      }),
    });
    for (const width of [390, 768, 1024, 1440]) {
      await page.setViewportSize({ width, height: 900 });
      expect(
        await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1),
      ).toBe(true);
      await page.screenshot({ path: info.outputPath(`boot-${width}.png`) });
    }
    await page.clock.fastForward(21000);
    await expect(page.locator('#boot-retry')).toBeVisible();
    await expect(page.locator('#boot-message')).toContainText('connexion');
  } finally {
    release();
    await cdp.send('Network.emulateNetworkConditions', {
      offline: false,
      latency: 0,
      downloadThroughput: -1,
      uploadThroughput: -1,
    });
    await cdp.detach();
  }
  await expect(page.locator('h1')).toBeVisible();
  await expect(page.locator('#boot-screen')).toHaveCount(0);
});

test('lazy navigation keeps the open page and shows a slow-connection state', async ({
  page,
  baseURL,
}) => {
  await signInAs(page, 'ROLE_RECEPTIONIST', baseURL!);
  await page.clock.install();
  await page.goto('/dashboard');
  await expect(page.locator('#main-content h1').first()).toBeVisible();
  let release!: () => void;
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.route('**/*.js', async (route) => {
    await held;
    await route.continue();
  });
  try {
    await page.locator('a[href="/patients"]').first().click();
    const loading = page.getByTestId('route-loading');
    await expect(loading).toBeVisible({ timeout: 3000 });
    await expect(loading).not.toHaveClass(/initial/);
    await expect(page.locator('#main-content h1').first()).toBeVisible();
    await page.clock.fastForward(21000);
    await expect(loading).toContainText('connexion');
  } finally {
    release();
  }
  await expect(page).toHaveURL((url) => url.pathname === '/patients');
  await expect(page.getByTestId('route-loading')).toHaveCount(0);
  await expect(page.locator('#main-content h1').first()).toBeVisible();
});
