import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NursingIntakeFormComponent } from './nursing-intake-form.component';
import { TranslateModule } from '@ngx-translate/core';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  EncounterService,
  EncounterResponse,
  NursingIntakeResponse,
} from '../../services/encounter.service';
import { ToastService } from '../../core/toast.service';
import { of, throwError } from 'rxjs';

describe('NursingIntakeFormComponent', () => {
  let component: NursingIntakeFormComponent;
  let fixture: ComponentFixture<NursingIntakeFormComponent>;
  let mockEncounterService: jasmine.SpyObj<EncounterService>;
  let mockToastService: jasmine.SpyObj<ToastService>;

  const sampleEncounter: Partial<EncounterResponse> = {
    id: 'enc-1',
    patientFullName: 'Jane Doe',
    patientName: 'Jane Doe',
    chiefComplaint: 'Headache',
    status: 'WAITING_FOR_PHYSICIAN',
  };

  const sampleResponse: NursingIntakeResponse = {
    encounterId: 'enc-1',
    encounterStatus: 'IN_PROGRESS',
    intakeTimestamp: '2026-04-10T10:15:00',
    allergyCount: 2,
    medicationCount: 1,
    nursingNoteRecorded: true,
  };

  beforeEach(async () => {
    mockEncounterService = jasmine.createSpyObj('EncounterService', ['submitNursingIntake']);
    mockToastService = jasmine.createSpyObj('ToastService', ['success', 'error']);

    await TestBed.configureTestingModule({
      imports: [NursingIntakeFormComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: EncounterService, useValue: mockEncounterService },
        { provide: ToastService, useValue: mockToastService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(NursingIntakeFormComponent);
    component = fixture.componentInstance;
    component.encounter = sampleEncounter as EncounterResponse;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should allow submit when encounter is set and not saving', () => {
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

  it('should add and remove allergies', () => {
    expect(component.allergies().length).toBe(0);

    component.addAllergy();
    component.addAllergy();
    expect(component.allergies().length).toBe(2);

    component.removeAllergy(0);
    expect(component.allergies().length).toBe(1);
  });

  it('should add and remove medications', () => {
    expect(component.medications().length).toBe(0);

    component.addMedication();
    expect(component.medications().length).toBe(1);

    component.removeMedication(0);
    expect(component.medications().length).toBe(0);
  });

  it('should call submitNursingIntake and emit intakeCompleted on success', () => {
    mockEncounterService.submitNursingIntake.and.returnValue(of(sampleResponse));
    const emitSpy = spyOn(component.intakeCompleted, 'emit');

    component.nursingAssessmentNotes.set('Alert and oriented x3');
    component.chiefComplaint.set('Headache persists');
    component.submit();

    expect(mockEncounterService.submitNursingIntake).toHaveBeenCalledWith(
      'enc-1',
      jasmine.objectContaining({
        nursingAssessmentNotes: 'Alert and oriented x3',
        chiefComplaint: 'Headache persists',
      }),
    );
    expect(emitSpy).toHaveBeenCalledWith(sampleResponse);
    expect(mockToastService.success).toHaveBeenCalled();
    expect(component.saving()).toBeFalse();
  });

  it('should filter empty allergy entries before submitting', () => {
    mockEncounterService.submitNursingIntake.and.returnValue(of(sampleResponse));

    component.addAllergy();
    component.addAllergy();
    // set only first allergen display
    component.allergies()[0].allergenDisplay = 'Penicillin';
    // second has empty allergenDisplay
    component.submit();

    const submittedReq = mockEncounterService.submitNursingIntake.calls.mostRecent().args[1];
    expect(submittedReq.allergies!.length).toBe(1);
    expect(submittedReq.allergies![0].allergenDisplay).toBe('Penicillin');
  });

  it('should show error toast on failure', () => {
    mockEncounterService.submitNursingIntake.and.returnValue(
      throwError(() => ({
        error: { message: 'Invalid encounter status' },
      })),
    );

    component.submit();

    expect(mockToastService.error).toHaveBeenCalledWith('Invalid encounter status');
    expect(component.saving()).toBeFalse();
  });

  it('should show generic error when no message in error response', () => {
    mockEncounterService.submitNursingIntake.and.returnValue(throwError(() => ({})));

    component.submit();

    expect(mockToastService.error).toHaveBeenCalledWith(
      'Failed to submit nursing intake. Please try again.',
    );
  });

  it('should show error toast when no encounter selected', () => {
    component.encounter = null;
    component.submit();
    expect(mockToastService.error).toHaveBeenCalledWith('No encounter selected for nursing intake');
  });

  it('should track items by index', () => {
    expect(component.trackByIndex(5)).toBe(5);
  });
});
