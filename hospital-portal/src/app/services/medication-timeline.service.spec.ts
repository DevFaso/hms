import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { MedicationTimelineService } from './medication-timeline.service';

describe('MedicationTimelineService', () => {
  let service: MedicationTimelineService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), MedicationTimelineService],
    });
    service = TestBed.inject(MedicationTimelineService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('GETs the timeline with required hospitalId and optional range', () => {
    service
      .timeline('p1', 'h1', '2026-01-01', '2026-08-19')
      .subscribe((t) => expect(t.totalMedications).toBe(3));
    const req = httpMock.expectOne(
      '/medication-history/patient/p1/timeline?hospitalId=h1&startDate=2026-01-01&endDate=2026-08-19',
    );
    expect(req.request.method).toBe('GET');
    req.flush({
      timeline: [],
      totalMedications: 3,
      activeMedications: 2,
      controlledSubstances: 0,
      medicationsWithOverlaps: 1,
      medicationsWithInteractions: 0,
      polypharmacyDetected: false,
    });
  });

  it('omits range params when not provided', () => {
    service.timeline('p1', 'h1').subscribe();
    const req = httpMock.expectOne('/medication-history/patient/p1/timeline?hospitalId=h1');
    req.flush({
      timeline: [],
      totalMedications: 0,
      activeMedications: 0,
      controlledSubstances: 0,
      medicationsWithOverlaps: 0,
      medicationsWithInteractions: 0,
      polypharmacyDetected: false,
    });
  });

  it('POSTs a new pharmacy fill', () => {
    service
      .createFill({
        patientId: 'p1',
        hospitalId: 'h1',
        medicationName: 'Amoxicillin',
        fillDate: '2026-08-19',
        refillNumber: 0,
      })
      .subscribe((f) => expect(f.id).toBe('fill1'));
    const req = httpMock.expectOne('/medication-history/pharmacy-fills');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.medicationName).toBe('Amoxicillin');
    req.flush({ id: 'fill1', patientId: 'p1', hospitalId: 'h1' });
  });

  it('PUTs a full-replace fill update', () => {
    service
      .updateFill('fill1', {
        patientId: 'p1',
        hospitalId: 'h1',
        medicationName: 'Amoxicillin',
        fillDate: '2026-08-19',
        daysSupply: 10,
      })
      .subscribe();
    const req = httpMock.expectOne('/medication-history/pharmacy-fills/fill1');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.daysSupply).toBe(10);
    req.flush({ id: 'fill1', patientId: 'p1', hospitalId: 'h1' });
  });

  it('GETs patient fills with the required hospitalId param', () => {
    service.fillsForPatient('p1', 'h1').subscribe((list) => expect(list.length).toBe(1));
    const req = httpMock.expectOne('/medication-history/patient/p1/pharmacy-fills?hospitalId=h1');
    expect(req.request.method).toBe('GET');
    req.flush([{ id: 'fill1', patientId: 'p1', hospitalId: 'h1' }]);
  });
});
