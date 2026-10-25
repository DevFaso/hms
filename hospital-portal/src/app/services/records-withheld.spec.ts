import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { Observable } from 'rxjs';
import { PatientService } from './patient.service';
import { VitalSignService } from './vital-sign.service';
import { EncounterService } from './encounter.service';

/**
 * QA D504: every patient clinical list the chart reads carries the server's
 * X-Records-Withheld signal through to the caller, so a list narrowed by the
 * patient's sharing opt-out never reads as the whole record.
 */
describe('Clinical list reads — withheld signal (QA D504)', () => {
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withXhr()), provideHttpClientTesting()],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  const WITHHELD = { headers: { 'X-Records-Withheld': 'OTHER_HOSPITALS' } };

  function read(
    call: () => Observable<{ withheld: boolean }>,
    url: string,
    body: object,
    withheld: boolean,
  ): boolean | undefined {
    let seen: boolean | undefined;
    call().subscribe((r) => (seen = r.withheld));
    httpMock.expectOne(url).flush(body, withheld ? WITHHELD : {});
    return seen;
  }

  const cases: {
    name: string;
    call: () => Observable<{ withheld: boolean }>;
    url: string;
    body: object;
  }[] = [
    {
      name: 'problems',
      call: () => TestBed.inject(PatientService).listDiagnoses('p1'),
      url: '/patients/p1/diagnoses',
      body: [],
    },
    {
      name: 'chart updates',
      call: () => TestBed.inject(PatientService).listChartUpdates('p1'),
      url: '/patients/p1/chart-updates',
      body: { content: [], totalElements: 0 },
    },
    {
      name: 'labs',
      call: () => TestBed.inject(PatientService).listLabResults('p1'),
      url: '/patients/p1/lab-results',
      body: [],
    },
    {
      name: 'vitals',
      call: () => TestBed.inject(VitalSignService).getRecent('p1'),
      url: '/patients/p1/vitals/recent',
      body: [],
    },
    {
      name: 'encounters',
      call: () => TestBed.inject(EncounterService).listForPatient('p1'),
      url: '/encounters?patientId=p1',
      body: { content: [] },
    },
  ];

  for (const c of cases) {
    it(`${c.name}: reports withheld only when the server says so`, () => {
      expect(read(c.call, c.url, c.body, true)).toBeTrue();
      expect(read(c.call, c.url, c.body, false)).toBeFalse();
    });
  }
});
