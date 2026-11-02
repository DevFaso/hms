import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { of, throwError } from 'rxjs';
import { PatientTrackerComponent } from './patient-tracker.component';
import {
  PatientTrackerService,
  PatientTrackerBoard,
  PatientTrackerItem,
} from '../services/patient-tracker.service';
import { PatientTrackerWsService } from '../services/patient-tracker-ws.service';
import { AuthService } from '../auth/auth.service';
import {
  EncounterService,
  EncounterResponse,
  AfterVisitSummary,
} from '../services/encounter.service';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import fr from '../../assets/i18n/fr.json';
import es from '../../assets/i18n/es.json';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';

function mockItem(overrides: Partial<PatientTrackerItem> = {}): PatientTrackerItem {
  return {
    patientId: 'p1',
    patientName: 'John Doe',
    mrn: 'MRN-001',
    appointmentId: 'a1',
    encounterId: 'e1',
    currentStatus: 'ARRIVED',
    roomAssignment: 'Room-1',
    assignedProvider: 'Dr Smith',
    departmentName: 'ER',
    arrivalTimestamp: '2025-01-15T08:00:00',
    triageTimestamp: null,
    currentWaitMinutes: 20,
    acuityLevel: 'ESI-3',
    preCheckedIn: false,
    ...overrides,
  };
}

function mockBoard(overrides: Partial<PatientTrackerBoard> = {}): PatientTrackerBoard {
  return {
    arrived: [mockItem()],
    triage: [],
    waitingForPhysician: [],
    inProgress: [
      mockItem({ encounterId: 'e2', patientName: 'Jane Roe', currentStatus: 'IN_PROGRESS' }),
    ],
    awaitingResults: [],
    readyForDischarge: [],
    totalPatients: 2,
    averageWaitMinutes: 15,
    ...overrides,
  };
}

describe('PatientTrackerComponent', () => {
  let component: PatientTrackerComponent;
  let fixture: ComponentFixture<PatientTrackerComponent>;
  let trackerSpy: jasmine.SpyObj<PatientTrackerService>;
  let trackerWsSpy: jasmine.SpyObj<PatientTrackerWsService>;
  let authSpy: jasmine.SpyObj<AuthService>;
  let encounterSpy: jasmine.SpyObj<EncounterService>;

  beforeEach(async () => {
    trackerSpy = jasmine.createSpyObj('PatientTrackerService', ['getTrackerBoard']);
    trackerWsSpy = jasmine.createSpyObj('PatientTrackerWsService', [
      'connect',
      'disconnect',
      'getEvents',
      'getConnectionState',
    ]);
    trackerWsSpy.getEvents.and.returnValue(of());
    trackerWsSpy.getConnectionState.and.returnValue(of(false));
    authSpy = jasmine.createSpyObj('AuthService', ['getHospitalId', 'hasAnyRole', 'getUserId']);
    authSpy.hasAnyRole.and.returnValue(true);
    encounterSpy = jasmine.createSpyObj('EncounterService', [
      'getById',
      'checkOut',
      'completeTriage',
      'completeExamination',
      'markReadyForDischarge',
    ]);

    authSpy.getHospitalId.and.returnValue('h1');
    trackerSpy.getTrackerBoard.and.returnValue(of(mockBoard()));

    await TestBed.configureTestingModule({
      imports: [PatientTrackerComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: PatientTrackerService, useValue: trackerSpy },
        { provide: PatientTrackerWsService, useValue: trackerWsSpy },
        { provide: AuthService, useValue: authSpy },
        { provide: EncounterService, useValue: encounterSpy },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(PatientTrackerComponent);
    component = fixture.componentInstance;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should load board on init', () => {
    fixture.detectChanges();
    expect(trackerSpy.getTrackerBoard).toHaveBeenCalledWith('h1');
    expect(component.board()).toBeTruthy();
    expect(component.totalPatients()).toBe(2);
    expect(component.averageWait()).toBe(15);
  });

  // QA D1085: the midwife was offered Démarrer on every waiting card.
  it('D1085: offers Start per card on the server rule (midwife: only her own visits)', () => {
    const roles = TestBed.inject(RoleContextService);
    roles.setRoles(['ROLE_MIDWIFE']);
    authSpy.getUserId.and.returnValue('u-mw');
    const mine = mockItem({
      encounterId: 'm',
      currentStatus: 'WAITING_FOR_PHYSICIAN',
      assignedProviderUserId: 'u-mw',
    });
    const doctors = mockItem({
      encounterId: 'd',
      currentStatus: 'WAITING_FOR_PHYSICIAN',
      assignedProviderUserId: 'u-dr',
    });
    const unassigned = mockItem({
      encounterId: 'u',
      currentStatus: 'WAITING_FOR_PHYSICIAN',
      assignedProviderUserId: null,
    });
    expect(component.canStartItem(mine)).toBeTrue();
    expect(component.canStartItem(doctors)).toBeFalse();
    expect(component.canStartItem(unassigned)).toBeFalse();
    roles.setRoles(['ROLE_DOCTOR']);
    authSpy.getUserId.and.returnValue('u-dr');
    expect(component.canStartItem(unassigned)).toBeTrue();
    expect(component.canStartItem(doctors)).toBeTrue();
    expect(component.canStartItem(mine)).toBeFalse();
    trackerSpy.getTrackerBoard.and.returnValue(
      of(mockBoard({ waitingForPhysician: [mine, doctors, unassigned] })),
    );
    roles.setRoles(['ROLE_MIDWIFE']);
    authSpy.getUserId.and.returnValue('u-mw');
    fixture.detectChanges();
    const starts = fixture.nativeElement.querySelectorAll('[data-testid="tracker-start"]');
    expect(starts.length).withContext('one Start button, on her own visit').toBe(1);
  });

  it('QA D954: an arrived patient who left unseen is closed from the tracker', () => {
    authSpy.hasAnyRole.and.callFake((roles: string[]) => roles.includes('ROLE_NURSE'));
    fixture.detectChanges();
    const btn = fixture.nativeElement.querySelector(
      '[data-testid="tracker-lwbs"]',
    ) as HTMLButtonElement;
    expect(btn).withContext('LWBS action on the arrived card').toBeTruthy();
    btn.click();
    fixture.detectChanges();
    expect(component.closeUnseenItem()?.encounterId).toBe('e1');
    expect(fixture.nativeElement.querySelector('app-close-unseen-dialog')).toBeTruthy();
    component.onUnseenVisitClosed();
    expect(component.closeUnseenItem()).toBeNull();
  });

  // QA D1099 (round 19): a midwife ended a doctor's examination from his card.
  it('QA D1099: ending the examination and checking out are the examining clinician only', () => {
    const roles = TestBed.inject(RoleContextService);
    const doctorsVisit = mockItem({
      encounterId: 'e2',
      currentStatus: 'IN_PROGRESS',
      assignedProviderUserId: 'u-dr',
    });
    trackerSpy.getTrackerBoard.and.returnValue(of(mockBoard({ inProgress: [doctorsVisit] })));
    roles.setRoles(['ROLE_MIDWIFE']);
    authSpy.getUserId.and.returnValue('u-mw');
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(component.canEndExamItem(doctorsVisit)).toBeFalse();
    expect(el.querySelectorAll('.btn-complete-exam').length).withContext('no end-exam').toBe(0);
    expect(el.querySelectorAll('.btn-checkout').length).withContext('no Sortie').toBe(0);

    roles.setRoles(['ROLE_DOCTOR']);
    authSpy.getUserId.and.returnValue('u-dr');
    fixture.detectChanges();
    expect(component.canEndExamItem(doctorsVisit)).toBeTrue();
    expect(el.querySelectorAll('.btn-complete-exam').length).toBe(1);
    expect(el.querySelectorAll('.btn-checkout').length).toBe(1);

    authSpy.getUserId.and.returnValue('u-other-doctor');
    expect(component.canEndExamItem(doctorsVisit)).withContext('another doctor').toBeFalse();
  });

  it('QA D1099: a refused end of examination shows the server reason', () => {
    const toast = TestBed.inject(ToastService);
    spyOn(toast, 'error');
    encounterSpy.completeExamination.and.returnValue(
      throwError(() => ({
        status: 400,
        error: { message: 'Seul le clinicien qui mène cet examen peut le terminer.' },
      })),
    );
    component.completeExamination(mockItem({ encounterId: 'e2', currentStatus: 'IN_PROGRESS' }));
    expect(toast.error).toHaveBeenCalledWith(
      'Seul le clinicien qui mène cet examen peut le terminer.',
    );
  });

  // QA D1108 (round 19): LWBS was offered only before triage.
  it('QA D1108: LWBS is offered until the patient is seen, after triage too', () => {
    authSpy.hasAnyRole.and.callFake((roles: string[]) => roles.includes('ROLE_NURSE'));
    trackerSpy.getTrackerBoard.and.returnValue(
      of(
        mockBoard({
          arrived: [],
          waitingForPhysician: [
            mockItem({ encounterId: 'w1', currentStatus: 'WAITING_FOR_PHYSICIAN' }),
          ],
        }),
      ),
    );
    fixture.detectChanges();
    const lwbs = fixture.nativeElement.querySelectorAll('[data-testid="tracker-lwbs"]');
    expect(lwbs.length).withContext('the waiting card, not the card in progress').toBe(1);
    (lwbs[0] as HTMLButtonElement).click();
    expect(component.closeUnseenItem()?.encounterId).toBe('w1');
  });

  it('QA D954: a role the endpoint refuses is not offered the LWBS action', () => {
    authSpy.hasAnyRole.and.callFake((roles: string[]) => roles.includes('ROLE_LAB_TECHNICIAN'));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="tracker-lwbs"]')).toBeNull();
  });

  it('should set loading to false after load', () => {
    fixture.detectChanges();
    expect(component.loading()).toBeFalse();
  });

  it('should set lastRefreshed after load', () => {
    fixture.detectChanges();
    expect(component.lastRefreshed()).toBeTruthy();
  });

  it('should handle load error gracefully', () => {
    trackerSpy.getTrackerBoard.and.returnValue(throwError(() => new Error('fail')));
    fixture.detectChanges();
    expect(component.loading()).toBeFalse();
    expect(component.board()).toBeNull();
  });

  it('should not load if no hospital ID', () => {
    authSpy.getHospitalId.and.returnValue(null);
    trackerSpy.getTrackerBoard.calls.reset();
    component.loadBoard();
    expect(trackerSpy.getTrackerBoard).not.toHaveBeenCalled();
  });

  it('should return column items correctly', () => {
    fixture.detectChanges();
    expect(component.getColumnItems('arrived').length).toBe(1);
    expect(component.getColumnItems('inProgress').length).toBe(1);
    expect(component.getColumnItems('triage').length).toBe(0);
  });

  it('should return empty array for unknown column key', () => {
    fixture.detectChanges();
    expect(component.getColumnItems('nonexistent')).toEqual([]);
  });

  it('getWaitClass should return correct class', () => {
    expect(component.getWaitClass(5)).toBe('wait-ok');
    expect(component.getWaitClass(15)).toBe('wait-warning');
    expect(component.getWaitClass(30)).toBe('wait-critical');
    expect(component.getWaitClass(60)).toBe('wait-critical');
  });

  it('getAcuityClass should return correct class', () => {
    expect(component.getAcuityClass('ESI-1')).toBe('acuity-emergent');
    expect(component.getAcuityClass('EMERGENT')).toBe('acuity-emergent');
    expect(component.getAcuityClass('ESI-2')).toBe('acuity-urgent');
    expect(component.getAcuityClass('URGENT')).toBe('acuity-urgent');
    expect(component.getAcuityClass('ESI-3')).toBe('acuity-moderate');
    expect(component.getAcuityClass('ROUTINE')).toBe('acuity-routine');
    expect(component.getAcuityClass('')).toBe('acuity-routine');
  });

  it('should refresh board on refresh()', () => {
    fixture.detectChanges();
    trackerSpy.getTrackerBoard.calls.reset();
    component.refresh();
    expect(trackerSpy.getTrackerBoard).toHaveBeenCalledWith('h1');
  });

  it('should render patient cards in template', () => {
    fixture.detectChanges();
    const cards = (fixture.nativeElement as HTMLElement).querySelectorAll('.patient-card');
    expect(cards.length).toBe(2);
  });

  it('should render column headers', () => {
    fixture.detectChanges();
    const titles = (fixture.nativeElement as HTMLElement).querySelectorAll('.column-title');
    expect(titles.length).toBe(6);
  });

  it('should show loading state', () => {
    fixture.detectChanges(); // trigger ngOnInit (completes synchronously)
    component.loading.set(true);
    component.board.set(null);
    fixture.detectChanges(); // re-render with loading=true, board=null
    const loadingEl = (fixture.nativeElement as HTMLElement).querySelector('.tracker-loading');
    expect(loadingEl).toBeTruthy();
  });

  it('should unsubscribe on destroy', () => {
    fixture.detectChanges();
    expect(() => component.ngOnDestroy()).not.toThrow();
  });

  /* ── Triage form (D408) ──────────────────────────── */

  it('openTriage should fetch the encounter and open the triage form (D408)', () => {
    const mockEnc: Partial<EncounterResponse> = { id: 'e3', status: 'ARRIVED' };
    encounterSpy.getById.and.returnValue(of(mockEnc as EncounterResponse));

    component.openTriage(mockItem({ encounterId: 'e3' }));

    expect(encounterSpy.getById).toHaveBeenCalledWith('e3');
    expect(component.triageEncounter()).toEqual(mockEnc as EncounterResponse);
  });

  it('onTriageCompleted should close the form and refresh the board (D408)', () => {
    fixture.detectChanges();
    trackerSpy.getTrackerBoard.calls.reset();
    component.triageEncounter.set({ id: 'e3' } as EncounterResponse);

    component.onTriageCompleted();

    expect(component.triageEncounter()).toBeNull();
    expect(trackerSpy.getTrackerBoard).toHaveBeenCalled();
  });

  it('arrived cards offer the triage form, not only the complete button (D408)', () => {
    fixture.detectChanges();
    const buttons = Array.from(
      fixture.nativeElement.querySelectorAll('.btn-complete-triage') as NodeListOf<HTMLElement>,
    ).map((b) => b.textContent?.trim());
    expect(buttons).toContain('TRIAGE.TITLE');
  });

  /* ── Check-Out dialog (MVP 6) ────────────────────── */

  it('openCheckout should fetch encounter and show dialog', () => {
    const mockEnc: Partial<EncounterResponse> = {
      id: 'e2',
      patientName: 'Jane Roe',
      status: 'IN_PROGRESS',
    };
    encounterSpy.getById.and.returnValue(of(mockEnc as EncounterResponse));

    const item = mockItem({ encounterId: 'e2' });
    component.openCheckout(item);

    expect(encounterSpy.getById).toHaveBeenCalledWith('e2');
    expect(component.checkoutEncounter()).toEqual(mockEnc as EncounterResponse);
    expect(component.showCheckoutDialog()).toBeTrue();
  });

  it('onCheckoutDismissed should close dialog and clear encounter', () => {
    component.showCheckoutDialog.set(true);
    component.checkoutEncounter.set({ id: 'e1' } as EncounterResponse);

    component.onCheckoutDismissed();

    expect(component.showCheckoutDialog()).toBeFalse();
    expect(component.checkoutEncounter()).toBeNull();
  });

  it('onCheckoutCompleted should close dialog and refresh board', () => {
    fixture.detectChanges();
    trackerSpy.getTrackerBoard.calls.reset();
    component.showCheckoutDialog.set(true);

    const avs: AfterVisitSummary = {
      encounterId: 'e1',
      visitDate: '2026-06-15',
      providerName: 'Dr Smith',
      departmentName: 'ER',
      hospitalName: 'City Hospital',
      dischargeDiagnoses: [],
      encounterStatus: 'COMPLETED',
      checkoutTimestamp: '2026-06-15T11:00:00',
    };

    component.onCheckoutCompleted(avs);

    expect(component.showCheckoutDialog()).toBeFalse();
    expect(component.checkoutEncounter()).toBeNull();
    expect(trackerSpy.getTrackerBoard).toHaveBeenCalledWith('h1');
  });

  it('should render checkout buttons for eligible columns', () => {
    trackerSpy.getTrackerBoard.and.returnValue(
      of(
        mockBoard({
          readyForDischarge: [
            mockItem({ encounterId: 'e3', currentStatus: 'READY_FOR_DISCHARGE' }),
          ],
        }),
      ),
    );
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    const checkoutBtns = el.querySelectorAll('.btn-checkout');
    expect(checkoutBtns.length).toBeGreaterThan(0);
  });

  it('QA D788: an in-progress card offers the check-out to its clinician only, not the desk', () => {
    const board = () =>
      of(
        mockBoard({
          inProgress: [mockItem({ encounterId: 'e2', currentStatus: 'IN_PROGRESS' })],
          awaitingResults: [],
          readyForDischarge: [],
        }),
      );
    trackerSpy.getTrackerBoard.and.callFake(board);

    authSpy.hasAnyRole.and.returnValue(false);
    component.loadBoard();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.btn-checkout').length).toBe(0);

    authSpy.hasAnyRole.and.returnValue(true);
    // QA D1099: the clinician is a doctor acting on an unassigned visit (who may end it).
    TestBed.inject(RoleContextService).setRoles(['ROLE_DOCTOR']);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.btn-checkout').length).toBe(1);
    expect(authSpy.hasAnyRole).toHaveBeenCalledWith(['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE']);
  });

  /* ── Complete Triage ───────────────────────────── */

  it('should call completeTriage and reload board', () => {
    const enc: EncounterResponse = {
      id: 'e1',
      status: 'WAITING_FOR_PHYSICIAN',
    } as EncounterResponse;
    encounterSpy.completeTriage.and.returnValue(of(enc));

    const item = mockItem({ encounterId: 'e1', currentStatus: 'TRIAGE' });
    component.completeTriage(item);

    expect(encounterSpy.completeTriage).toHaveBeenCalledWith('e1');
    expect(component.triageInProgress()).toBeFalse();
    expect(trackerSpy.getTrackerBoard).toHaveBeenCalledWith('h1');
  });

  it('should render complete-triage buttons in triage column', () => {
    trackerSpy.getTrackerBoard.and.returnValue(
      of(
        mockBoard({
          triage: [mockItem({ encounterId: 'e4', currentStatus: 'TRIAGE' })],
        }),
      ),
    );
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    const triageBtns = el.querySelectorAll('.btn-complete-triage');
    expect(triageBtns.length).toBeGreaterThan(0);
  });

  /* ── Complete Examination ──────────────────────── */

  it('should call completeExamination and reload board', () => {
    const enc: EncounterResponse = {
      id: 'e2',
      status: 'READY_FOR_DISCHARGE',
    } as EncounterResponse;
    encounterSpy.completeExamination.and.returnValue(of(enc));

    const item = mockItem({ encounterId: 'e2', currentStatus: 'IN_PROGRESS' });
    component.completeExamination(item);

    expect(encounterSpy.completeExamination).toHaveBeenCalledWith('e2');
    expect(component.examInProgress()).toBeFalse();
    expect(trackerSpy.getTrackerBoard).toHaveBeenCalledWith('h1');
  });

  it('should handle completeExamination error gracefully', () => {
    encounterSpy.completeExamination.and.returnValue(throwError(() => new Error('fail')));

    const item = mockItem({ encounterId: 'e2', currentStatus: 'IN_PROGRESS' });
    component.completeExamination(item);

    expect(component.examInProgress()).toBeFalse();
  });

  it('should render complete-exam button in inProgress column', () => {
    // QA D1099: offered to the examining clinician (here a doctor, the visit unassigned).
    TestBed.inject(RoleContextService).setRoles(['ROLE_DOCTOR']);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    const examBtns = el.querySelectorAll('.btn-complete-exam');
    expect(examBtns.length).toBeGreaterThan(0);
  });

  /* ── Ready for Discharge ───────────────────────── */

  it('should call markReadyForDischarge and reload board', () => {
    const enc: EncounterResponse = {
      id: 'e5',
      status: 'READY_FOR_DISCHARGE',
    } as EncounterResponse;
    encounterSpy.markReadyForDischarge.and.returnValue(of(enc));

    const item = mockItem({ encounterId: 'e5', currentStatus: 'AWAITING_RESULTS' });
    component.markReadyForDischarge(item);

    expect(encounterSpy.markReadyForDischarge).toHaveBeenCalledWith('e5');
    expect(component.readyInProgress()).toBeFalse();
    expect(trackerSpy.getTrackerBoard).toHaveBeenCalledWith('h1');
  });

  it('should handle markReadyForDischarge error gracefully', () => {
    encounterSpy.markReadyForDischarge.and.returnValue(throwError(() => new Error('fail')));

    const item = mockItem({ encounterId: 'e5', currentStatus: 'AWAITING_RESULTS' });
    component.markReadyForDischarge(item);

    expect(component.readyInProgress()).toBeFalse();
  });

  it('should render ready-discharge button in awaitingResults column', () => {
    trackerSpy.getTrackerBoard.and.returnValue(
      of(
        mockBoard({
          awaitingResults: [mockItem({ encounterId: 'e6', currentStatus: 'AWAITING_RESULTS' })],
        }),
      ),
    );
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    const readyBtns = el.querySelectorAll('.btn-ready-discharge');
    expect(readyBtns.length).toBeGreaterThan(0);
  });
});

/** QA D528/D663 replay: HOSPITAL_ADMIN_A opens /patient-tracker in FR, then ES; the cards read ROUTINE / ESI-3. */
describe('PatientTrackerComponent — the acuity badge is labelled (QA D528/D663)', () => {
  function badgesIn(lang: 'fr' | 'es'): string[] {
    const tracker = jasmine.createSpyObj('PatientTrackerService', ['getTrackerBoard']);
    tracker.getTrackerBoard.and.returnValue(
      of(
        mockBoard({
          arrived: [mockItem({ acuityLevel: 'ROUTINE' })],
          inProgress: [mockItem({ encounterId: 'e2', acuityLevel: 'ESI-3' })],
        }),
      ),
    );
    const ws = jasmine.createSpyObj('PatientTrackerWsService', [
      'connect',
      'disconnect',
      'getEvents',
      'getConnectionState',
    ]);
    ws.getEvents.and.returnValue(of());
    ws.getConnectionState.and.returnValue(of(false));
    TestBed.configureTestingModule({
      imports: [PatientTrackerComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: PatientTrackerService, useValue: tracker },
        { provide: PatientTrackerWsService, useValue: ws },
        {
          provide: AuthService,
          // getUserId: the per-card end-exam rule (QA D1099) reads the signed-in user.
          useValue: { getHospitalId: () => 'h1', hasAnyRole: () => true, getUserId: () => null },
        },
        { provide: EncounterService, useValue: {} },
      ],
    });
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', fr);
    translate.setTranslation('es', es);
    translate.use(lang);
    const fixture = TestBed.createComponent(PatientTrackerComponent);
    fixture.detectChanges();
    return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.acuity-badge')).map(
      (b) => b.textContent?.trim() ?? '',
    );
  }

  it('shows French labels, never the wire token', () => {
    const badges = badgesIn('fr');
    expect(badges).toContain('Courant');
    expect(badges).toContain('ESI 3 - Urgent');
    expect(badges).not.toContain('ROUTINE');
  });

  it('shows Spanish labels, never the wire token', () => {
    const badges = badgesIn('es');
    expect(badges).toContain('Rutinario');
    expect(badges).not.toContain('ROUTINE');
  });
});
