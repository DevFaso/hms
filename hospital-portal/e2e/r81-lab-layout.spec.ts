import { test, expect } from './fixtures/test-fixtures';
import { signInAs } from './helpers/role-session';
import { installLocalIconFonts } from './helpers/local-icon-fonts';

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
        await expect(page.locator('tbody tr').first()).toContainText('15/03/2025');
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
