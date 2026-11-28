import AxeBuilder from '@axe-core/playwright';
import type { Page, Route } from '@playwright/test';

import { test, expect } from './fixtures/test-fixtures';
// Register the inventory-driven cases in the existing CI entry point.
import './a11y-routed-pages';
import { installLocalIconFonts } from './helpers/local-icon-fonts';
import { signInAs } from './helpers/role-session';
import { routedPages } from './helpers/routed-page-inventory';
import './a11y-journey-forms';
import './a11y-keyboard-care';
import './a11y-main-refusals';

/**
 * v1.0 / Accessibility / axe-core/playwright smoke (roadmap row 10).
 *
 * Filename intentionally lacks the "smoke" suffix — Playwright's
 * `smoke` project (see hospital-portal/playwright.config.ts) picks up
 * any file matching `/smoke\.spec\.ts/` and runs it against
 * `$SMOKE_BASE_URL` (staging/prod). This spec must run against the
 * local Angular dev server in the `chromium` project so we can stub
 * `/api/**` and avoid a live backend dependency in CI.
 *
 * Runs axe-core against the four critical clinical-flow pages on every
 * PR. The gate fails only on `serious` or `critical` impact violations.
 * `moderate` and `minor` violations don't block CI but ARE printed to
 * the console so existing soft-debt is visible to reviewers — addresses
 * Copilot review on PR #286 which caught the original "filtered out
 * silently, comment lied" mismatch.
 *
 * Coverage targets (per row 10 deliverable):
 * - /login              — public, unauthenticated. Scanned with
 *                         storageState explicitly cleared, otherwise
 *                         LoginRedirectGuard bounces an authenticated
 *                         visitor to /dashboard before axe sees the
 *                         login DOM (Copilot review on PR #286).
 * - /dashboard          — main authenticated dashboard
 * - /patient-tracker    — clinician patient-tracker board
 * - /my-medications     — patient portal AVS-adjacent surface
 *                         (medications view; SuperAdmin storage state
 *                         carries the role bits needed to render).
 *
 * The row-11 widening to /reception, /nurse-station, /prescriptions,
 * and /pharmacy/dispensing was attempted but reverted because each
 * carries pre-existing structural a11y debt (form input missing
 * label on /reception; select missing accessible name on
 * /pharmacy/dispensing) on top of the wider color-contrast debt that
 * blocks dropping the suppression here. Re-add each route once its
 * per-screen audit closes the underlying violations.
 *
 * Mock strategy: the shared `./fixtures/test-fixtures` auto-fixture
 * registers comprehensive /api/** mocks so every spec runs against the
 * same stable stubs. This spec does NOT redefine its own narrower
 * stubBackend (Copilot review on PR #286 caught the divergence).
 *
 * Failure semantics: `expect(violations).toEqual([])` after filtering
 * by impact, with the violations array surfaced via Playwright's
 * default reporter. Each violation includes the rule id, the
 * affected nodes' selectors, and the help URL so a reviewer can land
 * a fix without re-running the scan locally.
 */

type AxeResults = Awaited<ReturnType<AxeBuilder['analyze']>>;
type Violation = AxeResults['violations'][number];

/** Impact levels we treat as PR-blocking. */
const BLOCKING_IMPACTS = new Set<Violation['impact']>(['critical', 'serious']);
/** Lower-impact buckets we report but don't block on. */
const REPORT_ONLY_IMPACTS = new Set<Violation['impact']>(['moderate', 'minor']);

function summarize(violations: Violation[]) {
  return violations.map((v) => ({
    id: v.id,
    impact: v.impact,
    description: v.description,
    helpUrl: v.helpUrl,
    nodes: v.nodes.map((n) => n.target),
  }));
}

async function runAxe(page: Page, route: string) {
  if (route !== '/login') {
    const roles = routedPages().find((screen) => screen.path === route)?.roles ?? [];
    const role = roles.includes('ROLE_HOSPITAL_ADMIN')
      ? 'ROLE_HOSPITAL_ADMIN'
      : (roles[0] ?? 'ROLE_HOSPITAL_ADMIN');
    await signInAs(page, role, test.info().project.use.baseURL as string);
  }
  await installLocalIconFonts(page);
  await page.goto(route, { waitUntil: 'domcontentloaded' });
  await expect(page).toHaveURL((url) => url.pathname === route);
  const content =
    route === '/login'
      ? page.locator('app-root')
      : page.locator('#main-content router-outlet + *').first();
  await expect(content).toBeVisible();
  await expect(content.locator('h1, h2, [role="heading"]').first()).toBeVisible();
  await page.evaluate(() => document.fonts.ready.then(() => undefined));

  const results = await new AxeBuilder({ page })
    // WCAG 2.1 AA is the project target per docs/ui/accessibility.md
    // (created by row 11). axe's wcag2aa + wcag21aa tag set captures
    // everything WCAG-relevant; the experimental + best-practice tags
    // are excluded so the gate only catches genuine standard
    // violations.
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .analyze();

  const blocking = results.violations
    .map((violation) =>
      violation.id === 'color-contrast'
        ? {
            ...violation,
            nodes: violation.nodes.filter(
              (node) => !node.html.includes('brand-word') && !node.html.includes('brand-accent'),
            ),
          }
        : violation,
    )
    .filter((violation) => violation.nodes.length > 0)
    .filter((v) => v.impact !== null && v.impact !== undefined && BLOCKING_IMPACTS.has(v.impact));
  const reportOnly = results.violations.filter(
    (v) => v.impact !== null && v.impact !== undefined && REPORT_ONLY_IMPACTS.has(v.impact),
  );

  // Surface moderate/minor findings to the run output even though they
  // don't fail the gate — keeps soft-debt visible and addresses Copilot
  // review on PR #286. console.log over console.warn so Playwright's
  // default reporter doesn't tag the line as an error.
  if (reportOnly.length > 0) {
    // eslint-disable-next-line no-console
    console.log(
      `[a11y] ${route} — ${reportOnly.length} non-blocking violation(s) (moderate/minor):\n` +
        JSON.stringify(summarize(reportOnly), null, 2),
    );
  }

  expect(summarize(blocking), `serious/critical a11y violations on ${route}`).toEqual([]);
}

test.describe('a11y smoke (axe-core) — authenticated surfaces', () => {
  test('main dashboard has no serious/critical violations', async ({ page }) => {
    await runAxe(page, '/dashboard');
  });

  test('patient tracker has no serious/critical violations', async ({ page }) => {
    await runAxe(page, '/patient-tracker');
  });

  test('disease registries have no serious/critical violations', async ({ page }) => {
    // Tier 2 item 35. Storage state carries SuperAdmin, which the
    // /registries RoleGuard admits.
    await runAxe(page, '/registries');
  });

  test('AVS surface (my-medications) has no serious/critical violations', async ({ page }) => {
    await runAxe(page, '/my-medications');
  });

  test('super-admin provider facilities have no serious/critical violations', async ({ page }) => {
    // D5 P1-T12. Storage state carries SuperAdmin, which the route's
    // RoleGuard admits. One submitted pharmacy so axe scans the table, the
    // filters and the badges rather than the empty state. Registered after
    // the shared auto-mocks, so this handler answers first.
    const page1 = JSON.stringify({
      content: [
        {
          id: '00000000-0000-0000-0000-0000000000d5',
          facilityType: 'PHARMACY',
          code: 'PH-A11Y',
          name: 'Pharmacie A11y',
          email: null,
          active: false,
          lifecycleState: 'SUSPENDED',
          verificationStatus: 'SUBMITTED',
          submittedAt: '2026-10-08T09:00:00',
        },
      ],
      totalElements: 1,
      totalPages: 1,
      size: 20,
      number: 0,
    });
    const fulfil = (route: Route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: page1 });
    // The plain list is a GET; a typed term is a POST to /search (QA D4075: never in the URL).
    await page.route('**/api/super-admin/providers?**', fulfil);
    await page.route('**/api/super-admin/providers/search**', (route) =>
      route.request().method() === 'POST' ? fulfil(route) : route.fallback(),
    );
    await runAxe(page, '/super-admin/providers');
  });

  // v1.0 row 11 widening (/reception, /nurse-station, /prescriptions,
  // /pharmacy/dispensing) was attempted in the row 11 finish PR but
  // reverted. The row 11 finish closed the §10 keyboard gaps (sidebar
  // Alt+Arrow reorder, roving tabindex on vitals/tracker/in-basket)
  // and the documented forbidden grey foregrounds, but the wider
  // color-contrast debt that those routes also carry exceeds row 11's
  // scope. Add each route here as its per-screen contrast + structural
  // audit lands.
});

test.describe('a11y smoke (axe-core) — unauthenticated /login', () => {
  // Per-describe override: discard the SuperAdmin storage state from
  // the chromium project so LoginRedirectGuard does NOT redirect us
  // away from /login. Without this override, the test would silently
  // scan /dashboard (the authenticated landing page) — Copilot review
  // on PR #286 caught the bug.
  test.use({ storageState: { cookies: [], origins: [] } });

  test('login page has no serious/critical violations', async ({ page }) => {
    await runAxe(page, '/login');
  });
});
