import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { ReferralsComponent } from './referrals';
import { RoleContextService } from '../core/role-context.service';
import { AuthService } from '../auth/auth.service';
import { signal } from '@angular/core';

describe('ReferralsComponent', () => {
  let component: ReferralsComponent;
  let fixture: ComponentFixture<ReferralsComponent>;
  let httpMock: HttpTestingController;
  let roleContextStub: jasmine.SpyObj<RoleContextService>;
  let authStub: jasmine.SpyObj<AuthService>;

  const mockHospital = {
    id: 'h1',
    name: 'Main Hospital',
    code: 'MH',
    hospitalType: 'GENERAL',
    cityOrTown: '',
    stateOrRegion: '',
    country: '',
    phoneNumber: '',
    email: '',
    address: '',
    active: true,
    createdAt: '',
    updatedAt: '',
    organizationId: '',
    organizationName: '',
  };

  const mockHospital2 = {
    ...mockHospital,
    id: 'h2',
    name: 'Remote Hospital',
    code: 'RH',
  };

  beforeEach(async () => {
    // Cross-tenant chip injects RoleContextService and reads three
    // signals (isSuperAdmin / globalView / selectedHospitalId) plus two
    // mutators (scopeToHospital / enableGlobalView). The chip itself
    // also calls `effectiveHospitalIdForRequest()` indirectly via the
    // auth interceptor, but in unit tests `provideHttpClientTesting()`
    // bypasses interceptors entirely so we don't need to stub it.
    roleContextStub = jasmine.createSpyObj(
      'RoleContextService',
      ['scopeToHospital', 'enableGlobalView'],
      {
        isSuperAdmin: signal(false),
        globalView: signal(false),
        selectedHospitalId: signal<string | null>(null),
      },
    );
    authStub = jasmine.createSpyObj('AuthService', ['getUserProfile']);
    authStub.getUserProfile.and.returnValue({ staffId: 'staff-1' } as any);

    await TestBed.configureTestingModule({
      imports: [ReferralsComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: RoleContextService, useValue: roleContextStub },
        { provide: AuthService, useValue: authStub },
      ],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ReferralsComponent);
    component = fixture.componentInstance;
  });

  afterEach(() => httpMock.verify());

  function flushInitRequests(): void {
    // ngOnInit triggers: load(), loadAssignedHospitals(), loadAllHospitals()
    httpMock.match((req) => req.url.includes('/referrals')).forEach((r) => r.flush([]));
    const myHospReq = httpMock.match((req) => req.url.includes('/hospitals/me'));
    myHospReq.forEach((r) => r.flush(mockHospital));
    const allHospReq = httpMock.match((req) => req.url.includes('/hospitals'));
    allHospReq.forEach((r) => r.flush([mockHospital, mockHospital2]));
    // source departments for the locked hospital
    const deptReqs = httpMock.match((req) => req.url.includes('/departments/active-minimal'));
    deptReqs.forEach((r) => r.flush({ data: [{ id: 'd1', name: 'Emergency' }] }));
  }

  it('should create', () => {
    flushInitRequests();
    expect(component).toBeTruthy();
  });

  it('emptyForm should include new fields with defaults', () => {
    flushInitRequests();
    const form = component.emptyForm();
    expect(form.receivingHospitalId).toBe('');
    expect(form.sourceDepartmentId).toBe('');
    expect(form.targetDepartmentId).toBe('');
    expect(form.referringProviderId).toBe('staff-1');
  });

  it('onReceivingHospitalChange should load target departments', () => {
    flushInitRequests();

    component.onReceivingHospitalChange('h2');

    const deptReq = httpMock.expectOne('/departments/active-minimal/h2');
    deptReq.flush({
      data: [
        { id: 'd2', name: 'Cardiology' },
        { id: 'd3', name: 'Neurology' },
      ],
    });

    expect(component.targetDepartments().length).toBe(2);
    expect(component.form.receivingHospitalId).toBe('h2');
    expect(component.form.targetDepartmentId).toBe('');
  });

  it('onReceivingHospitalChange with empty id should clear target departments', () => {
    flushInitRequests();

    component.targetDepartments.set([{ id: 'd1', name: 'X' }]);
    component.onReceivingHospitalChange('');

    expect(component.targetDepartments().length).toBe(0);
    expect(component.form.targetDepartmentId).toBe('');
  });

  it('loadSourceDepartments should populate source departments', () => {
    flushInitRequests();

    component.loadSourceDepartments('h1');

    const req = httpMock.expectOne('/departments/active-minimal/h1');
    req.flush({
      data: [
        { id: 'd1', name: 'Emergency' },
        { id: 'd4', name: 'ICU' },
      ],
    });

    expect(component.sourceDepartments().length).toBe(2);
  });

  it('loadSourceDepartments with empty id should clear departments', () => {
    flushInitRequests();

    component.sourceDepartments.set([{ id: 'd1', name: 'X' }]);
    component.loadSourceDepartments('');

    expect(component.sourceDepartments().length).toBe(0);
  });

  it('onSourceHospitalChange should reset sourceDepartmentId and reload departments', () => {
    flushInitRequests();

    component.form.sourceDepartmentId = 'd-old';
    component.onSourceHospitalChange('h2');

    expect(component.form.hospitalId).toBe('h2');
    expect(component.form.sourceDepartmentId).toBe('');

    const req = httpMock.expectOne('/departments/active-minimal/h2');
    req.flush({ data: [{ id: 'd5', name: 'Lab' }] });

    expect(component.sourceDepartments().length).toBe(1);
  });

  it('hospitalLocked should be true for non-super-admin', () => {
    flushInitRequests();
    expect(component.hospitalLocked).toBeTrue();
  });

  // ── P1 #12 lifecycle transitions ──────────────────────────────────────

  it('executeSchedule posts to /referrals/{id}/schedule with appointment time + location', () => {
    flushInitRequests();
    component.schedulingRef.set({ id: 'r1', patientName: 'X' } as any);
    component.scheduleAppointmentTime = '2026-06-01T09:30';
    component.scheduleLocation = 'Clinic 4';

    component.executeSchedule();

    const req = httpMock.expectOne('/referrals/r1/schedule');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      appointmentTime: '2026-06-01T09:30',
      location: 'Clinic 4',
    });
    req.flush({ id: 'r1', status: 'SCHEDULED' });
    httpMock.match((r) => r.url.includes('/referrals')).forEach((r) => r.flush([]));
    httpMock.match((r) => r.url.includes('/hospitals')).forEach((r) => r.flush([]));
  });

  it('executeSchedule is a no-op when appointment time is empty', () => {
    flushInitRequests();
    component.schedulingRef.set({ id: 'r1' } as any);
    component.scheduleAppointmentTime = '';

    component.executeSchedule();

    httpMock.expectNone('/referrals/r1/schedule');
    expect(component.actionLoading()).toBeFalse();
  });

  it('startReferral posts to /referrals/{id}/start with empty body', () => {
    flushInitRequests();

    component.startReferral({ id: 'r2' } as any);

    const req = httpMock.expectOne('/referrals/r2/start');
    expect(req.request.method).toBe('POST');
    req.flush({ id: 'r2', status: 'IN_PROGRESS' });
    httpMock.match((r) => r.url.includes('/referrals')).forEach((r) => r.flush([]));
    httpMock.match((r) => r.url.includes('/hospitals')).forEach((r) => r.flush([]));
  });

  it('executeReject posts to /referrals/{id}/reject with reason', () => {
    flushInitRequests();
    component.rejectingRef.set({ id: 'r3', patientName: 'Y' } as any);
    component.rejectReason = 'Out of scope';

    component.executeReject();

    const req = httpMock.expectOne('/referrals/r3/reject');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ reason: 'Out of scope' });
    req.flush({ id: 'r3', status: 'REJECTED' });
    httpMock.match((r) => r.url.includes('/referrals')).forEach((r) => r.flush([]));
    httpMock.match((r) => r.url.includes('/hospitals')).forEach((r) => r.flush([]));
  });

  it('executeReject is a no-op when reason is blank', () => {
    flushInitRequests();
    component.rejectingRef.set({ id: 'r3' } as any);
    component.rejectReason = '   ';

    component.executeReject();

    httpMock.expectNone('/referrals/r3/reject');
    expect(component.actionLoading()).toBeFalse();
  });

  it('getStatusClass maps SCHEDULED to active and REJECTED/EXPIRED to expected groups', () => {
    flushInitRequests();
    expect(component.getStatusClass('SCHEDULED')).toBe('status-active');
    expect(component.getStatusClass('REJECTED')).toBe('status-cancelled');
    expect(component.getStatusClass('EXPIRED')).toBe('status-overdue');
  });

  it('applyFilter "active" tab includes SCHEDULED referrals', () => {
    flushInitRequests();
    component.referrals.set([
      { id: 'a', status: 'ACKNOWLEDGED' } as any,
      { id: 's', status: 'SCHEDULED' } as any,
      { id: 'd', status: 'DRAFT' } as any,
    ]);
    component.setTab('active');
    expect(
      component
        .filtered()
        .map((r) => r.id)
        .sort(),
    ).toEqual(['a', 's']);
  });

  it('applyFilter "completed" tab includes REJECTED and EXPIRED referrals', () => {
    flushInitRequests();
    component.referrals.set([
      { id: 'c', status: 'COMPLETED' } as any,
      { id: 'r', status: 'REJECTED' } as any,
      { id: 'e', status: 'EXPIRED' } as any,
      { id: 'p', status: 'SUBMITTED' } as any,
    ]);
    component.setTab('completed');
    expect(
      component
        .filtered()
        .map((r) => r.id)
        .sort(),
    ).toEqual(['c', 'e', 'r']);
  });

  it('countByGroup matches the row count shown when each tab is active', () => {
    flushInitRequests();
    component.referrals.set([
      { id: 'd', status: 'DRAFT' } as any,
      { id: 's', status: 'SUBMITTED' } as any,
      { id: 'a', status: 'ACKNOWLEDGED' } as any,
      { id: 'sc', status: 'SCHEDULED' } as any,
      { id: 'ip', status: 'IN_PROGRESS' } as any,
      { id: 'co', status: 'COMPLETED' } as any,
      { id: 'ca', status: 'CANCELLED' } as any,
      { id: 're', status: 'REJECTED' } as any,
      { id: 'ex', status: 'EXPIRED' } as any,
    ]);
    for (const tab of ['pending', 'active', 'completed'] as const) {
      component.setTab(tab);
      expect(component.countByGroup(tab))
        .withContext(`badge count for ${tab} tab must equal filtered row count`)
        .toBe(component.filtered().length);
    }
  });
});
