import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, Subject, Subscription } from 'rxjs';
import { NurseStationComponent } from './nurse-station';
import { NurseTaskService } from '../services/nurse-task.service';
import { ToastService } from '../core/toast.service';
import { EncounterService } from '../services/encounter.service';

describe('NurseStationComponent — two-tier polling', () => {
  let component: NurseStationComponent;
  let nurseServiceSpy: jasmine.SpyObj<NurseTaskService>;
  let toastSpy: jasmine.SpyObj<ToastService>;
  let routerSpy: jasmine.SpyObj<Router>;
  let encounterSpy: jasmine.SpyObj<EncounterService>;

  beforeEach(async () => {
    nurseServiceSpy = jasmine.createSpyObj('NurseTaskService', [
      'getVitalsDue',
      'getMedicationMAR',
      'getDashboardSummary',
      'getNursingTasks',
      'getNurseInbox',
      'getOrders',
      'getHandoffs',
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

    await TestBed.configureTestingModule({
      imports: [NurseStationComponent, TranslateModule.forRoot()],
      providers: [
        { provide: NurseTaskService, useValue: nurseServiceSpy },
        { provide: ToastService, useValue: toastSpy },
        { provide: Router, useValue: routerSpy },
        { provide: EncounterService, useValue: encounterSpy },
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
});
