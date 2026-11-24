/**
 * The portal's HTTP security headers and Content-Security-Policy, against the
 * real nginx image (the headers live in nginx/security-headers.conf, so the
 * `ng serve` dev server has none and this spec skips itself there).
 *
 * Run it against a locally built image:
 *   docker build -t hms-portal hospital-portal
 *   docker run --rm -p 8099:80 -e API_BASE_URL=http://127.0.0.1:9 hms-portal
 *   PLAYWRIGHT_BASE_URL=http://localhost:8099 npx playwright test security-headers \
 *     --project=chromium --no-deps
 *
 * Every main journey screen is opened as its working role (API mocked) and the
 * spec fails on ANY CSP violation, enforced or report-only, so the policy can
 * be switched from Report-Only to enforced with evidence.
 */
import type { Page } from '@playwright/test';
import { test, expect } from './fixtures/test-fixtures';
import { signInAs } from './helpers/role-session';
import { MAIN_JOURNEY_ROLES, MAIN_JOURNEY_ROUTES } from './helpers/main-journey-routes';

test.use({ storageState: { cookies: [], origins: [] } });

type Violation = { directive: string; blocked: string; source: string };

async function recordViolations(page: Page): Promise<() => Promise<Violation[]>> {
  const console: string[] = [];
  page.on('console', (message) => {
    if (/Content[- ]Security[- ]Policy/i.test(message.text())) console.push(message.text());
  });
  await page.addInitScript(() => {
    const seen: unknown[] = [];
    (window as unknown as { __csp: unknown[] }).__csp = seen;
    document.addEventListener('securitypolicyviolation', (event) =>
      seen.push({
        directive: event.violatedDirective,
        blocked: event.blockedURI,
        source: `${event.sourceFile}:${event.lineNumber}`,
      }),
    );
  });
  return async () => {
    const events = (await page.evaluate(
      () => (window as unknown as { __csp?: Violation[] }).__csp ?? [],
    )) as Violation[];
    return [
      ...events,
      ...console.map((text) => ({ directive: 'console', blocked: text, source: '' })),
    ];
  };
}

async function headersOrSkip(page: Page, path: string) {
  const response = await page.goto(path);
  const headers = response?.headers() ?? {};
  const policy =
    headers['content-security-policy'] ?? headers['content-security-policy-report-only'];
  test.skip(!policy, 'no CSP header: not served by the nginx image (ng serve)');
  return { headers, policy: policy! };
}

test('the SPA shell carries every security header', async ({ page }) => {
  const { headers, policy } = await headersOrSkip(page, '/login');
  expect(headers['strict-transport-security']).toBe('max-age=604800; includeSubDomains');
  expect(headers['x-frame-options']).toBe('DENY');
  expect(headers['x-content-type-options']).toBe('nosniff');
  expect(headers['referrer-policy']).toBe('strict-origin-when-cross-origin');
  expect(headers['permissions-policy']).toContain('camera=(self)');
  expect(policy).toContain("frame-ancestors 'none'");
  expect(policy).not.toContain('__CSP_SCRIPT_HASHES__');
  expect(policy).toMatch(/script-src 'self'( 'sha256-[A-Za-z0-9+/=]+')+;/);
});

test('the login page loads with no CSP violation', async ({ page }) => {
  const violations = await recordViolations(page);
  await headersOrSkip(page, '/login');
  await expect(page.locator('app-root form, app-root input').first()).toBeVisible();
  expect(await violations()).toEqual([]);
});

test('blob previews (image, PDF frame, audio) are allowed', async ({ page, baseURL }) => {
  const violations = await recordViolations(page);
  await signInAs(page, 'ROLE_DOCTOR', baseURL!);
  await headersOrSkip(page, '/patients');
  await page.evaluate(async () => {
    const pixel = Uint8Array.from(
      atob(
        'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=',
      ),
      (c) => c.charCodeAt(0),
    );
    const img = document.createElement('img');
    img.src = URL.createObjectURL(new Blob([pixel], { type: 'image/png' }));
    const frame = document.createElement('iframe');
    frame.src = URL.createObjectURL(new Blob(['%PDF-1.4\n%%EOF'], { type: 'application/pdf' }));
    const audio = document.createElement('audio');
    audio.src = URL.createObjectURL(new Blob([new Uint8Array(8)], { type: 'audio/webm' }));
    document.body.append(img, frame, audio);
    await new Promise((resolve) => setTimeout(resolve, 500));
  });
  expect(await violations()).toEqual([]);
});

for (const path of MAIN_JOURNEY_ROUTES) {
  test(`${path} loads with no CSP violation`, async ({ page, baseURL }) => {
    const violations = await recordViolations(page);
    await signInAs(page, MAIN_JOURNEY_ROLES[path] ?? 'ROLE_PATIENT', baseURL!);
    await headersOrSkip(page, path);
    await expect(page.locator('#boot-screen')).toHaveCount(0, { timeout: 15_000 });
    await page.waitForLoadState('networkidle');
    expect(await violations()).toEqual([]);
  });
}
