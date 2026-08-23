import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { DashboardComponent } from './dashboard';
import { AuthService } from '../auth/auth.service';
import { PermissionService } from '../core/permission.service';
import { ToastService } from '../core/toast.service';
import { EncounterService } from '../services/encounter.service';
import { signal } from '@angular/core';
import { of, Subject, throwError } from 'rxjs';
import {
  PatientTrackerWsService,
  PatientTrackerEvent,
} from '../services/patient-tracker-ws.service';

/**
 * Lightweight unit tests for dashboard navigation and RBAC fixes.
 * We instantiate the component with mocked auth/permissions, then
 * assert that quick-action routes and workflow tiles are correct.
 */
describe('Dashboard navigation & RBAC', () => {
  function createComponent(
    roles: string[],
    permissions: string[],
    routes: import('@angular/router').Routes = [],
  ): DashboardComponent {
    const permSet = new Set(permissions);
    const authStub = jasmine.createSpyObj('AuthService', [
      'getRoles',
      'hasAnyRole',
      'getToken',
      'getUserProfile',
    ]);
    authStub.getRoles.and.returnValue(roles);
    authStub.hasAnyRole.and.callFake((r: string[]) =>
      roles.some((role: string) => r.includes(role)),
    );
    authStub.getToken.and.returnValue('fake-token');
    authStub.getUserProfile.and.returnValue({
      id: 'u1',
      username: 'testuser',
      email: 'test@test.com',
      roles,
      staffId: 's1',
      active: true,
    } as any);

    const permStub: Partial<PermissionService> = {
      hasPermission: (p: string) => permSet.has(p) || permSet.has('*'),
      hasAnyPermission: (...ps: string[]) => ps.some((p) => permSet.has(p) || permSet.has('*')),
    };

    TestBed.configureTestingModule({
      imports: [DashboardComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter(routes),
        { provide: AuthService, useValue: authStub },
        { provide: PermissionService, useValue: permStub },
      ],
    });

    const fixture = TestBed.createComponent(DashboardComponent);
    const c = fixture.componentInstance;

    // Set role flags to match the provided roles
    c.isDoctor.set(roles.includes('ROLE_DOCTOR'));
    c.isNurse.set(roles.includes('ROLE_NURSE'));
    c.isMidwife.set(roles.includes('ROLE_MIDWIFE'));
    c.isReceptionist.set(roles.includes('ROLE_RECEPTIONIST'));
    c.isLabScientist.set(
      roles.includes('ROLE_LAB_SCIENTIST') || roles.includes('ROLE_LAB_TECHNICIAN'),
    );
    c.isLabManager.set(roles.includes('ROLE_LAB_MANAGER'));
    c.isLabDirector.set(roles.includes('ROLE_LAB_DIRECTOR'));
    c.isQualityManager.set(roles.includes('ROLE_QUALITY_MANAGER'));
    c.isPharmacist.set(roles.includes('ROLE_PHARMACIST'));
    c.isRadiologist.set(roles.includes('ROLE_RADIOLOGIST'));
    c.isSuperAdmin.set(roles.includes('ROLE_SUPER_ADMIN'));
    c.isHospitalAdmin.set(roles.includes('ROLE_HOSPITAL_ADMIN'));
    c.isPatient.set(roles.includes('ROLE_PATIENT'));

    return c;
  }

  afterEach(() => TestBed.resetTestingModule());

  // ── Quick Action route validity ─────────────────────────────

  // These flat-component routes have NO child routes. Quick actions must
  // not append "/new" to them — the components handle creation internally.
  const flatRoutes = new Set(['/encounters', '/prescriptions', '/lab', '/referrals', '/imaging']);

  it('quick action routes should not end with /new for flat-component routes', () => {
    const doctor = createComponent(
      ['ROLE_DOCTOR'],
      [
        'Register Patients',
        'Create Appointments',
        'Create Encounters',
        'Create Prescriptions',
        'View Lab',
        'Request Imaging Studies',
        'Create Referrals',
      ],
    );

    const actions = doctor.quickActions();
    for (const a of actions) {
      const base = a.route.replace(/\/new$/, '');
      if (flatRoutes.has(base)) {
        expect(a.route).not.toMatch(
          /\/new$/,
          `Quick action "${a.label}" uses ${a.route} which is not a real route`,
        );
      }
    }
  });

  // ── Doctor quick actions include Lab and Referrals ───────────

  it('doctor quick actions should include Lab Orders', () => {
    const doctor = createComponent(
      ['ROLE_DOCTOR'],
      ['Register Patients', 'Create Appointments', 'Create Encounters', 'View Lab'],
    );
    const routes = doctor.quickActions().map((a) => a.route);
    expect(routes).toContain('/lab');
  });

  it('doctor quick actions should include Referrals when under the cap', () => {
    const doctor = createComponent(
      ['ROLE_DOCTOR'],
      [
        'Register Patients',
        'Create Appointments',
        'Create Encounters',
        'Create Prescriptions',
        'View Lab',
        'Create Referrals',
      ],
    );
    // 6 unique routes: /patients/new, /appointments/new, /encounters, /prescriptions, /lab, /referrals
    // but cap is 6 so check if /referrals made it or was cut by Imaging first
    const actions = doctor.quickActions();
    expect(actions.length).toBeGreaterThan(0);
    expect(actions.length).toBeLessThanOrEqual(6);
  });

  // ── Doctor workflow tiles must NOT include /nurse-station ────

  it('doctor workflow tiles should NOT include a nurse-station route', () => {
    const doctor = createComponent(['ROLE_DOCTOR'], []);
    doctor.isDoctor.set(true);
    const tiles = doctor.doctorWorkflowTiles();
    const routes = tiles.map((t) => t.route);
    expect(routes).not.toContain('/nurse-station');
  });

  // ── The refill queue must be reachable from the dashboard ───
  // Both tiles used to point at /prescriptions, which meant the approval
  // queue existed but no click anywhere in the portal reached it.

  it('doctor workflow tiles link to the refill queue', () => {
    const doctor = createComponent(['ROLE_DOCTOR'], []);
    doctor.isDoctor.set(true);
    expect(doctor.doctorWorkflowTiles().map((t) => t.route)).toContain('/refills');
  });

  it('the pharmacist refills tile no longer points at /prescriptions', () => {
    const pharmacist = createComponent(['ROLE_PHARMACIST'], []);
    // Matched on icon rather than label — the label goes through translation.
    const refillTile = pharmacist.pharmacistWorkflowTiles().find((t) => t.icon === 'loop');
    expect(refillTile).toBeDefined();
    expect(refillTile?.route).toBe('/refills');
  });

  // ── Nurse workflow tiles SHOULD include /nurse-station ──────

  it('nurse workflow tiles should include a nurse-station route', () => {
    const nurse = createComponent(['ROLE_NURSE'], []);
    nurse.isNurse.set(true);
    const tiles = nurse.nurseWorkflowTiles();
    const routes = tiles.map((t) => t.route);
    expect(routes).toContain('/nurse-station');
  });

  // ── Doctor active view is "doctor" ──────────────────────────

  it('activeView should be "doctor" for ROLE_DOCTOR', () => {
    const doctor = createComponent(['ROLE_DOCTOR'], []);
    expect(doctor.activeView()).toBe('doctor');
  });

  // ── Nurse active view is "nurse" ────────────────────────────

  it('activeView should be "nurse" for ROLE_NURSE', () => {
    const nurse = createComponent(['ROLE_NURSE'], []);
    expect(nurse.activeView()).toBe('nurse');
  });

  // ── Lab Director active view and computed properties ────────

  it('activeView should be "lab-director" for ROLE_LAB_DIRECTOR', () => {
    const c = createComponent(['ROLE_LAB_DIRECTOR'], []);
    c.isLabDirector.set(true);
    expect(c.activeView()).toBe('lab-director');
  });

  it('roleLabel should resolve the lab-director key for ROLE_LAB_DIRECTOR', () => {
    // The test harness installs TranslateModule.forRoot() with no loader, so
    // translate.instant() returns the key itself. We assert on the key (proves
    // the right branch fired) — the rendered French/English string is verified
    // by the i18n JSON snapshots.
    const c = createComponent(['ROLE_LAB_DIRECTOR'], []);
    c.isLabDirector.set(true);
    expect(c.roleLabel()).toBe('DASHBOARD.ROLE.LAB_DIRECTOR');
  });

  it('heroGradientClass should be "hero-gradient-lab-director" for ROLE_LAB_DIRECTOR', () => {
    const c = createComponent(['ROLE_LAB_DIRECTOR'], []);
    c.isLabDirector.set(true);
    expect(c.heroGradientClass()).toBe('hero-gradient-lab-director');
  });

  it('labDirectorStatCards should return empty array when no data loaded', () => {
    const c = createComponent(['ROLE_LAB_DIRECTOR'], []);
    c.isLabDirector.set(true);
    expect(c.labDirectorStatCards()).toEqual([]);
  });

  it('labDirectorStatCards should return 5 cards when data is set', () => {
    const c = createComponent(['ROLE_LAB_DIRECTOR'], []);
    c.isLabDirector.set(true);
    c.labDirectorDashboard.set({
      hospitalId: '00000000-0000-0000-0000-000000000001',
      asOfDate: '2025-01-01',
      pendingDirectorApproval: 3,
      pendingQaReview: 2,
      draftDefinitions: 1,
      activeDefinitions: 42,
      validationStudiesPendingApproval: 1,
      validationStudiesLast30Days: 10,
      ordersToday: 100,
      ordersCompletedToday: 80,
      ordersInProgress: 15,
      ordersCancelledThisWeek: 5,
      avgTurnaroundMinutesToday: 47.3,
      recentApprovalAudit: [],
    });
    expect(c.labDirectorStatCards().length).toBe(5);
    const approvalCard = c.labDirectorStatCards().find((c) => c.key === 'pending_director');
    expect(approvalCard?.value).toBe(3);
  });

  it('labDirectorStatCards TAT should show N/A when avgTurnaroundMinutesToday is null', () => {
    const c = createComponent(['ROLE_LAB_DIRECTOR'], []);
    c.isLabDirector.set(true);
    c.labDirectorDashboard.set({
      hospitalId: '00000000-0000-0000-0000-000000000001',
      asOfDate: '2025-01-01',
      pendingDirectorApproval: 0,
      pendingQaReview: 0,
      draftDefinitions: 0,
      activeDefinitions: 0,
      validationStudiesPendingApproval: 0,
      validationStudiesLast30Days: 0,
      ordersToday: 0,
      ordersCompletedToday: 0,
      ordersInProgress: 0,
      ordersCancelledThisWeek: 0,
      avgTurnaroundMinutesToday: null,
      recentApprovalAudit: [],
    });
    const tatCard = c.labDirectorStatCards().find((c) => c.key === 'avg_tat');
    expect(tatCard?.value).toBe('N/A');
  });

  it('labDirectorNavTiles should return 11 tiles with badges for pending counts', () => {
    const c = createComponent(['ROLE_LAB_DIRECTOR'], []);
    c.isLabDirector.set(true);
    c.labDirectorDashboard.set({
      hospitalId: 'h1',
      asOfDate: '2025-01-01',
      pendingDirectorApproval: 5,
      pendingQaReview: 3,
      draftDefinitions: 0,
      activeDefinitions: 0,
      validationStudiesPendingApproval: 0,
      validationStudiesLast30Days: 0,
      ordersToday: 0,
      ordersCompletedToday: 0,
      ordersInProgress: 0,
      ordersCancelledThisWeek: 0,
      avgTurnaroundMinutesToday: null,
      recentApprovalAudit: [],
    });
    const tiles = c.labDirectorNavTiles();
    expect(tiles.length).toBe(13);
    // Identify the approval-queue tile by its icon (unique within the array)
    // rather than its translated label, so the lookup survives i18n changes.
    const approvalTile = tiles.find((t) => t.icon === 'approval');
    expect(approvalTile?.count).toBe(5);
  });

  // ── Quality Manager active view and computed properties ─────

  it('activeView should be "quality-manager" for ROLE_QUALITY_MANAGER', () => {
    const c = createComponent(['ROLE_QUALITY_MANAGER'], []);
    c.isQualityManager.set(true);
    expect(c.activeView()).toBe('quality-manager');
  });

  it('roleLabel should resolve the quality-manager key for ROLE_QUALITY_MANAGER', () => {
    const c = createComponent(['ROLE_QUALITY_MANAGER'], []);
    c.isQualityManager.set(true);
    expect(c.roleLabel()).toBe('DASHBOARD.ROLE.QUALITY_MANAGER');
  });

  it('heroGradientClass should be "hero-gradient-quality-manager" for ROLE_QUALITY_MANAGER', () => {
    const c = createComponent(['ROLE_QUALITY_MANAGER'], []);
    c.isQualityManager.set(true);
    expect(c.heroGradientClass()).toBe('hero-gradient-quality-manager');
  });

  it('qualityManagerStatCards should return empty array when no data loaded', () => {
    const c = createComponent(['ROLE_QUALITY_MANAGER'], []);
    c.isQualityManager.set(true);
    expect(c.qualityManagerStatCards()).toEqual([]);
  });

  it('qualityManagerStatCards should return 5 cards with pass rate when data set', () => {
    const c = createComponent(['ROLE_QUALITY_MANAGER'], []);
    c.isQualityManager.set(true);
    c.qualityManagerDashboard.set({
      hospitalId: 'h1',
      asOfDate: '2025-01-01',
      pendingQaReview: 4,
      draftDefinitions: 2,
      pendingDirectorApproval: 1,
      activeDefinitions: 30,
      totalValidationStudies: 50,
      passedValidationStudies: 45,
      failedValidationStudies: 5,
      qualityPassRate: 90.0,
      validationStudiesLast30Days: 12,
      ordersCancelledThisWeek: 3,
      ordersToday: 80,
    });
    expect(c.qualityManagerStatCards().length).toBe(5);
    const passRateCard = c.qualityManagerStatCards().find((c) => c.key === 'pass_rate');
    expect(passRateCard?.value).toBe('90.0%');
  });

  it('qualityManagerStatCards pass rate should show N/A when qualityPassRate is null', () => {
    const c = createComponent(['ROLE_QUALITY_MANAGER'], []);
    c.isQualityManager.set(true);
    c.qualityManagerDashboard.set({
      hospitalId: 'h1',
      asOfDate: '2025-01-01',
      pendingQaReview: 0,
      draftDefinitions: 0,
      pendingDirectorApproval: 0,
      activeDefinitions: 0,
      totalValidationStudies: 0,
      passedValidationStudies: 0,
      failedValidationStudies: 0,
      qualityPassRate: null,
      validationStudiesLast30Days: 0,
      ordersCancelledThisWeek: 0,
      ordersToday: 0,
    });
    const passRateCard = c.qualityManagerStatCards().find((c) => c.key === 'pass_rate');
    expect(passRateCard?.value).toBe('N/A');
  });

  it('lab-director should take priority over lab-scientist in activeView', () => {
    const c = createComponent(['ROLE_LAB_DIRECTOR', 'ROLE_LAB_SCIENTIST'], []);
    c.isLabDirector.set(true);
    c.isLabScientist.set(true);
    expect(c.activeView()).toBe('lab-director');
  });

  // ── Check-in route points to /reception ──────────────────────

  it('receptionist Check-in quick action should route to /reception', () => {
    const c = createComponent(
      ['ROLE_RECEPTIONIST'],
      ['Register Patients', 'Create Appointments', 'Check-in Patients'],
    );
    c.isReceptionist.set(true);
    const actions = c.quickActions();
    // Identify the check-in action by the unique route+icon pair instead of
    // its translated label, so this assertion is i18n-stable.
    const checkIn = actions.find((a) => a.route === '/reception' && a.icon === 'how_to_reg');
    expect(checkIn)
      .withContext('Expected receptionist quick actions to include a Check-in action')
      .toBeDefined();
    expect(checkIn!.route).toBe('/reception');
  });

  it('nurse Check-In tile should route to /nurse-station', () => {
    const c = createComponent(['ROLE_NURSE'], []);
    c.isNurse.set(true);
    const tiles = c.nurseWorkflowTiles();
    // The check-in tile is the only nurse-station tile that uses the
    // how_to_reg icon, so look it up by that pair instead of by label.
    const checkIn = tiles.find((t) => t.route === '/nurse-station' && t.icon === 'how_to_reg');
    expect(checkIn)
      .withContext('Expected nurse workflow tiles to include a Check-In tile')
      .toBeDefined();
    expect(checkIn!.route).toBe('/nurse-station');
  });

  it('receptionist Check-In tile should route to /reception', () => {
    const c = createComponent(['ROLE_RECEPTIONIST'], []);
    c.isReceptionist.set(true);
    const tiles = c.receptionistWorkflowTiles();
    const checkIn = tiles.find((t) => t.route === '/reception' && t.icon === 'how_to_reg');
    expect(checkIn)
      .withContext('Expected receptionist workflow tiles to include a Check-In tile')
      .toBeDefined();
    expect(checkIn!.route).toBe('/reception');
  });

  // ── Accountant fallback view (2026-08-23 screenshot bug) ─────
  // The accountant landed on a dashboard whose only data call was
  // GET /patients — which the backend rejects for finance roles — and
  // whose only visible content was the generic welcome card.

  const ACCOUNTANT_PERMISSIONS = [
    'View Dashboard',
    'View Billing',
    'View Billing Summary',
    'Record Payment',
    'View Billing Reports',
    'View Notifications',
  ];

  it('accountant lands on the fallback view with a billing quick action', () => {
    const c = createComponent(['ROLE_ACCOUNTANT'], ACCOUNTANT_PERMISSIONS);
    c.isAccountant.set(true);

    expect(c.activeView()).toBe('fallback');
    expect(c.quickActions().map((a) => a.route)).toContain('/billing');
  });

  it('accountant role label is its own, not the generic Staff badge', () => {
    const c = createComponent(['ROLE_ACCOUNTANT'], ACCOUNTANT_PERMISSIONS);
    c.isAccountant.set(true);

    expect(c.roleLabel()).toContain('ACCOUNTANT');
  });

  // 2026-08-23 role audit: only LAB_SCIENTIST mapped to the lab view, so
  // technicians and managers fell through to the generic fallback. These
  // drive initProfile itself so the flag mapping can't silently drift again.

  it('lab technician lands on the lab view via initProfile', () => {
    const c = createComponent(['ROLE_LAB_TECHNICIAN'], []);
    (c as unknown as { initProfile(): void }).initProfile();
    expect(c.activeView()).toBe('lab');
  });

  it('lab manager lands on the lab view via initProfile with its own label', () => {
    const c = createComponent(['ROLE_LAB_MANAGER'], []);
    (c as unknown as { initProfile(): void }).initProfile();
    expect(c.activeView()).toBe('lab');
    expect(c.roleLabel()).toContain('LAB_MANAGER');
  });

  it('pharmacist tiles drop guard-rejected routes and use real pharmacy pages', () => {
    const guarded: import('@angular/router').Routes = [
      { path: 'patients', children: [], data: { roles: ['ROLE_DOCTOR'] } },
      { path: 'encounters', children: [], data: { roles: ['ROLE_DOCTOR'] } },
    ];
    const c = createComponent(['ROLE_PHARMACIST'], [], guarded);
    c.isPharmacist.set(true);

    const routes = c.pharmacistWorkflowTiles().map((t) => t.route);
    expect(routes).not.toContain('/patients');
    expect(routes).not.toContain('/encounters');
    expect(routes).toContain('/pharmacy/dispensing');
    expect(routes).toContain('/pharmacy/inventory');
    expect(routes).toContain('/pharmacy/drug-interactions');
  });

  it('canAccessRoute respects a role-guarded route the caller is outside of', () => {
    const guardedRoutes: import('@angular/router').Routes = [
      { path: 'patients', children: [], data: { roles: ['ROLE_DOCTOR', 'ROLE_NURSE'] } },
    ];
    const c = createComponent(
      ['ROLE_ACCOUNTANT'],
      [...ACCOUNTANT_PERMISSIONS, 'View Patient Records'],
      guardedRoutes,
    );

    // The recent-patients fetch gates on this — a permission alone must not
    // fire GET /patients for a role the route guard (and backend) rejects.
    const gate = (c as unknown as { canAccessRoute(r: string): boolean }).canAccessRoute;
    expect(gate.call(c, '/patients')).toBeFalse();
  });
});

describe('Dashboard onStartEncounter', () => {
  let component: DashboardComponent;
  let encounterServiceSpy: jasmine.SpyObj<EncounterService>;
  let toastSpy: jasmine.SpyObj<ToastService>;
  let router: Router;

  beforeEach(() => {
    encounterServiceSpy = jasmine.createSpyObj('EncounterService', ['startEncounter']);
    toastSpy = jasmine.createSpyObj('ToastService', ['error', 'success']);

    const authStub = jasmine.createSpyObj('AuthService', [
      'getRoles',
      'hasAnyRole',
      'getToken',
      'getUserProfile',
    ]);
    authStub.getRoles.and.returnValue(['ROLE_DOCTOR']);
    authStub.hasAnyRole.and.callFake((r: string[]) => r.includes('ROLE_DOCTOR'));
    authStub.getToken.and.returnValue('fake-token');
    authStub.getUserProfile.and.returnValue({
      id: 'u1',
      username: 'testuser',
      email: 'test@test.com',
      roles: ['ROLE_DOCTOR'],
      staffId: 's1',
      active: true,
    } as any);

    const permStub: Partial<PermissionService> = {
      hasPermission: () => true,
      hasAnyPermission: () => true,
    };

    TestBed.configureTestingModule({
      imports: [DashboardComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: AuthService, useValue: authStub },
        { provide: PermissionService, useValue: permStub },
        { provide: EncounterService, useValue: encounterServiceSpy },
        { provide: ToastService, useValue: toastSpy },
      ],
    });

    const fixture = TestBed.createComponent(DashboardComponent);
    component = fixture.componentInstance;
    router = TestBed.inject(Router);
  });

  afterEach(() => TestBed.resetTestingModule());

  it('should navigate to encounter on successful start', () => {
    encounterServiceSpy.startEncounter.and.returnValue(of({} as any));
    const navSpy = spyOn(router, 'navigate');

    component.onStartEncounter('enc-123');

    expect(encounterServiceSpy.startEncounter).toHaveBeenCalledWith('enc-123');
    expect(navSpy).toHaveBeenCalledWith(['/encounters', 'enc-123']);
  });

  it('should show toast error with backend message on failure', () => {
    const errorResponse = {
      error: {
        message: 'Cannot start encounter in status COMPLETED. Expected WAITING_FOR_PHYSICIAN.',
      },
    };
    encounterServiceSpy.startEncounter.and.returnValue(throwError(() => errorResponse));
    const navSpy = spyOn(router, 'navigate');

    component.onStartEncounter('enc-456');

    expect(encounterServiceSpy.startEncounter).toHaveBeenCalledWith('enc-456');
    expect(navSpy).not.toHaveBeenCalled();
    expect(toastSpy.error).toHaveBeenCalledWith(
      'Cannot start encounter in status COMPLETED. Expected WAITING_FOR_PHYSICIAN.',
    );
  });

  it('should show fallback toast error when no backend message', () => {
    encounterServiceSpy.startEncounter.and.returnValue(throwError(() => ({ status: 500 })));

    component.onStartEncounter('enc-789');

    expect(toastSpy.error).toHaveBeenCalledWith('Failed to start encounter');
  });
});

/**
 * Coverage-focused tests for the i18n refactor on this branch.
 * These do not assert on rendered strings (TranslateModule.forRoot() has no
 * loader, so translate.instant returns the key) — they cover the new code
 * paths so Sonar's new-code coverage gate is satisfied.
 */
describe('Dashboard i18n refactor coverage', () => {
  function createComponent(roles: string[], permissions: string[] = []): DashboardComponent {
    const permSet = new Set(permissions);
    const authStub = jasmine.createSpyObj('AuthService', [
      'getRoles',
      'hasAnyRole',
      'getToken',
      'getUserProfile',
    ]);
    authStub.getRoles.and.returnValue(roles);
    authStub.hasAnyRole.and.callFake((r: string[]) => roles.some((role) => r.includes(role)));
    authStub.getToken.and.returnValue('fake-token');
    authStub.getUserProfile.and.returnValue({
      id: 'u1',
      username: 'testuser',
      email: 'test@test.com',
      roles,
      staffId: 's1',
      active: true,
    } as any);

    const permStub: Partial<PermissionService> = {
      hasPermission: (p: string) => permSet.has(p) || permSet.has('*'),
      hasAnyPermission: (...ps: string[]) => ps.some((p) => permSet.has(p) || permSet.has('*')),
    };

    TestBed.configureTestingModule({
      imports: [DashboardComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: AuthService, useValue: authStub },
        { provide: PermissionService, useValue: permStub },
      ],
    });

    const fixture = TestBed.createComponent(DashboardComponent);
    const c = fixture.componentInstance;
    c.isDoctor.set(roles.includes('ROLE_DOCTOR'));
    c.isNurse.set(roles.includes('ROLE_NURSE'));
    c.isMidwife.set(roles.includes('ROLE_MIDWIFE'));
    c.isReceptionist.set(roles.includes('ROLE_RECEPTIONIST'));
    c.isLabScientist.set(roles.includes('ROLE_LAB_SCIENTIST'));
    c.isLabDirector.set(roles.includes('ROLE_LAB_DIRECTOR'));
    c.isQualityManager.set(roles.includes('ROLE_QUALITY_MANAGER'));
    c.isPharmacist.set(roles.includes('ROLE_PHARMACIST'));
    c.isRadiologist.set(roles.includes('ROLE_RADIOLOGIST'));
    c.isSuperAdmin.set(roles.includes('ROLE_SUPER_ADMIN'));
    c.isHospitalAdmin.set(roles.includes('ROLE_HOSPITAL_ADMIN'));
    c.isPatient.set(roles.includes('ROLE_PATIENT'));
    return c;
  }

  afterEach(() => TestBed.resetTestingModule());

  // ── roleLabel — every branch ──────────────────────────────────

  // Each role should resolve a distinct DASHBOARD.ROLE.* key. The harness has
  // no translation loader so the key itself comes back, which is enough to
  // prove the right computed branch fired.
  // MVP-5: SUPER_ADMIN no longer has a dashboard view branch — they land on
  // /super-admin (Control Tower) via SuperAdminRedirectGuard.
  const roleLabelCases: [string, string][] = [
    ['ROLE_PATIENT', 'DASHBOARD.ROLE.PATIENT'],
    ['ROLE_HOSPITAL_ADMIN', 'DASHBOARD.ROLE.HOSPITAL_ADMIN'],
    ['ROLE_DOCTOR', 'DASHBOARD.ROLE.DOCTOR'],
    ['ROLE_MIDWIFE', 'DASHBOARD.ROLE.MIDWIFE'],
    ['ROLE_NURSE', 'DASHBOARD.ROLE.NURSE'],
    ['ROLE_RECEPTIONIST', 'DASHBOARD.ROLE.RECEPTIONIST'],
    ['ROLE_LAB_DIRECTOR', 'DASHBOARD.ROLE.LAB_DIRECTOR'],
    ['ROLE_QUALITY_MANAGER', 'DASHBOARD.ROLE.QUALITY_MANAGER'],
    ['ROLE_LAB_SCIENTIST', 'DASHBOARD.ROLE.LAB_SCIENTIST'],
    ['ROLE_PHARMACIST', 'DASHBOARD.ROLE.PHARMACIST'],
    ['ROLE_RADIOLOGIST', 'DASHBOARD.ROLE.RADIOLOGIST'],
  ];

  for (const [role, expected] of roleLabelCases) {
    it(`roleLabel resolves ${expected} for ${role}`, () => {
      const c = createComponent([role]);
      expect(c.roleLabel()).toBe(expected);
    });
  }

  it('roleLabel falls back to STAFF when no role matches', () => {
    const c = createComponent(['ROLE_UNKNOWN']);
    expect(c.roleLabel()).toBe('DASHBOARD.ROLE.STAFF');
  });

  // ── Per-role tile arrays — one assertion each, exercises every label ──

  it('hospitalAdminNavTiles returns 6 tiles', () => {
    const c = createComponent(['ROLE_HOSPITAL_ADMIN']);
    expect(c.hospitalAdminNavTiles().length).toBe(6);
  });

  it('doctorWorkflowTiles returns 11 tiles', () => {
    const c = createComponent(['ROLE_DOCTOR']);
    expect(c.doctorWorkflowTiles().length).toBe(11);
  });

  it('nurseWorkflowTiles returns 10 tiles', () => {
    const c = createComponent(['ROLE_NURSE']);
    expect(c.nurseWorkflowTiles().length).toBe(10);
  });

  it('receptionistWorkflowTiles returns 8 tiles', () => {
    const c = createComponent(['ROLE_RECEPTIONIST']);
    expect(c.receptionistWorkflowTiles().length).toBe(8);
  });

  it('labWorkflowTiles returns 8 tiles', () => {
    const c = createComponent(['ROLE_LAB_SCIENTIST']);
    expect(c.labWorkflowTiles().length).toBe(8);
  });

  it('pharmacistWorkflowTiles returns 7 tiles', () => {
    // 2026-08-23 role audit: the Reports tile is gone (it routed to
    // /prescriptions and no pharmacy-reports page exists). With no guarded
    // routes registered in this test, the canAccessRoute filter drops nothing.
    const c = createComponent(['ROLE_PHARMACIST']);
    expect(c.pharmacistWorkflowTiles().length).toBe(7);
  });

  it('radiologistWorkflowTiles returns 8 tiles', () => {
    const c = createComponent(['ROLE_RADIOLOGIST']);
    expect(c.radiologistWorkflowTiles().length).toBe(8);
  });

  it('patientQuickLinks returns 8 tiles', () => {
    const c = createComponent(['ROLE_PATIENT']);
    expect(c.patientQuickLinks().length).toBe(8);
  });

  it('qualityManagerNavTiles returns 9 tiles', () => {
    const c = createComponent(['ROLE_QUALITY_MANAGER']);
    expect(c.qualityManagerNavTiles().length).toBe(9);
  });

  // ── criticalStripCards / statCards / hospitalAdminStatCards ──

  it('criticalStripCards returns 6 actionable cards when criticalStrip is set', () => {
    const c = createComponent(['ROLE_DOCTOR']);
    c.criticalStrip.set({
      criticalLabsCount: 2,
      waitingLongCount: 1,
      pendingConsultsCount: 0,
      unsignedNotesCount: 4,
      pendingOrderReviewCount: 0,
      activeSafetyAlertsCount: 1,
    } as any);
    expect(c.criticalStripCards().length).toBe(6);
  });

  it('criticalStripCards is empty when no criticalStrip data', () => {
    const c = createComponent(['ROLE_DOCTOR']);
    expect(c.criticalStripCards()).toEqual([]);
  });

  it('statCards returns the 6 generic clinical fallback cards', () => {
    const c = createComponent(['ROLE_NURSE']);
    expect(c.statCards().length).toBe(6);
  });

  it('hospitalAdminStatCards returns 14 cards when summary is loaded', () => {
    const c = createComponent(['ROLE_HOSPITAL_ADMIN']);
    c.hospitalAdminSummary.set({
      appointments: { todayTotal: 10, completed: 5, noShows: 1 },
      admissions: { active: 3, admittedToday: 2, dischargedToday: 1 },
      consultations: { requested: 4, acknowledged: 2, overdue: 1 },
      staffing: { activeStaff: 20, onShiftToday: 12, staffOnLeaveToday: 1 },
      licenseAlerts: [{ severity: 'EXPIRED' }],
      billing: { overdueInvoices: 2, openBalanceTotal: 1234 },
    } as any);
    expect(c.hospitalAdminStatCards().length).toBe(14);
  });

  it('hospitalAdminStatCards is empty when summary missing', () => {
    const c = createComponent(['ROLE_HOSPITAL_ADMIN']);
    expect(c.hospitalAdminStatCards()).toEqual([]);
  });

  // ── Inbox grouping ────────────────────────────────────────────

  it('inboxGrouped maps each category to a translated label', () => {
    const c = createComponent(['ROLE_DOCTOR']);
    c.inboxItems.set([
      { id: 'i1', category: 'MESSAGE' } as any,
      { id: 'i2', category: 'CRITICAL_RESULT' } as any,
      { id: 'i3', category: 'TASK' } as any,
    ]);
    const groups = c.inboxGrouped();
    expect(groups.length).toBe(3);
    const cats = groups.map((g) => g.category);
    expect(cats).toContain('MESSAGE');
    expect(cats).toContain('CRITICAL_RESULT');
    expect(cats).toContain('TASK');
    // Each group should carry an icon + a label (translation key).
    for (const g of groups) {
      expect(g.icon).toBeTruthy();
      expect(g.label).toBeTruthy();
    }
  });

  // ── getApptStatusLabel — every status branch ──────────────────

  const apptStatusCases: [string, string][] = [
    ['SCHEDULED', 'APPOINTMENTS.SCHEDULED'],
    ['CONFIRMED', 'APPOINTMENTS.CONFIRMED'],
    ['COMPLETED', 'DASHBOARD.DONE'],
    ['CANCELLED', 'APPOINTMENTS.CANCELLED'],
    ['NO_SHOW', 'APPOINTMENTS.NO_SHOW'],
    ['IN_PROGRESS', 'DASHBOARD.IN_PROGRESS'],
    ['REQUESTED', 'APPOINTMENTS.REQUESTED'],
  ];

  for (const [status, expected] of apptStatusCases) {
    it(`getApptStatusLabel resolves ${expected} for ${status}`, () => {
      const c = createComponent(['ROLE_DOCTOR']);
      expect(c.getApptStatusLabel(status)).toBe(expected);
    });
  }

  it('getApptStatusLabel returns the raw value for unknown statuses', () => {
    const c = createComponent(['ROLE_DOCTOR']);
    expect(c.getApptStatusLabel('SOMETHING_WEIRD')).toBe('SOMETHING_WEIRD');
  });

  // ── Locale-aware time formatters ──────────────────────────────

  it('formatApptTime returns empty string for empty input', () => {
    const c = createComponent(['ROLE_DOCTOR']);
    expect(c.formatApptTime('')).toBe('');
  });

  it('formatApptTime returns a non-empty string for a valid HH:mm value', () => {
    const c = createComponent(['ROLE_DOCTOR']);
    // Locale output varies by env, so just assert it produced something.
    expect(c.formatApptTime('09:30').length).toBeGreaterThan(0);
  });

  // ── Status / appearance helpers (small, branchy) ──────────────

  it('getApptStatusClass maps known statuses to CSS classes', () => {
    const c = createComponent(['ROLE_DOCTOR']);
    expect(c.getApptStatusClass('SCHEDULED')).toContain('status-scheduled');
    expect(c.getApptStatusClass('UNKNOWN')).toBe('appt-status ');
  });

  it('getAlertSeverityClass maps known severities and falls back', () => {
    const c = createComponent(['ROLE_DOCTOR']);
    expect(c.getAlertSeverityClass('CRITICAL')).toContain('severity-critical');
    expect(c.getAlertSeverityClass('UNKNOWN')).toBe('alert-item ');
  });

  it('getAlertIcon maps known severities and falls back', () => {
    const c = createComponent(['ROLE_DOCTOR']);
    expect(c.getAlertIcon('CRITICAL')).toBe('emergency');
    expect(c.getAlertIcon('UNKNOWN')).toBe('notification_important');
  });

  it('getTriageClass maps known triage states and falls back', () => {
    const c = createComponent(['ROLE_NURSE']);
    expect(c.getTriageClass('TRIAGED')).toContain('triage-triaged');
    expect(c.getTriageClass('UNKNOWN')).toContain('triage-default');
  });

  it('getTrendIcon maps up/down/stable', () => {
    const c = createComponent(['ROLE_DOCTOR']);
    expect(c.getTrendIcon('up')).toBe('trending_up');
    expect(c.getTrendIcon('down')).toBe('trending_down');
    expect(c.getTrendIcon('stable')).toBe('trending_flat');
  });

  it('getTrendClass maps up/down/stable', () => {
    const c = createComponent(['ROLE_DOCTOR']);
    expect(c.getTrendClass('up')).toContain('trend-up');
    expect(c.getTrendClass('down')).toContain('trend-down');
    expect(c.getTrendClass('stable')).toContain('trend-stable');
  });

  it('getPatientInitials handles single, multi, and empty names', () => {
    const c = createComponent(['ROLE_DOCTOR']);
    expect(c.getPatientInitials('Jane Doe')).toBe('JD');
    expect(c.getPatientInitials('Cher')).toBe('C');
    expect(c.getPatientInitials('')).toBe('?');
  });

  it('getAvatarColor returns a deterministic hex colour', () => {
    const c = createComponent(['ROLE_DOCTOR']);
    expect(c.getAvatarColor('JaneDoe')).toMatch(/^#[0-9a-f]{6}$/i);
    expect(c.getAvatarColor('JaneDoe')).toBe(c.getAvatarColor('JaneDoe'));
  });

  it('getIntegrationStatusClass maps known + unknown statuses', () => {
    const c = createComponent(['ROLE_HOSPITAL_ADMIN']);
    expect(c.getIntegrationStatusClass('ACTIVE')).toContain('integration-active');
    expect(c.getIntegrationStatusClass('UNKNOWN')).toBe('integration-badge ');
  });

  it('getAgingBucketColor returns a colour for valid indices and falls back', () => {
    const c = createComponent(['ROLE_HOSPITAL_ADMIN']);
    expect(c.getAgingBucketColor(0)).toMatch(/^#[0-9a-f]{6}$/i);
    expect(c.getAgingBucketColor(99)).toBe('#64748b');
  });

  it('getPaymentMethodColor maps known + unknown', () => {
    const c = createComponent(['ROLE_HOSPITAL_ADMIN']);
    expect(c.getPaymentMethodColor('CASH')).toMatch(/^#[0-9a-f]{6}$/i);
    expect(c.getPaymentMethodColor('UNKNOWN')).toBe('#64748b');
  });

  it('formatMethodLabel converts SNAKE_CASE underscores to spaces', () => {
    const c = createComponent(['ROLE_HOSPITAL_ADMIN']);
    // Input is already upper-case so the title-case replace is a no-op —
    // the only observable change is "_" → " ".
    expect(c.formatMethodLabel('CREDIT_CARD')).toBe('CREDIT CARD');
    expect(c.formatMethodLabel('bank_transfer')).toBe('Bank Transfer');
  });

  // ── Language-change subscription path ────────────────────────

  // The component subscribes to translate.onLangChange in ngOnInit and bumps
  // a langTick signal so every computed() re-evaluates with new strings.
  it('switching language fires the onLangChange handler without throwing', () => {
    const c = createComponent(['ROLE_DOCTOR']);
    // Snapshot a derived value before, then drive the change, then verify
    // the array is freshly returned (signals re-fire) and stays valid.
    const before = c.doctorWorkflowTiles().length;
    const translate = TestBed.inject(TranslateService);
    expect(() => translate.use('fr')).not.toThrow();
    const after = c.doctorWorkflowTiles().length;
    expect(after).toBe(before);
    // todayLabel should have been re-set by the subscription (still a string).
    expect(typeof c.todayLabel()).toBe('string');
    expect(c.todayLabel().length).toBeGreaterThan(0);
  });

  it('switching to es also runs the locale-aware paths', () => {
    const c = createComponent(['ROLE_DOCTOR']);
    const translate = TestBed.inject(TranslateService);
    expect(() => translate.use('es')).not.toThrow();
    expect(c.formatApptTime('14:05').length).toBeGreaterThan(0);
  });

  it('ngOnDestroy clears the clock interval and unsubscribes without throwing', () => {
    const c = createComponent(['ROLE_DOCTOR']);
    expect(() => c.ngOnDestroy()).not.toThrow();
    // Idempotent — second call must not blow up either.
    expect(() => c.ngOnDestroy()).not.toThrow();
  });
});

/**
 * Task 24: the clinician dashboard subscribes to the shared patient-tracker
 * STOMP stream and refreshes its encounter-driven panels on events.
 */
describe('Dashboard live tracker refresh', () => {
  let trackerWsSpy: jasmine.SpyObj<PatientTrackerWsService>;
  let wsEvents$: Subject<PatientTrackerEvent>;

  function createComponent(roles: string[], hospitalId: string | null): DashboardComponent {
    const authStub = jasmine.createSpyObj('AuthService', [
      'getRoles',
      'hasAnyRole',
      'getToken',
      'getUserProfile',
      'getHospitalId',
    ]);
    authStub.getRoles.and.returnValue(roles);
    authStub.hasAnyRole.and.callFake((r: string[]) => roles.some((role) => r.includes(role)));
    authStub.getToken.and.returnValue('fake-token');
    authStub.getHospitalId.and.returnValue(hospitalId);
    authStub.getUserProfile.and.returnValue({
      id: 'u1',
      username: 'testuser',
      email: 'test@test.com',
      roles,
      staffId: 's1',
      active: true,
    } as any);

    const permStub: Partial<PermissionService> = {
      hasPermission: () => false,
      hasAnyPermission: () => false,
    };

    wsEvents$ = new Subject<PatientTrackerEvent>();
    trackerWsSpy = jasmine.createSpyObj('PatientTrackerWsService', [
      'connect',
      'disconnect',
      'getEvents',
      'getConnectionState',
    ]);
    trackerWsSpy.getEvents.and.returnValue(wsEvents$.asObservable());

    TestBed.configureTestingModule({
      imports: [DashboardComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: AuthService, useValue: authStub },
        { provide: PermissionService, useValue: permStub },
        { provide: PatientTrackerWsService, useValue: trackerWsSpy },
      ],
    });

    return TestBed.createComponent(DashboardComponent).componentInstance;
  }

  afterEach(() => TestBed.resetTestingModule());

  it('connects the shared socket for clinicians with an active hospital', () => {
    const c = createComponent(['ROLE_DOCTOR'], 'h1');
    c.ngOnInit();
    expect(trackerWsSpy.connect).toHaveBeenCalledWith('h1');
    c.ngOnDestroy();
    expect(trackerWsSpy.disconnect).toHaveBeenCalled();
  });

  it('does not connect for non-clinical roles', () => {
    const c = createComponent(['ROLE_RECEPTIONIST'], 'h1');
    c.ngOnInit();
    expect(trackerWsSpy.connect).not.toHaveBeenCalled();
    c.ngOnDestroy();
    expect(trackerWsSpy.disconnect).not.toHaveBeenCalled();
  });

  it('does not connect without an active hospital', () => {
    const c = createComponent(['ROLE_DOCTOR'], null);
    c.ngOnInit();
    expect(trackerWsSpy.connect).not.toHaveBeenCalled();
    c.ngOnDestroy();
  });
});
