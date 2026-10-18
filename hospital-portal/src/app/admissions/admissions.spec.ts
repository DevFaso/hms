import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';
import { AdmissionsComponent } from './admissions';
import { DepartmentLookupService } from '../services/department-lookup.service';
import { RoleContextService } from '../core/role-context.service';
import { roleContextStub } from '../testing/role-context.stub';

describe('AdmissionsComponent', () => {
  let departments: jasmine.SpyObj<DepartmentLookupService>;

  beforeEach(async () => {
    departments = jasmine.createSpyObj('DepartmentLookupService', ['getActiveDepartments']);
    departments.getActiveDepartments.and.returnValue(of([{ id: 'd1', name: 'Médecine' }]));

    await TestBed.configureTestingModule({
      imports: [AdmissionsComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: DepartmentLookupService, useValue: departments },
        {
          provide: RoleContextService,
          useValue: roleContextStub({
            superAdmin: false,
            hospitalId: 'h1',
            roles: ['ROLE_DOCTOR'],
          }),
        },
      ],
    }).compileComponents();
  });

  it('loads the departments for a single-hospital user who never picks a hospital (D153)', () => {
    const component = TestBed.createComponent(AdmissionsComponent).componentInstance;
    component.hospitals.set([{ id: 'h1', name: 'Hôpital A' } as never]);

    component.openCreate();

    expect(departments.getActiveDepartments).toHaveBeenCalledWith('h1');
    expect(component.departments().length).toBe(1);
  });
});
