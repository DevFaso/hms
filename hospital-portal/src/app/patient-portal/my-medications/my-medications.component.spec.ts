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
import fr from '../../../assets/i18n/fr.json';

describe('MyMedicationsComponent', () => {
  let component: MyMedicationsComponent;
  let fixture: ComponentFixture<MyMedicationsComponent>;

  const mockPortalService = {
    getMyMedications: () => of([]),
    getMyPrescriptions: () => of([]),
    getMyRefills: () => of([]),
    requestRefill: () => of({}),
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

    await TestBed.configureTestingModule({
      imports: [MyMedicationsComponent, TranslateModule.forRoot()],
      providers: [
        {
          provide: PatientPortalService,
          useValue: {
            getMyMedications: () => of([m]),
            getMyPrescriptions: () => of([]),
            getMyRefills: () => of(refills),
            requestRefill,
          },
        },
        { provide: ToastService, useValue: toast },
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
    component.requestRefill(component.medications()[0]);

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

    component.requestRefill(component.medications()[0]);
    expect(toast.success).toHaveBeenCalledWith('Demande de renouvellement envoyée');

    requestRefill.and.returnValue(throwError(() => ({ status: 500 })));
    component.requestRefill(component.medications()[0]);
    expect(toast.error).toHaveBeenCalledWith('Échec de la demande de renouvellement');
  });

  it("surfaces the backend's own refusal message rather than a generic failure", async () => {
    await render(med());
    requestRefill.and.returnValue(
      throwError(() => ({
        error: { message: 'You already have a refill request awaiting review' },
      })),
    );

    component.requestRefill(component.medications()[0]);

    expect(toast.error).toHaveBeenCalledWith('You already have a refill request awaiting review');
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
            getMyMedications: () => of(meds),
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
});
