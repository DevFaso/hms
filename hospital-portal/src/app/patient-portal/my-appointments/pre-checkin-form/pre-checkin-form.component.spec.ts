import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { PreCheckinFormComponent } from './pre-checkin-form.component';
import {
  PatientPortalService,
  PortalAppointment,
  QuestionnaireDTO,
} from '../../../services/patient-portal.service';
import { TranslateModule } from '@ngx-translate/core';
import { FormsModule } from '@angular/forms';

describe('PreCheckinFormComponent', () => {
  let component: PreCheckinFormComponent;
  let fixture: ComponentFixture<PreCheckinFormComponent>;
  let portalSpy: jasmine.SpyObj<PatientPortalService>;

  const mockAppointment: PortalAppointment = {
    id: 'appt-1',
    date: '2025-08-15',
    startTime: '09:00',
    endTime: '09:30',
    providerName: 'Dr. Smith',
    department: 'Cardiology',
    reason: 'Checkup',
    status: 'SCHEDULED',
    location: 'Room 101',
    preCheckedIn: false,
  };

  const mockQuestionnaires: QuestionnaireDTO[] = [
    {
      id: 'q1',
      title: 'Health Screening',
      description: 'Pre-visit health questions',
      questions: JSON.stringify([
        { id: 'q1-1', text: 'Do you have allergies?', type: 'YES_NO' },
        { id: 'q1-2', text: 'Any current medications?', type: 'TEXT' },
      ]),
      version: 1,
      departmentId: 'dept-1',
      departmentName: 'Cardiology',
    },
  ];

  beforeEach(async () => {
    portalSpy = jasmine.createSpyObj('PatientPortalService', [
      'getQuestionnairesForAppointment',
      'submitPreCheckIn',
    ]);
    portalSpy.getQuestionnairesForAppointment.and.returnValue(of(mockQuestionnaires));

    await TestBed.configureTestingModule({
      imports: [PreCheckinFormComponent, TranslateModule.forRoot(), FormsModule],
      providers: [{ provide: PatientPortalService, useValue: portalSpy }],
    }).compileComponents();

    fixture = TestBed.createComponent(PreCheckinFormComponent);
    component = fixture.componentInstance;
    component.appointment = mockAppointment;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should load questionnaires on init', () => {
    expect(portalSpy.getQuestionnairesForAppointment).toHaveBeenCalledWith('appt-1');
    expect(component.questionnaires().length).toBe(1);
  });

  it('should parse questionnaire questions', () => {
    const questions = component.parsedQuestions().get('q1');
    expect(questions?.length).toBe(2);
    expect(questions?.[0].text).toBe('Do you have allergies?');
  });

  it('should initialise answers map', () => {
    const answers = component.answers().get('q1');
    expect(answers).toBeDefined();
    expect(answers?.['q1-1']).toBe(false);
    expect(answers?.['q1-2']).toBe('');
  });

  it('should navigate between steps', () => {
    expect(component.step()).toBe('demographics');
    component.goToStep('questionnaires');
    expect(component.step()).toBe('questionnaires');
    component.goToStep('review');
    expect(component.step()).toBe('review');
  });

  it('should update answer on change', () => {
    component.onAnswerChange('q1', 'q1-1', true);
    expect(component.answers().get('q1')?.['q1-1']).toBe(true);
  });

  it('should not allow submit without consent', () => {
    component.consentAcknowledged = false;
    expect(component.canSubmit).toBeFalse();
  });

  it('should allow submit with consent', () => {
    component.consentAcknowledged = true;
    expect(component.canSubmit).toBeTrue();
  });

  it('should emit dismissed on close', () => {
    spyOn(component.dismissed, 'emit');
    component.close();
    expect(component.dismissed.emit).toHaveBeenCalled();
  });

  it('should submit pre-check-in successfully', (done) => {
    portalSpy.submitPreCheckIn.and.returnValue(
      of({
        appointmentId: 'appt-1',
        appointmentStatus: 'SCHEDULED',
        preCheckedIn: true,
        preCheckinTimestamp: '2025-08-08T10:00:00',
        questionnaireResponsesSubmitted: 1,
        demographicsUpdated: false,
      }),
    );

    spyOn(component.preCheckinCompleted, 'emit');
    component.consentAcknowledged = true;
    component.submit();

    // of() is synchronous, so the subscribe callback has already run
    expect(component.saving()).toBeFalse();
    expect(component.successMessage()).toBeTruthy();

    // preCheckinCompleted emits after a 1500ms setTimeout inside submit()
    setTimeout(() => {
      expect(component.preCheckinCompleted.emit).toHaveBeenCalled();
      done();
    }, 1600);
  });

  it('should show error on submit failure', () => {
    portalSpy.submitPreCheckIn.and.returnValue(
      throwError(() => ({ error: { message: 'Too early' } })),
    );

    component.consentAcknowledged = true;
    component.submit();

    expect(component.saving()).toBeFalse();
    expect(component.errorMessage()).toBe('Too early');
  });

  it('should not submit when already saving', () => {
    component.saving.set(true);
    component.submit();
    expect(portalSpy.submitPreCheckIn).not.toHaveBeenCalled();
  });

  it('should not submit without appointment', () => {
    component.appointment = null;
    component.consentAcknowledged = true;
    component.submit();
    expect(portalSpy.submitPreCheckIn).not.toHaveBeenCalled();
  });
});
