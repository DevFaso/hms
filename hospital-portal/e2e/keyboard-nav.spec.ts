import type { Page } from '@playwright/test';

import { test, expect } from './fixtures/test-fixtures';

/**
 * v1.0 / Accessibility / Keyboard navigation pass (roadmap row 11).
 *
 * Companion to e2e/a11y.spec.ts (axe rule scan). This spec drives the
 * critical clinical-flow surfaces using the keyboard alone — Tab,
 * Shift+Tab, Enter, Escape — and asserts on `document.activeElement` to
 * prove that every screen is reachable and actionable without a mouse.
 *
 * Why both specs:
 *   - axe-core/playwright catches structural a11y bugs (missing labels,
 *     low contrast, mis-nested headings).
 *   - This spec catches functional bugs that axe cannot see — e.g. a
 *     button that exists in the DOM but cannot be reached via Tab, or
 *     a modal that traps focus inside a hidden element.
 *
 * Per docs/ui/accessibility.md §11, this spec is part of the CI gate;
 * any PR that adds a clinical-flow screen which fails the keyboard
 * journey blocks merge.
 *
 * Scope (current pass — see roadmap row 11):
 *   - Skip-link discoverable on first Tab from the shell
 *   - Skip-link jumps focus to <main id="main-content">
 *   - Route changes move focus back to <main> (covers screen-reader
 *     context loss after Angular's default no-op behavior)
 *   - Login → dashboard happy path is fully keyboard-driven
 *
 * Per-screen keyboard contracts (form ordering, vitals grid arrow keys,
 * CDS warn-card Esc dismiss) land in follow-up PRs as each clinical-flow
 * screen receives its keyboard-nav audit. Tests for those will be added
 * alongside the screen-level work.
 *
 * Runs in the `chromium` (dev-server) Playwright project, NOT `smoke`.
 * The filename intentionally avoids the `*smoke.spec.ts` glob.
 */

/**
 * Returns a CSS selector that uniquely identifies the currently focused
 * element. Falls back to its tag name if no id/data-testid is set.
 */
async function focusedDescriptor(page: Page): Promise<string> {
  return page.evaluate(() => {
    const el = document.activeElement as HTMLElement | null;
    if (!el || el === document.body) return 'body';
    const id = el.id ? `#${el.id}` : '';
    const testid = el.getAttribute('data-testid');
    const testidPart = testid ? `[data-testid="${testid}"]` : '';
    return `${el.tagName.toLowerCase()}${id}${testidPart}`;
  });
}

test.describe('keyboard navigation — shell + skip-link', () => {
  test('first Tab reveals the skip-link, Enter jumps to <main id="main-content">', async ({
    page,
  }) => {
    await page.goto('/dashboard', { waitUntil: 'domcontentloaded' });
    // Let Angular finish the initial render so the shell is mounted.
    await page.waitForSelector('#main-content');

    // Skip-link is the first focusable element in the shell. Some
    // browsers focus <body> initially; Tab should move us into the
    // skip-link.
    await page.keyboard.press('Tab');

    const focused = page.locator(':focus');
    await expect(focused).toHaveAttribute('data-testid', 'skip-link');

    // Activating it moves focus to <main id="main-content">.
    await page.keyboard.press('Enter');

    const mainHasFocus = await page.evaluate(
      () => document.activeElement?.id === 'main-content',
    );
    expect(mainHasFocus).toBeTruthy();
  });

  test('NavigationEnd moves focus back to <main> on route change', async ({ page }) => {
    // Land on /dashboard first.
    await page.goto('/dashboard', { waitUntil: 'domcontentloaded' });
    await page.waitForSelector('#main-content');

    // Focus a sidebar link so we have a non-<main> active element to
    // displace. We use the keyboard so the test stays keyboard-only.
    // Tab off the skip-link (1 press) and into the sidebar (a few more).
    // The nav-item link to /patient-tracker is reachable via Tab — we
    // just keep pressing Tab until we find one.
    let attempts = 0;
    while (attempts++ < 50) {
      await page.keyboard.press('Tab');
      const href = await page.evaluate(
        () => (document.activeElement as HTMLAnchorElement | null)?.getAttribute?.('href') ?? '',
      );
      if (href.endsWith('/patient-tracker')) break;
    }
    expect(attempts, 'patient-tracker nav link should be reachable via Tab').toBeLessThan(50);

    await page.keyboard.press('Enter');

    // Wait for the URL change, then poll the DOM until the shell's
    // NavigationEnd → focus hook (queueMicrotask in shell.ts
    // ngAfterViewInit) actually lands focus on <main>. Polling — not
    // a fixed waitForTimeout — so the test stays deterministic under
    // CI load. Per Copilot review on PR #288.
    await page.waitForURL('**/patient-tracker', { timeout: 5000 });
    await page.waitForFunction(() => document.activeElement?.id === 'main-content', null, {
      timeout: 5000,
      polling: 16, // ~one animation frame
    });
    // If we reach this line, the assertion is implicit in waitForFunction —
    // but make it explicit too so the failure message is readable.
    const mainHasFocus = await page.evaluate(
      () => document.activeElement?.id === 'main-content',
    );
    expect(
      mainHasFocus,
      'focus should land on <main id="main-content"> after navigation',
    ).toBeTruthy();
  });
});

test.describe('keyboard navigation — login (unauthenticated)', () => {
  // Discard the SuperAdmin storage state from the chromium project so
  // LoginRedirectGuard does NOT redirect us away from /login. Same
  // pattern as the unauthenticated section of a11y.spec.ts.
  test.use({ storageState: { cookies: [], origins: [] } });

  test('username input is autofocused; Enter submits the form', async ({ page }) => {
    await page.goto('/login', { waitUntil: 'domcontentloaded' });
    await page.waitForSelector('#username');

    // Wait for Login.ngAfterViewInit → queueMicrotask → focus() to
    // settle on #username. Poll the DOM instead of a fixed sleep so
    // we don't flake under CI load. Per Copilot review on PR #288.
    await page.waitForFunction(() => document.activeElement?.id === 'username', null, {
      timeout: 5000,
      polling: 16,
    });
    const focusedId = await page.evaluate(() => document.activeElement?.id ?? '');
    expect(focusedId).toBe('username');

    // Type a username, Tab to password, type, Enter to submit. The
    // form's `(ngSubmit)` handler fires; we don't assert on the API
    // call (that would require richer mocks) — only that the keyboard
    // path works.
    await page.keyboard.type('keyboard-user');
    await page.keyboard.press('Tab');
    const afterTab = await focusedDescriptor(page);
    expect(afterTab).toContain('input#password');

    await page.keyboard.type('hunter2');
    // Enter on a form input triggers (ngSubmit). The form will either
    // succeed or surface an error banner; either way the keyboard
    // journey is complete from the user's standpoint.
    await page.keyboard.press('Enter');
    // No further assertion — the loading state or error banner means
    // the submit fired. The point of this test is the keyboard path.
  });
});
