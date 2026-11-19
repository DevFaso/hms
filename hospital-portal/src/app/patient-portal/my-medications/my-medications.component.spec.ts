import { HttpHeaders, HttpResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { MyMedicationsComponent } from './my-medications.component';
import {
  PatientPortalService,
  MedicationRefill,
  MedicationSummary,
} from '../../services/patient-portal.service';
import { ToastService } from '../../core/toast.service';
import { ConfirmService } from '../../shared/confirm-dialog/confirm.service';
import fr from '../../../assets/i18n/fr.json';

describe('MyMedicationsComponent', () => {
  let component: MyMedicationsComponent;
  let fixture: ComponentFixture<MyMedicationsComponent>;

  const mockPortalService = {
    getAllMyMedications: () => of([]),
    getMyPrescriptions: () => of([]),
    getMyRefills: () => of([]),
    requestRefill: () => of({}),
    getMyPrintedPrescription: (_id: string) =>
      of(
        new HttpResponse({
          body: new Blob(['%PDF-1.4']),
          headers: new HttpHeaders({ 'Content-Disposition': 'inline; filename="ordonnance.pdf"' }),
        }),
      ),
  };

  const mockToast = {
    success: jasmine.createSpy('success'),
    error: jasmine.createSpy('error'),
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyMedicationsComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PatientPortalService, useValue: mockPortalService },
        { provide: ToastService, useValue: mockToast },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(MyMedicationsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should show empty state when no medications or prescriptions', () => {
    expect(component.medications().length).toBe(0);
    expect(component.prescriptions().length).toBe(0);
    expect(component.loading()).toBe(false);
  });

  it('D720: lists a completed course under past medications, not active', () => {
    const base = { medicationName: 'X', dosage: '1', frequency: 'od' } as MedicationSummary;
    component.medications.set([
      { ...base, id: 'a', status: 'ACTIVE' },
      { ...base, id: 'c', status: 'COMPLETED' },
      { ...base, id: 'd', status: 'DISCONTINUED' },
    ]);
    const groups = component.medicationGroups();
    expect(groups.map((g) => g.titleKey)).toEqual([
      'PORTAL.MEDICATIONS.ACTIVE',
      'PORTAL.MEDICATIONS.PAST',
    ]);
    expect(groups[0].meds.map((m) => m.id)).toEqual(['a']);
    expect(groups[1].meds.map((m) => m.id)).toEqual(['c', 'd']);
  });

  it('QA D966: a prescription printed for the patient offers its copy, which opens', () => {
    component.prescriptions.set([
      { id: 'rx-p', medicationName: 'Métronidazole', status: 'PRINTED_FOR_PATIENT' } as never,
      { id: 'rx-s', medicationName: 'Fer', status: 'SIGNED' } as never,
    ]);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('[data-testid="rx-printed-copy-rx-s"]')).toBeNull();
    const fetch = spyOn(mockPortalService, 'getMyPrintedPrescription').and.callThrough();
    // QA D1443: saved under the server's readable name, never a tab named after a random id.
    const links: HTMLAnchorElement[] = [];
    const create = document.createElement.bind(document);
    spyOn(document, 'createElement').and.callFake((tag: string) => {
      const made = create(tag);
      if (tag === 'a') {
        spyOn(made as HTMLAnchorElement, 'click');
        links.push(made as HTMLAnchorElement);
      }
      return made;
    });

    (el.querySelector('[data-testid="rx-printed-copy-rx-p"]') as HTMLButtonElement).click();

    expect(fetch).toHaveBeenCalledWith('rx-p');
    expect(links[0].download).toBe('ordonnance.pdf');
  });

  it('QA D1442: the prescriptions show a page and the total, and every one is a click away', () => {
    component.prescriptions.set(
      Array.from({ length: 45 }, (_, i) => ({ id: 'rx' + i, medicationName: 'M' + i }) as never),
    );
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('[data-testid="rx-count"]')?.textContent).toContain('PORTAL_PAGING');
    expect(component.prescriptionRows.shown().length).toBe(20);
    expect(component.prescriptionRows.total()).toBe(45);

    (el.querySelector('[data-testid="rx-more"]') as HTMLButtonElement).click();
    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>(
      '[data-testid="rx-more"]',
    );
    fixture.detectChanges();
    expect(component.prescriptionRows.shown().length).toBe(40);
    (el.querySelector('[data-testid="rx-more"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(component.prescriptionRows.shown().length).toBe(45);
    expect(el.querySelector('[data-testid="rx-more"]')).toBeNull();
  });

  it('should toggle medication expand', () => {
    component.toggleMed('m1');
    expect(component.expandedMedId()).toBe('m1');
    component.toggleMed('m1');
    expect(component.expandedMedId()).toBeNull();
  });

  it('should toggle prescription expand', () => {
    component.toggleRx('r1');
    expect(component.expandedRxId()).toBe('r1');
    component.toggleRx('r1');
    expect(component.expandedRxId()).toBeNull();
  });
});

/**
 * `refillsAllowed` / `refillsRemaining` have been on the prescriptions table
 * since V1 and no consumer ever read them — the portal even shipped
 * REFILLS_REMAINING and REFILLS_COUNT translations with no data behind them.
 * These cover the row that finally shows them, and the request gate that
 * replaced a client-side status check which hid the button on every DISPENSED
 * prescription — exactly the ones a patient needs a refill for.
 */
describe('MyMedicationsComponent — refill visibility', () => {
  let component: MyMedicationsComponent;
  let fixture: ComponentFixture<MyMedicationsComponent>;
  let requestRefill: jasmine.Spy;
  let toast: { success: jasmine.Spy; error: jasmine.Spy };
  let confirmAsk: jasmine.Spy;
  let cancelRefill: jasmine.Spy;

  function med(overrides: Partial<MedicationSummary> = {}): MedicationSummary {
    return {
      id: 'rx-1',
      medicationName: 'Metformin 500mg',
      dosage: '500mg',
      frequency: 'Twice daily',
      prescribedBy: 'Dr. Smith',
      startDate: '2026-05-01',
      status: 'ACTIVE',
      route: 'PO',
      endDate: '',
      indication: '',
      instructions: '',
      refillsAllowed: 3,
      refillsRemaining: 2,
      refillsUsed: 1,
      refillable: true,
      ...overrides,
    };
  }

  async function render(m: MedicationSummary, refills: MedicationRefill[] = []) {
    requestRefill = jasmine.createSpy('requestRefill').and.returnValue(of({ id: 'rf-1' }));
    toast = { success: jasmine.createSpy('success'), error: jasmine.createSpy('error') };
    confirmAsk = jasmine.createSpy('ask').and.resolveTo(true);
    cancelRefill = jasmine
      .createSpy('cancelRefill')
      .and.callFake((id: string) =>
        of({ ...refills.find((r) => r.id === id), status: 'CANCELLED' }),
      );

    await TestBed.configureTestingModule({
      imports: [MyMedicationsComponent, TranslateModule.forRoot()],
      providers: [
        {
          provide: PatientPortalService,
          useValue: {
            getAllMyMedications: () => of([m]),
            getMyPrescriptions: () => of([]),
            getMyRefills: () => of(refills),
            requestRefill,
            cancelRefill,
          },
        },
        { provide: ToastService, useValue: toast },
        { provide: ConfirmService, useValue: { ask: confirmAsk } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(MyMedicationsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    // The refill block lives inside the expanded detail panel.
    component.toggleMed(m.id);
    fixture.detectChanges();
  }

  afterEach(() => TestBed.resetTestingModule());

  it('shows how many refills are left', async () => {
    await render(med({ refillsRemaining: 2 }));
    expect(fixture.nativeElement.querySelector('[data-testid="med-refills-rx-1"]')).not.toBeNull();
  });

  it('offers a refill on a dispensed prescription — the case the old gate hid', async () => {
    await render(med({ status: 'COMPLETED', refillable: true }));
    expect(
      fixture.nativeElement.querySelector('[data-testid="med-refill-btn-rx-1"]'),
    ).not.toBeNull();
  });

  it('refuses a refill once the prescription is no longer refillable', async () => {
    await render(med({ refillable: false }));
    expect(fixture.nativeElement.querySelector('[data-testid="med-refill-btn-rx-1"]')).toBeNull();
    expect(
      fixture.nativeElement.querySelector('[data-testid="med-not-refillable-rx-1"]'),
    ).not.toBeNull();
  });

  it('suppresses a duplicate request while one is awaiting review', async () => {
    await render(med({ refillRequestStatus: 'REQUESTED', refillRequestOpen: true }));
    expect(fixture.nativeElement.querySelector('[data-testid="med-refill-btn-rx-1"]')).toBeNull();
    expect(
      fixture.nativeElement.querySelector('[data-testid="med-refill-pending-rx-1"]'),
    ).not.toBeNull();
  });

  it('treats a held request as still open — a hold is not a decision', async () => {
    await render(med({ refillRequestStatus: 'PAUSED', refillRequestOpen: true }));
    expect(component.canRequestRefill(med({ refillRequestOpen: true }))).toBeFalse();
    expect(fixture.nativeElement.querySelector('[data-testid="med-refill-btn-rx-1"]')).toBeNull();
  });

  it("shows the provider's reason for a hold or denial", async () => {
    await render(
      med({
        refillRequestStatus: 'DENIED',
        refillProviderNotes: 'Discontinued — see clinic',
      }),
    );
    expect(fixture.nativeElement.textContent).toContain('Discontinued — see clinic');
  });

  it('marks the row as pending immediately after a request, without a refetch', async () => {
    await render(med());
    await component.requestRefill(component.medications()[0]);

    expect(requestRefill).toHaveBeenCalledWith({
      prescriptionId: 'rx-1',
      preferredPharmacy: '',
      notes: '',
    });
    expect(component.medications()[0].refillRequestOpen).toBeTrue();
    expect(component.medications()[0].refillRequestStatus).toBe('REQUESTED');
    expect(toast.success).toHaveBeenCalled();
  });

  it('toasts in the patient language, never the raw key', async () => {
    // ToastService renders its message verbatim, so a key passed straight
    // through showed PORTAL.MEDICATIONS.REFILL_REQUESTED to the patient.
    await render(med());
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', {
      PORTAL: {
        MEDICATIONS: {
          REFILL_REQUESTED: 'Demande de renouvellement envoyée',
          REFILL_FAILED: 'Échec de la demande de renouvellement',
        },
      },
    });
    translate.use('fr');

    await component.requestRefill(component.medications()[0]);
    expect(toast.success).toHaveBeenCalledWith('Demande de renouvellement envoyée');

    requestRefill.and.returnValue(throwError(() => ({ status: 500 })));
    await component.requestRefill(component.medications()[0]);
    expect(toast.error).toHaveBeenCalledWith('Échec de la demande de renouvellement');
  });

  it("surfaces the backend's own refusal message rather than a generic failure", async () => {
    await render(med());
    requestRefill.and.returnValue(
      throwError(() => ({
        status: 409,
        error: { message: 'You already have a refill request awaiting review' },
      })),
    );

    await component.requestRefill(component.medications()[0]);

    expect(toast.error).toHaveBeenCalledWith('You already have a refill request awaiting review');
  });

  it('QA D2354: a refill is sent only after the patient confirms it', async () => {
    await render(med());
    confirmAsk.and.resolveTo(false);
    (
      fixture.nativeElement.querySelector('[data-testid="med-refill-btn-rx-1"]') as HTMLElement
    ).click();
    await fixture.whenStable();
    expect(confirmAsk).toHaveBeenCalledWith(
      jasmine.objectContaining({
        titleKey: 'PORTAL_REFILL.CONFIRM_TITLE',
        params: { name: 'Metformin 500mg' },
      }),
    );
    expect(requestRefill).not.toHaveBeenCalled();

    confirmAsk.and.resolveTo(true);
    await component.requestRefill(component.medications()[0]);
    expect(requestRefill).toHaveBeenCalledTimes(1);
  });

  it('QA D2354: a pending request says since when, and can be cancelled after a confirmation', async () => {
    const pending: MedicationRefill = {
      id: 'rf-9',
      prescriptionId: 'rx-1',
      medicationName: 'Metformin 500mg',
      preferredPharmacy: '',
      status: 'REQUESTED',
      requestedAt: '2026-11-14T08:00:00',
      updatedAt: null,
      notes: '',
    };
    await render(med({ refillRequestStatus: 'REQUESTED', refillRequestOpen: true }), [pending]);
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('[data-testid="med-refill-since-rx-1"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="refill-next-rf-9"]')).not.toBeNull();

    confirmAsk.and.resolveTo(false);
    (el.querySelector('[data-testid="med-refill-cancel-rx-1"]') as HTMLElement).click();
    await fixture.whenStable();
    expect(cancelRefill).not.toHaveBeenCalled();

    confirmAsk.and.resolveTo(true);
    (el.querySelector('[data-testid="refill-cancel-rf-9"]') as HTMLElement).click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(cancelRefill).toHaveBeenCalledOnceWith('rf-9');
    expect(component.refills()[0].status).toBe('CANCELLED');
    expect(component.medications()[0].refillRequestOpen).toBeFalse();
    expect(el.querySelector('[data-testid="refill-cancel-rf-9"]')).toBeNull();
    expect(el.querySelector('[data-testid="med-refill-btn-rx-1"]')).not.toBeNull();
    expect(toast.success).toHaveBeenCalled();
  });

  it('QA D2354: a decided request offers no cancel', async () => {
    const decided: MedicationRefill = {
      id: 'rf-8',
      prescriptionId: 'rx-1',
      medicationName: 'Metformin 500mg',
      preferredPharmacy: '',
      status: 'APPROVED',
      requestedAt: '2026-11-14T08:00:00',
      updatedAt: '2026-11-15T08:00:00',
      notes: '',
    };
    await render(med({ refillRequestStatus: 'APPROVED' }), [decided]);
    expect(fixture.nativeElement.querySelector('[data-testid="refill-cancel-rf-8"]')).toBeNull();
  });

  function useFrench(): void {
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', fr);
    translate.use('fr');
    fixture.detectChanges();
  }

  it('QA D813: names a controlled medicine as controlled, not as out of renewals', async () => {
    await render(
      med({ medicationName: 'Morphine', refillable: false, refillBlockedReason: 'CONTROLLED' }),
    );
    useFrench();
    const text = (
      fixture.nativeElement.querySelector('[data-testid="med-not-refillable-rx-1"]') as HTMLElement
    ).textContent!;
    expect(text).toContain(fr.PORTAL.MEDICATIONS.NOT_REFILLABLE_CONTROLLED);
    expect(text).toContain('contrôlé');
    expect(text).not.toContain('plus être renouvelée');
  });

  it('QA D813: an exhausted prescription keeps the no-renewals wording, worded as the API is', async () => {
    await render(med({ refillable: false, refillBlockedReason: 'NOT_REFILLABLE' }));
    useFrench();
    const text = (
      fixture.nativeElement.querySelector('[data-testid="med-not-refillable-rx-1"]') as HTMLElement
    ).textContent!;
    // The same sentence as patient.refill.notRefillable in messages-patient_fr.properties.
    expect(text).toContain(
      'Cette ordonnance ne peut plus être renouvelée. Contactez votre équipe soignante pour une nouvelle ordonnance.',
    );
  });

  for (const status of [
    'REQUESTED',
    'PAUSED',
    'APPROVED',
    'DENIED',
    'DISPENSED',
    'CANCELLED',
  ] as const) {
    it(`QA D816: the row and the history name a ${status} request the same way`, async () => {
      await render(med({ refillRequestStatus: status, refillRequestOpen: false }), [
        {
          id: 'rf-1',
          prescriptionId: 'rx-1',
          medicationName: 'Metformin 500mg',
          preferredPharmacy: '',
          status,
          requestedAt: '2026-10-25T10:00:00',
          updatedAt: null,
          notes: '',
        },
      ]);
      useFrench();
      const host = fixture.nativeElement as HTMLElement;
      const row = host.querySelector('[data-testid="med-refill-status-rx-1"]')!.textContent!.trim();
      const history = host
        .querySelector('[data-testid="refill-history-status-rf-1"]')!
        .textContent!.trim();
      expect(history).toBe(row);
      expect(history).toBe((fr.REFILLS as Record<string, string>)[`STATUS_${status}`]);
    });
  }

  it('styles approved, denied and held decisions distinctly', async () => {
    await render(med());
    expect(component.refillChipClass('APPROVED')).toContain('refill-chip--ok');
    expect(component.refillChipClass('DENIED')).toContain('refill-chip--danger');
    expect(component.refillChipClass('PAUSED')).toContain('refill-chip--warn');
    expect(component.refillChipClass('DISPENSED')).toBe('refill-chip');
  });
});

/** G15 AC-12: a fill waiting at the counter is shown on the row. */
describe('MyMedicationsComponent — ready for collection', () => {
  let fixture: ComponentFixture<MyMedicationsComponent>;

  const base: MedicationSummary = {
    id: 'm-1',
    medicationName: 'Amoxicillin',
    dosage: '500mg',
    frequency: 'BID',
    prescribedBy: 'Dr. A',
    startDate: '2026-10-01',
    status: 'ACTIVE',
    route: 'PO',
    endDate: '',
    indication: '',
    instructions: '',
  };

  async function render(meds: MedicationSummary[], rxs: unknown[] = []): Promise<HTMLElement> {
    await TestBed.configureTestingModule({
      imports: [MyMedicationsComponent, TranslateModule.forRoot()],
      providers: [
        {
          provide: PatientPortalService,
          useValue: {
            getAllMyMedications: () => of(meds),
            getMyPrescriptions: () => of(rxs),
            getMyRefills: () => of([]),
            requestRefill: () => of({}),
          },
        },
        {
          provide: ToastService,
          useValue: { success: jasmine.createSpy('success'), error: jasmine.createSpy('error') },
        },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(MyMedicationsComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('shows the chip on a medication with a fill waiting, and none elsewhere', async () => {
    const el = await render([
      {
        ...base,
        readyForCollectionAt: '2026-10-06T15:30:00',
        readyForCollectionPharmacyName: 'Pharmacie Centrale',
      },
      { ...base, id: 'm-2' },
    ]);

    const chip = el.querySelector('[data-testid="med-ready-m-1"]');
    expect(chip).not.toBeNull();
    expect(chip!.textContent).toContain('PORTAL.MEDICATIONS.READY_AT');
    expect(el.querySelector('[data-testid="med-ready-m-2"]')).toBeNull();
  });

  it('shows the chip on a prescription with a fill waiting', async () => {
    const el = await render(
      [],
      [
        {
          id: 'rx-9',
          medicationName: 'Amoxicillin',
          dosage: '500mg',
          frequency: 'BID',
          duration: '',
          notes: '',
          prescribedBy: '',
          prescribedDate: '2026-10-01',
          status: 'SIGNED',
          readyForCollectionAt: '2026-10-06T15:30:00',
          readyForCollectionPharmacyName: 'Pharmacie Centrale',
        },
      ],
    );

    expect(el.querySelector('[data-testid="rx-ready-rx-9"]')).not.toBeNull();
  });

  describe('QA D1398 / D1399', () => {
    let component: MyMedicationsComponent;
    const base = {
      id: 'x',
      medicationName: 'Paracétamol',
      dosage: '500 mg',
      frequency: 'q6h',
      prescribedBy: '',
      startDate: '',
      status: 'COMPLETED',
      route: '',
      endDate: '',
      indication: '',
      instructions: '',
    } as MedicationSummary;

    async function renderWith(service: object): Promise<HTMLElement> {
      TestBed.resetTestingModule();
      await TestBed.configureTestingModule({
        imports: [MyMedicationsComponent, TranslateModule.forRoot()],
        providers: [
          { provide: PatientPortalService, useValue: service },
          {
            provide: ToastService,
            useValue: { success: jasmine.createSpy('success'), error: jasmine.createSpy('error') },
          },
        ],
      }).compileComponents();
      fixture = TestBed.createComponent(MyMedicationsComponent);
      component = fixture.componentInstance;
      fixture.detectChanges();
      return fixture.nativeElement as HTMLElement;
    }

    const serviceWith = (meds: MedicationSummary[]) => ({
      getAllMyMedications: () => of(meds),
      getMyPrescriptions: () => of([]),
      getMyRefills: () => of([]),
    });

    it('QA D1398: a card says as needed and its least interval; a fixed schedule says nothing', async () => {
      const el = await renderWith(
        serviceWith([
          { ...base, id: 'prn', asNeeded: true, prnMinIntervalHours: 6 },
          { ...base, id: 'prn-no-interval', asNeeded: true },
          { ...base, id: 'fixed' },
        ]),
      );

      expect(el.querySelector('[data-testid="med-prn-prn"]')?.textContent).toContain(
        'PORTAL.MEDICATIONS.AS_NEEDED_INTERVAL',
      );
      expect(el.querySelector('[data-testid="med-prn-prn-no-interval"]')?.textContent).toContain(
        'PORTAL.MEDICATIONS.AS_NEEDED',
      );
      expect(el.querySelector('[data-testid="med-prn-fixed"]')).toBeNull();
    });

    it('QA D1399: every past medicine is one click away, with the total', async () => {
      const rows = Array.from({ length: 45 }, (_, i) => ({ ...base, id: 'p' + i }));
      const el = await renderWith(serviceWith(rows));

      const past = () => el.querySelectorAll('.portal-list-item').length;
      expect(past()).toBe(20);
      expect(component.pastRows.total()).toBe(45);
      expect(el.querySelector('[data-testid="med-past-count"]')).not.toBeNull();

      for (const expected of [40, 45]) {
        (el.querySelector('[data-testid="med-past-more"]') as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(past()).toBe(expected);
      }
      expect(el.querySelector('[data-testid="med-past-more"]')).toBeNull();
    });

    it('QA D1399: a failed read says so, never "no medicines"', async () => {
      const el = await renderWith({
        ...serviceWith([]),
        getAllMyMedications: () => throwError(() => new Error('down')),
      });

      expect(el.querySelector('[data-testid="medications-load-failed"]')).not.toBeNull();
      expect(el.querySelector('.portal-empty')).toBeNull();
    });
  });
});

/**
 * QA D2353 (r63): no printable prescription, and no quantity or duration: a
 * patient sent to an outside pharmacy had nothing to show from the phone.
 */
describe('MyMedicationsComponent — prescription document (QA D2353)', () => {
  let fixture: ComponentFixture<MyMedicationsComponent>;

  const rx = (overrides: Record<string, unknown> = {}) => ({
    id: 'rx-a',
    medicationName: 'Amoxicilline 500 mg',
    dosage: '1 gélule',
    frequency: '3 fois par jour',
    duration: '7 jours',
    notes: '',
    instructions: 'Pendant les repas',
    route: 'Orale',
    quantity: 21,
    quantityUnit: 'gélules',
    prescribedBy: 'Dr Aminata Ouédraogo',
    prescribedDate: '2026-11-14T09:00:00',
    signedAt: '2026-11-14T09:05:00',
    hospitalName: 'CHU Demo',
    patientFullName: 'Seydou Traoré',
    encounterId: 'enc-1',
    status: 'SIGNED',
    ...overrides,
  });

  async function render(prescriptions: unknown[]) {
    await TestBed.configureTestingModule({
      imports: [MyMedicationsComponent, TranslateModule.forRoot()],
      providers: [
        {
          provide: PatientPortalService,
          useValue: {
            getAllMyMedications: () => of([]),
            getMyPrescriptions: () => of(prescriptions),
            getMyRefills: () => of([]),
          },
        },
        { provide: ToastService, useValue: { success: () => {}, error: () => {} } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(MyMedicationsComponent);
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', fr);
    translate.use('fr');
    fixture.detectChanges();
  }

  afterEach(() => TestBed.resetTestingModule());

  const el = (): HTMLElement => fixture.nativeElement;
  const text = (id: string): string =>
    el().querySelector(`[data-testid="${id}"]`)?.textContent?.trim() ?? '';

  it('shows the duration, quantity and instructions in the prescription details', async () => {
    await render([rx()]);
    fixture.componentInstance.toggleRx('rx-a');
    fixture.detectChanges();
    expect(text('rx-duration-rx-a')).toBe('7 jours');
    expect(text('rx-quantity-rx-a')).toContain('21');
    expect(text('rx-quantity-rx-a')).toContain('gélules');
    expect(text('rx-instructions-rx-a')).toBe('Pendant les repas');
  });

  it("opens the visit's prescription as one document, with prescriber, date, medicines and instructions", async () => {
    await render([
      rx(),
      rx({
        id: 'rx-b',
        medicationName: 'Paracétamol 500 mg',
        instructions: '',
        notes: 'Si fièvre',
      }),
      rx({ id: 'rx-c', medicationName: 'Arrêté', status: 'CANCELLED' }),
      rx({ id: 'rx-d', medicationName: 'Autre visite', encounterId: 'enc-2' }),
    ]);
    fixture.componentInstance.toggleRx('rx-a');
    fixture.detectChanges();
    (el().querySelector('[data-testid="rx-document-btn-rx-a"]') as HTMLElement).click();
    fixture.detectChanges();

    const doc = el().querySelector('[data-testid="rx-document"]') as HTMLElement;
    expect(doc).not.toBeNull();
    expect(doc.getAttribute('role')).toBe('dialog');
    expect(text('rx-doc-hospital')).toBe('CHU Demo');
    expect(text('rx-doc-prescriber')).toBe('Dr Aminata Ouédraogo');
    expect(text('rx-doc-patient')).toBe('Seydou Traoré');
    expect(text('rx-doc-date')).toContain('2026');
    expect(text('rx-doc-signed')).toContain('Dr Aminata Ouédraogo');
    expect(el().querySelector('[data-testid="rx-doc-item-rx-a"]')).not.toBeNull();
    expect(el().querySelector('[data-testid="rx-doc-item-rx-b"]')).not.toBeNull();
    expect(el().querySelector('[data-testid="rx-doc-item-rx-c"]')).toBeNull();
    expect(el().querySelector('[data-testid="rx-doc-item-rx-d"]')).toBeNull();
    expect(text('rx-doc-duration-rx-a')).toBe('7 jours');
    expect(text('rx-doc-instructions-rx-a')).toContain('Pendant les repas');
    expect(text('rx-doc-instructions-rx-b')).toContain('Si fièvre');
    expect(doc.textContent).not.toContain('PORTAL_RX_DOC');
  });

  it('prints the document alone, and closes', async () => {
    await render([rx()]);
    fixture.componentInstance.openDocument(fixture.componentInstance.prescriptions()[0]);
    fixture.detectChanges();
    const print = spyOn(window, 'print');
    (el().querySelector('[data-testid="rx-doc-print"]') as HTMLElement).click();
    expect(print).toHaveBeenCalled();
    expect(document.body.classList.contains('print-isolated')).toBeTrue();
    window.dispatchEvent(new Event('afterprint'));
    expect(document.body.classList.contains('print-isolated')).toBeFalse();

    fixture.componentInstance.closeDocument();
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="rx-document"]')).toBeNull();
  });

  it('offers no document for a stopped prescription', async () => {
    await render([rx({ status: 'CANCELLED' })]);
    fixture.componentInstance.toggleRx('rx-a');
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="rx-document-btn-rx-a"]')).toBeNull();
  });
});
