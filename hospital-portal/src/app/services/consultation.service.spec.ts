import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { ConsultationService, ConsultationRequest } from './consultation.service';

describe('ConsultationService', () => {
  let service: ConsultationService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [ConsultationService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ConsultationService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('should POST to /consultations with patientId and hospitalId from payload', () => {
    const req: ConsultationRequest = {
      patientId: 'patient-123',
      hospitalId: 'hospital-456',
      consultationType: 'OUTPATIENT_CONSULT',
      specialtyRequested: 'cardiology',
      reasonForConsult: 'Chest pain',
      urgency: 'ROUTINE',
      clinicalQuestion: 'Rule out ACS',
    };

    service.create(req).subscribe();

    const httpReq = httpMock.expectOne('/consultations');
    expect(httpReq.request.method).toBe('POST');
    expect(httpReq.request.body.patientId).toBe(req.patientId);
    expect(httpReq.request.body.hospitalId).toBe(req.hospitalId);
    httpReq.flush({ id: 'consult-1' });
  });
});
