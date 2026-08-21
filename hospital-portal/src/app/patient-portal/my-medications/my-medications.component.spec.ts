import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { MyMedicationsComponent } from './my-medications.component';
import { PatientPortalService, MedicationSummary } from '../../services/patient-portal.service';
import { ToastService } from '../../core/toast.service';

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

  async function render(m: MedicationSummary) {
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
            getMyRefills: () => of([]),
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

  it('styles approved, denied and held decisions distinctly', async () => {
    await render(med());
    expect(component.refillChipClass('APPROVED')).toContain('refill-chip--ok');
    expect(component.refillChipClass('DENIED')).toContain('refill-chip--danger');
    expect(component.refillChipClass('PAUSED')).toContain('refill-chip--warn');
    expect(component.refillChipClass('DISPENSED')).toBe('refill-chip');
  });
});
