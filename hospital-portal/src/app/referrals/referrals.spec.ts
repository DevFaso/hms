import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { ReferralsComponent } from './referrals';
import {
  EXPECTED_PICKER_SUBLINES,
  renderPickerSublines,
} from '../testing/patient-subline.fixtures';
import { RoleContextService } from '../core/role-context.service';
import { AuthService } from '../auth/auth.service';
import { ToastService } from '../core/toast.service';
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
      ['scopeToHospital', 'enableGlobalView', 'hasAnyActiveRole'],
      {
        isSuperAdmin: signal(false),
        globalView: signal(false),
        selectedHospitalId: signal<string | null>(null),
        effectiveHospitalIdForRequest: signal<string | null>('h-A'),
      },
    );
    roleContextStub.hasAnyActiveRole.and.returnValue(true);
    authStub = jasmine.createSpyObj('AuthService', ['getUserProfile']);
    authStub.getUserProfile.and.returnValue({ staffId: 'staff-1' } as any);

    await TestBed.configureTestingModule({
      imports: [ReferralsComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
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
    const myHospReq = httpMock.match((req) => req.url === '/me/hospital');
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

  // QA D903/D912: the chip showed the email, or an empty line for a
  // phone-only patient, instead of this hospital's MRN.
  it('names the patient by MRN in the chip and the options (QA D912)', () => {
    flushInitRequests();
    fixture.detectChanges();
    flushInitRequests();
    component.openCreate();
    // The form loads the locked hospital's departments as it opens.
    flushInitRequests();
    fixture.detectChanges();
    expect(renderPickerSublines(fixture, component)).toEqual(EXPECTED_PICKER_SUBLINES);
  });

  it('QA D427: a nurse is not offered New referral, which the API refuses', () => {
    roleContextStub.hasAnyActiveRole.and.callFake((roles: string[]) =>
      roles.includes('ROLE_NURSE'),
    );
    flushInitRequests();
    fixture.detectChanges();
    flushInitRequests();
    const buttons = Array.from(
      fixture.nativeElement.querySelectorAll('.page-actions .btn-primary'),
    );
    expect(buttons.length).toBe(0);
  });

  for (const role of ['ROLE_NURSE', 'ROLE_MIDWIFE']) {
    it(`QA D661: ${role} is not offered Cancel, Acknowledge or Reject, which the API refuses`, () => {
      roleContextStub.hasAnyActiveRole.and.callFake((roles: string[]) => roles.includes(role));
      flushInitRequests();
      fixture.detectChanges();
      flushInitRequests();
      component.loading.set(false);
      component.filtered.set([{ id: 'r-1', status: 'SUBMITTED' } as any]);
      fixture.detectChanges();
      const el: HTMLElement = fixture.nativeElement;
      expect(el.querySelector('.action-link.delete')).toBeNull();
      expect(el.querySelector('.action-link.acknowledge')).toBeNull();
      expect(el.querySelector('.action-link.reject')).toBeNull();
      component.selectedReferral.set({ id: 'r-1', status: 'SUBMITTED' } as any);
      fixture.detectChanges();
      expect(el.querySelector('.btn-danger')).toBeNull();
    });
  }

  it('QA D661: a doctor is still offered Cancel on an open referral they wrote', () => {
    roleContextStub.hasAnyActiveRole.and.callFake((roles: string[]) =>
      roles.includes('ROLE_DOCTOR'),
    );
    flushInitRequests();
    fixture.detectChanges();
    flushInitRequests();
    component.loading.set(false);
    component.filtered.set([
      { id: 'r-1', status: 'SUBMITTED', referringProviderId: 'staff-1' } as any,
    ]);
    fixture.detectChanges();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('.action-link.delete'),
    ).not.toBeNull();
  });

  it('QA D611: DOCTOR2 is not offered Cancel on a DRAFT another doctor wrote', () => {
    roleContextStub.hasAnyActiveRole.and.callFake((roles: string[]) =>
      roles.includes('ROLE_DOCTOR'),
    );
    flushInitRequests();
    fixture.detectChanges();
    flushInitRequests();
    component.loading.set(false);
    const theirs = { id: 'r-2', status: 'DRAFT', referringProviderId: 'staff-other' } as any;
    component.filtered.set([theirs]);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('.action-link.delete')).toBeNull();
    component.selectedReferral.set(theirs);
    fixture.detectChanges();
    expect(el.querySelector('.btn-danger')).toBeNull();
  });

  function showAsDoctor(ref: any): HTMLElement {
    roleContextStub.hasAnyActiveRole.and.callFake((roles: string[]) =>
      roles.includes('ROLE_DOCTOR'),
    );
    flushInitRequests();
    fixture.detectChanges();
    flushInitRequests();
    component.loading.set(false);
    component.filtered.set([ref]);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('QA D775: the sending hospital is not offered Acknowledge or Reject (403 at the API)', () => {
    const sent = {
      id: 'r-3',
      status: 'SUBMITTED',
      hospitalId: 'h-A',
      receivingHospitalId: 'h-B',
      referringProviderId: 'staff-1',
    } as any;
    const el = showAsDoctor(sent);
    expect(el.querySelector('.action-link.acknowledge')).toBeNull();
    expect(el.querySelector('.action-link.reject')).toBeNull();
    component.selectedReferral.set(sent);
    fixture.detectChanges();
    const labels = Array.from(
      el.querySelectorAll('.modal-actions button, .btn-primary, .btn-danger'),
    ).map((b) => b.textContent ?? '');
    expect(labels.some((t) => t.includes('REFERRALS.ACTION_ACKNOWLEDGE'))).toBeFalse();
    expect(labels.some((t) => t.includes('REFERRALS.ACTION_REJECT'))).toBeFalse();
    // The ACKNOWLEDGED steps are the receiver's too.
    expect(component.isReceivingSide({ ...sent, status: 'ACKNOWLEDGED' })).toBeFalse();
  });

  it('QA D775: the receiving hospital keeps Acknowledge and Reject', () => {
    const received = {
      id: 'r-4',
      status: 'SUBMITTED',
      hospitalId: 'h-B',
      receivingHospitalId: 'h-A',
      referringProviderId: 'staff-other',
    } as any;
    const el = showAsDoctor(received);
    expect(el.querySelector('.action-link.acknowledge')).not.toBeNull();
    expect(el.querySelector('.action-link.reject')).not.toBeNull();
  });

  it('QA D775: an internal referral (no receiving hospital) is acted on at its own hospital', () => {
    expect(component.isReceivingSide({ id: 'r-5', hospitalId: 'h-A' } as any)).toBeTrue();
  });

  it('QA D611: a refused cancel shows the server reason in the dialog', () => {
    roleContextStub.hasAnyActiveRole.and.callFake((roles: string[]) =>
      roles.includes('ROLE_DOCTOR'),
    );
    flushInitRequests();
    fixture.detectChanges();
    flushInitRequests();
    component.confirmCancel({ id: 'r-3', status: 'DRAFT', referringProviderId: 'staff-1' } as any);
    component.executeCancel();
    const reason = "Seul le soignant qui a rédigé cette orientation peut l'annuler.";
    httpMock
      .expectOne((req) => req.method === 'POST' && req.url === '/referrals/r-3/cancel')
      .flush({ message: reason }, { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();
    const err = (fixture.nativeElement as HTMLElement).querySelector('.form-error');
    expect(err?.textContent?.trim()).toBe(reason);
    expect(component.showDeleteConfirm()).toBeTrue();
  });

  it('QA D412/D427: only the author of a draft is offered Submit', () => {
    flushInitRequests();
    const draft = (referringProviderId: string, status = 'DRAFT') =>
      ({ id: 'r', status, referringProviderId }) as any;
    expect(component.isMyDraft(draft('staff-1'))).toBeTrue();
    expect(component.isMyDraft(draft('doctor-2'))).toBeFalse();
    expect(component.isMyDraft(draft('staff-1', 'SUBMITTED'))).toBeFalse();
  });

  it('QA D36: the destination picker reads the hospital directory, not the scoped list', () => {
    fixture.detectChanges();
    const directory = httpMock.match((req) => req.url === '/hospitals/directory');
    expect(directory.length).toBe(1);
    directory[0].flush([{ id: 'h2', name: 'Remote Hospital', city: 'Bobo' }]);
    expect(httpMock.match((req) => req.url === '/hospitals').length).toBe(0);
    flushInitRequests();
    expect(component.allHospitals().map((h) => h.id)).toEqual(['h2']);
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
      // #948 review: the referral appointment is wall clock (the server books
      // an Appointment at this date and start time), so it is sent unconverted.
      appointmentTime: '2026-06-01T09:30',
      location: 'Clinic 4',
    });
    req.flush({ id: 'r1', status: 'SCHEDULED' });
    httpMock.match((r) => r.url.includes('/referrals')).forEach((r) => r.flush([]));
    httpMock.match((r) => r.url.includes('/hospitals')).forEach((r) => r.flush([]));
  });

  it('QA D890: an empty referral form is not posted; the toast names fields by their labels', () => {
    const toast = TestBed.inject(ToastService);
    spyOn(toast, 'error');
    flushInitRequests();
    component.openCreate();

    component.submitForm();

    httpMock.expectNone((r) => r.method === 'POST' && r.url === '/referrals');
    const message = (toast.error as jasmine.Spy).calls.mostRecent().args[0] as string;
    expect(message).toContain('COMMON.MISSING_REQUIRED_FIELDS');
    expect(message).not.toContain('targetSpecialty');
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

  it('QA D243: the urgency options are translated, not raw enum names', () => {
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', {
      PORTAL: {
        ENUM: {
          REFERRAL_URGENCY: {
            ROUTINE: 'Routine',
            PRIORITY: 'Prioritaire',
            URGENT: 'Urgente',
            EMERGENCY: 'Urgence',
          },
        },
      },
    });
    translate.use('fr');
    fixture.detectChanges();
    flushInitRequests();
    component.openCreate();
    fixture.detectChanges();
    httpMock.match(() => true).forEach((r) => r.flush([]));
    fixture.detectChanges();
    const options = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('#ref-urgency option'),
    ).map((o) => o.textContent?.trim());
    expect(options).toEqual(['Routine', 'Prioritaire', 'Urgente', 'Urgence']);
  });
});
