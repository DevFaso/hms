import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { EncounterService, EncounterResponse } from '../services/encounter.service';
import { LabComponent } from './lab';
import {
  LabService,
  LabOrderResponse,
  LabTestDefinition,
  PerformingLab,
} from '../services/lab.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { PatientService, PatientResponse } from '../services/patient.service';
import { ProfileService } from '../services/profile.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { AuthService } from '../auth/auth.service';
import { PrintLabelService } from '../services/print-label.service';
import { RoleContextStub, roleContextStub } from '../testing/role-context.stub';

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
  let scope: RoleContextStub;
  let toast: jasmine.SpyObj<ToastService>;

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
    scope = roleContextStub({ superAdmin: false, hospitalId, roles });

    labService = jasmine.createSpyObj<LabService>('LabService', [
      'listOrders',
      'listTestDefinitions',
      'listPerformingLabs',
      'createOrder',
      'updateOrder',
      'deleteOrder',
      'voidOrder',
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
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'info']);
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
        { provide: RoleContextService, useValue: scope },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(LabComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  it('does not offer a midwife the order form the server refuses her (D106)', async () => {
    await setup(['ROLE_MIDWIFE'], []);

    expect(component.canCreateOrder()).toBeFalse();
    expect(labService.listPerformingLabs).not.toHaveBeenCalled();
  });

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

  it('D48: offers only ACTIVE definitions to order, keeping the one an edited order names', async () => {
    await setup(['ROLE_DOCTOR'], []);
    const def = (
      id: string,
      approvalStatus: LabTestDefinition['approvalStatus'],
      isActive = true,
    ) => ({ id, testCode: id, testName: id, isActive, approvalStatus }) as LabTestDefinition;
    component.labTestDefs.set([
      def('active', 'ACTIVE'),
      def('draft', 'DRAFT'),
      def('approved', 'APPROVED'),
      def('switched-off', 'ACTIVE', false),
      def('retired', 'RETIRED'),
    ]);

    component.form.labTestDefinitionId = '';
    expect(component.orderableTestDefs().map((d) => d.id)).toEqual(['active']);

    component.form.labTestDefinitionId = 'retired';
    expect(component.orderableTestDefs().map((d) => d.id)).toEqual(['active', 'retired']);
  });

  it('QA D177: the specimen form starts from the test definition sample type', async () => {
    await setup(['ROLE_LAB_SCIENTIST'], []);
    labService.listSpecimens.and.returnValue(of([]));
    component.labTestDefs.set([
      {
        id: 'cbc',
        testCode: 'CBC',
        testName: 'CBC',
        sampleType: 'Sang total EDTA',
      } as LabTestDefinition,
    ]);

    component.openSpecimens(order({ labTestCode: 'CBC' }));

    expect(component.specimenForm.specimenType).toBe('Sang total EDTA');
  });

  it('QA D227: the specimens modal names the order by its short code, not the raw UUID', async () => {
    await setup(['ROLE_LAB_SCIENTIST'], []);
    labService.listSpecimens.and.returnValue(of([]));
    const uuid = '3f2a9c1e-7b4d-4e8a-9c21-0d5e6f7a8b9c';

    component.openSpecimens(order({ labOrderCode: uuid }));
    fixture.detectChanges();

    const title =
      (fixture.nativeElement as HTMLElement).querySelector('.modal-header h2')?.textContent ?? '';
    expect(title).toContain('#3F2A9C1E');
    expect(title).not.toContain(uuid);
  });

  it('QA D177: picking a patient lists their encounters instead of asking for an id', async () => {
    await setup(['ROLE_DOCTOR'], []);
    const encounters = TestBed.inject(EncounterService);
    spyOn(encounters, 'list').and.returnValue(
      of([
        { id: 'e-old', encounterDate: '2026-09-01T08:00:00' },
        { id: 'e-new', encounterDate: '2026-10-01T08:00:00' },
      ] as EncounterResponse[]),
    );

    component.selectPatient({ id: 'p-1', firstName: 'A', lastName: 'D' } as PatientResponse);

    expect(encounters.list).toHaveBeenCalledWith(jasmine.objectContaining({ patientId: 'p-1' }));
    expect(component.patientEncounters().map((e) => e.id)).toEqual(['e-new', 'e-old']);
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

  it('keeps Edit for the clinician of the hospital that placed the order', async () => {
    const outgoing = order({
      performingHospitalId: LAB_B,
      performingHospitalName: 'Central Laboratory B',
    });
    await setup(['ROLE_DOCTOR'], [outgoing]);

    expect(fixture.nativeElement.querySelector('.edit-link')).not.toBeNull();
    // QA D527: DELETE /lab-orders/{id} admits ADMIN and SUPER_ADMIN only.
    expect(fixture.nativeElement.querySelector('.delete-link')).toBeNull();
  });

  it('keeps Delete for the super-admin, whom DELETE /lab-orders/{id} admits', async () => {
    await setup(['ROLE_SUPER_ADMIN'], [order({})]);

    expect(fixture.nativeElement.querySelector('.delete-link')).not.toBeNull();
  });

  describe('QA D820: void a COMPLETED order as entered in error', () => {
    const voidButton = (id: string) =>
      fixture.nativeElement.querySelector(
        `[data-testid="lab-void-${id}"]`,
      ) as HTMLButtonElement | null;

    it('the lab director voids the COMPLETED order, with a reason, never a delete', async () => {
      await setup(['ROLE_LAB_DIRECTOR'], [order({ id: 'lab-35', status: 'COMPLETED' })]);
      labService.voidOrder.and.returnValue(of(order({ id: 'lab-35', status: 'ENTERED_IN_ERROR' })));

      voidButton('lab-35')!.click();
      fixture.detectChanges();
      const confirm = fixture.nativeElement.querySelector(
        '[data-testid="lab-void-confirm"]',
      ) as HTMLButtonElement;
      expect(confirm.disabled).withContext('no reason yet').toBeTrue();

      const reason = fixture.nativeElement.querySelector(
        '[data-testid="lab-void-reason"]',
      ) as HTMLTextAreaElement;
      reason.value = '   ';
      reason.dispatchEvent(new Event('input'));
      fixture.detectChanges();
      expect(confirm.disabled).withContext('spaces are no reason').toBeTrue();

      reason.value = '  Wrong patient  ';
      reason.dispatchEvent(new Event('input'));
      fixture.detectChanges();
      expect(confirm.disabled).toBeFalse();
      labService.listOrders.calls.reset();
      confirm.click();
      fixture.detectChanges();

      expect(labService.voidOrder).toHaveBeenCalledOnceWith('lab-35', 'Wrong patient');
      expect(labService.deleteOrder).not.toHaveBeenCalled();
      expect(toast.success).toHaveBeenCalled();
      expect(labService.listOrders).toHaveBeenCalled();
      expect(component.voidingOrder()).toBeNull();
    });

    it('a lab manager and a super-admin are offered it too', async () => {
      await setup(['ROLE_LAB_MANAGER'], [order({ id: 'o-1', status: 'COMPLETED' })]);
      expect(voidButton('o-1')).not.toBeNull();
      TestBed.resetTestingModule();
      await setup(['ROLE_SUPER_ADMIN'], [order({ id: 'o-1', status: 'COMPLETED' })]);
      expect(voidButton('o-1')).not.toBeNull();
    });

    it('the ordering clinician and the bench are not, as the endpoint refuses them', async () => {
      for (const role of [
        'ROLE_DOCTOR',
        'ROLE_NURSE',
        'ROLE_LAB_TECHNICIAN',
        'ROLE_LAB_SCIENTIST',
      ]) {
        TestBed.resetTestingModule();
        await setup([role], [order({ id: 'o-1', status: 'COMPLETED' })]);
        expect(voidButton('o-1')).withContext(role).toBeNull();
      }
    });

    it('a voided order is not voided again, and reads as entered in error', async () => {
      await setup(
        ['ROLE_LAB_DIRECTOR'],
        [order({ id: 'o-v', status: 'ENTERED_IN_ERROR', cancellationReason: 'Wrong patient' })],
      );
      expect(voidButton('o-v')).toBeNull();
      expect(component.getStatusClass('ENTERED_IN_ERROR')).toBe('status-badge status-cancelled');
      component.viewOrder(component.orders()[0]);
      fixture.detectChanges();
      expect(
        (fixture.nativeElement.querySelector('[data-testid="lab-order-reason"]') as HTMLElement)
          .textContent,
      ).toContain('Wrong patient');
    });

    it('a cancelled order is not offered a void', async () => {
      await setup(['ROLE_LAB_DIRECTOR'], [order({ id: 'o-c', status: 'CANCELLED' })]);
      expect(voidButton('o-c')).toBeNull();
    });

    it("the performing laboratory does not void another hospital's order", async () => {
      await setup(
        ['ROLE_LAB_DIRECTOR'],
        [order({ id: 'o-in', status: 'COMPLETED', performingHospitalId: LAB_B })],
        LAB_B,
      );
      expect(voidButton('o-in')).toBeNull();
    });

    it('shows the server refusal as written', async () => {
      await setup(['ROLE_LAB_DIRECTOR'], [order({ id: 'o-1', status: 'COMPLETED' })]);
      labService.voidOrder.and.returnValue(
        throwError(() => ({ error: { message: 'Déjà déclarée saisie par erreur.' } })),
      );
      component.openVoid(component.orders()[0]);
      component.voidReason.set('Wrong test');
      component.executeVoid();
      expect(toast.error).toHaveBeenCalledWith('Déjà déclarée saisie par erreur.');
      expect(component.voidingOrder()).not.toBeNull();
    });
  });

  it('QA D616: the lab technician sees no trash icon on an ORDERED or a COMPLETED order', async () => {
    await setup(
      ['ROLE_LAB_TECHNICIAN'],
      [order({ id: 'o-ordered', status: 'ORDERED' }), order({ id: 'o-done', status: 'COMPLETED' })],
    );

    expect(fixture.nativeElement.querySelectorAll('tbody tr').length)
      .withContext('both orders are rendered')
      .toBeGreaterThanOrEqual(2);
    expect(fixture.nativeElement.querySelector('.delete-link')).toBeNull();
  });

  it('QA D601/D616: the super-admin is offered delete on an ORDERED order but never on a COMPLETED one', async () => {
    await setup(['ROLE_SUPER_ADMIN'], [order({ id: 'o-done', status: 'COMPLETED' })]);
    expect(fixture.nativeElement.querySelector('.delete-link'))
      .withContext('an order with results is part of the chart')
      .toBeNull();

    TestBed.resetTestingModule();
    await setup(['ROLE_SUPER_ADMIN'], [order({ id: 'o-ordered', status: 'ORDERED' })]);
    expect(fixture.nativeElement.querySelector('.delete-link')).not.toBeNull();
  });

  it('QA D616: a lab technician who also holds ROLE_ADMIN, with no role picked, gets the trash (the reported case)', async () => {
    // No role equivalence maps LAB_TECHNICIAN to ADMIN (only physician roles
    // expand, to DOCTOR). The /lab icon reaches a technician only through a
    // second, admin role held alongside, or a build from before #920 gated it.
    await setup(
      ['ROLE_LAB_TECHNICIAN', 'ROLE_ADMIN'],
      [order({ id: 'o-ordered', status: 'ORDERED' })],
    );
    expect(fixture.nativeElement.querySelector('.delete-link')).not.toBeNull();
  });

  it('QA D527: a lab technician is offered no Edit or Delete on her own hospital order', async () => {
    await setup(['ROLE_LAB_TECHNICIAN'], [order({})]);

    expect(fixture.nativeElement.querySelector('.edit-link')).toBeNull();
    expect(fixture.nativeElement.querySelector('.delete-link')).toBeNull();
    expect(fixture.nativeElement.querySelector('.specimen-link'))
      .withContext('the bench keeps its specimen action')
      .not.toBeNull();
  });

  it('QA D645: an order whose test was deleted shows the API label, not a dash', async () => {
    await setup(
      ['ROLE_LAB_TECHNICIAN'],
      [order({ labTestName: 'Examen supprimé', labTestCode: '', labTestDefinitionMissing: true })],
    );

    expect((fixture.nativeElement as HTMLElement).querySelector('tbody')?.textContent).toContain(
      'Examen supprimé',
    );
  });

  it('QA D527: the worklist and the detail panel never show the patient e-mail', async () => {
    const o = order({});
    await setup(['ROLE_LAB_TECHNICIAN'], [o]);
    component.viewOrder(o);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain(
      'aminata@example.test',
    );
  });

  it('labels the worklist by the hospital the chip is pinned to, not the primary one', async () => {
    // A super-admin whose primary hospital is A, scoped to laboratory B: the
    // order is incoming from B's point of view. Reading activeHospitalId
    // instead of the effective id called it outgoing, put hospital A on the
    // primary line and offered Edit and Delete the backend refuses.
    const incoming = order({
      performingHospitalId: LAB_B,
      performingHospitalName: 'Central Laboratory B',
    });
    await setup(['ROLE_SUPER_ADMIN'], [incoming], HOSPITAL_A);
    scope.set({ superAdmin: true });
    TestBed.inject(RoleContextService).scopeToHospital(LAB_B);
    fixture.detectChanges();

    expect(component.isIncomingExternal(incoming)).toBeTrue();
    expect(component.isSentOut(incoming)).toBeFalse();
    expect(component.actingHospitalName(incoming)).toBe('Central Laboratory B');
    expect(component.counterpartLabelKey(incoming)).toBe('LAB.ORDERED_BY');
    expect(fixture.nativeElement.querySelector('[data-testid="routing-incoming"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.edit-link')).toBeNull();
    expect(fixture.nativeElement.querySelector('.delete-link')).toBeNull();
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
    // QA D820
    'ENTERED_IN_ERROR',
  ];

  it('QA D601/D616: never offers delete on an order that carries results', () => {
    for (const st of ['RESULTED', 'VERIFIED', 'COMPLETED']) {
      expect(component.hasResultsStage(order(st)))
        .withContext(st)
        .toBeTrue();
    }
    for (const st of ['ORDERED', 'PENDING', 'COLLECTED', 'CANCELLED']) {
      expect(component.hasResultsStage(order(st)))
        .withContext(st)
        .toBeFalse();
    }
  });

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
      expect(tabs).toBe(status === 'CANCELLED' || status === 'ENTERED_IN_ERROR' ? 0 : 1, status);
    }
  });

  it('badges COLLECTED and RECEIVED as specimen states and VERIFIED as done', () => {
    expect(component.getStatusClass('RECEIVED')).toBe('status-badge status-collected');
    expect(component.getStatusClass('COLLECTED')).toBe('status-badge status-collected');
    expect(component.getStatusClass('VERIFIED')).toBe('status-badge status-completed');
  });
});
