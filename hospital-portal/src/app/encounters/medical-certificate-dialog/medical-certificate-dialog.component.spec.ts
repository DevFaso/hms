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
