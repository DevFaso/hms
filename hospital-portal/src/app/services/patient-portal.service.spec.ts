import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { LabResultSummary, PatientPortalService } from './patient-portal.service';

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
