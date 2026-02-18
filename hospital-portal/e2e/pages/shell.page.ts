/**
 * ShellPage – Page Object Model for the authenticated shell (sidebar + topbar).
 */
import { type Page, type Locator, expect } from '@playwright/test';

export class ShellPage {
  readonly page: Page;

  /* ── Sidebar ── */
  readonly sidebar: Locator;
  readonly sidebarToggle: Locator;
  readonly logoText: Locator;
  readonly navItems: Locator;

  /* ── Topbar ── */
  readonly topbar: Locator;
  readonly pageTitle: Locator;
  readonly notificationBtn: Locator;
  readonly notificationBadge: Locator;
  readonly profileBtn: Locator;
  readonly profileName: Locator;
  readonly profileMenu: Locator;
  readonly logoutBtn: Locator;
  readonly mobileToggle: Locator;

  /* ── Notification Panel ── */
  readonly notifPanel: Locator;
  readonly notifPanelClose: Locator;

  constructor(page: Page) {
    this.page = page;

    // Sidebar
    this.sidebar = page.locator('aside.sidebar');
    this.sidebarToggle = page.locator('button.toggle-btn');
    this.logoText = page.locator('.logo-text');
    this.navItems = page.locator('.nav-item');

    // Topbar
    this.topbar = page.locator('header.topbar');
    this.pageTitle = page.locator('header.topbar .page-title');
    this.notificationBtn = page.locator('button.icon-btn').first();
    this.notificationBadge = page.locator('.badge');
    this.profileBtn = page.locator('.profile-btn');
    this.profileName = page.locator('.profile-name');
    this.profileMenu = page.locator('.profile-menu');
    this.logoutBtn = page.locator(
      'button:has-text("Logout"), button:has-text("Sign Out"), .logout-btn',
    );
    this.mobileToggle = page.locator('.mobile-toggle');

    // Notification Panel
    this.notifPanel = page.locator('.notif-panel');
    this.notifPanelClose = page.locator('.close-panel');
  }

  /** Navigate to a page using the sidebar */
  async navigateTo(label: string): Promise<void> {
    const navItem = this.page.locator(`.nav-item:has(.nav-label:text("${label}"))`);
    // If sidebar is collapsed, expand it first
    const isCollapsed = await this.page.locator('.shell.collapsed').count();
    if (isCollapsed > 0) {
      await this.sidebarToggle.click();
      await this.page.waitForTimeout(300); // animation
    }
    await navItem.click();
    await this.page.waitForLoadState('networkidle');
  }

  /** Get the nav item locator by label text */
  getNavItem(label: string): Locator {
    return this.page.locator(`.nav-item`, {
      has: this.page.locator(`.nav-label:text("${label}")`),
    });
  }

  /** Get all visible nav labels */
  async getNavLabels(): Promise<string[]> {
    const labels = this.page.locator('.nav-label');
    return labels.allTextContents();
  }

  /** Toggle sidebar collapse */
  async toggleSidebar(): Promise<void> {
    await this.sidebarToggle.click();
    await this.page.waitForTimeout(300);
  }

  /** Check if sidebar is collapsed */
  async isSidebarCollapsed(): Promise<boolean> {
    return (await this.page.locator('.shell.collapsed').count()) > 0;
  }

  /** Open notifications panel */
  async openNotifications(): Promise<void> {
    await this.notificationBtn.click();
    await expect(this.notifPanel).toBeVisible();
  }

  /** Close notifications panel */
  async closeNotifications(): Promise<void> {
    await this.notifPanelClose.click();
    await expect(this.notifPanel).not.toBeVisible();
  }

  /** Open profile menu */
  async openProfileMenu(): Promise<void> {
    await this.profileBtn.click();
  }

  /** Logout */
  async logout(): Promise<void> {
    await this.openProfileMenu();
    await this.logoutBtn.click();
    await this.page.waitForURL('**/login', { timeout: 10_000 });
  }

  /** Assert shell is fully rendered (authenticated state) */
  async expectShellVisible(): Promise<void> {
    await expect(this.sidebar).toBeVisible();
    await expect(this.topbar).toBeVisible();
  }

  /** Assert the active nav item */
  async expectActiveNav(label: string): Promise<void> {
    const item = this.getNavItem(label);
    await expect(item).toHaveClass(/active/);
  }
}
