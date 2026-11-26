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

test('D1636: the sign-in form never shows without its words while the language file is late', async ({
  page,
}) => {
  // r44: on Slow 3G the form appeared at 24 s with no label, no button caption
  // and « · · · » placeholders, because fr.json (180 KB) arrived after it.
  let release!: () => void;
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.route('**/assets/i18n/*.json', async (route) => {
    await held;
    await route.continue();
  });
  const wordless = () =>
    page.evaluate(() => {
      const shown = (el: Element) => (el as HTMLElement).getClientRects().length > 0;
      return (
        Array.from(
          document.querySelectorAll('app-root label, app-root button, app-root h1, app-root a'),
        )
          .filter(shown)
          .filter((el) => !el.closest('#boot-screen'))
          // An icon-only control is worded by its aria-label.
          .map((el) => (el.getAttribute('aria-label') ?? el.textContent ?? '').trim())
          .filter((text) => text === '' || /^[A-Z_]+(\.[A-Z0-9_]+)+$/.test(text))
      );
    });
  try {
    await page.goto('/login', { waitUntil: 'load' });
    // Loading feedback stays up for as long as the words are missing.
    for (let sample = 0; sample < 6; sample++) {
      expect(await wordless(), 'no empty or raw-key label, caption or title').toEqual([]);
      await expect(
        page.locator('#boot-screen, [data-testid="route-loading"]').first(),
      ).toBeVisible();
      await page.waitForTimeout(500);
    }
  } finally {
    release();
  }
  await expect(page.locator('label[for="username"]')).toContainText("Nom d'utilisateur");
  await expect(page.locator('#boot-screen')).toHaveCount(0);
  expect(await wordless()).toEqual([]);
});
