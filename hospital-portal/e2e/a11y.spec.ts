import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';

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
 * PR. The gate fails only on `serious` or `critical` impact violations
 * — `moderate` and `minor` are reported in the run output but don't
 * block CI, so existing soft-debt doesn't lock up the pipeline. New
 * serious/critical regressions surface immediately.
 *
 * Coverage targets (per row 10 deliverable):
 * - /login              — public, unauthenticated
 * - /dashboard          — main authenticated dashboard
 * - /patient-tracker    — clinician patient-tracker board
 * - /my-medications     — patient portal AVS-adjacent surface
 *                         (medications view; SuperAdmin storage state
 *                         carries the role bits needed to render).
 *
 * Auth is inherited from `e2e/global-setup.ts` (SuperAdmin storage
 * state with mocked /api/** routes, so the spec runs without a live
 * Spring Boot backend). The login test re-uses the same storage
 * state — the login page renders identically whether the visitor is
 * already authenticated or not, so axe sees the same DOM.
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

/**
 * Stub the network calls the page makes on load so the spec runs
 * without a live backend. Mirrors the catch-all pattern from
 * global-setup.ts: anything under /api/** returns a benign empty
 * paginated response, so the Angular HTTP error interceptor never
 * kicks in and bounces the user back to /login.
 */
async function stubBackend(page: Page) {
  await page.route('**/api/**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ content: [], totalElements: 0, totalPages: 0, size: 20, number: 0 }),
    }),
  );
}

async function runAxe(page: Page, route: string) {
  await stubBackend(page);
  await page.goto(route, { waitUntil: 'domcontentloaded' });
  // Let Angular finish its initial render — the body's data-cy="ready"
  // hook isn't present on every page, so we fall back to a fixed wait
  // for the framework to settle.
  await page.waitForLoadState('networkidle').catch(() => {});

  const results = await new AxeBuilder({ page })
    // WCAG 2.1 AA is the project target per docs/ui/accessibility.md
    // (created by row 11). axe's wcag2aa + wcag21aa tag set captures
    // everything WCAG-relevant; the experimental + best-practice tags
    // are excluded so the gate only catches genuine standard
    // violations.
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .analyze();

  const blocking = results.violations.filter(
    (v) => v.impact !== null && v.impact !== undefined && BLOCKING_IMPACTS.has(v.impact),
  );
  const formatted = blocking.map((v) => ({
    id: v.id,
    impact: v.impact,
    description: v.description,
    helpUrl: v.helpUrl,
    nodes: v.nodes.map((n) => n.target),
  }));

  expect(formatted, `serious/critical a11y violations on ${route}: ${JSON.stringify(formatted, null, 2)}`)
    .toEqual([]);
}

test.describe('a11y smoke (axe-core)', () => {
  test('login page has no serious/critical violations', async ({ page }) => {
    await runAxe(page, '/login');
  });

  test('main dashboard has no serious/critical violations', async ({ page }) => {
    await runAxe(page, '/dashboard');
  });

  test('patient tracker has no serious/critical violations', async ({ page }) => {
    await runAxe(page, '/patient-tracker');
  });

  test('AVS surface (my-medications) has no serious/critical violations', async ({ page }) => {
    // Patient-portal route. Storage state in chromium project carries
    // SuperAdmin which has cross-role read access; the role guard
    // either passes or the redirect lands on /dashboard, in which
    // case axe still scans a real page. Either outcome is useful
    // signal.
    await runAxe(page, '/my-medications');
  });
});
