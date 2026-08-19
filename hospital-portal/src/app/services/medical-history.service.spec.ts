import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { MedicalHistoryService } from './medical-history.service';

describe('MedicalHistoryService', () => {
  let service: MedicalHistoryService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), MedicalHistoryService],
    });
    service = TestBed.inject(MedicalHistoryService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('POSTs a social history (create supersedes prior versions)', () => {
    service
      .createSocialHistory({ patientId: 'p1', hospitalId: 'h1', recordedDate: '2026-08-19' })
      .subscribe();
    const req = httpMock.expectOne('/medical-history/social');
    expect(req.request.method).toBe('POST');
    req.flush({ id: 's1', patientId: 'p1', versionNumber: 2 });
  });

  it('GETs the current social history and tolerates an empty body', () => {
    service.currentSocialHistory('p1').subscribe((current) => expect(current).toBeNull());
    httpMock.expectOne('/medical-history/patient/p1/social/current').flush(null);
  });

  it('PUTs a full-replace family history update', () => {
    service
      .updateFamilyHistory('f1', {
        patientId: 'p1',
        hospitalId: 'h1',
        recordedDate: '2026-08-19',
        relationship: 'Mother',
        conditionDisplay: 'Breast cancer',
        isCancer: true,
      })
      .subscribe();
    const req = httpMock.expectOne('/medical-history/family/f1');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.isCancer).toBeTrue();
    req.flush({ id: 'f1', patientId: 'p1' });
  });

  it('GETs the screening-needed family worklist', () => {
    service.screeningNeededFamilyHistory('p1').subscribe((list) => expect(list.length).toBe(1));
    httpMock
      .expectOne('/medical-history/patient/p1/family/screening-needed')
      .flush([{ id: 'f1', patientId: 'p1', screeningRecommended: true }]);
  });

  it('POSTs an immunization with uppercase COMPLETED status', () => {
    service
      .createImmunization({
        patientId: 'p1',
        hospitalId: 'h1',
        vaccineCode: '208',
        vaccineDisplay: 'COVID-19 mRNA',
        administrationDate: '2026-08-19',
        status: 'COMPLETED',
      })
      .subscribe();
    const req = httpMock.expectOne('/medical-history/immunizations');
    expect(req.request.body.status).toBe('COMPLETED');
    req.flush({ id: 'i1', patientId: 'p1' });
  });

  it('GETs upcoming immunizations with both required bounds', () => {
    service.upcomingImmunizations('p1', '2026-08-19', '2026-09-19').subscribe();
    const req = httpMock.expectOne(
      '/medical-history/patient/p1/immunizations/upcoming?startDate=2026-08-19&endDate=2026-09-19',
    );
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('PATCHes mark-reminder-sent (empty response body)', () => {
    service.markReminderSent('i1').subscribe();
    const req = httpMock.expectOne('/medical-history/immunizations/i1/mark-reminder-sent');
    expect(req.request.method).toBe('PATCH');
    req.flush(null);
  });
});
