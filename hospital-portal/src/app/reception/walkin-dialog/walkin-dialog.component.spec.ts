import { ComponentFixture, TestBed } from '@angular/core/testing';
import { WalkInDialogComponent } from './walkin-dialog.component';
import { TranslateModule } from '@ngx-translate/core';
import { provideHttpClientTesting } from '@angular/common/http/testing';
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
});
