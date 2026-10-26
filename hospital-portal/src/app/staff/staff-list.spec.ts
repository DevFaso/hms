import { TestBed } from '@angular/core/testing';
import { TranslateService } from '@ngx-translate/core';
import { of } from 'rxjs';

import { StaffListComponent } from './staff-list';
import { StaffResponse, StaffService } from '../services/staff.service';
import { HospitalService } from '../services/hospital.service';
import { UserService } from '../services/user.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { PermissionService } from '../core/permission.service';

/**
 * QA D195: the specialization list is medical specialties, and the server
 * requires one for doctors and radiologists only. The form required it for
 * pharmacists, nurses and lab staff too, and sent it for them.
 */
describe('StaffListComponent — specialization only for physicians (QA D195)', () => {
  let component: StaffListComponent;
  let staffService: jasmine.SpyObj<StaffService>;

  beforeEach(() => {
    staffService = jasmine.createSpyObj<StaffService>('StaffService', ['create', 'update', 'list']);
    staffService.create.and.returnValue(of({} as StaffResponse));
    staffService.list.and.returnValue(of([]));
    TestBed.configureTestingModule({
      providers: [
        { provide: StaffService, useValue: staffService },
        { provide: HospitalService, useValue: {} },
        { provide: UserService, useValue: {} },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error']),
        },
        { provide: RoleContextService, useValue: {} },
        { provide: PermissionService, useValue: { hasPermission: () => true } },
        { provide: TranslateService, useValue: { instant: (k: string) => k } },
      ],
    });
    component = TestBed.runInInjectionContext(() => new StaffListComponent());
    spyOn(component, 'loadStaff');
  });

  for (const title of ['PHARMACIST', 'NURSE', 'LABORATORY_SCIENTIST']) {
    it(`does not ask a ${title} for a specialization, nor send one`, () => {
      component.form = {
        ...component.form,
        userEmail: 'a@example.test',
        hospitalName: 'H1',
        jobTitle: title,
        specialization: 'CARDIOLOGY',
        licenseNumber: 'LIC-1',
      } as never;
      expect(component.needsSpecialization).toBeFalse();
      component.submitForm();
      const payload = staffService.create.calls.mostRecent().args[0];
      expect(payload.specialization).toBeUndefined();
      // The licence is still theirs to record.
      expect(payload.licenseNumber).toBe('LIC-1');
    });
  }

  for (const title of ['DOCTOR', 'RADIOLOGIST']) {
    it(`still asks a ${title} for a specialization`, () => {
      component.form = { ...component.form, jobTitle: title } as never;
      expect(component.needsSpecialization).toBeTrue();
    });
  }
});

describe('StaffListComponent — one hospital-admin job title (QA D541)', () => {
  let component: StaffListComponent;

  beforeEach(() => {
    const staffService = jasmine.createSpyObj<StaffService>('StaffService', ['list']);
    staffService.list.and.returnValue(of([]));
    TestBed.configureTestingModule({
      providers: [
        { provide: StaffService, useValue: staffService },
        { provide: HospitalService, useValue: {} },
        { provide: UserService, useValue: {} },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error']),
        },
        { provide: RoleContextService, useValue: {} },
        { provide: PermissionService, useValue: { hasPermission: () => true } },
        { provide: TranslateService, useValue: { instant: (k: string) => k } },
      ],
    });
    component = TestBed.runInInjectionContext(() => new StaffListComponent());
  });

  it('offers "hospital admin" once', () => {
    expect(component.jobTitles).toContain('HOSPITAL_ADMIN');
    expect(component.jobTitles).not.toContain('HOSPITAL_ADMINISTRATOR');
  });

  it('still finds the legacy HOSPITAL_ADMINISTRATOR rows under that one filter', () => {
    component.staff.set([
      { id: '1', jobTitle: 'HOSPITAL_ADMINISTRATOR' },
      { id: '2', jobTitle: 'HOSPITAL_ADMIN' },
      { id: '3', jobTitle: 'NURSE' },
    ] as StaffResponse[]);
    component.jobTitleFilter = 'HOSPITAL_ADMIN';
    component.applyFilter();
    expect(component.filtered().map((s) => s.id)).toEqual(['1', '2']);
  });
});

/** QA D818: a new staff member is never placed at an archived hospital. */
describe('StaffListComponent — no archived hospital for a new placement (QA D818)', () => {
  it('offers open hospitals, and an edited member keeps their own closed one', () => {
    TestBed.configureTestingModule({
      providers: [
        { provide: StaffService, useValue: { list: () => of([]) } },
        { provide: HospitalService, useValue: {} },
        { provide: UserService, useValue: {} },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error']),
        },
        { provide: RoleContextService, useValue: {} },
        { provide: PermissionService, useValue: { hasPermission: () => true } },
        { provide: TranslateService, useValue: { instant: (k: string) => k } },
      ],
    });
    const component = TestBed.runInInjectionContext(() => new StaffListComponent());
    component.hospitals.set([
      { id: 'h1', name: 'CMA Pissy', lifecycleState: 'ACTIVE' },
      { id: 'h2', name: 'Ancien CMA', lifecycleState: 'ARCHIVED' },
      { id: 'h3', name: 'CSPS Tanghin', lifecycleState: 'SUSPENDED' },
    ] as never);
    expect(component.hospitalOptions().map((h) => h.id)).toEqual(['h1', 'h3']);
    component.editing.set({ id: 's1', hospitalName: 'Ancien CMA' } as StaffResponse);
    expect(component.hospitalOptions().map((h) => h.id)).toEqual(['h1', 'h2', 'h3']);
  });
});
