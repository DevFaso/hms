import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { MyDocumentsComponent } from './my-documents.component';
import { PatientPortalService } from '../../services/patient-portal.service';
import { ToastService } from '../../core/toast.service';

const mockDoc = {
  id: 'doc-1',
  patientId: 'p-1',
  uploadedByUserId: 'u-1',
  uploadedByDisplayName: 'Dr. Smith',
  documentType: 'LAB_RESULT' as const,
  displayName: 'blood-test.pdf',
  fileUrl: '/uploads/test.pdf',
  mimeType: 'application/pdf',
  fileSizeBytes: 51200,
  checksumSha256: 'abc123',
  collectionDate: '2025-01-15',
  notes: 'Routine CBC',
  createdAt: '2025-01-15T10:00:00',
};

describe('MyDocumentsComponent', () => {
  let component: MyDocumentsComponent;
  let fixture: ComponentFixture<MyDocumentsComponent>;
  let portalService: jasmine.SpyObj<PatientPortalService>;
  let toastService: jasmine.SpyObj<ToastService>;

  beforeEach(async () => {
    portalService = jasmine.createSpyObj('PatientPortalService', [
      'listDocuments',
      'uploadDocument',
      'deleteDocument',
    ]);
    toastService = jasmine.createSpyObj('ToastService', ['success', 'error']);

    portalService.listDocuments.and.returnValue(of({ content: [mockDoc], totalElements: 1 }));
    portalService.uploadDocument.and.returnValue(of(mockDoc));
    portalService.deleteDocument.and.returnValue(of(void 0));

    await TestBed.configureTestingModule({
      imports: [MyDocumentsComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PatientPortalService, useValue: portalService },
        { provide: ToastService, useValue: toastService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(MyDocumentsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should load documents on init', () => {
    expect(portalService.listDocuments).toHaveBeenCalled();
    expect(component.documents()).toEqual([mockDoc]);
    expect(component.totalElements()).toBe(1);
    expect(component.loading()).toBeFalse();
  });

  it('should show error toast when loadDocuments fails', () => {
    portalService.listDocuments.and.returnValue(throwError(() => new Error('fail')));
    component.loadDocuments();
    expect(toastService.error).toHaveBeenCalled();
    expect(component.loading()).toBeFalse();
  });

  it('should not upload if no file selected', () => {
    component.selectedFile.set(null);
    component.uploadDocument();
    expect(portalService.uploadDocument).not.toHaveBeenCalled();
  });

  it('should upload document and prepend to list', () => {
    component.documents.set([]);
    const file = new File(['content'], 'report.pdf', { type: 'application/pdf' });
    component.selectedFile.set(file);
    component.selectedDocumentType.set('LAB_RESULT');
    component.uploadDocument();
    expect(portalService.uploadDocument).toHaveBeenCalled();
    expect(component.documents()).toContain(mockDoc);
    expect(toastService.success).toHaveBeenCalled();
    expect(component.showUploadForm()).toBeFalse();
  });

  it('should show error toast when upload fails', () => {
    portalService.uploadDocument.and.returnValue(throwError(() => new Error('fail')));
    const file = new File(['x'], 'x.pdf');
    component.selectedFile.set(file);
    component.uploadDocument();
    expect(toastService.error).toHaveBeenCalled();
    expect(component.uploading()).toBeFalse();
  });

  it('should delete document and remove from list', () => {
    spyOn(window, 'confirm').and.returnValue(true);
    component.documents.set([mockDoc]);
    component.deleteDocument('doc-1');
    expect(portalService.deleteDocument).toHaveBeenCalledWith('doc-1');
    expect(component.documents()).toEqual([]);
    expect(toastService.success).toHaveBeenCalled();
  });

  it('should not delete if confirm is cancelled', () => {
    spyOn(window, 'confirm').and.returnValue(false);
    component.deleteDocument('doc-1');
    expect(portalService.deleteDocument).not.toHaveBeenCalled();
  });

  it('should show error toast when delete fails', () => {
    spyOn(window, 'confirm').and.returnValue(true);
    portalService.deleteDocument.and.returnValue(throwError(() => new Error('fail')));
    component.documents.set([mockDoc]);
    component.deleteDocument('doc-1');
    expect(toastService.error).toHaveBeenCalled();
    expect(component.deleting()).toBeNull();
  });

  it('should reload documents when filter changes', () => {
    portalService.listDocuments.calls.reset();
    component.filterType.set('LAB_RESULT');
    component.onFilterChange();
    expect(portalService.listDocuments).toHaveBeenCalledWith('LAB_RESULT', 0, 50);
  });

  it('fileSizeKb should format correctly', () => {
    expect(component.fileSizeKb(51200)).toBe('50.0');
  });
});
