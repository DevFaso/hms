import { TestBed, ComponentFixture } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { LabResultsComponent } from './lab-results';

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

  it('should submit create form', () => {
    fixture.detectChanges();
    flushInit();

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
});
