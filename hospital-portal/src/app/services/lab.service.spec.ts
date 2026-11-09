import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { LabService, LabResultRequest, LabTestDefinitionRequest } from './lab.service';

describe('LabService', () => {
  let service: LabService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [LabService, provideHttpClient(withXhr()), provideHttpClientTesting()],
    });
    service = TestBed.inject(LabService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('reads the unwrapped detail DTO returned by GET /lab-results/{id}', () => {
    const detail = { id: 'result-1', patientId: 'patient-1', trendHistory: [] };
    service
      .getResult('result-1')
      .subscribe((result) => expect(result).toEqual(jasmine.objectContaining(detail)));
    const request = httpMock.expectOne('/lab-results/result-1');
    expect(request.request.method).toBe('GET');
    request.flush(detail);
  });

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

  describe('specimens', () => {
    it('lists specimens for an order (unwraps ApiWrapper)', () => {
      service.listSpecimens('ord-1').subscribe((list) => {
        expect(list.length).toBe(1);
        expect(list[0].id).toBe('sp-1');
      });
      const req = httpMock.expectOne('/lab-orders/ord-1/specimens');
      expect(req.request.method).toBe('GET');
      req.flush({ data: [{ id: 'sp-1', status: 'COLLECTED' }], success: true });
    });

    it('creates a specimen against an order', () => {
      service.createSpecimen('ord-1', { specimenType: 'Whole blood' }).subscribe();
      const req = httpMock.expectOne('/lab-orders/ord-1/specimens');
      expect(req.request.method).toBe('POST');
      expect(req.request.body.specimenType).toBe('Whole blood');
      req.flush({ data: { id: 'sp-1', status: 'COLLECTED' }, success: true });
    });

    it('receives a specimen', () => {
      service.receiveSpecimen('sp-1').subscribe((sp) => expect(sp.status).toBe('RECEIVED'));
      const req = httpMock.expectOne('/lab-specimens/sp-1/receive');
      expect(req.request.method).toBe('POST');
      req.flush({ data: { id: 'sp-1', status: 'RECEIVED' }, success: true });
    });
  });

  describe('result sign / acknowledge / critical / compare', () => {
    it('signs a result with signature payload', () => {
      service.signResult('r-1', { signature: 'Dr. Who', notes: 'ok' }).subscribe();
      const req = httpMock.expectOne('/lab-results/r-1/sign');
      expect(req.request.method).toBe('POST');
      expect(req.request.body.signature).toBe('Dr. Who');
      req.flush({ id: 'r-1' });
    });

    it('acknowledges a result (204, no body)', () => {
      let completed = false;
      service.acknowledgeResult('r-1').subscribe({ complete: () => (completed = true) });
      const req = httpMock.expectOne('/lab-results/r-1/acknowledge');
      expect(req.request.method).toBe('POST');
      req.flush(null, { status: 204, statusText: 'No Content' });
      expect(completed).toBeTrue();
    });

    it('loads unacknowledged critical results for a hospital', () => {
      service.criticalUnacknowledged('h-1').subscribe();
      const req = httpMock.expectOne('/lab-results/hospital/h-1/critical/unacknowledged');
      expect(req.request.method).toBe('GET');
      req.flush([]);
    });

    it('passes the since param to the critical list', () => {
      service.criticalResults('h-1', '2026-08-17T00:00:00').subscribe();
      const req = httpMock.expectOne((r) => r.url === '/lab-results/hospital/h-1/critical');
      expect(req.request.params.get('since')).toBe('2026-08-17T00:00:00');
      req.flush([]);
    });

    it('fetches a result comparison', () => {
      service.compareResult('r-1').subscribe((cmp) => expect(cmp.testCode).toBe('GLU'));
      const req = httpMock.expectOne('/lab-results/r-1/compare');
      req.flush({ testCode: 'GLU', trendHistory: [], referenceRanges: [] });
    });
  });

  describe('reflex rules', () => {
    it('lists rules (unwraps ApiWrapper)', () => {
      service.listReflexRules().subscribe((rules) => expect(rules.length).toBe(1));
      const req = httpMock.expectOne('/lab-reflex-rules');
      req.flush({ data: [{ id: 'rule-1', active: true }], success: true });
    });

    it('creates a rule', () => {
      service
        .createReflexRule({
          triggerTestDefinitionId: 'def-1',
          reflexTestDefinitionId: 'def-2',
          condition: '{"severityFlag":"ABNORMAL"}',
          active: true,
        })
        .subscribe();
      const req = httpMock.expectOne('/lab-reflex-rules');
      expect(req.request.method).toBe('POST');
      req.flush({ data: { id: 'rule-1' }, success: true });
    });

    it('updates a rule', () => {
      service
        .updateReflexRule('rule-1', {
          triggerTestDefinitionId: 'def-1',
          reflexTestDefinitionId: 'def-2',
          condition: '{"thresholdOperator":"GT","thresholdValue":11}',
          active: false,
        })
        .subscribe();
      const req = httpMock.expectOne('/lab-reflex-rules/rule-1');
      expect(req.request.method).toBe('PUT');
      expect(req.request.body.active).toBeFalse();
      req.flush({ data: { id: 'rule-1' }, success: true });
    });
  });
  // ── QA D1347: the worklists read every page ─────────────────────────

  describe('QA D1347: listOrders without a page is every order', () => {
    const order = (i: number) => ({ id: `o${i}`, labOrderCode: `LAB-${i}` });
    const flushPage = (page: number, content: unknown[], total: number, totalPages: number) =>
      httpMock
        .expectOne((r) => r.url === '/lab-orders' && r.params.get('page') === String(page))
        .flush({
          data: { content, totalElements: total, totalPages, number: page },
          success: true,
        });

    it('reads page after page until the last: 216 orders, not the 200 newest', () => {
      let got: { id: string }[] = [];
      service.listOrders().subscribe((list) => (got = list as { id: string }[]));
      flushPage(
        0,
        Array.from({ length: 200 }, (_, i) => order(i)),
        216,
        2,
      );
      flushPage(
        1,
        Array.from({ length: 16 }, (_, i) => order(200 + i)),
        216,
        2,
      );
      expect(got.length).toBe(216);
      expect(got[215].id).toBe('o215');
    });

    it('a caller that asks for one page gets that page only', () => {
      let got: unknown[] = [];
      service.listOrders({ patientId: 'p-1', page: 0, size: 25 }).subscribe((l) => (got = l));
      const req = httpMock.expectOne((r) => r.url === '/lab-orders');
      expect(req.request.params.get('size')).toBe('25');
      expect(req.request.params.get('patientId')).toBe('p-1');
      req.flush({ data: { content: [order(1)], totalElements: 90, totalPages: 4, number: 0 } });
      expect(got.length).toBe(1);
    });

    it('lab results and QC events read every page too', () => {
      let results = 0;
      let qc = 0;
      service.listAllResults().subscribe((r) => (results = r.total));
      service.getQcEventsByDefinition('def-1').subscribe((r) => (qc = r.length));
      const first = httpMock.expectOne(
        (r) => r.url === '/lab-results' && r.params.get('page') === '0',
      );
      // QA D1361: every page is cut from one total order (newest result, then id).
      expect(first.request.params.getAll('sort')).toEqual(['resultDate,desc', 'id,desc']);
      first.flush({ data: { content: [{ id: 'a' }], totalElements: 2, totalPages: 2, number: 0 } });
      httpMock
        .expectOne((r) => r.url === '/lab-results' && r.params.get('page') === '1')
        .flush({ data: { content: [{ id: 'b' }], totalElements: 2, totalPages: 2, number: 1 } });
      const qcReq = httpMock.expectOne((r) => r.url === '/lab-qc-events');
      expect(qcReq.request.params.get('sort')).toBe('recordedAt,asc');
      qcReq.flush({ data: { content: [{ id: 'q' }], totalElements: 1, totalPages: 1, number: 0 } });
      expect(results).toBe(2);
      expect(qc).toBe(1);
    });
  });
});
