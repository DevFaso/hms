import AxeBuilder from '@axe-core/playwright';
import process from 'node:process';
import path from 'node:path';
import type { Page } from '@playwright/test';

import { test, expect } from './fixtures/test-fixtures';
import { routedPages } from './helpers/routed-page-inventory';
import { installLocalIconFonts } from './helpers/local-icon-fonts';
import { signInAs, TEST_ID } from './helpers/role-session';
import { auditAtWidths, formatOffenders, heightFor, LAYOUT_WIDTHS } from './helpers/layout-audit';

/**
 * QA D2230 / D2231 (owner screenshots 39–41, 2026-11-18): the invoice drawer
 * cut off its payment actions and line totals, and the Billing page's toolbar,
 * KPI amounts and invoice table spilled at an ordinary laptop width. No QA
 * round reported it because nothing measured the layout.
 *
 * This guard measures it. Every routed page (from the same route inventory the
 * a11y scan uses), then the main drawers and modals opened over seeded data,
 * each at 1366, 1024, 768 and 390 px. It fails on a sideways-scrolling page, a
 * button/link/field cut off inside a drawer, modal, panel or card, or text that
 * spills out of its box — see helpers/layout-audit.ts for the exact rules.
 * Offenders are reported as `[kind] route @width: selector — detail`.
 *
 * Runs in the a11y-smoke CI job (`npm run e2e:layout`), against the mocked
 * backend in fixtures/test-fixtures.ts; no live server.
 */

const PUBLIC = new Set([
  '/login',
  '/mfa-challenge',
  '/mfa-enroll',
  '/reset-password',
  '/verify',
  '/privacy-policy',
  '/onboarding/role-welcome',
]);

function pickRole(roles: string[]): string {
  return roles.includes('ROLE_HOSPITAL_ADMIN')
    ? 'ROLE_HOSPITAL_ADMIN'
    : (roles[0] ?? 'ROLE_HOSPITAL_ADMIN');
}

/** A role the route admits (the same pick the routed-page sweep makes). */
function roleFor(route: string): string {
  const screen = routedPages().find((p) => p.path === route);
  return pickRole(screen?.roles ?? []);
}

async function open(page: Page, baseURL: string, role: string | null, path: string) {
  // Sign in first: its off-origin block must be registered BEFORE the icon-font
  // route, which then wins (last registered first) and serves real glyphs. With
  // the order reversed the icons render as their ligature names
  // (« receipt_long ») and every width is measured wrong.
  if (role) await signInAs(page, role, baseURL);
  await installLocalIconFonts(page);
  await page.goto(path, { waitUntil: 'domcontentloaded' });
  await expect(page.locator('h1, h2, [role="heading"]').first()).toBeVisible();
  await page.evaluate(() => document.fonts.ready.then(() => undefined));
}

test.describe('layout overflow: routed pages', () => {
  test.use({ storageState: { cookies: [], origins: [] } });

  for (const screen of routedPages()) {
    test(`no overflow: ${screen.path}`, async ({ page }, testInfo) => {
      const pathname = screen.path.replace(/:[^/]+/g, TEST_ID);
      const role = PUBLIC.has(screen.path) ? null : pickRole(screen.roles);
      await open(page, testInfo.project.use.baseURL as string, role, pathname);
      const found = await auditAtWidths(page, pathname);
      expect(found, formatOffenders(found)).toEqual([]);
    });
  }
});

/* ── Seeded data for the drawers ─────────────────────────────────────────── */

const INVOICE_ID = '00000000-0000-0000-0000-0000000a1001';
const LONG_NAME = 'Évelyne Démo-Ouédraogo';
const HOSPITAL = 'Centre Hospitalier Wend-Panga';

function invoice(n: number, extra: Record<string, unknown> = {}) {
  return {
    id: n === 1 ? INVOICE_ID : `00000000-0000-0000-0000-0000000a10${10 + n}`,
    patientId: TEST_ID,
    patientFullName: n % 2 ? LONG_NAME : 'Kadidia Demo-Caisse',
    patientName: LONG_NAME,
    patientEmail: 'evelyne.demo.ouedraogo@example.invalid',
    patientPhone: '+22670000000',
    hospitalName: HOSPITAL,
    hospitalCode: 'CHWP',
    hospitalAddress: 'Ouagadougou',
    encounterCode: 'ENC-20261114-E8AC4F',
    encounterType: 'CONSULTATION',
    encounterStatus: 'COMPLETED',
    encounterDate: '2026-11-14',
    encounterTime: '09:00',
    createdByName: 'Caisse Demo',
    invoiceNumber: `CHWF2396-2026-00000${n}`,
    invoiceDate: '2026-11-14',
    dueDate: '2026-12-07',
    totalAmount: 1_500,
    amountPaid: 2_000,
    balanceDue: 0,
    refundDue: 500,
    insuranceCoverageAmount: 0,
    patientResponsibilityAmount: 1_500,
    status: 'PAID',
    notes: '',
    createdAt: '2026-11-14T09:00:00Z',
    updatedAt: '2026-11-14T09:00:00Z',
    ...extra,
  };
}

async function seedBilling(page: Page) {
  const rows = [1, 2, 3, 4, 5, 6].map((n) => invoice(n));
  await page.route('**/api/billing-invoices/search**', (r) =>
    r.fulfill({ json: { content: rows, totalElements: 46, totalPages: 8, number: 0, size: 20 } }),
  );
  await page.route('**/api/billing-invoices/summary**', (r) =>
    r.fulfill({
      json: {
        total: 46,
        paid: 6,
        outstanding: 4,
        overdue: 0,
        totalRevenue: 13_550,
        balance: 9_900,
      },
    }),
  );
  await page.route(`**/api/billing-invoices/${INVOICE_ID}`, (r) => r.fulfill({ json: rows[0] }));
  await page.route(`**/api/invoice-items/invoice/${INVOICE_ID}**`, (r) =>
    r.fulfill({
      json: [
        {
          id: 'it-1',
          billingInvoiceId: INVOICE_ID,
          itemDescription: 'Consultation générale (médecin généraliste)',
          itemCategory: 'CONSULTATION',
          quantity: 1,
          unitPrice: 300,
          totalPrice: 300,
          priceSource: 'OVERRIDE',
          tariffUnitPrice: 1_800,
          overrideReason: 'Tarif essai demo-r61',
        },
      ],
    }),
  );
  await page.route(`**/api/billing-invoices/${INVOICE_ID}/payments**`, (r) =>
    r.fulfill({
      json: [
        {
          id: 'pay-1',
          amount: 1_500,
          paymentDate: '2026-11-14',
          paymentMethod: 'MOBILE_MONEY',
          referenceNumber: 'OM-R53RF-782086',
          status: 'CONFIRMED',
        },
        {
          id: 'pay-2',
          amount: 300,
          paymentDate: '2026-11-18',
          paymentMethod: 'CASH',
          status: 'PENDING',
        },
      ],
    }),
  );
  await page.route(`**/api/billing-invoices/${INVOICE_ID}/adjustments**`, (r) =>
    r.fulfill({ json: [] }),
  );
  await page.route(`**/api/billing-invoices/${INVOICE_ID}/refunds**`, (r) =>
    r.fulfill({ json: [] }),
  );
}

/**
 * LAYOUT_SHOTS=<dir> [LAYOUT_SHOTS_TAG=before|after]: save the PR's evidence screenshots.
 * LAYOUT_SHOTS_FOCUS=<selector> (r65): scroll that element to the top first. The shell
 * scrolls inside `.content`, so a plain shot shows the first screen only; `table` shows
 * each list's table instead (its cards on a phone, its sticky last column on a tablet).
 */
async function shoot(
  page: Page,
  name: string,
  widths: readonly number[] = [1366, 390],
  focus: string | undefined = process.env['LAYOUT_SHOTS_FOCUS'],
) {
  const dir = process.env['LAYOUT_SHOTS'];
  if (!dir) return;
  const tag = process.env['LAYOUT_SHOTS_TAG'] ?? 'shot';
  for (const width of widths) {
    await page.setViewportSize({ width, height: heightFor(width) });
    await page.evaluate(() => {
      const backdrop = document.querySelector<HTMLElement>('.sidebar-backdrop');
      if (backdrop && getComputedStyle(backdrop).display !== 'none') backdrop.click();
    });
    if (focus && (await page.locator(focus).count()) > 0) {
      await page
        .locator(focus)
        .first()
        .evaluate((el) => el.scrollIntoView({ block: 'start' }));
    }
    await page.waitForTimeout(400);
    await page.screenshot({ path: path.join(dir, `${name}-${width}-${tag}.png`) });
  }
}

test.describe('layout overflow: drawers and modals', () => {
  test.use({ storageState: { cookies: [], origins: [] } });

  test('billing page and invoice detail drawer (D2230 / D2231)', async ({ page }, testInfo) => {
    await seedBilling(page);
    await open(page, testInfo.project.use.baseURL as string, 'ROLE_HOSPITAL_ADMIN', '/billing');
    await expect(page.locator('.data-row').first()).toBeVisible();
    const found = await auditAtWidths(page, '/billing');
    // D2231: at a laptop width the invoice list fits without its own sideways scrollbar.
    await page.setViewportSize({ width: 1366, height: 900 });
    const table = page.locator('.data-table');
    const fits = await table.evaluate((el) => el.scrollWidth <= el.clientWidth + 1);
    expect.soft(fits, 'the invoice table scrolls sideways at 1366px').toBe(true);
    await shoot(page, 'billing', [1366, 1024, 390]);

    await page.setViewportSize({ width: 1366, height: 900 });
    await page.locator('.view-link').first().click();
    const drawer = page.locator('.detail-panel');
    await expect(drawer.getByText('OM-R53RF-782086')).toBeVisible();
    await expect(drawer.getByText('Tarif essai demo-r61', { exact: false })).toBeVisible();
    found.push(...(await auditAtWidths(page, '/billing invoice drawer')));
    await drawer.locator('.payments-section').scrollIntoViewIfNeeded();
    await shoot(page, 'invoice-drawer', [1366, 1024, 390]);
    expect(found, formatOffenders(found)).toEqual([]);
  });
});

/* ── Clinical drawers: one long, realistic record each ────────────────────── */

const RECORD_ID = '00000000-0000-0000-0000-0000000b2001';
const NOW = '2026-11-14T09:30:00Z';
const LONG_NOTE =
  'Douleur thoracique atypique depuis trois jours, irradiant vers le bras gauche, sans dyspnée';

/** Fields shared by the order / result / prescription rows of the clinical pages. */
const COMMON = {
  id: RECORD_ID,
  patientId: TEST_ID,
  patientFullName: LONG_NAME,
  patientDisplayName: LONG_NAME,
  patientName: LONG_NAME,
  patientEmail: 'evelyne.demo.ouedraogo@example.invalid',
  patientMrn: 'MRN-CHWP-2026-000123',
  hospitalId: TEST_ID,
  hospitalName: HOSPITAL,
  createdAt: NOW,
  updatedAt: NOW,
  notes: LONG_NOTE,
};

const IMAGING_ORDER = {
  ...COMMON,
  modality: 'CT',
  studyType: 'Scanner thoracique avec injection de produit de contraste',
  bodyRegion: 'Thorax',
  laterality: '',
  priority: 'URGENT',
  status: 'ORDERED',
  clinicalQuestion: LONG_NOTE,
  orderingProviderId: TEST_ID,
  orderingProviderName: 'Dr Aminata Sawadogo-Kaboré',
  orderedAt: NOW,
  scheduledDate: '2026-11-15',
  scheduledTime: '10:00',
  completedAt: null,
};

const LAB_ORDER = {
  ...COMMON,
  labOrderCode: 'LAB-20261114-8F3A21C9',
  labTestName: 'Numération formule sanguine complète (NFS) avec plaquettes',
  labTestCode: 'NFS-PLQ-2026',
  orderingStaffName: 'Dr Aminata Sawadogo-Kaboré',
  orderDatetime: NOW,
  status: 'COMPLETED',
  clinicalIndication: LONG_NOTE,
  medicalNecessityNote: LONG_NOTE,
  primaryDiagnosisCode: 'R07.4',
  additionalDiagnosisCodes: [],
  priority: 'ROUTINE',
  orderChannel: 'ELECTRONIC',
};

const LAB_RESULT = {
  ...COMMON,
  labOrderId: RECORD_ID,
  labOrderCode: LAB_ORDER.labOrderCode,
  labTestName: LAB_ORDER.labTestName,
  labTestCode: LAB_ORDER.labTestCode,
  resultValue: '13.4',
  resultUnit: 'g/dL',
  resultDate: NOW,
  referenceRanges: [],
  trendHistory: [],
  severityFlag: 'NORMAL',
  acknowledged: false,
  released: true,
};

const PRESCRIPTION = {
  ...COMMON,
  staffId: TEST_ID,
  staffFullName: 'Dr Aminata Sawadogo-Kaboré',
  encounterId: RECORD_ID,
  medicationName: 'Amoxicilline + acide clavulanique 1 g/125 mg comprimé pelliculé',
  medicationDisplayName: 'Amoxicilline + acide clavulanique 1 g/125 mg comprimé pelliculé',
  dosage: '1 comprimé',
  frequency: 'Toutes les 8 heures',
  route: 'ORAL',
  duration: '7 jours',
  quantity: 21,
  quantityUnit: 'comprimés',
  status: 'SIGNED',
};

const page1 = (rows: unknown[]) => ({
  content: rows,
  totalElements: rows.length,
  totalPages: 1,
  number: 0,
  size: 20,
  first: true,
  last: true,
});

interface DrawerCase {
  route: string;
  seed: (page: Page) => Promise<void>;
  /** The first row's "view details" control. */
  open: string;
  drawer: string;
}

const DRAWERS: DrawerCase[] = [
  {
    route: '/imaging',
    seed: async (page) => {
      await page.route('**/api/imaging/orders**', (r) =>
        r.request().url().includes(RECORD_ID)
          ? r.fulfill({ json: IMAGING_ORDER })
          : r.fulfill({ json: [IMAGING_ORDER] }),
      );
      await page.route('**/api/imaging/results/**', (r) => r.fulfill({ json: [] }));
    },
    open: '.action-group .action-link >> nth=0',
    drawer: '.detail-panel',
  },
  {
    route: '/lab',
    seed: async (page) => {
      await page.route('**/api/lab-orders**', (r) =>
        r.request().url().includes(RECORD_ID)
          ? r.fulfill({ json: { success: true, data: LAB_ORDER } })
          : r.fulfill({ json: { success: true, data: page1([LAB_ORDER]) } }),
      );
      await page.route(`**/api/lab-orders/${RECORD_ID}/**`, (r) =>
        r.fulfill({ json: { success: true, data: [] } }),
      );
    },
    open: '.view-link >> nth=0',
    drawer: '.detail-panel',
  },
  {
    route: '/lab-results',
    seed: async (page) => {
      await page.route('**/api/lab-results**', (r) =>
        r.request().url().includes(RECORD_ID)
          ? r.fulfill({ json: { success: true, data: LAB_RESULT } })
          : r.fulfill({ json: { success: true, data: page1([LAB_RESULT]) } }),
      );
    },
    open: '.view-link >> nth=0',
    drawer: '.detail-panel',
  },
  {
    route: '/prescriptions',
    seed: async (page) => {
      await page.route('**/api/prescriptions**', (r) =>
        r.request().url().includes(RECORD_ID)
          ? r.fulfill({ json: PRESCRIPTION })
          : r.fulfill({ json: page1([PRESCRIPTION]) }),
      );
    },
    open: '.view-link >> nth=0',
    drawer: '.detail-panel',
  },
];

/*
 * QA D2644 (r65): « Latéralité » shared its row with the full-width « Question
 * clinique » and was squeezed to an empty 60 px select (0 px at 390, its label
 * one letter per line). The select keeps a readable width at every width.
 */
test.describe('layout: the new imaging order form (D2644)', () => {
  test.use({ storageState: { cookies: [], origins: [] } });

  test('« Latéralité » stays readable at 1366, 1024, 768 and 390', async ({ page }, testInfo) => {
    await page.route('**/api/imaging/orders**', (r) => r.fulfill({ json: [IMAGING_ORDER] }));
    await page.route('**/api/imaging/results/**', (r) => r.fulfill({ json: [] }));
    await open(page, testInfo.project.use.baseURL as string, 'ROLE_DOCTOR', '/imaging');
    await page
      .locator('button.btn-primary')
      .filter({ hasText: /Nouvelle demande|New (imaging )?order/i })
      .first()
      .click();
    const select = page.locator('#img-laterality');
    await expect(select).toBeVisible();
    for (const width of LAYOUT_WIDTHS) {
      await page.setViewportSize({ width, height: heightFor(width) });
      const field = await select.boundingBox();
      const label = await page.locator('label[for="img-laterality"]').boundingBox();
      expect(field?.width ?? 0, `select width @${width}`).toBeGreaterThanOrEqual(150);
      expect(label?.height ?? 99, `label on one line @${width}`).toBeLessThan(32);
    }
    await shoot(page, 'imaging-new-order', LAYOUT_WIDTHS);
  });
});

test.describe('layout overflow: clinical drawers', () => {
  test.use({ storageState: { cookies: [], origins: [] } });

  for (const c of DRAWERS) {
    test(`drawer fits: ${c.route}`, async ({ page }, testInfo) => {
      await c.seed(page);
      await open(page, testInfo.project.use.baseURL as string, roleFor(c.route), c.route);
      await page.locator(c.open).click();
      await expect(page.locator(c.drawer)).toBeVisible();
      const found = await auditAtWidths(page, `${c.route} drawer`);
      await shoot(page, `drawer-${c.route.replace(/\W+/g, '')}`, [1366, 1024]);
      expect(found, formatOffenders(found)).toEqual([]);
    });
  }
});

const PATIENT = {
  id: TEST_ID,
  firstName: 'Évelyne',
  lastName: 'Démo-Ouédraogo Kaboré-Sawadogo',
  dateOfBirth: '1988-03-14',
  gender: 'FEMALE',
  mrn: 'MRN-CHWP-2026-000123',
  address: 'Secteur 15, rue 15.234, porte 112, Ouaga 2000',
  city: 'Ouagadougou',
  country: 'Burkina Faso',
  phoneNumberPrimary: '+226 70 00 00 00',
  email: 'evelyne.demo.ouedraogo@example.invalid',
  emergencyContactName: 'Boureima Démo-Ouédraogo',
  emergencyContactPhone: '+226 76 00 00 00',
  emergencyContactRelationship: 'Conjoint',
  bloodType: 'O+',
  allergies: 'Pénicilline (urticaire généralisée), arachide',
  hospitalId: TEST_ID,
  hospitalName: HOSPITAL,
  active: true,
};

test.describe('layout overflow: patient record panels', () => {
  test.use({ storageState: { cookies: [], origins: [] } });

  test('every record section and tab fits', async ({ page }, testInfo) => {
    test.setTimeout(120_000);
    await page.route(`**/api/patients/${TEST_ID}`, (r) => r.fulfill({ json: PATIENT }));
    await page.route(`**/api/patients/${TEST_ID}?**`, (r) => r.fulfill({ json: PATIENT }));
    const path = `/patients/${TEST_ID}`;
    await open(page, testInfo.project.use.baseURL as string, 'ROLE_DOCTOR', path);
    await expect(page.locator('.section-tab').first()).toBeVisible();
    const found: Awaited<ReturnType<typeof auditAtWidths>> = [];
    const sections = await page.locator('.section-tab').count();
    expect(sections, 'the record shows its sections').toBeGreaterThan(1);
    for (let s = 0; s < sections; s++) {
      await page.setViewportSize({ width: 1366, height: 900 });
      await page.locator('.section-tab').nth(s).click();
      const tabs = Math.max(1, await page.locator('.sub-tabs .tab').count());
      for (let t = 0; t < tabs; t++) {
        await page.setViewportSize({ width: 1366, height: 900 });
        const tab = page.locator('.sub-tabs .tab').nth(t);
        if (await tab.count()) await tab.click();
        const name = await page
          .locator('.sub-tabs .tab.active, .section-tab.active')
          .last()
          .innerText()
          .catch(() => `section ${s}`);
        found.push(...(await auditAtWidths(page, `${path} [${name.trim()}]`)));
      }
    }
    expect(found, formatOffenders(found)).toEqual([]);
  });
});

/* ── Admin tables with rows (QA D2321, r63) ──────────────────────────────────
   An empty list has no row actions to lose; these pages get three long rows so
   the row-action rule (helpers/layout-audit.ts) has something to measure. */

const ROW = {
  id: RECORD_ID,
  name: 'Centre Hospitalier Universitaire Wend-Panga de Ouagadougou',
  code: 'CHU-WP-OUAGA-2026',
  firstName: 'Aminata',
  lastName: 'Sawadogo-Kaboré',
  fullName: 'Aminata Sawadogo-Kaboré',
  username: 'aminata.sawadogo.kabore',
  email: 'aminata.sawadogo.kabore@wend-panga.example.invalid',
  phoneNumber: '+226 70 00 00 00',
  roles: ['ROLE_HOSPITAL_ADMIN', 'ROLE_BILLING_SPECIALIST'],
  roleName: 'ROLE_HOSPITAL_ADMIN',
  authority: 'ROLE_HOSPITAL_ADMIN',
  description: 'Administration de l’établissement, facturation et caisse',
  permissions: [],
  active: true,
  status: 'ACTIVE',
  type: 'HOSPITAL',
  city: 'Ouagadougou',
  address: 'Secteur 15, rue 15.234, Ouaga 2000',
  country: 'Burkina Faso',
  organizationName: 'Réseau de santé Wend-Panga',
  hospitalId: TEST_ID,
  hospitalName: HOSPITAL,
  departmentName: 'Médecine interne et maladies infectieuses',
  jobTitle: 'Médecin-chef de service',
  licenseNumber: 'CNOM-BF-2026-000123',
  eventType: 'DATA_CREATED',
  userName: 'aminata.sawadogo.kabore',
  resourceName: 'Facture CHWF2396-2026-000046',
  entityType: 'BILLING_INVOICE',
  ipAddress: '2001:db8:a9af:12::1',
  eventTimestamp: NOW,
  createdAt: NOW,
  updatedAt: NOW,
};
const ROWS = [1, 2, 3].map((n) => ({ ...ROW, id: `${RECORD_ID.slice(0, -1)}${n}` }));

const ADMIN_TABLES: { route: string; seed: (page: Page) => Promise<void> }[] = [
  {
    route: '/users',
    seed: (p) => p.route('**/api/users**', (r) => r.fulfill({ json: page1(ROWS) })),
  },
  {
    route: '/staff',
    seed: async (p) => {
      await p.route('**/api/staff**', (r) => r.fulfill({ json: ROWS }));
      await p.route('**/api/staff/hospital/**', (r) => r.fulfill({ json: page1(ROWS) }));
    },
  },
  {
    route: '/hospitals',
    seed: (p) => p.route('**/api/hospitals**', (r) => r.fulfill({ json: ROWS })),
  },
  {
    route: '/organizations',
    seed: (p) => p.route('**/api/organizations**', (r) => r.fulfill({ json: page1(ROWS) })),
  },
  {
    route: '/roles',
    seed: (p) => p.route('**/api/roles**', (r) => r.fulfill({ json: ROWS })),
  },
  {
    route: '/departments',
    seed: async (p) => {
      await p.route('**/api/hospitals**', (r) => r.fulfill({ json: [ROW] }));
      await p.route('**/api/departments**', (r) => r.fulfill({ json: page1(ROWS) }));
    },
  },
  {
    route: '/audit-logs',
    seed: (p) => p.route('**/api/audit-logs?**', (r) => r.fulfill({ json: page1(ROWS) })),
  },
];

test.describe('layout overflow: admin tables with rows (D2320 / D2321)', () => {
  test.use({ storageState: { cookies: [], origins: [] } });

  for (const c of ADMIN_TABLES) {
    test(`rows and actions fit: ${c.route}`, async ({ page }, testInfo) => {
      await c.seed(page);
      await open(page, testInfo.project.use.baseURL as string, roleFor(c.route), c.route);
      await expect(
        page
          .locator('tbody tr, [class*="card"]')
          .filter({ hasText: /sawadogo|wend-panga|CHU-WP/i })
          .first(),
      ).toBeVisible();
      const found = await auditAtWidths(page, c.route);
      await shoot(page, `table-${c.route.replace(/\W+/g, '')}`, [1024, 390]);
      expect(found, formatOffenders(found)).toEqual([]);
    });
  }
});

/* ── Front desk and ward lists with rows (QA D2241 / D2242 / D2246, r63) ──── */

const DESK = {
  ...ROW,
  id: RECORD_ID,
  patientId: TEST_ID,
  patientName: LONG_NAME,
  patientFullName: LONG_NAME,
  mrn: 'MRN-CHWP-2026-000123',
  dateOfBirth: '1988-03-14',
  gender: 'FEMALE',
  appointmentId: RECORD_ID,
  encounterId: RECORD_ID,
  appointmentDate: '2026-11-19',
  appointmentTime: '09:30',
  startTime: '09:30',
  endTime: '10:00',
  providerName: 'Dr Aminata Sawadogo-Kaboré',
  staffName: 'Dr Aminata Sawadogo-Kaboré',
  departmentName: 'Médecine interne et maladies infectieuses',
  appointmentReason: 'Première consultation de médecine générale',
  reason: 'Première consultation de médecine générale',
  status: 'SCHEDULED',
  priority: 'ROUTINE',
  waitMinutes: 42,
  hasInsuranceIssue: true,
  hasOutstandingBalance: true,
  registrationDate: NOW,
  requestedDateFrom: '2026-11-20',
  requestedDateTo: '2026-11-27',
  preferredProviderName: 'Dr Aminata Sawadogo-Kaboré',
  dueAt: NOW,
  roomBed: 'Médecine B — chambre 12, lit 2',
};
const DESK_ROWS = [1, 2, 3].map((n) => ({ ...DESK, id: `${RECORD_ID.slice(0, -1)}${n}` }));

const FRONT_DESK: {
  route: string;
  role: string;
  seed: (page: Page) => Promise<void>;
  then?: (page: Page) => Promise<void>;
  label?: string;
  /** false: a summary page with no seeded rows to wait for. */
  rows?: boolean;
}[] = [
  {
    route: '/patients',
    role: 'ROLE_RECEPTIONIST',
    seed: async (p) => {
      await p.route('**/api/patients', (r) => r.fulfill({ json: DESK_ROWS }));
      await p.route('**/api/patients?**', (r) => r.fulfill({ json: DESK_ROWS }));
    },
  },
  {
    route: '/appointments',
    role: 'ROLE_RECEPTIONIST',
    seed: (p) => p.route('**/api/appointments**', (r) => r.fulfill({ json: DESK_ROWS })),
  },
  {
    route: '/registrations',
    role: 'ROLE_RECEPTIONIST',
    seed: (p) => p.route('**/api/registrations**', (r) => r.fulfill({ json: DESK_ROWS })),
  },
  {
    route: '/nurse-station',
    role: 'ROLE_NURSE',
    seed: (p) => p.route('**/api/nurse/vitals/due**', (r) => r.fulfill({ json: DESK_ROWS })),
  },
  {
    route: '/reception',
    role: 'ROLE_RECEPTIONIST',
    seed: async (p) => {
      await p.route('**/api/reception/queue**', (r) => r.fulfill({ json: DESK_ROWS }));
      await p.route('**/api/reception/payments/pending**', (r) => r.fulfill({ json: DESK_ROWS }));
      await p.route('**/api/reception/waitlist**', (r) =>
        r.fulfill({ json: DESK_ROWS.map((w) => ({ ...w, status: 'WAITING' })) }),
      );
    },
  },
  {
    route: '/reception',
    label: '/reception [waitlist]',
    role: 'ROLE_RECEPTIONIST',
    seed: (p) =>
      p.route('**/api/reception/waitlist**', (r) =>
        r.fulfill({ json: DESK_ROWS.map((w) => ({ ...w, status: 'WAITING' })) }),
      ),
    then: async (p) => {
      await p
        .getByRole('button', { name: /Liste d'attente|Waitlist/i })
        .first()
        .click();
      await expect(p.getByText(/sawadogo/i).first()).toBeVisible();
    },
  },
  {
    route: '/dashboard',
    label: '/dashboard [receptionist]',
    role: 'ROLE_RECEPTIONIST',
    rows: false,
    seed: async (p) => {
      await p.route('**/api/reception/queue**', (r) => r.fulfill({ json: DESK_ROWS }));
      await p.route('**/api/reception/dashboard/summary**', (r) =>
        r.fulfill({
          json: {
            date: '2026-11-19',
            hospitalId: TEST_ID,
            scheduledToday: 1248,
            arrivedCount: 1036,
            waitingCount: 357,
            inProgressCount: 214,
            noShowCount: 128,
            completedCount: 1009,
            walkInCount: 486,
          },
        }),
      );
    },
  },
  /* Ward (QA D2280–D2288, r63): the tablet at 768 is the main ward device. */
  {
    route: '/nurse-station',
    label: '/nurse-station [workboard]',
    role: 'ROLE_NURSE',
    seed: (p) => p.route('**/api/nurse/workboard**', (r) => r.fulfill({ json: DESK_ROWS })),
    then: async (p) => {
      await p
        .locator('[data-testid="section-workboard"], button')
        .filter({ hasText: /Tableau de travail|Workboard/i })
        .first()
        .click();
    },
  },
  {
    route: '/admissions',
    role: 'ROLE_NURSE',
    seed: async (p) => {
      await p.route('**/api/admissions', (r) => r.fulfill({ json: DESK_ROWS }));
      await p.route('**/api/admissions?**', (r) => r.fulfill({ json: DESK_ROWS }));
    },
  },
  {
    route: '/bed-board',
    role: 'ROLE_NURSE',
    rows: false,
    seed: (p) =>
      p.route('**/api/transfers/pending**', (r) =>
        r.fulfill({
          json: DESK_ROWS.map((t) => ({
            ...t,
            admissionId: RECORD_ID,
            fromBedLabel: 'MED1/M1-04',
            fromWardName: 'Médecine interne B — aile nord',
            toBedLabel: 'URGA/U-01',
            toWardName: 'Unité des urgences adultes et soins continus',
            transferType: 'INTRA_HOSPITAL',
            status: 'PENDING',
            reason: 'Surveillance rapprochée après dégradation hémodynamique nocturne',
            requestedByName: 'Inf. Salimata Ouédraogo-Compaoré',
            requestedAt: NOW,
          })),
        }),
      ),
  },
  {
    route: '/emar',
    label: '/emar [dose panel]',
    role: 'ROLE_NURSE',
    seed: (p) =>
      p.route('**/api/nurse/medications/mar**', (r) =>
        r.fulfill({
          json: DESK_ROWS.map((t) => ({
            ...t,
            medication: 'Amoxicilline + acide clavulanique 1 g/125 mg comprimé pelliculé',
            dose: '1 comprimé',
            route: 'ORAL',
            routeCode: 'PO',
            dueTime: NOW,
            status: 'PENDING',
          })),
        }),
      ),
    then: async (p) => {
      await p
        .getByText(/Amoxicilline/)
        .first()
        .click();
    },
  },
  /* Clinical lists (QA D2262 / D2301, r63): actions on screen at every width. */
  {
    route: '/pharmacy/dispensing',
    role: 'ROLE_PHARMACIST',
    rows: false,
    seed: async (p) => {
      await p.route('**/api/pharmacy-registry**', (r) => r.fulfill({ json: page1([ROW]) }));
      await p.route('**/api/pharmacy/dispense/work-queue**', (r) =>
        r.fulfill({
          json: {
            data: page1(
              DESK_ROWS.map((d) => ({
                ...d,
                prescriptionId: d.id,
                medicationName: 'Amoxicilline + acide clavulanique 1 g/125 mg comprimé pelliculé',
                dosage: '1 comprimé',
                frequency: 'Toutes les 8 heures',
                prescriberName: 'Dr Aminata Sawadogo-Kaboré',
                status: 'SIGNED',
              })),
            ),
          },
        }),
      );
    },
  },
  {
    route: '/discharge',
    role: 'ROLE_DOCTOR',
    rows: false,
    seed: (p) => p.route('**/api/discharge-summaries/**', (r) => r.fulfill({ json: DESK_ROWS })),
  },
  /* Patient portal (QA D2345–D2347, r63): the phone is the patient's device. */
  {
    route: '/my-family-access',
    label: '/my-family-access [grant form + tables]',
    role: 'ROLE_PATIENT',
    seed: async (p) => {
      const proxies = DESK_ROWS.map((d) => ({
        ...d,
        grantorPatientId: TEST_ID,
        grantorName: LONG_NAME,
        proxyUserId: TEST_ID,
        proxyUsername: 'boureima.demo.ouedraogo',
        proxyDisplayName: 'Boureima Démo-Ouédraogo',
        relationship: 'SPOUSE',
        status: 'ACTIVE',
        permissions: 'APPOINTMENTS,LAB_RESULTS,MEDICATIONS',
        expiresAt: '2027-11-19T00:00:00Z',
      }));
      await p.route('**/api/me/patient/proxies**', (r) => r.fulfill({ json: { data: proxies } }));
      await p.route('**/api/me/patient/proxy-access**', (r) =>
        r.fulfill({ json: { data: proxies } }),
      );
    },
    then: async (p) => {
      await p
        .locator('.btn-primary')
        .filter({ hasText: /Accorder|Grant/i })
        .first()
        .click();
    },
  },
  /* QA D2342 (r63): the patient's imaging reports, read on a phone. */
  {
    route: '/my-imaging',
    label: '/my-imaging [reports + addendum + images]',
    role: 'ROLE_PATIENT',
    seed: (p) =>
      p.route('**/api/me/patient/imaging', (r) =>
        r.fulfill({
          json: {
            data: [1, 2].map((n) => ({
              imagingOrderId: `${RECORD_ID.slice(0, -1)}${n}`,
              modality: 'XRAY',
              studyType: 'Radiographie thoracique de face et de profil, contrôle post-opératoire',
              bodyRegion: 'Thorax — champs pulmonaires et médiastin supérieur',
              laterality: 'BILATERAL',
              orderedAt: NOW,
              hospitalName: 'Centre hospitalier universitaire Yalgado Ouédraogo de Ouagadougou',
              reports: [
                {
                  id: `${RECORD_ID.slice(0, -1)}${n}`,
                  reportStatus: 'FINAL',
                  reportVersion: 1,
                  findings:
                    'Silhouette cardiaque de taille normale. Absence d’épanchement pleural. Parenchyme pulmonaire sans foyer de condensation systématisé.',
                  impression: 'Radiographie thoracique sans anomalie décelable.',
                  signedByName: 'Dr Aminata Sawadogo-Kaboré',
                  signedAt: NOW,
                },
                {
                  id: `${RECORD_ID.slice(0, -2)}9${n}`,
                  reportStatus: 'ADDENDUM',
                  reportVersion: 2,
                  findings: 'Comparaison avec le cliché du 12/11/2026 : aspect superposable.',
                  signedByName: 'Dr Aminata Sawadogo-Kaboré',
                  signedAt: NOW,
                },
              ],
              images: [1, 2, 3, 4, 5].map((i) => ({
                id: `${RECORD_ID.slice(0, -2)}${n}${i}`,
                contentType: i === 5 ? 'application/pdf' : 'image/png',
                sizeBytes: 1024,
                caption: i === 1 ? 'Incidence de face, inspiration profonde' : null,
              })),
            })),
          },
        }),
      ),
  },
];

test.describe('layout overflow: front desk and ward lists (D2241 / D2242 / D2246)', () => {
  test.use({ storageState: { cookies: [], origins: [] } });

  for (const c of FRONT_DESK) {
    test(`rows and actions fit: ${c.label ?? c.route}`, async ({ page }, testInfo) => {
      await c.seed(page);
      await open(page, testInfo.project.use.baseURL as string, c.role, c.route);
      if (c.then) await c.then(page);
      // The seeded rows are on screen (an empty list has no actions to lose).
      if (c.rows !== false) {
        await expect(page.getByText(/Démo-Ouédraogo|MRN-CHWP|sawadogo/i).first()).toBeVisible();
      }
      const found = await auditAtWidths(page, c.label ?? c.route);
      await shoot(page, `list-${(c.label ?? c.route).replace(/\W+/g, '-')}`, [1024, 768, 390]);
      expect(found, formatOffenders(found)).toEqual([]);
    });
  }
});

/* ── r64 (QA D2461 / D2431–D2436 / D2400): dialogs and money screens ────────
   A dialog is never wider than the screen; the money screens' tables fit
   their card or scroll in place with a cue; the patient's insured invoice card
   keeps its text readable on a phone. Each case opens the screen QA failed. */

const PAYER_PLAN_ID = `${RECORD_ID.slice(0, -1)}7`;
const PAYER = {
  id: RECORD_ID,
  hospitalId: TEST_ID,
  code: 'MUT-TEST-A-R64',
  name: 'Mutuelle des agents de santé du Kadiogo, régime complémentaire',
  payerType: 'MUTUELLE',
  active: true,
  plans: [
    {
      id: PAYER_PLAN_ID,
      payerId: RECORD_ID,
      code: 'FORMULE-ESSENTIELLE-2026',
      name: 'Formule essentielle hospitalisation et consultations 2026',
      defaultCoveragePercent: 80,
      perInvoiceCeiling: 250_000,
      annualCeilingPerBeneficiary: 1_500_000,
      effectiveFrom: '2026-01-01',
      status: 'IN_FORCE',
      linkable: true,
      terms: [1, 2].map((n) => ({
        id: `terms-${n}`,
        defaultCoveragePercent: 70 + n * 5,
        perInvoiceCeiling: 250_000,
        annualCeilingPerBeneficiary: 1_500_000,
        effectiveFrom: `202${4 + n}-01-01`,
        effectiveTo: n === 1 ? '2025-12-31' : null,
        status: n === 1 ? 'ENDED' : 'IN_FORCE',
        changeReason: 'Avenant numéro 3 à la convention signé avec le conseil',
        createdByName: 'Aminata Sawadogo-Kaboré',
      })),
      rules: [1, 2].map((n) => ({
        id: `rule-${n}`,
        planId: PAYER_PLAN_ID,
        category: 'LABORATORY',
        priceListItemCode: `NFS-HEMOGRAMME-COMPLET-${n}`,
        priceListItemLabel: 'Numération formule sanguine et plaquettes (hémogramme complet)',
        coveragePercent: 60,
        excluded: false,
        perActCeiling: 2_000,
        effectiveFrom: '2026-01-01',
        status: 'IN_FORCE',
        endReason: n === 2 ? 'Remplacée par la règle de la convention 2027' : null,
      })),
    },
  ],
};

/** Billing plan PR 10: one cashier's till with long names and large amounts. */
const CASH_SESSION = {
  id: 'cs-1',
  till: 'BILLING',
  cashierId: 'u-1',
  cashierName: 'Aminata Sawadogo-Kaboré',
  businessDate: '2026-11-19',
  status: 'OPEN',
  closeRound: 0,
  mine: true,
  methods: [
    {
      method: 'CASH',
      operator: null,
      inCount: 12,
      inAmount: 1_245_500,
      outCount: 1,
      outAmount: 50,
      expected: 1_245_450,
    },
    {
      method: 'MOBILE_MONEY',
      operator: 'ORANGE_MONEY',
      inCount: 7,
      inAmount: 386_000,
      outCount: 0,
      outAmount: 0,
      expected: 386_000,
    },
  ],
  totalIn: 1_631_500,
  totalOut: 50,
  totalExpected: 1_631_450,
  cashToCount: 1_245_450,
  totalDifference: null,
  counts: [],
  movements: [1, 2, 3].map((n) => ({
    id: `m-${n}`,
    kind: 'PAYMENT',
    direction: 'IN',
    at: NOW,
    document: `CHWF2396-2026-00004${n}`,
    patientName: LONG_NAME,
    method: 'MOBILE_MONEY',
    operator: 'ORANGE_MONEY',
    reference: 'OM-R53RF-782086-2026',
    amount: 1_245_500,
    status: 'CONFIRMED',
  })),
};

const DIALOGS: {
  label: string;
  route: string;
  role: string;
  seed?: (page: Page) => Promise<unknown>;
  then: (page: Page) => Promise<void>;
  /** The part of the screen the PR's evidence shot scrolls to. */
  focus?: string;
}[] = [
  {
    label: '/referrals [Nouvelle orientation] (D2461)',
    route: '/referrals',
    role: 'ROLE_DOCTOR',
    then: async (p) => {
      await p
        .locator('.btn-primary')
        .filter({ hasText: /Nouvelle orientation|New referral/i })
        .first()
        .click();
      await expect(p.locator('[role="dialog"]').first()).toBeVisible();
    },
  },
  {
    // Billing plan PR 10 (D2325, D2714): the counted close replaced the read-only closing (D2432).
    label: '/billing [Clôture de caisse] (D2432)',
    route: '/billing',
    focus: '[data-testid="cash-session-methods-BILLING"]',
    role: 'ROLE_BILLING_SPECIALIST',
    seed: (p) => p.route('**/api/cash-sessions/mine**', (r) => r.fulfill({ json: [CASH_SESSION] })),
    then: async (p) => {
      await p.locator('[data-testid="cash-closing-toggle"]').click();
      await expect(p.locator('[data-testid="cash-to-count"]')).toBeVisible();
    },
  },
  {
    label: '/billing/caisses [Caisses du jour] (D2326)',
    route: '/billing/caisses',
    focus: '[data-testid="cash-supervisor-table"]',
    role: 'ROLE_HOSPITAL_ADMIN',
    seed: async (p) => {
      await p.route('**/api/cash-sessions?**', (r) =>
        r.fulfill({
          json: [
            {
              ...CASH_SESSION,
              mine: false,
              status: 'CLOSED',
              closeRound: 1,
              // PR 10 review: a shortage and a surplus that net to zero are flagged under the cash difference.
              totalDifference: 0,
              cashDifference: -125_500,
              differenceLines: 2,
            },
          ],
        }),
      );
      // PR 10 review (QA D2703): the lines removed from invoices that day, long line, reason and name.
      await p.route('**/api/cash-sessions/removed-lines**', (r) =>
        r.fulfill({
          json: [
            {
              id: 'removed-1',
              invoiceNumber: 'TWP-2026-000040',
              line: 'Radiographie du thorax de face et de profil avec interprétation du radiologue',
              quantity: 1,
              unitPrice: 1_250_000,
              reason:
                'Acte non réalisé : le patient est reparti avant la radiographie, la ligne est retirée avant émission',
              removedByName: 'Aminata Sawadogo-Kaboré',
              removedAt: '2026-11-20T09:30:00',
            },
          ],
        }),
      );
    },
    then: async (p) => {
      await expect(p.locator('[data-testid="cash-supervisor-table"]')).toBeVisible();
      await expect(p.locator('[data-testid="cash-removed-lines"]')).toBeVisible();
    },
  },
  {
    label: '/billing/organismes [payer detail] (D2436)',
    route: '/billing/organismes',
    focus: '[data-testid="payers-terms-FORMULE-ESSENTIELLE-2026"]',
    role: 'ROLE_HOSPITAL_ADMIN',
    seed: (p) =>
      p.route('**/api/billing/payers**', (r) =>
        r.fulfill({ json: r.request().url().includes(RECORD_ID) ? PAYER : [PAYER] }),
      ),
    then: async (p) => {
      await p.locator('[data-testid="payers-row-MUT-TEST-A-R64"] .link-button').click();
      await expect(
        p.locator('[data-testid="payers-terms-FORMULE-ESSENTIELLE-2026"]'),
      ).toBeVisible();
    },
  },
  {
    label: '/my-billing [insured invoice] (D2431)',
    route: '/my-billing',
    role: 'ROLE_PATIENT',
    seed: (p) =>
      p.route('**/api/me/patient/billing/invoices**', (r) =>
        r.fulfill({
          json: {
            data: page1(
              [1, 2].map((n) =>
                invoice(n, {
                  status: 'PARTIALLY_PAID',
                  totalAmount: 3_300,
                  amountPaid: 0,
                  balanceDue: 330,
                  refundDue: 0,
                  insuranceCoverageAmount: 2_970,
                  patientResponsibilityAmount: 330,
                  encounterDate: '2026-11-19',
                }),
              ),
            ),
          },
        }),
      ),
    then: async (p) => {
      await expect(p.locator('[data-testid^="payer-share-"]').first()).toBeVisible();
    },
  },
  {
    label: '/admissions [Attribuer un lit] (D2286)',
    route: '/admissions',
    role: 'ROLE_NURSE',
    seed: async (p) => {
      const active = DESK_ROWS.map((a) => ({ ...a, status: 'ACTIVE' }));
      await p.route('**/api/admissions', (r) => r.fulfill({ json: active }));
      await p.route('**/api/admissions?**', (r) => r.fulfill({ json: active }));
      await p.route('**/api/beds/available**', (r) =>
        r.fulfill({
          json: [1, 2].map((n) => ({
            id: `bed-${n}`,
            label: `MED1/M1-0${n}`,
            wardName: 'Médecine interne B et maladies infectieuses, aile nord',
            roomNumber: 'Chambre 12 (isolement respiratoire)',
          })),
        }),
      );
    },
    then: async (p) => {
      await p.locator('[data-testid="open-bed-assign"]').first().click();
      await expect(p.locator('#adm-bed-select')).toBeVisible();
    },
  },
  {
    label: '/patient-notifications [rows] (D2400)',
    route: '/patient-notifications',
    role: 'ROLE_HOSPITAL_ADMIN',
    seed: (p) =>
      p.route('**/api/patient-notification-deliveries**', (r) =>
        r.fulfill({
          json: {
            data: {
              content: DESK_ROWS.map((d) => ({
                id: d.id,
                patientId: TEST_ID,
                patientName: LONG_NAME,
                eventType: 'APPOINTMENT_REMINDER',
                channel: 'SMS',
                status: 'SKIPPED',
                attempts: 3,
                errorCode: 'RECIPIENT_NOT_ALLOWLISTED',
                retryable: true,
                manualRetries: 0,
                createdAt: NOW,
                lastAttemptAt: NOW,
              })),
              page: 0,
              size: 25,
              totalElements: 3,
              countsByStatus: { PENDING: 0, RETRYING: 0, SENT: 0, FAILED: 0, SKIPPED: 3 },
            },
          },
        }),
      ),
    then: async (p) => {
      await expect(p.getByText(LONG_NAME).first()).toBeVisible();
    },
  },
  /* ── r68 (D3050 / D3055 / D3057): three views #1246 / #1247 left past a phone's edge ── */
  {
    label: '/super-admin/subscriptions [Nouveau plan] (D3050)',
    route: '/super-admin/subscriptions',
    role: 'ROLE_SUPER_ADMIN',
    focus: '[data-test="edit-card"]',
    seed: (p) =>
      p.route('**/api/super-admin/subscriptions/plans**', (r) =>
        r.fulfill({
          json: [
            {
              id: RECORD_ID,
              code: 'ESSENTIEL-CLINIQUE-2026',
              name: 'Essentiel clinique de district',
              description:
                'Dossier patient, rendez-vous, facturation et pharmacie pour une clinique',
              monthlyPriceCents: 12_500_000,
              currency: 'XOF',
              active: true,
              features: [],
            },
          ],
        }),
      ),
    then: async (p) => {
      await p.locator('.toolbar .btn-primary').first().click();
      await expect(p.locator('[data-test="edit-card"]')).toBeVisible();
    },
  },
  {
    label: '/dashboard [hospital admin: schedule, quick navigation] (D3055)',
    route: '/dashboard',
    role: 'ROLE_HOSPITAL_ADMIN',
    focus: '.admin-workspace',
    seed: async (p) => {
      await p.route('**/api/appointments**', (r) => r.fulfill({ json: DESK_ROWS }));
    },
    then: async (p) => {
      await expect(p.locator('.admin-workspace .sched-row').first()).toBeVisible();
      await expect(p.locator('.admin-workspace .nav-tile').first()).toBeVisible();
    },
  },
  {
    label: '/admin-assignments [details: e-mail, checklist, footer] (D3051)',
    route: '/admin-assignments',
    role: 'ROLE_HOSPITAL_ADMIN',
    seed: (p) =>
      p.route('**/api/assignments?**', (r) =>
        r.fulfill({
          json: page1([
            {
              id: RECORD_ID,
              userId: TEST_ID,
              userName: 'Aminata Sawadogo-Kaboré',
              userEmail:
                'aminata.sawadogo.kabore.medecin-chef@centre-hospitalier-wend-panga.example.invalid',
              roleCode: 'ROLE_DOCTOR',
              roleName: 'ROLE_DOCTOR',
              hospitalId: TEST_ID,
              hospitalName: HOSPITAL,
              hospitalCode: 'CHU-WP-OUAGA-2026',
              active: true,
              assignmentCode: 'ASG-CHUWP-2026-000123',
              assignedAt: NOW,
              profileChecklist: [
                'Numéro d’ordre du médecin à renseigner',
                'Département de rattachement à choisir',
                'Spécialité à préciser',
                'Photo du profil à ajouter',
              ],
            },
          ]),
        }),
      ),
    then: async (p) => {
      await p.locator('tbody tr').first().locator('.icon-btn').first().click();
      await expect(p.locator('#admin-assignments-dialog-3-title')).toBeVisible();
    },
  },
  {
    label: '/organizations [cards: name and description] (D3057)',
    route: '/organizations',
    role: 'ROLE_SUPER_ADMIN',
    seed: (p) =>
      p.route('**/api/organizations?**', (r) =>
        r.fulfill({
          json: page1(
            [
              'Centre Hospitalier Universitaire Wend-Panga de Ouagadougou',
              'Org Démo Wend-Panga — FICTIF',
              'Réseau-de-santé-Wend-Panga-et-centres-associés-du-Kadiogo',
            ].map((name, i) => ({
              id: `${RECORD_ID.slice(0, -1)}${i + 1}`,
              name,
              code: `CHU-WP-OUAGA-202${i}`,
              description: 'demo-r68 — organisation de démonstration fictive du réseau Wend-Panga',
              type: 'HOSPITAL_CHAIN',
              active: true,
              createdAt: NOW,
              updatedAt: NOW,
              primaryContactEmail: 'direction.generale@wend-panga.example.invalid',
              primaryContactPhone: '+226 70 00 00 00',
              defaultTimezone: 'Africa/Ouagadougou',
              onboardingNotes: '',
              lifecycleState: i === 2 ? 'SUSPENDED' : 'ACTIVE',
              hospitals: [],
            })),
          ),
        }),
      ),
    then: async (p) => {
      await expect(p.locator('tbody .org-name').first()).toBeVisible();
    },
  },
];

test.describe('layout overflow: r64 dialogs and money screens', () => {
  test.use({ storageState: { cookies: [], origins: [] } });

  for (const c of DIALOGS) {
    test(`fits: ${c.label}`, async ({ page }, testInfo) => {
      if (c.seed) await c.seed(page);
      await open(page, testInfo.project.use.baseURL as string, c.role, c.route);
      await c.then(page);
      const found = await auditAtWidths(page, c.label);
      const name = c.label.replace(/[^a-z0-9]+/gi, '-').replace(/^-|-$/g, '');
      await shoot(page, name, LAYOUT_WIDTHS, c.focus);
      expect(found, formatOffenders(found)).toEqual([]);
    });
  }

  test('fits: patient record [Couverture, Lier une assurance] (D2433)', async ({
    page,
  }, testInfo) => {
    await page.route(`**/api/patients/${TEST_ID}`, (r) => r.fulfill({ json: PATIENT }));
    await page.route(`**/api/patients/${TEST_ID}?**`, (r) => r.fulfill({ json: PATIENT }));
    // D2433: the « Formule » select lists the hospital's long plan names
    // (shown when the signed-in role may set a plan).
    await page.route('**/api/billing/payers**', (r) => r.fulfill({ json: [PAYER] }));
    const path = `/patients/${TEST_ID}`;
    await open(page, testInfo.project.use.baseURL as string, 'ROLE_RECEPTIONIST', path);
    await page.setViewportSize({ width: 1366, height: 900 });
    await page
      .locator('.section-tab')
      .filter({ hasText: /administrati/i })
      .first()
      .click();
    await page
      .locator('.sub-tabs .tab')
      .filter({ hasText: /Couverture|Coverage/i })
      .first()
      .click();
    await page
      .getByRole('button', { name: /Lier une assurance|Link insurance/i })
      .first()
      .click();
    await expect(page.locator('[role="dialog"]').first()).toBeVisible();
    const found = await auditAtWidths(page, '/patients/:id [Lier une assurance]');
    await shoot(page, 'coverage-link-dialog', LAYOUT_WIDTHS);
    expect(found, formatOffenders(found)).toEqual([]);
  });
});

/* ── r66 (QA D2938): a dialog is never taller than the screen ───────────────
   The nurse-station vitals dialog had no height limit and nothing scrolled:
   with the maternal « préoccupations » list open, « Enregistrer » sat below a
   1366×768 laptop screen, and on a 390×844 phone it could never be reached.
   The `dialog-height` rule of helpers/layout-audit.ts now measures every dialog
   this file opens; this case opens the one QA failed, list unfolded, and
   clicks Save where a nurse would. */

test.describe('layout overflow: r66 dialog height (D2938)', () => {
  test.use({ storageState: { cookies: [], origins: [] } });

  test('the vitals dialog fits a laptop and a phone with the concerns list open, and Save is in reach', async ({
    page,
  }, testInfo) => {
    await page.route('**/api/nurse/vitals/due**', (r) => r.fulfill({ json: DESK_ROWS }));
    await open(page, testInfo.project.use.baseURL as string, 'ROLE_NURSE', '/nurse-station');
    await page.setViewportSize({ width: 1366, height: 768 });
    await page.locator('.action-record').first().click();
    const dialog = page.locator('.modal-vitals');
    await expect(dialog).toBeVisible();
    // The list is the last field but one: scrolled to the end of the dialog, it is clear of the
    // action row stuck to the bottom edge.
    await dialog.evaluate((el) => (el.scrollTop = el.scrollHeight));
    await dialog.locator('[data-testid="maternal-concerns"] summary').click();
    await expect(dialog.locator('[data-testid^="maternal-concern-"]').first()).toBeVisible();

    const found = await auditAtWidths(page, '/nurse-station [signes vitaux, préoccupations]');
    expect(found, formatOffenders(found)).toEqual([]);

    const save = dialog.locator('[data-testid="vitals-save"]');
    for (const width of [1366, 390]) {
      await page.setViewportSize({ width, height: heightFor(width) });
      // Wherever the fields are scrolled, the title bar of the dialog and Save are on screen.
      for (const top of [0, 100_000]) {
        await dialog.evaluate((el, y) => (el.scrollTop = y), top);
        await expect(save, `Save @${width}px, scrolled to ${top}`).toBeInViewport({ ratio: 1 });
        const box = (await dialog.boundingBox())!;
        expect(box.y, `dialog top @${width}px`).toBeGreaterThanOrEqual(0);
        expect(box.y + box.height, `dialog bottom @${width}px`).toBeLessThanOrEqual(
          heightFor(width),
        );
        // The action row reaches the dialog's bottom edge: no strip of fields scrolls under the buttons.
        const row = (await dialog.locator('.modal-actions').boundingBox())!;
        expect(
          Math.abs(row.y + row.height - (box.y + box.height)),
          `action row at the dialog's edge @${width}px, scrolled to ${top}`,
        ).toBeLessThanOrEqual(2);
      }
      // A real pointer reaches it (Playwright refuses a click outside the viewport).
      await save.click({ trial: true, timeout: 5_000 });
    }
    await shoot(page, 'nurse-vitals-dialog-concerns', [1366, 390]);
  });
});

/* ── Lab instrument outbox: the message dialog (owner, 2026-11-19) ───────── */

const OUTBOX_ID = '00000000-0000-0000-0000-0000000c3001';
const OUTBOX_ROW = {
  id: OUTBOX_ID,
  labOrderId: RECORD_ID,
  messageType: 'ORU^R01',
  resultStatus: 'P',
  status: 'ERROR',
  createdAt: '2026-11-19T10:30:00',
  attempts: 5,
  lastError:
    'java.net.ConnectException: Connection refused (Connection refused) to analyser 10.0.0.12:2576',
  lastAttemptAt: '2026-11-19T10:45:00',
};
const OUTBOX_PAYLOAD = [
  'MSH|^~\\&|HMS|HOSPITAL|LAB_ANALYZER|LAB|20261119103000+0000||ORU^R01^ORU_R01|HMS-4F2A9C1B7D3E|P|2.5.1',
  `PID|1||${TEST_ID}||Démo-Ouédraogo Kaboré-Sawadogo^Évelyne^Marie-Noëlle`,
  `OBR|1|${RECORD_ID}||NFS^Numération formule sanguine complète avec plaquettes|||20261119102000+0000`,
  'OBX|1|ST|NFS^Numération formule sanguine complète avec plaquettes||Leucocytes 12,4 ; hématies 4,1 ; plaquettes 250|10\\S\\9/L||H|||P|||20261119102000+0000',
].join('\r');

test.describe('layout overflow: lab outbox message dialog', () => {
  test.use({ storageState: { cookies: [], origins: [] } });

  test('the message dialog fits: summary, segment lines, copy button', async ({
    page,
  }, testInfo) => {
    await page.route('**/api/lab-instrument-outbox**', (r) => {
      const url = r.request().url();
      if (url.includes('/transport')) {
        return r.fulfill({
          json: {
            success: true,
            data: {
              enabled: true,
              host: '10.0.0.12',
              port: 2576,
              maxAttempts: 5,
              retryAfterSeconds: 60,
              batchSize: 50,
            },
          },
        });
      }
      if (url.includes('/orders/')) {
        return r.fulfill({
          json: {
            success: true,
            data: [OUTBOX_ROW, { ...OUTBOX_ROW, id: 'sib-1', resultStatus: 'F', status: 'ACK' }],
          },
        });
      }
      if (url.includes(OUTBOX_ID)) {
        return r.fulfill({
          json: { success: true, data: { ...OUTBOX_ROW, payload: OUTBOX_PAYLOAD } },
        });
      }
      return r.fulfill({
        json: {
          success: true,
          data: {
            content: [OUTBOX_ROW],
            page: 0,
            size: 25,
            totalElements: 1,
            pendingCount: 0,
            errorCount: 1,
            ackCount: 0,
          },
        },
      });
    });
    await open(page, testInfo.project.use.baseURL as string, roleFor('/lab-outbox'), '/lab-outbox');
    await page.setViewportSize({ width: 1366, height: 900 });
    await page.locator(`[data-testid="outbox-view-${OUTBOX_ID}"]`).click();
    await expect(page.locator('[data-testid="outbox-detail-summary"]')).toBeVisible();
    await expect(page.locator('[data-testid="outbox-detail-payload"] .segment')).toHaveCount(4);
    const found = await auditAtWidths(page, '/lab-outbox message dialog');
    await shoot(page, 'lab-outbox-dialog', LAYOUT_WIDTHS);
    expect(found, formatOffenders(found)).toEqual([]);
  });
});

/* ── #1247 follow-up (Rex): a sortable list sorts in card mode ───────────────
   Under 600 px the header row (and its sort buttons) is hidden; the shared
   « Trier par » control takes its place and drives the same sort. At 768 and
   1366 the header sorts and the control is not shown. */

const SORT_ROWS = ['MRN-B-0002', 'MRN-C-0003', 'MRN-A-0001'].map((mrn, n) => ({
  ...DESK,
  id: `${RECORD_ID.slice(0, -1)}${n + 1}`,
  mrn,
}));

test.describe('card mode: sortable lists keep their sort (#1247)', () => {
  test.use({ storageState: { cookies: [], origins: [] } });

  // The page opens at phone width: resizing down from a desktop leaves the
  // shell's phone drawer open, and the page behind it is inert (QA D1153).
  test('/patients sorts by « Trier par » on a phone', async ({ page }, testInfo) => {
    await page.route('**/api/patients', (r) => r.fulfill({ json: SORT_ROWS }));
    await page.route('**/api/patients?**', (r) => r.fulfill({ json: SORT_ROWS }));
    await page.setViewportSize({ width: 390, height: heightFor(390) });
    await open(page, testInfo.project.use.baseURL as string, 'ROLE_RECEPTIONIST', '/patients');
    const control = page.locator('app-card-sort');
    const firstRow = page.locator('table tbody tr').first();

    await expect(control).toBeVisible();
    await expect(page.locator('table thead')).toBeHidden();
    // axe on the control itself: labelled select, named toggle, contrast.
    const axe = await new AxeBuilder({ page }).include('app-card-sort').analyze();
    expect(axe.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
    const field = control.getByRole('combobox');
    await field.selectOption('mrn');
    await expect(firstRow).toContainText('MRN-A-0001');
    // Keyboard: Tab to the direction toggle and press Enter.
    await field.focus();
    await page.keyboard.press('Tab');
    await expect(control.getByRole('button')).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(firstRow).toContainText('MRN-C-0003');

    // Wider, the header sorts, shows the same sort, and the control is gone.
    for (const width of [768, 1366]) {
      await page.setViewportSize({ width, height: heightFor(width) });
      await expect(control).toBeHidden();
      await expect(page.locator('table thead')).toBeVisible();
      await expect(page.locator('th[aria-sort="descending"]')).toHaveCount(1);
    }
    const found = await auditAtWidths(page, '/patients [card sort]');
    await shoot(page, 'card-sort-patients', [1366, 768, 390]);
    expect(found, formatOffenders(found)).toEqual([]);
  });

  test('/appointments shows the control on a phone only', async ({ page }, testInfo) => {
    await page.route('**/api/appointments**', (r) => r.fulfill({ json: SORT_ROWS }));
    await page.setViewportSize({ width: 390, height: heightFor(390) });
    await open(page, testInfo.project.use.baseURL as string, 'ROLE_RECEPTIONIST', '/appointments');
    const control = page.locator('app-card-sort');
    await expect(control).toBeVisible();
    await expect(control.getByRole('combobox')).toHaveValue('date');
    await control.getByRole('combobox').selectOption('status');
    for (const width of [768, 1366]) {
      await page.setViewportSize({ width, height: heightFor(width) });
      await expect(control).toBeHidden();
      await expect(
        page.locator('th[aria-sort="descending"], th[aria-sort="ascending"]'),
      ).toHaveCount(1);
    }
  });
});

// D3908/D3909: populated diagnostics worklists, not just their empty states.
test.describe('r77 diagnostic worklist controls', () => {
  test.use({ storageState: { cookies: [], origins: [] } });

  for (const width of [390, 1440]) {
    test(`D3904: culture catalogue setting is visible and governed at ${width}`, async ({
      page,
    }, testInfo) => {
      await page.setViewportSize({ width, height: 1000 });
      await signInAs(page, 'ROLE_LAB_DIRECTOR', testInfo.project.use.baseURL as string);
      await installLocalIconFonts(page);
      await page.addInitScript(() => localStorage.setItem('lang', 'fr'));
      await page.route(/\/api\/lab-test-definitions\/search(?:\?.*)?$/, (route) =>
        route.fulfill({
          json: {
            data: {
              content: [
                {
                  id: TEST_ID,
                  testCode: 'ECBU',
                  testName: 'ECBU',
                  category: 'Microbiologie',
                  culture: true,
                  approvalStatus: 'ACTIVE',
                  isActive: true,
                  referenceRanges: [],
                },
              ],
              totalElements: 1,
              totalPages: 1,
              number: 0,
            },
          },
        }),
      );
      await page.goto('/lab-test-config');
      await page.getByTestId('edit-def').first().click();
      const culture = page.locator('#defCulture');
      await expect(culture).toBeChecked();
      await expect(culture).toBeDisabled();
      await expect(page.locator('label[for="defCulture"]')).toContainText('Culture');
      await expect(page.locator('#defCultureHint')).toBeVisible();
      await testInfo.attach(`culture-catalogue-${width}`, {
        body: await page.screenshot(),
        contentType: 'image/png',
      });
      await page.keyboard.press('Escape');
      await expect(culture).toHaveCount(0);
      await page.locator('.header-right .btn-primary').click();
      await expect(culture).not.toBeChecked();
      await expect(culture).toBeEnabled();
    });

    test(`D3908: blood-bank tabs and row actions are styled at ${width}`, async ({
      page,
    }, testInfo) => {
      await page.setViewportSize({ width, height: 1000 });
      await signInAs(page, 'ROLE_LAB_DIRECTOR', testInfo.project.use.baseURL as string);
      await installLocalIconFonts(page);
      await page.route(/\/api\/transfusions\/requests(?:\?.*)?$/, (route) =>
        route.fulfill({
          json: [
            {
              id: TEST_ID,
              patientId: TEST_ID,
              hospitalId: TEST_ID,
              patientName: LONG_NAME,
              patientMrn: 'SYN-77',
              productType: 'PACKED_RED_CELLS',
              unitsRequested: 1,
              indication: 'Synthetic worklist',
              urgency: 'ROUTINE',
              status: 'REQUESTED',
              requestedAt: '2026-11-25T10:00:00',
              patientAboGroup: null,
              patientRhFactor: null,
            },
          ],
        }),
      );
      await page.goto('/transfusions');
      const selected = page.locator('.tab-btn.active');
      const rowAction = page.locator('tbody .action-link').first();
      await expect(rowAction).toBeVisible();
      for (const control of [selected, rowAction]) {
        const style = await control.evaluate((element) => {
          const computed = getComputedStyle(element);
          return {
            radius: parseFloat(computed.borderRadius),
            height: element.getBoundingClientRect().height,
          };
        });
        expect(style.radius).toBeGreaterThanOrEqual(8);
        expect(style.height).toBeGreaterThanOrEqual(40);
      }
      await selected.focus();
      await expect(selected).toBeFocused();
      await testInfo.attach(`blood-bank-${width}`, {
        body: await page.screenshot(),
        contentType: 'image/png',
      });
    });
  }

  test('D3909: the received-date header stays clear of Actions on a desktop', async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    await signInAs(page, 'ROLE_LAB_DIRECTOR', testInfo.project.use.baseURL as string);
    await installLocalIconFonts(page);
    await page.route(/\/api\/lab-results\/pending-release(?:\?.*)?$/, (route) =>
      route.fulfill({
        json: {
          data: {
            content: [
              {
                id: TEST_ID,
                patientId: TEST_ID,
                hospitalId: TEST_ID,
                patientFullName: LONG_NAME,
                labTestName: 'Potassium - laboratoire de biochimie',
                labOrderCode: 'LAB-SYNTHETIC-77',
                resultValue: '4.2',
                resultUnit: 'mmol/L',
                severity: 'NORMAL',
                resultDate: '2026-11-25T10:00:00',
                createdAt: '2026-11-25T10:02:00',
                resultStatus: 'FINAL',
                validated: false,
              },
            ],
            totalElements: 1,
            totalPages: 1,
            number: 0,
            size: 20,
          },
        },
      }),
    );
    await page.goto('/lab-release-worklist');
    await expect(page.locator('[data-testid="release-row-' + TEST_ID + '"]')).toBeVisible();
    const headers = page.locator('.release-table thead th');
    const received = await headers.nth(6).boundingBox();
    const actions = await headers.nth(7).boundingBox();
    expect(received).not.toBeNull();
    expect(actions).not.toBeNull();
    expect(received!.x + received!.width).toBeLessThanOrEqual(actions!.x + 1);
    await testInfo.attach('release-worklist-1440', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });
  });
});
