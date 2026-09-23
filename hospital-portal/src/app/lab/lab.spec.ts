import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { LabComponent } from './lab';
import { LabService, LabOrderResponse, PerformingLab } from '../services/lab.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { PatientService } from '../services/patient.service';
import { ProfileService } from '../services/profile.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { AuthService } from '../auth/auth.service';
import { PrintLabelService } from '../services/print-label.service';
import { RoleContextStubState, roleContextStub } from '../testing/role-context.stub';

/**
 * Audit gap B1 on the order form and the worklist: a provider can send an
 * order to another hospital's laboratory, the laboratory sees who ordered
 * it, and the ordering hospital sees where it went.
 */
describe('LabComponent — performing laboratory', () => {
  const HOSPITAL_A = 'hosp-a';
  const LAB_B = 'lab-b';

  let fixture: ComponentFixture<LabComponent>;
  let component: LabComponent;
  let labService: jasmine.SpyObj<LabService>;
  let auth: jasmine.SpyObj<AuthService>;
  let scope: RoleContextStubState;

  const labs: PerformingLab[] = [
    { id: LAB_B, name: 'Central Laboratory B', code: 'LABB' },
    { id: 'lab-c', name: 'Regional Laboratory C', code: 'LABC' },
  ];

  function order(overrides: Partial<LabOrderResponse>): LabOrderResponse {
    return {
      id: 'o-1',
      labOrderCode: 'o-1',
      patientId: 'p-1',
      patientFullName: 'Aminata Diallo',
      patientEmail: 'aminata@example.test',
      hospitalId: HOSPITAL_A,
      hospitalName: 'Ordering Hospital A',
      performingHospitalId: null,
      performingHospitalName: null,
      labTestName: 'CBC',
      labTestCode: 'CBC',
      orderDatetime: '2026-09-22T08:00:00',
      status: 'ORDERED',
      clinicalIndication: 'Fatigue',
      medicalNecessityNote: 'Rule out anaemia',
      notes: '',
      primaryDiagnosisCode: 'D64.9',
      additionalDiagnosisCodes: [],
      orderChannel: 'PORTAL',
      createdAt: '2026-09-22T08:00:00',
      updatedAt: '2026-09-22T08:00:00',
      ...overrides,
    };
  }

  async function setup(roles: string[], orders: LabOrderResponse[], hospitalId = HOSPITAL_A) {
    scope = { superAdmin: false, hospitalId, roles };

    labService = jasmine.createSpyObj<LabService>('LabService', [
      'listOrders',
      'listTestDefinitions',
      'listPerformingLabs',
      'createOrder',
      'updateOrder',
      'deleteOrder',
      'listSpecimens',
      'createSpecimen',
      'receiveSpecimen',
      'submitApprovalAction',
    ]);
    labService.listOrders.and.returnValue(of(orders));
    labService.listTestDefinitions.and.returnValue(of([]));
    labService.listPerformingLabs.and.returnValue(of(labs));
    labService.createOrder.and.returnValue(of(orders[0] ?? order({})));
    labService.updateOrder.and.returnValue(of(orders[0] ?? order({})));

    const hospitalService = jasmine.createSpyObj<HospitalService>('HospitalService', [
      'list',
      'getMyHospitalAsResponse',
    ]);
    hospitalService.getMyHospitalAsResponse.and.returnValue(
      of({ id: hospitalId, name: 'Ordering Hospital A' } as unknown as HospitalResponse),
    );
    const patientService = jasmine.createSpyObj<PatientService>('PatientService', ['list']);
    patientService.list.and.returnValue(of([]));
    const profileService = jasmine.createSpyObj<ProfileService>('ProfileService', [
      'getAssignments',
    ]);
    profileService.getAssignments.and.returnValue(of([]));
    const toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'info']);
    const printService = jasmine.createSpyObj<PrintLabelService>('PrintLabelService', [
      'getSpecimenLabelPdf',
      'openForPrint',
    ]);
    auth = jasmine.createSpyObj<AuthService>('AuthService', ['hasAnyRole', 'getUserProfile']);
    auth.hasAnyRole.and.callFake((expected: string[]) => expected.some((r) => roles.includes(r)));
    auth.getUserProfile.and.returnValue({
      staffId: 'staff-1',
    } as unknown as ReturnType<AuthService['getUserProfile']>);

    await TestBed.configureTestingModule({
      imports: [LabComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: LabService, useValue: labService },
        { provide: HospitalService, useValue: hospitalService },
        { provide: PatientService, useValue: patientService },
        { provide: ProfileService, useValue: profileService },
        { provide: ToastService, useValue: toast },
        { provide: PrintLabelService, useValue: printService },
        { provide: AuthService, useValue: auth },
        { provide: RoleContextService, useValue: roleContextStub(scope) },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(LabComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  it('offers a provider the performing laboratories, defaulting to this hospital', async () => {
    await setup(['ROLE_DOCTOR'], []);

    expect(labService.listPerformingLabs).toHaveBeenCalled();
    expect(component.performingLabs()).toEqual(labs);

    component.openCreate();
    fixture.detectChanges();

    const select: HTMLSelectElement = fixture.nativeElement.querySelector('#lab-performingLab');
    expect(select).withContext('performing laboratory select').not.toBeNull();
    const options = Array.from(select.options).map((o) => o.value);
    expect(options).toEqual(['', LAB_B, 'lab-c']);
    expect(select.value).toBe('');
    expect(component.form.performingHospitalId).toBe('');
  });

  it('does not fetch the laboratories for a user who cannot place orders', async () => {
    await setup(['ROLE_LAB_SCIENTIST'], []);

    expect(labService.listPerformingLabs).not.toHaveBeenCalled();
    expect(component.performingLabs()).toEqual([]);
  });

  it('sends the chosen laboratory, and an explicit null for an in-house order', async () => {
    await setup(['ROLE_DOCTOR'], []);

    component.openCreate();
    component.form.performingHospitalId = LAB_B;
    component.submitForm();
    expect(labService.createOrder).toHaveBeenCalledWith(
      jasmine.objectContaining({ performingHospitalId: LAB_B }),
    );

    // Null, never absent: the API reads an absent field as "keep the current
    // laboratory", so an omitted one would strand an outsourced order.
    component.openCreate();
    component.form.performingHospitalId = '';
    component.submitForm();
    const payload = labService.createOrder.calls.mostRecent().args[0];
    expect(payload.performingHospitalId).toBeNull();
    expect('performingHospitalId' in payload).toBeTrue();
  });

  it('brings an outsourced order back in-house from the edit form', async () => {
    const outgoing = order({
      performingHospitalId: LAB_B,
      performingHospitalName: 'Central Laboratory B',
    });
    await setup(['ROLE_DOCTOR'], [outgoing]);

    component.openEdit(outgoing);
    expect(component.form.performingHospitalId).toBe(LAB_B);

    component.form.performingHospitalId = '';
    component.submitForm();

    const payload = labService.updateOrder.calls.mostRecent().args[1];
    expect(payload.performingHospitalId).toBeNull();
  });

  it('tells the laboratory who ordered an incoming external order', async () => {
    const incoming = order({
      hospitalName: 'Ordering Hospital A',
      performingHospitalId: LAB_B,
      performingHospitalName: 'Central Laboratory B',
    });
    await setup(['ROLE_LAB_SCIENTIST'], [incoming], LAB_B);

    expect(component.isIncomingExternal(incoming)).toBeTrue();
    expect(component.isSentOut(incoming)).toBeFalse();
    const hint: HTMLElement = fixture.nativeElement.querySelector(
      '[data-testid="routing-incoming"]',
    );
    expect(hint).withContext('ordered-by hint').not.toBeNull();
    expect(hint.textContent).toContain('Ordering Hospital A');
    expect(fixture.nativeElement.querySelector('[data-testid="routing-outgoing"]')).toBeNull();

    // Each hospital once: the primary line is this laboratory, the hint is
    // who sent the order. Naming the ordering hospital on both lines left
    // the laboratory unnamed and told the reader nothing twice.
    const cell: HTMLElement = fixture.nativeElement.querySelector('.data-row td:nth-child(4)');
    expect(cell.querySelector('.cell-primary')!.textContent).toContain('Central Laboratory B');
    expect(cell.textContent!.match(/Ordering Hospital A/g)!.length).toBe(1);
    expect(component.actingHospitalName(incoming)).toBe('Central Laboratory B');
    expect(component.counterpartHospitalName(incoming)).toBe('Ordering Hospital A');
    expect(component.counterpartLabelKey(incoming)).toBe('LAB.ORDERED_BY');
  });

  it('tells the ordering hospital where an order was sent', async () => {
    const outgoing = order({
      performingHospitalId: LAB_B,
      performingHospitalName: 'Central Laboratory B',
    });
    await setup(['ROLE_DOCTOR'], [outgoing]);

    expect(component.isSentOut(outgoing)).toBeTrue();
    expect(component.isIncomingExternal(outgoing)).toBeFalse();
    const hint: HTMLElement = fixture.nativeElement.querySelector(
      '[data-testid="routing-outgoing"]',
    );
    expect(hint).withContext('sent-to hint').not.toBeNull();
    expect(hint.textContent).toContain('Central Laboratory B');

    // The other side of the same relationship: our hospital on the primary
    // line, the laboratory it went to on the hint, each named once.
    const cell: HTMLElement = fixture.nativeElement.querySelector('.data-row td:nth-child(4)');
    expect(cell.querySelector('.cell-primary')!.textContent).toContain('Ordering Hospital A');
    expect(cell.textContent!.match(/Central Laboratory B/g)!.length).toBe(1);
    expect(component.counterpartLabelKey(outgoing)).toBe('LAB.SENT_TO');

    component.viewOrder(outgoing);
    fixture.detectChanges();
    const detail: HTMLElement = fixture.nativeElement.querySelector(
      '[data-testid="detail-performing-lab"]',
    );
    expect(detail).withContext('detail field').not.toBeNull();
    expect(detail.textContent).toContain('Central Laboratory B');
    // The HOSPITAL field above it names our own hospital, not the lab again.
    const hospitalField = Array.from(
      fixture.nativeElement.querySelectorAll('.detail-field') as NodeListOf<HTMLElement>,
    ).find((f) => f.textContent!.includes('ENCOUNTERS.HOSPITAL'));
    expect(hospitalField!.textContent).toContain('Ordering Hospital A');
    expect(hospitalField!.textContent).not.toContain('Central Laboratory B');
  });

  it('offers the performing laboratory no Edit or Delete on an incoming order', async () => {
    // PUT and DELETE are the ordering hospital's; a button the backend 404s
    // teaches the laboratory to distrust its own worklist.
    const incoming = order({
      performingHospitalId: LAB_B,
      performingHospitalName: 'Central Laboratory B',
    });
    await setup(['ROLE_LAB_SCIENTIST'], [incoming], LAB_B);

    expect(fixture.nativeElement.querySelector('.edit-link')).toBeNull();
    expect(fixture.nativeElement.querySelector('.delete-link')).toBeNull();
    expect(fixture.nativeElement.querySelector('.view-link'))
      .withContext('viewing stays available')
      .not.toBeNull();
  });

  it('keeps Edit and Delete for the hospital that placed the order', async () => {
    const outgoing = order({
      performingHospitalId: LAB_B,
      performingHospitalName: 'Central Laboratory B',
    });
    await setup(['ROLE_DOCTOR'], [outgoing]);

    expect(fixture.nativeElement.querySelector('.edit-link')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.delete-link')).not.toBeNull();
  });

  it('shows no routing hint for an in-house order', async () => {
    await setup(['ROLE_DOCTOR'], [order({})]);

    expect(fixture.nativeElement.querySelector('[data-testid="routing-incoming"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="routing-outgoing"]')).toBeNull();
  });
});

/**
 * The worklist's Pending / Completed tabs against the backend lifecycle.
 *
 * The backend moves an order ORDERED → COLLECTED → RECEIVED on the specimen
 * events and RESULTED → COMPLETED on results (#716). The tabs used to know
 * only PENDING / IN_PROGRESS / ORDERED and COMPLETED / RESULTED, so an order
 * disappeared from both between specimen collection and result entry.
 */
describe('LabComponent worklist tabs', () => {
  let component: LabComponent;

  // Scoped to this block: the sibling suite above has an `order` helper of
  // its own with a different shape.
  function order(status: string): LabOrderResponse {
    return { id: status.toLowerCase(), status } as LabOrderResponse;
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [LabComponent, TranslateModule.forRoot()],
      providers: [provideHttpClient(withXhr()), provideHttpClientTesting(), provideRouter([])],
    });
    // No detectChanges: ngOnInit would fetch; the tabs are pure over orders().
    component = TestBed.createComponent(LabComponent).componentInstance;
  });

  const lifecycle = [
    'ORDERED',
    'PENDING',
    'COLLECTED',
    'RECEIVED',
    'IN_PROGRESS',
    'RESULTED',
    'VERIFIED',
    'COMPLETED',
    'CANCELLED',
  ];

  it('keeps an order on the Pending tab while its specimen is collected and received', () => {
    component.orders.set(lifecycle.map(order));

    component.setTab('pending');

    expect(component.filtered().map((o) => o.status)).toEqual([
      'ORDERED',
      'PENDING',
      'COLLECTED',
      'RECEIVED',
      'IN_PROGRESS',
    ]);
  });

  it('shows resulted, verified and completed orders on the Completed tab', () => {
    component.orders.set(lifecycle.map(order));

    component.setTab('completed');

    expect(component.filtered().map((o) => o.status)).toEqual([
      'RESULTED',
      'VERIFIED',
      'COMPLETED',
    ]);
  });

  it('places every lifecycle state on exactly one tab, cancelled on neither', () => {
    for (const status of lifecycle) {
      const o = order(status);
      const tabs = Number(component.isPending(o)) + Number(component.isCompleted(o));
      expect(tabs).toBe(status === 'CANCELLED' ? 0 : 1, status);
    }
  });

  it('badges COLLECTED and RECEIVED as specimen states and VERIFIED as done', () => {
    expect(component.getStatusClass('RECEIVED')).toBe('status-badge status-collected');
    expect(component.getStatusClass('COLLECTED')).toBe('status-badge status-collected');
    expect(component.getStatusClass('VERIFIED')).toBe('status-badge status-completed');
  });
});
