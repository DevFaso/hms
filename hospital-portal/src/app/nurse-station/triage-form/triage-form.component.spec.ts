import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TriageFormComponent } from './triage-form.component';
import { TranslateModule } from '@ngx-translate/core';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import {
  EncounterService,
  EncounterResponse,
  TriageSubmissionResponse,
} from '../../services/encounter.service';
import { ToastService } from '../../core/toast.service';
import { of, throwError } from 'rxjs';
import { PatientService, PatientResponse } from '../../services/patient.service';

describe('TriageFormComponent', () => {
  let component: TriageFormComponent;
  let fixture: ComponentFixture<TriageFormComponent>;
  let mockEncounterService: jasmine.SpyObj<EncounterService>;
  let mockToastService: jasmine.SpyObj<ToastService>;
  let mockPatientService: jasmine.SpyObj<PatientService>;

  const sampleEncounter: Partial<EncounterResponse> = {
    id: 'enc-1',
    patientFullName: 'Jane Doe',
    patientName: 'Jane Doe',
    chiefComplaint: 'Headache',
    status: 'ARRIVED',
  };

  const sampleResponse: TriageSubmissionResponse = {
    encounterId: 'enc-1',
    encounterStatus: 'WAITING_FOR_PHYSICIAN',
    esiScore: 3,
    urgency: 'ROUTINE',
    roomAssignment: 'ER-Bay-3',
    triageTimestamp: '2026-04-10T10:00:00',
    roomedTimestamp: '2026-04-10T10:05:00',
    chiefComplaint: 'Headache',
    vitalSignId: 'vs-1',
  };

  beforeEach(async () => {
    mockEncounterService = jasmine.createSpyObj('EncounterService', ['submitTriage']);
    mockToastService = jasmine.createSpyObj('ToastService', ['success', 'error']);
    mockPatientService = jasmine.createSpyObj('PatientService', ['getById']);
    mockPatientService.getById.and.returnValue(throwError(() => new Error('not asked')));

    await TestBed.configureTestingModule({
      imports: [TriageFormComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: EncounterService, useValue: mockEncounterService },
        { provide: ToastService, useValue: mockToastService },
        { provide: PatientService, useValue: mockPatientService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(TriageFormComponent);
    component = fixture.componentInstance;
    component.encounter = sampleEncounter as EncounterResponse;
    fixture.detectChanges();
  });

  /** QA D1158: a triage the nurse actually filled — an acuity and one vital sign. */
  function fillMinimum(): void {
    component.esiScore.set(3);
    component.heartRateBpm.set(88);
  }

  it('QA D1158: the ESI is not preset and an empty triage is refused, field by field', () => {
    const el = fixture.nativeElement as HTMLElement;
    document.body.appendChild(el);
    try {
      expect(component.esiScore()).toBeNull();
      expect(component.canSubmit).toBeFalse();
      const submit = el.querySelector<HTMLButtonElement>('button[type="submit"]')!;
      submit.click();
      fixture.detectChanges();
      expect(mockEncounterService.submitTriage).not.toHaveBeenCalled();
      expect(el.querySelector('#esiScore')?.getAttribute('aria-invalid')).toBe('true');

      component.esiScore.set(2);
      fixture.detectChanges();
      submit.click();
      fixture.detectChanges();
      expect(mockEncounterService.submitTriage).not.toHaveBeenCalled();
      expect(el.querySelector('#temperature')?.getAttribute('aria-invalid')).toBe('true');
      expect(el.textContent).toContain('TRIAGE.VITAL_REQUIRED');
    } finally {
      el.remove();
    }
  });

  // QA D1077: the triage form offered and stored a fundal height for a man.
  it('D1077: offers no fundal height for a patient recorded as male, and sends none', () => {
    mockPatientService.getById.and.returnValue(
      of({ id: 'p-m', gender: 'MALE' } as PatientResponse),
    );
    mockEncounterService.submitTriage.and.returnValue(of(sampleResponse));
    component.encounter = { ...sampleEncounter, patientId: 'p-m' } as EncounterResponse;
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="triage-fundal-height"]')).toBeNull();
    component.fundalHeightCm.set(28);
    fillMinimum();
    component.submit();
    const sent = mockEncounterService.submitTriage.calls.mostRecent().args[1];
    expect(sent.fundalHeightCm).toBeUndefined();
  });

  it('D1077: keeps the fundal height for a woman and for a patient whose sex is not known', () => {
    mockPatientService.getById.and.returnValue(
      of({ id: 'p-f', gender: 'FEMALE' } as PatientResponse),
    );
    component.encounter = { ...sampleEncounter, patientId: 'p-f' } as EncounterResponse;
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="triage-fundal-height"]')).not.toBeNull();
    component.encounter = {
      ...sampleEncounter,
      patientId: undefined,
    } as unknown as EncounterResponse;
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="triage-fundal-height"]')).not.toBeNull();
  });

  // QA D992: the visit is named by its code, never by a slice of its UUID.
  it('names the encounter by its visit code, never a UUID fragment (QA D992)', () => {
    component.encounter = {
      ...sampleEncounter,
      id: '42a0451a-0000-4000-8000-000000000000',
      code: 'ENC-20261027-51B828',
    } as EncounterResponse;
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('ENC-20261027-51B828');
    expect(text).not.toContain('42a0451a');
  });

  // QA D1004: the triage overlay is a modal dialog for keyboard and screen-reader users.
  it('is a labelled modal dialog that closes on Escape (QA D1004)', () => {
    const dialog = (fixture.nativeElement as HTMLElement).querySelector(
      '.triage-dialog',
    ) as HTMLElement;
    expect(dialog.getAttribute('role')).toBe('dialog');
    expect(dialog.getAttribute('aria-modal')).toBe('true');
    expect(dialog.getAttribute('aria-labelledby')).toBe('triage-dialog-title');
    expect(dialog.contains(document.activeElement)).toBeTrue();
    const closed = spyOn(component.dismissed, 'emit');
    dialog.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    expect(closed).toHaveBeenCalled();
  });

  it('Escape never throws away typed vitals (review of #971)', () => {
    const closed = spyOn(component.dismissed, 'emit');
    component.systolicBpMmHg.set(152);
    component.onEscape();
    expect(closed).not.toHaveBeenCalled();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('carries the check-in chief complaint into the form (D179)', () => {
    const fresh = TestBed.createComponent(TriageFormComponent).componentInstance;
    fresh.encounter = { id: 'e9', chiefComplaint: 'Fièvre depuis 3 jours' } as EncounterResponse;
    expect(fresh.chiefComplaint()).toBe('Fièvre depuis 3 jours');
  });

  it('should allow submit when ESI score is valid and encounter is set', () => {
    fillMinimum();
    expect(component.canSubmit).toBeTrue();
  });

  it('should not allow submit when saving is in progress', () => {
    component.saving.set(true);
    expect(component.canSubmit).toBeFalse();
  });

  it('should not allow submit when no encounter is set', () => {
    component.encounter = null;
    expect(component.canSubmit).toBeFalse();
  });

  it('should call submitTriage and emit triageCompleted on success', () => {
    mockEncounterService.submitTriage.and.returnValue(of(sampleResponse));
    const emitSpy = spyOn(component.triageCompleted, 'emit');

    fillMinimum();
    component.chiefComplaint.set('Headache');
    component.roomAssignment.set('ER-Bay-3');
    component.submit();

    expect(mockEncounterService.submitTriage).toHaveBeenCalledWith(
      'enc-1',
      jasmine.objectContaining({
        esiScore: 3,
        chiefComplaint: 'Headache',
        roomAssignment: 'ER-Bay-3',
      }),
    );
    expect(emitSpy).toHaveBeenCalledWith(sampleResponse);
    expect(mockToastService.success).toHaveBeenCalled();
    expect(component.saving()).toBeFalse();
  });

  it('should show error toast on failure', () => {
    mockEncounterService.submitTriage.and.returnValue(
      throwError(() => ({ status: 400, error: { message: 'Invalid encounter status' } })),
    );
    fillMinimum();

    component.submit();

    expect(mockToastService.error).toHaveBeenCalledWith('Invalid encounter status');
    expect(component.saving()).toBeFalse();
  });

  it('should show generic error when no message in error response', () => {
    mockEncounterService.submitTriage.and.returnValue(throwError(() => ({})));
    fillMinimum();

    component.submit();

    // No translations are loaded in the harness, so instant() echoes the key.
    expect(mockToastService.error).toHaveBeenCalledWith('TRIAGE.SUBMIT_FAILED');
  });
});
