import { EmpiStatusService } from '../reception/empi-status.service';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Route, Router } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { MOBILE_SIDEBAR_QUERY, ShellComponent } from './shell';
import { AuthService } from '../auth/auth.service';
import { PermissionService } from '../core/permission.service';
import { RoleContextService } from '../core/role-context.service';
import { NotificationService } from '../services/notification.service';
import { expectKeyboardModal } from '../testing/modal-dialog-keyboard';
import { ImpersonationService } from '../services/impersonation.service';
import { IdleService } from '../core/idle.service';
import { EmergencyBroadcastService } from '../services/emergency-broadcast.service';
import { DowntimeService } from '../services/downtime.service';
import { NavOrderService } from './nav-order.service';
import { navGroupForRoute, providerNavEntries } from './nav-groups';
import { ProviderContextService } from '../core/provider-context.service';
import { FacilityTypeGuard } from '../auth/facility-type.guard';
import { settingsFixture } from '../testing/provider-fixtures';
import { ProviderSettings } from '../services/provider.model';
import { WritableSignal } from '@angular/core';
import { routes } from '../app.routes';
import { roleSatisfies } from '../core/role-equivalence';
import en from '../../assets/i18n/en.json';
import fr from '../../assets/i18n/fr.json';
import es from '../../assets/i18n/es.json';

interface NavItem {
  route: string;
  label: string;
  translationKey?: string;
}

/**
 * MVP-5: super-admin surface consolidation. The shell side-nav must drop
 * the Dashboard and Administration entries when the active role is
 * ROLE_SUPER_ADMIN — the Control Tower at /super-admin is the landing
 * page. Other roles still see Dashboard, and ADMIN / HOSPITAL_ADMIN
 * still see Administration.
 */
describe('ShellComponent — MVP-5 nav role filter', () => {
  function createComponent(opts: {
    activeRole: string | null;
    roles: string[];
    wildcardPermission: boolean;
    /** When set, hasPermission answers from this list instead of the wildcard. */
    permissions?: string[];
    /** QA D987: whether the EMPI matcher is on (default on). */
    empiEnabled?: boolean | null;
    /** Review round 16: the status read failed. */
    empiFailed?: boolean;
  }): { items: NavItem[]; component: ShellComponent } {
    const authStub = jasmine.createSpyObj<AuthService>('AuthService', [
      'getUserProfile',
      'hasAnyRole',
      'getSubject',
      'logout',
    ]);
    authStub.getUserProfile.and.returnValue(null);
    authStub.hasAnyRole.and.callFake((rs: string[]) => rs.some((r) => opts.roles.includes(r)));
    authStub.getSubject.and.returnValue(null);
    Object.defineProperty(authStub, 'currentProfile', { value: () => null });

    // Wildcard owners (super admin / admin) get every permission; other
    // roles get none — keeps the test focused on the nav role-filter logic.
    // Tests that need a realistic per-role set pass `permissions` instead.
    const permStub: Partial<PermissionService> = {
      hasPermission: (p: string) =>
        opts.permissions ? opts.permissions.includes(p) : opts.wildcardPermission,
      hasAnyPermission: (...ps: string[]) =>
        opts.permissions ? ps.some((p) => opts.permissions!.includes(p)) : opts.wildcardPermission,
    };

    const notifStub = jasmine.createSpyObj<NotificationService>('NotificationService', [
      'connectWebSocket',
      'disconnectWebSocket',
      'getNotifications',
      'getNotificationStream',
      'getReadStream',
      'getAllReadStream',
      'markAsReadAndNotify',
      'markAllReadAndNotify',
    ]);
    notifStub.getNotifications.and.returnValue(
      of({ content: [], totalElements: 0, totalPages: 0, size: 10, number: 0 }),
    );
    notifStub.getNotificationStream.and.returnValue(of() as never);
    notifStub.getReadStream.and.returnValue(of() as never);
    notifStub.getAllReadStream.and.returnValue(of() as never);

    const impersonationStub = jasmine.createSpyObj<ImpersonationService>('ImpersonationService', [
      'refreshActive',
    ]);
    impersonationStub.refreshActive.and.returnValue(of(null) as never);

    const idleStub = jasmine.createSpyObj<IdleService>('IdleService', ['start', 'stop']);

    TestBed.configureTestingModule({
      imports: [ShellComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: AuthService, useValue: authStub },
        { provide: PermissionService, useValue: permStub },
        { provide: NotificationService, useValue: notifStub },
        { provide: ImpersonationService, useValue: impersonationStub },
        { provide: IdleService, useValue: idleStub },
        {
          provide: EmpiStatusService,
          useValue: {
            enabled: signal<boolean | null>(
              opts.empiEnabled === undefined ? true : opts.empiEnabled,
            ),
            failed: signal(opts.empiFailed ?? false),
            load: () => of(true),
          },
        },
      ],
    });

    const roleContext = TestBed.inject(RoleContextService);
    roleContext.setRoles(opts.roles);
    roleContext.activeRole = opts.activeRole;

    const fixture = TestBed.createComponent(ShellComponent);
    // Read the private baseNavItems computed without running ngOnInit (which
    // pulls in notification websockets and idle timers).
    const items = (
      fixture.componentInstance as unknown as { baseNavItems: () => NavItem[] }
    ).baseNavItems();
    // The component comes back too so the grouping and search tests below can
    // drive the real computeds without rendering the whole shell.
    return { items, component: fixture.componentInstance };
  }

  afterEach(() => TestBed.resetTestingModule());

  it('starts with the drawer closed on a phone, so it does not cover the topbar (D116)', () => {
    spyOn(globalThis, 'matchMedia').and.callFake(
      (q: string) => ({ matches: q === '(max-width: 768px)' }) as MediaQueryList,
    );
    const { component } = createComponent({
      activeRole: 'ROLE_PATIENT',
      roles: ['ROLE_PATIENT'],
      wildcardPermission: false,
    });
    expect(component.sidebarCollapsed()).toBeTrue();
    component.toggleSidebar();
    expect(component.sidebarCollapsed()).toBeFalse();
    component.closeSidebar();
    expect(component.sidebarCollapsed()).toBeTrue();
  });

  it('keeps the sidebar open on a desktop', () => {
    spyOn(globalThis, 'matchMedia').and.callFake(() => ({ matches: false }) as MediaQueryList);
    const { component } = createComponent({
      activeRole: 'ROLE_PATIENT',
      roles: ['ROLE_PATIENT'],
      wildcardPermission: false,
    });
    expect(component.sidebarCollapsed()).toBeFalse();
  });

  it('gives every patient nav entry a distinct label in EN, FR and ES (D257)', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_PATIENT',
      roles: ['ROLE_PATIENT'],
      wildcardPermission: false,
    });
    const locales: Record<string, { NAV: Record<string, unknown> }> = { en, fr, es };
    for (const [lang, bundle] of Object.entries(locales)) {
      const labels = items.map((i) => {
        const key = (i.translationKey ?? '').replace(/^NAV\./, '');
        const raw = bundle.NAV[key];
        const label = typeof raw === 'string' ? raw : undefined;
        expect(label).withContext(`${lang} ${i.translationKey}`).toBeTruthy();
        return (label ?? '').trim().toLocaleLowerCase(lang);
      });
      const dupes = labels.filter((l, idx) => labels.indexOf(l) !== idx);
      expect(dupes).withContext(lang).toEqual([]);
    }
  });

  it('omits Dashboard and Administration when activeRole is ROLE_SUPER_ADMIN', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_SUPER_ADMIN',
      roles: ['ROLE_SUPER_ADMIN'],
      wildcardPermission: true,
    });

    const routes = items.map((i) => i.route);
    expect(routes).not.toContain('/dashboard');
    expect(routes).not.toContain('/admin');
    // Super-admin Control Tower should still be in the nav.
    expect(routes).toContain('/super-admin');
  });

  it('keeps Administration for ROLE_ADMIN active role (non-super)', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_ADMIN',
      roles: ['ROLE_ADMIN'],
      wildcardPermission: true,
    });

    const routes = items.map((i) => i.route);
    expect(routes).toContain('/dashboard');
    expect(routes).toContain('/admin');
    expect(routes).not.toContain('/super-admin');
  });

  it('keeps Dashboard for non-wildcard roles (e.g. ROLE_DOCTOR)', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_DOCTOR',
      roles: ['ROLE_DOCTOR'],
      wildcardPermission: false,
    });

    const routes = items.map((i) => i.route);
    expect(routes).toContain('/dashboard');
    expect(routes).not.toContain('/admin');
    expect(routes).not.toContain('/super-admin');
  });

  // PR #225 review (Copilot): a multi-role super admin who picked a non-super
  // active role at login must NOT see admin-tier sidebar entries — clicking
  // them would trigger a 403 from RoleGuard's active-role check. Before the
  // fix, the gate used `permissions.hasPermission('*')` which reads JWT roles
  // (always true while ROLE_SUPER_ADMIN is in the token) and bypassed the
  // active-role choice.
  it('hides admin-tier entries for multi-role SUPER_ADMIN+DOCTOR who picked DOCTOR active', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_DOCTOR',
      roles: ['ROLE_SUPER_ADMIN', 'ROLE_DOCTOR'],
      wildcardPermission: true,
    });

    const routes = items.map((i) => i.route);
    expect(routes).not.toContain('/admin');
    expect(routes).not.toContain('/organizations');
    expect(routes).not.toContain('/users');
    expect(routes).not.toContain('/roles');
    expect(routes).not.toContain('/platform');
    expect(routes).not.toContain('/super-admin');
    expect(routes).toContain('/dashboard');
  });

  // The /refills queue and its backend were complete, but nothing in the
  // portal linked to them — a prescriber notified about a refill had no way
  // to reach the approval screen. These pin the sidebar entry in place.

  it('shows the refill queue to a prescriber', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_DOCTOR',
      roles: ['ROLE_DOCTOR'],
      wildcardPermission: false,
    });

    expect(items.map((i) => i.route)).toContain('/refills');
  });

  it('shows the refill queue to a pharmacist', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_PHARMACIST',
      roles: ['ROLE_PHARMACIST'],
      wildcardPermission: false,
    });

    expect(items.map((i) => i.route)).toContain('/refills');
  });

  it('reaches the duplicate-patient panel from the sidebar', () => {
    // The EMPI panel and its whole backend shipped with no click anywhere
    // reaching them — an admin had to hand-type the URL. Same defect class as
    // the refill queue, which is why both are pinned here.
    const { items } = createComponent({
      activeRole: 'ROLE_RECEPTIONIST',
      roles: ['ROLE_RECEPTIONIST'],
      wildcardPermission: false,
    });

    expect(items.map((i) => i.route)).toContain('/reception/empi-candidates');
  });

  it('a hospital admin reaches Users to onboard staff, exactly once (D117)', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_HOSPITAL_ADMIN',
      roles: ['ROLE_HOSPITAL_ADMIN'],
      wildcardPermission: false,
    });

    expect(items.filter((i) => i.route === '/users').length).toBe(1);
  });

  it('QA D876: DHIS2 export and ADT intake are reachable from the menu, by their guards', () => {
    const superAdmin = createComponent({
      activeRole: 'ROLE_SUPER_ADMIN',
      roles: ['ROLE_SUPER_ADMIN'],
      wildcardPermission: true,
    }).items.map((i) => i.route);
    expect(superAdmin).toContain('/admin/integrations/dhis2');
    expect(superAdmin).toContain('/admin/adt-intake-configs');
    TestBed.resetTestingModule();

    const hospitalAdmin = createComponent({
      activeRole: 'ROLE_HOSPITAL_ADMIN',
      roles: ['ROLE_HOSPITAL_ADMIN'],
      wildcardPermission: false,
    }).items.map((i) => i.route);
    expect(hospitalAdmin).toContain('/admin/integrations/dhis2');
    // ADT intake is SUPER_ADMIN only, on the route and on the controller.
    expect(hospitalAdmin).not.toContain('/admin/adt-intake-configs');
  });

  it('QA D987: hides the duplicate-patient panel while the matcher is switched off', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_RECEPTIONIST',
      roles: ['ROLE_RECEPTIONIST'],
      wildcardPermission: false,
      empiEnabled: false,
    });

    expect(items.map((i) => i.route)).not.toContain('/reception/empi-candidates');
  });

  it('review round 16: a failed status read still offers the panel (the guard decides)', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_RECEPTIONIST',
      roles: ['ROLE_RECEPTIONIST'],
      wildcardPermission: false,
      empiEnabled: null,
      empiFailed: true,
    });

    expect(items.map((i) => i.route)).toContain('/reception/empi-candidates');
  });

  it('hides the duplicate-patient panel from roles outside its route guard', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_PHARMACIST',
      roles: ['ROLE_PHARMACIST'],
      wildcardPermission: false,
    });

    expect(items.map((i) => i.route)).not.toContain('/reception/empi-candidates');
  });

  // The accountant screenshot bug (2026-08-23): the sidebar offered Patients
  // and the click landed on the 403 page — the nav item was permission-gated
  // while the route guard is role-gated, and the two vocabularies disagreed
  // for ten roles. The item now carries the guard's role list too.
  it('accountant sees Billing but not Patients — nav mirrors the route guard', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_ACCOUNTANT',
      roles: ['ROLE_ACCOUNTANT'],
      wildcardPermission: false,
      permissions: [
        'View Dashboard',
        'View Billing',
        'View Billing Summary',
        'Record Payment',
        'View Billing Reports',
        'View Notifications',
      ],
    });

    const routes = items.map((i) => i.route);
    expect(routes).toContain('/billing');
    expect(routes).not.toContain('/patients');
    // Chat and Announcements are still reachable for this role — they moved
    // to the topbar, so they are no longer nav rows. Covered below.
    expect(routes).not.toContain('/chat');
    expect(routes).not.toContain('/announcements');
  });

  it('keeps Patients for a role inside the /patients route guard', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_NURSE',
      roles: ['ROLE_NURSE'],
      wildcardPermission: false,
      permissions: ['View Patient Records'],
    });

    expect(items.map((i) => i.route)).toContain('/patients');
  });

  // 2026-08-23 role audit: the same dead-nav defect class existed for ten
  // more roles. These pin the guard-mirroring role lists added in response.

  it('midwife sees the clinical entries whose guards now admit her', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_MIDWIFE',
      roles: ['ROLE_MIDWIFE'],
      wildcardPermission: false,
      permissions: [
        'View Prescriptions',
        'View Imaging Studies',
        'Create Treatment Plans',
        'Create Referrals',
      ],
    });

    const routes = items.map((i) => i.route);
    expect(routes).toContain('/prescriptions');
    expect(routes).toContain('/imaging');
    expect(routes).toContain('/treatment-plans');
    expect(routes).toContain('/referrals');
  });

  it('radiologist no longer sees dead Appointments/Lab entries', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_RADIOLOGIST',
      roles: ['ROLE_RADIOLOGIST'],
      wildcardPermission: false,
      permissions: ['View Appointments', 'View Lab', 'View Imaging Studies', 'View Notifications'],
    });

    const routes = items.map((i) => i.route);
    expect(routes).not.toContain('/appointments');
    expect(routes).not.toContain('/lab');
    expect(routes).not.toContain('/lab-results');
    expect(routes).toContain('/imaging');
    // Chat remains open to every authenticated user (decision C3); it is
    // reached from the topbar now rather than the side-nav.
    expect(routes).not.toContain('/chat');
  });

  it('lab technician reaches the release worklist (B14)', () => {
    // The queue exists for the laboratory bench: a technician reads it (and
    // releases nothing — that gate is in the component, not the nav).
    const { items } = createComponent({
      activeRole: 'ROLE_LAB_TECHNICIAN',
      roles: ['ROLE_LAB_TECHNICIAN'],
      wildcardPermission: false,
      permissions: ['View Lab'],
    });

    expect(items.map((i) => i.route)).toContain('/lab-release-worklist');
  });

  it('super-admin reaches the release worklist, being expanded into the laboratory', () => {
    // RoleExpansion.SUPER_ADMIN_INHERITS grants ROLE_LAB_SCIENTIST, so the
    // worklist endpoint serves a super-admin although its @PreAuthorize does
    // not name the role — and SUPER_ADMIN is the one role that may always
    // release. A nav that hid the queue hid it from them alone.
    const { items } = createComponent({
      activeRole: 'ROLE_SUPER_ADMIN',
      roles: ['ROLE_SUPER_ADMIN'],
      wildcardPermission: true,
    });

    expect(items.map((i) => i.route)).toContain('/lab-release-worklist');
  });

  it('admin sees neither Lab Results nor the release worklist (B16)', () => {
    // ROLE_ADMIN is on no lab-result gate the backend runs — not the
    // SecurityConfig matcher, not the @PreAuthorize. The entry was a link to
    // a 403 page, and the route guard admitted it just as wrongly.
    const { items } = createComponent({
      activeRole: 'ROLE_ADMIN',
      roles: ['ROLE_ADMIN'],
      wildcardPermission: true,
    });

    const routes = items.map((i) => i.route);
    expect(routes).not.toContain('/lab-results');
    expect(routes).not.toContain('/lab-release-worklist');
  });

  it('admin does not see Prescriptions: no backend read admits ROLE_ADMIN', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_ADMIN',
      roles: ['ROLE_ADMIN'],
      wildcardPermission: true,
    });

    expect(items.map((i) => i.route)).not.toContain('/prescriptions');
  });

  it('pharmacy verifier reaches Dispensing, Stock Routing and Prescriptions', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_PHARMACY_VERIFIER',
      roles: ['ROLE_PHARMACY_VERIFIER'],
      wildcardPermission: false,
      permissions: ['View Prescriptions'],
    });

    const routes = items.map((i) => i.route);
    expect(routes).toContain('/pharmacy/dispensing');
    expect(routes).toContain('/pharmacy/stock-routing');
    // Gap G9, resolved: /prescriptions used to be withheld because GET
    // /prescriptions and GET /prescriptions/{id} did not admit the role, so
    // the entry opened on a "failed to load" toast. Both reads admit
    // ROLE_PHARMACY_VERIFIER now (PrescriptionControllerTest pins them) and
    // the route guard always did, so the page the pharmacist-verify and
    // request-clarification ceremonies live on is finally reachable from the
    // navigation. What this expectation now guards is the pairing: if the
    // backend ever drops the role from those reads, this must come out with it.
    expect(routes).toContain('/prescriptions');
  });

  it('pharmacy verifier gets exactly one Prescriptions entry when also a pharmacist', () => {
    // The verifier's own nav block pushes Dispensing and Stock Routing. Its
    // Prescriptions entry is deliberately NOT pushed there but added to the
    // clinical item's roles list, so holding both roles cannot produce two.
    const { items } = createComponent({
      activeRole: null,
      roles: ['ROLE_PHARMACIST', 'ROLE_PHARMACY_VERIFIER'],
      wildcardPermission: false,
      permissions: ['View Prescriptions'],
    });

    expect(items.filter((i) => i.route === '/prescriptions').length).toBe(1);
  });

  it('claims reviewer reaches Pharmacy Claims — its entire purpose', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_CLAIMS_REVIEWER',
      roles: ['ROLE_CLAIMS_REVIEWER'],
      wildcardPermission: false,
      permissions: ['View Billing'],
    });

    const routes = items.map((i) => i.route);
    expect(routes).toContain('/pharmacy/claims');
    expect(routes).not.toContain('/billing');
  });

  // Role audit decision C2: physicians/surgeons are doctor-equivalent —
  // every nav gate naming ROLE_DOCTOR admits them.
  it('physician sees the doctor nav entries via role equivalence', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_PHYSICIAN',
      roles: ['ROLE_PHYSICIAN'],
      wildcardPermission: false,
      permissions: ['View Appointments', 'Create Encounters', 'View Prescriptions'],
    });

    const routes = items.map((i) => i.route);
    expect(routes).toContain('/appointments');
    expect(routes).toContain('/encounters');
    expect(routes).toContain('/prescriptions');
  });

  // Role audit decision C1: ADMIN is back-office operations, not platform IT.
  it('admin sees Users/Administration/Patients/Audit Logs but no platform-IT entries', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_ADMIN',
      roles: ['ROLE_ADMIN'],
      wildcardPermission: false,
      permissions: [
        'View Dashboard',
        'View Patient Records',
        'View Audit Logs',
        'View Notifications',
      ],
    });

    const routes = items.map((i) => i.route);
    expect(routes).toContain('/users');
    expect(routes).toContain('/admin');
    expect(routes).toContain('/patients');
    expect(routes).toContain('/audit-logs');
    expect(routes).not.toContain('/organizations');
    expect(routes).not.toContain('/roles');
    expect(routes).not.toContain('/platform');
    expect(routes).not.toContain('/analytics');
    expect(routes).not.toContain('/feature-flags');
    expect(routes).not.toContain('/bed-management');
  });

  // Role audit decision C6: lab leadership reads the audit trail.
  it('quality manager reaches Audit Logs', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_QUALITY_MANAGER',
      roles: ['ROLE_QUALITY_MANAGER'],
      wildcardPermission: false,
      permissions: ['View Audit Logs'],
    });

    expect(items.map((i) => i.route)).toContain('/audit-logs');
  });

  it('nurse reaches the eMAR from the sidebar', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_NURSE',
      roles: ['ROLE_NURSE'],
      wildcardPermission: false,
      permissions: [],
    });

    expect(items.map((i) => i.route)).toContain('/emar');
  });

  it('reaches the duplicate-patient panel as a NURSE with no reception role', () => {
    // The regression the receptionist test above could never catch: the entry
    // used to be pushed inside a receptionist/admin-only block, so its own
    // roles list was never consulted for a nurse or doctor — they were in the
    // route guard and locked out of the sidebar (found 2026-08-21).
    const { items } = createComponent({
      activeRole: 'ROLE_NURSE',
      roles: ['ROLE_NURSE'],
      wildcardPermission: false,
    });

    expect(items.map((i) => i.route)).toContain('/reception/empi-candidates');
  });

  it('reaches the duplicate-patient panel as a DOCTOR with no reception role', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_DOCTOR',
      roles: ['ROLE_DOCTOR'],
      wildcardPermission: false,
    });

    expect(items.map((i) => i.route)).toContain('/reception/empi-candidates');
  });

  it('hides the refill queue from roles that cannot decide a refill', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_RECEPTIONIST',
      roles: ['ROLE_RECEPTIONIST'],
      wildcardPermission: false,
    });

    expect(items.map((i) => i.route)).not.toContain('/refills');
  });

  it('shows patient parity nav entries on patient active role', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_PATIENT',
      roles: ['ROLE_PATIENT'],
      wildcardPermission: false,
    });

    const routes = items.map((i) => i.route);
    expect(routes).toContain('/my-pharmacy-invoices');
    expect(routes).toContain('/my-care-team');
    expect(routes).toContain('/my-family-access');
    expect(routes).toContain('/my-documents');
    // Notifications is no longer a patient nav row — it moved to the topbar
    // bell, which serves patients the same rows /my-notifications did (both
    // endpoints call notificationService.getNotificationsForUser). Neither
    // spelling should appear in the side-nav.
    expect(routes).not.toContain('/my-notifications');
    expect(routes).not.toContain('/notifications');
  });

  // ── Grouping and module search ───────────────────────────────────────

  it('files every nav entry a role can reach under a real group', () => {
    // The enforcement. A route missing from NAV_GROUP_BY_ROUTE still renders
    // — under "More" — rather than vanishing, because a nav entry that
    // disappears for want of one line in a lookup table is the
    // built-but-unreachable failure this codebase keeps producing. That
    // fallback is a safety net, not a place for things to live, so anything
    // landing in it fails here.
    //
    // Super-admin with wildcard permissions plus the patient nav together
    // reach every entry the shell can build.
    // TestBed allows one configuration per instantiation, so each pass resets
    // before the next builds its own component.
    const staff = createComponent({
      activeRole: 'ROLE_SUPER_ADMIN',
      roles: ['ROLE_SUPER_ADMIN'],
      wildcardPermission: true,
    }).items;
    TestBed.resetTestingModule();
    const admin = createComponent({
      activeRole: 'ROLE_ADMIN',
      roles: ['ROLE_ADMIN'],
      wildcardPermission: true,
    }).items;
    TestBed.resetTestingModule();
    const patient = createComponent({
      activeRole: 'ROLE_PATIENT',
      roles: ['ROLE_PATIENT'],
      wildcardPermission: false,
    }).items;

    const ungrouped = [...staff, ...admin, ...patient]
      .map((i) => i.route)
      .filter((route) => navGroupForRoute(route) === 'OTHER');

    expect(ungrouped)
      .withContext(
        `these routes have no entry in NAV_GROUP_BY_ROUTE and fell back to "More": ${[
          ...new Set(ungrouped),
        ].join(', ')}`,
      )
      .toEqual([]);
  });

  it('switches to the provider shell when the server says the session acts at a provider facility', () => {
    // A pharmacy's PROVIDER_ADMIN: the hospital pages would all answer 404.
    const { component } = createComponent({
      activeRole: 'ROLE_PROVIDER_ADMIN',
      roles: ['ROLE_PROVIDER_ADMIN'],
      wildcardPermission: false,
    });
    // The context's state is read-only to consumers; the test drives it as the server would.
    const context = TestBed.inject(ProviderContextService) as unknown as {
      settingsSignal: WritableSignal<ProviderSettings | null>;
    };
    const set = (settings: ProviderSettings | null) => context.settingsSignal.set(settings);
    const read = () =>
      (component as unknown as { baseNavItems: () => NavItem[] })
        .baseNavItems()
        .map((i) => i.route);

    set(settingsFixture());
    expect(read()).toEqual([
      '/provider',
      '/provider/profile',
      '/provider/staff',
      '/provider/audit',
    ]);
    for (const route of read()) {
      expect(navGroupForRoute(route)).withContext(route).toBe('FACILITY');
    }

    // A pharmacist there: no admin pages.
    set(settingsFixture({ providerAdmin: false }));
    expect(read()).toEqual(['/provider', '/provider/profile']);

    // Every provider nav entry is a real route, guarded by the facility guard.
    const children = routes.find((r) => r.children)?.children ?? [];
    for (const route of read()) {
      const match = children.find((r) => '/' + r.path === route);
      expect(match).withContext(route).toBeDefined();
      expect(match?.canActivate).withContext(route).toContain(FacilityTypeGuard);
    }

    // No provider settings: the hospital nav as before.
    set(null);
    expect(read()).toContain('/dashboard');
  });

  it('providerNavEntries filters by facility type and admin seat', () => {
    expect(providerNavEntries('HOSPITAL', true)).toEqual([]);
    expect(providerNavEntries(null, true)).toEqual([]);
    expect(providerNavEntries('LABORATORY', true).map((e) => e.route)).toContain('/provider/audit');
    expect(providerNavEntries('LABORATORY', false).map((e) => e.route)).toEqual([
      '/provider',
      '/provider/profile',
    ]);
  });

  it('groups the nav and drops groups the role holds nothing in', () => {
    const { items, component } = createComponent({
      activeRole: 'ROLE_PATIENT',
      roles: ['ROLE_PATIENT'],
      wildcardPermission: false,
    });
    // navItems is filled by a constructor effect, and these tests skip
    // detectChanges on purpose (ngOnInit opens websockets and idle timers).
    // Seeding it drives the grouping computed directly.
    component.navItems.set(items as never);

    const ids = component.navGroups().map((g) => g.id);
    expect(ids).toContain('MY_HEALTH');
    // A patient holds no staff modules, so those headings must not render as
    // titles over empty space.
    expect(ids).not.toContain('PHARMACY');
    expect(ids).not.toContain('PLATFORM');
    expect(component.navGroups().every((g) => g.entries.length > 0)).toBeTrue();
  });

  it('keeps flatIndex pointing at the real position in navItems', () => {
    // Reorder persists a flat route order, so the handlers must be given the
    // flat index. Handing them the template's group-relative $index would
    // scramble the saved order the first time anyone dragged anything —
    // silently, and only for users who had reordered.
    const { items, component } = createComponent({
      activeRole: 'ROLE_ADMIN',
      roles: ['ROLE_ADMIN'],
      wildcardPermission: true,
    });
    component.navItems.set(items as never);

    const flat = component.navItems();
    for (const group of component.navGroups()) {
      for (const entry of group.entries) {
        expect(flat[entry.flatIndex]?.route).toBe(entry.item.route);
      }
    }
  });

  it('narrows the nav to the modules that match', () => {
    const { items, component } = createComponent({
      activeRole: 'ROLE_ADMIN',
      roles: ['ROLE_ADMIN'],
      wildcardPermission: true,
    });
    component.navItems.set(items as never);

    // "admin" rather than a clinical term: this role's nav is the one the
    // harness can build with wildcard permissions, and a search that matched
    // nothing would pass a weaker assertion by accident.
    component.navSearch.set('admin');
    const routes = component.navGroups().flatMap((g) => g.entries.map((e) => e.item.route));

    expect(routes.length).toBeGreaterThan(0);
    expect(routes).toContain('/admin');
    // Narrowing, not merely reordering — the point of the box. Both of these
    // are in this role's nav and neither matches, so their absence is the
    // filter working rather than a permission hiding them.
    expect(routes).not.toContain('/dashboard');
    expect(routes).not.toContain('/patients');
  });

  it('reports no match rather than rendering an empty sidebar', () => {
    // An empty nav with no explanation reads as a broken app, and the state is
    // easy to reach by typo.
    const { items, component } = createComponent({
      activeRole: 'ROLE_ADMIN',
      roles: ['ROLE_ADMIN'],
      wildcardPermission: true,
    });
    component.navItems.set(items as never);

    component.navSearch.set('zzzzz-no-such-module');
    expect(component.navGroups()).toEqual([]);
    expect(component.navSearchEmpty()).toBeTrue();

    component.clearNavSearch();
    expect(component.navSearchEmpty()).toBeFalse();
    expect(component.navGroups().length).toBeGreaterThan(0);
  });
  // ---------------------------------------------------------------------------
  // E9 #68: sidebar visibility is decided on the same role lists as the route
  // guards. For every role the routes name, render the sidebar with every
  // permission granted (the widest the static map can ever give) and assert
  // that no visible entry leads to a route whose RoleGuard would send that
  // role to /error/403. The check is behavioural — it runs the real
  // baseNavItems, so the conditional `if (this.hasAnyRole(...))` gates are
  // covered as well as the `roles:` mirrors.
  // ---------------------------------------------------------------------------
  describe('nav/route role parity (E9 #68)', () => {
    /**
     * Full route path → the roles the nearest RoleGuard (own or ancestor)
     * requires, or null when nothing on the path names a role list. First
     * definition wins, as in the router.
     */
    function guardedRoutes(): Map<string, string[] | null> {
      const map = new Map<string, string[] | null>();
      const walk = (nodes: Route[], prefix: string, inherited: string[] | null): void => {
        for (const node of nodes) {
          const path = [prefix, node.path ?? ''].filter((p) => p !== '').join('/');
          const own = (node.data?.['roles'] as string[] | undefined) ?? null;
          const effective = own ?? inherited;
          if (!map.has(path)) map.set(path, effective);
          if (node.children) walk(node.children, path, effective);
        }
      };
      walk(routes, '', null);
      return map;
    }

    const guards = guardedRoutes();
    const portalRoles = [...new Set([...guards.values()].flatMap((r) => r ?? []))].sort();

    it('a hospital admin can reach the pharmacy registry it administers (QA D39)', () => {
      expect(guards.get('pharmacy-registry')).toContain('ROLE_HOSPITAL_ADMIN');
      const { items } = createComponent({
        activeRole: 'ROLE_HOSPITAL_ADMIN',
        roles: ['ROLE_HOSPITAL_ADMIN'],
        wildcardPermission: true,
      });
      expect(items.map((i) => i.route)).toContain('/pharmacy-registry');
    });

    it('derives the role set from the route guards', () => {
      expect(portalRoles).toContain('ROLE_DOCTOR');
      expect(portalRoles).toContain('ROLE_PATIENT');
      expect(portalRoles).toContain('ROLE_SUPER_ADMIN');
    });

    for (const role of portalRoles) {
      it(`${role}: no sidebar entry leads to a route whose guard refuses it`, () => {
        const { items } = createComponent({
          activeRole: role,
          roles: [role],
          wildcardPermission: true,
        });
        const unknown: string[] = [];
        const refused: string[] = [];
        for (const route of items.map((i) => i.route)) {
          const key = route.replace(/^\//, '').split('?')[0];
          if (!guards.has(key)) {
            unknown.push(route);
            continue;
          }
          const required = guards.get(key);
          if (required && !roleSatisfies(required, role)) refused.push(route);
        }
        // Joined so a failure names the routes, not just a length.
        expect(unknown.join(' '))
          .withContext(`${role}: sidebar routes that are not routes`)
          .toBe('');
        expect(refused.join(' ')).withContext(`${role}: sidebar routes the guard 403s`).toBe('');
      });
    }
  });
});

/**
 * v1.0 row 11 finish — sidebar Alt+ArrowUp/Down keyboard reorder.
 *
 * Tests target the pure-data path on `onNavKeydown`: pressing the
 * shortcut while focused on a nav item swaps that item with its
 * neighbor and persists the new order. The DOM focus restoration
 * (queueMicrotask + ViewChildren refocus) is covered end-to-end by
 * e2e/keyboard-nav.spec.ts; covering it here would require booting
 * the full ngOnInit (websockets, idle timers) which the existing
 * baseNavItems tests intentionally avoid.
 */
describe('ShellComponent — onNavKeydown (row 11 keyboard reorder)', () => {
  interface ShellHandle {
    onNavKeydown: (event: KeyboardEvent, index: number) => void;
    navItems: { (): { route: string }[]; set: (v: { route: string }[]) => void };
  }

  function createShell(): { shell: ShellHandle; persistedOrders: string[][] } {
    const persistedOrders: string[][] = [];
    const navOrderStub = {
      load: () => null,
      save: (routes: string[]) => persistedOrders.push([...routes]),
      applyOrder: <T>(items: T[], _saved: string[]) => items,
    };

    // Minimal wiring — only what the ShellComponent constructor pulls in
    // before we drive the public method directly. A focused test would
    // ideally not stand up the full component, but ShellComponent injects
    // through inject() in field initializers so we need TestBed.
    const authStub = jasmine.createSpyObj<AuthService>('AuthService', [
      'getUserProfile',
      'hasAnyRole',
      'getSubject',
      'logout',
    ]);
    authStub.getUserProfile.and.returnValue(null);
    authStub.hasAnyRole.and.returnValue(false);
    authStub.getSubject.and.returnValue(null);
    Object.defineProperty(authStub, 'currentProfile', { value: () => null });

    const permStub: Partial<PermissionService> = {
      hasPermission: () => false,
      hasAnyPermission: () => false,
    };
    const notifStub = jasmine.createSpyObj<NotificationService>('NotificationService', [
      'connectWebSocket',
      'disconnectWebSocket',
      'getNotifications',
      'getNotificationStream',
      'getReadStream',
      'getAllReadStream',
      'markAsReadAndNotify',
      'markAllReadAndNotify',
    ]);
    notifStub.getNotifications.and.returnValue(
      of({ content: [], totalElements: 0, totalPages: 0, size: 10, number: 0 }),
    );
    notifStub.getNotificationStream.and.returnValue(of() as never);
    notifStub.getReadStream.and.returnValue(of() as never);
    notifStub.getAllReadStream.and.returnValue(of() as never);
    const impersonationStub = jasmine.createSpyObj<ImpersonationService>('ImpersonationService', [
      'refreshActive',
    ]);
    impersonationStub.refreshActive.and.returnValue(of(null) as never);
    const idleStub = jasmine.createSpyObj<IdleService>('IdleService', ['start', 'stop']);

    TestBed.configureTestingModule({
      imports: [ShellComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: AuthService, useValue: authStub },
        { provide: PermissionService, useValue: permStub },
        { provide: NotificationService, useValue: notifStub },
        { provide: ImpersonationService, useValue: impersonationStub },
        { provide: IdleService, useValue: idleStub },
        // Replace NavOrderService with the in-memory stub so we can
        // assert on what the shell tries to persist.
        { provide: NavOrderService, useValue: navOrderStub },
      ],
    });

    const fixture = TestBed.createComponent(ShellComponent);
    const shell = fixture.componentInstance as unknown as ShellHandle;
    // Seed three nav items directly — bypasses the permission filtering
    // we don't care about here.
    shell.navItems.set([
      { route: '/a' } as never,
      { route: '/b' } as never,
      { route: '/c' } as never,
    ]);

    return { shell, persistedOrders };
  }

  afterEach(() => TestBed.resetTestingModule());

  it('Alt+ArrowDown swaps the focused item with the one below and persists the new order', () => {
    const { shell, persistedOrders } = createShell();
    const event = new KeyboardEvent('keydown', { key: 'ArrowDown', altKey: true });

    shell.onNavKeydown(event, 0);

    expect(shell.navItems().map((i) => i.route)).toEqual(['/b', '/a', '/c']);
    expect(persistedOrders.length).toBe(1);
    expect(persistedOrders[0]).toEqual(['/b', '/a', '/c']);
  });

  it('Alt+ArrowUp at index 0 is a clamped no-op (no swap, no persistence)', () => {
    const { shell, persistedOrders } = createShell();
    const event = new KeyboardEvent('keydown', { key: 'ArrowUp', altKey: true });

    shell.onNavKeydown(event, 0);

    expect(shell.navItems().map((i) => i.route)).toEqual(['/a', '/b', '/c']);
    expect(persistedOrders).toEqual([]);
  });

  it('plain ArrowDown without Alt is ignored (no swap)', () => {
    const { shell, persistedOrders } = createShell();
    const event = new KeyboardEvent('keydown', { key: 'ArrowDown' });

    shell.onNavKeydown(event, 0);

    expect(shell.navItems().map((i) => i.route)).toEqual(['/a', '/b', '/c']);
    expect(persistedOrders).toEqual([]);
  });

  it('Alt+ArrowDown at the last index is a clamped no-op', () => {
    const { shell, persistedOrders } = createShell();
    const event = new KeyboardEvent('keydown', { key: 'ArrowDown', altKey: true });

    shell.onNavKeydown(event, 2);

    expect(shell.navItems().map((i) => i.route)).toEqual(['/a', '/b', '/c']);
    expect(persistedOrders).toEqual([]);
  });

  // ── Now that the nav renders in groups ───────────────────────────────
  //
  // The tests above use /a /b /c, which are in no group and therefore share
  // the fallback one — so they never exercise a boundary. These use real
  // routes from different groups.

  it('stops at the group boundary instead of moving an item nothing can see move', () => {
    // A group is decided by the route, not by position, so crossing the
    // boundary in the flat array would reorder the data and change nothing on
    // screen. The keystroke would read as broken.
    const { shell, persistedOrders } = createShell();
    shell.navItems.set([
      { route: '/dashboard' } as never, // MY_DAY
      { route: '/appointments' } as never, // MY_DAY
      { route: '/patients' } as never, // PATIENTS_FLOW
    ]);

    // Last item of MY_DAY, pressed downward.
    shell.onNavKeydown(new KeyboardEvent('keydown', { key: 'ArrowDown', altKey: true }), 1);

    expect(shell.navItems().map((i) => i.route)).toEqual([
      '/dashboard',
      '/appointments',
      '/patients',
    ]);
    expect(persistedOrders).toEqual([]);
  });

  it('moves to the next item of the same group, stepping over another group', () => {
    const { shell, persistedOrders } = createShell();
    shell.navItems.set([
      { route: '/dashboard' } as never, // MY_DAY
      { route: '/patients' } as never, // PATIENTS_FLOW
      { route: '/appointments' } as never, // MY_DAY
    ]);

    shell.onNavKeydown(new KeyboardEvent('keydown', { key: 'ArrowDown', altKey: true }), 0);

    expect(shell.navItems().map((i) => i.route)).toEqual([
      '/patients',
      '/appointments',
      '/dashboard',
    ]);
    expect(persistedOrders.length).toBe(1);
  });

  it('still persists a flat route order, which is what NavOrderService replays', () => {
    const { shell, persistedOrders } = createShell();
    shell.navItems.set([
      { route: '/dashboard' } as never,
      { route: '/appointments' } as never,
      { route: '/patients' } as never,
    ]);

    shell.onNavKeydown(new KeyboardEvent('keydown', { key: 'ArrowDown', altKey: true }), 0);

    // Grouping is applied at render time, so persistence stays a flat list —
    // no migration of anything already in localStorage.
    expect(persistedOrders[0]).toEqual(['/appointments', '/dashboard', '/patients']);
  });
});

/**
 * Inbox surfaces moved out of the side-nav (Notifications, Messages,
 * Announcements). The side-nav lists places you GO; these three are
 * things that ARRIVE, and Notifications was duplicated — a topbar bell
 * with a live badge AND a redundant nav row that carried no count.
 */
describe('ShellComponent — inbox surfaces live in the topbar', () => {
  function build(
    roles: string[],
    activeRole: string,
    providerUser = false,
    profile: { firstName: string; lastName: string; roles: string[] } | null = null,
  ) {
    // Same shape as the harness above: ngOnInit calls getUserProfile, so a
    // partial stub blows up on the first detectChanges.
    const authStub = jasmine.createSpyObj<AuthService>('AuthService', [
      'getUserProfile',
      'hasAnyRole',
      'getSubject',
      'logout',
    ]);
    authStub.getUserProfile.and.returnValue(null);
    authStub.hasAnyRole.and.callFake((rs: string[]) => rs.some((r) => roles.includes(r)));
    // null subject skips the websocket branch in ngOnInit.
    authStub.getSubject.and.returnValue(null);
    Object.defineProperty(authStub, 'currentProfile', { value: () => profile });

    // Unlike the harness above, these tests DO run ngOnInit (they assert on
    // rendered DOM), so the stub also needs what ngOnInit calls.
    const permStub: Partial<PermissionService> = {
      hasPermission: () => true,
      hasAnyPermission: () => true,
      loadFromBackend: () => undefined,
      clear: () => undefined,
    };
    const notifStub = jasmine.createSpyObj<NotificationService>('NotificationService', [
      'connectWebSocket',
      'disconnectWebSocket',
      'getNotifications',
      'getNotificationStream',
      'getReadStream',
      'getAllReadStream',
      'markAsReadAndNotify',
      'markAllReadAndNotify',
    ]);
    notifStub.getNotifications.and.returnValue(
      of({ content: [], totalElements: 0, totalPages: 0, size: 10, number: 0 }),
    );
    notifStub.getNotificationStream.and.returnValue(of() as never);
    notifStub.getReadStream.and.returnValue(of() as never);
    notifStub.getAllReadStream.and.returnValue(of() as never);

    // These tests render the WHOLE shell, so the three banner children mount
    // too and read signals off their own services. Each stub therefore needs
    // its signals callable, not just the methods ngOnInit calls.
    const impersonationStub = jasmine.createSpyObj<ImpersonationService>('ImpersonationService', [
      'refreshActive',
      'stop',
      'forceStop',
    ]);
    impersonationStub.refreshActive.and.returnValue(of(null) as never);
    Object.defineProperty(impersonationStub, 'active', { value: () => null });
    Object.defineProperty(impersonationStub, 'nearingExpiry', { value: () => false });
    Object.defineProperty(impersonationStub, 'remainingMs', { value: () => 0 });

    const idleStub = jasmine.createSpyObj<IdleService>('IdleService', ['start', 'stop']);
    // idle.locked() decides whether the lock screen paints over the shell.
    Object.defineProperty(idleStub, 'locked', { value: () => false });

    const broadcastStub = jasmine.createSpyObj<EmergencyBroadcastService>(
      'EmergencyBroadcastService',
      ['connect', 'disconnect', 'dismiss'],
    );
    Object.defineProperty(broadcastStub, 'latest', { value: () => null });

    const downtimeStub = jasmine.createSpyObj<DowntimeService>('DowntimeService', [
      'startPolling',
      'stopPolling',
    ]);
    Object.defineProperty(downtimeStub, 'status', { value: () => null });

    TestBed.configureTestingModule({
      imports: [ShellComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: AuthService, useValue: authStub },
        { provide: PermissionService, useValue: permStub },
        { provide: NotificationService, useValue: notifStub },
        { provide: ImpersonationService, useValue: impersonationStub },
        { provide: IdleService, useValue: idleStub },
        { provide: EmergencyBroadcastService, useValue: broadcastStub },
        { provide: DowntimeService, useValue: downtimeStub },
      ],
    });

    const roleContext = TestBed.inject(RoleContextService);
    roleContext.setRoles(roles);
    roleContext.activeRole = activeRole;
    roleContext.setProviderUser(providerUser);
    return TestBed.createComponent(ShellComponent);
  }

  function broadcastSpy(): jasmine.SpyObj<EmergencyBroadcastService> {
    return TestBed.inject(EmergencyBroadcastService) as jasmine.SpyObj<EmergencyBroadcastService>;
  }

  it('opens the emergency-broadcast socket for staff once the session is resolved', () => {
    const fixture = build(['ROLE_DOCTOR'], 'ROLE_DOCTOR');
    TestBed.inject(RoleContextService).markSessionResolved();
    fixture.detectChanges();
    expect(broadcastSpy().connect).toHaveBeenCalledTimes(1);
  });

  // QA D1125 (D1004 class, round 20): the bell panel and the profile menu
  // moved no focus, and their Escape was bound on an aria-hidden backdrop that
  // never receives a key, so Escape left them open.
  for (const [what, testid] of [
    ['notification panel', 'shell-bell'],
    ['profile menu', 'shell-profile-btn'],
  ]) {
    it(`the ${what} is a keyboard modal: focus in, Escape closes, focus back to its button`, () => {
      const fixture = build(['ROLE_DOCTOR'], 'ROLE_DOCTOR');
      document.body.appendChild(fixture.nativeElement);
      fixture.detectChanges();
      const opener = (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>(
        `[data-testid="${testid}"]`,
      )!;
      expectKeyboardModal(fixture, opener);
      fixture.destroy();
    });
  }

  // QA D1153 / D1125 / D1004 (round 21): at 375 px the drawer never took
  // focus, Escape from the Menu button did nothing and Tab walked to the
  // language select behind the backdrop.
  describe('the phone drawer is a keyboard modal', () => {
    function phoneShell(): { fixture: ComponentFixture<ShellComponent>; menu: HTMLElement } {
      spyOn(globalThis, 'matchMedia').and.callFake(
        (q: string) => ({ matches: q === MOBILE_SIDEBAR_QUERY }) as MediaQueryList,
      );
      const fixture = build(['ROLE_PATIENT'], 'ROLE_PATIENT');
      document.body.appendChild(fixture.nativeElement);
      attached.push(fixture.nativeElement);
      fixture.detectChanges();
      const host = fixture.nativeElement as HTMLElement;
      // The test browser is wider than a phone, so the CSS hides the Menu button.
      const menu = host.querySelector<HTMLElement>('.mobile-toggle')!;
      menu.style.display = 'inline-flex';
      return { fixture, menu };
    }

    function openFromMenu(
      fixture: ComponentFixture<ShellComponent>,
      menu: HTMLElement,
    ): HTMLElement {
      menu.focus();
      menu.click();
      fixture.detectChanges();
      TestBed.tick();
      const aside = (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>(
        'aside.sidebar',
      )!;
      expect(aside.getAttribute('role')).toBe('dialog');
      expect(aside.getAttribute('aria-modal')).toBe('true');
      expect((aside.getAttribute('aria-label') ?? '').trim()).not.toBe('');
      expect(aside.contains(document.activeElement))
        .withContext('focus moved into the drawer')
        .toBeTrue();
      return aside;
    }

    function press(target: EventTarget, key: string, shiftKey = false): void {
      target.dispatchEvent(
        new KeyboardEvent('keydown', { key, shiftKey, bubbles: true, cancelable: true }),
      );
    }

    const attached: HTMLElement[] = [];
    afterEach(() => attached.splice(0).forEach((el) => el.remove()));

    it('focus moves in, Escape closes it and focus is back on Menu', () => {
      const { fixture, menu } = phoneShell();
      openFromMenu(fixture, menu);
      const mainArea = (fixture.nativeElement as HTMLElement).querySelector('.main-area')!;
      expect(mainArea.hasAttribute('inert')).withContext('the page behind is inert').toBeTrue();
      press(document.activeElement!, 'Escape');
      fixture.detectChanges();
      TestBed.tick();
      expect(fixture.componentInstance.sidebarCollapsed()).toBeTrue();
      expect(document.activeElement).withContext('focus back on Menu').toBe(menu);
      expect(mainArea.hasAttribute('inert')).toBeFalse();
      fixture.destroy();
    });

    it('Escape pressed with focus outside the drawer (on Menu, or fallen to the page) still closes it', () => {
      const { fixture, menu } = phoneShell();
      openFromMenu(fixture, menu);
      (document.activeElement as HTMLElement).blur();
      press(document.body, 'Escape');
      fixture.detectChanges();
      TestBed.tick();
      expect(fixture.componentInstance.sidebarCollapsed()).toBeTrue();
      expect(document.activeElement).toBe(menu);
      fixture.destroy();
    });

    it('Tab and Shift+Tab stay inside the drawer', () => {
      const { fixture, menu } = phoneShell();
      const aside = openFromMenu(fixture, menu);
      const focusable = Array.from(
        aside.querySelectorAll<HTMLElement>(
          'a[href], button:not([disabled]), input:not([disabled])',
        ),
      ).filter((el) => el.getClientRects().length > 0);
      const last = focusable[focusable.length - 1];
      last.focus();
      press(last, 'Tab');
      expect(document.activeElement)
        .withContext('Tab from the last control wraps')
        .toBe(focusable[0]);
      press(focusable[0], 'Tab', true);
      expect(document.activeElement).withContext('Shift+Tab from the first wraps').toBe(last);
      // Focus that left the drawer is brought back in by Tab.
      (document.activeElement as HTMLElement).blur();
      press(document.body, 'Tab');
      expect(aside.contains(document.activeElement)).toBeTrue();
      fixture.destroy();
    });

    it('a navigation from the open drawer closes it and puts focus on the new page, not on Menu', async () => {
      const { fixture, menu } = phoneShell();
      openFromMenu(fixture, menu);
      await TestBed.inject(Router).navigateByUrl('/');
      fixture.detectChanges();
      TestBed.tick();
      expect(fixture.componentInstance.sidebarCollapsed()).toBeTrue();
      const main = (fixture.nativeElement as HTMLElement).querySelector('#main-content');
      expect(document.activeElement).withContext('focus on <main>').toBe(main);
      fixture.destroy();
    });

    it('on a wide screen the expanded sidebar is a landmark, not a modal', () => {
      spyOn(globalThis, 'matchMedia').and.callFake(() => ({ matches: false }) as MediaQueryList);
      const fixture = build(['ROLE_PATIENT'], 'ROLE_PATIENT');
      fixture.detectChanges();
      const host = fixture.nativeElement as HTMLElement;
      expect(fixture.componentInstance.sidebarCollapsed()).toBeFalse();
      expect(host.querySelector('aside.sidebar')!.hasAttribute('role')).toBeFalse();
      expect(host.querySelector('aside.sidebar')!.hasAttribute('aria-modal')).toBeFalse();
      expect(host.querySelector('.main-area')!.hasAttribute('inert')).toBeFalse();
    });
  });

  it('a provider user whose settings are being asked again keeps the provider home, never the hospital nav', () => {
    const fixture = build(['ROLE_PHARMACIST'], 'ROLE_PHARMACIST', true);
    const context = TestBed.inject(ProviderContextService) as unknown as {
      settingsSignal: WritableSignal<ProviderSettings | null>;
      statusSignal: WritableSignal<string>;
    };
    context.settingsSignal.set(null);
    context.statusSignal.set('error');
    const routes = (fixture.componentInstance as unknown as { baseNavItems: () => NavItem[] })
      .baseNavItems()
      .map((i) => i.route);

    // Every hospital entry would 404 under provider confinement.
    expect(routes).toEqual(['/provider']);
  });

  it('never opens it for a provider user (the server refuses them the topic)', () => {
    const fixture = build(['ROLE_PHARMACIST'], 'ROLE_PHARMACIST', true);
    TestBed.inject(RoleContextService).markSessionResolved();
    fixture.detectChanges();
    expect(broadcastSpy().connect).not.toHaveBeenCalled();
  });

  it('after a hard refresh: no socket before the bootstrap answers, then one for staff, none for a provider', () => {
    // Staff: the flag is only its initial false until the bootstrap lands.
    const staff = build(['ROLE_DOCTOR'], 'ROLE_DOCTOR');
    staff.detectChanges();
    expect(broadcastSpy().connect).not.toHaveBeenCalled();
    const roleContext = TestBed.inject(RoleContextService);
    roleContext.setProviderUser(false);
    roleContext.markSessionResolved();
    staff.detectChanges();
    roleContext.markSessionResolved();
    staff.detectChanges();
    expect(broadcastSpy().connect).toHaveBeenCalledTimes(1);
  });

  it('after a hard refresh, a provider user never gets the socket, before or after the bootstrap', () => {
    const provider = build(['ROLE_PHARMACIST'], 'ROLE_PHARMACIST');
    provider.detectChanges();
    const roleContext = TestBed.inject(RoleContextService);
    roleContext.setProviderUser(true);
    roleContext.markSessionResolved();
    provider.detectChanges();
    expect(broadcastSpy().connect).not.toHaveBeenCalled();
  });

  it('drops all three from the side-nav for staff', () => {
    const fixture = build(['ROLE_DOCTOR'], 'ROLE_DOCTOR');
    const routes = (fixture.componentInstance as unknown as { baseNavItems: () => NavItem[] })
      .baseNavItems()
      .map((i) => i.route);

    expect(routes).not.toContain('/notifications');
    expect(routes).not.toContain('/chat');
    expect(routes).not.toContain('/announcements');
  });

  it('drops both from the patient side-nav — the topbar bell serves patients too', () => {
    // /notifications and the patient portal's own endpoint both call
    // notificationService.getNotificationsForUser(username), so the bell
    // shows a patient the same rows /my-notifications did.
    const fixture = build(['ROLE_PATIENT'], 'ROLE_PATIENT');
    const routes = (fixture.componentInstance as unknown as { baseNavItems: () => NavItem[] })
      .baseNavItems()
      .map((i) => i.route);

    expect(routes).not.toContain('/my-notifications');
    expect(routes).not.toContain('/chat');
  });

  it('renders Messages and Announcements links in the topbar', () => {
    const fixture = build(['ROLE_DOCTOR'], 'ROLE_DOCTOR');
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    const hrefs = Array.from(host.querySelectorAll('.topbar-right a.icon-btn')).map((a) =>
      a.getAttribute('href'),
    );
    expect(hrefs).toContain('/chat');
    expect(hrefs).toContain('/announcements');
  });

  it('shows the unread-message badge and clamps past 99', () => {
    const fixture = build(['ROLE_DOCTOR'], 'ROLE_DOCTOR');
    // detectChanges FIRST: it runs ngOnInit, whose loadUnreadMessages()
    // resets the count to 0 before its request resolves. Setting the value
    // beforehand would be overwritten.
    fixture.detectChanges();
    fixture.componentInstance.chatUnread.set(120);
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    const badges = Array.from(host.querySelectorAll('.topbar-right .badge')).map((b) =>
      b.textContent?.trim(),
    );
    expect(badges).toContain('99+');
  });

  /**
   * D812: at 375px the topbar was 736px wide, so the profile button (and
   * with it the only sign-out) sat off-screen while the PAGE did not
   * overflow, which is why a page-width check missed it. These measure the
   * topbar itself. The topbar compacts by its own width (container queries),
   * so constraining the shell to a phone's width here exercises the same CSS
   * a phone does; the phone's drawer is fixed and out of flow (a viewport
   * media query a desktop test browser does not match), so it is taken out
   * of flow the same way.
   */
  // The test browser has no Material Symbols font, so every icon would
  // render as its ligature text ("notifications" is ~90px wide). The real
  // font draws each one as a 1em square; mimic that so widths are real.
  let iconFont: HTMLStyleElement | null = null;
  beforeEach(() => {
    iconFont = document.createElement('style');
    iconFont.textContent =
      '.material-symbols-outlined{display:inline-block;width:1em;overflow:hidden;' +
      'white-space:nowrap;font-size:24px;line-height:1}';
    document.head.appendChild(iconFont);
  });
  afterEach(() => iconFont?.remove());

  function renderAtWidth(width: number, roles: string[], activeRole: string): HTMLElement {
    const fixture = build(roles, activeRole, false, {
      firstName: 'Habibou',
      lastName: 'Nana-Ouedraogo',
      roles,
    });
    fixture.detectChanges();
    // The drawer open, as when a phone user taps the hamburger.
    fixture.componentInstance.sidebarCollapsed.set(false);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    host.style.display = 'block';
    host.style.width = `${width}px`;
    (host.querySelector('.sidebar') as HTMLElement).style.position = 'fixed';
    return host;
  }

  function expectTopbarFits(host: HTMLElement, width: number): void {
    const topbar = host.querySelector('header.topbar') as HTMLElement;
    const bar = topbar.getBoundingClientRect();
    expect(Math.round(bar.width)).withContext('topbar width').toBe(width);
    expect(topbar.scrollWidth)
      .withContext('topbar scrollWidth')
      .toBeLessThanOrEqual(topbar.clientWidth);
    for (const selector of ['.mobile-toggle', '.icon-btn', '.profile-btn']) {
      for (const el of Array.from(topbar.querySelectorAll(selector))) {
        const box = el.getBoundingClientRect();
        if (box.width === 0) continue; // display:none at this width
        expect(box.left).withContext(`${selector} left`).toBeGreaterThanOrEqual(bar.left);
        expect(box.right).withContext(`${selector} right`).toBeLessThanOrEqual(bar.right);
      }
    }
    const profile = topbar.querySelector('.profile-btn')!.getBoundingClientRect();
    expect(profile.width).withContext('profile button rendered').toBeGreaterThan(0);
  }

  for (const [role, label] of [
    ['ROLE_PATIENT', 'a patient'],
    ['ROLE_DOCTOR', 'a doctor'],
  ] as const) {
    it(`keeps the profile menu on screen at 375px for ${label}, with a language picker (D812)`, () => {
      const host = renderAtWidth(375, [role], role);
      expectTopbarFits(host, 375);
      const select = host.querySelector('.lang-select') as HTMLSelectElement;
      expect(getComputedStyle(select).display).not.toBe('none');
      expect(getComputedStyle(host.querySelector('.lang-switcher')!).display).toBe('none');
    });
  }

  it('fits at 320px too, the narrowest phone still in use (D812)', () => {
    expectTopbarFits(renderAtWidth(320, ['ROLE_PATIENT'], 'ROLE_PATIENT'), 320);
  });

  it('keeps the wide topbar (button group, name) on a desktop (D812)', () => {
    const host = renderAtWidth(1280, ['ROLE_DOCTOR'], 'ROLE_DOCTOR');
    expectTopbarFits(host, 1280);
    expect(getComputedStyle(host.querySelector('.lang-select')!).display).toBe('none');
    expect(getComputedStyle(host.querySelector('.lang-switcher')!).display).not.toBe('none');
    expect(getComputedStyle(host.querySelector('.profile-name')!).display).not.toBe('none');
  });

  it('switches the language from the narrow picker (D812)', () => {
    const fixture = build(['ROLE_PATIENT'], 'ROLE_PATIENT');
    fixture.detectChanges();
    const switchLang = spyOn(fixture.componentInstance, 'switchLang');
    const select = (fixture.nativeElement as HTMLElement).querySelector(
      '.lang-select',
    ) as HTMLSelectElement;
    select.value = 'es';
    select.dispatchEvent(new Event('change'));
    expect(switchLang).toHaveBeenCalledOnceWith('es');
  });

  it('offers sign-out in the drawer, the only menu on a phone (D812)', () => {
    const host = renderAtWidth(375, ['ROLE_PATIENT'], 'ROLE_PATIENT');
    const logout = host.querySelector(
      '.sidebar [data-testid="sidebar-logout"]',
    ) as HTMLButtonElement;
    expect(logout).withContext('drawer sign-out').not.toBeNull();
    logout.click();
    expect((TestBed.inject(AuthService) as jasmine.SpyObj<AuthService>).logout).toHaveBeenCalled();
  });

  it('hides the badge at zero rather than rendering a 0', () => {
    const fixture = build(['ROLE_DOCTOR'], 'ROLE_DOCTOR');
    fixture.detectChanges();
    fixture.componentInstance.chatUnread.set(0);
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    const messagesLink = host.querySelector('.topbar-right a.icon-btn[href="/chat"]');
    expect(messagesLink?.querySelector('.badge')).toBeNull();
  });
});

describe('ShellComponent — route-level hospital scope gate', () => {
  /** Every route path carrying `data.requiresHospitalScope`, parent path joined in. */
  function flaggedRoutes(rs: Route[], prefix = ''): string[] {
    return rs.flatMap((r) => {
      const path = [prefix, r.path].filter(Boolean).join('/');
      const own = r.data?.['requiresHospitalScope'] === true ? [path] : [];
      return [...own, ...(r.children ? flaggedRoutes(r.children, path) : [])];
    });
  }

  it('flags exactly the one-facility pages, so a super-admin in global view is asked to pick first', () => {
    // The mapping behind this list is the "Hospital scope is applied page by
    // page" entry of tasklist.md: pages that read the hospital from the
    // session and send it as a param, path variable or body, or whose backend
    // resolves the raw context hospital, i.e. the ones that used to fail or
    // silently show the primary hospital in global view.
    expect(flaggedRoutes(routes).sort()).toEqual(
      [
        'admin/integrations/dhis2',
        'admin/order-sets',
        'appointments/calendar',
        'discharge',
        'emar',
        'lab-instruments',
        'lab-inventory',
        'lab-ops-dashboard',
        // The ten that used to gate themselves (own chip + scopeReady +
        // hint): moved onto the flag, their per-page block and chip deleted.
        'bed-board',
        'microbiology',
        'on-call',
        'panels',
        'pharmacy/mtm',
        'registries',
        'roi',
        'slot-admin',
        'transfusions',
        'webhooks',
        // The release worklist is one laboratory's queue: getPendingRelease
        // refuses a scopeless read outright rather than guessing a hospital,
        // so the page must not render until one is pinned.
        'lab-release-worklist',
        'lab-staff',
        'maternity',
        'nurse-station',
        'patient-tracker',
        'procedure-orders',
        'reception',
        'registrations',
        'reports',
        // QA D853: one hospital's wards; in global view it mixed every
        // hospital's wards and 'Nouvelle unité' could only fail.
        'bed-management',
      ].sort(),
    );
  });
});
