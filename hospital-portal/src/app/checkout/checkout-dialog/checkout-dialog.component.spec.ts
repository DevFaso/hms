import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { CheckoutDialogComponent } from './checkout-dialog.component';
import {
  EncounterService,
  EncounterResponse,
  AfterVisitSummary,
} from '../../services/encounter.service';
import { ToastService } from '../../core/toast.service';
import { AuthService } from '../../auth/auth.service';
import { PatientProblem, PatientService } from '../../services/patient.service';
import { PrescriptionResponse, PrescriptionService } from '../../services/prescription.service';
import { ReferralResponse, ReferralService } from '../../services/referral.service';

describe('CheckoutDialogComponent', () => {
  let component: CheckoutDialogComponent;
  let fixture: ComponentFixture<CheckoutDialogComponent>;
  let encounterSpy: jasmine.SpyObj<EncounterService>;
  let toastSpy: jasmine.SpyObj<ToastService>;

  const sampleEncounter: Partial<EncounterResponse> = {
    id: 'enc-1',
    patientFullName: 'John Doe',
    patientName: 'John Doe',
    status: 'IN_PROGRESS',
    chiefComplaint: 'Cough',
  };

  const sampleAvs: AfterVisitSummary = {
    encounterId: 'enc-1',
    visitDate: '2026-06-15T10:00:00',
    providerName: 'Dr Smith',
    departmentName: 'General',
    hospitalName: 'City Hospital',
    patientName: 'John Doe',
    chiefComplaint: 'Cough',
    dischargeDiagnoses: ['Upper respiratory infection'],
    prescriptionSummary: 'Amoxicillin 500mg',
    referralSummary: 'ENT referral',
    followUpInstructions: 'Return in 7 days',
    encounterStatus: 'COMPLETED',
    checkoutTimestamp: '2026-06-15T11:00:00',
  };

  beforeEach(async () => {
    encounterSpy = jasmine.createSpyObj('EncounterService', ['checkOut']);
    toastSpy = jasmine.createSpyObj('ToastService', ['success', 'error']);

    await TestBed.configureTestingModule({
      imports: [CheckoutDialogComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: EncounterService, useValue: encounterSpy },
        { provide: ToastService, useValue: toastSpy },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(CheckoutDialogComponent);
    component = fixture.componentInstance;
    component.encounter = sampleEncounter as EncounterResponse;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should render the dialog with checkout title', () => {
    const el: HTMLElement = fixture.nativeElement;
    const title = el.querySelector('#checkout-title');
    expect(title?.textContent).toContain('CHECKOUT.TITLE');
  });

  it('canSubmit should be true when encounter is set and not saving', () => {
    expect(component.canSubmit).toBeTrue();
  });

  it('canSubmit should be false when saving', () => {
    component.saving.set(true);
    expect(component.canSubmit).toBeFalse();
  });

  it('canSubmit should be false when no encounter', () => {
    component.encounter = null;
    expect(component.canSubmit).toBeFalse();
  });

  it('should emit dismissed when close() is called', () => {
    spyOn(component.dismissed, 'emit');
    component.close();
    expect(component.dismissed.emit).toHaveBeenCalled();
  });

  it('should emit dismissed when Cancel button is clicked', () => {
    spyOn(component.dismissed, 'emit');
    const el: HTMLElement = fixture.nativeElement;
    const cancelBtn = el.querySelector('.btn-secondary') as HTMLButtonElement;
    cancelBtn.click();
    expect(component.dismissed.emit).toHaveBeenCalled();
  });

  it('should submit checkout and show AVS on success', () => {
    encounterSpy.checkOut.and.returnValue(of(sampleAvs));
    spyOn(component.checkoutCompleted, 'emit');

    component.followUpInstructions.set('Return in 7 days');
    component.dischargeDiagnosesText.set('Upper respiratory infection');
    component.prescriptionSummary.set('Amoxicillin 500mg');

    component.submit();

    expect(encounterSpy.checkOut).toHaveBeenCalledWith(
      'enc-1',
      jasmine.objectContaining({
        followUpInstructions: 'Return in 7 days',
        dischargeDiagnoses: ['Upper respiratory infection'],
        prescriptionSummary: 'Amoxicillin 500mg',
      }),
    );
    expect(component.avs()).toEqual(sampleAvs);
    expect(component.saving()).toBeFalse();
    expect(toastSpy.success).toHaveBeenCalled();
    expect(component.checkoutCompleted.emit).toHaveBeenCalledWith(sampleAvs);
  });

  it('should show error toast on checkout failure', () => {
    encounterSpy.checkOut.and.returnValue(throwError(() => new Error('fail')));

    component.submit();

    expect(component.saving()).toBeFalse();
    expect(toastSpy.error).toHaveBeenCalled();
    expect(component.avs()).toBeNull();
  });

  it('should not submit when encounter is null', () => {
    component.encounter = null;
    component.submit();
    expect(encounterSpy.checkOut).not.toHaveBeenCalled();
  });

  it('should not submit when already saving', () => {
    component.saving.set(true);
    component.submit();
    expect(encounterSpy.checkOut).not.toHaveBeenCalled();
  });

  it('should parse multi-line diagnoses', () => {
    encounterSpy.checkOut.and.returnValue(of(sampleAvs));

    component.dischargeDiagnosesText.set('Diagnosis 1\nDiagnosis 2\n\nDiagnosis 3');
    component.submit();

    expect(encounterSpy.checkOut).toHaveBeenCalledWith(
      'enc-1',
      jasmine.objectContaining({
        dischargeDiagnoses: ['Diagnosis 1', 'Diagnosis 2', 'Diagnosis 3'],
      }),
    );
  });

  it('should include follow-up appointment when reason is provided', () => {
    encounterSpy.checkOut.and.returnValue(of(sampleAvs));

    component.followUpReason.set('Recheck in 2 weeks');
    component.followUpDate.set('2026-06-29');
    component.submit();

    expect(encounterSpy.checkOut).toHaveBeenCalledWith(
      'enc-1',
      jasmine.objectContaining({
        followUpAppointment: {
          reason: 'Recheck in 2 weeks',
          preferredDate: '2026-06-29',
        },
      }),
    );
  });

  it('should not include follow-up appointment when reason is empty', () => {
    encounterSpy.checkOut.and.returnValue(of(sampleAvs));

    component.followUpReason.set('');
    component.submit();

    const callArgs = encounterSpy.checkOut.calls.mostRecent().args[1];
    expect(callArgs.followUpAppointment).toBeUndefined();
  });

  it('keeps a follow-up date entered without a reason (D426/D321)', () => {
    encounterSpy.checkOut.and.returnValue(of(sampleAvs));

    component.followUpReason.set('');
    component.followUpDate.set('2026-11-03');
    component.submit();

    const callArgs = encounterSpy.checkOut.calls.mostRecent().args[1];
    expect(callArgs.followUpAppointment).toEqual({
      reason: undefined,
      preferredDate: '2026-11-03',
    });
  });

  it('should display AVS preview after successful checkout', () => {
    encounterSpy.checkOut.and.returnValue(of(sampleAvs));
    component.submit();
    fixture.detectChanges();

    const el: HTMLElement = fixture.nativeElement;
    const avsTitle = el.querySelector('.avs-title');
    expect(avsTitle?.textContent).toContain('CHECKOUT.AVS_TITLE');
    expect(el.textContent).toContain('John Doe');
    expect(el.textContent).toContain('Dr Smith');
  });

  it('should show form when avs is null', () => {
    const el: HTMLElement = fixture.nativeElement;
    const form = el.querySelector('form');
    expect(form).toBeTruthy();
    expect(el.querySelector('.avs-preview')).toBeFalsy();
  });

  it('should display patient banner with name and status', () => {
    const el: HTMLElement = fixture.nativeElement;
    const banner = el.querySelector('.patient-banner');
    expect(banner?.textContent).toContain('John Doe');
    // The status badge is rendered via `| enumLabel: 'encounterStatus'`, which
    // translates 'IN_PROGRESS' → 'In Progress' (or the localised equivalent).
    // Assert on the human-readable label, not the wire value — the wire value
    // is preserved in the data flow (see EnumLabelPipe spec for that guard).
    expect(banner?.textContent).toContain('In Progress');
  });
});

describe('CheckoutDialogComponent — prefilled from the visit (QA D960)', () => {
  let component: CheckoutDialogComponent;

  async function open(roles: boolean) {
    const auth = jasmine.createSpyObj('AuthService', ['hasAnyRole']);
    auth.hasAnyRole.and.returnValue(roles);
    const patients = jasmine.createSpyObj('PatientService', ['listDiagnoses']);
    patients.listDiagnoses.and.returnValue(
      of({
        items: [
          {
            id: 'd1',
            patientId: 'p1',
            problemDisplay: 'Paludisme simple',
            problemCode: 'B54',
            status: 'ACTIVE',
            // Onset three days before the visit, recorded during it.
            onsetDate: '2026-10-24',
            lastReviewedAt: '2026-10-27T10:15:00',
          },
          {
            id: 'd0',
            patientId: 'p1',
            problemDisplay: 'HTA',
            status: 'ACTIVE',
            onsetDate: '2020-01-01',
            lastReviewedAt: '2025-03-01T09:00:00',
          },
        ] as PatientProblem[],
        withheld: false,
      }),
    );
    const rx = jasmine.createSpyObj('PrescriptionService', ['list']);
    rx.list.and.returnValue(
      of([
        {
          id: 'r1',
          encounterId: 'enc-9',
          medicationName: 'Artéméther-luméfantrine',
          dosage: '80/480 mg',
          frequency: '2x/j',
          duration: '3 j',
          status: 'SIGNED',
        },
        { id: 'r2', encounterId: 'enc-9', medicationName: 'Annulé', status: 'CANCELLED' },
        { id: 'r4', encounterId: 'enc-9', medicationName: 'Brouillon', status: 'DRAFT' },
        { id: 'r3', encounterId: 'other', medicationName: 'Autre visite', status: 'SIGNED' },
      ] as PrescriptionResponse[]),
    );
    const refs = jasmine.createSpyObj('ReferralService', ['getByPatient']);
    refs.getByPatient.and.returnValue(
      of([
        {
          id: 'f1',
          targetSpecialty: 'CARDIOLOGY',
          targetDepartmentName: 'Cardiologie',
          referralReason: 'Souffle',
          status: 'SUBMITTED',
          createdAt: '2026-10-27T11:00:00',
        },
        {
          id: 'f0',
          targetDepartmentName: 'ORL',
          referralReason: 'Ancienne',
          status: 'COMPLETED',
          createdAt: '2025-01-01T09:00:00',
        },
      ] as ReferralResponse[]),
    );
    await TestBed.configureTestingModule({
      imports: [CheckoutDialogComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        {
          provide: EncounterService,
          useValue: jasmine.createSpyObj('EncounterService', ['checkOut']),
        },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error']),
        },
        { provide: AuthService, useValue: auth },
        { provide: PatientService, useValue: patients },
        { provide: PrescriptionService, useValue: rx },
        { provide: ReferralService, useValue: refs },
      ],
    }).compileComponents();
    component = TestBed.createComponent(CheckoutDialogComponent).componentInstance;
    component.encounter = {
      id: 'enc-9',
      patientId: 'p1',
      hospitalId: 'h1',
      encounterDate: '2026-10-27T08:00:00',
      note: { assessment: 'Accès palustre' },
    } as unknown as EncounterResponse;
  }

  it('starts from the diagnoses, prescriptions and referrals of this visit', async () => {
    await open(true);
    expect(component.dischargeDiagnosesText()).toBe('Paludisme simple (B54)');
    expect(component.prescriptionSummary()).toBe('Artéméther-luméfantrine 80/480 mg 2x/j 3 j');
    expect(component.referralSummary()).toBe('Cardiologie — Souffle');
  });

  it('falls back to the note assessment for a role that cannot read the record', async () => {
    await open(false);
    expect(component.dischargeDiagnosesText()).toBe('Accès palustre');
    expect(component.prescriptionSummary()).toBe('');
  });
});
