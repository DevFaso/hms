import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { DepartmentDetailComponent } from './department-detail';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { roleContextStub } from '../testing/role-context.stub';

/**
 * E9 #63 — the hospital admin's classification control. The endpoint has
 * existed since E8 #51; this is the screen that calls it.
 */
describe('DepartmentDetailComponent — default sensitive category', () => {
  let fixture: ComponentFixture<DepartmentDetailComponent>;
  let component: DepartmentDetailComponent;
  let http: HttpTestingController;
  let toast: jasmine.SpyObj<ToastService>;

  function setup(roles: string[]) {
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'info']);
    TestBed.configureTestingModule({
      imports: [DepartmentDetailComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: new Map([['id', 'd-1']]) } } },
        {
          provide: RoleContextService,
          useValue: roleContextStub({ superAdmin: false, hospitalId: 'h-1', roles }),
        },
        { provide: ToastService, useValue: toast },
      ],
    });
    fixture = TestBed.createComponent(DepartmentDetailComponent);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http
      .expectOne('/departments/d-1')
      .flush({ id: 'd-1', name: 'Psychiatrie', code: 'PSY', active: true });
    http.expectOne('/departments/d-1/with-staff').flush({ staff: [] });
    http
      .expectOne('/departments/d-1/stats')
      .flush({ totalStaff: 0, activeStaff: 0, totalPatients: 0, totalAppointments: 0 });
  }

  afterEach(() => http.verify());

  it('loads the current default for a hospital admin and saves a new one', () => {
    setup(['ROLE_HOSPITAL_ADMIN']);
    http
      .expectOne('/departments/d-1/default-sensitivity')
      .flush({ id: 'd-1', departmentDefault: null });
    expect(component.canClassify).toBeTrue();
    expect(component.defaultSensitivity()).toBeNull();

    component.setDefaultSensitivity('BEHAVIOURAL_HEALTH');
    const put = http.expectOne('/departments/d-1/default-sensitivity');
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toEqual({ category: 'BEHAVIOURAL_HEALTH' });
    put.flush({ id: 'd-1', departmentDefault: 'BEHAVIOURAL_HEALTH' });

    expect(component.defaultSensitivity()).toBe('BEHAVIOURAL_HEALTH');
    expect(toast.success).toHaveBeenCalled();
  });

  it('clears the default with an empty selection and rolls back when the save fails', () => {
    setup(['ROLE_HOSPITAL_ADMIN']);
    http
      .expectOne('/departments/d-1/default-sensitivity')
      .flush({ id: 'd-1', departmentDefault: 'HIV' });
    expect(component.defaultSensitivity()).toBe('HIV');

    component.setDefaultSensitivity('');
    const put = http.expectOne('/departments/d-1/default-sensitivity');
    expect(put.request.body).toEqual({ category: null });
    put.flush('nope', { status: 500, statusText: 'Server Error' });

    expect(component.defaultSensitivity()).toBe('HIV');
    expect(toast.error).toHaveBeenCalled();
  });

  it('never asks for the default when the viewer cannot classify', () => {
    setup(['ROLE_RECEPTIONIST']);
    expect(component.canClassify).toBeFalse();
    http.expectNone('/departments/d-1/default-sensitivity');
    component.setDefaultSensitivity('HIV');
    http.expectNone('/departments/d-1/default-sensitivity');
  });
});
