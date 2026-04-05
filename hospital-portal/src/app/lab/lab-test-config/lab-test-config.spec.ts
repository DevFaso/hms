import { TestBed, ComponentFixture } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { LabTestConfigComponent } from './lab-test-config';

function mockDefinition(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id: 'def-1',
    testCode: 'CBC',
    testName: 'Complete Blood Count',
    description: 'Full blood count',
    category: 'Hematology',
    sampleType: 'Blood',
    isActive: true,
    approvalStatus: 'ACTIVE',
    approvedById: null,
    approvedAt: null,
    reviewedById: null,
    reviewedAt: null,
    rejectionReason: null,
    referenceRanges: [],
    ...overrides,
  };
}

describe('LabTestConfigComponent', () => {
  let fixture: ComponentFixture<LabTestConfigComponent>;
  let component: LabTestConfigComponent;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [LabTestConfigComponent, TranslateModule.forRoot()],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });

    fixture = TestBed.createComponent(LabTestConfigComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  function flushSearch(
    content: Record<string, unknown>[] = [mockDefinition()],
    totalElements = 1,
  ): void {
    const req = httpMock.expectOne((r) => r.url.includes('/lab-test-definitions/search'));
    expect(req.request.method).toBe('GET');
    req.flush({ data: { content, totalElements, totalPages: 1, number: 0 }, success: true });
  }

  it('should create the component', () => {
    expect(component).toBeTruthy();
    fixture.detectChanges();
    flushSearch();
  });

  it('should load definitions on init', () => {
    fixture.detectChanges();
    flushSearch();

    expect(component.loading()).toBeFalse();
    expect(component.definitions().length).toBe(1);
    expect(component.totalElements()).toBe(1);
  });

  it('should set error on load failure', () => {
    fixture.detectChanges();
    const req = httpMock.expectOne((r) => r.url.includes('/lab-test-definitions/search'));
    req.error(new ProgressEvent('error'));

    expect(component.loading()).toBeFalse();
    expect(component.error()).toBeTruthy();
  });

  it('should search on keyword change', () => {
    fixture.detectChanges();
    flushSearch();

    component.searchKeyword.set('CBC');
    component.onSearch();

    const req = httpMock.expectOne((r) => r.url.includes('/lab-test-definitions/search'));
    expect(req.request.params.get('keyword')).toBe('CBC');
    req.flush({ data: { content: [mockDefinition()], totalElements: 1 }, success: true });
  });

  it('should open and close range editor', () => {
    fixture.detectChanges();
    flushSearch();

    const def = component.definitions()[0];
    component.openRangeEditor(def as never);

    expect(component.editingDef()).toBeTruthy();

    component.closeRangeEditor();
    expect(component.editingDef()).toBeNull();
    expect(component.editRanges().length).toBe(0);
  });

  it('should add and remove ranges', () => {
    fixture.detectChanges();
    flushSearch();

    const def = component.definitions()[0];
    component.openRangeEditor(def as never);

    component.addRange();
    expect(component.editRanges().length).toBe(1);

    component.addRange();
    expect(component.editRanges().length).toBe(2);

    component.removeRange(0);
    expect(component.editRanges().length).toBe(1);
  });

  it('should save reference ranges', () => {
    fixture.detectChanges();
    flushSearch();

    const def = component.definitions()[0];
    component.openRangeEditor(def as never);
    component.addRange();
    component.saveRanges();

    const req = httpMock.expectOne((r) =>
      r.url.includes('/lab-test-definitions/def-1/reference-ranges'),
    );
    expect(req.request.method).toBe('PUT');
    req.flush({
      data: mockDefinition({ referenceRanges: [{ minValue: 0, maxValue: 10 }] }),
      success: true,
    });

    expect(component.editingDef()).toBeNull();
    expect(component.saving()).toBeFalse();
  });

  it('should export CSV', () => {
    fixture.detectChanges();
    flushSearch();

    component.exportCsv();

    const req = httpMock.expectOne(
      (r) => r.url.includes('/lab-test-definitions/export') && !r.url.includes('/pdf'),
    );
    expect(req.request.method).toBe('GET');
    req.flush(new Blob(['test'], { type: 'text/csv' }));
  });

  it('should export PDF', () => {
    fixture.detectChanges();
    flushSearch();

    component.exportPdf();

    const req = httpMock.expectOne((r) => r.url.includes('/lab-test-definitions/export/pdf'));
    expect(req.request.method).toBe('GET');
    req.flush(new Blob(['test'], { type: 'application/pdf' }));
  });

  it('should return correct status class', () => {
    expect(component.statusClass('ACTIVE')).toBe('badge-active');
    expect(component.statusClass('APPROVED')).toBe('badge-approved');
    expect(component.statusClass('DRAFT')).toBe('badge-draft');
    expect(component.statusClass('REJECTED')).toBe('badge-rejected');
    expect(component.statusClass('RETIRED')).toBe('badge-retired');
    expect(component.statusClass('PENDING_QA_REVIEW')).toBe('badge-pending');
  });

  it('should navigate pages', () => {
    fixture.detectChanges();
    flushSearch([mockDefinition()], 50);

    expect(component.page()).toBe(0);

    component.nextPage();
    flushSearch([mockDefinition({ id: 'def-2' })], 50);
    expect(component.page()).toBe(1);

    component.prevPage();
    flushSearch([mockDefinition()], 50);
    expect(component.page()).toBe(0);

    // prevPage should not go below 0
    component.prevPage();
    flushSearch([mockDefinition()], 50);
    expect(component.page()).toBe(0);
  });

  it('should calculate totalPages', () => {
    fixture.detectChanges();
    flushSearch([mockDefinition()], 50);

    expect(component.totalPages()).toBe(3); // ceil(50/20)
  });
});
