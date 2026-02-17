/**
 * DashboardPage – Page Object Model for the dashboard.
 */
import { type Page, type Locator, expect } from '@playwright/test';

export class DashboardPage {
  readonly page: Page;

  /* ── Locators ── */
  readonly welcomeBanner: Locator;
  readonly welcomeHeading: Locator;
  readonly welcomeDate: Locator;
  readonly metricsGrid: Locator;
  readonly metricCards: Locator;
  readonly loadingState: Locator;
  readonly kpiSection: Locator;
  readonly kpiCards: Locator;
  readonly inboxCard: Locator;
  readonly appointmentsSection: Locator;

  constructor(page: Page) {
    this.page = page;
    this.welcomeBanner = page.locator('.welcome-banner');
    this.welcomeHeading = page.locator('.welcome-text h1');
    this.welcomeDate = page.locator('.welcome-date');
    this.metricsGrid = page.locator('.metrics-grid');
    this.metricCards = page.locator('.metric-card');
    this.loadingState = page.locator('.loading-state');
    this.kpiSection = page.locator('.kpi-section');
    this.kpiCards = page.locator('.kpi-card');
    this.inboxCard = page.locator('.inbox-card');
    this.appointmentsSection = page.locator('.appointments-section, .today-appointments');
  }

  /** Navigate to dashboard */
  async goto(): Promise<void> {
    await this.page.goto('/dashboard', { waitUntil: 'networkidle' });
  }

  /** Wait for dashboard to fully load */
  async waitForLoad(): Promise<void> {
    // Wait for loading to disappear or for content to appear
    await this.page.waitForFunction(
      () => {
        const loading = document.querySelector('.loading-state');
        const welcome = document.querySelector('.welcome-banner');
        return !loading || getComputedStyle(loading).display === 'none' || welcome;
      },
      { timeout: 15_000 },
    );
  }

  /** Assert dashboard is visible with welcome banner */
  async expectDashboardLoaded(): Promise<void> {
    await expect(this.welcomeBanner).toBeVisible({ timeout: 15_000 });
    await expect(this.welcomeHeading).toBeVisible();
  }

  /** Get a specific metric card value by label text */
  async getMetricValue(label: string): Promise<string> {
    const card = this.page.locator(`.metric-card`, {
      has: this.page.locator(`.metric-label:text("${label}")`),
    });
    return (await card.locator('.metric-value').textContent()) ?? '';
  }

  /** Assert metrics grid is visible with at least n cards */
  async expectMetricsVisible(minCards = 1): Promise<void> {
    await expect(this.metricsGrid).toBeVisible({ timeout: 10_000 });
    const count = await this.metricCards.count();
    expect(count).toBeGreaterThanOrEqual(minCards);
  }

  /** Click on a metric card link */
  async clickMetric(label: string): Promise<void> {
    const card = this.page.locator(`a.metric-card`, {
      has: this.page.locator(`.metric-label:text("${label}")`),
    });
    await card.click();
  }
}
