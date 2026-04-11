import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
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
        provideHttpClient(),
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
    expect(banner?.textContent).toContain('IN_PROGRESS');
  });
});
