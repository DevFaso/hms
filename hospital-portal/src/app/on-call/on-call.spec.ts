import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { OnCallComponent } from './on-call';
import { OnCallService, OnCallScheduleResponse } from '../services/on-call.service';
import { StaffService } from '../services/staff.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { AuthService } from '../auth/auth.service';

/**
 * The on-call rota (P2 #13) — first writer for the table behind the
 * dashboard's on-call pill, which could only ever say "Off duty" before this
 * page existed.
 */
describe('OnCallComponent', () => {
  let fixture: ComponentFixture<OnCallComponent>;
  let component: OnCallComponent;
  let onCallService: jasmine.SpyObj<OnCallService>;
  let toast: jasmine.SpyObj<ToastService>;

  function entry(overrides: Partial<OnCallScheduleResponse>): OnCallScheduleResponse {
    return {
      id: 'oc-1',
      staffId: 's-1',
      staffName: 'Dr. Awa Traoré',
      startTime: '2026-08-22T08:00:00Z',
      endTime: '2026-08-22T20:00:00Z',
      currentlyOnCall: false,
      ...overrides,
    };
  }

  function setup(activeRoles: string[], entries: OnCallScheduleResponse[]) {
    onCallService = jasmine.createSpyObj<OnCallService>('OnCallService', [
      'list',
      'listForStaff',
      'create',
      'update',
      'delete',
      'listDepartments',
    ]);
    onCallService.list.and.returnValue(of(entries));
    onCallService.listDepartments.and.returnValue(of([]));

    const staffService = jasmine.createSpyObj<StaffService>('StaffService', ['list']);
    staffService.list.and.returnValue(of([]));

    const auth = jasmine.createSpyObj<AuthService>('AuthService', ['getHospitalId']);
    auth.getHospitalId.and.returnValue('h-1');

    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'info']);

    TestBed.configureTestingModule({
      imports: [OnCallComponent, TranslateModule.forRoot()],
      providers: [
        { provide: OnCallService, useValue: onCallService },
        { provide: StaffService, useValue: staffService },
        { provide: AuthService, useValue: auth },
        { provide: ToastService, useValue: toast },
        {
          provide: RoleContextService,
          useValue: {
            activeHospitalId: 'h-1',
            hasAnyActiveRole: (roles: string[]) => roles.some((r) => activeRoles.includes(r)),
          },
        },
      ],
    });

    fixture = TestBed.createComponent(OnCallComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  it('loads the rota and marks who is on call right now', () => {
    setup(['ROLE_DOCTOR'], [entry({ id: 'oc-1', currentlyOnCall: true }), entry({ id: 'oc-2' })]);

    expect(onCallService.list).toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('[data-testid="oncall-row-oc-1"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="oncall-now-badge"]')).not.toBeNull();
  });

  it('offers write controls only to the backend WRITE roles', () => {
    // READ_ROLES spans six roles; WRITE_ROLES is HOSPITAL_ADMIN/SUPER_ADMIN.
    // A doctor seeing an Add button that 403s is the defect class this
    // initiative keeps finding — gate it in-component.
    setup(['ROLE_DOCTOR'], [entry({})]);
    expect(fixture.nativeElement.querySelector('[data-testid="oncall-add"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="oncall-edit-oc-1"]')).toBeNull();
  });

  it('shows write controls for a hospital admin', () => {
    setup(['ROLE_HOSPITAL_ADMIN'], [entry({})]);
    expect(fixture.nativeElement.querySelector('[data-testid="oncall-add"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="oncall-edit-oc-1"]')).not.toBeNull();
  });

  it('refuses a shift that ends before it starts, before any request is made', () => {
    setup(['ROLE_HOSPITAL_ADMIN'], []);
    component.form = {
      staffId: 's-1',
      departmentId: '',
      startLocal: '2026-08-22T20:00',
      endLocal: '2026-08-22T08:00',
      notes: '',
    };

    component.submit();

    expect(toast.error).toHaveBeenCalled();
    expect(onCallService.create).not.toHaveBeenCalled();
  });

  it('sends ISO timestamps and omits departmentId for a hospital-wide entry', () => {
    setup(['ROLE_HOSPITAL_ADMIN'], []);
    onCallService.create.and.returnValue(of(entry({})));
    component.form = {
      staffId: 's-1',
      departmentId: '',
      startLocal: '2026-08-22T08:00',
      endLocal: '2026-08-22T20:00',
      notes: '',
    };

    component.submit();

    const request = onCallService.create.calls.mostRecent().args[0];
    expect(request.staffId).toBe('s-1');
    expect(request.departmentId).toBeUndefined();
    expect(request.startTime).toMatch(/Z$/);
    expect(toast.success).toHaveBeenCalled();
  });

  it('surfaces the overlap refusal verbatim instead of a generic failure', () => {
    setup(['ROLE_HOSPITAL_ADMIN'], []);
    const backendMessage = 'This staff member is already on call over part of that window.';
    onCallService.create.and.returnValue(
      throwError(() => ({ error: { message: backendMessage } }) as unknown),
    );
    component.form = {
      staffId: 's-1',
      departmentId: '',
      startLocal: '2026-08-22T08:00',
      endLocal: '2026-08-22T20:00',
      notes: '',
    };

    component.submit();

    expect(toast.error).toHaveBeenCalledWith(backendMessage);
  });

  it('deletes only after confirmation, then reloads', () => {
    const row = entry({ id: 'oc-9' });
    setup(['ROLE_HOSPITAL_ADMIN'], [row]);
    onCallService.delete.and.returnValue(of(void 0));

    component.confirmDelete(row);
    fixture.detectChanges();
    expect(
      fixture.nativeElement.querySelector('[data-testid="oncall-delete-modal"]'),
    ).not.toBeNull();

    component.executeDelete();

    expect(onCallService.delete).toHaveBeenCalledWith('oc-9');
    expect(onCallService.list).toHaveBeenCalledTimes(2);
  });
});
