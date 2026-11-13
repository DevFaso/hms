import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { of, Subject } from 'rxjs';
import { TranslateModule } from '@ngx-translate/core';
import { DepartmentListComponent } from './department-list';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { roleContextStub } from '../testing/role-context.stub';
import { HospitalService } from '../services/hospital.service';
import { StaffService, StaffResponse } from '../services/staff.service';
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
    bedCount: 12,
    translations: ['Cardiologie', 'Cardiology'],
    translationEntries: [
      { languageCode: 'fr', name: 'Cardiologie', description: 'Soins du coeur' },
      { languageCode: 'en', name: 'Cardiology', description: 'Heart care' },
      { languageCode: 'pt', name: 'Cardiologia', description: null },
    ],
  };
  const staff = [
    {
      id: 's-1',
      name: 'Awa Traore',
      email: 'awa@h.bf',
      active: true,
      departmentId: 'd-1',
      headOfDepartment: true,
    },
    { id: 's-2', name: 'Boukary Sawadogo', email: 'bou@h.bf', active: true },
  ];

  function setup(superAdmin: boolean): void {
    TestBed.configureTestingModule({
      imports: [DepartmentListComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        {
          provide: RoleContextService,
          useValue: roleContextStub({
            superAdmin,
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
  }

  beforeEach(() => setup(false));

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

  it('does not replace an unavailable head with an active namesake on save', () => {
    const cmp = fixture.componentInstance;
    // The only active namesake heads d-1, not this department.
    cmp.openEdit({ ...apiDept, id: 'd-2' });
    expect(cmp.form.headOfDepartmentEmail).toBe('');
    cmp.submitForm();
    const put = http.expectOne('/departments/d-2');
    expect(put.request.body.headOfDepartmentEmail).toBeUndefined();
    put.flush({});
    http.expectOne('/departments').flush({ content: [apiDept] });
  });

  it('preselects the identified head even when names are duplicated', () => {
    spyOn(TestBed.inject(StaffService), 'list').and.returnValue(
      of([
        ...staff,
        { ...staff[0], id: 's-3', email: 'other@h.bf', headOfDepartment: false },
      ] as StaffResponse[]),
    );
    fixture.componentInstance.openEdit(apiDept);
    expect(fixture.componentInstance.form.headOfDepartmentEmail).toBe('awa@h.bf');
  });

  it('ignores a delayed head lookup from a previously opened department', () => {
    const delayed = new Subject<StaffResponse[]>();
    spyOn(TestBed.inject(StaffService), 'list').and.returnValues(delayed, of([]));
    const cmp = fixture.componentInstance;
    cmp.openEdit(apiDept);
    cmp.openEdit({ ...apiDept, id: 'd-2' });
    delayed.next(staff as StaffResponse[]);
    expect(cmp.form.headOfDepartmentEmail).toBe('');
    expect(cmp.staffForHospital()).toEqual([]);
  });

  it('shows bed capacity and a name per language, and an unchanged save keeps every translation', () => {
    const cmp = fixture.componentInstance;
    cmp.openEdit(cmp.departments()[0]);
    expect(cmp.form.bedCapacity).toBe(12);
    expect(cmp.langNames).toEqual({ fr: 'Cardiologie', en: 'Cardiology', es: '' });

    cmp.submitForm();
    const put = http.expectOne('/departments/d-1');
    expect(put.request.body.bedCapacity).toBe(12);
    expect(put.request.body.translations).toEqual([
      { languageCode: 'pt', name: 'Cardiologia', description: null },
      { languageCode: 'fr', name: 'Cardiologie', description: 'Soins du coeur' },
      { languageCode: 'en', name: 'Cardiology', description: 'Heart care' },
    ]);
    put.flush({});
    http.expectOne('/departments').flush({ content: [apiDept] });
  });

  it('keeps a stored name when its field is blanked, and adds a typed one', () => {
    const cmp = fixture.componentInstance;
    cmp.openEdit(cmp.departments()[0]);
    cmp.langNames['en'] = '  ';
    cmp.langNames['es'] = 'Cardiologia';
    cmp.submitForm();
    const put = http.expectOne('/departments/d-1');
    const sent = put.request.body.translations as { languageCode: string; name: string }[];
    expect(sent.find((t) => t.languageCode === 'en')?.name).toBe('Cardiology');
    expect(sent.find((t) => t.languageCode === 'es')?.name).toBe('Cardiologia');
    put.flush({});
    http.expectOne('/departments').flush({ content: [apiDept] });
  });

  it('shows the hospital read-only while editing, even for a super-admin', () => {
    TestBed.resetTestingModule();
    setup(true);
    const cmp = fixture.componentInstance;
    cmp.showModal.set(true);
    cmp.openEdit(cmp.departments()[0]);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('#dHospital')).toBeNull();
    expect(host.querySelector('.locked-field')?.textContent).toContain('Hopital A');
  });
});
