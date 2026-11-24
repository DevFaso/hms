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
    await page.waitForLoadState('networkidle');
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
  test('super-admin without a hospital staff profile gets the management overview', async ({
    page,
  }, testInfo) => {
    await page.route('**/api/panels/my**', (route) =>
      route.fulfill({ status: 400, json: { message: 'No local staff profile' } }),
    );
    await page.route('**/api/panels/overview', (route) =>
      route.fulfill({
        json: [
          {
            providerStaffId: HOME,
            providerName: 'Team clinician',
            panelRole: 'CHW',
            activeCount: 3,
          },
        ],
      }),
    );
    await page.goto(`/panels?hospitalId=${TARGET}`);
    const screen = page.locator('app-panel');
    await expect(screen.locator('#panel-overview-heading')).toBeVisible();
    await expect(screen.locator('#my-panel-heading')).toHaveCount(0);
    await expect(screen).toContainText('Rattachements actifs');
    await expect(screen).toContainText('Team clinician');
    await testInfo.attach('panel-management', {
      body: await page.screenshot({ fullPage: true }),
      contentType: 'image/png',
    });
  });
  test('maternity filter badges use hospital totals, retain zero and isolate unavailable worklists', async ({
    page,
  }, testInfo) => {
    const reads: { url: URL; hospital: string | undefined }[] = [];
    let highRiskTotal = 11;
    await page.route('**/api/maternal-history/**', (route) => {
      const url = new URL(route.request().url());
      reads.push({ url, hospital: route.request().headers()['x-hospital-id'] });
      const worklist = url.pathname.split('/').pop()!;
      const totals: Record<string, number> = {
        'high-risk': highRiskTotal,
        'pending-review': 3,
        'specialist-referral': 2,
        'psychosocial-concerns': 0,
        search: 25,
      };
      return route.fulfill({
        json: {
          content: [],
          totalElements: totals[worklist],
          totalPages: 2,
          number: 0,
          size: Number(url.searchParams.get('size')),
        },
      });
    });
    await page.route('**/api/birth-plans/**', (route) => {
      const url = new URL(route.request().url());
      reads.push({ url, hospital: route.request().headers()['x-hospital-id'] });
      const total = url.pathname.endsWith('/pending-review')
        ? 4
        : url.searchParams.get('providerReviewed') === 'true'
          ? 8
          : 17;
      return route.fulfill({
        json: {
          content: [],
          totalElements: total,
          totalPages: 2,
          number: 0,
          size: Number(url.searchParams.get('size')),
        },
      });
    });
    await page.route('**/api/ultrasound/**', (route) => {
      const url = new URL(route.request().url());
      reads.push({ url, hospital: route.request().headers()['x-hospital-id'] });
      if (url.pathname.endsWith('/high-risk')) return route.fulfill({ status: 503, json: {} });
      const count = url.pathname.endsWith('/pending')
        ? 1
        : url.pathname.endsWith('/follow-up-required')
          ? 2
          : url.pathname.endsWith('/anomalies')
            ? 0
            : 3;
      return route.fulfill({
        json: Array.from({ length: count }, (_, i) => ({
          id: `${i}`,
          patientId: HOME,
          patientDisplayName: `Count patient ${i}`,
          scanType: 'GROWTH',
          status: 'ORDERED',
        })),
      });
    });
    await page.goto('/maternity');
    await expect(page.getByTestId('hospital-network-workspace')).toBeVisible();
    await page.waitForLoadState('networkidle');
    expect(reads).toHaveLength(0);
    await page.goto(`/maternity?hospitalId=${TARGET}`);
    const screen = page.locator('app-maternity');
    await expect(screen.locator('.worklist-count')).toHaveText(['11', '3', '2', '0', '25']);
    highRiskTotal = 16;
    await screen.getByRole('button', { name: 'Actualiser', exact: true }).click();
    await expect(screen.locator('.worklist-count')).toHaveText(['16', '3', '2', '0', '25']);
    await screen.getByRole('button', { name: 'Plans de naissance', exact: true }).click();
    await expect(screen.locator('app-birth-plan-tab .worklist-count')).toHaveText(['17', '4', '8']);
    await screen.getByRole('button', { name: 'Échographie', exact: true }).click();
    await expect(screen.locator('app-ultrasound-tab .worklist-count')).toHaveText([
      '3',
      '1',
      '—',
      '2',
      '0',
    ]);
    expect(reads.length).toBeGreaterThan(10);
    expect(
      reads.every(
        ({ url, hospital }) =>
          hospital === TARGET &&
          (url.pathname.includes(TARGET) || url.searchParams.get('hospitalId') === TARGET),
      ),
    ).toBe(true);
    await testInfo.attach('maternity-filter-counts', {
      body: await page.screenshot({ fullPage: true }),
      contentType: 'image/png',
    });
  });
  test('maternity four-digit MRN search remains hospital-scoped and requires choosing a patient', async ({
    page,
  }, testInfo) => {
    const reads: { url: string; method: string; body: Record<string, unknown> | null }[] = [];
    await page.route('**/api/maternal-history/**', (route) =>
      route.fulfill({
        json: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 },
      }),
    );
    await page.route('**/api/birth-plans/search**', (route) =>
      route.fulfill({
        json: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 },
      }),
    );
    await page.route('**/api/patients/search**', (route) => {
      expect(route.request().headers()['x-hospital-id']).toBe(TARGET);
      reads.push({
        url: route.request().url(),
        method: route.request().method(),
        body: route.request().postDataJSON(),
      });
      return route.fulfill({
        json: [
          { id: HOME, firstName: 'Awa', lastName: 'Test', mrn: 'CHW5624', active: true },
          { id: TARGET, firstName: 'Ben', lastName: 'Test', mrn: 'CHW15624', active: true },
        ],
      });
    });
    await page.goto(`/maternity?hospitalId=${TARGET}`);
    const screen = page.locator('app-maternity');
    await expect(screen.locator('details')).not.toHaveAttribute('open', '');
    await screen.getByRole('button', { name: 'Plans de naissance', exact: true }).click();
    const search = screen.locator('#bp-filter-patient');
    await expect(search).toHaveAttribute('placeholder', /4 chiffres/);
    await search.fill('5624');
    await expect(screen.getByRole('option')).toHaveCount(2);
    expect(
      reads.some(
        (read) =>
          // The term travels in the POST body, never in the URL; the scope stays in the query.
          read.method === 'POST' &&
          read.body?.['name'] === '5624' &&
          !new URL(read.url).searchParams.has('name') &&
          new URL(read.url).searchParams.get('hospitalId') === TARGET,
      ),
    ).toBe(true);
    await expect(screen.locator('.picker-chip')).toHaveCount(0);
    await testInfo.attach('maternity-mrn-search', {
      body: await page.screenshot({ fullPage: true }),
      contentType: 'image/png',
    });
  });
});
