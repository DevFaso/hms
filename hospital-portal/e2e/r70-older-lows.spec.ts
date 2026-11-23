import { test, expect } from './fixtures/test-fixtures';
import { signInAs, TEST_ID } from './helpers/role-session';
import { installLocalIconFonts } from './helpers/local-icon-fonts';
import { heightFor, LAYOUT_WIDTHS } from './helpers/layout-audit';

test.use({ storageState: { cookies: [], origins: [] } });

test('global feature-flag descriptions stay scoped and custom keys appear once', async ({
  page,
  baseURL,
}, testInfo) => {
  await signInAs(page, 'ROLE_SUPER_ADMIN', baseURL!);
  await installLocalIconFonts(page);
  await page.route('**/api/feature-flags', (route) =>
    route.fulfill({ json: { 'cdx.custom': true } }),
  );
  await page.route('**/api/feature-flags/overrides', (route) =>
    route.fulfill({
      json: [
        {
          id: 'tenant',
          flagKey: 'cdx.custom',
          organizationId: TEST_ID,
          description: 'cdx-tenant-only description',
        },
        {
          id: 'global',
          flagKey: 'cdx.custom',
          organizationId: null,
          description: 'cdx-global description',
        },
      ],
    }),
  );
  await page.goto('/feature-flags');
  const row = page.locator('.flag-row');
  await expect(row).toContainText('cdx-global description');
  await expect(row).not.toContainText('cdx-tenant-only description');
  await expect(row.locator('.flag-name')).toHaveText('cdx.custom');
  await expect(row.locator('.muted')).toHaveCount(0);
  const search = page.locator('.search-bar input');
  await search.fill('cdx-tenant-only');
  await expect(row).toHaveCount(0);
  await search.fill('cdx-global');
  await expect(row).toHaveCount(1);
  for (const width of LAYOUT_WIDTHS) {
    await page.setViewportSize({ width, height: heightFor(width) });
    await page.evaluate(() => {
      const backdrop = document.querySelector<HTMLElement>('.sidebar-backdrop');
      if (backdrop && getComputedStyle(backdrop).display !== 'none') backdrop.click();
    });
    await expect(row).toBeVisible();
    expect(await row.evaluate((el) => el.scrollWidth <= el.clientWidth + 1)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath(`flags-${width}.png`) });
  }
});

test('D2653: every report revision kind is available at four widths', async ({
  page,
  baseURL,
}, testInfo) => {
  await signInAs(page, 'ROLE_RADIOLOGIST', baseURL!);
  await installLocalIconFonts(page);
  const order = {
    id: TEST_ID,
    patientId: TEST_ID,
    patientDisplayName: 'cdx-Local Patient',
    hospitalId: TEST_ID,
    modality: 'XRAY',
    studyType: 'cdx-Chest study',
    bodyRegion: 'Thorax',
    priority: 'ROUTINE',
    status: 'RESULTS_AVAILABLE',
  };
  const source = {
    id: 'cdx-signed-report',
    imagingOrderId: TEST_ID,
    patientId: TEST_ID,
    patientDisplayName: 'cdx-Local Patient',
    modality: 'XRAY',
    reportStatus: 'FINAL',
    reportVersion: 1,
    reportTitle: 'cdx-Chest study',
    signed: true,
    signedByName: 'cdx-Radiologist',
    signedAt: '2026-11-22T09:00:00',
    findings: 'cdx-Original signed findings',
    statusHistory: [],
  };
  await page.route(`**/api/imaging/orders/${TEST_ID}`, (route) => route.fulfill({ json: order }));
  await page.route(`**/api/imaging/results/order/${TEST_ID}/all`, (route) =>
    route.fulfill({ json: [source] }),
  );
  await page.route('**/api/imaging/results/cdx-signed-report', (route) =>
    route.fulfill({ json: source }),
  );
  await page.goto(`/imaging?order=${TEST_ID}`);
  await page.getByTestId('imaging-open-report-cdx-signed-report').click();
  await page.getByTestId('imaging-add-addendum').click();
  const dialog = page.getByRole('dialog');
  const picker = page.locator('#rep-status');
  for (const width of LAYOUT_WIDTHS) {
    await page.setViewportSize({ width, height: heightFor(width) });
    await page.evaluate(() => {
      const backdrop = document.querySelector<HTMLElement>('.sidebar-backdrop');
      if (backdrop && getComputedStyle(backdrop).display !== 'none') backdrop.click();
    });
    expect(
      await picker
        .locator('option')
        .evaluateAll((options) => options.map((option) => (option as HTMLOptionElement).value)),
    ).toEqual(['ADDENDUM', 'CORRECTED', 'AMENDED']);
    for (const kind of ['ADDENDUM', 'CORRECTED', 'AMENDED']) {
      await picker.selectOption(kind);
      await expect(picker).toHaveValue(kind);
      await expect(dialog.locator('h2')).toHaveText('Réviser le compte rendu signé');
      await expect(page.getByTestId('addendum-original-report').locator('h3')).toHaveText(
        'Compte rendu signé faisant l’objet de la révision',
      );
    }
    await picker.scrollIntoViewIfNeeded();
    await expect(picker).toBeInViewport();
    const bounds = await dialog.boundingBox();
    expect(bounds!.x).toBeGreaterThanOrEqual(0);
    expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(width + 1);
    await expect(page.getByTestId('addendum-original-report')).toContainText(source.findings);
    await page.screenshot({ path: testInfo.outputPath(`revision-kinds-${width}.png`) });
  }
});

test('D2770: organization facts and hospital links fit four widths', async ({
  page,
  baseURL,
}, testInfo) => {
  await signInAs(page, 'ROLE_SUPER_ADMIN', baseURL!);
  await installLocalIconFonts(page);
  await page.route('**/api/organizations/' + TEST_ID + '**', (route) =>
    route.fulfill({
      json: {
        id: TEST_ID,
        name: 'cdx-Health Network',
        code: 'CDX',
        description: 'Local preview organization',
        type: 'HEALTHCARE_NETWORK',
        active: true,
        primaryContactEmail: 'cdx-long-organization-contact@example.invalid',
        primaryContactPhone: '+22670000000',
        defaultTimezone: 'Africa/Ouagadougou',
        hospitals: [
          { id: TEST_ID, name: 'cdx-Hospital', code: 'CDXH', city: 'Ouagadougou', active: true },
        ],
      },
    }),
  );
  await page.route('**/api/super-admin/organizations/*/lifecycle', (route) =>
    route.fulfill({
      json: {
        organizationId: TEST_ID,
        lifecycleState: 'ACTIVE',
        canSuspend: false,
        canRestore: false,
        canArchive: false,
        canSchedulePurge: false,
        canCancelPurge: false,
      },
    }),
  );
  await page.goto('/organizations/' + TEST_ID);
  const facts = page.getByTestId('organization-details');
  await expect(facts).toContainText('Africa/Ouagadougou');
  await expect(facts.getByRole('link')).toHaveAttribute('href', '/hospitals/' + TEST_ID);
  for (const width of LAYOUT_WIDTHS) {
    await page.setViewportSize({ width, height: heightFor(width) });
    await page.evaluate(() => {
      const backdrop = document.querySelector<HTMLElement>('.sidebar-backdrop');
      if (backdrop && getComputedStyle(backdrop).display !== 'none') backdrop.click();
    });
    await expect(facts).toBeVisible();
    expect(await facts.evaluate((el) => el.scrollWidth <= el.clientWidth + 1)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath(`organization-${width}.png`) });
  }
});

test('D2344: upload cancellation is secondary and closes the form at four widths', async ({
  page,
  baseURL,
}, testInfo) => {
  await signInAs(page, 'ROLE_PATIENT', baseURL!);
  await installLocalIconFonts(page);
  await page.route('**/api/me/patient/documents?*', (route) =>
    route.fulfill({ json: { data: { content: [], totalElements: 0 } } }),
  );
  await page.goto('/my-documents');
  const toggle = page.getByTestId('document-upload-toggle');
  for (const width of LAYOUT_WIDTHS) {
    await page.setViewportSize({ width, height: heightFor(width) });
    await page.evaluate(() => {
      const backdrop = document.querySelector<HTMLElement>('.sidebar-backdrop');
      if (backdrop && getComputedStyle(backdrop).display !== 'none') backdrop.click();
    });
    await toggle.click();
    await expect(toggle).toHaveClass(/btn-secondary/);
    await expect(page.locator('.upload-form')).toBeVisible();
    const box = await page.locator('.upload-form').boundingBox();
    expect(box!.x).toBeGreaterThanOrEqual(0);
    expect(box!.x + box!.width).toBeLessThanOrEqual(width + 1);
    await page.screenshot({ path: testInfo.outputPath(`documents-${width}.png`) });
    await toggle.click();
    await expect(page.locator('.upload-form')).toHaveCount(0);
  }
});

test('D2302: specimen actions remain inside the dialog at four widths', async ({
  page,
  baseURL,
}, testInfo) => {
  await signInAs(page, 'ROLE_LAB_SCIENTIST', baseURL!);
  await installLocalIconFonts(page);
  await page.route('**/api/lab-orders?*', (route) =>
    route.fulfill({
      json: {
        data: {
          content: [
            {
              id: TEST_ID,
              labOrderCode: 'LAB-000001',
              patientId: TEST_ID,
              patientFullName: 'cdx-Patient',
              hospitalId: TEST_ID,
              hospitalName: 'cdx-Hospital',
              labTestName: 'cdx-Test',
              labTestCode: 'CDX',
              status: 'COLLECTED',
              orderDatetime: '2026-11-22T08:00:00',
              priority: 'STAT',
            },
          ],
          totalPages: 1,
          totalElements: 1,
          number: 0,
        },
      },
    }),
  );
  await page.route('**/api/lab-orders/*/specimens', (route) =>
    route.fulfill({
      json: {
        data: [
          {
            id: 'specimen-cdx',
            accessionNumber: 'CDX-SPECIMEN-000001',
            specimenType: 'WHOLE_BLOOD',
            collectedAt: '2026-11-22T08:05:00',
            currentLocation: 'cdx-Laboratory reception',
            status: 'COLLECTED',
          },
        ],
      },
    }),
  );
  await page.goto('/lab');
  await page.locator('.specimen-link').first().click();
  const dialog = page.getByTestId('specimens-dialog');
  await expect(dialog.getByTestId('print-specimen-label')).toBeVisible();
  for (const width of LAYOUT_WIDTHS) {
    await page.setViewportSize({ width, height: heightFor(width) });
    await page.evaluate(() => {
      const backdrop = document.querySelector<HTMLElement>('.sidebar-backdrop');
      if (backdrop && getComputedStyle(backdrop).display !== 'none') backdrop.click();
    });
    const box = await dialog.boundingBox();
    expect(box!.x).toBeGreaterThanOrEqual(0);
    expect(box!.x + box!.width).toBeLessThanOrEqual(width + 1);
    if (width >= 1024) {
      await expect(dialog.locator('.specimen-scroll-hint')).toBeHidden();
      expect(
        await dialog.locator('.data-table').evaluate((el) => el.scrollWidth <= el.clientWidth + 1),
      ).toBe(true);
    } else {
      await expect(dialog.locator('.specimen-scroll-hint')).toBeVisible();
    }
    const action = await dialog.getByTestId('print-specimen-label').boundingBox();
    expect(action!.x + action!.width).toBeLessThanOrEqual(box!.x + box!.width + 1);
    await page.screenshot({ path: testInfo.outputPath(`specimens-${width}.png`) });
  }
});
