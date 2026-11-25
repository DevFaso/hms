import AxeBuilder from '@axe-core/playwright';
import { expect, type Locator, type Page } from '@playwright/test';
import { blockingAxeViolations } from './axe-blocking';

// The only way these journeys move focus is a real keyboard action. Querying
// focus is observational: no locator.focus/click/fill/selectOption shortcuts.
export async function tabTo(page: Page, target: Locator, backwards = false): Promise<void> {
  await expect(target).toBeVisible();
  for (let count = 0; count < 160; count++) {
    if (await target.evaluate((el) => el === document.activeElement)) return;
    await page.keyboard.press(backwards ? 'Shift+Tab' : 'Tab');
  }
  throw new Error('Keyboard could not reach the visible target');
}

export async function announcedRefusal(page: Page, field: Locator): Promise<void> {
  await expect(field).toHaveAttribute('aria-invalid', 'true');
  await expect(field).toBeFocused();
  const ids = (await field.getAttribute('aria-describedby'))?.trim().split(/\s+/) ?? [];
  expect(ids.length).toBeGreaterThan(0);
  const alerts = page.locator(ids.map((id) => `[id="${id}"][role="alert"]`).join(', '));
  await expect(alerts.first()).toBeVisible();
  await expect(alerts.first()).not.toHaveText('');
}

export async function accessibleAtWidths(page: Page, scope: string): Promise<void> {
  for (const width of [390, 1440]) {
    await page.setViewportSize({ width, height: 1000 });
    const scan = await new AxeBuilder({ page })
      .include(scope)
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze();
    expect(blockingAxeViolations(scan.violations)).toEqual([]);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(
      true,
    );
  }
}
