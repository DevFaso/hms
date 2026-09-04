import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { provideRouter } from '@angular/router';

import { PanelComponent } from './panel';
import { PanelAssignment, PanelPage, PanelService } from '../services/panel.service';
import { StaffService } from '../services/staff.service';
import { PatientService } from '../services/patient.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';

function assignment(overrides: Partial<PanelAssignment> = {}): PanelAssignment {
  return {
    id: 'a1',
    patientId: 'p1',
    patientName: 'Awa Traore',
    providerStaffId: 's1',
    providerName: 'Dr. Diallo',
    panelRole: 'PRIMARY_PROVIDER',
    status: 'ACTIVE',
    assignedOn: '2026-08-01',
    ...overrides,
  };
}

function page(rows: PanelAssignment[]): PanelPage {
  return { content: rows, totalElements: rows.length, totalPages: 1, number: 0, size: 200 };
}

/**
 * Panel management (Tier 2 item 37). The two states worth pinning: a caller
 * with no staff profile gets a hint (their 400 is an expected state, not an
 * outage), and an overview failure renders "unavailable", never an empty
 * hospital.
 */
describe('PanelComponent', () => {
  let fixture: ComponentFixture<PanelComponent>;
  let component: PanelComponent;
  let panelService: jasmine.SpyObj<PanelService>;
  let toast: jasmine.SpyObj<ToastService>;
  let adminRoles: boolean;

  beforeEach(async () => {
    adminRoles = true;
    panelService = jasmine.createSpyObj<PanelService>('PanelService', [
      'myPanel',
      'providerPanel',
      'overview',
      'assign',
      'end',
      'patientAssignments',
    ]);
    panelService.myPanel.and.returnValue(of(page([assignment()])));
    panelService.overview.and.returnValue(of([]));
    panelService.assign.and.returnValue(of(assignment()));
    panelService.end.and.returnValue(of(assignment({ status: 'ENDED' })));

    toast = jasmine.createSpyObj<ToastService>('ToastService', [
      'success',
      'error',
      'info',
      'warning',
    ]);

    await TestBed.configureTestingModule({
      imports: [PanelComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: PanelService, useValue: panelService },
        { provide: StaffService, useValue: { list: () => of([]) } },
        {
          provide: PatientService,
          useValue: { search: () => of([]), lookup: () => of([]), list: () => of([]) },
        },
        {
          provide: RoleContextService,
          useValue: {
            effectiveHospitalIdForRequest: () => 'h1',
            activeHospitalId: 'h1',
            hasAnyActiveRole: () => adminRoles,
          },
        },
        { provide: ToastService, useValue: toast },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(PanelComponent);
    component = fixture.componentInstance;
  });

  it('loads my panel (and, for admins, the overview) on init', () => {
    fixture.detectChanges();
    expect(panelService.myPanel).toHaveBeenCalledWith(0, 200);
    expect(panelService.overview).toHaveBeenCalled();
    expect(component.myPanelRows().length).toBe(1);
  });

  it('a 400 on my-panel renders the no-staff-profile hint, not an error toast', () => {
    panelService.myPanel.and.returnValue(throwError(() => new HttpErrorResponse({ status: 400 })));
    fixture.detectChanges();
    expect(component.noStaffProfile()).toBeTrue();
    expect(toast.error).not.toHaveBeenCalled();
  });

  it('an overview outage renders unavailable — never an empty hospital', () => {
    panelService.overview.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
    fixture.detectChanges();
    expect(component.overviewFailed()).toBeTrue();
    expect(component.overviewRows().length).toBe(0);
  });

  it('empanelment submits patient + staff + role and refreshes the panel', () => {
    fixture.detectChanges();
    component.openAssign();
    component.onPatientSelected({ id: 'p9' } as never);
    component.formStaffId.set('s9');
    component.formRole.set('CHW');
    panelService.myPanel.calls.reset();

    component.submitAssign();

    expect(panelService.assign).toHaveBeenCalledWith('p9', {
      providerStaffId: 's9',
      panelRole: 'CHW',
      assignedOn: undefined,
    });
    expect(panelService.myPanel).toHaveBeenCalled();
    expect(toast.success).toHaveBeenCalled();
  });
});
