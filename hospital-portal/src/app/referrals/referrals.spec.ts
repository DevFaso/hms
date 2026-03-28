import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { ReferralsComponent } from './referrals';
import { RoleContextService } from '../core/role-context.service';
import { AuthService } from '../auth/auth.service';
import { signal } from '@angular/core';

describe('ReferralsComponent', () => {
  let component: ReferralsComponent;
  let fixture: ComponentFixture<ReferralsComponent>;
  let httpMock: HttpTestingController;
  let roleContextStub: jasmine.SpyObj<RoleContextService>;
  let authStub: jasmine.SpyObj<AuthService>;

  const mockHospital = {
    id: 'h1',
    name: 'Main Hospital',
    code: 'MH',
    hospitalType: 'GENERAL',
    cityOrTown: '',
    stateOrRegion: '',
    country: '',
    phoneNumber: '',
    email: '',
    address: '',
    active: true,
    createdAt: '',
    updatedAt: '',
    organizationId: '',
    organizationName: '',
  };

  const mockHospital2 = {
    ...mockHospital,
    id: 'h2',
    name: 'Remote Hospital',
    code: 'RH',
  };

  beforeEach(async () => {
    roleContextStub = jasmine.createSpyObj('RoleContextService', [], {
      isSuperAdmin: signal(false),
    });
    authStub = jasmine.createSpyObj('AuthService', ['getUserProfile']);
    authStub.getUserProfile.and.returnValue({ staffId: 'staff-1' } as any);

    await TestBed.configureTestingModule({
      imports: [ReferralsComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: RoleContextService, useValue: roleContextStub },
        { provide: AuthService, useValue: authStub },
      ],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ReferralsComponent);
    component = fixture.componentInstance;
  });

  afterEach(() => httpMock.verify());

  function flushInitRequests(): void {
    // ngOnInit triggers: load(), loadAssignedHospitals(), loadAllHospitals()
    httpMock.match((req) => req.url.includes('/referrals')).forEach((r) => r.flush([]));
    const myHospReq = httpMock.match((req) => req.url.includes('/hospitals/me'));
    myHospReq.forEach((r) => r.flush(mockHospital));
    const allHospReq = httpMock.match((req) => req.url.includes('/hospitals'));
    allHospReq.forEach((r) => r.flush([mockHospital, mockHospital2]));
    // source departments for the locked hospital
    const deptReqs = httpMock.match((req) => req.url.includes('/departments/active-minimal'));
    deptReqs.forEach((r) => r.flush({ data: [{ id: 'd1', name: 'Emergency' }] }));
  }

  it('should create', () => {
    flushInitRequests();
    expect(component).toBeTruthy();
  });

  it('emptyForm should include new fields with defaults', () => {
    flushInitRequests();
    const form = component.emptyForm();
    expect(form.receivingHospitalId).toBe('');
    expect(form.sourceDepartmentId).toBe('');
    expect(form.targetDepartmentId).toBe('');
    expect(form.referringProviderId).toBe('staff-1');
  });

  it('onReceivingHospitalChange should load target departments', () => {
    flushInitRequests();

    component.onReceivingHospitalChange('h2');

    const deptReq = httpMock.expectOne('/departments/active-minimal/h2');
    deptReq.flush({
      data: [
        { id: 'd2', name: 'Cardiology' },
        { id: 'd3', name: 'Neurology' },
      ],
    });

    expect(component.targetDepartments().length).toBe(2);
    expect(component.form.receivingHospitalId).toBe('h2');
    expect(component.form.targetDepartmentId).toBe('');
  });

  it('onReceivingHospitalChange with empty id should clear target departments', () => {
    flushInitRequests();

    component.targetDepartments.set([{ id: 'd1', name: 'X' }]);
    component.onReceivingHospitalChange('');

    expect(component.targetDepartments().length).toBe(0);
    expect(component.form.targetDepartmentId).toBe('');
  });

  it('loadSourceDepartments should populate source departments', () => {
    flushInitRequests();

    component.loadSourceDepartments('h1');

    const req = httpMock.expectOne('/departments/active-minimal/h1');
    req.flush({
      data: [
        { id: 'd1', name: 'Emergency' },
        { id: 'd4', name: 'ICU' },
      ],
    });

    expect(component.sourceDepartments().length).toBe(2);
  });

  it('loadSourceDepartments with empty id should clear departments', () => {
    flushInitRequests();

    component.sourceDepartments.set([{ id: 'd1', name: 'X' }]);
    component.loadSourceDepartments('');

    expect(component.sourceDepartments().length).toBe(0);
  });

  it('onSourceHospitalChange should reset sourceDepartmentId and reload departments', () => {
    flushInitRequests();

    component.form.sourceDepartmentId = 'd-old';
    component.onSourceHospitalChange('h2');

    expect(component.form.hospitalId).toBe('h2');
    expect(component.form.sourceDepartmentId).toBe('');

    const req = httpMock.expectOne('/departments/active-minimal/h2');
    req.flush({ data: [{ id: 'd5', name: 'Lab' }] });

    expect(component.sourceDepartments().length).toBe(1);
  });

  it('hospitalLocked should be true for non-super-admin', () => {
    flushInitRequests();
    expect(component.hospitalLocked).toBeTrue();
  });
});
