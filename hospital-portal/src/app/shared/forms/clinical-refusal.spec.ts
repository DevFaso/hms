import { Type } from '@angular/core';
import { TestBed, ComponentFixture } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { DischargeComponent } from '../../discharge/discharge';
import { LabResultsComponent } from '../../lab/lab-results/lab-results';
import { PatientChartComponent } from '../../patients/patient-chart/patient-chart.component';
import { PatientFormComponent } from '../../patients/patient-form';
import { ConsultationsComponent } from '../../consultations/consultations';
import { NurseStationComponent } from '../../nurse-station/nurse-station';

describe('Clinical refusals mark and focus the rendered field before any write', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [TranslateModule.forRoot()],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }),
  );

  afterEach(() => {
    const http = TestBed.inject(HttpTestingController);
    http.expectNone((r) => ['POST', 'PUT', 'PATCH', 'DELETE'].includes(r.method));
    // Reads caused by child pickers are irrelevant to the refused write.
    for (const request of http.match(() => true)) request.flush([]);
  });

  function create<T extends { ngOnInit(): void }>(type: Type<T>): ComponentFixture<T> {
    spyOn(type.prototype as { ngOnInit(): void }, 'ngOnInit').and.stub();
    return TestBed.createComponent(type);
  }

  function announced<T>(fixture: ComponentFixture<T>, id: string): void {
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const field = host.querySelector<HTMLElement>(`#${id}`)!;
    expect(field).withContext(`real template contains #${id}`).not.toBeNull();
    expect(field.getAttribute('aria-invalid')).toBe('true');
    expect(document.activeElement).toBe(field);
    const messages = (field.getAttribute('aria-describedby') ?? '')
      .split(/\s+/)
      .filter(Boolean)
      .map((key) => host.querySelector<HTMLElement>(`#${key}`))
      .filter((node) => node?.getAttribute('role') === 'alert');
    expect(messages.some((node) => !!node?.textContent?.trim())).toBeTrue();
  }

  it('lab entry points to the missing order', () => {
    const f = create(LabResultsComponent);
    const c = f.componentInstance;
    c.showModal.set(true);
    f.detectChanges();
    c.submitForm();
    announced(f, 'lr-orderId');
  });

  for (const mode of ['approve', 'reject'] as const) {
    it(`discharge ${mode} names its missing diagnosis/reason`, () => {
      const f = create(DischargeComponent);
      const c = f.componentInstance;
      Object.assign(c, { staffId: 'staff', hospitalId: 'hospital', assignmentId: 'assignment' });
      c.decisionTarget.set({ id: 'decision' } as never);
      c.decisionMode.set(mode);
      c.showDecisionModal.set(true);
      f.detectChanges();
      c.submitDecision();
      announced(f, mode === 'approve' ? 'decision-diagnosis' : 'decision-note');
    });
  }
  it('discharge summary marks all four blank fields and focuses the patient picker', () => {
    const f = create(DischargeComponent);
    const c = f.componentInstance;
    Object.assign(c, { staffId: 'staff', hospitalId: 'hospital', assignmentId: 'assignment' });
    c.summaryForm.dischargeDate = '';
    c.showSummaryModal.set(true);
    f.detectChanges();
    c.submitSummary();
    announced(f, 'summary-patient');
    for (const id of ['summary-encounter', 'summary-date', 'summary-diagnosis'])
      expect(
        (f.nativeElement as HTMLElement).querySelector(`#${id}`)?.getAttribute('aria-invalid'),
      ).toBe('true');
  });
  it('discharge finalization points to the missing signature', () => {
    const f = create(DischargeComponent);
    const c = f.componentInstance;
    Object.assign(c, { staffId: 'staff' });
    c.finalizeTarget.set({ id: 'summary' } as never);
    c.showFinalizeModal.set(true);
    f.detectChanges();
    c.submitFinalize();
    announced(f, 'finalize-signature');
  });

  const chartCases = [
    ['allergy', 'showAllergyModal', 'submitAllergy', 'allergy-name'],
    [
      'allergy deactivation',
      'showAllergyDeactivate',
      'submitDeactivateAllergy',
      'deactivate-reason',
    ],
    ['problem', 'showProblemModal', 'submitProblem', 'problem-name'],
    ['problem deletion', 'showProblemDelete', 'submitDeleteProblem', 'problem-delete-reason'],
    ['chart update', 'showUpdateModal', 'submitUpdate', 'update-reason'],
    ['timeline access', 'showTimelineReason', 'submitTimelineReason', 'timeline-reason'],
  ] as const;
  for (const [name, modal, method, field] of chartCases) {
    it(`${name} points to its blank field`, () => {
      const f = create(PatientChartComponent);
      const c = f.componentInstance;
      spyOn(c as unknown as { hospitalId(): string }, 'hospitalId').and.returnValue('hospital');
      c[modal].set(true);
      f.detectChanges();
      c[method]();
      announced(f, field);
    });
  }

  const consultationCases = [
    ['showDeleteConfirm', 'executeCancel', 'cancel-reason'],
    ['showAssignModal', 'submitAssign', 'assign-consultant'],
    ['showScheduleModal', 'submitSchedule', 'sched-at'],
    ['showCompleteModal', 'submitComplete', 'complete-recommendations'],
    ['showDeclineModal', 'submitDecline', 'decline-reason'],
  ] as const;
  for (const [modal, method, field] of consultationCases) {
    it(`consultation ${method} points to its blank field`, () => {
      const f = create(ConsultationsComponent);
      const c = f.componentInstance;
      c[modal].set(true);
      f.detectChanges();
      c[method]();
      announced(f, field);
    });
  }
  it('consultation reassignment points to the required reason', () => {
    const f = create(ConsultationsComponent);
    const c = f.componentInstance;
    spyOn(c, 'isReassign').and.returnValue(true);
    c.assignConsultantId.set('colleague');
    c.assigningItem.set({ id: 'consultation' } as never);
    c.showAssignModal.set(true);
    f.detectChanges();
    c.submitAssign();
    announced(f, 'assign-note');
  });
  it('consultation creation still marks the missing patient', () => {
    const f = create(ConsultationsComponent);
    const c = f.componentInstance;
    c.showModal.set(true);
    f.detectChanges();
    c.submitForm();
    announced(f, 'con-patientSearch');
  });

  for (const flow of ['handoff', 'task'] as const) {
    it(`nursing ${flow} points to the patient picker`, () => {
      const f = create(NurseStationComponent);
      const c = f.componentInstance;
      spyOn(c, 'canCreateNursing').and.returnValue(true);
      if (flow === 'handoff') c.handoffCreateOpen.set(true);
      else c.taskCreateOpen.set(true);
      f.detectChanges();
      if (flow === 'handoff') c.submitCreateHandoff();
      else c.submitCreateTask();
      announced(f, flow === 'handoff' ? 'ho-patient' : 'tc-patient');
    });
  }
  for (const field of ['ho-situation', 'ho-recipient']) {
    it(`handoff points to missing ${field}`, () => {
      const f = create(NurseStationComponent);
      const c = f.componentInstance;
      spyOn(c, 'canCreateNursing').and.returnValue(true);
      c.handoffForm.set({
        patientId: 'patient',
        direction: 'OUTGOING',
        ...(field === 'ho-recipient' ? { situation: 'Situation' } : {}),
      } as never);
      c.handoffCreateOpen.set(true);
      f.detectChanges();
      c.submitCreateHandoff();
      announced(f, field);
    });
  }
  for (const needsWitness of [false, true]) {
    it(`medication reason dialog marks ${needsWitness ? 'witness' : 'reason'}`, () => {
      const f = create(NurseStationComponent);
      const c = f.componentInstance;
      spyOn(c, 'canDocumentCare').and.returnValue(true);
      spyOn(c, 'promptNeedsWitness').and.returnValue(needsWitness);
      c.reasonPrompt.set({ taskId: 'task', action: needsWitness ? 'GIVEN' : 'HELD' });
      c.reasonText.set(needsWitness ? 'Documented reason' : '');
      f.detectChanges();
      c.confirmHoldRefuse();
      announced(f, needsWitness ? 'mar-witness' : 'mar-reason-input');
    });
  }
  it('phone verification marks its existing phone field and alert', () => {
    const f = create(PatientFormComponent);
    const c = f.componentInstance;
    f.detectChanges();
    c.sendPhoneVerification();
    announced(f, 'phonePrimary');
  });
});
