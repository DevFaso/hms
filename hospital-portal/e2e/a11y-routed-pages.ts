import AxeBuilder from '@axe-core/playwright';
import { test, expect } from './fixtures/test-fixtures';
import { routedPages } from './helpers/routed-page-inventory';
import { installLocalIconFonts } from './helpers/local-icon-fonts';

const ID = '00000000-0000-0000-0000-00000000cd12';
const inventory = routedPages();
const publicPaths = new Set([
  '/login',
  '/mfa-challenge',
  '/mfa-enroll',
  '/reset-password',
  '/verify',
  '/privacy-policy',
  '/onboarding/role-welcome',
]);

/**
 * QA D2793 (r65): axe's `color-contrast` rule now runs on EVERY routed page
 * (it was suppressed everywhere while the palette carried slate-500 text at
 * 4.34:1 and a light-blue primary). The routes below still fail it and are
 * left out until their owner converts them: they belong to the
 * platform/onboarding track (dashboard, hospitals, platform, super-admin),
 * which the r65 design pass did not touch. Two more land on the dashboard in
 * this sweep (its guards redirect them), so they fail with it.
 * A new route is scanned from its first day. `A11Y_CONTRAST=all` scans these
 * too, to see what is left.
 */
const CONTRAST_PENDING = new Set(
  process.env['A11Y_CONTRAST'] === 'all'
    ? []
    : [
        '/dashboard',
        '/account-setup',
        '/reception/empi-candidates',
        '/hospitals/:id',
        '/platform',
        '/super-admin/platform',
        '/super-admin/integrations',
        '/super-admin/audit-search',
        '/super-admin/emergency',
        '/super-admin/subscriptions',
        '/super-admin/cost',
        '/super-admin/data-residency',
        '/super-admin/data-residency/policy',
      ],
);
/** The e-Keneya wordmark's amber half: a logotype has no contrast requirement (WCAG 1.4.3). */
const LOGOTYPE = '.brand-word';

test.describe('all routed pages', () => {
  test.use({ storageState: { cookies: [], origins: [] } });

  for (const screen of inventory) {
    test(`routed accessibility: ${screen.path}`, async ({ page }, testInfo) => {
      const pathname = screen.path.replace(/:[^/]+/g, ID);
      const role = screen.roles.includes('ROLE_HOSPITAL_ADMIN')
        ? 'ROLE_HOSPITAL_ADMIN'
        : (screen.roles[0] ?? 'ROLE_HOSPITAL_ADMIN');
      const publicPage = publicPaths.has(screen.path);
      // Block external assets/providers; shared fixtures intercept all API calls.
      const origin = new URL(testInfo.project.use.baseURL as string).origin;
      await page.route('**/*', (route) => {
        const url = new URL(route.request().url());
        return url.origin === origin ? route.fallback() : route.abort('blockedbyclient');
      });
      await installLocalIconFonts(page);
      if (!publicPage) {
        const profile = {
          id: ID,
          username: 'cdx-a11y',
          email: 'cdx@example.invalid',
          active: true,
          roles: [role],
          primaryHospitalId: ID,
          primaryHospitalName: 'cdx Hospital',
          hospitalIds: [ID],
        };
        await page.addInitScript(
          ({ profile }) => {
            const payload = btoa(
              JSON.stringify({ sub: profile.username, roles: profile.roles, exp: 9999999999 }),
            );
            localStorage.setItem(
              'auth_token',
              `eyJhbGciOiJIUzI1NiJ9.${payload}.cdx-test-signature`,
            );
            localStorage.setItem('user_profile', JSON.stringify(profile));
          },
          { profile },
        );
        await page.route('**/api/auth/session/bootstrap', (route) =>
          route.fulfill({
            json: {
              ...profile,
              userId: ID,
              authSource: 'internal',
              superAdmin: role === 'ROLE_SUPER_ADMIN',
              hospitalAdmin: role === 'ROLE_HOSPITAL_ADMIN',
              primaryHospitalId: ID,
              permittedHospitalIds: [ID],
              staffId: ID,
              patientId: ID,
            },
          }),
        );
      }
      const errors: string[] = [];
      page.on('pageerror', (error) => errors.push(error.message));
      await page.goto(pathname, { waitUntil: 'domcontentloaded' });
      // Explicit destination/render assertions prevent a guard/error redirect or empty shell being counted.
      await expect(page).toHaveURL((url) => url.pathname === pathname);
      await expect(page.locator('h1, h2, [role="heading"]').first()).toBeVisible();
      await page.evaluate(() => document.fonts.ready.then(() => undefined));
      // Two frames: the design conventions (shared/a11y/design-conventions.ts)
      // work once per animation frame after the page renders.
      await page.evaluate(
        () => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))),
      );
      const design = await page.evaluate(() => {
        const shown = (el: Element) => (el as HTMLElement).getClientRects().length > 0;
        const ICON = '.material-symbols-outlined, .material-icons';
        return {
          // QA D2791 (r65): one page title, whatever the page's own stylesheet says.
          titles: Array.from(document.querySelectorAll('.content h1'))
            .filter(shown)
            .map((h) => {
              const style = getComputedStyle(h);
              return `${style.fontSize}/${style.fontWeight}`;
            })
            .filter((style) => style !== '24px/700'),
          // QA D2792 (r65): an icon's ligature name is not read as part of a
          // heading, a column header or a navigation link that has a name of
          // its own. (An icon that is the host's ONLY name is left readable.)
          spokenIcons: Array.from(
            document.querySelectorAll(
              ['h1', 'h2', 'th', '.sidebar a', '.sidebar button']
                .flatMap((host) => ICON.split(', ').map((icon) => `${host} ${icon}`))
                .join(', '),
            ),
          )
            .filter((icon) => shown(icon) && icon.getAttribute('aria-hidden') !== 'true')
            .filter(
              (icon) =>
                !icon.hasAttribute('aria-label') &&
                !icon.hasAttribute('title') &&
                icon.getAttribute('role') !== 'img',
            )
            .filter((icon) => {
              const host = icon.closest('h1, h2, th, a, button');
              if (!host) return false;
              const named = (node: Element): boolean =>
                Array.from(node.childNodes).some((child) =>
                  child.nodeType === Node.TEXT_NODE
                    ? (child.textContent ?? '').trim() !== ''
                    : child instanceof Element &&
                      !child.matches(ICON) &&
                      shown(child) &&
                      named(child),
                );
              return host.hasAttribute('aria-label') || host.hasAttribute('title') || named(host);
            })
            .map((icon) => `${icon.parentElement?.tagName.toLowerCase()} «${icon.textContent}»`),
        };
      });
      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .exclude(LOGOTYPE)
        .disableRules(CONTRAST_PENDING.has(screen.path) ? ['color-contrast'] : [])
        .analyze();
      await testInfo.attach('route-audit', {
        contentType: 'application/json',
        body: JSON.stringify(
          {
            declaredPath: screen.path,
            pathname,
            role,
            parameterized: screen.parameterized,
            errors,
            violations: results.violations,
          },
          null,
          2,
        ),
      });
      expect(errors, 'route must render without application exceptions').toEqual([]);
      if (!publicPage) {
        expect(design.titles, 'one page title: every h1 is 24px/700 (QA D2791)').toEqual([]);
      }
      expect(design.spokenIcons, 'icons are hidden from screen readers (QA D2792)').toEqual([]);
      expect(
        results.violations.filter(
          (entry) => entry.impact === 'serious' || entry.impact === 'critical',
        ),
      ).toEqual([]);
    });
  }
});
