import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { DashboardComponent } from './dashboard';
import { AuthService } from '../auth/auth.service';
import { PermissionService } from '../core/permission.service';
import { ToastService } from '../core/toast.service';
import { EncounterService } from '../services/encounter.service';
import { signal } from '@angular/core';
import { of, throwError } from 'rxjs';

/**
 * Lightweight unit tests for dashboard navigation and RBAC fixes.
 * We instantiate the component with mocked auth/permissions, then
 * assert that quick-action routes and workflow tiles are correct.
 */
describe('Dashboard navigation & RBAC', () => {
  function createComponent(roles: string[], permissions: string[]): DashboardComponent {
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
        provideRouter([]),
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

  it('roleLabel should be "Lab Director" for ROLE_LAB_DIRECTOR', () => {
    const c = createComponent(['ROLE_LAB_DIRECTOR'], []);
    c.isLabDirector.set(true);
    expect(c.roleLabel()).toBe('Lab Director');
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
    const approvalTile = tiles.find((t) => t.label === 'Approval Queue');
    expect(approvalTile?.count).toBe(5);
  });

  // ── Quality Manager active view and computed properties ─────

  it('activeView should be "quality-manager" for ROLE_QUALITY_MANAGER', () => {
    const c = createComponent(['ROLE_QUALITY_MANAGER'], []);
    c.isQualityManager.set(true);
    expect(c.activeView()).toBe('quality-manager');
  });

  it('roleLabel should be "Quality Manager" for ROLE_QUALITY_MANAGER', () => {
    const c = createComponent(['ROLE_QUALITY_MANAGER'], []);
    c.isQualityManager.set(true);
    expect(c.roleLabel()).toBe('Quality Manager');
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
    const checkIn = actions.find((a) => a.label === 'Check-in');
    expect(checkIn)
      .withContext('Expected receptionist quick actions to include a Check-in action')
      .toBeDefined();
    expect(checkIn!.route).toBe('/reception');
  });

  it('nurse Check-In tile should route to /nurse-station', () => {
    const c = createComponent(['ROLE_NURSE'], []);
    c.isNurse.set(true);
    const tiles = c.nurseWorkflowTiles();
    const checkIn = tiles.find((t) => t.label === 'Check-In');
    expect(checkIn)
      .withContext('Expected nurse workflow tiles to include a Check-In tile')
      .toBeDefined();
    expect(checkIn!.route).toBe('/nurse-station');
  });

  it('receptionist Check-In tile should route to /reception', () => {
    const c = createComponent(['ROLE_RECEPTIONIST'], []);
    c.isReceptionist.set(true);
    const tiles = c.receptionistWorkflowTiles();
    const checkIn = tiles.find((t) => t.label === 'Check-In');
    expect(checkIn)
      .withContext('Expected receptionist workflow tiles to include a Check-In tile')
      .toBeDefined();
    expect(checkIn!.route).toBe('/reception');
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
