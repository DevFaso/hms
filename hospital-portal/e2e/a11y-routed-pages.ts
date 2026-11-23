import AxeBuilder from '@axe-core/playwright';
import { test, expect } from './fixtures/test-fixtures';
import { routedPages } from './helpers/routed-page-inventory';
import { installLocalIconFonts } from './helpers/local-icon-fonts';
import { MAIN_JOURNEY_ROUTES, MAIN_JOURNEY_ROLES } from './helpers/main-journey-routes';

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
 * QA D2793 (r65): axe's `color-contrast` rule runs on EVERY routed page, with
 * no route left out (it was suppressed everywhere while the palette carried
 * slate-500 text at 4.34:1 and a light-blue primary; the platform track's
 * pages, the last 13 exempted by name, were converted when that track
 * merged). A new route is scanned from its first day.
 */
// The e-Keneya wordmark's amber half is a logotype: no contrast requirement
// (WCAG 1.4.3). Its nodes are dropped from the `color-contrast` result below,
// and from that rule alone.

test.describe('all routed pages', () => {
  test.use({ storageState: { cookies: [], origins: [] } });

  test('the CI inventory contains at least twenty-five distinct main journeys', () => {
    const distinct = new Set<string>(MAIN_JOURNEY_ROUTES);
    expect(distinct.size).toBeGreaterThanOrEqual(25);
    expect(
      MAIN_JOURNEY_ROUTES.filter((path) => !inventory.some((screen) => screen.path === path)),
    ).toEqual([]);
  });

  for (const screen of inventory) {
    test(`routed accessibility: ${screen.path}`, async ({ page }, testInfo) => {
      const pathname = screen.path.replace(/:[^/]+/g, ID);
      const role =
        MAIN_JOURNEY_ROLES[screen.path] ??
        (screen.roles.includes('ROLE_HOSPITAL_ADMIN')
          ? 'ROLE_HOSPITAL_ADMIN'
          : (screen.roles[0] ?? 'ROLE_HOSPITAL_ADMIN'));
      const publicPage = publicPaths.has(screen.path);
      // Block external assets/providers; shared fixtures intercept all API calls.
      const origin = new URL(testInfo.project.use.baseURL as string).origin;
      await page.route('**/*', (route) => {
        const url = new URL(route.request().url());
        return url.origin === origin ? route.fallback() : route.abort('blockedbyclient');
      });
      await installLocalIconFonts(page);
      // Detail endpoints return an object, never the empty list used by directory fixtures.
      const detail = {
        id: ID,
        name: 'cdx Test facility',
        firstName: 'cdx',
        lastName: 'Test',
        fullName: 'cdx Test',
        username: 'cdx-test',
        email: 'cdx@example.invalid',
        active: true,
        hospitalId: ID,
        hospitalName: 'cdx Hospital',
        roles: [role],
        departments: [],
        hospitalRegistrations: [],
        staffMembers: [],
        address: 'cdx Test address',
      };
      const detailPaths: Record<string, string> = {
        '/patients/:id': `/api/patients/${ID}`,
        '/staff/:id': `/api/staff/${ID}/active`,
        '/departments/:id': `/api/departments/${ID}`,
        '/hospitals/:id': `/api/hospitals/${ID}`,
        '/organizations/:id': `/api/organizations/${ID}`,
        '/super-admin/providers/:id': `/api/super-admin/providers/${ID}`,
        '/profile': `/api/users/${ID}`,
      };
      if (detailPaths[screen.path]) {
        await page.route(`**${detailPaths[screen.path]}?*`, (route) =>
          route.fulfill({ json: detail }),
        );
        await page.route(`**${detailPaths[screen.path]}`, (route) =>
          route.fulfill({ json: detail }),
        );
      }
      if (screen.path === '/pharmacy/dispensing') {
        await page.route('**/api/pharmacy-registry?**', (route) =>
          route.fulfill({
            json: {
              content: [{ id: ID, hospitalId: ID, name: 'cdx-Fixture pharmacy', active: true }],
              totalElements: 1,
              totalPages: 1,
              number: 0,
              size: 20,
            },
          }),
        );
      }
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
      // A sidebar heading, directory or scope chooser is not proof that the requested screen loaded.
      const content = publicPage
        ? page.locator('app-root')
        : page.locator('#main-content router-outlet + *').first();
      await expect(content).toBeVisible();
      await expect(content.locator('h1, h2, [role="heading"]').first()).toBeVisible();
      if (MAIN_JOURNEY_ROUTES.some((path) => path === screen.path)) {
        await expect(
          page.locator('[data-testid="scope-gate"], app-hospital-network-workspace'),
        ).toHaveCount(0);
        if (!screen.path.startsWith('/my-')) {
          await expect(content.locator('button, input, select, a[href]').first()).toBeVisible();
        }
      }
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
        results.violations
          .map((entry) =>
            // The wordmark is left out of the contrast rule only; every other
            // rule still reads it.
            entry.id === 'color-contrast'
              ? {
                  ...entry,
                  nodes: entry.nodes.filter(
                    (node) =>
                      !node.html.includes('brand-word') && !node.html.includes('brand-accent'),
                  ),
                }
              : entry,
          )
          .filter((entry) => entry.nodes.length > 0)
          .filter((entry) => entry.impact === 'serious' || entry.impact === 'critical'),
      ).toEqual([]);
    });
  }
});
