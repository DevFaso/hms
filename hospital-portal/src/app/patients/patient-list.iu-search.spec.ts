import { signal } from '@angular/core';
import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';
import { PatientListComponent } from './patient-list';
import { PatientService, PatientResponse } from '../services/patient.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { PermissionService } from '../core/permission.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';

/**
 * QA D2521 (r65): on /patients, the 12-digit IU saved at registration found nobody (« 0 »): the
 * list holds the masked IU only. The page now asks the exact IU search and keeps its patients.
 */
describe('PatientListComponent: a search by IU (QA D2521)', () => {
  let patients: jasmine.SpyObj<PatientService>;
  let roles: string[];
  const row = (id: string, lastName: string): PatientResponse => ({
    id,
    firstName: 'Mariam',
    lastName,
    mrn: 'CMT' + id,
    hospitalId: 'h1',
    active: true,
  });

  beforeEach(() => {
    roles = ['ROLE_RECEPTIONIST'];
    patients = jasmine.createSpyObj('PatientService', ['list', 'searchNationalUniqueId']);
    patients.list.and.returnValue(of([row('1', 'Kabore'), row('2', 'Sawadogo')]));
    patients.searchNationalUniqueId.and.returnValue(of([row('2', 'Sawadogo')]));
    const hospitals = jasmine.createSpyObj('HospitalService', ['list', 'getMyHospitalAsResponse']);
    hospitals.getMyHospitalAsResponse.and.returnValue(
      of({ id: 'h1', name: 'Cdx Hospital' } as HospitalResponse),
    );
    TestBed.configureTestingModule({
      imports: [PatientListComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: PatientService, useValue: patients },
        { provide: HospitalService, useValue: hospitals },
        { provide: PermissionService, useValue: { hasPermission: () => true } },
        {
          provide: RoleContextService,
          useValue: {
            isSuperAdmin: signal(false),
            hasAnyActiveRole: () => false,
            hasAnyActiveRoleExactly: (asked: string[]) => asked.some((r) => roles.includes(r)),
          },
        },
        { provide: ToastService, useValue: jasmine.createSpyObj('ToastService', ['error']) },
      ],
    });
  });

  function search(term: string) {
    const fixture = TestBed.createComponent(PatientListComponent);
    fixture.detectChanges();
    fixture.componentInstance.onSearchChange(term);
    tick(300);
    fixture.detectChanges();
    return fixture.componentInstance.filtered().map((p) => p.id);
  }

  it('finds the patient whose IU is typed, with or without spaces', fakeAsync(() => {
    expect(search('2065 3283 4071')).toEqual(['2']);
    expect(patients.searchNationalUniqueId).toHaveBeenCalledWith('206532834071');
  }));

  it('does not ask the IU search for a name, a phone or an MRN', fakeAsync(() => {
    expect(search('Kabore')).toEqual(['1']);
    search('70001002');
    search('CMT1');
    expect(patients.searchNationalUniqueId).not.toHaveBeenCalled();
  }));

  it('does not ask the IU search for a role the server refuses (its 403 would leave the page)', fakeAsync(() => {
    roles = ['ROLE_ADMIN'];
    expect(search('206532834071')).toEqual([]);
    expect(patients.searchNationalUniqueId).not.toHaveBeenCalled();
  }));
});
