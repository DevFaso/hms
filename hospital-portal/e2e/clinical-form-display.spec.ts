import type { Locator, Page, TestInfo } from '@playwright/test';
import { test, expect } from './fixtures/test-fixtures';
import { signInAs, TEST_ID } from './helpers/role-session';
import { installLocalIconFonts } from './helpers/local-icon-fonts';
import fr from '../src/assets/i18n/fr.json';

test.use({ storageState: { cookies: [], origins: [] }, locale: 'fr-FR' });

async function capture(page: Page, info: TestInfo, name: string, controls: Locator): Promise<void> {
  await expect(controls.first()).toBeVisible();
  expect(await controls.count()).toBeGreaterThan(0);
  for (const width of [390, 1440]) {
    await page.setViewportSize({ width, height: 1000 });
    // A desktop-to-phone resize opens the existing navigation drawer. Close
    // that drawer on inline pages so the evidence actually shows the form.
    const sidebar = page.locator('aside.sidebar');
    const phoneMenu = page.locator('aside.sidebar[role="dialog"]');
    const inDialog = await controls.first().evaluate((el) => !!el.closest('[role="dialog"]'));
    if (width === 390 && !inDialog) {
      await expect
        .poll(async () => {
          const right = await sidebar.evaluate((el) => el.getBoundingClientRect().right);
          return right <= 1 || (await phoneMenu.isVisible());
        })
        .toBe(true);
      if (await phoneMenu.isVisible()) await phoneMenu.getByTestId('sidebar-toggle').click();
      await expect
        .poll(() => sidebar.evaluate((el) => el.getBoundingClientRect().right))
        .toBeLessThanOrEqual(1);
    }
    for (const control of await controls.all()) {
      await expect(control).toHaveClass(/keneya-form-control/);
      await expect
        .poll(() => control.evaluate((el) => el.getBoundingClientRect().right))
        .toBeLessThanOrEqual(width + 1);
      const measurement = await control.evaluate((el) => {
        const box = el.getBoundingClientRect();
        const style = getComputedStyle(el);
        return {
          width: box.width,
          height: box.height,
          radius: parseFloat(style.borderRadius),
          right: box.right,
        };
      });
      expect(measurement.width).toBeGreaterThan(0);
      expect(measurement.height).toBeGreaterThanOrEqual(43);
      expect(measurement.radius).toBeGreaterThanOrEqual(7);
      expect(measurement.right).toBeLessThanOrEqual(width + 1);
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(
      true,
    );
    await controls.first().scrollIntoViewIfNeeded();
    const path = info.outputPath(`${name}-${width}.png`);
    await page.screenshot({ path, fullPage: true });
    await info.attach(`${name}-${width}`, { path, contentType: 'image/png' });
  }
}

test('mortality date filters use the shared native design', async ({ page, baseURL }, info) => {
  await signInAs(page, 'ROLE_HOSPITAL_ADMIN', baseURL!);
  await installLocalIconFonts(page);
  await page.route('**/api/mortality/register?**', (route) =>
    route.fulfill({
      json: {
        from: '2026-11-01',
        to: '2026-11-24',
        totalDeaths: 0,
        maternalDeaths: 0,
        lateMaternalDeaths: 0,
        perinatalDeaths: 0,
        perinatalEligibilityUnknown: 0,
        perinatalBelowGestation: 0,
        stillbirths: 0,
        earlyNeonatalDeaths: 0,
        lateNeonatalDeaths: 0,
        deaths: [],
        enteredInError: [],
      },
    }),
  );
  await page.route('**/api/mortality/disputes*', (route) => route.fulfill({ json: [] }));
  await page.goto('/mortality');
  await expect(page).toHaveURL((url) => url.pathname === '/mortality');
  await capture(page, info, 'mortality-filters', page.locator('#mort-from, #mort-to'));
});

test('supervisor date filter uses the shared native design', async ({ page, baseURL }, info) => {
  await signInAs(page, 'ROLE_ACCOUNTANT', baseURL!);
  await installLocalIconFonts(page);
  await page.route(/\/api\/cash-sessions(?:\?.*)?$/, (route) => route.fulfill({ json: [] }));
  await page.goto('/billing/caisses');
  await expect(page).toHaveURL((url) => url.pathname === '/billing/caisses');
  await capture(page, info, 'cash-supervisor', page.locator('#cash-supervisor-date'));
});

test('transfusion request and inventory filters use the shared native design', async ({
  page,
  baseURL,
}, info) => {
  await signInAs(page, 'ROLE_DOCTOR', baseURL!);
  await installLocalIconFonts(page);
  await page.route(/\/api\/transfusions\/(?:requests|units)(?:\?.*)?$/, (route) =>
    route.fulfill({ json: [] }),
  );
  await page.goto('/transfusions');
  await expect(page).toHaveURL((url) => url.pathname === '/transfusions');
  await capture(page, info, 'transfusion-requests', page.locator('.results-filters select'));
  await page.getByRole('button', { name: fr.TRANSFUSION.UNITS_TAB, exact: true }).click();
  await capture(page, info, 'transfusion-inventory', page.locator('.results-filters select'));
});

test('global imaging filter and read-only catalogue fields retain their scope and explanation', async ({
  page,
  baseURL,
}, info) => {
  await signInAs(page, 'ROLE_SUPER_ADMIN', baseURL!);
  await installLocalIconFonts(page);
  await page.route(/\/api\/imaging\/orders(?:\/hospital\/[^/?]+)?(?:\?.*)?$/, (route) =>
    route.fulfill({ json: [] }),
  );
  await page.route('**/api/imaging/catalogue', (route) =>
    route.fulfill({
      json: [
        {
          id: TEST_ID,
          code: 'CDX-XR',
          label: 'cdx Synthetic exam',
          modality: 'XRAY',
          bodyRegion: 'cdx Region',
          mayRevealPregnancy: false,
          namedAsPregnancyStudy: false,
          pregnancyFlagDecidedBy: null,
          pregnancyFlagDecidedAt: null,
          canEdit: false,
        },
      ],
    }),
  );
  await page.goto('/imaging');
  await expect(page).toHaveURL((url) => url.pathname === '/imaging');
  await capture(page, info, 'imaging-hospital-filter', page.getByTestId('imaging-hospital-filter'));
  // The catalogue belongs to one hospital. Inspect its disabled fields with
  // a hospital-scoped reader; global super-admin browsing remains unchanged.
  await signInAs(page, 'ROLE_HOSPITAL_ADMIN', baseURL!);
  await page.goto('/imaging');
  await page.getByTestId('imaging-catalogue-tab').click();
  const controls = page.locator('app-imaging-catalogue .keneya-form-control');
  await expect(controls.first()).toBeDisabled();
  const description = await controls.first().getAttribute('aria-describedby');
  expect(description).toBeTruthy();
  await expect(page.locator(`[id="${description}"]`)).toContainText(
    fr.IMAGING_CATALOGUE.READ_ONLY_FIELDS,
  );
  await capture(page, info, 'imaging-catalogue', controls);
});

test('patient recipient code field uses the native design without a real gateway', async ({
  page,
  baseURL,
}, info) => {
  await signInAs(page, 'ROLE_PATIENT', baseURL!);
  await installLocalIconFonts(page);
  await page.route('**/api/me/patient/recipient-verification', (route) =>
    route.fulfill({ json: [{ channel: 'SMS', verified: false }] }),
  );
  await page.route('**/api/me/patient/recipient-verification/SMS/request', (route) =>
    route.fulfill({
      json: {
        channel: 'SMS',
        verified: false,
        expiresAt: '2099-12-31T12:00:00Z',
      },
    }),
  );
  await page.goto('/notification-settings');
  await expect(page).toHaveURL((url) => url.pathname === '/notification-settings');
  await page
    .getByTestId('verify-SMS')
    .getByRole('button', { name: fr.RECIPIENT_VERIFICATION.SEND, exact: true })
    .click();
  await capture(page, info, 'recipient-verification', page.locator('#recipient-code-SMS'));
});

test('profile recovery code field uses the native design with a fake code delivery', async ({
  page,
  baseURL,
}, info) => {
  await signInAs(page, 'ROLE_DOCTOR', baseURL!);
  await installLocalIconFonts(page);
  await page.route(`**/api/users/${TEST_ID}`, (route) =>
    route.fulfill({
      json: {
        id: TEST_ID,
        username: 'cdx-reader',
        email: 'cdx@example.invalid',
        firstName: 'cdx',
        lastName: 'Reader',
        active: true,
        roles: [{ id: TEST_ID, code: 'ROLE_DOCTOR', name: 'Doctor' }],
      },
    }),
  );
  await page.route('**/api/auth/credentials/me', (route) =>
    route.fulfill({
      json: {
        userId: TEST_ID,
        username: 'cdx-reader',
        email: 'cdx@example.invalid',
        active: true,
        forcePasswordChange: false,
        forceUsernameChange: false,
        mfaEnrolledCount: 0,
        verifiedMfaCount: 0,
        hasPrimaryMfa: false,
        recoveryContactCount: 1,
        verifiedRecoveryContacts: 0,
        hasPrimaryRecoveryContact: false,
        mfaEnrollments: [],
        recoveryContacts: [
          {
            id: TEST_ID,
            contactType: 'EMAIL',
            contactValue: 'cdx@example.invalid',
            verified: false,
            primaryContact: false,
          },
        ],
      },
    }),
  );
  await page.route(`**/api/auth/credentials/recovery/${TEST_ID}/send-code`, (route) =>
    route.fulfill({ status: 204 }),
  );
  await page.goto('/profile');
  await expect(page).toHaveURL((url) => url.pathname === '/profile');
  await page.getByTestId('profile-tab-security').click();
  await page
    .locator('.recovery-item')
    .getByRole('button', { name: fr.PROFILE.VERIFY, exact: true })
    .click();
  await page
    .getByTestId('confirm-dialog')
    .getByRole('button', { name: fr.PROFILE.RECOVERY_SEND_CODE, exact: true })
    .click();
  await capture(page, info, 'profile-verification', page.locator('#verification-code'));
});

test('cash count and explanation fields retain their values and native design', async ({
  page,
  baseURL,
}, info) => {
  await signInAs(page, 'ROLE_ACCOUNTANT', baseURL!);
  await installLocalIconFonts(page);
  const day = new Date().toISOString().slice(0, 10);
  await page.route('**/api/cash-sessions/mine*', (route) =>
    route.fulfill({
      json: [
        {
          id: TEST_ID,
          till: 'BILLING',
          cashierId: TEST_ID,
          cashierName: 'cdx Cashier',
          businessDate: day,
          status: 'OPEN',
          closeRound: 0,
          mine: true,
          methods: [
            {
              method: 'CASH',
              inCount: 1,
              inAmount: 1000,
              outCount: 0,
              outAmount: 0,
              expected: 1000,
            },
          ],
          totalIn: 1000,
          totalOut: 0,
          totalExpected: 1000,
          cashToCount: 1000,
          counts: [],
          movements: [],
        },
      ],
    }),
  );
  await page.goto('/billing');
  await page.getByTestId('cash-closing-toggle').click();
  const count = page.getByTestId('cash-count-CASH-');
  await count.fill('800');
  await expect(page.getByTestId('cash-explanation-CASH')).toBeVisible();
  await capture(page, info, 'cash-session', page.locator('app-cash-session .keneya-form-control'));
  await expect(count).toHaveValue('800');
  // No close/approval/payment is submitted: these are local unsaved field values.
});

test('hospital payer-share reason is styled without recording a decision', async ({
  page,
  baseURL,
}, info) => {
  await signInAs(page, 'ROLE_ACCOUNTANT', baseURL!);
  await installLocalIconFonts(page);
  await page.route('**/api/billing-invoices/payer-shares?**', (route) =>
    route.fulfill({
      // Insurer claims PR 1 (G2): one page of the list, with the count and total of every page.
      json: {
        content: [
          {
            id: TEST_ID,
            invoiceId: TEST_ID,
            invoiceNumber: 'cdx-INV',
            patientName: 'cdx Patient',
            payerName: 'cdx Synthetic payer',
            amount: 750,
            status: 'OPEN',
          },
        ],
        page: 0,
        size: 25,
        totalElements: 1,
        totalPages: 1,
        totalAmount: 750,
      },
    }),
  );
  await page.goto('/billing/parts-assureurs');
  await expect(page).toHaveURL((url) => url.pathname === '/billing/parts-assureurs');
  await page.getByTestId(`payer-share-reject-${TEST_ID}`).click();
  await capture(page, info, 'payer-share-list', page.getByTestId(`payer-share-reason-${TEST_ID}`));
});

test('invoice payer-share reason is styled without recording a decision', async ({
  page,
  baseURL,
}, info) => {
  await signInAs(page, 'ROLE_ACCOUNTANT', baseURL!);

  await installLocalIconFonts(page);
  const invoice = {
    id: TEST_ID,
    invoiceNumber: 'cdx-INV',
    patientFullName: 'cdx Patient',
    patientName: 'cdx Patient',
    hospitalName: 'cdx Hospital',
    status: 'SENT',
    totalAmount: 1000,
    balanceDue: 1000,
    amountPaid: 0,
    invoiceDate: '2026-11-24',
    dueDate: '2026-11-24',
    insuranceCoverageAmount: 750,
    patientResponsibilityAmount: 250,
  };
  await page.route('**/api/billing-invoices/search?**', (route) =>
    route.fulfill({
      json: {
        content: [invoice],
        totalElements: 1,
        totalPages: 1,
        number: 0,
        size: 20,
      },
    }),
  );
  await page.route(`**/api/invoice-items/invoice/${TEST_ID}`, (r) => r.fulfill({ json: [] }));
  let shareReads = 0;
  await page.route(`**/api/billing-invoices/${TEST_ID}/payer-shares*`, (route) => {
    shareReads++;
    return route.fulfill({
      json: [
        {
          id: TEST_ID,
          payerName: 'cdx Synthetic payer',
          amount: 750,
          coverageYear: 2026,
          status: 'OPEN',
        },
      ],
    });
  });
  await page.route(`**/api/billing-invoices/${TEST_ID}`, (route) =>
    route.fulfill({ json: invoice }),
  );
  for (const section of ['items', 'payments', 'adjustments', 'refunds']) {
    await page.route(`**/api/billing-invoices/${TEST_ID}/${section}`, (route) =>
      route.fulfill({ json: [] }),
    );
  }
  await page.goto('/billing');
  await page.locator('.view-link').click();
  await expect.poll(() => shareReads).toBeGreaterThan(0);
  await page.getByTestId(`payer-share-reject-${TEST_ID}`).click();
  await capture(page, info, 'invoice-payer-share', page.locator('#payer-share-reject-reason'));
});

test('chart documents filter and nursing-note addendum retain the shared control design', async ({
  page,
  baseURL,
}, info) => {
  await signInAs(page, 'ROLE_DOCTOR', baseURL!);
  await installLocalIconFonts(page);
  await page.route(`**/api/patients/${TEST_ID}?**`, (r) =>
    r.fulfill({
      json: {
        id: TEST_ID,
        firstName: 'cdx',
        lastName: 'Patient',
        dateOfBirth: '1990-01-01',
        gender: 'FEMALE',
        active: true,
      },
    }),
  );
  await page.route(`**/api/patients/${TEST_ID}`, (r) =>
    r.fulfill({
      json: {
        id: TEST_ID,
        firstName: 'cdx',
        lastName: 'Patient',
        dateOfBirth: '1990-01-01',
        gender: 'FEMALE',
        active: true,
      },
    }),
  );
  await page.route(`**/api/patients/${TEST_ID}/documents?**`, (r) =>
    r.fulfill({ json: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 25 } }),
  );
  await page.route('**/api/nurse/notes?**', (r) =>
    r.fulfill({
      json: [
        {
          id: TEST_ID,
          patientId: TEST_ID,
          hospitalId: TEST_ID,
          authorName: 'cdx Nurse',
          title: 'cdx Synthetic note',
          template: 'DAR',
          narrative: 'cdx Synthetic observation',
          documentedAt: '2026-11-24T08:00:00',
          signedAt: '2026-11-24T08:00:00',
          addenda: [],
        },
      ],
    }),
  );
  await page.goto(`/patients/${TEST_ID}`);
  await page.getByTestId('documents-section-button').click();
  await capture(page, info, 'patient-documents', page.locator('app-documents-tab select'));
  await page.getByTestId('clinical-section-button').click();
  await page.getByTestId('nursing-notes-tab-button').click();
  await page.getByTestId('nursing-note-addendum-open').click();
  await capture(
    page,
    info,
    'nursing-note-addendum',
    page.getByTestId('nursing-note-addendum-text'),
  );
});

test('result read-back field keeps its clinical action unsent', async ({ page, baseURL }, info) => {
  await signInAs(page, 'ROLE_DOCTOR', baseURL!);
  await installLocalIconFonts(page);
  await page.route('**/api/me/results/review-queue', (r) =>
    r.fulfill({
      json: {
        data: [
          {
            id: TEST_ID,
            patientId: TEST_ID,
            patientName: 'cdx Patient',
            testName: 'cdx Synthetic test',
            resultValue: 'cdx',
            resultUnit: '',
            abnormalFlag: 'CRITICAL',
            resultedAt: '2026-11-24T08:00:00',
            readBackRequired: true,
            acknowledged: false,
          },
        ],
      },
    }),
  );
  await page.goto('/in-basket');
  await page.getByTestId('result-read-back').click();
  await capture(page, info, 'result-read-back', page.getByTestId('result-read-back-value'));
});

test('result-evolution unit selector is styled with separate synthetic units', async ({
  page,
  baseURL,
}, info) => {
  await signInAs(page, 'ROLE_LAB_SCIENTIST', baseURL!);
  await installLocalIconFonts(page);
  const result = {
    id: TEST_ID,
    labOrderId: TEST_ID,
    labOrderCode: 'cdx-LAB',
    patientId: TEST_ID,
    patientFullName: 'cdx Patient',
    hospitalName: 'cdx Hospital',
    labTestName: 'cdx Synthetic glucose',
    resultValue: '0.9',
    resultUnit: 'g/L',
    resultDate: '2026-11-24T08:00:00',
    referenceRanges: [],
    released: true,
    severityFlag: 'NORMAL',
    trendHistory: [
      { resultDate: '2026-11-23T08:00:00', resultValue: '90', resultUnit: 'mg/dL' },
      { resultDate: '2026-11-24T08:00:00', resultValue: '0.9', resultUnit: 'g/L' },
    ],
  };
  await page.route('**/api/lab-results?**', (r) =>
    r.fulfill({
      json: {
        data: {
          content: [result],
          totalElements: 1,
          totalPages: 1,
          number: 0,
          size: 200,
          last: true,
        },
      },
    }),
  );
  await page.route(`**/api/lab-results/${TEST_ID}`, (r) => r.fulfill({ json: result }));
  await page.goto('/lab-results');
  await page.getByRole('button', { name: fr.LAB_RESULTS.VIEW_DETAILS, exact: true }).click();
  await capture(page, info, 'result-evolution', page.locator('app-result-evolution select'));
});

test('pharmacy refund rejection reason is styled without deciding the refund', async ({
  page,
  baseURL,
}, info) => {
  await signInAs(page, 'ROLE_ACCOUNTANT', baseURL!);
  await installLocalIconFonts(page);
  await page.route('**/api/pharmacy/reconciliation/pharmacies', (r) =>
    r.fulfill({ json: { data: [] } }),
  );
  await page.route('**/api/pharmacy/refunds?**', (r) =>
    r.fulfill({
      json: {
        data: [
          {
            id: TEST_ID,
            pharmacyId: TEST_ID,
            returnTransactionId: TEST_ID,
            medicationName: 'cdx Synthetic medicine',
            quantity: 1,
            amount: 100,
            method: 'CASH',
            reason: 'cdx Synthetic return',
            status: 'REQUESTED',
            requestedBy: 'different-synthetic-user',
            requestedByName: 'cdx Requester',
            requestedAt: '2026-11-24T08:00:00',
            canDecide: true,
          },
        ],
      },
    }),
  );
  await page.goto('/pharmacy/reconciliation');
  await page
    .locator('.refund-card button')
    .filter({ hasText: /Refuser/ })
    .click();
  await capture(page, info, 'pharmacy-refund-rejection', page.locator('#refundRejectReason'));
});

test('imaging exam and staged/existing image captions use shared controls without clinical writes', async ({
  page,
  baseURL,
}, info) => {
  await signInAs(page, 'ROLE_RADIOLOGIST', baseURL!);
  await installLocalIconFonts(page);
  const order = {
    id: TEST_ID,
    patientId: TEST_ID,
    patientDisplayName: 'cdx Patient',
    patientMrn: 'cdx-MRN',
    hospitalId: TEST_ID,
    hospitalName: 'cdx Hospital',
    modality: 'CT',
    studyType: 'cdx Synthetic study',
    bodyRegion: 'cdx Region',
    laterality: 'NOT_APPLICABLE',
    priority: 'ROUTINE',
    status: 'IN_PROGRESS',
    clinicalQuestion: 'cdx Synthetic question',
    orderingProviderId: TEST_ID,
    orderingProviderName: 'cdx Clinician',
    orderedAt: '2026-11-24T08:00:00',
    checkedInAt: '2026-11-24T08:00:00',
    examStartedAt: '2026-11-24T08:00:00',
    examContrastAdministered: true,
    examContrastDetails: 'cdx Synthetic contrast',
    technicianMayPerform: true,
  };
  await page.route(/\/api\/imaging\/orders(?:\/hospital\/[^/?]+)?(?:\?.*)?$/, (r) =>
    r.fulfill({ json: [order] }),
  );
  await page.route(`**/api/imaging/results/order/${TEST_ID}/all`, (r) => r.fulfill({ json: [] }));
  await page.route(`**/api/imaging/orders/${TEST_ID}/images`, (r) =>
    r.fulfill({
      json: [
        {
          id: TEST_ID,
          imagingOrderId: TEST_ID,
          contentType: 'image/png',
          sizeBytes: 68,
          caption: 'cdx Synthetic caption',
          locked: false,
          deletable: true,
          captionEditable: true,
        },
      ],
    }),
  );
  await page.route(`**/api/imaging/images/${TEST_ID}/content`, (r) =>
    r.fulfill({
      contentType: 'image/png',
      body: Buffer.from(
        'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aX0YAAAAASUVORK5CYII=',
        'base64',
      ),
    }),
  );
  await page.route(`**/api/imaging/orders/${TEST_ID}/reading-context`, (r) =>
    r.fulfill({ json: { imagingOrderId: TEST_ID, patientId: TEST_ID, priorStudies: [] } }),
  );
  await page.goto('/imaging');
  await page.getByRole('button', { name: fr.IMAGING.VIEW_DETAILS, exact: true }).click();
  await capture(
    page,
    info,
    'imaging-exam-record',
    page.locator('app-imaging-exam-record .keneya-form-control'),
  );
  await page.getByTestId('imaging-images-input').setInputFiles({
    name: 'cdx-synthetic.png',
    mimeType: 'image/png',
    buffer: Buffer.from(
      'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aX0YAAAAASUVORK5CYII=',
      'base64',
    ),
  });
  await capture(
    page,
    info,
    'imaging-staged-caption',
    page.getByTestId('imaging-images-staged-caption-0'),
  );
  await page.getByTestId(`imaging-image-${TEST_ID}`).locator('.thumb-open').click();
  await capture(
    page,
    info,
    'imaging-viewer-caption',
    page.getByTestId('imaging-images-caption-edit'),
  );
});

test('nurse consciousness and controlled-dose witness controls remain unsaved', async ({
  page,
  baseURL,
}, info) => {
  await signInAs(page, 'ROLE_NURSE', baseURL!);
  await installLocalIconFonts(page);
  await page.route('**/api/nurse/vitals/due?**', (r) =>
    r.fulfill({
      json: [
        {
          id: TEST_ID,
          patientId: TEST_ID,
          patientName: 'cdx Patient',
          type: 'VITAL_SIGNS',
          dueTime: '2026-11-24T08:00:00',
          overdue: false,
        },
      ],
    }),
  );
  await page.route('**/api/nurse/medications/mar?**', (r) =>
    r.fulfill({
      json: [
        {
          id: TEST_ID,
          patientId: TEST_ID,
          patientName: 'cdx Patient',
          medication: 'cdx Synthetic controlled medicine',
          dose: 'cdx',
          route: 'cdx',
          dueTime: '2026-11-24T08:00:00',
          status: 'DUE',
          controlledSubstance: true,
          doseHistory: [],
        },
      ],
    }),
  );
  await page.route('**/api/nurse/medications/mar/witness-candidates', (r) =>
    r.fulfill({ json: [{ userId: 'different-synthetic-user', displayName: 'cdx Witness' }] }),
  );
  await page.goto('/nurse-station');
  await page.locator('.action-record').first().click();
  await capture(page, info, 'nurse-consciousness', page.locator('#vf-consciousness'));
  await page.keyboard.press('Escape');
  await page.locator('[data-focus-place="nurse-sections"] button').nth(1).click();
  await page.locator('.action-give').first().click();
  await capture(page, info, 'nurse-witness', page.getByTestId('witness-select'));
});

test('maternity admission and bed choices keep their scope and remain unsaved', async ({
  page,
  baseURL,
}, info) => {
  await signInAs(page, 'ROLE_DOCTOR', baseURL!);
  await installLocalIconFonts(page);
  const patient = {
    id: TEST_ID,
    firstName: 'cdx',
    lastName: 'Patient',
    dateOfBirth: '1990-01-01',
    gender: 'FEMALE',
    active: true,
  };
  await page.route('**/api/patients/search?**', (r) => r.fulfill({ json: [patient] }));
  await page.route('**/api/patients/lookup?**', (r) => r.fulfill({ json: [] }));
  await page.route(`**/api/patients/${TEST_ID}/labor/episodes?**`, (r) =>
    r.fulfill({
      json: [
        {
          id: TEST_ID,
          patientId: TEST_ID,
          patientName: 'cdx Patient',
          hospitalId: TEST_ID,
          admittedAt: '2026-11-24T08:00:00',
          status: 'ACTIVE',
          deliveryRecorded: false,
          entryCount: 0,
        },
      ],
    }),
  );
  await page.route(`**/api/patients/${TEST_ID}/labor/episodes/${TEST_ID}/entries`, (r) =>
    r.fulfill({ json: [] }),
  );
  await page.route('**/api/beds/available?**', (r) => r.fulfill({ json: [] }));
  let hasStay = false;
  await page.route(`**/api/admissions/patient/${TEST_ID}/current`, (r) =>
    r.fulfill({
      json: hasStay
        ? {
            id: TEST_ID,
            patientId: TEST_ID,
            patientName: 'cdx Patient',
            hospitalId: TEST_ID,
            roomBed: '',
            admissionType: 'LABOR_DELIVERY',
            status: 'ADMITTED',
            admissionDateTime: '2026-11-24T08:00:00',
          }
        : null,
    }),
  );
  for (const state of ['acuity', 'bed']) {
    hasStay = state === 'bed';
    await page.goto('/maternity');
    await page.getByRole('button', { name: fr.MATERNITY.LABOR_TAB, exact: true }).click();
    await page.locator('#labor-patient').fill('cdx');
    await page.locator('app-patient-picker [role="option"]').first().click();
    const control = page.getByTestId(
      state === 'bed' ? 'maternity-stay-bed-select' : 'maternity-stay-acuity',
    );
    await capture(page, info, 'maternity-stay-' + state, control);
  }
});
