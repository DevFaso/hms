/**
 * PatientListPage – Page Object Model for the patient list view.
 */
import { type Page, type Locator, expect } from '@playwright/test';

export class PatientListPage {
  readonly page: Page;

  /* ── Locators ── */
  readonly pageTitle: Locator;
  readonly pageSubtitle: Locator;
  readonly registerButton: Locator;
  readonly searchInput: Locator;
  readonly dataTable: Locator;
  readonly tableRows: Locator;
  readonly loadingState: Locator;
  readonly emptyState: Locator;

  constructor(page: Page) {
    this.page = page;
    this.pageTitle = page.locator('main .page-title');
    this.pageSubtitle = page.locator('main .page-subtitle');
    this.registerButton = page.locator('a:has-text("Register Patient")');
    this.searchInput = page.locator('.search-bar input');
    this.dataTable = page.locator('.data-table');
    this.tableRows = page.locator('.data-table tbody tr');
    this.loadingState = page.locator('.loading-state');
    this.emptyState = page.locator('.empty-state');
  }

  /** Navigate to patients list */
  async goto(): Promise<void> {
    await this.page.goto('/patients', { waitUntil: 'networkidle' });
  }

  /** Wait for list to finish loading */
  async waitForLoad(): Promise<void> {
    await this.page.waitForFunction(
      () =>
        !document.querySelector('.loading-state') ||
        document.querySelector('.data-table') ||
        document.querySelector('.empty-state'),
      { timeout: 15_000 },
    );
  }

  /** Assert the patient list page is loaded */
  async expectPageLoaded(): Promise<void> {
    await expect(this.pageTitle).toContainText('Patients');
  }

  /** Search for a patient */
  async search(term: string): Promise<void> {
    await this.searchInput.fill(term);
    await this.page.waitForTimeout(500); // debounce
  }

  /** Clear search */
  async clearSearch(): Promise<void> {
    await this.searchInput.clear();
    await this.page.waitForTimeout(500);
  }

  /** Get the row count */
  async getRowCount(): Promise<number> {
    return this.tableRows.count();
  }

  /** Click the register patient button */
  async clickRegister(): Promise<void> {
    await this.registerButton.click();
    await this.page.waitForURL('**/patients/new');
  }

  /** Click the view action for a row */
  async viewPatient(rowIndex: number): Promise<void> {
    const row = this.tableRows.nth(rowIndex);
    await row.locator('.action-link').click();
  }

  /** Get patient name from a row */
  async getPatientName(rowIndex: number): Promise<string> {
    const row = this.tableRows.nth(rowIndex);
    return (await row.locator('.name-cell span').textContent()) ?? '';
  }

  /** Check if table or empty state is shown */
  async hasPatients(): Promise<boolean> {
    await this.waitForLoad();
    return (await this.dataTable.count()) > 0;
  }
}
