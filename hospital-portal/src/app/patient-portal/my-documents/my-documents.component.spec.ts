import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { MAX_PATIENT_DOCUMENT_BYTES, MyDocumentsComponent } from './my-documents.component';
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

/**
 * The upload endpoint refuses anything over 10 MB (multipart max-file-size),
 * but the web sent any file and let the server reject it. The picker now
 * refuses it first, in the patient's language.
 */
describe('MyDocumentsComponent - file size', () => {
  let fixture: ComponentFixture<MyDocumentsComponent>;
  let portalService: jasmine.SpyObj<PatientPortalService>;
  let toastService: jasmine.SpyObj<ToastService>;

  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;

  function pick(bytes: number): HTMLInputElement {
    const input = host().querySelector('input[type="file"]') as HTMLInputElement;
    const transfer = new DataTransfer();
    transfer.items.add(new File([new Uint8Array(bytes)], 'scan.pdf', { type: 'application/pdf' }));
    input.files = transfer.files;
    input.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    return input;
  }

  const uploadButton = (): HTMLButtonElement =>
    host().querySelector('.upload-form .btn-primary') as HTMLButtonElement;

  beforeEach(() => {
    portalService = jasmine.createSpyObj('PatientPortalService', [
      'listDocuments',
      'uploadDocument',
      'deleteDocument',
    ]);
    portalService.listDocuments.and.returnValue(of({ content: [], totalElements: 0 }));
    toastService = jasmine.createSpyObj('ToastService', ['success', 'error']);
    TestBed.configureTestingModule({
      imports: [MyDocumentsComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PatientPortalService, useValue: portalService },
        { provide: ToastService, useValue: toastService },
      ],
    });
    const translate = TestBed.inject(TranslateService);
    translate.setFallbackLang('fr');
    translate.use('fr');
    translate.setTranslation('fr', {
      PORTAL: {
        DOCUMENTS: {
          FILE_TOO_LARGE: 'Le fichier ne doit pas dépasser 10 Mo.',
          UPLOAD_FAILED: 'Échec du téléversement du document',
        },
      },
    });
    fixture = TestBed.createComponent(MyDocumentsComponent);
    fixture.detectChanges();
    fixture.componentInstance.showUploadForm.set(true);
    fixture.detectChanges();
  });

  it('is 10 MB exactly', () => {
    expect(MAX_PATIENT_DOCUMENT_BYTES).toBe(10 * 1024 * 1024);
  });

  it('refuses a file over 10 MB on screen, in French, and never sends it', () => {
    const input = pick(MAX_PATIENT_DOCUMENT_BYTES + 1);

    expect(host().querySelector('[data-testid="file-too-large"]')?.textContent?.trim()).toBe(
      'Le fichier ne doit pas dépasser 10 Mo.',
    );
    expect(fixture.componentInstance.selectedFile()).toBeNull();
    expect(input.value).toBe('');
    expect(uploadButton().disabled).toBeTrue();
    fixture.componentInstance.uploadDocument();
    expect(portalService.uploadDocument).not.toHaveBeenCalled();
  });

  it('accepts a file of exactly 10 MB and clears an earlier refusal', () => {
    pick(MAX_PATIENT_DOCUMENT_BYTES + 1);
    pick(MAX_PATIENT_DOCUMENT_BYTES);

    expect(host().querySelector('[data-testid="file-too-large"]')).toBeNull();
    expect(fixture.componentInstance.selectedFile()?.size).toBe(MAX_PATIENT_DOCUMENT_BYTES);
    expect(uploadButton().disabled).toBeFalse();
  });

  it('toasts the upload failure translated, not as a raw key', () => {
    portalService.uploadDocument.and.returnValue(throwError(() => new Error('fail')));
    pick(1024);
    uploadButton().click();
    expect(toastService.error).toHaveBeenCalledWith('Échec du téléversement du document');
  });
});
