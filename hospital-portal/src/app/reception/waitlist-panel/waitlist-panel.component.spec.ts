import { ComponentFixture, TestBed } from '@angular/core/testing';
import { WaitlistPanelComponent } from './waitlist-panel.component';
import { TranslateModule } from '@ngx-translate/core';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { ReceptionService } from '../reception.service';
import { PatientService } from '../../services/patient.service';
import { ReferralService } from '../../services/referral.service';
import { StaffService } from '../../services/staff.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { of } from 'rxjs';

describe('WaitlistPanelComponent', () => {
  let component: WaitlistPanelComponent;
  let fixture: ComponentFixture<WaitlistPanelComponent>;

  const mockReceptionService = {
    getWaitlist: () => of([]),
    addToWaitlist: () => of({}),
    offerWaitlistSlot: () => of({}),
    closeWaitlistEntry: () => of({}),
  };

  const mockPatientService = { list: () => of([]) };
  const mockReferralService = { getDepartmentsByHospital: () => of([]) };
  const mockStaffService = { list: () => of([]) };
  const mockRoleCtx = { activeHospitalId: 'h1' };
  const mockToastService = {
    success: jasmine.createSpy('success'),
    error: jasmine.createSpy('error'),
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [WaitlistPanelComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ReceptionService, useValue: mockReceptionService },
        { provide: PatientService, useValue: mockPatientService },
        { provide: ReferralService, useValue: mockReferralService },
        { provide: StaffService, useValue: mockStaffService },
        { provide: RoleContextService, useValue: mockRoleCtx },
        { provide: ToastService, useValue: mockToastService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(WaitlistPanelComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should load entries on init', () => {
    expect(component.entries()).toEqual([]);
    expect(component.loading()).toBe(false);
  });

  it('should default status filter to WAITING', () => {
    expect(component.statusFilter()).toBe('WAITING');
  });

  it('should open add form', () => {
    component.openAddForm();
    expect(component.showAddForm()).toBe(true);
    expect(component.selectedPatient()).toBeNull();
  });

  it('should select patient', () => {
    const patient = { id: 'p1', firstName: 'John', lastName: 'Doe' } as any;
    component.selectPatient(patient);
    expect(component.selectedPatient()).toEqual(patient);
    expect(component.patientQuery()).toBe('John Doe');
  });
});
