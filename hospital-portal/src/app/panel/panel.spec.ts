import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, Subject, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { provideRouter } from '@angular/router';

import { PanelComponent } from './panel';
import {
  PanelAssignment,
  PanelOverviewRow,
  PanelPage,
  PanelService,
} from '../services/panel.service';
import { StaffService } from '../services/staff.service';
import { PatientService } from '../services/patient.service';
import { HospitalService } from '../services/hospital.service';
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
  let scopedHospitalId: string | null;

  beforeEach(async () => {
    adminRoles = true;
    scopedHospitalId = 'h1';
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

    const hospitalSpy = jasmine.createSpyObj('HospitalService', [
      'list',
      'getMyHospitalAsResponse',
    ]);
    hospitalSpy.list.and.returnValue(of([]));

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
        { provide: HospitalService, useValue: hospitalSpy },
        {
          provide: RoleContextService,
          useValue: {
            effectiveHospitalIdForRequest: () => scopedHospitalId,
            hasHospitalScope: () => scopedHospitalId != null,
            activeHospitalId: 'h1',
            isSuperAdmin: () => false,
            globalView: () => scopedHospitalId === null,
            selectedHospitalId: () => scopedHospitalId,
            permittedHospitalIds: ['h1'],
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
    const overview = new Subject<never[]>();
    panelService.overview.and.returnValue(overview);
    fixture.detectChanges();
    expect(component.overviewLoading()).toBeTrue();
    overview.error(new HttpErrorResponse({ status: 500 }));
    fixture.detectChanges();
    expect(component.overviewLoading()).toBeFalse();
    expect(component.overviewFailed()).toBeTrue();
    expect(component.overviewRows().length).toBe(0);
    expect(fixture.nativeElement.textContent).toContain('PANEL.OVERVIEW_UNAVAILABLE');
    expect(fixture.nativeElement.textContent).not.toContain('PANEL.OVERVIEW_EMPTY');
  });

  it('replaces an overview outage with loading during an action-triggered retry', () => {
    const retry = new Subject<PanelOverviewRow[]>();
    panelService.overview.and.returnValues(
      throwError(() => new HttpErrorResponse({ status: 503 })),
      retry,
    );
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    expect(root.textContent).toContain('PANEL.OVERVIEW_UNAVAILABLE');

    component.openAssign();
    component.onPatientSelected({ id: 'cdx-patient' } as never);
    component.formStaffId.set('s1');
    component.submitAssign();
    fixture.detectChanges();
    expect(root.textContent).toContain('COMMON.LOADING');
    expect(root.textContent).not.toContain('PANEL.OVERVIEW_UNAVAILABLE');
    expect(root.textContent).not.toContain('PANEL.OVERVIEW_EMPTY');

    retry.next([
      { providerStaffId: 's1', providerName: 'Cdx Recovery', panelRole: 'CHW', activeCount: 3 },
    ]);
    retry.complete();
    fixture.detectChanges();
    expect(root.textContent).toContain('Cdx Recovery');
    expect(root.textContent).not.toContain('COMMON.LOADING');
    expect(component.overviewLoading()).toBeFalse();
    expect(component.overviewFailed()).toBeFalse();
  });

  it('keeps both outage messages visually distinct from ordinary hints', () => {
    panelService.myPanel.and.returnValue(throwError(() => new HttpErrorResponse({ status: 503 })));
    panelService.overview.and.returnValue(throwError(() => new HttpErrorResponse({ status: 503 })));
    fixture.detectChanges();
    const errors = (fixture.nativeElement as HTMLElement).querySelectorAll('.hint.error-hint');
    expect(errors.length).toBe(2);
    for (const message of Array.from(errors)) {
      expect(getComputedStyle(message).color).toBe('rgb(180, 83, 9)');
    }
  });

  it('carries no scope chip or hint of its own: the route gate owns them', () => {
    // The route is flagged requiresHospitalScope: the shell shows the one
    // chip and builds this page only with a hospital pinned.
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('app-hospital-scope-chip')).toBeNull();
    expect(el.querySelector('app-hospital-scope-hint')).toBeNull();
  });

  it('the overview drilldown passes the row role so the cohort matches the count', () => {
    panelService.providerPanel.and.returnValue(of(page([assignment({ panelRole: 'CHW' })])));
    fixture.detectChanges();

    component.openDrilldown({
      providerStaffId: 's1',
      providerName: 'Dr. Diallo',
      panelRole: 'CHW',
      activeCount: 3,
    });

    expect(panelService.providerPanel).toHaveBeenCalledWith('s1', 'CHW', 0, 200);
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
  it('distinguishes a personal-panel outage from an empty panel', () => {
    panelService.myPanel.and.returnValue(throwError(() => new HttpErrorResponse({ status: 503 })));
    fixture.detectChanges();
    expect(component.myPanelFailed()).toBeTrue();
    expect(fixture.nativeElement.querySelector('[role="alert"]')).not.toBeNull();
    expect(toast.error).not.toHaveBeenCalled();
    expect(component.noStaffProfile()).toBeFalse();
  });
  it('retries a personal-panel outage and replaces the alert with the loaded panel', () => {
    panelService.myPanel.and.returnValues(
      throwError(() => new HttpErrorResponse({ status: 503 })),
      of(page([assignment({ patientName: 'Cdx Retry' })])),
    );
    fixture.detectChanges();
    const retry = fixture.nativeElement.querySelector('[role="alert"] button') as HTMLButtonElement;
    expect(retry).not.toBeNull();
    retry.click();
    fixture.detectChanges();
    expect(panelService.myPanel).toHaveBeenCalledTimes(2);
    expect(component.myPanelLoading()).toBeFalse();
    expect(component.myPanelFailed()).toBeFalse();
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('table')?.textContent).toContain('Cdx Retry');
  });
  it('filters the provider overview locally while preserving its source rows', () => {
    panelService.overview.and.returnValue(
      of([
        { providerStaffId: 's1', providerName: 'Cdx Example', panelRole: 'CHW', activeCount: 3 },
      ]),
    );
    fixture.detectChanges();
    component.providerSearch.set('missing');
    expect(component.visibleOverviewRows().length).toBe(0);
    expect(component.overviewRows().length).toBe(1);
    expect(component.loadedProviders()).toBe(1);
    expect(panelService.overview).toHaveBeenCalledTimes(1);
  });

  it('retains the populated overview and search input during an action-triggered refresh', () => {
    const rows: PanelOverviewRow[] = [
      { providerStaffId: 's1', providerName: 'Cdx Example', panelRole: 'CHW', activeCount: 3 },
    ];
    const refresh = new Subject<PanelOverviewRow[]>();
    panelService.overview.and.returnValues(of(rows), refresh);
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    const search = root.querySelector('.care-search input') as HTMLInputElement;
    search.value = 'Cdx';
    search.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    const table = root.querySelector('[aria-labelledby="panel-overview-heading"]');
    component.openAssign();
    component.onPatientSelected({ id: 'cdx-patient' } as never);
    component.formStaffId.set('s1');
    component.submitAssign();
    fixture.detectChanges();
    expect(component.overviewLoading()).toBeTrue();
    expect(root.querySelector('.care-search input')).toBe(search);
    expect(root.querySelector('[aria-labelledby="panel-overview-heading"]')).toBe(table);
    expect(table?.getAttribute('aria-busy')).toBe('true');
    expect(table?.parentElement?.querySelector('.hint[role="status"]')).toBeNull();
    expect(search.value).toBe('Cdx');
    refresh.next([{ ...rows[0], activeCount: 4 }]);
    refresh.complete();
    fixture.detectChanges();
    expect(table?.getAttribute('aria-busy')).toBe('false');
    expect(component.overviewLoading()).toBeFalse();
    expect(table?.textContent).toContain('4');
  });

  it('does not describe the admin overview to a personal-panel-only clinician', () => {
    adminRoles = false;
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.page-subtitle')).toBeNull();
    expect(fixture.nativeElement.querySelector('#my-panel-heading')).not.toBeNull();
    expect(panelService.overview).not.toHaveBeenCalled();
  });
});
