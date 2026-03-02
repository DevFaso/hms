/**
 * DashboardPage – Page Object Model for the dashboard.
 */
import { type Page, type Locator, expect } from '@playwright/test';

export class DashboardPage {
  readonly page: Page;

  /* ── Locators ── */
  readonly welcomeBanner: Locator;
  readonly welcomeHeading: Locator;
  readonly greetingTag: Locator;
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
    this.welcomeBanner = page.locator('.hero-header');
    this.welcomeHeading = page.locator('.hero-name');
    this.greetingTag = page.locator('.greeting-tag');
    this.welcomeDate = page.locator('.hero-sub');
    this.metricsGrid = page.locator('.stat-strip');
    this.metricCards = page.locator('.stat-card');
    this.loadingState = page.locator('.skeleton-card');
    this.kpiSection = page.locator('.stat-strip');
    this.kpiCards = page.locator('.stat-card');
    this.inboxCard = page.locator('.inbox-card');
    this.appointmentsSection = page.locator('.schedule-panel');
  }

  /** Navigate to dashboard */
  async goto(): Promise<void> {
    await this.page.goto('/dashboard', { waitUntil: 'domcontentloaded' });
  }

  /** Wait for dashboard to fully load */
  async waitForLoad(): Promise<void> {
    // Wait for loading to disappear or for content to appear
    await this.page.waitForFunction(
      () => {
        const loading = document.querySelector('.skeleton-card');
        const welcome = document.querySelector('.hero-header');
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
    const card = this.page.locator(`.stat-card`, {
      has: this.page.locator(`.stat-label:text("${label}")`),
    });
    return (await card.locator('.stat-value').textContent()) ?? '';
  }

  /** Assert metrics grid is visible with at least n cards */
  async expectMetricsVisible(minCards = 1): Promise<void> {
    await expect(this.metricsGrid).toBeVisible({ timeout: 10_000 });
    const count = await this.metricCards.count();
    expect(count).toBeGreaterThanOrEqual(minCards);
  }

  /** Click on a metric card link */
  async clickMetric(label: string): Promise<void> {
    const card = this.page.locator(`a.stat-card`, {
      has: this.page.locator(`.stat-label:text("${label}")`),
    });
    await card.click();
  }
}
