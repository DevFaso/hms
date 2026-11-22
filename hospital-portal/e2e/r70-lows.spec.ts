import { test, expect } from './fixtures/test-fixtures';
import { signInAs, TEST_ID } from './helpers/role-session';
import { installLocalIconFonts } from './helpers/local-icon-fonts';
import { heightFor, LAYOUT_WIDTHS } from './helpers/layout-audit';

test.use({ storageState: { cookies: [], origins: [] } });
test('D3353/D3356: draft panel guidance and component ranges at four widths', async ({
  page,
  baseURL,
}, testInfo) => {
  await signInAs(page, 'ROLE_LAB_DIRECTOR', baseURL!);
  await installLocalIconFonts(page);
  await page.route('**/api/lab-test-definitions/search**', (r) =>
    r.fulfill({
      json: {
        data: {
          content: [
            {
              id: TEST_ID,
              testCode: 'NFS',
              testName: 'Numération formule sanguine',
              approvalStatus: 'DRAFT',
              referenceRanges: [],
              components: [
                {
                  code: 'HB',
                  name: 'Hémoglobine',
                  unit: 'g/dL',
                  referenceRanges: [
                    {
                      minValue: 12.5,
                      maxValue: 16,
                      gender: 'ALL',
                      ageMin: 18,
                      ageMax: null,
                      ageUnit: 'YEARS',
                    },
                  ],
                },
                {
                  code: 'PLT',
                  name: 'Plaquettes',
                  unit: 'G/L',
                  referenceRanges: [
                    { minValue: 150, maxValue: 400, gender: 'ALL' },
                    { minValue: 100, maxValue: 450, gender: 'ALL' },
                  ],
                },
              ],
              category: 'Hématologie',
              unit: null,
              sampleType: 'Sang',
              isActive: false,
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
  const ranges = page.getByTestId('def-component-ranges');
  await expect(ranges.locator('summary')).toContainText('2 composants');
  await expect(ranges.locator('summary')).toContainText('3 plages');
  await ranges.locator('summary').click();
  await expect(ranges).toContainText('12,5');
  for (const width of LAYOUT_WIDTHS) {
    await page.setViewportSize({ width, height: heightFor(width) });
    await expect(page.getByTestId('def-approval-next-step')).toHaveAttribute(
      'href',
      '/lab-approval-queue',
    );
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(
      true,
    );
    await page.screenshot({
      path: testInfo.outputPath(`lab-config-${width}.png`),
    });
  }
});
test('D3357: the order attestation explains its meaning at four widths', async ({
  page,
  baseURL,
}, testInfo) => {
  await signInAs(page, 'ROLE_DOCTOR', baseURL!);
  await installLocalIconFonts(page);
  await page.goto('/lab');
  await page
    .locator('button')
    .filter({ hasText: /Nouvelle demande/ })
    .first()
    .click();
  const meaning = page.locator('.checkbox-label').filter({ hasText: 'J’atteste électroniquement' });
  for (const width of LAYOUT_WIDTHS) {
    await page.setViewportSize({ width, height: heightFor(width) });
    await expect(meaning).toBeVisible();
    expect(await meaning.evaluate((el) => el.scrollWidth <= el.clientWidth + 1)).toBe(true);
    await page.screenshot({
      path: testInfo.outputPath(`lab-attestation-${width}.png`),
    });
  }
});
