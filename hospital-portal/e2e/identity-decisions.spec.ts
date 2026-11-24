import { test, expect } from './fixtures/test-fixtures';
import { signInAs, TEST_ID } from './helpers/role-session';
import { installLocalIconFonts } from './helpers/local-icon-fonts';
import AxeBuilder from '@axe-core/playwright';

for (const width of [390, 1440]) {
  test(`registration requires a keep-separate decision at ${width}px`, async ({ page, baseURL }, info) => {
    await page.setViewportSize({ width, height: 1000 });
    await signInAs(page, 'ROLE_RECEPTIONIST', baseURL!);
    await installLocalIconFonts(page);
    await page.route('**/api/me/hospital', route => route.fulfill({ json: { id: TEST_ID, name: 'cdx Hospital' } }));
    await page.route('**/api/patients/phone-verification/availability', route => route.fulfill({ json: { available: false } }));
    await page.route('**/api/patients/registration-match**', route => route.fulfill({ json: [] }));
    await page.route('**/api/reception/patients/duplicate-candidates', route => route.fulfill({ json: [{
      patientId: '00000000-0000-0000-0000-00000000cd13', fullName: 'cdx Awa Ouédraogo-N’Guessan',
      mrn: 'cdx-123', dateOfBirth: '1992-05-04', confidenceScore: 65,
    }] }));
    const registrations: Record<string, unknown>[] = [];
    await page.route('**/api/users/admin-register', route => {
      registrations.push(route.request().postDataJSON());
      return route.fulfill({ json: { id: TEST_ID, patientId: TEST_ID, activationDelivery: [] } });
    });
    await page.goto('/patients/new');
    await page.locator('#firstName').fill('cdx Awa');
    await page.locator('#lastName').fill('Ouédraogo-N’Guessan');
    await page.locator('#phonePrimary').fill('+22670000000');
    await page.locator('#dob').fill('1992-05-04');
    await page.locator('#gender').selectOption('FEMALE');
    await page.locator('#country').fill('Burkina Faso');
    await page.locator('#city').fill('Ouagadougou');
    await expect(page.locator('.duplicate-warning-strip')).toBeVisible();
    await page.locator('.form-actions button[type="submit"]').click();
    const dialog = page.getByRole('alertdialog');
    await expect(dialog).toBeVisible();
    await dialog.locator('.btn-danger').click();
    expect(registrations).toHaveLength(0);
    const reason = dialog.locator('#duplicate-review-reason');
    await expect(reason).toHaveAttribute('aria-invalid', 'true');
    await expect(reason).toBeFocused();
    await expect(dialog.locator('#duplicate-review-error')).not.toBeEmpty();
    const axe = await new AxeBuilder({ page }).include('[role="alertdialog"]').withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
    expect(axe.violations.filter(v => v.impact === 'serious' || v.impact === 'critical')).toEqual([]);
    await page.screenshot({ path: info.outputPath(`keep-separate-${width}.png`) });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
    await reason.fill('cdx Documents et prénoms vérifiés : personnes différentes');
    await dialog.locator('.btn-danger').click();
    await expect(page).toHaveURL(url => url.pathname === `/patients/${TEST_ID}`);
    expect(registrations).toHaveLength(1);
    expect((registrations[0]['patient'] as Record<string, unknown>)['duplicateReviewReason'])
      .toBe('cdx Documents et prénoms vérifiés : personnes différentes');
  });
}
