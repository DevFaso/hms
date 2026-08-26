import { ComponentFixture, TestBed } from '@angular/core/testing';
import { WalkInDialogComponent } from './walkin-dialog.component';
import { TranslateModule } from '@ngx-translate/core';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { PatientService } from '../../services/patient.service';
import { StaffService } from '../../services/staff.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { of } from 'rxjs';

describe('WalkInDialogComponent', () => {
  let component: WalkInDialogComponent;
  let fixture: ComponentFixture<WalkInDialogComponent>;

  const mockPatientService = { list: () => of([]) };
  const mockStaffService = { list: () => of([]) };
  const mockRoleCtx = { activeHospitalId: 'h1' };
  const mockToastService = {
    success: jasmine.createSpy('success'),
    error: jasmine.createSpy('error'),
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [WalkInDialogComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: PatientService, useValue: mockPatientService },
        { provide: StaffService, useValue: mockStaffService },
        { provide: RoleContextService, useValue: mockRoleCtx },
        { provide: ToastService, useValue: mockToastService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(WalkInDialogComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should default encounter type to OUTPATIENT', () => {
    expect(component.encounterType()).toBe('OUTPATIENT');
  });

  it('should select patient', () => {
    const patient = { id: 'p1', firstName: 'Jane', lastName: 'Doe', mrn: 'MRN-1' } as any;
    component.selectPatient(patient);
    expect(component.selectedPatient()).toEqual(patient);
    expect(component.patientQuery()).toBe('Jane Doe');
  });

  it('should select staff', () => {
    const staff = { id: 's1', name: 'Dr. Smith', departmentName: 'Cardio' } as any;
    component.selectStaff(staff);
    expect(component.selectedStaff()).toEqual(staff);
    expect(component.staffQuery()).toBe('Dr. Smith');
  });

  it('should emit dismissed', () => {
    spyOn(component.dismissed, 'emit');
    component.dismissed.emit();
    expect(component.dismissed.emit).toHaveBeenCalled();
  });

  // ── The 400 the front desk actually hit ─────────────────────────────
  //
  // EncounterRequestDTO.departmentId is @NotNull and this dialog never
  // collected one, so POST /encounters returned
  // {"departmentId":"must not be null"} every single time — walk-in
  // registration had never worked. None of the tests above noticed because
  // none of them looked at the request body.

  it('sends a departmentId, without which the backend rejects every walk-in', () => {
    const httpMock = TestBed.inject(HttpTestingController);
    httpMock.match('/departments').forEach((r) => r.flush([{ id: 'd1', name: 'Emergency' }]));

    component.selectedPatient.set({ id: 'p1', firstName: 'Karim', lastName: 'Porgo' } as never);
    component.selectedStaff.set({ id: 's1', name: 'Nurse_B' } as never);
    component.departmentId.set('d1');

    component.submit();

    const req = httpMock.expectOne('/encounters');
    expect(req.request.body.departmentId).toBe('d1');
    expect(req.request.body.patientId).toBe('p1');
    req.flush({});
  });

  it('refuses to submit without a department rather than letting the server 400', () => {
    const httpMock = TestBed.inject(HttpTestingController);
    httpMock.match('/departments').forEach((r) => r.flush([]));

    component.selectedPatient.set({ id: 'p1', firstName: 'Karim', lastName: 'Porgo' } as never);
    component.selectedStaff.set({ id: 's1', name: 'Nurse_B' } as never);
    component.departmentId.set('');

    component.submit();

    httpMock.expectNone('/encounters');
    expect(mockToastService.error).toHaveBeenCalled();
  });

  it('pre-fills the department from the chosen provider', () => {
    const httpMock = TestBed.inject(HttpTestingController);
    httpMock.match('/departments').forEach((r) => r.flush([{ id: 'd9', name: 'Maternity' }]));

    component.selectStaff({ id: 's1', name: 'Nurse_B', departmentId: 'd9' } as never);

    expect(component.departmentId()).toBe('d9');
  });

  it('does not overwrite a department the receptionist already chose', () => {
    // Picking a provider afterwards must not silently reroute the patient to
    // a different queue.
    const httpMock = TestBed.inject(HttpTestingController);
    httpMock.match('/departments').forEach((r) => r.flush([]));

    component.departmentId.set('chosen-by-hand');
    component.selectStaff({ id: 's1', name: 'Nurse_B', departmentId: 'd9' } as never);

    expect(component.departmentId()).toBe('chosen-by-hand');
  });

  it('degrades to an empty department list rather than breaking the dialog', () => {
    // A failed /departments load must not leave the receptionist staring at
    // a dialog that cannot be submitted with no explanation — the field
    // renders empty and the required-check gives them the message.
    const httpMock = TestBed.inject(HttpTestingController);
    httpMock
      .match('/departments')
      .forEach((r) => r.flush('nope', { status: 500, statusText: 'Server Error' }));

    expect(component.departments()).toEqual([]);
  });
});
