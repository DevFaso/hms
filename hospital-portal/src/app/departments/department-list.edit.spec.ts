import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { of } from 'rxjs';
import { TranslateModule } from '@ngx-translate/core';
import { DepartmentListComponent } from './department-list';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { roleContextStub } from '../testing/role-context.stub';
import { HospitalService } from '../services/hospital.service';
import { StaffService } from '../services/staff.service';
import { ProfileService } from '../services/profile.service';

/**
 * D1790: the API's DepartmentResponseDTO sends `departmentCode` and only the
 * head's name; the form read `code` and a head e-mail, so it opened with an
 * empty code and no head and the save was refused until the code was retyped.
 */
describe('DepartmentListComponent — edit form (D1790)', () => {
  let fixture: ComponentFixture<DepartmentListComponent>;
  let http: HttpTestingController;

  const apiDept = {
    id: 'd-1',
    hospitalId: 'h-1',
    hospitalName: 'Hopital A',
    name: 'Cardiologie QA',
    departmentCode: 'CARD-R49',
    description: 'Soins du coeur',
    headOfDepartmentName: 'Awa Traore',
    phoneNumber: '+22625000050',
    email: '',
    active: true,
    staffCount: 1,
    bedCount: 0,
    translations: [],
  };
  const staff = [
    { id: 's-1', name: 'Awa Traore', email: 'awa@h.bf', active: true },
    { id: 's-2', name: 'Boukary Sawadogo', email: 'bou@h.bf', active: true },
  ];

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [DepartmentListComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        {
          provide: RoleContextService,
          useValue: roleContextStub({
            superAdmin: false,
            hospitalId: 'h-1',
            roles: ['ROLE_HOSPITAL_ADMIN'],
          }),
        },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['error', 'success']),
        },
        {
          provide: HospitalService,
          useValue: {
            list: () => of([]),
            getById: () => of({ id: 'h-1', name: 'Hopital A' }),
            getMyHospitalAsResponse: () => of({ id: 'h-1', name: 'Hopital A' }),
          },
        },
        { provide: StaffService, useValue: { list: () => of(staff) } },
        { provide: ProfileService, useValue: { getAssignments: () => of([]) } },
      ],
    });
    fixture = TestBed.createComponent(DepartmentListComponent);
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne('/departments').flush({ content: [apiDept] });
    fixture.detectChanges();
  });

  it('opens prefilled from an API-shaped response and saves the same code', () => {
    const cmp = fixture.componentInstance;
    expect(cmp.filtered()[0].departmentCode).toBe('CARD-R49');

    cmp.openEdit(cmp.departments()[0]);
    expect(cmp.form.code).toBe('CARD-R49');
    expect(cmp.form.name).toBe('Cardiologie QA');
    expect(cmp.form.description).toBe('Soins du coeur');
    expect(cmp.form.phoneNumber).toBe('+22625000050');
    expect(cmp.form.headOfDepartmentEmail).toBe('awa@h.bf');

    cmp.submitForm();
    const put = http.expectOne('/departments/d-1');
    expect(put.request.method).toBe('PUT');
    expect(put.request.body.code).toBe('CARD-R49');
    expect(put.request.body.headOfDepartmentEmail).toBe('awa@h.bf');
    put.flush({});
    http.expectOne('/departments').flush({ content: [apiDept] });
  });

  it('shows the department code in the list, searches by it', () => {
    const cmp = fixture.componentInstance;
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('.dept-code')?.textContent,
    ).toContain('CARD-R49');
    cmp.searchTerm = 'card-r49';
    cmp.applyFilter();
    expect(cmp.filtered().length).toBe(1);
  });
});
