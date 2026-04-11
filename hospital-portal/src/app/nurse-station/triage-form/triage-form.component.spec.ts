import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TriageFormComponent } from './triage-form.component';
import { TranslateModule } from '@ngx-translate/core';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  EncounterService,
  EncounterResponse,
  TriageSubmissionResponse,
} from '../../services/encounter.service';
import { ToastService } from '../../core/toast.service';
import { of, throwError } from 'rxjs';

describe('TriageFormComponent', () => {
  let component: TriageFormComponent;
  let fixture: ComponentFixture<TriageFormComponent>;
  let mockEncounterService: jasmine.SpyObj<EncounterService>;
  let mockToastService: jasmine.SpyObj<ToastService>;

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

    await TestBed.configureTestingModule({
      imports: [TriageFormComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: EncounterService, useValue: mockEncounterService },
        { provide: ToastService, useValue: mockToastService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(TriageFormComponent);
    component = fixture.componentInstance;
    component.encounter = sampleEncounter as EncounterResponse;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should allow submit when ESI score is valid and encounter is set', () => {
    component.esiScore.set(3);
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

    component.esiScore.set(3);
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
      throwError(() => ({ error: { message: 'Invalid encounter status' } })),
    );

    component.submit();

    expect(mockToastService.error).toHaveBeenCalledWith('Invalid encounter status');
    expect(component.saving()).toBeFalse();
  });

  it('should show generic error when no message in error response', () => {
    mockEncounterService.submitTriage.and.returnValue(throwError(() => ({})));

    component.submit();

    expect(mockToastService.error).toHaveBeenCalledWith(
      'Failed to submit triage. Please try again.',
    );
  });
});
