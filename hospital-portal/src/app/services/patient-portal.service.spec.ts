import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import {
  LabResultSummary,
  PatientPortalService,
  PortalInvoice,
  formatTime,
  mapPortalInvoice,
} from './patient-portal.service';

/**
 * The portal used to decide whether a lab result was abnormal by parsing the
 * value and the reference range itself, ignoring the `status` the API grades
 * and sends. That was already lossy — an analyser flag on a test with no
 * configured range never showed — and it became a patient-safety defect once
 * the API stopped sending values for results the laboratory has not released:
 * every pending row parsed as "not abnormal" and the templates drew it as a
 * green check beside an empty value, which reads as "your test was normal".
 *
 * So these cases pin the mapping to the wire contract: `status` decides, and
 * an unreleased row is pending — never a result.
 */
describe('PatientPortalService lab results', () => {
  let service: PatientPortalService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [PatientPortalService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(PatientPortalService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  function labResults(rows: unknown[]): LabResultSummary[] {
    let received: LabResultSummary[] = [];
    service.getMyLabResults().subscribe((r) => (received = r));
    httpMock.expectOne((req) => req.url === '/me/patient/lab-results').flush({ data: rows });
    return received;
  }

  it('maps an unreleased result as pending, not as a normal result', () => {
    // Exactly what the API sends for an unreleased row: no value, no unit,
    // no reference range, no notes — only that a test is expected.
    const [lab] = labResults([
      {
        id: 'l1',
        testName: 'Hemoglobin',
        status: 'PENDING',
        released: false,
        collectedAt: '2026-09-20T08:00:00',
      },
    ]);

    expect(lab.isPending).toBeTrue();
    expect(lab.released).toBeFalse();
    expect(lab.isAbnormal).toBeFalse();
    expect(lab.result).toBe('');
  });

  it('treats released: false as pending even if the status says otherwise', () => {
    const [lab] = labResults([
      { id: 'l1', testName: 'Hemoglobin', status: 'NORMAL', released: false },
    ]);

    expect(lab.isPending).toBeTrue();
    expect(lab.isAbnormal).toBeFalse();
  });

  it('reads abnormality off the status, including a test with no reference range', () => {
    const rows = ['ABNORMAL', 'ABNORMAL_LOW', 'ABNORMAL_HIGH', 'CRITICAL'].map((status, i) => ({
      id: `l${i}`,
      testName: 'Potassium',
      value: '5.9',
      status,
      released: true,
    }));

    const mapped = labResults(rows);

    expect(mapped.map((l) => l.isAbnormal)).toEqual([true, true, true, true]);
    expect(mapped.map((l) => l.isPending)).toEqual([false, false, false, false]);
  });

  it('keeps a released normal result normal, value and range included', () => {
    const [lab] = labResults([
      {
        id: 'l1',
        testName: 'Hemoglobin',
        value: '13.7',
        unit: 'g/dL',
        referenceRange: '12 - 15.5 g/dL',
        status: 'NORMAL',
        released: true,
      },
    ]);

    expect(lab.isPending).toBeFalse();
    expect(lab.isAbnormal).toBeFalse();
    expect(lab.result).toBe('13.7');
    expect(lab.referenceRange).toBe('12 - 15.5 g/dL');
  });

  it('maps a released row nothing graded (units differ) as ungraded, not normal', () => {
    const [lab] = labResults([
      {
        id: 'l1',
        testName: 'Glucose',
        value: '5.4',
        unit: 'mmol/L',
        status: 'NORMAL',
        unitMismatch: true,
        released: true,
      },
    ]);

    expect(lab.isUngraded).toBeTrue();
    expect(lab.isAbnormal).toBeFalse();
  });

  it('D31: maps a released UNKNOWN row (no range, no flag) as not graded, never normal', () => {
    const [lab] = labResults([
      {
        id: 'u1',
        testName: 'Glucose',
        value: '28.5',
        unit: 'mmol/L',
        status: 'UNKNOWN',
        released: true,
      },
    ]);

    expect(lab.isUngraded).toBeTrue();
    expect(lab.isAbnormal).toBeFalse();
  });

  it('keeps an abnormal status abnormal, and a matching-unit normal graded, whatever unitMismatch says', () => {
    const mapped = labResults([
      {
        id: 'a',
        testName: 'K',
        value: '7.8',
        status: 'CRITICAL',
        unitMismatch: true,
        released: true,
      },
      {
        id: 'b',
        testName: 'K',
        value: '4.1',
        status: 'NORMAL',
        unitMismatch: false,
        released: true,
      },
      { id: 'c', testName: 'K', status: 'PENDING', unitMismatch: true, released: false },
    ]);

    expect(mapped.map((l) => l.isUngraded)).toEqual([false, false, false]);
    expect(mapped.map((l) => l.isAbnormal)).toEqual([true, false, false]);
  });

  it('does not call a value inside its range abnormal, nor one outside it normal', () => {
    // The old value-vs-range parsing decided both of these the other way:
    // it flagged the in-range row the analyser called high as normal, and
    // it could not see the out-of-range row at all without a parsable range.
    const mapped = labResults([
      {
        id: 'in-range-but-flagged',
        testName: 'Hemoglobin',
        value: '13.7',
        referenceRange: '12 - 15.5 g/dL',
        status: 'ABNORMAL_HIGH',
        released: true,
      },
      {
        id: 'no-range-configured',
        testName: 'Potassium',
        value: '7.8',
        referenceRange: '',
        status: 'CRITICAL',
        released: true,
      },
    ]);

    expect(mapped.map((l) => l.isAbnormal)).toEqual([true, true]);
  });
});

/**
 * GET /me/patient/care-team returns CareTeamDTO { primaryCare,
 * primaryCareHistory }. The portal declared `{ members }` for it and swallowed
 * every error as an empty team.
 */
describe('PatientPortalService care team', () => {
  let service: PatientPortalService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [PatientPortalService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(PatientPortalService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('passes the backend shape through', () => {
    const pcp = {
      id: 'pcp-1',
      hospitalId: 'h-1',
      hospitalName: null,
      doctorUserId: 'd-1',
      doctorDisplay: 'Dr Awa Traoré',
      startDate: '2026-01-15',
      endDate: null,
      current: true,
    };
    let received: unknown;
    service.getMyCareTeam().subscribe((t) => (received = t));
    httpMock
      .expectOne('/me/patient/care-team')
      .flush({ data: { primaryCare: pcp, primaryCareHistory: [pcp] } });
    expect(received).toEqual({ primaryCare: pcp, primaryCareHistory: [pcp], visitClinicians: [] });
  });

  it('normalises an absent current provider and history', () => {
    let received: unknown;
    service.getMyCareTeam().subscribe((t) => (received = t));
    httpMock.expectOne('/me/patient/care-team').flush({ data: {} });
    expect(received).toEqual({ primaryCare: null, primaryCareHistory: [], visitClinicians: [] });
  });

  it('lets a failure through instead of reporting an empty team', () => {
    let failed = false;
    service.getMyCareTeam().subscribe({ error: () => (failed = true) });
    httpMock
      .expectOne('/me/patient/care-team')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    expect(failed).toBeTrue();
  });
});

/**
 * The chat picker's two sources. `/me/patient/care-team` sends
 * `{ primaryCare, primaryCareHistory }` (the `CareTeamDTO` `getMyCareTeam`
 * returns), and only a user id can address a message — so
 * the mapping reads `doctorUserId` / `staffUserId`, drops anything without
 * one, and lets errors through so the picker can tell a failure from an
 * empty list.
 */
describe('PatientPortalService chat recipients', () => {
  let service: PatientPortalService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [PatientPortalService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(PatientPortalService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('maps the care team to its doctors’ user ids, current first, deduped', () => {
    let ids: string[] = [];
    service.getMyCareTeamClinicians().subscribe((r) => (ids = r.map((c) => c.userId)));
    httpMock.expectOne('/me/patient/care-team').flush({
      data: {
        primaryCare: { id: 'p2', doctorUserId: 'u2', doctorDisplay: 'B', current: true },
        primaryCareHistory: [
          { id: 'p1', doctorUserId: 'u1', doctorDisplay: 'A', current: false },
          { id: 'p2', doctorUserId: 'u2', doctorDisplay: 'B', current: true },
          { id: 'p3', doctorUserId: null, doctorDisplay: 'C' },
        ],
      },
    });
    expect(ids).toEqual(['u2', 'u1']);
  });

  it('adds the clinicians of open visits after the primary-care doctors, deduped (D115)', () => {
    let ids: string[] = [];
    service.getMyCareTeamClinicians().subscribe((r) => (ids = r.map((c) => c.userId)));
    httpMock.expectOne('/me/patient/care-team').flush({
      data: {
        primaryCare: { id: 'p1', doctorUserId: 'u1', doctorDisplay: 'A', current: true },
        primaryCareHistory: [],
        visitClinicians: [
          { userId: 'u3', displayName: 'C', hospitalName: 'H' },
          { userId: 'u1', displayName: 'A', hospitalName: 'H' },
        ],
      },
    });
    expect(ids).toEqual(['u1', 'u3']);
  });

  it('reads an empty care team as no one', () => {
    let result: unknown = null;
    service.getMyCareTeamClinicians().subscribe((r) => (result = r));
    httpMock.expectOne('/me/patient/care-team').flush({ data: {} });
    expect(result).toEqual([]);
  });

  it('maps appointments to their clinicians’ user ids, deduped, named only', () => {
    let result: unknown = null;
    service.getMyAppointmentClinicians().subscribe((r) => (result = r));
    httpMock.expectOne('/me/patient/appointments').flush({
      data: [
        { id: 'a1', staffId: 's1', staffUserId: 'u1', staffName: 'A', hospitalName: 'H' },
        { id: 'a2', staffId: 's1', staffUserId: 'u1', staffName: 'A', hospitalName: 'H' },
        { id: 'a3', staffId: 's2', staffUserId: null, staffName: 'No user' },
        { id: 'a4', staffId: 's3', staffUserId: 'u3', staffName: '' },
      ],
    });
    expect(result).toEqual([{ userId: 'u1', name: 'A', hospitalName: 'H' }]);
  });

  it('lets a failure through instead of reading it as an empty list', () => {
    let failed = false;
    service.getMyAppointmentClinicians().subscribe({ error: () => (failed = true) });
    httpMock
      .expectOne('/me/patient/appointments')
      .flush({}, { status: 500, statusText: 'Server Error' });
    expect(failed).toBeTrue();

    failed = false;
    service.getMyCareTeamClinicians().subscribe({ error: () => (failed = true) });
    httpMock
      .expectOne('/me/patient/care-team')
      .flush({}, { status: 500, statusText: 'Server Error' });
    expect(failed).toBeTrue();
  });
});

/**
 * QA D139: the billing page read `amount`, `balance` and `facility`, which
 * the API never sends (it sends BillingInvoiceResponseDTO: totalAmount,
 * balanceDue, hospitalName), so every invoice showed 0 and no Pay button.
 */
describe('PatientPortalService invoices', () => {
  let service: PatientPortalService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [PatientPortalService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(PatientPortalService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('maps the wire invoice to the amounts, balance and hospital the page shows', () => {
    let received: PortalInvoice[] = [];
    service.getMyInvoices().subscribe((r) => (received = r));
    httpMock.expectOne('/me/patient/billing/invoices').flush({
      success: true,
      data: {
        content: [
          {
            id: 'i1',
            invoiceNumber: 'HA-2026-000013',
            invoiceDate: '2026-10-01',
            dueDate: '2026-10-31',
            totalAmount: 2500,
            amountPaid: 1000,
            balanceDue: 1500,
            status: 'PARTIALLY_PAID',
            hospitalName: 'Hôpital A',
          },
        ],
      },
    });
    expect(received.length).toBe(1);
    expect(received[0].amount).toBe(2500);
    expect(received[0].paid).toBe(1000);
    expect(received[0].balance).toBe(1500);
    expect(received[0].facility).toBe('Hôpital A');
    expect(received[0].date).toBe('2026-10-01');
  });

  it('derives the balance when the API leaves balanceDue out', () => {
    expect(mapPortalInvoice({ id: 'x', totalAmount: 900, amountPaid: 300 }).balance).toBe(600);
    // QA D202: a cancelled invoice owes nothing, whatever the API sent.
    expect(
      mapPortalInvoice({ id: 'y', totalAmount: 5000, balanceDue: 5000, status: 'CANCELLED' })
        .balance,
    ).toBe(0);
  });
});

describe('formatTime (QA D255)', () => {
  it('writes a French time on the 24-hour clock, never "AM/PM"', () => {
    const fr = formatTime('09:00:00', 'fr-FR');
    expect(fr).toBe('09:00');
    expect(formatTime('14:30', 'fr-FR')).toBe('14:30');
  });

  it('keeps the 12-hour clock for English', () => {
    expect(formatTime('14:30', 'en-US')).toMatch(/2:30\sPM/);
  });

  it('passes through what it cannot parse', () => {
    expect(formatTime('', 'fr-FR')).toBe('');
    expect(formatTime('later', 'fr-FR')).toBe('later');
  });
});
