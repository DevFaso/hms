import { test, expect } from './fixtures/test-fixtures';
import { signInAs, TEST_ID } from './helpers/role-session';
import { installLocalIconFonts } from './helpers/local-icon-fonts';

for (const width of [390, 1440]) {
  test(`automatic admissions has designed controls and explains disabled fields at ${width}px`, async ({
    page,
    baseURL,
  }, info) => {
    await page.setViewportSize({ width, height: 1000 });
    await signInAs(page, 'ROLE_SUPER_ADMIN', baseURL!);
    await installLocalIconFonts(page);
    await page.route('**/api/admin/adt-intake-configs*', (route) => route.fulfill({ json: [] }));
    await page.goto('/admin/adt-intake-configs');
    const form = page.getByTestId('adt-intake-form');
    await expect(form).toBeVisible();
    const provider = page.getByTestId('adt-intake-provider');
    await expect(provider).toBeDisabled();
    await expect(page.locator('#adt-dependent-help')).toBeVisible();
    expect(await provider.getAttribute('aria-describedby')).toBe('adt-dependent-help');
    const controls = form.locator('input:not([type="checkbox"]), select, textarea');
    for (const control of await controls.all()) {
      const measured = await control.evaluate((el) => {
        const rect = el.getBoundingClientRect();
        const parent = el.closest('form')!.getBoundingClientRect();
        const style = getComputedStyle(el);
        return {
          height: rect.height,
          left: rect.left,
          right: rect.right,
          parentLeft: parent.left,
          parentRight: parent.right,
          radius: parseFloat(style.borderRadius),
          font: style.fontFamily,
        };
      });
      expect(measured.height).toBeGreaterThanOrEqual(43);
      expect(measured.radius).toBeGreaterThanOrEqual(7);
      expect(measured.left).toBeGreaterThanOrEqual(measured.parentLeft);
      expect(measured.right).toBeLessThanOrEqual(measured.parentRight);
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(
      true,
    );
    await expect(page.getByTestId('adt-intake-admin')).not.toContainText('ADT^');
    await expect(page.getByTestId('adt-intake-admin')).not.toContainText('app.hl7.');
    const screenshot = info.outputPath(`automatic-admissions-${width}.png`);
    await page.screenshot({ path: screenshot, fullPage: true });
    await info.attach(`automatic-admissions-${width}`, {
      path: screenshot,
      contentType: 'image/png',
    });
  });
}

for (const width of [390, 1440]) {
  test(`integration and cost forms use the native design system at ${width}px`, async ({
    page,
    baseURL,
  }, info) => {
    await page.setViewportSize({ width, height: 1000 });
    await signInAs(page, 'ROLE_HOSPITAL_ADMIN', baseURL!);
    await installLocalIconFonts(page);
    await page.route('**/api/admin/integrations/dhis2/facility*', (route) =>
      route.fulfill({
        json: {
          id: TEST_ID,
          hospitalId: TEST_ID,
          baseUrl: 'https://example.invalid',
          authMode: 'PAT',
          authSecretEnvVar: 'CDX_DHIS2_TOKEN',
          authSecretConfigured: false,
          defaultPeriodType: 'MONTHLY',
          defaultDatasetUid: null,
          active: true,
        },
      }),
    );
    await page.route('**/api/admin/integrations/dhis2/mappings*', (route) =>
      route.fulfill({ json: { content: [], totalElements: 0, totalPages: 0, number: 0 } }),
    );
    await page.route('**/api/admin/integrations/dhis2/exports/runs*', (route) =>
      route.fulfill({ json: [] }),
    );
    await page.goto('/admin/integrations/dhis2');
    const checkAndCapture = async (formId: string, name: string) => {
      const form = page.getByTestId(formId);
      await expect(form).toBeVisible();
      const measurements = await form
        .locator('input:not([type="checkbox"]), select, textarea')
        .evaluateAll((controls) =>
          controls.map((el) => {
            const box = el.getBoundingClientRect();
            const form = el.closest('form')!.getBoundingClientRect();
            return {
              height: box.height,
              left: box.left,
              right: box.right,
              parentLeft: form.left,
              parentRight: form.right,
              radius: parseFloat(getComputedStyle(el).borderRadius),
              designed: el.classList.contains('keneya-form-control'),
            };
          }),
        );
      expect(measurements.length).toBeGreaterThan(0);
      for (const measured of measurements) {
        expect(measured.designed).toBe(true);
        expect(measured.height).toBeGreaterThanOrEqual(43);
        expect(measured.radius).toBeGreaterThanOrEqual(7);
        expect(measured.left).toBeGreaterThanOrEqual(measured.parentLeft);
        expect(measured.right).toBeLessThanOrEqual(measured.parentRight);
      }
      expect(
        await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1),
      ).toBe(true);
      const screenshot = info.outputPath(`${name}-${width}.png`);
      await page.screenshot({ path: screenshot, fullPage: true });
      await info.attach(`${name}-${width}`, { path: screenshot, contentType: 'image/png' });
    };
    await checkAndCapture('dhis2-config-form', 'integration-settings');
    await page.getByTestId('dhis2-tab-mappings').click();
    await checkAndCapture('dhis2-mapping-add-form', 'integration-mappings');
    await page.getByTestId('dhis2-tab-exports').click();
    await checkAndCapture('dhis2-export-form', 'integration-exports');
    await signInAs(page, 'ROLE_SUPER_ADMIN', baseURL!);
    await page.route('**/api/super-admin/cost/per-tenant/chargeback*', (route) =>
      route.fulfill({ json: [] }),
    );
    await page.goto('/super-admin/cost');
    await checkAndCapture('cost-panel-filters', 'platform-cost-filters');
  });
}
