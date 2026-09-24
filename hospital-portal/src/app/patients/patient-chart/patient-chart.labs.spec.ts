import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { PatientChartComponent } from './patient-chart.component';
import { PatientService, PatientLabResult } from '../../services/patient.service';
import { LabService, LabOrderResponse } from '../../services/lab.service';
import { ToastService } from '../../core/toast.service';
import { RoleContextService } from '../../core/role-context.service';
import { roleContextStub } from '../../testing/role-context.stub';
import { AuthService } from '../../auth/auth.service';

/**
 * B6 — the chart's Labs section. Wave 1 shipped the release ceremony; until
 * this section existed, `GET /patients/{id}/lab-results` had no caller on the
 * web, so a released result reached nobody.
 */
describe('PatientChartComponent — labs section', () => {
  let fixture: ComponentFixture<PatientChartComponent>;
  let component: PatientChartComponent;
  let patientService: jasmine.SpyObj<PatientService>;
  let labService: jasmine.SpyObj<LabService>;

  function released(overrides: Partial<PatientLabResult> = {}): PatientLabResult {
    return {
      id: 'r-1',
      testName: 'Hémoglobine',
      testCode: 'HGB',
      value: '9.2',
      unit: 'g/dL',
      referenceRange: '12 - 16 g/dL',
      status: 'ABNORMAL_LOW',
      released: true,
      resultedAt: '2026-09-20T09:30:00Z',
      orderedBy: 'Dr Sanou',
      performedBy: 'A. Kaboré',
      hospitalId: 'h-1',
      hospitalName: 'CHU Bogodogo',
      ...overrides,
    };
  }

  /** What the STAFF path returns for a row the lab has not released. */
  function unreleased(overrides: Partial<PatientLabResult> = {}): PatientLabResult {
    return {
      id: 'r-2',
      testName: 'Créatinine',
      // The staff endpoint does send the preliminary value and a grading; the
      // section must not render either until release.
      value: '150',
      unit: 'µmol/L',
      status: 'PENDING',
      released: false,
      hospitalId: 'h-1',
      hospitalName: 'CHU Bogodogo',
      ...overrides,
    };
  }

  function order(overrides: Partial<LabOrderResponse> = {}): LabOrderResponse {
    return {
      id: 'o-1',
      labOrderCode: 'LAB-0001',
      patientId: 'p-1',
      patientFullName: 'Awa Traoré',
      patientEmail: '',
      hospitalId: 'h-1',
      hospitalName: 'CHU Bogodogo',
      labTestName: 'Hémoglobine',
      labTestCode: 'HGB',
      orderDatetime: '2026-09-20T08:00:00Z',
      status: 'COMPLETED',
      clinicalIndication: '',
      medicalNecessityNote: '',
      notes: '',
      primaryDiagnosisCode: '',
      additionalDiagnosisCodes: [],
      orderChannel: '',
      createdAt: '2026-09-20T08:00:00Z',
      updatedAt: '2026-09-20T09:30:00Z',
      ...overrides,
    };
  }

  interface SetupOptions {
    roles?: string[];
    results?: PatientLabResult[];
    orders?: LabOrderResponse[];
    resultsFail?: boolean;
    ordersFail?: boolean;
  }

  function setup(options: SetupOptions = {}): void {
    const roles = options.roles ?? ['ROLE_DOCTOR'];

    patientService = jasmine.createSpyObj<PatientService>('PatientService', [
      'getDoctorTimeline',
      'listAllergies',
      'listDiagnoses',
      'listChartUpdates',
      'listLabResults',
    ]);
    patientService.listAllergies.and.returnValue(of([]));
    patientService.listDiagnoses.and.returnValue(of([]));
    patientService.listChartUpdates.and.returnValue(of({ content: [], totalElements: 0 }));
    patientService.listLabResults.and.returnValue(
      options.resultsFail
        ? throwError(() => new Error('403'))
        : of(options.results ?? [released()]),
    );

    labService = jasmine.createSpyObj<LabService>('LabService', ['listOrders']);
    labService.listOrders.and.returnValue(
      options.ordersFail ? throwError(() => new Error('403')) : of(options.orders ?? [order()]),
    );

    TestBed.configureTestingModule({
      imports: [PatientChartComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: PatientService, useValue: patientService },
        { provide: LabService, useValue: labService },
        {
          provide: RoleContextService,
          useValue: roleContextStub({ superAdmin: false, hospitalId: 'h-1', roles }),
        },
        {
          provide: AuthService,
          useValue: {
            isAuthenticated: () => true,
            getRoles: () => roles,
            getHospitalId: () => 'h-1',
          },
        },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj<ToastService>('ToastService', [
            'success',
            'error',
            'info',
          ]),
        },
      ],
    });

    fixture = TestBed.createComponent(PatientChartComponent);
    component = fixture.componentInstance;
    component.patientId = 'p-1';
    fixture.detectChanges();
  }

  function openLabs(): void {
    component.setSection('labs');
    fixture.detectChanges();
  }

  function tabLabels(): string[] {
    return Array.from(fixture.nativeElement.querySelectorAll('.chart-tab') as NodeListOf<Element>)
      .map((el) => el.textContent?.trim() ?? '')
      .filter((label) => label.length > 0);
  }

  /* ── The gate ── */

  it('shows the Labs tab for a doctor and reads both lab endpoints', () => {
    setup({ roles: ['ROLE_DOCTOR'] });

    expect(tabLabels()).toContain('CHART.LABS');

    openLabs();

    expect(patientService.listLabResults).toHaveBeenCalledWith('p-1', {
      hospitalId: 'h-1',
      limit: 25,
    });
    expect(labService.listOrders).toHaveBeenCalledWith({ patientId: 'p-1', size: 25 });
  });

  it('hides the Labs tab from a role neither lab endpoint admits', () => {
    // A physiotherapist reaches the chart (CHART_REVIEW / allergies) but is on
    // neither PatientLabResultController's nor LabOrderController's list.
    setup({ roles: ['ROLE_PHYSIOTHERAPIST'] });

    expect(tabLabels()).not.toContain('CHART.LABS');
    expect(component.canViewLabs()).toBeFalse();

    openLabs();

    expect(patientService.listLabResults).not.toHaveBeenCalled();
    expect(labService.listOrders).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('.labs-heading')).toBeNull();
  });

  it('reads results but never lab orders for a pharmacist', () => {
    // SecurityConfig's GET matcher for /lab-orders omits ROLE_PHARMACIST, so
    // that call is a guaranteed 403 — the section must not make it.
    setup({ roles: ['ROLE_PHARMACIST'] });
    openLabs();

    expect(patientService.listLabResults).toHaveBeenCalled();
    expect(labService.listOrders).not.toHaveBeenCalled();
  });

  /* ── Rendering ── */

  it('renders a released result with its value, unit and reference range', () => {
    setup({ roles: ['ROLE_DOCTOR'], results: [released()] });
    openLabs();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('9.2');
    expect(text).toContain('g/dL');
    expect(text).toContain('12 - 16 g/dL');
    expect(text).toContain('CHART.LAB_STATUS_ABNORMAL_LOW');
  });

  it('renders an unreleased row as pending, with no value and no abnormal colouring', () => {
    setup({ roles: ['ROLE_DOCTOR'], results: [unreleased()] });
    openLabs();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('CHART.LAB_AWAITING_RELEASE');
    expect(text).toContain('CHART.LAB_STATUS_PENDING');
    // The preliminary value the staff endpoint sent is deliberately not drawn.
    expect(text).not.toContain('150');
    expect(fixture.nativeElement.querySelector('.lab-value')).toBeNull();
    expect(fixture.nativeElement.querySelector('.lab-abnormal')).toBeNull();
    expect(fixture.nativeElement.querySelector('.lab-normal')).toBeNull();
    expect(fixture.nativeElement.querySelector('tr.pending-row')).not.toBeNull();
  });

  it('labels a pending row PENDING even when the payload graded it NORMAL', () => {
    setup({ roles: ['ROLE_DOCTOR'], results: [unreleased({ status: 'NORMAL' })] });
    openLabs();

    expect(component.labStatusKey(unreleased({ status: 'NORMAL' }))).toBe(
      'CHART.LAB_STATUS_PENDING',
    );
    expect(component.labStatusClass(unreleased({ status: 'NORMAL' }))).toBe(
      'lab-badge lab-pending',
    );
  });

  it('renders the order status through the labOrderStatus enum vocabulary', () => {
    setup({ roles: ['ROLE_DOCTOR'], orders: [order({ status: 'RESULTED' })] });
    openLabs();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('LAB-0001');
    expect(text).toContain('Resulted');
  });

  it('says the orders block is scoped to this hospital, because the results block is not', () => {
    setup({ roles: ['ROLE_DOCTOR'] });
    openLabs();

    expect(fixture.nativeElement.textContent).toContain('CHART.LAB_ORDERS_SCOPE_HINT');
  });

  it('says the results block shows every stored row, superseded preliminaries included', () => {
    // The staff path returns BOTH rows of an analyzer's preliminary/final
    // pair, and the DTO carries no supersession marker — so the section says
    // so rather than guessing which row to hide.
    setup({ roles: ['ROLE_DOCTOR'] });
    openLabs();

    expect(fixture.nativeElement.textContent).toContain('CHART.LAB_RESULTS_SCOPE_HINT');
  });

  it('says so when a list came back capped, instead of reading as a full history', () => {
    const page = Array.from({ length: 25 }, (_, i) => released({ id: 'r-' + i }));
    setup({ roles: ['ROLE_DOCTOR'], results: page, orders: [order()] });
    openLabs();

    expect(component.labResultsTruncated()).toBeTrue();
    expect(component.labOrdersTruncated()).toBeFalse();
    expect(fixture.nativeElement.textContent).toContain('CHART.LAB_TRUNCATED');
  });

  it('shows no truncation hint on a short list', () => {
    setup({ roles: ['ROLE_DOCTOR'], results: [released()], orders: [order()] });
    openLabs();

    expect(component.labResultsTruncated()).toBeFalse();
    expect(fixture.nativeElement.textContent).not.toContain('CHART.LAB_TRUNCATED');
  });

  it('keeps the cross-hospital marker on a row that is both foreign and pending', () => {
    // Same specificity, later rule wins: without the combined selector the
    // pending border silently replaces the E8 #50 provenance border.
    setup({
      roles: ['ROLE_DOCTOR'],
      results: [unreleased({ hospitalId: 'h-2', hospitalName: 'CHU Yalgado' })],
    });
    openLabs();

    const row = fixture.nativeElement.querySelector('tr.pending-row');
    expect(row).not.toBeNull();
    expect(row.classList.contains('foreign-row')).toBeTrue();
  });

  /* ── Empty and error states ── */

  it('renders the empty state for both blocks when the patient has no labs', () => {
    setup({ roles: ['ROLE_DOCTOR'], results: [], orders: [] });
    openLabs();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('CHART.NO_LAB_RESULTS');
    expect(text).toContain('CHART.NO_LAB_ORDERS');
    expect(fixture.nativeElement.querySelector('.error-state')).toBeNull();
  });

  it('renders an error state — not an empty list — when the results read fails', () => {
    setup({ roles: ['ROLE_DOCTOR'], resultsFail: true });
    openLabs();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('CHART.LAB_RESULTS_LOAD_ERROR');
    expect(text).not.toContain('CHART.NO_LAB_RESULTS');
    expect(component.labResultsError()).toBeTrue();
    // The other block is unaffected.
    expect(component.labOrdersError()).toBeFalse();
  });

  it('renders an error state when the orders read fails', () => {
    setup({ roles: ['ROLE_DOCTOR'], ordersFail: true });
    openLabs();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('CHART.LAB_ORDERS_LOAD_ERROR');
    expect(text).not.toContain('CHART.NO_LAB_ORDERS');
  });

  it('retries only the block that failed, leaving the other one on screen', () => {
    setup({ roles: ['ROLE_DOCTOR'], resultsFail: true });
    openLabs();
    expect(patientService.listLabResults).toHaveBeenCalledTimes(1);
    expect(labService.listOrders).toHaveBeenCalledTimes(1);

    patientService.listLabResults.and.returnValue(of([released()]));
    component.loadLabResults();
    fixture.detectChanges();

    expect(patientService.listLabResults).toHaveBeenCalledTimes(2);
    // The orders table the clinician was reading is neither re-fetched nor
    // replaced by a spinner.
    expect(labService.listOrders).toHaveBeenCalledTimes(1);
    expect(component.labResultsError()).toBeFalse();
    expect(fixture.nativeElement.textContent).toContain('9.2');
  });

  it('re-reads BOTH blocks when Retry is pressed after the scope moved', () => {
    // Otherwise the retried block renders the new hospital's rows directly
    // above the other block's rows from the old one, under one heading.
    const state = { superAdmin: false, hospitalId: 'h-1' as string | null, roles: ['ROLE_DOCTOR'] };
    patientService = jasmine.createSpyObj<PatientService>('PatientService', [
      'getDoctorTimeline',
      'listAllergies',
      'listDiagnoses',
      'listChartUpdates',
      'listLabResults',
    ]);
    patientService.listAllergies.and.returnValue(of([]));
    patientService.listDiagnoses.and.returnValue(of([]));
    patientService.listChartUpdates.and.returnValue(of({ content: [], totalElements: 0 }));
    patientService.listLabResults.and.returnValue(throwError(() => new Error('500')));
    labService = jasmine.createSpyObj<LabService>('LabService', ['listOrders']);
    labService.listOrders.and.returnValue(of([order()]));

    TestBed.configureTestingModule({
      imports: [PatientChartComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: PatientService, useValue: patientService },
        { provide: LabService, useValue: labService },
        { provide: RoleContextService, useValue: roleContextStub(state) },
        {
          provide: AuthService,
          useValue: {
            isAuthenticated: () => true,
            getRoles: () => state.roles,
            getHospitalId: () => state.hospitalId,
          },
        },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj<ToastService>('ToastService', [
            'success',
            'error',
            'info',
          ]),
        },
      ],
    });
    fixture = TestBed.createComponent(PatientChartComponent);
    component = fixture.componentInstance;
    component.patientId = 'p-1';
    fixture.detectChanges();

    openLabs();
    expect(labService.listOrders).toHaveBeenCalledTimes(1);

    state.hospitalId = 'h-2';
    patientService.listLabResults.and.returnValue(of([released()]));
    component.loadLabResults();
    fixture.detectChanges();

    // Both blocks re-read, under the new scope.
    expect(labService.listOrders).toHaveBeenCalledTimes(2);
    expect(patientService.listLabResults.calls.mostRecent().args[1]).toEqual({
      hospitalId: 'h-2',
      limit: 25,
    });
  });

  it('does not re-read on a second visit to a patient with no labs', () => {
    setup({ roles: ['ROLE_DOCTOR'], results: [], orders: [] });
    openLabs();
    component.setSection('allergies');
    openLabs();

    expect(patientService.listLabResults).toHaveBeenCalledTimes(1);
    expect(labService.listOrders).toHaveBeenCalledTimes(1);
  });

  it('re-reads when the hospital the request is scoped to changes', () => {
    // Both lab reads are scoped, so rows from the previous scope must not
    // survive the change. The key is hospitalId() — the same value the
    // results request sends — not a "have we loaded" boolean.
    const state = { superAdmin: false, hospitalId: 'h-1' as string | null, roles: ['ROLE_DOCTOR'] };
    patientService = jasmine.createSpyObj<PatientService>('PatientService', [
      'getDoctorTimeline',
      'listAllergies',
      'listDiagnoses',
      'listChartUpdates',
      'listLabResults',
    ]);
    patientService.listAllergies.and.returnValue(of([]));
    patientService.listDiagnoses.and.returnValue(of([]));
    patientService.listChartUpdates.and.returnValue(of({ content: [], totalElements: 0 }));
    patientService.listLabResults.and.returnValue(of([released()]));
    labService = jasmine.createSpyObj<LabService>('LabService', ['listOrders']);
    labService.listOrders.and.returnValue(of([order()]));

    TestBed.configureTestingModule({
      imports: [PatientChartComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: PatientService, useValue: patientService },
        { provide: LabService, useValue: labService },
        { provide: RoleContextService, useValue: roleContextStub(state) },
        {
          provide: AuthService,
          useValue: {
            isAuthenticated: () => true,
            getRoles: () => state.roles,
            getHospitalId: () => state.hospitalId,
          },
        },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj<ToastService>('ToastService', [
            'success',
            'error',
            'info',
          ]),
        },
      ],
    });
    fixture = TestBed.createComponent(PatientChartComponent);
    component = fixture.componentInstance;
    component.patientId = 'p-1';
    fixture.detectChanges();

    openLabs();
    expect(patientService.listLabResults).toHaveBeenCalledTimes(1);

    state.hospitalId = 'h-2';
    component.setSection('allergies');
    openLabs();

    expect(patientService.listLabResults).toHaveBeenCalledTimes(2);
    expect(patientService.listLabResults.calls.mostRecent().args[1]).toEqual({
      hospitalId: 'h-2',
      limit: 25,
    });
  });
});
