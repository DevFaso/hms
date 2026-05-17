import AxeBuilder from '@axe-core/playwright';
import type { Page, Route } from '@playwright/test';

import { test, expect } from './fixtures/test-fixtures';

/**
 * Roadmap row 32 follow-on — axe-core smoke against the KPI cards
 * surface with seeded data including a 30-day sparkline trend.
 *
 * <p>The foundation pass on row 32 shipped the three cards (avg + sub
 * text). The follow-on adds (a) a P50 median sub-line under
 * door-to-doctor and (b) an inline-SVG sparkline per card. Both
 * additions are new DOM with new accessible-name contracts — this
 * spec pins that contract: serious/critical axe violations on the
 * `<app-kpi-cards>` section fail the gate.
 *
 * <p>Strategy mirrors {@code e2e/a11y.spec.ts}: scope axe to WCAG
 * 2.x AA, suppress the wider color-contrast soft-debt (tracked
 * separately in {@code docs/ui/accessibility.md} § 10), and surface
 * moderate/minor findings to the run output without blocking. The
 * KPI endpoint is mocked with a seeded payload so the test is
 * deterministic and does not depend on the backend.
 */

type AxeResults = Awaited<ReturnType<AxeBuilder['analyze']>>;
type Violation = AxeResults['violations'][number];

const BLOCKING_IMPACTS = new Set<Violation['impact']>(['critical', 'serious']);
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

function jsonOk(route: Route, body: unknown): Promise<void> {
  return route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(body),
  });
}

/**
 * Seeded 7-day rollup. Mix of "all three KPIs populated", "single KPI
 * missing", and "all-null sparkline value" days exercises the
 * sparkline gap path (null = polyline break) which is the bit most
 * likely to fail axe (an empty SVG with `role="img"` and no
 * accessible name).
 */
function seededKpiPayload() {
  const from = '2026-04-25';
  const to = '2026-05-01';
  return {
    hospitalId: 'aaaaaaaa-0000-0000-0000-000000000001',
    from,
    to,
    doorToDoctor: {
      sampleSize: 42,
      averageMinutes: 27.3,
      medianMinutesEstimate: 22.1,
    },
    dispenseLeadTime: {
      sampleSize: 18,
      averageMinutes: 14.0,
    },
    noShowRate: {
      totalAppointments: 200,
      noShowCount: 14,
      rate: 0.07,
    },
    trend: [
      { date: '2026-04-25', doorToDoctorAverageMinutes: 25.0, dispenseLeadTimeAverageMinutes: 12.0, noShowRate: 0.05 },
      { date: '2026-04-26', doorToDoctorAverageMinutes: 30.0, dispenseLeadTimeAverageMinutes: 15.0, noShowRate: 0.08 },
      // 04-27: dispense gap — single KPI null
      { date: '2026-04-27', doorToDoctorAverageMinutes: 28.5, dispenseLeadTimeAverageMinutes: null, noShowRate: 0.06 },
      { date: '2026-04-28', doorToDoctorAverageMinutes: 22.1, dispenseLeadTimeAverageMinutes: 13.5, noShowRate: 0.09 },
      // 04-29: door-to-doctor gap
      { date: '2026-04-29', doorToDoctorAverageMinutes: null, dispenseLeadTimeAverageMinutes: 16.0, noShowRate: 0.07 },
      { date: '2026-04-30', doorToDoctorAverageMinutes: 29.0, dispenseLeadTimeAverageMinutes: 14.5, noShowRate: 0.05 },
      { date: '2026-05-01', doorToDoctorAverageMinutes: 26.2, dispenseLeadTimeAverageMinutes: 13.0, noShowRate: 0.10 },
    ],
  };
}

test.describe('a11y smoke (axe-core) — KPI cards with sparkline (roadmap row 32 follow-on)', () => {
  test('KPI cards on /super-admin/analytics have no serious/critical violations', async ({ page }) => {
    // Override the catch-all `/api/**` mock from test-fixtures with a
    // KPI-specific payload that includes the trend timeseries the
    // follow-on adds.
    await page.route('**/api/kpi/dashboard**', (r) => jsonOk(r, seededKpiPayload()));

    await page.goto('/super-admin/analytics', { waitUntil: 'domcontentloaded' });
    // Wait for the KPI heading to render — the cards' sparkline SVGs
    // mount only after the dashboard signal resolves.
    await page.waitForSelector('text="Operational KPIs"', { timeout: 10_000 }).catch(() => {});
    await page.waitForLoadState('networkidle').catch(() => {});

    const results = await new AxeBuilder({ page })
      .include('.kpi-cards')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      // See e2e/a11y.spec.ts — the wider color-contrast debt is
      // tracked separately and would block new gates if not suppressed.
      .disableRules(['color-contrast'])
      .analyze();

    const blocking = results.violations.filter(
      (v) => v.impact !== null && v.impact !== undefined && BLOCKING_IMPACTS.has(v.impact),
    );
    const reportOnly = results.violations.filter(
      (v) => v.impact !== null && v.impact !== undefined && REPORT_ONLY_IMPACTS.has(v.impact),
    );

    if (reportOnly.length > 0) {
      // eslint-disable-next-line no-console
      console.log(
        `[a11y] /super-admin/analytics .kpi-cards — ${reportOnly.length} non-blocking violation(s) (moderate/minor):\n` +
          JSON.stringify(summarize(reportOnly), null, 2),
      );
    }

    expect(
      summarize(blocking),
      'serious/critical a11y violations inside .kpi-cards',
    ).toEqual([]);
  });

  test('KPI dashboard endpoint is called with withTrends=true', async ({ page }) => {
    // Pins the foundation-pass -> follow-on contract: the component
    // requests trends explicitly. If a future refactor accidentally
    // reverts to `getKpiDashboard(from, to)` (no withTrends), the
    // sparkline silently disappears — this test catches that
    // regression.
    const calls: string[] = [];
    await page.route('**/api/kpi/dashboard**', (r) => {
      calls.push(r.request().url());
      return jsonOk(r, seededKpiPayload());
    });

    await page.goto('/super-admin/analytics', { waitUntil: 'domcontentloaded' });
    await page.waitForSelector('text="Operational KPIs"', { timeout: 10_000 }).catch(() => {});
    await page.waitForLoadState('networkidle').catch(() => {});

    expect(calls.length, 'KPI endpoint was called').toBeGreaterThan(0);
    expect(
      calls.some((u) => u.includes('withTrends=true')),
      'at least one call set withTrends=true',
    ).toBe(true);
  });
});
