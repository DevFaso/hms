import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { MedicalCertificateDialogComponent } from './medical-certificate-dialog.component';
import { MedicalCertificateService } from '../../services/medical-certificate.service';
import { EncounterResponse } from '../../services/encounter.service';
import { PatientDocumentResponse } from '../../services/patient-portal.service';
import { ToastService } from '../../core/toast.service';

describe('MedicalCertificateDialogComponent (QA D961)', () => {
  let component: MedicalCertificateDialogComponent;
  let service: jasmine.SpyObj<MedicalCertificateService>;

  beforeEach(async () => {
    service = jasmine.createSpyObj('MedicalCertificateService', ['issue']);
    await TestBed.configureTestingModule({
      imports: [MedicalCertificateDialogComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: MedicalCertificateService, useValue: service },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error']),
        },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(MedicalCertificateDialogComponent);
    component = fixture.componentInstance;
    component.encounter = {
      id: 'enc-1',
      encounterDate: '2026-10-27T09:00:00',
    } as EncounterResponse;
    fixture.detectChanges();
  });

  it('QA D1356: opens empty, and an untouched form signs nothing', () => {
    expect(component.type).toBe('');
    expect(component.days).toBeNull();
    expect(component.missing()).toBe('MEDICAL_CERTIFICATE.TYPE_REQUIRED');
    component.submit();
    expect(service.issue).not.toHaveBeenCalled();
    // Choosing a sick leave still asks for the days: none is proposed.
    component.type = 'SICK_LEAVE';
    expect(component.missing()).toBe('MEDICAL_CERTIFICATE.DAYS_REQUIRED');
    component.submit();
    expect(service.issue).not.toHaveBeenCalled();
  });

  it('QA D1356: the type control renders blank and required, and the press marks it', async () => {
    const fixture = TestBed.createComponent(MedicalCertificateDialogComponent);
    fixture.componentInstance.encounter = { id: 'enc-2' } as EncounterResponse;
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const select = host.querySelector<HTMLSelectElement>('#mc-type')!;
    expect(select.required).toBeTrue();
    expect(select.value).toBe('');
    host.querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
    fixture.detectChanges();
    expect(service.issue).not.toHaveBeenCalled();
    expect(select.getAttribute('aria-invalid')).toBe('true');
  });

  it('sends a sick-leave note with its days, start and language', () => {
    service.issue.and.returnValue(
      of({ id: 'doc-1', patientId: 'p1', displayName: 'Arrêt.pdf' } as PatientDocumentResponse),
    );
    component.type = 'SICK_LEAVE';
    component.days = 3;
    component.language = 'es';
    component.submit();
    const [encounterId, req] = service.issue.calls.mostRecent().args;
    expect(encounterId).toBe('enc-1');
    expect(req.type).toBe('SICK_LEAVE');
    expect(req.days).toBe(3);
    expect(req.startDate).toBeTruthy();
    expect(req.language).toBe('es');
    expect(component.result()?.id).toBe('doc-1');
  });

  it("leaves the language to the patient's own unless the doctor picks one", () => {
    service.issue.and.returnValue(
      of({ id: 'doc-2', patientId: 'p1', displayName: 'Présence.pdf' } as PatientDocumentResponse),
    );
    component.type = 'ATTENDANCE';
    component.submit();
    expect(service.issue.calls.mostRecent().args[1].language).toBeUndefined();
  });

  it('holds a sick-leave note with no days and a general certificate with no text', () => {
    component.type = 'SICK_LEAVE';
    component.days = null;
    expect(component.missing()).toBe('MEDICAL_CERTIFICATE.DAYS_REQUIRED');
    component.type = 'GENERAL';
    component.remarks = '  ';
    expect(component.missing()).toBe('MEDICAL_CERTIFICATE.REMARKS_REQUIRED');
    component.submit();
    expect(service.issue).not.toHaveBeenCalled();
  });

  it('shows the server refusal in the dialog', () => {
    service.issue.and.returnValue(
      throwError(() => ({ error: { message: 'Visite non vue' }, status: 400 })),
    );
    component.type = 'ATTENDANCE';
    component.submit();
    expect(component.error()).toBe('Visite non vue');
    expect(component.saving()).toBeFalse();
  });
});
