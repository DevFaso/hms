import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { Subject, of, throwError } from 'rxjs';
import { DocumentsTabComponent, PAGE_SIZE } from './documents-tab.component';
import {
  PatientDocumentPage,
  PatientDocumentsService,
} from '../../services/patient-documents.service';
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

const page = (
  content: PatientDocumentResponse[],
  over: Partial<PatientDocumentPage> = {},
): PatientDocumentPage => ({
  content,
  totalElements: content.length,
  totalPages: 1,
  number: 0,
  size: PAGE_SIZE,
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
    serviceSpy.list.and.returnValue(of(page([doc()])));

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
    expect(serviceSpy.list).toHaveBeenCalledWith('p1', null, 0, PAGE_SIZE);
    expect(component.documents().length).toBe(1);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('referral.pdf');
    expect(text).toContain('47 KB');
  });

  it('reloads from page 0 with the type filter when it changes', () => {
    component.nextPage(); // no-op on a single page
    component.onFilterChange('LAB_RESULT');
    expect(serviceSpy.list).toHaveBeenCalledWith('p1', 'LAB_RESULT', 0, PAGE_SIZE);
  });

  it('pages forward and back, bounded by totalPages', () => {
    const multi = (number: number) =>
      page([doc({ id: `d${number}` })], { totalPages: 3, number, totalElements: 60 });
    serviceSpy.list.and.returnValues(of(multi(0)), of(multi(1)), of(multi(0)));

    component.load(); // seed a three-page state
    expect(component.hasPrevious()).toBeFalse();
    expect(component.hasNext()).toBeTrue();

    component.nextPage();
    expect(serviceSpy.list).toHaveBeenCalledWith('p1', null, 1, PAGE_SIZE);
    expect(component.page()).toBe(1);
    expect(component.hasPrevious()).toBeTrue();
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('nav.pager')).not.toBeNull();

    component.previousPage();
    expect(serviceSpy.list.calls.mostRecent().args).toEqual(['p1', null, 0, PAGE_SIZE]);
    expect(component.page()).toBe(0);
    component.previousPage(); // bounded: no request below page 0
    expect(serviceSpy.list).toHaveBeenCalledTimes(4);
  });

  it('only the latest request can update the view (switchMap)', () => {
    const slow = new Subject<PatientDocumentPage>();
    const fast = new Subject<PatientDocumentPage>();
    serviceSpy.list.and.returnValues(slow, fast);

    component.onFilterChange('LAB_RESULT'); // -> slow
    component.onFilterChange('INVOICE'); // -> fast, cancels slow
    fast.next(page([doc({ id: 'invoice', documentType: 'INVOICE' })]));
    fast.complete();
    slow.next(page([doc({ id: 'stale', documentType: 'LAB_RESULT' })]));
    slow.complete();

    expect(component.documents().map((d) => d.id)).toEqual(['invoice']);
  });

  it('re-fetches from page 0 when the hospital scope changes after init', () => {
    fixture.componentRef.setInput('hospitalScope', 'h2');
    fixture.detectChanges();
    expect(serviceSpy.list.calls.mostRecent().args).toEqual(['p1', null, 0, PAGE_SIZE]);
    expect(serviceSpy.list).toHaveBeenCalledTimes(2);
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

  it('downloads through the authenticated route, never via fileUrl, and revokes later', async () => {
    serviceSpy.downloadBlob.and.returnValue(of(new Blob(['%PDF'])));
    spyOn(URL, 'createObjectURL').and.returnValue('blob:x');
    const revoke = spyOn(URL, 'revokeObjectURL');
    const click = spyOn(HTMLAnchorElement.prototype, 'click');

    component.download(doc());

    expect(serviceSpy.downloadBlob).toHaveBeenCalledWith('p1', 'd1');
    expect(click).toHaveBeenCalled();
    expect(revoke).not.toHaveBeenCalled(); // deferred so WebKit can start reading
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    expect(revoke).toHaveBeenCalledWith('blob:x');
    expect(component.downloading()).toBeNull();
    expect((fixture.nativeElement as HTMLElement).innerHTML).not.toContain('legacy.example');
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
