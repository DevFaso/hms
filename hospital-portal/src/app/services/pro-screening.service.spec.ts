import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { ProScreeningService } from './pro-screening.service';

describe('ProScreeningService', () => {
  let service: ProScreeningService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), ProScreeningService],
    });
    service = TestBed.inject(ProScreeningService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lists the instruments that have text loaded', () => {
    service.instruments().subscribe((list) => expect(list.length).toBe(1));
    const req = httpMock.expectOne('/pro-instruments');
    expect(req.request.method).toBe('GET');
    req.flush([{ code: 'EPDS', name: 'EPDS', languages: ['en'] }]);
  });

  it('asks for an instrument in a language, encoding the code', () => {
    service.instrument('A/B', 'fr').subscribe();
    const req = httpMock.expectOne('/pro-instruments/A%2FB?language=fr');
    expect(req.request.method).toBe('GET');
    req.flush({});
  });

  it('omits the language param when none is chosen', () => {
    service.instrument('EPDS').subscribe();
    httpMock.expectOne('/pro-instruments/EPDS').flush({});
  });

  it('POSTs a staff-administered response under the patient', () => {
    service
      .record('p1', { instrumentCode: 'EPDS', language: 'en', answers: { 1: 0, 2: 3 } })
      .subscribe();
    const req = httpMock.expectOne('/patients/p1/pro-responses');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.answers).toEqual({ 1: 0, 2: 3 });
    req.flush({ id: 'r1' });
  });

  it('GETs history with a default limit and an optional instrument filter', () => {
    service.history('p1').subscribe();
    httpMock.expectOne('/patients/p1/pro-responses?limit=20').flush([]);

    service.history('p1', 'EPDS', 5).subscribe();
    httpMock.expectOne('/patients/p1/pro-responses?limit=5&instrument=EPDS').flush([]);
  });

  it('acknowledges with a trimmed note, or null when the note is blank', () => {
    service.acknowledge('p1', 'r1', '  called the mother  ').subscribe();
    const withNote = httpMock.expectOne('/patients/p1/pro-responses/r1/acknowledge');
    expect(withNote.request.method).toBe('POST');
    expect(withNote.request.body).toEqual({ actionTaken: 'called the mother' });
    withNote.flush({ id: 'r1' });

    service.acknowledge('p1', 'r1', '   ').subscribe();
    const blank = httpMock.expectOne('/patients/p1/pro-responses/r1/acknowledge');
    expect(blank.request.body).toEqual({ actionTaken: null });
    blank.flush({ id: 'r1' });
  });

  it('uses the /me/patient surface for self-report', () => {
    service.myScreenings().subscribe();
    httpMock.expectOne('/me/patient/pro-screenings').flush({ available: [], history: [] });

    service.myInstrument('EPDS', 'fr').subscribe();
    httpMock.expectOne('/me/patient/pro-instruments/EPDS?language=fr').flush({});

    service.submitMine({ instrumentCode: 'EPDS', answers: { 1: 1 } }).subscribe();
    const submit = httpMock.expectOne('/me/patient/pro-screenings');
    expect(submit.request.method).toBe('POST');
    expect(submit.request.body.instrumentCode).toBe('EPDS');
    submit.flush({ id: 'r2' });
  });
});
