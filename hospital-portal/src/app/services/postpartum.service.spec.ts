import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { PostpartumService } from './postpartum.service';
import { PrenatalService } from './prenatal.service';

describe('PostpartumService', () => {
  let service: PostpartumService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), PostpartumService],
    });
    service = TestBed.inject(PostpartumService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('POSTs an observation under the patient path', () => {
    service.createObservation('p1', { painScore: 3 }).subscribe((o) => expect(o.id).toBe('obs1'));
    const req = httpMock.expectOne('/patients/p1/postpartum/observations');
    expect(req.request.method).toBe('POST');
    req.flush({ id: 'obs1', patientId: 'p1' });
  });

  it('GETs recent observations with a limit', () => {
    service.recentObservations('p1', 5).subscribe((list) => expect(list.length).toBe(1));
    httpMock
      .expectOne('/patients/p1/postpartum/observations/recent?limit=5')
      .flush([{ id: 'obs1', patientId: 'p1' }]);
  });

  it('GETs the monitoring schedule', () => {
    service.schedule('p1').subscribe((s) => expect(s.overdue).toBeTrue());
    httpMock.expectOne('/patients/p1/postpartum/schedule').flush({
      phase: 'IMMEDIATE_RECOVERY',
      immediateWindowComplete: false,
      immediateChecksCompleted: 1,
      immediateCheckTarget: 4,
      overdue: true,
    });
  });

  it('POSTs a newborn assessment', () => {
    service
      .createNewbornAssessment('p1', { apgarOneMinute: 8, followUpActions: ['NICU_CONSULT'] })
      .subscribe((a) => expect(a.id).toBe('nb1'));
    const req = httpMock.expectOne('/patients/p1/postpartum/newborn-assessments');
    expect(req.request.body.followUpActions).toEqual(['NICU_CONSULT']);
    req.flush({ id: 'nb1', patientId: 'p1' });
  });
});

describe('PrenatalService', () => {
  let service: PrenatalService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), PrenatalService],
    });
    service = TestBed.inject(PrenatalService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('POSTs a schedule generation request', () => {
    service
      .schedule({ patientId: 'p1', hospitalId: 'h1', lastMenstrualPeriodDate: '2026-05-01' })
      .subscribe((s) => expect(s.currentGestationalWeek).toBe(15));
    const req = httpMock.expectOne('/prenatal/schedule');
    expect(req.request.method).toBe('POST');
    req.flush({
      patientId: 'p1',
      hospitalId: 'h1',
      currentGestationalWeek: 15,
      highRisk: false,
      recommendations: [],
      existingAppointments: [],
      alerts: [],
    });
  });

  it('PUTs a reschedule request', () => {
    service
      .reschedule({ appointmentId: 'a1', newAppointmentDate: '2026-09-01', newStartTime: '10:00' })
      .subscribe();
    const req = httpMock.expectOne('/prenatal/appointments/reschedule');
    expect(req.request.method).toBe('PUT');
    req.flush({ id: 'a1' });
  });

  it('POSTs a reminder (202, empty body response)', () => {
    service.sendReminder('a1', 2, 'See you soon').subscribe();
    const req = httpMock.expectOne('/prenatal/reminders');
    expect(req.request.body).toEqual({
      appointmentId: 'a1',
      daysBefore: 2,
      customMessage: 'See you soon',
    });
    req.flush(null, { status: 202, statusText: 'Accepted' });
  });
});
