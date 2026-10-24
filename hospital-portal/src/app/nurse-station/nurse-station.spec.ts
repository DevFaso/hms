import { formatDate } from '@angular/common';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of, Subject, Subscription, throwError } from 'rxjs';
import { NurseStationComponent } from './nurse-station';
import { NurseTaskService, NurseFlowBoard, NurseHandoff } from '../services/nurse-task.service';
import { PatientResponse } from '../services/patient.service';
import { ToastService } from '../core/toast.service';
import { EncounterService } from '../services/encounter.service';
import { AuthService } from '../auth/auth.service';
import {
  PatientTrackerWsService,
  PatientTrackerEvent,
} from '../services/patient-tracker-ws.service';

describe('NurseStationComponent — two-tier polling', () => {
  let component: NurseStationComponent;
  // Describe-scoped so the layout tests below can assert on rendered DOM;
  // the polling tests that predate them only ever needed the instance.
  let fixture: ComponentFixture<NurseStationComponent>;
  let nurseServiceSpy: jasmine.SpyObj<NurseTaskService>;
  let toastSpy: jasmine.SpyObj<ToastService>;
  let routerSpy: jasmine.SpyObj<Router>;
  let encounterSpy: jasmine.SpyObj<EncounterService>;
  let authSpy: jasmine.SpyObj<AuthService>;
  let trackerWsSpy: jasmine.SpyObj<PatientTrackerWsService>;
  let wsEvents$: Subject<PatientTrackerEvent>;

  beforeEach(async () => {
    nurseServiceSpy = jasmine.createSpyObj('NurseTaskService', [
      'getVitalsDue',
      'getMedicationMAR',
      'getDashboardSummary',
      'getNursingTasks',
      'getNurseInbox',
      'getOrders',
      'getHandoffs',
      'createHandoff',
      'completeHandoff',
      'getAnnouncements',
      'getWorkboard',
      'getPatientFlow',
      'getPendingAdmissions',
      'captureVitals',
      'administerMedication',
    ]);
    toastSpy = jasmine.createSpyObj('ToastService', ['info', 'success', 'error', 'warn']);
    routerSpy = jasmine.createSpyObj('Router', ['navigate']);
    encounterSpy = jasmine.createSpyObj('EncounterService', ['list']);
    encounterSpy.list.and.returnValue(
      of({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 } as any),
    );

    // All service methods return empty observables by default
    nurseServiceSpy.getVitalsDue.and.returnValue(of([]));
    nurseServiceSpy.getMedicationMAR.and.returnValue(of([]));
    nurseServiceSpy.getDashboardSummary.and.returnValue(of(null as any));
    nurseServiceSpy.getNursingTasks.and.returnValue(of([]));
    nurseServiceSpy.getNurseInbox.and.returnValue(of([]));
    nurseServiceSpy.getOrders.and.returnValue(of([]));
    nurseServiceSpy.getHandoffs.and.returnValue(of([]));
    nurseServiceSpy.getAnnouncements.and.returnValue(of([]));
    nurseServiceSpy.getWorkboard.and.returnValue(of([]));
    nurseServiceSpy.getPatientFlow.and.returnValue(of(null as any));
    nurseServiceSpy.getPendingAdmissions.and.returnValue(of([]));

    authSpy = jasmine.createSpyObj('AuthService', ['getHospitalId', 'hasAnyRole']);
    authSpy.getHospitalId.and.returnValue('h1');
    authSpy.hasAnyRole.and.returnValue(false);
    wsEvents$ = new Subject<PatientTrackerEvent>();
    trackerWsSpy = jasmine.createSpyObj('PatientTrackerWsService', [
      'connect',
      'disconnect',
      'getEvents',
      'getConnectionState',
    ]);
    trackerWsSpy.getEvents.and.returnValue(wsEvents$.asObservable());
    trackerWsSpy.getConnectionState.and.returnValue(of(false));

    await TestBed.configureTestingModule({
      imports: [NurseStationComponent, TranslateModule.forRoot()],
      providers: [
        { provide: NurseTaskService, useValue: nurseServiceSpy },
        { provide: ToastService, useValue: toastSpy },
        { provide: Router, useValue: routerSpy },
        { provide: EncounterService, useValue: encounterSpy },
        { provide: AuthService, useValue: authSpy },
        { provide: PatientTrackerWsService, useValue: trackerWsSpy },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(NurseStationComponent);
    component = fixture.componentInstance;
  });

  afterEach(() => {
    // Ensure subscriptions are cleaned up between tests
    component.ngOnDestroy();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('ngOnInit creates a fast-tier subscription (refreshSub)', () => {
    component.ngOnInit();
    const fastSub: Subscription | undefined = (component as any).refreshSub;
    expect(fastSub).toBeDefined();
    expect(fastSub?.closed).toBeFalse();
  });

  it('ngOnInit creates a slow-tier subscription (slowRefreshSub)', () => {
    component.ngOnInit();
    const slowSub: Subscription | undefined = (component as any).slowRefreshSub;
    expect(slowSub).toBeDefined();
    expect(slowSub?.closed).toBeFalse();
  });

  it('fast poll (60 s) calls fast-tier services on init', () => {
    // ngOnInit triggers one immediate fast load and starts the interval
    component.ngOnInit();
    expect(nurseServiceSpy.getVitalsDue).toHaveBeenCalled();
    expect(nurseServiceSpy.getMedicationMAR).toHaveBeenCalled();
    expect(nurseServiceSpy.getNursingTasks).toHaveBeenCalled();
    expect(nurseServiceSpy.getNurseInbox).toHaveBeenCalled();
    // Slow-tier calls happen via loadSlow$ on init too, but let us verify
    // that fast-tier services are distinct from slow-tier ones:
    expect(nurseServiceSpy.getOrders).toHaveBeenCalled();
  });

  it('ngOnDestroy closes the fast-tier subscription', () => {
    component.ngOnInit();
    const fastSub: Subscription = (component as any).refreshSub;
    spyOn(fastSub, 'unsubscribe').and.callThrough();

    component.ngOnDestroy();

    expect(fastSub.unsubscribe).toHaveBeenCalled();
  });

  it('ngOnDestroy closes the slow-tier subscription', () => {
    component.ngOnInit();
    const slowSub: Subscription = (component as any).slowRefreshSub;
    spyOn(slowSub, 'unsubscribe').and.callThrough();

    component.ngOnDestroy();

    expect(slowSub.unsubscribe).toHaveBeenCalled();
  });

  it('both subscriptions are closed after ngOnDestroy', () => {
    component.ngOnInit();
    component.ngOnDestroy();

    expect((component as any).refreshSub?.closed).toBeTrue();
    expect((component as any).slowRefreshSub?.closed).toBeTrue();
  });

  it('keeps loading true until both initial fast and slow loads complete', () => {
    const vitals$ = new Subject<any[]>();
    const medications$ = new Subject<any[]>();
    const summary$ = new Subject<any>();
    const nursingTasks$ = new Subject<any[]>();
    const inbox$ = new Subject<any[]>();
    const orders$ = new Subject<any[]>();
    const handoffs$ = new Subject<any[]>();
    const announcements$ = new Subject<any[]>();
    const workboard$ = new Subject<any[]>();
    const flowBoard$ = new Subject<any>();
    const pendingAdmissions$ = new Subject<any[]>();

    nurseServiceSpy.getVitalsDue.and.returnValue(vitals$ as any);
    nurseServiceSpy.getMedicationMAR.and.returnValue(medications$ as any);
    nurseServiceSpy.getDashboardSummary.and.returnValue(summary$ as any);
    nurseServiceSpy.getNursingTasks.and.returnValue(nursingTasks$ as any);
    nurseServiceSpy.getNurseInbox.and.returnValue(inbox$ as any);
    nurseServiceSpy.getOrders.and.returnValue(orders$ as any);
    nurseServiceSpy.getHandoffs.and.returnValue(handoffs$ as any);
    nurseServiceSpy.getAnnouncements.and.returnValue(announcements$ as any);
    nurseServiceSpy.getWorkboard.and.returnValue(workboard$ as any);
    nurseServiceSpy.getPatientFlow.and.returnValue(flowBoard$ as any);
    nurseServiceSpy.getPendingAdmissions.and.returnValue(pendingAdmissions$ as any);

    component.ngOnInit();

    expect(component.loading()).toBeTrue();

    vitals$.next([]);
    vitals$.complete();
    medications$.next([]);
    medications$.complete();
    summary$.next(null);
    summary$.complete();
    nursingTasks$.next([]);
    nursingTasks$.complete();
    inbox$.next([]);
    inbox$.complete();

    expect(component.loading()).toBeTrue();

    orders$.next([]);
    orders$.complete();
    handoffs$.next([]);
    handoffs$.complete();
    announcements$.next([]);
    announcements$.complete();
    workboard$.next([]);
    workboard$.complete();
    flowBoard$.next(null);
    flowBoard$.complete();
    pendingAdmissions$.next([]);
    pendingAdmissions$.complete();

    expect(component.loading()).toBeFalse();
  });

  /* ── Task 24: live tracker events ─────────────────────────── */

  function mockTrackerEvent(): PatientTrackerEvent {
    return {
      hospitalId: 'h1',
      departmentId: null,
      encounterId: 'e1',
      patientId: 'p1',
      previousStatus: 'IN_PROGRESS',
      newStatus: 'READY_FOR_DISCHARGE',
      emittedAt: '2026-08-20T10:00:00',
    };
  }

  it('ngOnInit connects the shared tracker socket for the active hospital', () => {
    component.ngOnInit();
    expect(trackerWsSpy.getEvents).toHaveBeenCalled();
    expect(trackerWsSpy.connect).toHaveBeenCalledWith('h1');
  });

  it('does not touch the socket when no hospital is active', () => {
    authSpy.getHospitalId.and.returnValue(null);
    component.ngOnInit();
    expect(trackerWsSpy.connect).not.toHaveBeenCalled();
  });

  it('ngOnDestroy releases the shared socket', () => {
    component.ngOnInit();
    component.ngOnDestroy();
    expect(trackerWsSpy.disconnect).toHaveBeenCalled();
  });

  it('a tracker event triggers one debounced flow-panel refetch', (done) => {
    component.ngOnInit();
    nurseServiceSpy.getPatientFlow.calls.reset();
    nurseServiceSpy.getWorkboard.calls.reset();
    nurseServiceSpy.getPendingAdmissions.calls.reset();

    // Burst of transitions (e.g. discharge auto-completes several
    // encounters) must collapse into a single refetch.
    wsEvents$.next(mockTrackerEvent());
    wsEvents$.next(mockTrackerEvent());
    wsEvents$.next(mockTrackerEvent());

    expect(nurseServiceSpy.getPatientFlow).not.toHaveBeenCalled(); // debounced

    setTimeout(() => {
      expect(nurseServiceSpy.getPatientFlow).toHaveBeenCalledTimes(1);
      expect(nurseServiceSpy.getWorkboard).toHaveBeenCalledTimes(1);
      expect(nurseServiceSpy.getPendingAdmissions).toHaveBeenCalledTimes(1);
      done();
    }, 2_300);
  });

  it('loadFlow$ patches the flow-facing signals', () => {
    const board = { columns: [] } as unknown as NurseFlowBoard;
    nurseServiceSpy.getPatientFlow.and.returnValue(of(board));
    nurseServiceSpy.getWorkboard.and.returnValue(of([{ patientId: 'p1' } as never]));
    component.loadFlow$().subscribe();
    expect(component.flowBoard()).toBe(board);
    expect(component.workboard().length).toBe(1);
    expect(component.lastRefreshed()).toBeTruthy();
  });

  /* ── QA D408: triage entry for outpatients on the workboard ─── */

  describe('awaiting triage (D408)', () => {
    const arrived = {
      id: 'e1',
      patientName: 'Out Patient',
      status: 'ARRIVED',
      encounterType: 'OUTPATIENT',
      encounterDate: '2026-10-20T08:00:00',
    } as never;
    const stay = { id: 'e2', status: 'ARRIVED', encounterType: 'INPATIENT' } as never;

    beforeEach(() => {
      encounterSpy.list.and.callFake(((f: { status?: string }) =>
        of(f?.status === 'ARRIVED' ? [arrived, stay] : [])) as never);
    });

    it('a nurse sees ARRIVED/TRIAGE outpatients and opens the triage form on one', () => {
      authSpy.hasAnyRole.and.callFake((r: string[]) => r.includes('ROLE_NURSE'));
      component.loadFlow$().subscribe();
      const d = new Date();
      const p = (n: number) => String(n).padStart(2, '0');
      const fromDate = `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T00:00:00`;
      expect(encounterSpy.list).toHaveBeenCalledWith({ status: 'ARRIVED', fromDate, size: 100 });
      expect(encounterSpy.list).toHaveBeenCalledWith({ status: 'TRIAGE', fromDate, size: 100 });
      expect(component.awaitingTriage().map((e) => e.id)).toEqual(['e1']);

      component.activeSection.set('workboard');
      component['pendingLoads'].set(0);
      fixture.detectChanges();
      const btn = fixture.nativeElement.querySelector(
        '[data-testid="awaiting-triage-open"]',
      ) as HTMLButtonElement;
      expect(btn).withContext('triage action on the nurse-station workboard').toBeTruthy();
      btn.click();
      fixture.detectChanges();
      expect(component.triageEncounter()?.id).toBe('e1');
      expect(fixture.nativeElement.querySelector('app-triage-form')).toBeTruthy();
    });

    it('a non-nurse gets no triage list and no encounter query', () => {
      component.loadFlow$().subscribe();
      expect(encounterSpy.list).not.toHaveBeenCalled();
      expect(component.awaitingTriage()).toEqual([]);
    });
  });

  /* ── P0 #1: real SBAR handoffs ─────────────────────────────── */

  it('submitCreateHandoff requires patient and direction before posting', () => {
    component.openHandoffCreate();
    component.submitCreateHandoff();
    expect(toastSpy.error).toHaveBeenCalled();
    expect(nurseServiceSpy.createHandoff).not.toHaveBeenCalled();
  });

  it('submitCreateHandoff refuses an empty Situation (QA D331)', () => {
    component.openHandoffCreate();
    component.onHandoffPatientChange({ id: 'p1' } as PatientResponse);
    component.updateHandoffField('direction', 'SHIFT_CHANGE');
    component.updateHandoffField('situation', '   ');
    component.submitCreateHandoff();
    expect(toastSpy.error).toHaveBeenCalledWith('NURSE.TOAST.HANDOFF_SITUATION_REQUIRED');
    expect(nurseServiceSpy.createHandoff).not.toHaveBeenCalled();
  });

  it('submitCreateHandoff posts the SBAR form, toasts, and closes the dialog', () => {
    const created: NurseHandoff = {
      id: 'h9',
      patientId: 'p1',
      patientName: 'Bea Ward',
      direction: 'SHIFT_CHANGE',
      updatedAt: '2026-08-20T10:00:00',
      note: 'Stable.',
      background: null,
      assessment: null,
      recommendation: null,
      status: 'PENDING',
      createdByName: 'Nina Nurse',
    };
    nurseServiceSpy.createHandoff.and.returnValue(of(created));

    component.openHandoffCreate();
    component.onHandoffPatientChange({ id: 'p1' } as PatientResponse);
    component.updateHandoffField('direction', 'SHIFT_CHANGE');
    component.updateHandoffField('situation', 'Stable.');
    component.submitCreateHandoff();

    expect(nurseServiceSpy.createHandoff).toHaveBeenCalledWith(
      jasmine.objectContaining({
        patientId: 'p1',
        direction: 'SHIFT_CHANGE',
        situation: 'Stable.',
      }),
    );
    expect(toastSpy.success).toHaveBeenCalled();
    expect(component.handoffCreateOpen()).toBeFalse();
  });

  it('completeHandoff calls the service and refreshes', () => {
    nurseServiceSpy.completeHandoff.and.returnValue(of(void 0));
    component.completeHandoff('h1');
    expect(nurseServiceSpy.completeHandoff).toHaveBeenCalledWith('h1');
    expect(toastSpy.success).toHaveBeenCalled();
  });

  it('the completed-handoffs view requests status=COMPLETED — the parameter that had no caller', () => {
    // PR #462 added ?status= server-side; the 2026-08-22 re-verification found
    // the portal service physically could not send it. This pins the caller.
    nurseServiceSpy.getHandoffs.calls.reset();
    nurseServiceSpy.getHandoffs.and.returnValue(of([]));

    component.setHandoffView('COMPLETED');

    expect(nurseServiceSpy.getHandoffs).toHaveBeenCalledWith(
      jasmine.objectContaining({ status: 'COMPLETED' }),
    );
    expect(component.handoffView()).toBe('COMPLETED');

    component.setHandoffView('PENDING');
    expect(component.handoffView()).toBe('PENDING');
  });

  // ── Announcements sidebar layout ──────────────────────────────
  //
  // Reported from production: the "No announcements" card sat mid-page,
  // level with the tab row, and the 260px column it reserved squeezed the
  // tabs until Inbox wrapped onto a second line.

  it('renders no announcements sidebar when there is nothing to announce', () => {
    fixture.detectChanges();

    expect(component.announcements().length).toBe(0);
    expect(fixture.nativeElement.querySelector('.announcements-sidebar')).toBeNull();
  });

  it('drops the reserved sidebar column when the sidebar is absent', () => {
    // Without this the content panel stays 260px narrow to hold a sidebar
    // that is not rendered.
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.station-layout.no-sidebar')).not.toBeNull();
  });

  it('renders the sidebar once an announcement exists', () => {
    nurseServiceSpy.getAnnouncements.and.returnValue(
      of([
        {
          id: 'a-1',
          text: 'Fire drill at 14:00',
          category: 'SAFETY',
          createdAt: '2026-08-25T10:00:00',
          startsAt: '2026-08-25T09:00:00',
          expiresAt: '2026-08-26T09:00:00',
        },
      ]),
    );

    component.ngOnInit();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.announcements-sidebar')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.station-layout.no-sidebar')).toBeNull();
  });

  it('keeps the tabs out of the sidebar column so they get the full width', () => {
    // The tabs used to live inside .main-content, sharing the grid row with
    // the sidebar. That is what made them wrap.
    fixture.detectChanges();

    const tabs = fixture.nativeElement.querySelector('.section-tabs');
    expect(tabs).not.toBeNull();
    expect(tabs.closest('.station-layout')).toBeNull();
  });

  it('translates the vitals-round type the server used to spell in English', () => {
    // NurseTaskServiceImpl stamped the words "Full Set" and "Routine", so a
    // French nurse read English here whatever the portal did. It sends
    // FULL_SET / ROUTINE now and the column translates them.
    const translate = TestBed.inject(TranslateService);
    translate.setFallbackLang('fr');
    translate.use('fr');
    translate.setTranslation('fr', {
      PORTAL: { ENUM: { VITAL_TASK_TYPE: { FULL_SET: 'Bilan complet', ROUTINE: 'Routine' } } },
    });
    // ngOnInit runs on the first detectChanges and the spies return of([]),
    // which would overwrite the row below. Let it settle first.
    fixture.detectChanges();
    component.vitals.set([
      {
        id: 'v-1',
        // No patientId: the name cell then renders a plain div instead of a
        // routerLink, so this test needs no router wiring to reach the type cell.
        patientId: '',
        patientName: 'Awa Traoré',
        type: 'FULL_SET',
        dueTime: '2026-09-14T10:00:00',
        overdue: true,
      },
    ]);
    component.activeSection.set('vitals');
    fixture.detectChanges();

    const cells = Array.from(
      fixture.nativeElement.querySelectorAll('td') as NodeListOf<HTMLElement>,
    ).map((el) => (el.textContent ?? '').trim());
    expect(cells).toContain('Bilan complet');
    expect(cells).not.toContain('FULL_SET');
    // Deliberately no assertion against the old server literal "Full Set":
    // this spec sets the signal directly, so that string is not in its
    // universe and the check could never fail. NurseTaskServiceImplTest
    // pins the wire value instead.
  });

  it('refuses an impossible vital reading before it reaches the server (QA D29)', () => {
    component.recordVitals('p1', 'Awa');
    component.updateVitalsField('temperatureCelsius', '45');
    component.updateVitalsField('heartRateBpm', '80');
    component.submitVitals();
    expect(nurseServiceSpy.captureVitals).not.toHaveBeenCalled();
    expect(toastSpy.error).toHaveBeenCalledWith('VITAL_LIMITS.TEMPERATURE');
  });

  it('sends plausible vitals and shows the server field message on a 400', () => {
    nurseServiceSpy.captureVitals.and.returnValue(
      throwError(() => ({ error: { fieldErrors: { spo2Percent: 'SpO2 out of range' } } })),
    );
    component.recordVitals('p1', 'Awa');
    component.updateVitalsField('spo2Percent', '97');
    component.submitVitals();
    expect(nurseServiceSpy.captureVitals).toHaveBeenCalledWith('p1', { spo2Percent: 97 });
    expect(toastSpy.error).toHaveBeenCalledWith('SpO2 out of range');
  });

  it('QA D530: glucose is typed in mmol/L like the lab and sent in mg/dL', () => {
    nurseServiceSpy.captureVitals.and.returnValue(of(undefined));
    component.recordVitals('p1', 'Awa');
    component.updateGlucoseMmol('5.6');
    component.submitVitals();
    expect(nurseServiceSpy.captureVitals).toHaveBeenCalledWith('p1', { bloodGlucoseMgDl: 101 });
  });

  it('QA D530: the vitals modal names the patient, never the English word Patient', () => {
    component.recordVitals('p1', 'Awa Kabore');
    expect(component.vitalsCaptureFor()?.patientName).toBe('Awa Kabore');
    component.recordVitals('p2');
    expect(component.vitalsCaptureFor()?.patientName).not.toBe('Patient');
    expect(component.vitalsCaptureFor()?.patientName).toBe('NURSE.THIS_PATIENT');
  });

  it('picks the task patient by name instead of a raw UUID (QA D45)', () => {
    component.openTaskCreate();
    expect(component.taskForm().patientId).toBeUndefined();
    component.onTaskPatientChange({
      id: 'pat-7',
      firstName: 'Awa',
      lastName: 'K',
    } as PatientResponse);
    expect(component.taskPatient()?.id).toBe('pat-7');
    expect(component.taskForm().patientId).toBe('pat-7');
    component.onTaskPatientChange(null);
    expect(component.taskForm().patientId).toBeUndefined();
    component.closeTaskCreate();
    expect(component.taskPatient()).toBeNull();
  });

  describe('QA round 2 (D78, D130)', () => {
    it('D78: Give asks for an override reason and sends it as overrideReason', () => {
      nurseServiceSpy.administerMedication.and.returnValue(of({} as any));
      component.administerMedication('rx-1');
      expect(nurseServiceSpy.administerMedication).not.toHaveBeenCalled();
      expect(component.reasonPrompt()).toEqual({ taskId: 'rx-1', action: 'GIVEN' });

      component.reasonText.set('Scanner broken on ward');
      component.confirmHoldRefuse();

      expect(nurseServiceSpy.administerMedication).toHaveBeenCalledWith('rx-1', {
        status: 'GIVEN',
        overrideReason: 'Scanner broken on ward',
      });
      expect(component.reasonPrompt()).toBeNull();
    });

    it("D78: a refusal shows the server's localised reason, not the generic toast", () => {
      nurseServiceSpy.administerMedication.and.returnValue(
        throwError(() => ({ error: { message: 'Cette dose a déjà été enregistrée.' } })),
      );
      component.promptHoldRefuse('rx-1', 'HELD');
      component.reasonText.set('NPO');
      component.confirmHoldRefuse();
      expect(toastSpy.error).toHaveBeenCalledWith('Cette dose a déjà été enregistrée.');
    });

    it('D130: Escape closes the nurse task modal', () => {
      component.taskCreateOpen.set(true);
      component.onEscapeKey();
      expect(component.taskCreateOpen()).toBeFalse();
    });

    it('D130: Escape closes the care note modal', () => {
      component.careNoteFor.set({ patientId: 'p1', patientName: 'A' });
      component.onEscapeKey();
      expect(component.careNoteFor()).toBeNull();
    });
  });

  // QA D622: the admission time is stored as UTC wall clock; the pending
  // admissions row must show that instant in the user's zone.
  it('shows the admission time in the user zone (D622)', () => {
    nurseServiceSpy.getPendingAdmissions.and.returnValue(
      of([
        {
          admissionId: 'a1',
          patientName: 'Awa Traore',
          status: 'PENDING',
          admissionDateTime: '2026-10-23T02:30:00',
        } as any,
      ]),
    );
    fixture.detectChanges();
    component.activeSection.set('admissions');
    fixture.detectChanges();
    const expected = formatDate(new Date(Date.UTC(2026, 9, 23, 2, 30)), 'short', 'en-US');
    const cells = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('td')).map(
      (td) => td.textContent?.trim(),
    );
    expect(cells).toContain(expected);
  });
});
