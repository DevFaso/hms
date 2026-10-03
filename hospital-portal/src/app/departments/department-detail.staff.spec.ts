import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { DepartmentDetailComponent } from './department-detail';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { roleContextStub } from '../testing/role-context.stub';

/**
 * The staff tab read `res.staff` with {name, email, active}; the backend's
 * DepartmentWithStaffDTO sends `staffMembers` of StaffMinimalDTO {id,
 * fullName, jobTitle}, so the tab was always empty. Asserted through the DOM
 * in French: the job title is an enum token and must render translated.
 */
describe('DepartmentDetailComponent — staff tab', () => {
  let fixture: ComponentFixture<DepartmentDetailComponent>;
  let http: HttpTestingController;

  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;

  function open(withStaff: object): void {
    TestBed.configureTestingModule({
      imports: [DepartmentDetailComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: new Map([['id', 'd-1']]) } } },
        {
          provide: RoleContextService,
          useValue: roleContextStub({
            superAdmin: false,
            hospitalId: 'h-1',
            roles: ['ROLE_DOCTOR'],
          }),
        },
        { provide: ToastService, useValue: jasmine.createSpyObj('ToastService', ['error']) },
      ],
    });
    const translate = TestBed.inject(TranslateService);
    translate.setFallbackLang('fr');
    translate.use('fr');
    translate.setTranslation('fr', {
      PORTAL: { ENUM: { JOB_TITLE: { NURSE: 'Infirmier(ère)' } } },
      DEPARTMENTS: { NO_STAFF_ASSIGNED: 'Aucun personnel affecté' },
    });
    fixture = TestBed.createComponent(DepartmentDetailComponent);
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne('/departments/d-1').flush({ id: 'd-1', name: 'Pédiatrie', active: true });
    http.expectOne('/departments/d-1/with-staff').flush(withStaff);
    http.expectOne('/departments/d-1/stats').flush({ totalStaff: 1, activeStaff: 1 });
    fixture.componentInstance.setTab('staff');
    fixture.detectChanges();
  }

  afterEach(() => http.verify());

  it('lists staffMembers with their full name and translated job title', () => {
    open({
      departmentId: 'd-1',
      departmentName: 'Pédiatrie',
      staffMembers: [
        { id: 's-1', fullName: 'Awa Traoré', jobTitle: 'NURSE' },
        { id: 's-2', fullName: 'Idrissa Sawadogo', jobTitle: null },
      ],
    });

    const rows = Array.from(host().querySelectorAll('.data-table tbody tr')).map((tr) =>
      Array.from(tr.querySelectorAll('td')).map((td) => td.textContent?.trim()),
    );
    expect(rows).toEqual([
      ['Awa Traoré', 'Infirmier(ère)'],
      ['Idrissa Sawadogo', '—'],
    ]);
  });

  it('shows the empty state when the department has no staff', () => {
    open({ departmentId: 'd-1', staffMembers: [] });
    expect(host().querySelector('.data-table')).toBeNull();
    expect(host().querySelector('.empty-state h3')?.textContent?.trim()).toBe(
      'Aucun personnel affecté',
    );
  });
});
