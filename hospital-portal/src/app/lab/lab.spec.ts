import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { TranslateModule } from '@ngx-translate/core';
import { LabComponent } from './lab';
import { LabService } from '../services/lab.service';
import { HospitalService } from '../services/hospital.service';
import { PatientService } from '../services/patient.service';
import { ProfileService } from '../services/profile.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { AuthService } from '../auth/auth.service';

describe('LabComponent', () => {
  it('scopes patient search to selected hospital when creating lab orders', (done) => {
    const patientServiceMock = jasmine.createSpyObj<PatientService>('PatientService', ['list']);
    patientServiceMock.list.and.returnValue(of([]));

    const authMock = jasmine.createSpyObj<AuthService>('AuthService', ['hasAnyRole', 'getUserProfile']);
    authMock.hasAnyRole.and.returnValue(true);
    authMock.getUserProfile.and.returnValue({
      id: 'u1',
      username: 'user',
      email: 'user@example.com',
      roles: ['ROLE_DOCTOR'],
      active: true,
      staffId: 'staff-1',
    });

    TestBed.configureTestingModule({
      imports: [LabComponent, TranslateModule.forRoot()],
      providers: [
        { provide: LabService, useValue: { listTestDefinitions: () => of([]), listOrders: () => of([]) } },
        { provide: HospitalService, useValue: { getMyHospitalAsResponse: () => of({ id: 'h-1', name: 'H1' }) } },
        { provide: PatientService, useValue: patientServiceMock },
        { provide: ProfileService, useValue: { getAssignments: () => of([]) } },
        {
          provide: ToastService,
          useValue: { success: jasmine.createSpy('success'), error: jasmine.createSpy('error') },
        },
        { provide: RoleContextService, useValue: { isSuperAdmin: () => false, activeHospitalId: 'active-h' } },
        { provide: AuthService, useValue: authMock },
      ],
    });

    const fixture = TestBed.createComponent(LabComponent);
    const component = fixture.componentInstance;
    component.form.hospitalId = 'selected-hospital';
    component.initPatientSearch();

    component.onPatientQueryChange('jo');
    setTimeout(() => {
      expect(patientServiceMock.list).toHaveBeenCalledWith('selected-hospital', 'jo');
      done();
    }, 260);
  });
});
