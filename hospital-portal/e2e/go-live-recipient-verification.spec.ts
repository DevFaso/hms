import { test, expect } from './fixtures/test-fixtures';
import { signInAs, TEST_ID } from './helpers/role-session';
import fr from '../src/assets/i18n/fr.json';
import { installLocalIconFonts } from './helpers/local-icon-fonts';

test.use({ storageState: { cookies: [], origins: [] } });

for (const actor of ['patient', 'desk'] as const) {
  test(`current recipient verification: ${actor}, refusal then confirmed code`, async ({
    page,
    baseURL,
  }, info) => {
    await signInAs(page, actor === 'patient' ? 'ROLE_PATIENT' : 'ROLE_RECEPTIONIST', baseURL!);
    await installLocalIconFonts(page);
    const endpoint =
      actor === 'patient'
        ? '/api/me/patient/recipient-verification'
        : `/api/patients/${TEST_ID}/recipient-verification`;
    await page.route('**/api/notifications/preferences', (route) => route.fulfill({ json: [] }));
    await page.route(`**/api/patients/${TEST_ID}*`, (route) => {
      if (new URL(route.request().url()).pathname !== `/api/patients/${TEST_ID}`)
        return route.fallback();
      return route.fulfill({
        json: {
          id: TEST_ID,
          firstName: 'cdx-Fixture',
          lastName: 'Patient',
          dateOfBirth: '1990-01-01',
          active: true,
          hospitalId: TEST_ID,
        },
      });
    });
    await page.route(`**/api/patients/${TEST_ID}/channel-preferences*`, (route) =>
      route.fulfill({ json: [] }),
    );
    let confirmations = 0;
    await page.route(`**${endpoint}**`, (route) => {
      const request = route.request();
      if (request.method() === 'GET')
        return route.fulfill({
          json: [
            { channel: 'SMS', verified: false, maskedRecipient: '•••• 3456' },
            { channel: 'EMAIL', verified: true },
          ],
        });
      if (new URL(request.url()).pathname.endsWith('/request')) {
        expect(request.postDataJSON()).toEqual({});
        return route.fulfill({
          json: {
            channel: 'SMS',
            verified: false,
            maskedRecipient: '•••• 3456',
            expiresAt: '2026-11-24T08:10:00',
          },
        });
      }
      expect(request.postDataJSON()).toEqual({ code: confirmations === 0 ? '000000' : '123456' });
      confirmations++;
      if (confirmations === 1)
        return route.fulfill({ status: 400, json: { message: 'cdx-Code invalid' } });
      return route.fulfill({ json: { channel: 'SMS', verified: true } });
    });
    await page.goto(actor === 'patient' ? '/notification-settings' : `/patients/${TEST_ID}`);
    if (actor === 'desk') await page.getByTestId('administration-section-button').click();
    const channel = page.getByTestId('verify-SMS');
    await channel.getByRole('button').first().click();
    await expect(page.getByTestId('verification-code-sent')).toContainText('3456');
    await expect(page.getByTestId('verification-code-sent')).toContainText(
      fr.RECIPIENT_VERIFICATION.CODE_SENT.replace('{{contact}}', '•••• 3456'),
    );
    for (const width of [390, 1440]) {
      await page.setViewportSize({ width, height: 900 });
      const fits = await channel.locator('input').evaluate((el) => {
        const box = el.getBoundingClientRect();
        const form = el.closest('form')!.getBoundingClientRect();
        return box.left >= form.left && box.right <= form.right;
      });
      expect(fits).toBe(true);
      const screenshot = info.outputPath(`code-sent-${actor}-${width}.png`);
      await page.screenshot({ path: screenshot, fullPage: true });
      await info.attach(`code-sent-${actor}-${width}`, {
        path: screenshot,
        contentType: 'image/png',
      });
    }
    await channel.locator('input').fill('000000');
    await channel.locator('button[type="submit"]').click();
    await expect(page.getByRole('alert').filter({ hasText: 'cdx-Code invalid' })).toBeVisible();
    await expect(channel.locator('input')).toBeVisible();
    await channel.locator('input').fill('123456');
    await channel.locator('button[type="submit"]').click();
    await expect(channel.locator('input')).toHaveCount(0);
    await expect(channel).toContainText('Vérifié');
    expect(confirmations).toBe(2);
    for (const width of [390, 768, 1024, 1440]) {
      await page.setViewportSize({ width, height: 900 });
      expect(
        await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1),
      ).toBe(true);
      await page.screenshot({ path: info.outputPath(`verification-${actor}-${width}.png`) });
    }
  });
}
