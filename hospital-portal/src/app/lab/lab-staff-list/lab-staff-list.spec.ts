import { TestBed, ComponentFixture } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { LabStaffListComponent } from './lab-staff-list';
import { AuthService } from '../../auth/auth.service';
import { PagedResponse, StaffResponse } from '../../services/staff.service';

function mockStaff(overrides: Partial<StaffResponse> = {}): StaffResponse {
  return {
    id: 'staff-1',
    userId: 'user-1',
    username: 'jdoe',
    name: 'John Doe',
    email: 'jdoe@hospital.test',
    hospitalId: 'h-1',
    hospitalName: 'Test Hospital',
    departmentName: 'Laboratory',
    roleCode: 'ROLE_LAB_TECHNICIAN',
    roleName: 'Lab Technician',
    licenseNumber: 'LIC-001',
    active: true,
    createdAt: '2025-01-01T00:00:00Z',
    ...overrides,
  };
}

const PAGED: PagedResponse<StaffResponse> = {
  content: [
    mockStaff(),
    mockStaff({
      id: 'staff-2',
      name: 'Jane Smith',
      email: 'jsmith@hospital.test',
      roleCode: 'ROLE_LAB_SCIENTIST',
      roleName: 'Lab Scientist',
    }),
    mockStaff({
      id: 'staff-3',
      name: 'Dr. Director',
      email: 'director@hospital.test',
      roleCode: 'ROLE_LAB_DIRECTOR',
      roleName: 'Lab Director',
    }),
  ],
  totalElements: 3,
  totalPages: 1,
  number: 0,
  size: 20,
};

describe('LabStaffListComponent', () => {
  let fixture: ComponentFixture<LabStaffListComponent>;
  let component: LabStaffListComponent;
  let httpMock: HttpTestingController;
  let authSpy: jasmine.SpyObj<AuthService>;

  beforeEach(() => {
    authSpy = jasmine.createSpyObj('AuthService', ['getRoles', 'getHospitalId']);
    authSpy.getRoles.and.returnValue(['ROLE_LAB_DIRECTOR']);
    authSpy.getHospitalId.and.returnValue('h-1');

    TestBed.configureTestingModule({
      imports: [LabStaffListComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: AuthService, useValue: authSpy },
      ],
    });

    fixture = TestBed.createComponent(LabStaffListComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  function flushStaff(data: PagedResponse<StaffResponse> = PAGED): void {
    const req = httpMock.expectOne((r) => r.url.includes('/staff/hospital/h-1/lab'));
    expect(req.request.method).toBe('GET');
    req.flush(data);
  }

  it('should create the component', () => {
    expect(component).toBeTruthy();
    fixture.detectChanges();
    flushStaff();
  });

  it('should load lab staff on init', () => {
    fixture.detectChanges();
    flushStaff();

    expect(component.loading()).toBeFalse();
    expect(component.staff().length).toBe(3);
    expect(component.totalElements()).toBe(3);
  });

  it('should show loading state initially', () => {
    fixture.detectChanges();
    expect(component.loading()).toBeTrue();
    flushStaff();
  });

  it('should handle load error', () => {
    fixture.detectChanges();
    const req = httpMock.expectOne((r) => r.url.includes('/staff/hospital/h-1/lab'));
    req.error(new ProgressEvent('error'));

    expect(component.loading()).toBeFalse();
    expect(component.error()).toBeTruthy();
    expect(component.staff().length).toBe(0);
  });

  it('should set error if no hospital context', () => {
    authSpy.getHospitalId.and.returnValue(null);
    component.loadLabStaff();

    expect(component.error()).toContain('LAB_STAFF.NO_HOSPITAL');
    expect(component.loading()).toBeFalse();
  });

  it('should allow editing for lab director role', () => {
    expect(component.canEditRoles()).toBeTrue();
    fixture.detectChanges();
    flushStaff();
  });

  it('should not allow editing for lab manager role', () => {
    authSpy.getRoles.and.returnValue(['ROLE_LAB_MANAGER']);
    // Force re-compute
    fixture = TestBed.createComponent(LabStaffListComponent);
    component = fixture.componentInstance;

    expect(component.canEditRoles()).toBeFalse();
    fixture.detectChanges();
    const req = httpMock.expectOne((r) => r.url.includes('/staff/hospital/h-1/lab'));
    req.flush(PAGED);
  });

  it('should start inline edit', () => {
    fixture.detectChanges();
    flushStaff();

    const member = component.staff()[0];
    component.startEdit(member);

    expect(component.editingId()).toBe('staff-1');
    expect(component.editRoleCode()).toBe('ROLE_LAB_TECHNICIAN');
  });

  it('should cancel inline edit', () => {
    fixture.detectChanges();
    flushStaff();

    component.startEdit(component.staff()[0]);
    component.cancelEdit();

    expect(component.editingId()).toBeNull();
    expect(component.editRoleCode()).toBe('');
  });

  it('should save role update', () => {
    fixture.detectChanges();
    flushStaff();

    const member = component.staff()[0];
    component.startEdit(member);
    component.editRoleCode.set('ROLE_LAB_SCIENTIST');
    component.saveRole(member);

    const req = httpMock.expectOne('/staff/staff-1/lab-role');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ roleCode: 'ROLE_LAB_SCIENTIST' });

    const updated = mockStaff({
      roleCode: 'ROLE_LAB_SCIENTIST',
      roleName: 'Lab Scientist',
    });
    req.flush(updated);

    const updatedMember = component.staff().find((s) => s.id === 'staff-1');
    expect(updatedMember?.roleCode).toBe('ROLE_LAB_SCIENTIST');
    expect(component.editingId()).toBeNull();
  });

  it('should no-op save when role unchanged', () => {
    fixture.detectChanges();
    flushStaff();

    const member = component.staff()[0];
    component.startEdit(member);
    // Don't change the role code — keep original
    component.saveRole(member);

    // No HTTP request should be made
    expect(component.editingId()).toBeNull();
  });

  it('should handle save error', () => {
    fixture.detectChanges();
    flushStaff();

    const member = component.staff()[0];
    component.startEdit(member);
    component.editRoleCode.set('ROLE_LAB_MANAGER');
    component.saveRole(member);

    const req = httpMock.expectOne('/staff/staff-1/lab-role');
    req.error(new ProgressEvent('error'));

    expect(component.saving()).toBeFalse();
    // Edit should remain open on error
    expect(component.editingId()).toBe('staff-1');
  });

  it('should return correct badge classes', () => {
    expect(component.roleBadgeClass('ROLE_LAB_DIRECTOR')).toBe('badge-director');
    expect(component.roleBadgeClass('ROLE_LAB_MANAGER')).toBe('badge-manager');
    expect(component.roleBadgeClass('ROLE_LAB_SCIENTIST')).toBe('badge-scientist');
    expect(component.roleBadgeClass('ROLE_LAB_TECHNICIAN')).toBe('badge-technician');
    expect(component.roleBadgeClass('ROLE_OTHER')).toBe('');
    fixture.detectChanges();
    flushStaff();
  });

  it('should not allow editing director', () => {
    const director = mockStaff({ roleCode: 'ROLE_LAB_DIRECTOR' });
    expect(component.isEditable(director)).toBeFalse();
    fixture.detectChanges();
    flushStaff();
  });

  it('should allow editing non-director lab staff', () => {
    const tech = mockStaff({ roleCode: 'ROLE_LAB_TECHNICIAN' });
    expect(component.isEditable(tech)).toBeTrue();
    fixture.detectChanges();
    flushStaff();
  });

  it('should refresh on button click', () => {
    fixture.detectChanges();
    flushStaff();

    component.loadLabStaff();
    flushStaff();

    expect(component.staff().length).toBe(3);
  });
});
