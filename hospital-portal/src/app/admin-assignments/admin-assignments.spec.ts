import { TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { TranslateService } from '@ngx-translate/core';
import { of } from 'rxjs';

import { AdminAssignmentsComponent } from './admin-assignments';
import { AssignmentAdminService, AssignmentResponse } from '../services/assignment-admin.service';
import { HospitalService } from '../services/hospital.service';
import { RoleResponse, RoleService } from '../services/role.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';

/**
 * QA D194: a PATIENT assignment is activated by the patient's verification,
 * so the server refuses active=true; the create form had Actif checked by
 * default and sent it, which the server refused.
 */
describe('AdminAssignmentsComponent — patient assignments start inactive (QA D194)', () => {
  let component: AdminAssignmentsComponent;
  let service: jasmine.SpyObj<AssignmentAdminService>;

  beforeEach(() => {
    service = jasmine.createSpyObj<AssignmentAdminService>('AssignmentAdminService', [
      'create',
      'update',
    ]);
    service.create.and.returnValue(of({} as AssignmentResponse));
    TestBed.configureTestingModule({
      providers: [
        { provide: AssignmentAdminService, useValue: service },
        { provide: HospitalService, useValue: {} },
        { provide: RoleService, useValue: {} },
        { provide: RoleContextService, useValue: {} },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error']),
        },
        { provide: TranslateService, useValue: { instant: (k: string) => k } },
        { provide: ActivatedRoute, useValue: {} },
      ],
    });
    component = TestBed.runInInjectionContext(() => new AdminAssignmentsComponent());
    spyOn(component, 'load');
    component.roles.set([
      { id: 'r-patient', code: 'ROLE_PATIENT' } as RoleResponse,
      { id: 'r-nurse', code: 'ROLE_NURSE' } as RoleResponse,
    ]);
    component.formUserIdentifier.set('qa.user');
  });

  it('sends a patient assignment inactive even though Actif defaults to checked', () => {
    component.formActive.set(true);
    component.formRoleId.set('r-patient');
    expect(component.patientRoleSelected()).toBeTrue();
    component.submitCreate();
    expect(service.create.calls.mostRecent().args[0].active).toBeFalse();
  });

  it('keeps the chosen state for any other role', () => {
    component.formActive.set(true);
    component.formRoleId.set('r-nurse');
    expect(component.patientRoleSelected()).toBeFalse();
    component.submitCreate();
    expect(service.create.calls.mostRecent().args[0].active).toBeTrue();
  });
});
