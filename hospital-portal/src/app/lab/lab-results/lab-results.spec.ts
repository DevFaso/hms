import { TestBed, ComponentFixture } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { LabResultsComponent } from './lab-results';
import { LabOrderResponse } from '../../services/lab.service';

function mockResult(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id: 'result-1',
    labOrderId: 'order-1',
    labOrderCode: 'LAB-001',
    patientId: 'patient-1',
    patientFullName: 'John Doe',
    patientEmail: 'john@example.com',
    hospitalName: 'General Hospital',
    labTestName: 'Complete Blood Count',
    resultValue: '5.2',
    resultUnit: 'mg/dL',
    resultDate: '2025-01-15T10:00:00',
    notes: '',
    referenceRanges: [],
    trendHistory: [],
    severityFlag: 'NORMAL',
    acknowledged: false,
    acknowledgedAt: null,
    acknowledgedBy: null,
    released: false,
    releasedAt: null,
    releasedByFullName: null,
    signedAt: null,
    signedBy: null,
    signatureValue: null,
    signatureNotes: null,
    createdAt: '2025-01-15T10:00:00',
    updatedAt: '2025-01-15T10:00:00',
    ...overrides,
  };
}

describe('LabResultsComponent', () => {
  let fixture: ComponentFixture<LabResultsComponent>;
  let component: LabResultsComponent;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [LabResultsComponent, TranslateModule.forRoot()],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });

    fixture = TestBed.createComponent(LabResultsComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  function flushInit(results: Record<string, unknown>[] = [mockResult()]): void {
    // listResults
    const resultsReq = httpMock.expectOne((r) => r.url === '/lab-results' && r.method === 'GET');
    resultsReq.flush({
      data: { content: results, totalElements: results.length, totalPages: 1, number: 0 },
      success: true,
    });

    // listOrders
    const ordersReq = httpMock.expectOne((r) => r.url === '/lab-orders' && r.method === 'GET');
    ordersReq.flush({
      data: { content: [], totalElements: 0, totalPages: 0, number: 0 },
      success: true,
    });

    // getAssignments
    const assignReq = httpMock.expectOne((r) => r.url.includes('/assignments'));
    assignReq.flush([{ id: 'assign-1', active: true }]);
  }

  it('should create the component', () => {
    expect(component).toBeTruthy();
    fixture.detectChanges();
    flushInit();
  });

  it('should load results on init', () => {
    fixture.detectChanges();
    flushInit();

    expect(component.loading()).toBeFalse();
    expect(component.results().length).toBe(1);
    expect(component.stats().total).toBe(1);
  });

  it('should compute stats correctly', () => {
    fixture.detectChanges();
    flushInit([
      mockResult({ id: 'r-1', released: true }),
      mockResult({ id: 'r-2', released: false }),
      mockResult({ id: 'r-3', released: false }),
    ]);

    expect(component.stats().total).toBe(3);
    expect(component.stats().released).toBe(1);
    expect(component.stats().pending).toBe(2);
  });

  it('should filter by tab', () => {
    fixture.detectChanges();
    flushInit([
      mockResult({ id: 'r-1', released: true }),
      mockResult({ id: 'r-2', released: false }),
    ]);

    component.setTab('released');
    expect(component.filtered().length).toBe(1);

    component.setTab('pending');
    expect(component.filtered().length).toBe(1);

    component.setTab('all');
    expect(component.filtered().length).toBe(2);
  });

  it('should filter by search term', () => {
    fixture.detectChanges();
    flushInit([
      mockResult({ id: 'r-1', patientFullName: 'Alice Smith' }),
      mockResult({ id: 'r-2', patientFullName: 'Bob Jones' }),
    ]);

    component.searchTerm = 'alice';
    component.applyFilter();
    expect(component.filtered().length).toBe(1);
    expect(component.filtered()[0].patientFullName).toBe('Alice Smith');
  });

  it('should open create modal', () => {
    fixture.detectChanges();
    flushInit();

    component.openCreate();
    expect(component.showModal()).toBeTrue();
    expect(component.editing()).toBeFalse();
    expect(component.editingId()).toBeNull();
  });

  it('should open edit modal', () => {
    fixture.detectChanges();
    flushInit();

    const result = component.results()[0];
    component.openEdit(result);

    expect(component.showModal()).toBeTrue();
    expect(component.editing()).toBeTrue();
    expect(component.editingId()).toBe('result-1');
    expect(component.form.resultValue).toBe('5.2');
  });

  it('should close modal', () => {
    fixture.detectChanges();
    flushInit();

    component.openCreate();
    expect(component.showModal()).toBeTrue();

    component.closeModal();
    expect(component.showModal()).toBeFalse();
  });

  it('should block submit when labOrderId is missing', () => {
    fixture.detectChanges();
    flushInit();

    component.openCreate();
    component.form.labOrderId = '';
    component.form.resultValue = '7.0';
    component.submitForm();

    // saving should not be set and no HTTP request should be made
    expect(component.saving()).toBeFalse();
  });

  it('should submit create form', () => {
    fixture.detectChanges();
    flushInit();

    // Populate orders so submitForm guard can derive patientId
    component.orders.set([{ id: 'order-1', patientId: 'patient-1' } as LabOrderResponse]);

    component.openCreate();
    component.form.labOrderId = 'order-1';
    component.form.resultValue = '7.0';
    component.form.resultDate = '2025-01-15T10:00';
    component.submitForm();

    const req = httpMock.expectOne((r) => r.url === '/lab-results' && r.method === 'POST');
    req.flush({ id: 'new-result', resultValue: '7.0' });

    // reload triggered
    const reloadReq = httpMock.expectOne((r) => r.url === '/lab-results' && r.method === 'GET');
    reloadReq.flush({
      data: { content: [mockResult({ id: 'new-result', resultValue: '7.0' })], totalElements: 1 },
      success: true,
    });

    expect(component.showModal()).toBeFalse();
    expect(component.saving()).toBeFalse();
  });

  it('should confirm and execute delete', () => {
    fixture.detectChanges();
    flushInit();

    const result = component.results()[0];
    component.confirmDelete(result);
    expect(component.showDeleteConfirm()).toBeTrue();
    expect(component.deletingResult()).toBe(result);

    component.executeDelete();
    const req = httpMock.expectOne('/lab-results/result-1');
    expect(req.request.method).toBe('DELETE');
    req.flush('Deleted');

    // reload triggered
    const reloadReq = httpMock.expectOne((r) => r.url === '/lab-results' && r.method === 'GET');
    reloadReq.flush({ data: { content: [], totalElements: 0 }, success: true });

    expect(component.showDeleteConfirm()).toBeFalse();
    expect(component.deleting()).toBeFalse();
  });

  it('should cancel delete', () => {
    fixture.detectChanges();
    flushInit();

    const result = component.results()[0];
    component.confirmDelete(result);
    component.cancelDelete();

    expect(component.showDeleteConfirm()).toBeFalse();
    expect(component.deletingResult()).toBeNull();
  });

  it('should return correct severity class', () => {
    expect(component.getSeverityClass('CRITICAL')).toBe('severity-badge severity-critical');
    expect(component.getSeverityClass('HIGH')).toBe('severity-badge severity-high');
    expect(component.getSeverityClass('NORMAL')).toBe('severity-badge severity-normal');
    expect(component.getSeverityClass('UNKNOWN')).toBe('severity-badge');
  });

  it('should view and close detail panel', () => {
    fixture.detectChanges();
    flushInit();

    const result = component.results()[0];
    component.viewResult(result);
    expect(component.selectedResult()).toBe(result);

    component.closeDetail();
    expect(component.selectedResult()).toBeNull();
  });

  /* ── Critical-value read-back (P0 #5) ── */

  describe('critical-value read-back', () => {
    it('asks a critical result to be read back rather than merely acknowledged', () => {
      // canAcknowledge is role-derived and false in this harness; overriding it
      // is what makes the assertion about CRITICAL-vs-normal rather than about
      // whether the actions column renders at all.
      (component as unknown as { canAcknowledge: boolean }).canAcknowledge = true;
      fixture.detectChanges();
      flushInit([mockResult({ severityFlag: 'CRITICAL' })]);
      fixture.detectChanges();

      expect(
        fixture.nativeElement.querySelector('[data-testid="read-back-result-1"]'),
      ).not.toBeNull();
    });

    it('leaves a normal result with a plain acknowledge', () => {
      (component as unknown as { canAcknowledge: boolean }).canAcknowledge = true;
      fixture.detectChanges();
      flushInit([mockResult({ severityFlag: 'NORMAL' })]);
      fixture.detectChanges();

      expect(fixture.nativeElement.querySelector('[data-testid="read-back-result-1"]')).toBeNull();
      expect(component.isCritical(component.results()[0])).toBeFalse();
    });

    it('never prefills the reported value into the read-back field', () => {
      // Prefilling would let the clinician confirm the number by copying it,
      // which defeats the entire check.
      fixture.detectChanges();
      flushInit([mockResult({ severityFlag: 'CRITICAL', resultValue: '7.1' })]);
      component.openReadBack(component.results()[0]);
      fixture.detectChanges();

      expect(component.readBackValue()).toBe('');
    });

    it('posts the repeated value and resolves the row on a match', () => {
      fixture.detectChanges();
      flushInit([mockResult({ severityFlag: 'CRITICAL', resultValue: '7.1' })]);
      component.openReadBack(component.results()[0]);
      component.readBackValue.set('7.1');
      component.submitReadBack();

      const req = httpMock.expectOne('/lab-results/result-1/critical-read-back');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ repeatedValue: '7.1' });
      req.flush(mockResult({ severityFlag: 'CRITICAL', acknowledged: true }));

      expect(component.readBackTarget()).toBeNull();
    });

    it('surfaces the server message on a mismatch and keeps the dialog open', () => {
      fixture.detectChanges();
      flushInit([mockResult({ severityFlag: 'CRITICAL', resultValue: '7.1' })]);
      component.openReadBack(component.results()[0]);
      component.readBackValue.set('1.7');
      component.submitReadBack();

      httpMock
        .expectOne('/lab-results/result-1/critical-read-back')
        .flush(
          { message: 'Read-back does not match the reported result.' },
          { status: 400, statusText: 'Bad Request' },
        );

      // The clinician has to try again — silently closing would leave a
      // critical value unresolved and look like success.
      expect(component.readBackTarget()).not.toBeNull();
      expect(component.readBackSubmitting()).toBeFalse();
    });
  });
});
