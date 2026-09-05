import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { DocumentsTabComponent } from './documents-tab.component';
import { PatientDocumentsService } from '../../services/patient-documents.service';
import { ToastService } from '../../core/toast.service';
import { PatientDocumentResponse } from '../../services/patient-portal.service';

const doc = (over: Partial<PatientDocumentResponse> = {}): PatientDocumentResponse => ({
  id: 'd1',
  patientId: 'p1',
  uploadedByUserId: 'u1',
  uploadedByDisplayName: 'Awa Traoré',
  documentType: 'REFERRAL_LETTER',
  displayName: 'referral.pdf',
  fileUrl: 'https://legacy.example/uploads/referral.pdf',
  mimeType: 'application/pdf',
  fileSizeBytes: 48_211,
  checksumSha256: null,
  collectionDate: '2026-08-14',
  notes: null,
  createdAt: '2026-09-01T10:00:00',
  ...over,
});

describe('DocumentsTabComponent', () => {
  let fixture: ComponentFixture<DocumentsTabComponent>;
  let component: DocumentsTabComponent;
  let serviceSpy: jasmine.SpyObj<PatientDocumentsService>;
  let toastSpy: jasmine.SpyObj<ToastService>;

  beforeEach(async () => {
    serviceSpy = jasmine.createSpyObj<PatientDocumentsService>('PatientDocumentsService', [
      'list',
      'get',
      'downloadBlob',
    ]);
    toastSpy = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'info']);
    serviceSpy.list.and.returnValue(
      of({ content: [doc()], totalElements: 1, totalPages: 1, number: 0, size: 50 }),
    );

    await TestBed.configureTestingModule({
      imports: [DocumentsTabComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PatientDocumentsService, useValue: serviceSpy },
        { provide: ToastService, useValue: toastSpy },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(DocumentsTabComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('patientId', 'p1');
    fixture.detectChanges();
  });

  it("loads the patient's documents on init and renders them", () => {
    expect(serviceSpy.list).toHaveBeenCalledWith('p1', null);
    expect(component.documents().length).toBe(1);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('referral.pdf');
    expect(text).toContain('47 KB');
  });

  it('reloads with the type filter when it changes', () => {
    component.onFilterChange('LAB_RESULT');
    expect(serviceSpy.list).toHaveBeenCalledWith('p1', 'LAB_RESULT');
  });

  it('shows the server message when the list cannot load (no active hospital)', () => {
    serviceSpy.list.and.returnValue(
      throwError(() => ({ status: 400, error: { message: 'An active hospital is required' } })),
    );
    component.load();
    fixture.detectChanges();
    expect(component.error()).toBe('An active hospital is required');
    expect(component.documents()).toEqual([]);
    expect((fixture.nativeElement as HTMLElement).querySelector('[role="alert"]')).not.toBeNull();
  });

  it('downloads through the authenticated route, never via fileUrl', () => {
    serviceSpy.downloadBlob.and.returnValue(of(new Blob(['%PDF'])));
    spyOn(URL, 'createObjectURL').and.returnValue('blob:x');
    spyOn(URL, 'revokeObjectURL');
    const click = spyOn(HTMLAnchorElement.prototype, 'click');

    component.download(doc());

    expect(serviceSpy.downloadBlob).toHaveBeenCalledWith('p1', 'd1');
    expect(click).toHaveBeenCalled();
    expect(component.downloading()).toBeNull();
    const html = (fixture.nativeElement as HTMLElement).innerHTML;
    expect(html).not.toContain('legacy.example');
  });

  it('reports a failed download and clears the busy state', () => {
    serviceSpy.downloadBlob.and.returnValue(throwError(() => new Error('503')));
    component.download(doc());
    expect(toastSpy.error).toHaveBeenCalledWith('PATIENT_DOCUMENTS.DOWNLOAD_FAILED');
    expect(component.downloading()).toBeNull();
  });

  it('formats sizes for humans', () => {
    expect(component.formatSize(512)).toBe('512 B');
    expect(component.formatSize(48_211)).toBe('47 KB');
    expect(component.formatSize(3_600_000)).toBe('3.4 MB');
    expect(component.formatSize(null)).toBeNull();
  });
});
