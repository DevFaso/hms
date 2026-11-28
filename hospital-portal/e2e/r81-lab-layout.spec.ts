import { test, expect } from './fixtures/test-fixtures';
import { signInAs } from './helpers/role-session';
import { installLocalIconFonts } from './helpers/local-icon-fonts';

test.use({ storageState: { cookies: [], origins: [] } });

const pageOf = (content: object[]) => ({
  content,
  totalElements: content.length,
  totalPages: 1,
  number: 0,
  size: 20,
});

test('lab inventory and instruments remain readable at supported widths', async ({
  page,
}, testInfo) => {
  await signInAs(page, 'ROLE_LAB_SCIENTIST', testInfo.project.use.baseURL as string);
  await installLocalIconFonts(page);
  await page.route('**/api/lab/inventory/hospital/**', (route) =>
    route.fulfill({
      json: pageOf([
        {
          id: 'stock-1',
          name: 'Test reagent',
          itemCode: 'REAGENT-01',
          quantity: 0,
          unit: 'flacon',
          reorderThreshold: 2,
          lowStock: true,
          expired: false,
        },
      ]),
    }),
  );
  await page.route('**/api/lab/instruments/hospital/**', (route) =>
    route.fulfill({
      json: pageOf([
        {
          id: 'instrument-1',
          name: 'Test analyser',
          serialNumber: 'TEST-01',
          status: 'ACTIVE',
          nextCalibrationDate: '2025-03-15',
          calibrationOverdue: true,
        },
      ]),
    }),
  );
  await page.route('**/api/lab-test-definitions/search**', (route) =>
    route.fulfill({
      json: {
        success: true,
        data: pageOf([
          {
            id: 'culture-1',
            testCode: 'CULT',
            testName: 'Culture',
            category: 'Microbiology',
            sampleType: 'Blood',
            approvalStatus: 'ACTIVE',
            isActive: true,
            culture: true,
            referenceRanges: [],
          },
        ]),
      },
    }),
  );
  for (const width of [390, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    for (const screen of ['lab-inventory', 'lab-instruments', 'lab-test-config']) {
      await page.goto('/' + screen, { waitUntil: 'domcontentloaded' });
      await expect(page.locator('tbody tr').first()).toBeVisible();
      if (screen === 'lab-inventory') {
        await expect(page.locator('#lab-inventory-search')).toBeVisible();
        if (width === 768) {
          const lines = await page
            .locator('thead th')
            .nth(2)
            .evaluate((header) => {
              const range = document.createRange();
              range.selectNodeContents(header);
              const positions = Array.from(range.getClientRects())
                .filter((rect) => rect.width > 1 && rect.height > 1)
                .map((rect) => Math.round(rect.top));
              return new Set(positions).size;
            });
          expect(lines).toBe(1);
        }
      } else if (screen === 'lab-instruments') {
        await expect(page.locator('tbody tr').first()).toContainText(/15 mars 2025/);
      } else {
        await expect(page.locator('[data-testid="def-culture"]')).toBeVisible();
      }
      await testInfo.attach(`${screen}-${width}`, {
        body: await page.screenshot({ fullPage: true }),
        contentType: 'image/png',
      });
    }
  }
});

// D4163 / D4162: the lane's list screens fit at the three supported widths.
const LONG = 'Centre Hospitalier Universitaire Wend-Panga de Ouagadougou';
const lanePage = (content: object[]) => ({
  content,
  page: 0,
  number: 0,
  size: 20,
  totalElements: content.length,
  totalPages: 1,
  pendingCount: 1,
  errorCount: 1,
  ackCount: 1,
});
const labOrder = (i: number) => ({
  id: `lo-${i}`,
  labOrderCode: `LAB-00004${i}`,
  patientId: 'p1',
  patientFullName: 'Fatimata Ouedraogo-Compaore',
  patientMrn: 'MRN-0000123',
  hospitalName: LONG,
  labTestName: 'Numeration formule sanguine complete',
  labTestCode: 'NFS',
  orderDatetime: '2026-11-27T05:31:00',
  status: i % 2 ? 'RESULTED' : 'IN_PROGRESS',
  priority: 'STAT',
  orderingStaffName: 'Dr Boureima Sawadogo',
  clinicalIndication: 'Fievre persistante depuis trois jours avec asthenie',
});
const LANE_API: Record<string, (i: number) => unknown> = {
  '/api/lab-orders': (i) => ({ data: lanePage([labOrder(i), labOrder(i + 1)]) }),
  '/api/lab-results/pending-release': (i) => ({
    data: lanePage([
      {
        ...labOrder(i),
        id: `lr-${i}`,
        resultValue: '14.2',
        resultUnit: 'g/dL',
        resultDate: '2026-11-27T05:31:00',
        createdAt: '2026-11-27T05:30:00',
        severityFlag: 'CRITICAL_HIGH',
        orderPriority: 'STAT',
      },
    ]),
  }),
  '/api/micro-cultures': (i) =>
    lanePage([
      {
        id: `mc-${i}`,
        labOrderId: 'lo-1',
        labOrderCode: 'LAB-000046',
        labTestName: 'Culture urinaire (ECBU)',
        patientId: 'p1',
        patientName: 'Fatimata Ouedraogo-Compaore',
        hospitalId: 'h1',
        hospitalName: LONG,
        status: 'PRELIMINARY',
        growthResult: 'GROWTH',
        specimenAccessionNumber: 'ACC-2026-000123',
        collectedAt: '2026-11-27T05:00:00',
        createdAt: '2026-11-27T05:30:00',
      },
    ]),
  '/api/lab-instrument-outbox': (i) => ({
    data: lanePage([
      {
        id: `ob-${i}`,
        orderingHospitalName: LONG,
        messageType: 'ORM^O01',
        status: 'ERROR',
        attempts: 3,
        lastError: 'Connection refused by the instrument interface at 10.0.0.12:2575',
        lastAttemptAt: '2026-11-27T05:31:00',
        createdAt: '2026-11-27T05:30:00',
      },
    ]),
  }),
};
const LANE_SCREENS: { path: string; role: string; ready: string }[] = [
  { path: '/lab', role: 'ROLE_LAB_DIRECTOR', ready: 'tbody tr' },
  { path: '/lab-release-worklist', role: 'ROLE_LAB_DIRECTOR', ready: 'tbody tr' },
  { path: '/microbiology', role: 'ROLE_LAB_DIRECTOR', ready: 'tbody tr' },
  { path: '/lab-outbox', role: 'ROLE_LAB_DIRECTOR', ready: 'tbody tr' },
  { path: '/lab-staff', role: 'ROLE_LAB_DIRECTOR', ready: 'tbody tr' },
  { path: '/imaging', role: 'ROLE_DOCTOR', ready: 'tbody tr' },
];

test('lane list screens fit at 390, 768 and 1440 with no sideways scroll', async ({
  page,
}, testInfo) => {
  const failures: string[] = [];
  for (const screen of LANE_SCREENS) {
    await signInAs(page, screen.role, testInfo.project.use.baseURL as string);
    await installLocalIconFonts(page);
    await page.route('**/api/**', (route) => {
      const url = new URL(route.request().url());
      const make = LANE_API[url.pathname];
      if (make) return route.fulfill({ json: make(1) });
      if (/^\/api\/staff\/hospital\/[^/]+\/lab$/.test(url.pathname)) {
        return route.fulfill({
          json: lanePage([
            {
              id: 's1',
              userId: 'u1',
              username: 'amadou',
              name: 'Amadou Ouedraogo-Traore',
              email: 'amadou.ouedraogo-traore@hopital-wend-panga.example.org',
              hospitalId: 'h1',
              departmentName: 'Laboratoire de biologie medicale',
              licenseNumber: 'BF-LAB-2019-00045812',
              roleCode: 'ROLE_LAB_SCIENTIST',
              roleName: 'Biologiste',
            },
          ]),
        });
      }
      if (/^\/api\/imaging\/orders(\/hospital\/.*)?$/.test(url.pathname)) {
        return route.fulfill({
          json: [
            {
              id: 'io-1',
              patientDisplayName: 'Fatimata Ouedraogo-Compaore',
              patientMrn: 'MRN-0000123',
              hospitalName: LONG,
              modality: 'CT',
              studyType: 'Scanner thoracique',
              bodyRegion: 'Thorax',
              laterality: '',
              priority: 'STAT',
              status: 'COMPLETED',
              orderedAt: '2026-11-27T05:31:00',
              scheduledDate: '2026-11-27',
              orderingProviderName: 'Dr Boureima Sawadogo',
            },
          ],
        });
      }
      if (process.env.LANE_DEBUG) console.log('UNMATCHED', url.pathname + url.search);
      return route.fallback();
    });
    for (const width of [390, 768, 1440]) {
      await page.setViewportSize({ width, height: 900 });
      await page.goto(screen.path, { waitUntil: 'domcontentloaded' });
      await expect(page.locator(screen.ready).first()).toBeVisible();
      await page.evaluate(() => document.fonts.ready.then(() => undefined));
      const problems = await page.evaluate(() => {
        const out: string[] = [];
        const root = document.documentElement;
        if (root.scrollWidth > root.clientWidth + 1) out.push('page scrolls sideways');
        const content = document.querySelector('.content');
        if (content && content.scrollWidth > content.clientWidth + 1) out.push('content scrolls');
        for (const table of Array.from(document.querySelectorAll('table'))) {
          if (!(table as HTMLElement).offsetParent) continue;
          let el: HTMLElement | null = table.parentElement;
          while (el && el !== document.body) {
            const ox = getComputedStyle(el).overflowX;
            if ((ox === 'auto' || ox === 'scroll') && el.scrollWidth > el.clientWidth + 1) {
              out.push(`table scrolls sideways (${el.scrollWidth} > ${el.clientWidth})`);
              break;
            }
            el = el.parentElement;
          }
        }
        for (const el of Array.from(document.querySelectorAll('td, th, .status-badge, .chip'))) {
          const h = el as HTMLElement;
          if (!h.offsetParent || h.children.length > 0) continue;
          const text = (h.textContent ?? '').trim();
          if (text.length < 4 || /\s/.test(text)) continue;
          const range = document.createRange();
          range.selectNodeContents(h);
          const rects = Array.from(range.getClientRects()).filter((r) => r.width > 1);
          const lines = new Set(rects.map((r) => Math.round(r.top))).size;
          // A hyphenated code may wrap at its hyphen; an email may wrap at its separators.
          const wraps = /[-@]/.test(text);
          if (lines > (wraps ? 2 : 1) && !text.includes('@'))
            out.push(`word broken over ${lines} lines: ${text}`);
        }
        return out;
      });
      for (const p of problems) failures.push(`${screen.path} @${width}: ${p}`);
      if (process.env.LANE_SHOTS)
        await page.screenshot({
          path: `test-results/lane-shots/lane${screen.path.replace(/\//g, '-')}-${width}.png`,
          fullPage: true,
        });
      await testInfo.attach(`lane${screen.path.replace(/\//g, '-')}-${width}`, {
        body: await page.screenshot({ fullPage: true }),
        contentType: 'image/png',
      });
    }
  }
  expect(failures).toEqual([]);
});
