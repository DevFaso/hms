import process from 'node:process';
import path from 'node:path';
import type { Page } from '@playwright/test';

import { test, expect } from './fixtures/test-fixtures';
import { routedPages } from './helpers/routed-page-inventory';
import { installLocalIconFonts } from './helpers/local-icon-fonts';
import { signInAs, TEST_ID } from './helpers/role-session';
import { auditAtWidths, formatOffenders } from './helpers/layout-audit';

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

/** LAYOUT_SHOTS=<dir> [LAYOUT_SHOTS_TAG=before|after]: save the PR's evidence screenshots. */
async function shoot(page: Page, name: string) {
  const dir = process.env['LAYOUT_SHOTS'];
  if (!dir) return;
  const tag = process.env['LAYOUT_SHOTS_TAG'] ?? 'shot';
  for (const width of [1366, 390]) {
    await page.setViewportSize({ width, height: width < 600 ? 844 : 900 });
    await page.evaluate(() => {
      const backdrop = document.querySelector<HTMLElement>('.sidebar-backdrop');
      if (backdrop && getComputedStyle(backdrop).display !== 'none') backdrop.click();
    });
    await page.waitForTimeout(150);
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
    await shoot(page, 'billing');

    await page.setViewportSize({ width: 1366, height: 900 });
    await page.locator('.view-link').first().click();
    const drawer = page.locator('.detail-panel');
    await expect(drawer.getByText('OM-R53RF-782086')).toBeVisible();
    await expect(drawer.getByText('Tarif essai demo-r61', { exact: false })).toBeVisible();
    found.push(...(await auditAtWidths(page, '/billing invoice drawer')));
    await drawer.locator('.payments-section').scrollIntoViewIfNeeded();
    await shoot(page, 'invoice-drawer');
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

test.describe('layout overflow: clinical drawers', () => {
  test.use({ storageState: { cookies: [], origins: [] } });

  for (const c of DRAWERS) {
    test(`drawer fits: ${c.route}`, async ({ page }, testInfo) => {
      await c.seed(page);
      await open(page, testInfo.project.use.baseURL as string, roleFor(c.route), c.route);
      await page.locator(c.open).click();
      await expect(page.locator(c.drawer)).toBeVisible();
      const found = await auditAtWidths(page, `${c.route} drawer`);
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
