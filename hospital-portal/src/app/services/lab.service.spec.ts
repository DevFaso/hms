import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { LabService, LabResultRequest, LabTestDefinitionRequest } from './lab.service';

describe('LabService', () => {
  let service: LabService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [LabService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(LabService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  // ── Lab Result CRUD ──────────────────────────────────────────────────

  describe('createResult', () => {
    it('should POST to /lab-results', () => {
      const req: LabResultRequest = {
        labOrderId: 'order-1',
        assignmentId: 'assign-1',
        patientId: 'patient-1',
        resultValue: '5.2',
        resultUnit: 'mg/dL',
        resultDate: '2025-01-15T10:00',
        notes: 'Test note',
      };
      const mockResp = { id: 'result-1', resultValue: '5.2' };

      service.createResult(req).subscribe((res) => {
        expect(res.resultValue).toBe('5.2');
      });

      const httpReq = httpMock.expectOne('/lab-results');
      expect(httpReq.request.method).toBe('POST');
      expect(httpReq.request.body).toEqual(req);
      httpReq.flush(mockResp);
    });
  });

  describe('updateResult', () => {
    it('should PUT to /lab-results/:id', () => {
      const req: LabResultRequest = {
        labOrderId: 'order-1',
        assignmentId: 'assign-1',
        patientId: 'patient-1',
        resultValue: '6.0',
        resultDate: '2025-01-15T10:00',
      };
      const mockResp = { id: 'result-1', resultValue: '6.0' };

      service.updateResult('result-1', req).subscribe((res) => {
        expect(res.resultValue).toBe('6.0');
      });

      const httpReq = httpMock.expectOne('/lab-results/result-1');
      expect(httpReq.request.method).toBe('PUT');
      httpReq.flush(mockResp);
    });
  });

  describe('deleteResult', () => {
    it('should DELETE /lab-results/:id and return text', () => {
      service.deleteResult('result-1').subscribe((res) => {
        expect(res).toBeTruthy();
      });

      const httpReq = httpMock.expectOne('/lab-results/result-1');
      expect(httpReq.request.method).toBe('DELETE');
      expect(httpReq.request.responseType).toBe('text');
      httpReq.flush('Deleted');
    });
  });

  describe('releaseResult', () => {
    it('should POST to /lab-results/:id/release', () => {
      const mockResp = { id: 'result-1', released: true };

      service.releaseResult('result-1').subscribe((res) => {
        expect(res.id).toBe('result-1');
      });

      const httpReq = httpMock.expectOne('/lab-results/result-1/release');
      expect(httpReq.request.method).toBe('POST');
      httpReq.flush(mockResp);
    });
  });

  // ── Lab Test Definition CRUD ─────────────────────────────────────────

  describe('createTestDefinition', () => {
    it('should POST to /lab-test-definitions', () => {
      const req: LabTestDefinitionRequest = {
        testCode: 'BMP',
        testName: 'Basic Metabolic Panel',
        category: 'Chemistry',
      };
      const mockResp = {
        data: { id: 'def-1', testCode: 'BMP', testName: 'Basic Metabolic Panel' },
        success: true,
      };

      service.createTestDefinition(req).subscribe((res) => {
        expect(res.testCode).toBe('BMP');
      });

      const httpReq = httpMock.expectOne('/lab-test-definitions');
      expect(httpReq.request.method).toBe('POST');
      expect(httpReq.request.body).toEqual(req);
      httpReq.flush(mockResp);
    });
  });

  describe('updateTestDefinition', () => {
    it('should PUT to /lab-test-definitions/:id', () => {
      const req: LabTestDefinitionRequest = {
        testCode: 'BMP',
        testName: 'Basic Metabolic Panel (Updated)',
      };
      const mockResp = {
        data: { id: 'def-1', testCode: 'BMP', testName: 'Basic Metabolic Panel (Updated)' },
        success: true,
      };

      service.updateTestDefinition('def-1', req).subscribe((res) => {
        expect(res.testName).toBe('Basic Metabolic Panel (Updated)');
      });

      const httpReq = httpMock.expectOne('/lab-test-definitions/def-1');
      expect(httpReq.request.method).toBe('PUT');
      httpReq.flush(mockResp);
    });
  });

  describe('deleteTestDefinition', () => {
    it('should DELETE /lab-test-definitions/:id', () => {
      service.deleteTestDefinition('def-1').subscribe((res) => {
        expect(res).toBeTruthy();
      });

      const httpReq = httpMock.expectOne('/lab-test-definitions/def-1');
      expect(httpReq.request.method).toBe('DELETE');
      httpReq.flush({ data: 'Deleted', success: true });
    });
  });
});
