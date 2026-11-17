import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { of } from 'rxjs';

import { PatientResponse, PatientService, searchPatientsByTerm } from './patient.service';

const awa = { id: 'p1', firstName: 'Awa', lastName: 'Kaboré' } as PatientResponse;
const other = { id: 'p2', firstName: 'Ali', lastName: 'Ouédraogo' } as PatientResponse;

/**
 * QA D1274 (D1253 sibling, round 27): a birth date or an e-mail typed in a
 * patient search box is asked of the search that answers it by that field.
 */
describe('PatientService term search (QA D1274)', () => {
  let service: PatientService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withXhr()), provideHttpClientTesting(), PatientService],
    });
    service = TestBed.inject(PatientService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('list(): a day-first birth date also searches by birth date, ISO, and merges once', () => {
    let got: PatientResponse[] = [];
    service.list('h1', '15/03/2020').subscribe((r) => (got = r));
    http
      .expectOne((r) => r.url === '/patients' && r.params.get('search') === '15/03/2020')
      .flush([]);
    const byDob = http.expectOne((r) => r.url === '/patients/search');
    expect(byDob.request.params.get('dob')).toBe('2020-03-15');
    expect(byDob.request.params.get('hospitalId')).toBe('h1');
    expect(byDob.request.params.get('includeInactive')).toBe('true');
    expect(byDob.request.params.has('name')).toBeFalse();
    byDob.flush([awa]);
    expect(got.map((p) => p.id)).toEqual(['p1']);
  });

  it('list(): an e-mail, or a piece of one, also searches by e-mail', () => {
    let got: PatientResponse[] = [];
    service.list('h1', 'awa.kabore').subscribe((r) => (got = r));
    http.expectOne((r) => r.url === '/patients').flush([awa]);
    const byEmail = http.expectOne((r) => r.url === '/patients/search');
    expect(byEmail.request.params.get('email')).toBe('awa.kabore');
    byEmail.flush([awa, other]);
    expect(got.map((p) => p.id)).toEqual(['p1', 'p2']);
  });

  it('list(): a name or a phone number is one request, as before', () => {
    service.list('h1', 'Awa').subscribe();
    service.list('h1', '70002781').subscribe();
    http.expectOne((r) => r.url === '/patients' && r.params.get('search') === 'Awa').flush([]);
    http.expectOne((r) => r.url === '/patients' && r.params.get('search') === '70002781').flush([]);
    http.expectNone('/patients/search');
  });

  it('list(): a failed birth-date search still shows what the list found', () => {
    let got: PatientResponse[] = [];
    service.list('h1', '2020-03-15').subscribe((r) => (got = r));
    http.expectOne((r) => r.url === '/patients').flush([awa]);
    http
      .expectOne((r) => r.url === '/patients/search')
      .flush('x', { status: 500, statusText: 'Server Error' });
    expect(got.map((p) => p.id)).toEqual(['p1']);
  });

  it('searchPatientsByTerm: an ISO or day-first date is searched by birth date only', () => {
    const search = jasmine.createSpy('search').and.returnValue(of([awa]));
    for (const typed of ['2020-03-15', '15-03-2020']) {
      search.calls.reset();
      searchPatientsByTerm({ search }, typed, { hospitalId: 'h1', size: 20 }).subscribe();
      expect(search).toHaveBeenCalledOnceWith(
        jasmine.objectContaining({ dob: '2020-03-15', hospitalId: 'h1', size: 20 }),
      );
      expect(search.calls.mostRecent().args[0].name).toBeUndefined();
    }
  });

  it('searchPatientsByTerm: eight digits are searched as a phone (name box) and as a date', () => {
    const search = jasmine.createSpy('search').and.returnValue(of([awa]));
    let got: PatientResponse[] = [];
    searchPatientsByTerm({ search }, '15032020', { hospitalId: 'h1' }).subscribe((r) => (got = r));
    expect(search).toHaveBeenCalledWith(jasmine.objectContaining({ name: '15032020' }));
    expect(search).toHaveBeenCalledWith(jasmine.objectContaining({ dob: '2020-03-15' }));
    expect(got.map((p) => p.id)).toEqual(['p1']);
  });

  it('searchPatientsByTerm: an e-mail is searched as a name and as an e-mail', () => {
    const search = jasmine.createSpy('search').and.returnValue(of([]));
    searchPatientsByTerm({ search }, 'awa.kabore@example.test', {}).subscribe();
    expect(search).toHaveBeenCalledWith(
      jasmine.objectContaining({ name: 'awa.kabore@example.test' }),
    );
    expect(search).toHaveBeenCalledWith(
      jasmine.objectContaining({ email: 'awa.kabore@example.test' }),
    );
  });

  it('search(): sends dob when given', () => {
    service.search({ dob: '2020-03-15', hospitalId: 'h1' }).subscribe();
    const req = http.expectOne((r) => r.url === '/patients/search');
    expect(req.request.params.get('dob')).toBe('2020-03-15');
    req.flush([]);
  });
});
