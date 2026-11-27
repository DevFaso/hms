import AxeBuilder from '@axe-core/playwright';
import type { Page } from '@playwright/test';
import { test, expect } from './fixtures/test-fixtures';
import { blockingAxeViolations } from './helpers/axe-blocking';
import { installLocalIconFonts } from './helpers/local-icon-fonts';
import { signInAs, TEST_ID } from './helpers/role-session';
import { WCAG_JOURNEY_PAGES, WCAG_WIDTHS } from './helpers/wcag-journey-pages';

/**
 * Go-live scorecard P5: the agreed WCAG 2.1 AA scope (docs/qa/wcag-scope.md).
 * Runs against the production bundle (`npm run e2e:wcag-scope`,
 * playwright.wcag-scope.config.ts); every /api call is mocked by the shared
 * fixtures and nothing leaves the local server.
 *
 * Every page of the ten main journeys, as its working role, loaded at each
 * supported width. On top of the routed suite (one desktop width, serious and
 * critical only) a journey page must also:
 *  - reflow: no sideways page scroll (SC 1.4.10);
 *  - keep its landmark structure: one main, one banner, a named navigation,
 *    no content outside a landmark (SC 1.3.1, 2.4.1);
 *  - carry one visible h1 inside main (SC 2.4.6);
 *  - offer touch targets of 24 px or more at phone width (agreed extra, §4);
 *  - be operable from the keyboard: the first Tab stops are visible, focused
 *    and show a focus indicator, and the skip link comes first (SC 2.1.1,
 *    2.4.1, 2.4.3, 2.4.7).
 */

/** Rules outside the WCAG A/AA tags that the scope adds (docs/qa/wcag-scope.md §4). */
const STRUCTURE_RULES = [
  'landmark-one-main',
  'landmark-no-duplicate-main',
  'landmark-no-duplicate-banner',
  'landmark-unique',
  'region',
  'page-has-heading-one',
] as const;
const PHONE_RULES = ['target-size'] as const;

/** Sign in on /login against a mocked answer that sends the reader to `target`. */
async function arriveBySigningIn(
  page: Page,
  target: NonNullable<(typeof WCAG_JOURNEY_PAGES)[number]['viaLogin']>,
): Promise<void> {
  const payload = Buffer.from(
    JSON.stringify({ sub: 'cdx-a11y', roles: ['ROLE_RECEPTIONIST'], exp: 9999999999 }),
  ).toString('base64');
  const answer =
    target === 'account-setup'
      ? {
          token: `eyJhbGciOiJIUzI1NiJ9.${payload}.cdx-test-signature`,
          id: TEST_ID,
          username: 'cdx-a11y',
          email: 'cdx@example.invalid',
          roles: ['ROLE_RECEPTIONIST'],
          active: true,
          forcePasswordChange: true,
          forceUsernameChange: true,
          primaryHospitalId: TEST_ID,
          hospitalIds: [TEST_ID],
        }
      : {
          mfaRequired: true,
          mfaEnrolled: target === 'mfa-challenge',
          mfaToken: 'cdx-a11y-fixture',
          username: 'cdx-a11y',
        };
  await page.route('**/api/auth/login', (route) => route.fulfill({ json: answer }));
  await page.goto('/login', { waitUntil: 'domcontentloaded' });
  await page.locator('#username').fill('cdx-a11y');
  await page.locator('#password').fill('cdx-a11y-fixture');
  await page.locator('.btn-login').click();
}

async function keyboardSmoke(page: Page, signedIn: boolean): Promise<string[]> {
  const problems: string[] = [];
  // Start from the top of the document, as a keyboard user arriving on the page.
  await page.evaluate(() => {
    (document.activeElement as HTMLElement | null)?.blur();
    getSelection()?.removeAllRanges();
    window.scrollTo(0, 0);
  });
  for (let stop = 1; stop <= 12; stop++) {
    await page.keyboard.press('Tab');
    // A focus style may slide in (the skip link does): judge it once it has settled.
    await page.evaluate(() =>
      Promise.all((document.activeElement?.getAnimations() ?? []).map((a) => a.finished)).then(
        () => undefined,
        () => undefined,
      ),
    );
    const seen = await page.evaluate(() => {
      const el = document.activeElement as HTMLElement | null;
      if (!el || el === document.body) return null;
      const style = getComputedStyle(el);
      const box = el.getBoundingClientRect();
      const name = `${el.tagName.toLowerCase()}${el.id ? '#' + el.id : ''}${
        el.className && typeof el.className === 'string'
          ? '.' + el.className.trim().split(/\s+/).slice(0, 2).join('.')
          : ''
      }`;
      return {
        name,
        skipLink: !!el.closest('app-skip-link'),
        sized: box.width > 0 && box.height > 0,
        onScreen: box.right > 0 && box.bottom > 0 && box.left < innerWidth && box.top < innerHeight,
        indicator:
          (style.outlineStyle !== 'none' && parseFloat(style.outlineWidth) > 0) ||
          style.boxShadow !== 'none' ||
          // The last Tab stop inside a native date or time field is Chromium's
          // own calendar button: it draws its own ring in the field's shadow
          // tree, where the field itself no longer matches :focus-visible.
          (el instanceof HTMLInputElement &&
            ['date', 'time', 'datetime-local', 'month', 'week'].includes(el.type) &&
            !el.matches(':focus-visible')),
        positiveTabindex: el.tabIndex > 0,
      };
    });
    if (!seen) {
      // Focus left the document (browser chrome): the page has fewer stops than the sample.
      if (stop === 1) problems.push('the first Tab reaches nothing');
      break;
    }
    if (stop === 1 && signedIn && !seen.skipLink)
      problems.push(`the first Tab stop is ${seen.name}, not the skip link`);
    if (!seen.sized) problems.push(`stop ${stop} ${seen.name}: focused but not rendered`);
    else if (!seen.onScreen) problems.push(`stop ${stop} ${seen.name}: focused off screen`);
    if (!seen.indicator) problems.push(`stop ${stop} ${seen.name}: no visible focus indicator`);
    if (seen.positiveTabindex) problems.push(`stop ${stop} ${seen.name}: positive tabindex`);
  }
  return problems;
}

test.describe('WCAG scope', () => {
  test.use({ storageState: { cookies: [], origins: [] } });

  for (const screen of WCAG_JOURNEY_PAGES) {
    for (const width of WCAG_WIDTHS) {
      test(`${screen.journey} ${screen.route} at ${width}px`, async ({ page, baseURL }, info) => {
        await page.setViewportSize({ width, height: 900 });
        if (screen.role) await signInAs(page, screen.role, baseURL!);
        else {
          const origin = new URL(baseURL!).origin;
          await page.route('**/*', (route) =>
            new URL(route.request().url()).origin === origin
              ? route.fallback()
              : route.abort('blockedbyclient'),
          );
        }
        await installLocalIconFonts(page);
        const errors: string[] = [];
        page.on('pageerror', (error) => errors.push(error.message));
        if (screen.viaLogin) await arriveBySigningIn(page, screen.viaLogin);
        else
          await page.goto(screen.route + (screen.query ?? ''), { waitUntil: 'domcontentloaded' });
        await expect(page).toHaveURL((url) => url.pathname === screen.route);
        const inShell = await page
          .locator('#main-content')
          .waitFor({ state: 'attached', timeout: screen.role ? 15000 : 1000 })
          .then(
            () => true,
            () => false,
          );
        const content = inShell
          ? page.locator('#main-content router-outlet + *').first()
          : page.locator('app-root');
        await expect(content).toBeVisible();
        await expect(content.locator('h1, h2, [role="heading"]').first()).toBeVisible();
        await page.evaluate(() => document.fonts.ready.then(() => undefined));
        await page.evaluate(
          () => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))),
        );

        const extra = [...STRUCTURE_RULES, ...(width <= 390 ? PHONE_RULES : [])];
        const scan = await new AxeBuilder({ page })
          .options({
            runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] },
            rules: Object.fromEntries(extra.map((rule) => [rule, { enabled: true }])),
          })
          .analyze();
        const summary = (list: typeof scan.violations) =>
          list.map((v) => ({
            id: v.id,
            impact: v.impact,
            help: v.helpUrl,
            nodes: v.nodes.map((n) => `${n.target.join(' ')} :: ${n.html.slice(0, 160)}`),
          }));
        const standard = blockingAxeViolations(scan.violations);
        // A rule listed as `pending` for this page is owed by a named open change
        // (wcag-journey-pages.ts): reported in the attachment, not yet blocking.
        const added = scan.violations.filter(
          (v) =>
            (extra as readonly string[]).includes(v.id) &&
            !standard.includes(v) &&
            !screen.pending?.rules.includes(v.id),
        );
        const reflow = await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth + 1,
        );
        const keyboard = await keyboardSmoke(page, inShell);
        await info.attach('journey-audit', {
          contentType: 'application/json',
          body: JSON.stringify({ ...screen, width, errors, violations: scan.violations }, null, 2),
        });
        expect.soft(errors, 'the page renders without application exceptions').toEqual([]);
        expect.soft(summary(standard), 'WCAG 2.1 A/AA: serious and critical').toEqual([]);
        expect.soft(summary(added), 'landmarks, page heading and phone targets').toEqual([]);
        expect.soft(reflow, `no sideways page scroll at ${width}px (SC 1.4.10)`).toBe(true);
        expect.soft(keyboard, 'keyboard smoke: first Tab stops').toEqual([]);
      });
    }
  }

  // The entry dialog of a journey that starts in one: named, modal, focus
  // inside, Tab kept inside, Escape closes it and gives focus back (SC 2.1.2,
  // 2.4.3, 4.1.2), and its content passes at phone and desktop widths.
  for (const screen of WCAG_JOURNEY_PAGES.filter((entry) => entry.opener && entry.role)) {
    for (const width of [390, 1440]) {
      test(`${screen.journey} ${screen.route} entry dialog at ${width}px`, async ({
        page,
        baseURL,
      }) => {
        await page.setViewportSize({ width, height: 900 });
        await signInAs(page, screen.role!, baseURL!);
        await installLocalIconFonts(page);
        await page.goto(screen.route, { waitUntil: 'domcontentloaded' });
        await expect(page).toHaveURL((url) => url.pathname === screen.route);
        const opener = page.locator(screen.opener!).first();
        await expect(opener).toBeVisible();
        await opener.focus();
        await page.keyboard.press('Enter');
        const dialog = page.getByRole('dialog');
        await expect(dialog).toBeVisible();
        await expect(dialog).toHaveAttribute('aria-modal', 'true');
        expect(
          await dialog.evaluate(
            (el) => !!(el.getAttribute('aria-label') || el.getAttribute('aria-labelledby')),
          ),
          'the dialog has an accessible name',
        ).toBe(true);
        const focusInside = () =>
          page.evaluate(() => !!document.activeElement?.closest('[role="dialog"]'));
        await expect.poll(focusInside, { message: 'focus moves into the dialog' }).toBe(true);
        for (let stop = 0; stop < 40; stop++) {
          await page.keyboard.press('Tab');
          expect(await focusInside(), `Tab ${stop + 1} stays inside the dialog`).toBe(true);
        }
        const scan = await new AxeBuilder({ page })
          .include('[role="dialog"]')
          .options({
            runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] },
            rules: width <= 390 ? { 'target-size': { enabled: true } } : {},
          })
          .analyze();
        expect(
          scan.violations
            .filter(
              (v) => v.id === 'target-size' || v.impact === 'serious' || v.impact === 'critical',
            )
            .map((v) => ({ id: v.id, nodes: v.nodes.map((n) => n.target.join(' ')) })),
        ).toEqual([]);
        expect(
          await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1),
          `no sideways page scroll at ${width}px`,
        ).toBe(true);
        await page.keyboard.press('Escape');
        await expect(dialog).toHaveCount(0);
        await expect(opener).toBeFocused();
      });
    }
  }
});
