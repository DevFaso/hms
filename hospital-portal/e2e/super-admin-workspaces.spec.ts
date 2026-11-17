import { test, expect } from './fixtures/test-fixtures';
const HOME = '00000000-0000-0000-0000-000000000101';
const TARGET = '00000000-0000-0000-0000-000000000202';
const profile = {
  id: HOME,
  username: 'scope-review',
  roles: ['ROLE_SUPER_ADMIN'],
  active: true,
  primaryHospitalId: HOME,
  primaryHospitalName: 'Home hospital',
  hospitalIds: [HOME, TARGET],
};
const hospitals = [
  { id: HOME, name: 'Home hospital', active: true },
  { id: TARGET, name: 'Target hospital', active: true },
];
test.describe('super-admin workspaces', () => {
  test.use({ storageState: { cookies: [], origins: [] } });
  test.beforeEach(async ({ page }) => {
    await page.addInitScript(
      ({ profile }) => {
        const payload = btoa(
          JSON.stringify({ sub: profile.username, roles: profile.roles, exp: 9999999999 }),
        );
        localStorage.setItem('auth_token', `eyJhbGciOiJIUzI1NiJ9.${payload}.scope-test-signature`);
        localStorage.setItem('user_profile', JSON.stringify(profile));
      },
      { profile },
    );
    await page.route('**/api/auth/session/bootstrap', (route) =>
      route.fulfill({
        json: {
          ...profile,
          userId: HOME,
          authSource: 'internal',
          superAdmin: true,
          hospitalAdmin: false,
          permittedHospitalIds: [HOME, TARGET],
          staffId: HOME,
        },
      }),
    );
    await page.route('**/api/hospitals**', (route) => route.fulfill({ json: hospitals }));
    await page.route('**/api/hospitals/*', (route) =>
      route.fulfill({
        json: hospitals.find((h) => route.request().url().endsWith(h.id)) ?? hospitals[1],
      }),
    );
  });
  test('procedure list uses selected hospital and cancellation shows historical consent', async ({
    page,
  }) => {
    const reads: { url: string; hospital?: string }[] = [];
    await page.route('**/api/procedure-orders/hospital/**', (route) => {
      reads.push({
        url: route.request().url(),
        hospital: route.request().headers()['x-hospital-id'],
      });
      return route.fulfill({
        json: [
          {
            id: TARGET,
            patientId: HOME,
            patientName: 'Scope patient',
            patientMrn: 'MRN-202',
            hospitalId: TARGET,
            procedureName: 'Scope procedure',
            indication: 'Review',
            urgency: 'ROUTINE',
            status: 'CANCELLED',
            consentObtained: false,
            cancellationReason: 'Review',
          },
        ],
      });
    });
    await page.goto(`/procedure-orders?hospitalId=${TARGET}`);
    const screen = page.locator('app-procedure-orders');
    await expect(screen.getByText('Scope patient', { exact: false })).toBeVisible();
    expect(reads.length).toBeGreaterThan(0);
    expect(reads.every((r) => r.url.includes(`/hospital/${TARGET}`) && r.hospital === TARGET)).toBe(
      true,
    );
    await screen.locator('.actions-cell button').click();
    await expect(screen.getByRole('dialog')).toContainText(
      'Consentement non recueilli avant annulation',
    );
  });
  test('global procedure view never requests a clinical worklist', async ({ page }) => {
    let reads = 0;
    await page.route('**/api/procedure-orders/**', (route) => {
      reads++;
      return route.fulfill({ json: [] });
    });
    await page.goto('/procedure-orders');
    await expect(page.getByTestId('hospital-network-workspace')).toBeVisible();
    await expect(page.locator('app-procedure-orders')).toHaveCount(0);
    expect(reads).toBe(0);
  });
  test('education is globally readable but requires a hospital to create', async ({ page }) => {
    await page.route('**/api/patient-education/resources**', (route) =>
      route.fulfill({ json: [] }),
    );
    await page.goto('/patient-education');
    const screen = page.locator('app-patient-education');
    await expect(screen).toContainText('Biblioth');
    await expect(screen.locator('button', { hasText: 'Nouvelle ressource' })).toHaveCount(0);
    await page.goto(`/patient-education?hospitalId=${TARGET}`);
    await expect(screen.locator('button', { hasText: 'Nouvelle ressource' }).first()).toBeVisible();
    await expect(screen.locator('app-hospital-scope-chip')).toContainText('Target hospital');
  });
  test('registry All requests all six explicit statuses at the selected hospital', async ({
    page,
  }) => {
    const statuses: string[] = [];
    await page.route('**/api/programs/HIV/registry**', (route) => {
      const url = new URL(route.request().url());
      if (url.pathname.endsWith('/counts')) return route.fulfill({ json: { ACTIVE: 0 } });
      statuses.push(url.searchParams.get('status') ?? '');
      expect(route.request().headers()['x-hospital-id']).toBe(TARGET);
      return route.fulfill({
        json: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 200 },
      });
    });
    await page.goto(`/registries?hospitalId=${TARGET}`);
    const screen = page.locator('app-registries');
    await expect(screen.locator('.status-chips')).toBeVisible();
    await screen.getByRole('button', { name: 'Tous les statuts' }).click();
    await expect.poll(() => new Set(statuses).size).toBe(6);
    await expect(screen.locator('.empty-state')).toContainText('VIH');
  });
});
