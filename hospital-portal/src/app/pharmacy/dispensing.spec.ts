import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { DispensingComponent } from './dispensing';
import { PharmacyService } from '../services/pharmacy.service';
import { AuthService } from '../auth/auth.service';
import { ToastService } from '../core/toast.service';
import { OfflineDispenseQueueService } from './offline-dispense-queue.service';
import { BehaviorSubject, of, throwError } from 'rxjs';

describe('DispensingComponent', () => {
  let component: DispensingComponent;
  let fixture: ComponentFixture<DispensingComponent>;
  let pharmacySvc: jasmine.SpyObj<PharmacyService>;
  let authSvc: jasmine.SpyObj<AuthService>;
  let toastSvc: jasmine.SpyObj<ToastService>;

  const mockPharmacies = {
    content: [{ id: 'ph-1', name: 'Main Pharmacy' }],
    totalElements: 1,
    totalPages: 1,
    size: 100,
    number: 0,
  };

  const mockWorkQueue = {
    data: {
      content: [
        {
          id: 'rx-1',
          medicationName: 'Amoxicillin',
          dosage: '500mg',
          quantity: 30,
          status: 'SIGNED',
          patient: { id: 'pat-1', firstName: 'John', lastName: 'Doe' },
          staff: { id: 'staff-1', user: { id: 'user-1', firstName: 'Dr.', lastName: 'Smith' } },
        },
      ],
      totalElements: 1,
      totalPages: 1,
      size: 20,
      number: 0,
    },
  };

  const mockDispenses = {
    data: {
      content: [
        {
          id: 'd-1',
          medicationName: 'Amoxicillin',
          quantityDispensed: 30,
          unit: 'tablets',
          status: 'COMPLETED',
          dispensedByName: 'Pharmacist A',
          dispensedAt: '2025-06-01T10:00:00',
        },
      ],
      totalElements: 1,
      totalPages: 1,
      size: 10,
      number: 0,
    },
  };

  const mockInventory = {
    data: {
      content: [{ id: 'inv-1', medicationName: 'Amoxicillin', quantityOnHand: 100 }],
      totalElements: 1,
      totalPages: 1,
      size: 200,
      number: 0,
    },
  };

  beforeEach(async () => {
    pharmacySvc = jasmine.createSpyObj('PharmacyService', [
      'listPharmacies',
      'getDispenseWorkQueue',
      'listDispensesByPharmacy',
      'listInventoryByPharmacy',
      'createDispense',
      'cancelDispense',
    ]);
    authSvc = jasmine.createSpyObj('AuthService', [], {
      currentProfile: () => ({ id: 'user-1' }),
    });
    toastSvc = jasmine.createSpyObj('ToastService', ['success', 'error']);

    pharmacySvc.listPharmacies.and.returnValue(of(mockPharmacies as any));
    pharmacySvc.getDispenseWorkQueue.and.returnValue(of(mockWorkQueue as any));
    pharmacySvc.listDispensesByPharmacy.and.returnValue(of(mockDispenses as any));
    pharmacySvc.listInventoryByPharmacy.and.returnValue(of(mockInventory as any));

    // Roadmap row 4 / T-68 — substitute the offline queue with a stub so the
    // existing dispensing tests don't open the real IndexedDB. The pending$
    // BehaviorSubject keeps the component's subscription happy with a
    // deterministic value.
    const offlineQueueStub: Pick<
      OfflineDispenseQueueService,
      'pending$' | 'pending' | 'enqueue' | 'replayAll' | 'clear'
    > = {
      pending$: new BehaviorSubject<number>(0).asObservable(),
      pending: 0,
      enqueue: () => Promise.resolve({ id: 'k', request: {} as any, enqueuedAt: 0, attempts: 0 }),
      replayAll: () => Promise.resolve({ succeeded: 0, failed: 0, remaining: 0 }),
      clear: () => Promise.resolve(),
    };

    await TestBed.configureTestingModule({
      imports: [DispensingComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: PharmacyService, useValue: pharmacySvc },
        { provide: AuthService, useValue: authSvc },
        { provide: ToastService, useValue: toastSvc },
        { provide: OfflineDispenseQueueService, useValue: offlineQueueStub },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(DispensingComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should load pharmacies on init', () => {
    expect(pharmacySvc.listPharmacies).toHaveBeenCalled();
    expect(component.pharmacies().length).toBe(1);
  });

  it('should load work queue after pharmacies', () => {
    expect(pharmacySvc.getDispenseWorkQueue).toHaveBeenCalled();
    expect(component.workQueue().length).toBe(1);
  });

  it('should open dispense form when prescription selected', () => {
    const rx = mockWorkQueue.data.content[0];
    component.selectPrescription(rx);

    expect(component.showForm()).toBeTrue();
    expect(component.form.prescriptionId).toBe('rx-1');
    expect(component.form.patientId).toBe('pat-1');
    expect(component.form.medicationName).toBe('Amoxicillin');
  });

  it('should close form', () => {
    component.showForm.set(true);
    component.closeForm();

    expect(component.showForm()).toBeFalse();
    expect(component.selectedPrescription).toBeNull();
  });

  it('should dispense medication', () => {
    const mockResponse = { data: { id: 'd-new', status: 'COMPLETED' } };
    pharmacySvc.createDispense.and.returnValue(of(mockResponse as any));

    component.form = {
      prescriptionId: 'rx-1',
      patientId: 'pat-1',
      pharmacyId: 'ph-1',
      dispensedBy: 'user-1',
      medicationName: 'Amoxicillin',
      quantityRequested: 30,
      quantityDispensed: 30,
    };
    component.submitDispense();

    expect(pharmacySvc.createDispense).toHaveBeenCalledWith(component.form);
    expect(toastSvc.success).toHaveBeenCalledWith('Medication dispensed successfully');
    expect(component.showForm()).toBeFalse();
  });

  it('should show error on dispense failure', () => {
    pharmacySvc.createDispense.and.returnValue(
      throwError(() => ({ error: { message: 'Insufficient stock' } })),
    );

    component.form = {
      prescriptionId: 'rx-1',
      patientId: 'pat-1',
      pharmacyId: 'ph-1',
      dispensedBy: 'user-1',
      medicationName: 'Amoxicillin',
      quantityRequested: 30,
      quantityDispensed: 30,
    };
    component.submitDispense();

    expect(toastSvc.error).toHaveBeenCalledWith('Insufficient stock');
  });

  it('should cancel dispense', () => {
    spyOn(window, 'confirm').and.returnValue(true);
    const mockResponse = { data: { id: 'd-1', status: 'CANCELLED' } };
    pharmacySvc.cancelDispense.and.returnValue(of(mockResponse as any));

    component.cancelDispense('d-1');

    expect(pharmacySvc.cancelDispense).toHaveBeenCalledWith('d-1');
    expect(toastSvc.success).toHaveBeenCalledWith('Dispense cancelled');
  });

  it('should return correct badge class for status', () => {
    expect(component.getStatusClass('COMPLETED')).toBe('badge-success');
    expect(component.getStatusClass('PARTIAL')).toBe('badge-warning');
    expect(component.getStatusClass('CANCELLED')).toBe('badge-danger');
    expect(component.getStatusClass('PENDING')).toBe('badge-info');
  });

  it('should handle pagination', () => {
    component.queueTotalPages = 3;
    component.queuePage = 0;

    component.nextPage();
    expect(component.queuePage).toBe(1);

    component.prevPage();
    expect(component.queuePage).toBe(0);

    component.prevPage();
    expect(component.queuePage).toBe(0); // Should not go below 0
  });
});

/**
 * The pharmacist decides whether to hand medication over. Until the refill
 * column existed, nothing on this screen said whether the prescriber had
 * approved, denied or held the patient's refill request — a patient could
 * arrive asking for a refill their doctor had refused and the counter had no
 * way to know.
 */
describe('DispensingComponent — refill context on the work queue', () => {
  let component: DispensingComponent;
  let fixture: ComponentFixture<DispensingComponent>;
  let pharmacySvc: jasmine.SpyObj<PharmacyService>;

  function queueWith(refill: Record<string, unknown> | undefined) {
    return {
      data: {
        content: [
          {
            id: 'rx-1',
            medicationName: 'Metformin 500mg',
            dosage: '500mg',
            quantity: 30,
            status: 'SIGNED',
            patient: { id: 'pat-1', firstName: 'John', lastName: 'Doe' },
            staff: { id: 'staff-1', user: { id: 'user-1', firstName: 'Dr.', lastName: 'Smith' } },
            refill,
          },
        ],
        totalElements: 1,
        totalPages: 1,
        size: 20,
        number: 0,
      },
    };
  }

  async function render(refill: Record<string, unknown> | undefined) {
    pharmacySvc = jasmine.createSpyObj('PharmacyService', [
      'listPharmacies',
      'getDispenseWorkQueue',
      'listDispensesByPharmacy',
      'listInventoryByPharmacy',
      'createDispense',
      'cancelDispense',
    ]);
    // The component only loads the work queue once a pharmacy is selected,
    // so an empty pharmacy list would leave the queue permanently unrendered.
    pharmacySvc.listPharmacies.and.returnValue(
      of({
        content: [{ id: 'ph-1', name: 'Main Pharmacy' }],
        totalElements: 1,
        totalPages: 1,
        size: 100,
        number: 0,
      }) as never,
    );
    pharmacySvc.getDispenseWorkQueue.and.returnValue(of(queueWith(refill)) as never);
    pharmacySvc.listDispensesByPharmacy.and.returnValue(of({ data: { content: [] } }) as never);
    pharmacySvc.listInventoryByPharmacy.and.returnValue(of({ data: { content: [] } }) as never);

    const offlineQueueStub: Pick<
      OfflineDispenseQueueService,
      'pending$' | 'pending' | 'enqueue' | 'replayAll' | 'clear'
    > = {
      pending$: new BehaviorSubject<number>(0).asObservable(),
      pending: 0,
      enqueue: () => Promise.resolve({ id: 'k', request: {} as never, enqueuedAt: 0, attempts: 0 }),
      replayAll: () => Promise.resolve({ succeeded: 0, failed: 0, remaining: 0 }),
      clear: () => Promise.resolve(),
    };

    await TestBed.configureTestingModule({
      imports: [DispensingComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: PharmacyService, useValue: pharmacySvc },
        {
          provide: AuthService,
          useValue: jasmine.createSpyObj('AuthService', [], {
            currentProfile: () => ({ id: 'user-1' }),
          }),
        },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error']),
        },
        { provide: OfflineDispenseQueueService, useValue: offlineQueueStub },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(DispensingComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  afterEach(() => TestBed.resetTestingModule());

  it('shows the approved decision and flags the patient as here to collect', async () => {
    await render({
      allowed: 3,
      remaining: 1,
      used: 2,
      lastStatus: 'APPROVED',
      awaitingRefillPickup: true,
    });

    const chip = fixture.nativeElement.querySelector('[data-testid="rx-refill-status-rx-1"]');
    expect(chip).not.toBeNull();
    expect(chip.textContent).toContain('APPROVED');
    expect(
      fixture.nativeElement.querySelector('[data-testid="rx-refill-pickup-rx-1"]'),
    ).not.toBeNull();
  });

  it('shows a denial at the counter so medication is not handed over', async () => {
    await render({
      allowed: 3,
      remaining: 1,
      used: 0,
      lastStatus: 'DENIED',
      lastProviderNotes: 'Discontinued — see clinic',
    });

    const chip = fixture.nativeElement.querySelector('[data-testid="rx-refill-status-rx-1"]');
    expect(chip.textContent).toContain('DENIED');
    expect(fixture.nativeElement.textContent).toContain('Discontinued — see clinic');
    expect(fixture.nativeElement.querySelector('[data-testid="rx-refill-pickup-rx-1"]')).toBeNull();
  });

  it('shows a hold, which is not a decision to dispense', async () => {
    await render({ remaining: 2, lastStatus: 'PAUSED', lastProviderNotes: 'Need an A1c first' });

    expect(
      fixture.nativeElement.querySelector('[data-testid="rx-refill-status-rx-1"]').textContent,
    ).toContain('PAUSED');
    expect(fixture.nativeElement.querySelector('[data-testid="rx-refill-pickup-rx-1"]')).toBeNull();
  });

  it('renders a plain first fill when no refill context is attached', async () => {
    await render(undefined);

    expect(fixture.nativeElement.querySelector('[data-testid="rx-refill-status-rx-1"]')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('PHARMACY.REFILL_NONE');
  });

  it('styles a denial and a hold as stop signals, an approval as a go signal', async () => {
    await render(undefined);

    expect(component.refillBadgeClass('APPROVED')).toContain('badge-success');
    expect(component.refillBadgeClass('DENIED')).toContain('badge-danger');
    expect(component.refillBadgeClass('PAUSED')).toContain('badge-warning');
    expect(component.refillBadgeClass('REQUESTED')).toContain('badge-info');
  });
});
