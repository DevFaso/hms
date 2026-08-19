import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { RegistrationService } from './registration.service';
import { PatientInsuranceService } from './patient-insurance.service';

describe('RegistrationService', () => {
  let service: RegistrationService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), RegistrationService],
    });
    service = TestBed.inject(RegistrationService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('POSTs a new registration', () => {
    service.create({ patientId: 'p1', hospitalId: 'h1', stayStatus: 'ADMITTED' }).subscribe();
    const req = httpMock.expectOne('/registrations');
    expect(req.request.method).toBe('POST');
    req.flush({ id: 'reg1', active: true });
  });

  it('GETs the list as a BARE array (no Page envelope)', () => {
    service
      .list({ hospitalId: 'h1', active: true, size: 200 })
      .subscribe((list) => expect(list.length).toBe(1));
    const req = httpMock.expectOne('/registrations?hospitalId=h1&active=true&size=200');
    expect(req.request.method).toBe('GET');
    req.flush([{ id: 'reg1', active: true }]);
  });

  it('GETs the multi-hospital summary', () => {
    service.multiHospital().subscribe((rows) => expect(rows.length).toBe(2));
    httpMock.expectOne('/registrations/multi-hospital').flush([
      { patientId: 'p1', hospitalId: 'h1' },
      { patientId: 'p1', hospitalId: 'h2' },
    ]);
  });

  it('PUTs a full-replace update', () => {
    service
      .update('reg1', { patientId: 'p1', active: false, stayStatus: 'DISCHARGED' })
      .subscribe();
    const req = httpMock.expectOne('/registrations/reg1');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.active).toBeFalse();
    req.flush({ id: 'reg1', active: false });
  });

  it('DELETEs (hard delete)', () => {
    service.delete('reg1').subscribe();
    const req = httpMock.expectOne('/registrations/reg1');
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });
  });
});

describe('PatientInsuranceService', () => {
  let service: PatientInsuranceService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), PatientInsuranceService],
    });
    service = TestBed.inject(PatientInsuranceService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('GETs the patient policy list', () => {
    service.forPatient('p1').subscribe((list) => expect(list.length).toBe(1));
    httpMock
      .expectOne('/patient-insurances/patient/p1')
      .flush([{ id: 'ins1', patientId: 'p1', primary: true }]);
  });

  it('PUTs a link request by natural key', () => {
    service
      .link({ patientId: 'p1', payerCode: 'NHIS', policyNumber: 'POL-9', primary: true })
      .subscribe();
    const req = httpMock.expectOne('/patient-insurances/link');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.primary).toBeTrue();
    req.flush({ id: 'ins1', patientId: 'p1', primary: true });
  });

  it('PUTs an update to a policy', () => {
    service
      .update('ins1', { patientId: 'p1', providerName: 'NHIS Ghana', policyNumber: 'POL-9' })
      .subscribe();
    const req = httpMock.expectOne('/patient-insurances/ins1');
    expect(req.request.method).toBe('PUT');
    req.flush({ id: 'ins1', patientId: 'p1' });
  });

  it('DELETEs expecting a text/plain body', () => {
    service.delete('ins1').subscribe((message) => expect(message).toContain('deleted'));
    const req = httpMock.expectOne('/patient-insurances/ins1');
    expect(req.request.method).toBe('DELETE');
    expect(req.request.responseType).toBe('text');
    req.flush('Insurance deleted successfully');
  });
});
