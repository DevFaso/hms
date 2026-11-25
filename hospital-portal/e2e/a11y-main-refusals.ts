import AxeBuilder from '@axe-core/playwright';
import { test, expect } from './fixtures/test-fixtures';
import { signInAs } from './helpers/role-session';
import { installLocalIconFonts } from './helpers/local-icon-fonts';
import { MAIN_FORM_JOURNEYS } from './helpers/main-form-journeys';
import { announcedRefusal, tabTo } from './helpers/keyboard-journey';
import { accessibleAtWidths } from './helpers/keyboard-journey';
import fr from '../src/assets/i18n/fr.json';

test.use({ storageState: { cookies: [], origins: [] } });

for (const journey of MAIN_FORM_JOURNEYS) {
  for (const width of [390, 1440]) {
    test(`main form refusal, focus trap and Escape: ${journey.route} at ${width}px`, async ({
      page,
      baseURL,
    }, info) => {
      await page.setViewportSize({ width, height: 1000 });
      await signInAs(page, journey.role, baseURL!);
      await installLocalIconFonts(page);
      let writes = 0;
      page.on('request', (request) => {
        if (request.method() === 'POST' && new URL(request.url()).pathname === journey.endpoint)
          writes++;
      });
      await page.goto(journey.route);
      await expect(page).toHaveURL((url) => url.pathname === journey.route);
      const opener = page.locator('.page-header .btn-primary').first();
      await tabTo(page, opener);
      await page.keyboard.press('Enter');
      const dialog = page.getByRole('dialog');
      await expect(dialog).toBeVisible();
      const focusable = dialog.locator(
        'button:visible:not([disabled]):not([tabindex="-1"]), input:visible:not([disabled]):not([tabindex="-1"]), select:visible:not([disabled]):not([tabindex="-1"]), textarea:visible:not([disabled]):not([tabindex="-1"])',
      );
      await tabTo(page, focusable.last());
      await page.keyboard.press('Tab');
      await expect(focusable.first()).toBeFocused();
      await page.keyboard.press('Shift+Tab');
      await expect(focusable.last()).toBeFocused();
      const submit = dialog.locator('.btn-primary').last();
      await tabTo(page, submit);
      await page.keyboard.press('Enter');
      const invalid = dialog.locator('[aria-invalid="true"]').first();
      await announcedRefusal(page, invalid);
      expect(writes).toBe(0);
      const scan = await new AxeBuilder({ page })
        .include('[role="dialog"]')
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze();
      expect(
        scan.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical'),
      ).toEqual([]);
      expect(
        await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1),
      ).toBe(true);
      await page.screenshot({ path: info.outputPath(`main-form-refusal-${width}.png`) });
      await page.keyboard.press('Escape');
      await expect(dialog).toHaveCount(0);
      await expect(opener).toBeFocused();
    });
  }
}

for (const flow of ['sign-in', 'first-account', 'password-recovery', 'activation-recovery']) {
  test(`public account form announces its refused fields: ${flow}`, async ({ page, baseURL }) => {
    const origin = new URL(baseURL!).origin;
    await page.route('**/*', (route) =>
      new URL(route.request().url()).origin === origin
        ? route.fallback()
        : route.abort('blockedbyclient'),
    );
    await page.addInitScript(() => {
      localStorage.clear();
      localStorage.setItem('lang', 'fr');
    });
    await page.route('**/api/auth/bootstrap-status', (route) =>
      route.fulfill({ json: { allowed: flow === 'first-account' } }),
    );
    await installLocalIconFonts(page);
    let writes = 0;
    page.on('request', (request) => {
      if (
        request.method() === 'POST' &&
        [
          '/api/auth/login',
          '/api/auth/bootstrap-signup',
          '/api/auth/password/request',
          '/api/auth/resend-verification',
        ].includes(new URL(request.url()).pathname)
      )
        writes++;
    });
    await page.goto('/login');
    await expect(page).toHaveURL((url) => url.pathname === '/login');
    if (flow === 'password-recovery') {
      await tabTo(page, page.getByRole('link', { name: fr.LOGIN.FORGOT_PASSWORD, exact: true }));
      await page.keyboard.press('Enter');
    } else if (flow === 'activation-recovery') {
      await tabTo(page, page.getByTestId('resend-activation-link'));
      await page.keyboard.press('Enter');
    }
    const submit = page.locator('form button[type="submit"]:visible').last();
    await tabTo(page, submit);
    await page.keyboard.press('Enter');
    const firstId = {
      'sign-in': 'username',
      'first-account': 'bs-firstName',
      'password-recovery': 'fp-email',
      'activation-recovery': 'ra-email',
    }[flow]!;
    await announcedRefusal(page, page.locator(`#${firstId}`));
    expect(writes).toBe(0);
    await accessibleAtWidths(page, 'app-login');
  });
}

test('patient registration announces every missing field without posting', async ({
  page,
  baseURL,
}) => {
  await signInAs(page, 'ROLE_RECEPTIONIST', baseURL!);
  await installLocalIconFonts(page);
  await page.goto('/patients/new');
  await expect(page).toHaveURL((url) => url.pathname === '/patients/new');
  const form = page.locator('form.form-card');
  await tabTo(page, form.locator('button[type="submit"]'));
  await page.keyboard.press('Enter');
  await announcedRefusal(page, page.locator('#firstName'));
  for (const field of await form.locator('[aria-invalid="true"]').all()) {
    const id = await field.getAttribute('aria-describedby');
    expect(id).toBeTruthy();
    const ids = id!.split(/\s+/).filter(Boolean);
    await expect(
      page.locator(ids.map((value) => `[id="${value}"][role="alert"]`).join(', ')).first(),
    ).toBeVisible();
  }
  await accessibleAtWidths(page, 'form.form-card');
});
