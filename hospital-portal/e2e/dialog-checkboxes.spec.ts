/**
 * QA D1451: a checkbox inside a dialog answers a real click and a real key.
 *
 * A backdrop whose click expression answered `false` had Angular cancel every click that bubbled
 * through it. Karma cannot press a trusted key, so the Space key and the mouse are proved here, in
 * a browser, on the dialogs of /transfusions, against stubbed responses. MortalityComponent has no
 * route to reach it by, so its boxes are proved by click in its Karma spec.
 */
import { test, expect, type Locator, type Page } from '@playwright/test';

const json = (body: unknown) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify(body),
});

const REQUEST = {
  id: 'req-1',
  patientId: 'p1',
  patientName: 'Awa Kabore',
  productType: 'PACKED_RED_CELLS',
  unitsRequested: 1,
  indication: 'Hémorragie',
  urgency: 'EMERGENCY',
  status: 'REQUESTED',
  screenCurrent: true,
  units: [],
  crossmatches: [],
};

const unit = (id: string, rhFactor: string, extra = {}) => ({
  id,
  unitNumber: id,
  aboGroup: 'O',
  rhFactor,
  expiresOn: '2030-01-01',
  decisionRequired: rhFactor === 'POSITIVE',
  ...extra,
});

const base64url = (value: unknown) => Buffer.from(JSON.stringify(value)).toString('base64url');

/** A session of its own, with no backend behind it: the token is read, never verified. */
async function signIn(page: Page, role: string): Promise<void> {
  const token = [
    base64url({ alg: 'HS384' }),
    base64url({ sub: 'clinician', roles: [role], iat: 1700000000, exp: 9999999999 }),
    'fake-signature-for-e2e-testing',
  ].join('.');
  await page.addInitScript((value) => localStorage.setItem('auth_token', value), token);
  await page.route('**/api/**', (route) => route.fulfill(json({ content: [], totalElements: 0 })));
  await page.route('**/api/auth/session/bootstrap', (route) =>
    route.fulfill(json({ roles: [role], permittedHospitalIds: ['h1'], primaryHospitalId: 'h1' })),
  );
}

async function stubTransfusions(page: Page): Promise<void> {
  await signIn(page, 'ROLE_LAB_DIRECTOR');
  await page.route('**/api/transfusions/**', (route) => {
    const url = route.request().url();
    if (url.includes('/emergency-release/options') || url.endsWith('/emergency-release-options')) {
      return route.fulfill(
        json({
          antibodyWarning: false,
          antibodyDetail: null,
          negativeStock: [unit('ONEG-1', 'NEGATIVE')],
          units: [
            unit('OPOS-1', 'POSITIVE', { statementRequired: true }),
            unit('ONEG-1', 'NEGATIVE'),
          ],
          incompatibleUnits: [],
          deciders: [{ userId: 'doc', displayName: 'Dr A' }],
          authorisers: [],
        }),
      );
    }
    if (url.includes('/requests/req-1')) return route.fulfill(json(REQUEST));
    if (url.includes('/requests')) return route.fulfill(json([REQUEST]));
    return route.fulfill(json([]));
  });
}

/** The mouse on the box, the mouse on its label, then Space with the focus on it. */
async function expectAnswersMouseAndKey(box: Locator, label: Locator): Promise<void> {
  const start = await box.isChecked();
  await box.click();
  await expect(box).toBeChecked({ checked: !start });
  await label.click();
  await expect(box).toBeChecked({ checked: start });
  await box.focus();
  await box.press('Space');
  await expect(box).toBeChecked({ checked: !start });
}

test.describe('D1451: checkboxes inside a dialog', () => {
  test('the crossmatch box', async ({ page }) => {
    await stubTransfusions(page);
    await page.goto('/transfusions');
    await page.locator('tbody tr button').first().click();
    await page
      .locator('button', { hasText: /crossmatch|compatibilité|compatibilidad/i })
      .first()
      .click();
    const box = page.locator('.modal-body input[type="checkbox"]');
    await expectAnswersMouseAndKey(box, page.locator('.modal-body label.checkbox-field'));
  });

  test('the O-negative statement', async ({ page }) => {
    await stubTransfusions(page);
    await page.goto('/transfusions');
    await page.locator('tbody tr button').first().click();
    await page.getByTestId('emergency-release').click();
    await page.locator('#em-unit').selectOption('OPOS-1');
    const box = page.locator('#em-statement');
    await expectAnswersMouseAndKey(box, page.locator('label.checkbox-field', { has: box }));
  });
});
