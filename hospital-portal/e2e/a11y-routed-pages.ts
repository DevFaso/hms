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
      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        // Preserve the existing smoke's documented contrast exception; no new suppression.
        .disableRules(['color-contrast'])
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
      expect(
        results.violations.filter(
          (entry) => entry.impact === 'serious' || entry.impact === 'critical',
        ),
      ).toEqual([]);
    });
  }
});
