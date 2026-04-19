import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import {
  RecordSharingService,
  PatientRecord,
  RecordShareResult,
  ConsentGrantRequest,
  PatientConsentResponse,
} from './record-sharing.service';

describe('RecordSharingService', () => {
  let service: RecordSharingService;
  let httpMock: HttpTestingController;

  const patientId = 'p1';
  const fromHospitalId = 'h1';
  const toHospitalId = 'h2';

  const mockPatientRecord: PatientRecord = {
    patientId,
    firstName: 'Jane',
    lastName: 'Doe',
    encounters: [],
    treatments: [],
    labOrders: [],
    labResults: [],
    allergiesDetailed: [],
    prescriptions: [],
    insurances: [],
    problems: [],
    surgicalHistory: [],
    advanceDirectives: [],
    encounterHistory: [],
  };

  const mockShareResult: RecordShareResult = {
    shareScope: 'SAME_HOSPITAL',
    shareScopeLabel: 'Same Hospital',
    resolvedFromHospitalId: fromHospitalId,
    resolvedFromHospitalName: 'Hospital A',
    requestingHospitalId: fromHospitalId,
    requestingHospitalName: 'Hospital A',
    organizationName: null,
    organizationId: null,
    consentId: null,
    consentGrantedAt: null,
    consentExpiresAt: null,
    consentPurpose: null,
    consentActive: false,
    resolvedAt: '2025-01-01T00:00:00Z',
    patientRecord: mockPatientRecord,
  };

  const mockConsent: PatientConsentResponse = {
    id: 'c1',
    patient: { id: patientId, firstName: 'Jane', lastName: 'Doe' },
    fromHospital: { id: fromHospitalId, name: 'Hospital A' },
    toHospital: { id: toHospitalId, name: 'Hospital B' },
    consentGiven: true,
    consentTimestamp: '2025-01-01T00:00:00Z',
    consentExpiration: '2026-01-01T00:00:00Z',
    purpose: 'Treatment',
    consentType: 'TREATMENT',
    scope: 'ENCOUNTERS,PRESCRIPTIONS',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [RecordSharingService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(RecordSharingService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  // ── resolveAndShare ────────────────────────────────────────────────────

  describe('resolveAndShare', () => {
    it('should GET /records/resolve with query params', () => {
      service.resolveAndShare(patientId, fromHospitalId).subscribe((res) => {
        expect(res.shareScope).toBe('SAME_HOSPITAL');
        expect(res.patientRecord.firstName).toBe('Jane');
      });

      const req = httpMock.expectOne(
        (r) => r.url === '/records/resolve' && r.params.get('patientId') === patientId,
      );
      expect(req.request.method).toBe('GET');
      expect(req.request.params.get('requestingHospitalId')).toBe(fromHospitalId);
      req.flush(mockShareResult);
    });
  });

  // ── getPatientRecord ──────────────────────────────────────────────────

  describe('getPatientRecord', () => {
    it('should POST /records/share with body', () => {
      service.getPatientRecord(patientId, fromHospitalId, toHospitalId).subscribe((res) => {
        expect(res.patientId).toBe(patientId);
      });

      const req = httpMock.expectOne('/records/share');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ patientId, fromHospitalId, toHospitalId });
      req.flush(mockPatientRecord);
    });
  });

  // ── grantConsent ──────────────────────────────────────────────────────

  describe('grantConsent', () => {
    it('should POST /patient-consents/grant', () => {
      const grantReq: ConsentGrantRequest = {
        patientId,
        fromHospitalId,
        toHospitalId,
        purpose: 'Treatment',
        consentType: 'TREATMENT',
        scope: 'ENCOUNTERS',
      };

      service.grantConsent(grantReq).subscribe((res) => {
        expect(res.id).toBe('c1');
      });

      const req = httpMock.expectOne('/patient-consents/grant');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(grantReq);
      req.flush(mockConsent);
    });
  });

  // ── revokeConsent ─────────────────────────────────────────────────────

  describe('revokeConsent', () => {
    it('should POST /patient-consents/revoke with query params', () => {
      service.revokeConsent(patientId, fromHospitalId, toHospitalId).subscribe();

      const req = httpMock.expectOne(
        (r) => r.url === '/patient-consents/revoke' && r.params.get('patientId') === patientId,
      );
      expect(req.request.method).toBe('POST');
      expect(req.request.params.get('fromHospitalId')).toBe(fromHospitalId);
      expect(req.request.params.get('toHospitalId')).toBe(toHospitalId);
      expect(req.request.body).toBeNull();
      req.flush(null);
    });
  });

  // ── exportRecord ──────────────────────────────────────────────────────

  describe('exportRecord', () => {
    it('should POST /records/export as blob with query params', () => {
      service.exportRecord(patientId, fromHospitalId, toHospitalId, 'pdf').subscribe((res) => {
        expect(res).toBeTruthy();
      });

      const req = httpMock.expectOne(
        (r) =>
          r.url === '/records/export' &&
          r.params.get('format') === 'pdf' &&
          r.params.get('patientId') === patientId,
      );
      expect(req.request.method).toBe('POST');
      expect(req.request.responseType).toBe('blob');
      req.flush(new Blob(['test'], { type: 'application/pdf' }));
    });

    it('should support csv format', () => {
      service.exportRecord(patientId, fromHospitalId, toHospitalId, 'csv').subscribe();

      const req = httpMock.expectOne(
        (r) => r.url === '/records/export' && r.params.get('format') === 'csv',
      );
      expect(req.request.method).toBe('POST');
      req.flush(new Blob(['col1,col2'], { type: 'text/csv' }));
    });
  });

  // ── listConsents ──────────────────────────────────────────────────────

  describe('listConsents', () => {
    it('should GET /patient-consents with pagination params', () => {
      const mockPage = { content: [mockConsent], totalElements: 1, totalPages: 1 };

      service.listConsents({ page: 0, size: 10 }).subscribe((res) => {
        expect(res.totalElements).toBe(1);
        expect(res.content[0].id).toBe('c1');
      });

      const req = httpMock.expectOne(
        (r) =>
          r.url === '/patient-consents' &&
          r.params.get('page') === '0' &&
          r.params.get('size') === '10',
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockPage);
    });

    it('should GET /patient-consents without params when none provided', () => {
      const mockPage = { content: [], totalElements: 0, totalPages: 0 };

      service.listConsents().subscribe((res) => {
        expect(res.totalElements).toBe(0);
      });

      const req = httpMock.expectOne('/patient-consents');
      expect(req.request.method).toBe('GET');
      req.flush(mockPage);
    });

    it('should GET /patient-consents/to-hospital/:id when toHospitalId is provided', () => {
      const mockPage = { content: [mockConsent], totalElements: 1, totalPages: 1 };

      service.listConsents({ page: 0, size: 10, toHospitalId: 'h2' }).subscribe((res) => {
        expect(res.content[0].id).toBe('c1');
      });

      const req = httpMock.expectOne(
        (r) =>
          r.url === '/patient-consents/to-hospital/h2' &&
          r.params.get('page') === '0' &&
          r.params.get('size') === '10',
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockPage);
    });
  });
});
