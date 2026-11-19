import { test, expect } from './fixtures/test-fixtures';

const HOSPITAL = '00000000-0000-0000-0000-000000000201';
const USER = '00000000-0000-0000-0000-000000000101';
const rows = Array.from({ length: 7 }, (_, i) => ({
  patientId: `00000000-0000-0000-0000-${String(301 + i).padStart(12, '0')}`,
  patientName: `Awa Démo très long nom de patiente ${i}`,
  mrn: `CHW${String(i + 42).padStart(4, '0')}`,
  roomBed: i % 2 ? 'MAT-A / Chambre 204 / Lit 2' : '',
  acuityLevel: 'LEVEL_3_MAJOR',
  admissionId: `admission-${i}`,
  departmentName: i % 2 ? 'Maternité' : 'Médecine générale',
  attendingDoctor: i % 2 ? 'Aminata Ouédraogo' : 'Bibata Yaméogo',
  admittedAt: '2026-11-15T08:00:00Z',
  lastVitalsTime: null,
  vitalsDue: true,
  medsDue: i,
}));

test.describe('nursing oversight', () => {
  test.use({ storageState: { cookies: [], origins: [] } });

  async function prepare(page: import('@playwright/test').Page, role: string) {
    // Keep local layout checks independent of the external font service.
    await page.route('https://fonts.googleapis.com/**', (route) => route.abort());
    await page.route('https://fonts.gstatic.com/**', (route) => route.abort());
    const profile = {
      id: USER,
      username: 'nursing-review',
      roles: [role],
      active: true,
      hospitalIds: [HOSPITAL],
    };
    await page.addInitScript(
      ({ profile }) => {
        const payload = btoa(
          JSON.stringify({
            sub: profile.username,
            userId: profile.id,
            roles: profile.roles,
            exp: 4102444800,
          }),
        );
        localStorage.setItem('auth_token', `eyJhbGciOiJIUzI1NiJ9.${payload}.review-signature`);
        localStorage.setItem('user_profile', JSON.stringify(profile));
      },
      { profile },
    );
    await page.route('**/api/auth/session/bootstrap', (route) =>
      route.fulfill({
        json: {
          ...profile,
          userId: USER,
          authSource: 'internal',
          superAdmin: role === 'ROLE_SUPER_ADMIN',
          hospitalAdmin: false,
          activeHospitalId: HOSPITAL,
          hospitalId: HOSPITAL,
          permittedHospitalIds: [HOSPITAL],
        },
      }),
    );
    await page.route(`**/api/hospitals/${HOSPITAL}`, (route) =>
      route.fulfill({ json: { id: HOSPITAL, name: 'Hôpital de revue', active: true } }),
    );
    const writes: string[] = [];
    page.on('request', (request) => {
      if (request.url().includes('/api/nurse/') && !['GET', 'HEAD'].includes(request.method()))
        writes.push(request.method());
    });
    await page.route('**/api/nurse/**', (route) => {
      const url = new URL(route.request().url());
      let json: unknown = [];
      if (url.pathname.endsWith('/workboard')) json = rows;
      if (url.pathname.endsWith('/dashboard/summary'))
        json = {
          vitalsDue: 7,
          medicationsDue: 0,
          medicationsAsNeeded: 1,
          medicationsOverdue: 5,
          ordersPending: 24,
        };
      if (url.pathname.endsWith('/patient-flow'))
        json = { pending: [], active: [], critical: [], readyForDischarge: [] };
      return route.fulfill({ json });
    });
    await page.goto(`/nurse-station?hospitalId=${HOSPITAL}`, { waitUntil: 'domcontentloaded' });
    await expect(page.locator('.care-overview')).toBeVisible();
    return writes;
  }

  for (const width of [1920, 1280, 390, 320]) {
    test(`super-admin reads and filters the workboard at ${width}px without clinical writes`, async ({
      page,
    }, info) => {
      await page.setViewportSize({ width, height: 1080 });
      const writes = await prepare(page, 'ROLE_SUPER_ADMIN');
      await expect(page.locator('.oversight-note')).toBeVisible();
      await expect(page.locator('.filter-btn')).toHaveCount(1);
      if (width <= 640) await page.locator('.section-picker select').selectOption('workboard');
      else await page.locator('[data-focus-place="nurse-sections"] button').nth(4).click();
      await expect(page.locator('.workboard-card')).toHaveCount(7);
      await expect(page.locator('.workboard-card .action-record')).toHaveCount(0);
      await expect(page.locator('.workboard-card .action-intake')).toHaveCount(0);
      await expect(page.locator('.workboard-card .action-view')).toHaveCount(7);
      const search = page.locator('.board-toolbar input');
      await search.fill('0042');
      await expect(page.locator('.workboard-card')).toHaveCount(1);
      await search.fill('');
      await page.locator('.board-toolbar select').first().selectOption({ label: 'Maternité' });
      await expect(page.locator('.workboard-card')).toHaveCount(3);
      await page.locator('.board-toolbar button').first().click();
      await page.locator('.board-toolbar button').last().click();
      await expect(page.locator('.board-table tbody tr')).toHaveCount(7);
      await page.locator('.board-toolbar button').nth(1).click();
      await expect(page.locator('.workboard-card')).toHaveCount(7);
      const overflow = await page
        .locator('.workboard-card')
        .evaluateAll((cards) => cards.some((card) => card.scrollWidth > card.clientWidth + 1));
      expect(overflow).toBe(false);
      expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1)).toBe(
        false,
      );
      await page.locator('.workboard-card').first().scrollIntoViewIfNeeded();
      await page.screenshot({ path: info.outputPath(`nursing-${width}.png`), fullPage: true });
      await page.locator('.summary-card').last().click();
      await expect(page.locator('.overdue-toggle input')).toBeChecked();
      expect(writes).toEqual([]);
    });
  }

  test('nurse keeps card clinical launchers inside the card at mobile width', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 1080 });
    await prepare(page, 'ROLE_NURSE');
    await expect(page.locator('.oversight-note')).toHaveCount(0);
    await page.locator('.section-picker select').selectOption('workboard');
    await expect(page.locator('.workboard-card .action-record')).toHaveCount(7);
    await expect(page.locator('.workboard-card .action-intake')).toHaveCount(7);
    const overflow = await page
      .locator('.workboard-card')
      .evaluateAll((cards) => cards.some((card) => card.scrollWidth > card.clientWidth + 1));
    expect(overflow).toBe(false);
  });

  test('super-admin can inspect bedside eMAR without verification or recording controls', async ({
    page,
  }) => {
    const writes = await prepare(page, 'ROLE_SUPER_ADMIN');
    await page.route('**/api/nurse/medications/mar**', (route) =>
      route.fulfill({
        json: [
          {
            id: 'dose-review',
            patientId: rows[0].patientId,
            patientName: rows[0].patientName,
            medication: 'Review medication',
            dose: '1 tablet',
            route: 'PO',
            status: 'DUE',
            dueTime: '2026-11-18T08:00:00Z',
            doseHistory: [],
          },
        ],
      }),
    );
    await page.goto(`/emar?hospitalId=${HOSPITAL}`, { waitUntil: 'domcontentloaded' });
    await page.getByTestId('emar-task-dose-review').click();
    await expect(page.locator('.oversight-note')).toBeVisible();
    await expect(page.getByTestId('emar-verify')).toHaveCount(0);
    await expect(page.locator('.emar__actions')).toHaveCount(0);
    expect(writes).toEqual([]);
  });
});
