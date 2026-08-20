import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, Subject, Subscription } from 'rxjs';
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

    authSpy = jasmine.createSpyObj('AuthService', ['getHospitalId']);
    authSpy.getHospitalId.and.returnValue('h1');
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

    const fixture = TestBed.createComponent(NurseStationComponent);
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

  /* ── P0 #1: real SBAR handoffs ─────────────────────────────── */

  it('submitCreateHandoff requires patient and direction before posting', () => {
    component.openHandoffCreate();
    component.submitCreateHandoff();
    expect(toastSpy.error).toHaveBeenCalled();
    expect(nurseServiceSpy.createHandoff).not.toHaveBeenCalled();
  });

  it('submitCreateHandoff posts the SBAR form, toasts, and closes the dialog', () => {
    const created: NurseHandoff = {
      id: 'h9',
      patientId: 'p1',
      patientName: 'Bea Ward',
      direction: 'Shift change',
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
    component.updateHandoffField('direction', 'Shift change');
    component.updateHandoffField('situation', 'Stable.');
    component.submitCreateHandoff();

    expect(nurseServiceSpy.createHandoff).toHaveBeenCalledWith(
      jasmine.objectContaining({
        patientId: 'p1',
        direction: 'Shift change',
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
});
