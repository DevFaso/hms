import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { Router } from '@angular/router';
import { DispensingComponent } from './dispensing';
import { PharmacyService } from '../services/pharmacy.service';
import { AuthService } from '../auth/auth.service';
import { ToastService } from '../core/toast.service';
import { OfflineDispenseQueueService } from './offline-dispense-queue.service';
import { By } from '@angular/platform-browser';
import { BehaviorSubject, of, Subject, throwError } from 'rxjs';
import { RoleContextService } from '../core/role-context.service';
import { PrescriptionClarificationComponent } from '../shared/prescription-clarification/prescription-clarification.component';

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

  // Tier 2 item 34 — real stock lots. The picker used to be fed INVENTORY
  // ITEMS, whose ids the backend then failed to find in the stock-lot table,
  // so choosing a lot always 404'd.
  const mockLots = {
    data: {
      content: [
        {
          id: 'lot-fresh',
          inventoryItemId: 'inv-1',
          lotNumber: 'AMX-2291',
          expiryDate: '2099-03-31',
          initialQuantity: 100,
          remainingQuantity: 60,
          barcodeValue: 'LOT-4f2a91c07b3e',
        },
        {
          id: 'lot-expired',
          inventoryItemId: 'inv-1',
          lotNumber: 'AMX-1180',
          expiryDate: '2020-01-31',
          initialQuantity: 100,
          remainingQuantity: 40,
          barcodeValue: 'LOT-aaaaaaaaaaaa',
        },
        {
          id: 'lot-empty',
          inventoryItemId: 'inv-1',
          lotNumber: 'AMX-9000',
          expiryDate: '2099-12-31',
          initialQuantity: 100,
          remainingQuantity: 0,
          barcodeValue: 'LOT-bbbbbbbbbbbb',
        },
        {
          id: 'lot-unlabelled',
          inventoryItemId: 'inv-1',
          lotNumber: 'AMX-7777',
          expiryDate: '2098-06-30',
          initialQuantity: 50,
          remainingQuantity: 50,
        },
      ],
      totalElements: 4,
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
      'listLotsByPharmacy',
      'createDispense',
      'cancelDispense',
      'getDispenseSettings',
      'markReady',
      'handOver',
      'cancelReady',
    ]);
    authSvc = jasmine.createSpyObj('AuthService', [], {
      currentProfile: () => ({ id: 'user-1' }),
    });
    toastSvc = jasmine.createSpyObj('ToastService', ['success', 'error']);

    pharmacySvc.listPharmacies.and.returnValue(of(mockPharmacies as any));
    pharmacySvc.getDispenseWorkQueue.and.returnValue(of(mockWorkQueue as any));
    pharmacySvc.listDispensesByPharmacy.and.returnValue(of(mockDispenses as any));
    pharmacySvc.listInventoryByPharmacy.and.returnValue(of(mockInventory as any));
    pharmacySvc.listLotsByPharmacy.and.returnValue(of(mockLots as any));
    pharmacySvc.getDispenseSettings.and.returnValue(
      of({ data: { readyForCollectionEnabled: true } } as any),
    );

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
        provideHttpClient(withXhr()),
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
    expect(toastSvc.success).toHaveBeenCalledWith('PHARMACY.DISPENSE_SUCCESS');
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
    expect(toastSvc.success).toHaveBeenCalledWith('PHARMACY.DISPENSE_CANCELLED');
  });

  // ── Tier 2 item 34 — the lot picker and the counter-side scan ────────

  it('offers real stock lots, not inventory items', () => {
    // The defect this replaces: the picker listed inventory items and put
    // their ids into stockLotId, so the backend looked each one up in the
    // stock-lot table and 404'd. Selecting a lot could never succeed.
    const ids = component.dispensableLots().map((lot) => lot.id);

    expect(ids).toContain('lot-fresh');
    expect(ids).not.toContain('inv-1');
  });

  it('keeps expired and empty lots out of the picker', () => {
    const ids = component.dispensableLots().map((lot) => lot.id);

    expect(ids).not.toContain('lot-expired');
    expect(ids).not.toContain('lot-empty');
  });

  it('offers the shortest-dated lot first so stock is used before it expires', () => {
    const ids = component.dispensableLots().map((lot) => lot.id);

    // lot-unlabelled expires 2098, lot-fresh 2099.
    expect(ids[0]).toBe('lot-unlabelled');
  });

  it('clears a product scan when the lot changes', () => {
    // The scan was taken against the previous pack; carrying it over would
    // send the server a value that cannot match the new lot.
    component.form.stockLotId = 'lot-fresh';
    component.onLotChange();
    component.form.productScanValue = 'LOT-4f2a91c07b3e';

    component.form.stockLotId = 'lot-unlabelled';
    component.onLotChange();

    expect(component.form.productScanValue).toBe('');
    expect(component.selectedLot()?.id).toBe('lot-unlabelled');
  });

  it('sends the scan values through to the backend', () => {
    pharmacySvc.createDispense.and.returnValue(of({ data: { id: 'd-2' } } as any));
    component.selectPrescription(mockWorkQueue.data.content[0] as any);
    component.form.quantityDispensed = 30;
    component.form.stockLotId = 'lot-fresh';
    component.onLotChange();
    component.form.patientScanValue = 'pat-1';
    component.form.productScanValue = 'LOT-4f2a91c07b3e';

    component.submitDispense();

    const sent = pharmacySvc.createDispense.calls.mostRecent().args[0];
    expect(sent.patientScanValue).toBe('pat-1');
    expect(sent.productScanValue).toBe('LOT-4f2a91c07b3e');
    expect(sent.stockLotId).toBe('lot-fresh');
  });

  it('starts every new dispense with the previous scans cleared', () => {
    // Otherwise the wristband from the last patient at the counter would be
    // submitted against the next one, and the server would refuse a dispense
    // that is actually correct.
    component.form.patientScanValue = 'pat-1';
    component.form.productScanValue = 'LOT-4f2a91c07b3e';

    component.selectPrescription(mockWorkQueue.data.content[0] as any);

    expect(component.form.patientScanValue).toBe('');
    expect(component.form.productScanValue).toBe('');
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
  // ── G15 — ready for collection ───────────────────────────────────────

  const preparedRow = {
    id: 'rx-ready',
    medicationName: 'Amoxicillin',
    quantity: 30,
    status: 'SIGNED',
    patient: { id: 'pat-1', firstName: 'John', lastName: 'Doe' },
    readyForCollection: {
      dispenseId: 'd-ready',
      readyAt: '2026-10-06T09:00:00',
      preparedByName: 'Awa Ouedraogo',
      quantity: 30,
      unit: 'tablets',
    },
  };

  function showQueue(rows: unknown[]): void {
    component.workQueue.set(rows as any);
    fixture.detectChanges();
  }

  function byTestId(id: string): HTMLElement | null {
    return fixture.nativeElement.querySelector(`[data-testid="${id}"]`);
  }

  it('reads the ready-for-collection switch from the server', () => {
    expect(pharmacySvc.getDispenseSettings).toHaveBeenCalled();
    expect(component.readyForCollectionEnabled()).toBeTrue();
  });

  it('shows Mark ready next to Dispense only when the server switch is on', () => {
    component.selectPrescription(mockWorkQueue.data.content[0]);
    fixture.detectChanges();
    expect(byTestId('mark-ready')).not.toBeNull();

    component.readyForCollectionEnabled.set(false);
    fixture.detectChanges();
    expect(byTestId('mark-ready')).toBeNull();
  });

  it('keeps Mark ready hidden when the settings cannot be read', () => {
    pharmacySvc.getDispenseSettings.and.returnValue(throwError(() => new Error('down')));
    const again = TestBed.createComponent(DispensingComponent);
    again.detectChanges();
    expect(again.componentInstance.readyForCollectionEnabled()).toBeFalse();
  });

  it('marks ready through its own endpoint, never sending a wristband scan', () => {
    pharmacySvc.markReady.and.returnValue(of({ data: { id: 'd-new', status: 'PENDING' } } as any));
    component.form = {
      prescriptionId: 'rx-1',
      patientId: 'pat-1',
      pharmacyId: 'ph-1',
      dispensedBy: 'user-1',
      medicationName: 'Amoxicillin',
      quantityRequested: 30,
      quantityDispensed: 30,
      patientScanValue: 'someone',
    };

    component.submitReady();

    expect(pharmacySvc.markReady).toHaveBeenCalledWith(
      jasmine.objectContaining({ prescriptionId: 'rx-1', patientScanValue: '' }),
    );
    expect(pharmacySvc.createDispense).not.toHaveBeenCalled();
    expect(toastSvc.success).toHaveBeenCalledWith('PHARMACY.READY_SUCCESS');
  });

  it('a prepared row offers Hand over and Cancel preparation instead of Dispense and Route', () => {
    showQueue([preparedRow, mockWorkQueue.data.content[0]]);

    expect(byTestId('rx-hand-over-rx-ready')).not.toBeNull();
    expect(byTestId('rx-cancel-ready-rx-ready')).not.toBeNull();
    expect(byTestId('rx-dispense-rx-ready')).toBeNull();
    expect(byTestId('rx-ready-rx-ready')).not.toBeNull();
    // the plain row keeps Dispense and has no hand-over
    expect(byTestId('rx-dispense-rx-1')).not.toBeNull();
    expect(byTestId('rx-hand-over-rx-1')).toBeNull();
    // and no "ask the prescriber" control on the prepared row: the server
    // refuses a question while a fill is waiting (AC-10)
    const rows = fixture.debugElement.queryAll(By.css('tbody tr'));
    const prepared = rows.find((r) => r.query(By.css('[data-testid="rx-ready-rx-ready"]')));
    const plain = rows.find((r) => r.query(By.css('[data-testid="rx-dispense-rx-1"]')));
    expect(prepared?.query(By.css('app-prescription-clarification'))).toBeNull();
    expect(plain?.query(By.css('app-prescription-clarification'))).not.toBeNull();
  });

  it('hands over with the optional wristband scan', () => {
    pharmacySvc.handOver.and.returnValue(
      of({ data: { id: 'd-ready', status: 'COMPLETED' } } as any),
    );
    component.openHandOver(preparedRow as any);
    component.handOverScan = ' pat-1 ';

    component.confirmHandOver();

    expect(pharmacySvc.handOver).toHaveBeenCalledWith('d-ready', { patientScanValue: 'pat-1' });
    expect(toastSvc.success).toHaveBeenCalledWith('PHARMACY.HAND_OVER_SUCCESS');
    expect(component.readyAction()).toBeNull();
  });

  it('hands over without a scan when none was taken', () => {
    pharmacySvc.handOver.and.returnValue(of({ data: {} } as any));
    component.openHandOver(preparedRow as any);

    component.confirmHandOver();

    expect(pharmacySvc.handOver).toHaveBeenCalledWith('d-ready', {});
  });

  it('shows the server refusal when a hand-over fails', () => {
    pharmacySvc.handOver.and.returnValue(
      throwError(() => ({ error: { message: 'This prescription can no longer be handed over.' } })),
    );
    component.openHandOver(preparedRow as any);

    component.confirmHandOver();

    expect(toastSvc.error).toHaveBeenCalledWith('This prescription can no longer be handed over.');
    expect(component.readyAction()).not.toBeNull();
  });

  it('cancels a preparation with the chosen reason', () => {
    pharmacySvc.cancelReady.and.returnValue(of({ data: {} } as any));
    component.openCancelReady(preparedRow as any);
    component.cancelReason = 'STOCK_UNAVAILABLE';

    component.confirmCancelReady();

    expect(pharmacySvc.cancelReady).toHaveBeenCalledWith('d-ready', 'STOCK_UNAVAILABLE');
    expect(toastSvc.success).toHaveBeenCalledWith('PHARMACY.CANCEL_READY_SUCCESS');
  });

  it('offers only the pharmacist reasons, never the system ones', () => {
    expect([...component.readyCancelReasons]).toEqual([
      'STOCK_UNAVAILABLE',
      'PATIENT_DECLINED',
      'NOT_COLLECTED',
      'OTHER',
    ]);
  });

  it('labels READY_UNCOLLECTED with the days the fill has waited', () => {
    const readyAt = new Date(Date.now() - 8 * 24 * 60 * 60 * 1000 - 60_000).toISOString();
    const row = {
      ...preparedRow,
      needsAttention: true,
      attentionReason: 'READY_UNCOLLECTED',
      readyForCollection: { ...preparedRow.readyForCollection, readyAt },
    } as any;

    expect(component.attentionKey(row)).toBe('PHARMACY.ATTENTION.READY_UNCOLLECTED');
    expect(component.attentionParams(row)).toEqual({ days: 8 });
    expect(component.attentionParams(mockWorkQueue.data.content[0] as any)).toEqual({});
  });

  it('a PENDING row in recent dispenses has no plain Cancel and sorts by when it was prepared', () => {
    component.recentDispenses.set([
      {
        id: 'd-old',
        medicationName: 'A',
        quantityDispensed: 1,
        status: 'COMPLETED',
        dispensedAt: '2026-10-01T10:00:00',
        createdAt: '2026-10-01T10:00:00',
      },
      {
        id: 'd-pending',
        medicationName: 'B',
        quantityDispensed: 1,
        status: 'PENDING',
        createdAt: '2026-10-06T10:00:00',
      },
    ] as any);
    fixture.detectChanges();

    const rows = fixture.debugElement.queryAll(By.css('.section-card:last-of-type tbody tr'));
    const pendingRow = rows.find((r) => r.nativeElement.textContent.includes('B'));
    expect(pendingRow?.query(By.css('.btn-action.danger'))).toBeNull();
    // AC-11: a PENDING row reads "Ready for collection", not "Pending"
    expect(pendingRow?.query(By.css('.badge')).nativeElement.textContent.trim()).toBe(
      'Ready for collection',
    );

    pharmacySvc.listDispensesByPharmacy.and.returnValue(
      of({
        data: {
          content: [
            {
              id: 'd-old',
              status: 'COMPLETED',
              dispensedAt: '2026-10-01T10:00:00',
              createdAt: '2026-10-01T10:00:00',
            },
            { id: 'd-pending', status: 'PENDING', createdAt: '2026-10-06T10:00:00' },
          ],
        },
      } as any),
    );
    component.onPharmacyChange();
    expect(component.recentDispenses().map((d) => d.id)).toEqual(['d-pending', 'd-old']);
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
      'listLotsByPharmacy',
      'createDispense',
      'cancelDispense',
      'getDispenseSettings',
    ]);
    pharmacySvc.getDispenseSettings.and.returnValue(
      of({ data: { readyForCollectionEnabled: false } }) as never,
    );
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
    pharmacySvc.listLotsByPharmacy.and.returnValue(of({ data: { content: [] } }) as never);

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
        provideHttpClient(withXhr()),
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

/**
 * Gap G5 — the clarification control on the dispense work queue. The queue
 * row is where the pharmacist decides whether to fill, so it is where the
 * question belongs; the control itself is
 * `<app-prescription-clarification>` and its own spec covers the modal.
 * What matters here is that the row wires it correctly and that the role
 * gate is honoured on the real page.
 */
describe('DispensingComponent — clarification control on the work queue', () => {
  let fixture: ComponentFixture<DispensingComponent>;
  let pharmacySvc: jasmine.SpyObj<PharmacyService>;
  let roleContext: RoleContextService;

  function queueRow(overrides: Record<string, unknown>) {
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
            ...overrides,
          },
        ],
        totalElements: 1,
        totalPages: 1,
        size: 20,
        number: 0,
      },
    };
  }

  async function render(roles: string[], overrides: Record<string, unknown> = {}) {
    pharmacySvc = jasmine.createSpyObj('PharmacyService', [
      'listPharmacies',
      'getDispenseWorkQueue',
      'listDispensesByPharmacy',
      'listInventoryByPharmacy',
      'listLotsByPharmacy',
      'createDispense',
      'cancelDispense',
      'getDispenseSettings',
    ]);
    pharmacySvc.getDispenseSettings.and.returnValue(
      of({ data: { readyForCollectionEnabled: false } }) as never,
    );
    pharmacySvc.listPharmacies.and.returnValue(
      of({
        content: [{ id: 'ph-1', name: 'Main Pharmacy' }],
        totalElements: 1,
        totalPages: 1,
        size: 100,
        number: 0,
      }) as never,
    );
    pharmacySvc.getDispenseWorkQueue.and.returnValue(of(queueRow(overrides)) as never);
    pharmacySvc.listDispensesByPharmacy.and.returnValue(of({ data: { content: [] } }) as never);
    pharmacySvc.listInventoryByPharmacy.and.returnValue(of({ data: { content: [] } }) as never);
    pharmacySvc.listLotsByPharmacy.and.returnValue(of({ data: { content: [] } }) as never);

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
        provideHttpClient(withXhr()),
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

    roleContext = TestBed.inject(RoleContextService);
    roleContext.setRoles(roles);
    roleContext.activeRole = roles[0] ?? null;

    fixture = TestBed.createComponent(DispensingComponent);
    fixture.detectChanges();
  }

  afterEach(() => TestBed.resetTestingModule());

  it('offers the control to a pharmacist on a fillable row', async () => {
    await render(['ROLE_PHARMACIST']);

    expect(
      fixture.nativeElement.querySelector('[data-testid="rx-clarification-open-rx-1"]'),
    ).not.toBeNull();
  });

  it('offers the control to a pharmacy verifier', async () => {
    await render(['ROLE_PHARMACY_VERIFIER']);

    expect(
      fixture.nativeElement.querySelector('[data-testid="rx-clarification-open-rx-1"]'),
    ).not.toBeNull();
  });

  it('withholds the control from a role the endpoint would reject', async () => {
    // HOSPITAL_ADMIN reaches the dispensing page but is not on
    // /request-clarification, so the button would only ever earn a 403.
    await render(['ROLE_HOSPITAL_ADMIN']);

    expect(
      fixture.nativeElement.querySelector('[data-testid="rx-clarification-open-rx-1"]'),
    ).toBeNull();
  });

  it('flags a row that needs a second look, in words rather than an enum name', async () => {
    await render(['ROLE_PHARMACIST'], {
      status: 'PENDING_STOCK',
      needsAttention: true,
      attentionReason: 'PENDING_STOCK',
    });

    const cue = fixture.nativeElement.querySelector('[data-testid="rx-attention-rx-1"]');
    expect(cue).not.toBeNull();
    expect(cue.textContent).toContain('PHARMACY.ATTENTION.PENDING_STOCK');
  });

  it('flags an order whose SMS dispatch failed, in words', async () => {
    await render(['ROLE_PHARMACIST'], {
      status: 'TRANSMISSION_FAILED',
      needsAttention: true,
      attentionReason: 'TRANSMISSION_FAILED',
    });

    const cue = fixture.nativeElement.querySelector('[data-testid="rx-attention-rx-1"]');
    expect(cue.textContent).toContain('PHARMACY.ATTENTION.TRANSMISSION_FAILED');
    expect(cue.textContent).not.toContain('PHARMACY.ATTENTION.UNRECOGNISED');
  });

  it('leaves a plain fill unflagged', async () => {
    await render(['ROLE_PHARMACIST']);

    expect(fixture.nativeElement.querySelector('[data-testid="rx-attention-rx-1"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="rx-answered-rx-1"]')).toBeNull();
  });

  it('still flags a row whose attention reason this build has never heard of', async () => {
    await render(['ROLE_PHARMACIST'], {
      needsAttention: true,
      attentionReason: 'SOMETHING_ADDED_LATER',
    });

    expect(
      fixture.nativeElement.querySelector('[data-testid="rx-attention-rx-1"]').textContent,
    ).toContain('PHARMACY.ATTENTION.UNRECOGNISED');
  });

  it('says the prescriber has answered even when the status wins the attention reason', async () => {
    // resolveClarification restores the status the question was asked from,
    // and the single attentionReason reports that status by precedence — so
    // without its own cue the answer was invisible on the queue.
    await render(['ROLE_PHARMACIST'], {
      status: 'PENDING_STOCK',
      needsAttention: true,
      attentionReason: 'PENDING_STOCK',
      clarificationResolvedAt: '2026-09-24T09:00:00',
    });

    const answered = fixture.nativeElement.querySelector('[data-testid="rx-answered-rx-1"]');
    expect(answered).not.toBeNull();
    expect(answered.textContent).toContain('PHARMACY.ATTENTION.PRESCRIBER_ANSWERED');
  });

  it('says it once when the attention reason is the clarification itself', async () => {
    // With nothing of higher precedence the backend derives both fields from
    // the same timestamp, and that label already reads "the prescriber has
    // answered — read the answer first".
    await render(['ROLE_PHARMACIST'], {
      needsAttention: true,
      attentionReason: 'CLARIFICATION_RESOLVED',
      clarificationResolvedAt: '2026-09-24T09:00:00',
    });

    expect(
      fixture.nativeElement.querySelector('[data-testid="rx-attention-rx-1"]').textContent,
    ).toContain('PHARMACY.ATTENTION.CLARIFICATION_RESOLVED');
    expect(fixture.nativeElement.querySelector('[data-testid="rx-answered-rx-1"]')).toBeNull();
  });

  it('passes the answer timestamp to the clarification control', async () => {
    await render(['ROLE_PHARMACIST'], {
      status: 'PENDING_STOCK',
      attentionReason: 'PENDING_STOCK',
      clarificationResolvedAt: '2026-09-24T09:00:00',
    });

    const control = fixture.debugElement.query(By.directive(PrescriptionClarificationComponent))
      .componentInstance as PrescriptionClarificationComponent;
    expect(control.clarificationResolvedAt()).toBe('2026-09-24T09:00:00');
  });

  it('reloads the queue when a clarification is raised', async () => {
    await render(['ROLE_PHARMACIST']);
    const before = pharmacySvc.getDispenseWorkQueue.calls.count();

    const control = fixture.debugElement.query(By.directive(PrescriptionClarificationComponent))
      .componentInstance as PrescriptionClarificationComponent;
    control.changed.emit();
    fixture.detectChanges();

    expect(pharmacySvc.getDispenseWorkQueue.calls.count()).toBe(before + 1);
  });
});

describe('DispensingComponent — work-queue claim (G13)', () => {
  let fixture: ComponentFixture<DispensingComponent>;
  let component: DispensingComponent;
  let pharmacySvc: jasmine.SpyObj<PharmacyService>;
  let toast: jasmine.SpyObj<ToastService>;
  let router: Router;

  const colleagueClaim = {
    prescriptionId: 'rx-1',
    claimedByUserId: 'user-awa',
    claimedByName: 'Awa Sanou',
    claimedAt: '2026-10-07T09:05:00',
    expiresAt: '2026-10-07T09:20:00',
    mine: false,
  };
  const myClaim = { ...colleagueClaim, claimedByUserId: 'user-1', claimedByName: 'Me', mine: true };

  function row(overrides: Record<string, unknown> = {}) {
    return {
      id: 'rx-1',
      medicationName: 'Metformin 500mg',
      dosage: '500mg',
      quantity: 30,
      status: 'SIGNED',
      patient: { id: 'pat-1', firstName: 'John', lastName: 'Doe' },
      ...overrides,
    };
  }

  function page(rows: unknown[]) {
    return of({
      data: { content: rows, totalElements: rows.length, totalPages: 1, size: 20, number: 0 },
    }) as never;
  }

  async function render(rowOverrides: Record<string, unknown> = {}, claimsOn = true) {
    pharmacySvc = jasmine.createSpyObj('PharmacyService', [
      'listPharmacies',
      'getDispenseWorkQueue',
      'listDispensesByPharmacy',
      'listInventoryByPharmacy',
      'listLotsByPharmacy',
      'createDispense',
      'cancelDispense',
      'getDispenseSettings',
      'markReady',
      'claimQueueRow',
      'takeOverQueueRow',
      'releaseQueueRow',
    ]);
    pharmacySvc.getDispenseSettings.and.returnValue(
      of({
        data: {
          readyForCollectionEnabled: true,
          queueClaimEnabled: claimsOn,
          queueClaimTtlMinutes: 15,
        },
      }) as never,
    );
    pharmacySvc.listPharmacies.and.returnValue(
      of({
        content: [
          { id: 'ph-1', name: 'Main Pharmacy' },
          { id: 'ph-2', name: 'Ward Pharmacy' },
        ],
        totalElements: 2,
        totalPages: 1,
        size: 100,
        number: 0,
      }) as never,
    );
    pharmacySvc.getDispenseWorkQueue.and.returnValue(page([row(rowOverrides)]));
    pharmacySvc.listDispensesByPharmacy.and.returnValue(of({ data: { content: [] } }) as never);
    pharmacySvc.listInventoryByPharmacy.and.returnValue(of({ data: { content: [] } }) as never);
    pharmacySvc.listLotsByPharmacy.and.returnValue(of({ data: { content: [] } }) as never);
    pharmacySvc.releaseQueueRow.and.returnValue(of({ data: null }) as never);
    toast = jasmine.createSpyObj('ToastService', ['success', 'error']);

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
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: PharmacyService, useValue: pharmacySvc },
        {
          provide: AuthService,
          useValue: jasmine.createSpyObj('AuthService', [], {
            currentProfile: () => ({ id: 'user-1' }),
          }),
        },
        { provide: ToastService, useValue: toast },
        { provide: OfflineDispenseQueueService, useValue: offlineQueueStub },
      ],
    }).compileComponents();

    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('en', {
      PHARMACY: {
        QUEUE_CLAIM: {
          BEING_PREPARED_BY: 'Being prepared by {{name}}',
          MINE: 'You are preparing this',
          SINCE: 'since {{time}}',
          TAKE_OVER_CONFIRM:
            '{{name}} has been preparing this prescription since {{time}}. Take it over?',
        },
      },
    });
    translate.use('en');
    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);

    fixture = TestBed.createComponent(DispensingComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  function text(testId: string): string {
    const el = fixture.nativeElement.querySelector(
      `[data-testid="${testId}"]`,
    ) as HTMLElement | null;
    return el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  }

  function exists(testId: string): boolean {
    return !!fixture.nativeElement.querySelector(`[data-testid="${testId}"]`);
  }

  function click(testId: string): void {
    (fixture.nativeElement.querySelector(`[data-testid="${testId}"]`) as HTMLElement).click();
    fixture.detectChanges();
  }

  const conflict = (message: string) =>
    throwError(() => ({ status: 409, error: { status: 409, message } }));

  afterEach(() => TestBed.resetTestingModule());

  // ── the row ──

  it("names a colleague's claim and how long it has run", async () => {
    await render({ claim: colleagueClaim });
    expect(text('rx-claim-rx-1')).toContain('Being prepared by Awa Sanou');
    expect(text('rx-claim-rx-1')).toContain('since');
  });

  it('says so when the claim is mine', async () => {
    await render({ claim: myClaim });
    expect(text('rx-claim-rx-1')).toContain('You are preparing this');
    expect(text('rx-claim-rx-1')).not.toContain('Being prepared by');
  });

  it('offers Claim on an unclaimed row, Release on mine, Take over on a colleague’s', async () => {
    await render();
    expect(exists('rx-claim-btn-rx-1')).toBeTrue();
    expect(exists('rx-release-btn-rx-1')).toBeFalse();
    expect(exists('rx-take-over-btn-rx-1')).toBeFalse();
    TestBed.resetTestingModule();

    await render({ claim: myClaim });
    expect(exists('rx-release-btn-rx-1')).toBeTrue();
    expect(exists('rx-claim-btn-rx-1')).toBeFalse();
    TestBed.resetTestingModule();

    await render({ claim: colleagueClaim });
    expect(exists('rx-take-over-btn-rx-1')).toBeTrue();
    expect(exists('rx-claim-btn-rx-1')).toBeFalse();
  });

  it('a prepared row offers no claim control', async () => {
    await render({ readyForCollection: { dispenseId: 'd-1', readyAt: '2026-10-07T08:00:00' } });
    expect(exists('rx-claim-btn-rx-1')).toBeFalse();
    expect(exists('rx-hand-over-rx-1')).toBeTrue();
  });

  it('shows nothing of the claim when the server has it switched off', async () => {
    await render({ claim: colleagueClaim }, false);
    expect(exists('rx-claim-rx-1')).toBeFalse();
    expect(exists('rx-take-over-btn-rx-1')).toBeFalse();
    expect(exists('rx-claim-btn-rx-1')).toBeFalse();
    expect(exists('claim-filter')).toBeFalse();

    click('rx-dispense-rx-1');
    expect(pharmacySvc.claimQueueRow).not.toHaveBeenCalled();
    expect(component.showForm()).toBeTrue();
  });

  it('Claim, Release and Take over call their endpoints', async () => {
    await render();
    pharmacySvc.claimQueueRow.and.returnValue(of({ data: myClaim }) as never);
    click('rx-claim-btn-rx-1');
    expect(pharmacySvc.claimQueueRow).toHaveBeenCalledWith('rx-1');
    expect(toast.success).toHaveBeenCalledWith('PHARMACY.QUEUE_CLAIM.CLAIMED');

    component.releaseRow(row() as never);
    expect(pharmacySvc.releaseQueueRow).toHaveBeenCalledWith('rx-1');

    pharmacySvc.takeOverQueueRow.and.returnValue(of({ data: myClaim }) as never);
    component.requestTakeOver(row({ claim: colleagueClaim }) as never);
    fixture.detectChanges();
    click('claim-take-over-confirm');
    expect(pharmacySvc.takeOverQueueRow).toHaveBeenCalledWith('rx-1');
    expect(toast.success).toHaveBeenCalledWith('PHARMACY.QUEUE_CLAIM.TAKEN_OVER');
  });

  // ── Dispense claims first ──

  it('Dispense claims the row, then opens the form', async () => {
    await render();
    pharmacySvc.claimQueueRow.and.returnValue(
      of({ data: { ...myClaim, renewed: false } }) as never,
    );

    click('rx-dispense-rx-1');

    expect(pharmacySvc.claimQueueRow).toHaveBeenCalledWith('rx-1');
    expect(component.showForm()).toBeTrue();
    expect(component.formClaimedRowId()).toBe('rx-1');
  });

  it('a 409 because a colleague holds it asks to take over, naming them; Cancel leaves the form closed', async () => {
    await render();
    pharmacySvc.claimQueueRow.and.returnValue(
      conflict('Another pharmacist is preparing this prescription.'),
    );
    pharmacySvc.getDispenseWorkQueue.and.returnValue(page([row({ claim: colleagueClaim })]));

    click('rx-dispense-rx-1');

    expect(component.showForm()).toBeFalse();
    expect(exists('claim-take-over')).toBeTrue();
    expect(text('claim-take-over-text')).toContain(
      'Awa Sanou has been preparing this prescription since',
    );

    click('claim-take-over-cancel');
    expect(exists('claim-take-over')).toBeFalse();
    expect(component.showForm()).toBeFalse();
    expect(pharmacySvc.takeOverQueueRow).not.toHaveBeenCalled();
  });

  it('Take over in that confirm takes the claim and opens the form', async () => {
    await render({ claim: colleagueClaim });
    pharmacySvc.takeOverQueueRow.and.returnValue(
      of({ data: { ...myClaim, renewed: false } }) as never,
    );

    click('rx-dispense-rx-1');
    expect(pharmacySvc.claimQueueRow).not.toHaveBeenCalled();
    click('claim-take-over-confirm');

    expect(pharmacySvc.takeOverQueueRow).toHaveBeenCalledWith('rx-1');
    expect(component.showForm()).toBeTrue();
    expect(component.formClaimedRowId()).toBe('rx-1');
  });

  it('a 409 under the Unclaimed filter still offers the take-over, though the row left that list', async () => {
    await render();
    click('claim-filter-UNCLAIMED');
    pharmacySvc.claimQueueRow.and.returnValue(
      conflict('Another pharmacist is preparing this prescription.'),
    );
    // The server omits a claimed row from UNCLAIMED; the unfiltered queue still has it.
    pharmacySvc.getDispenseWorkQueue.and.callFake(((_p: number, _s: number, filter: string) =>
      filter === 'UNCLAIMED' ? page([]) : page([row({ claim: colleagueClaim })])) as never);

    click('rx-dispense-rx-1');

    expect(pharmacySvc.getDispenseWorkQueue).toHaveBeenCalledWith(0, 20, 'UNCLAIMED');
    expect(component.workQueue()).toEqual([]);
    expect(exists('claim-take-over')).toBeTrue();
    expect(text('claim-take-over-text')).toContain(
      'Awa Sanou has been preparing this prescription',
    );
    expect(component.showForm()).toBeFalse();
    expect(toast.error).not.toHaveBeenCalled();
  });

  it('a 409 for a row no longer claimable says why, reloads the queue and opens no form', async () => {
    await render();
    pharmacySvc.claimQueueRow.and.returnValue(
      conflict('This prescription is no longer on the work queue.'),
    );
    pharmacySvc.getDispenseWorkQueue.and.returnValue(page([]));
    const before = pharmacySvc.getDispenseWorkQueue.calls.count();

    click('rx-dispense-rx-1');

    // the visible list, and the unfiltered lookup of the row
    expect(pharmacySvc.getDispenseWorkQueue.calls.count()).toBe(before + 2);
    expect(toast.error).toHaveBeenCalledWith('This prescription is no longer on the work queue.');
    expect(component.showForm()).toBeFalse();
    expect(component.claimAction()).toBeNull();
  });

  it('a network failure of the claim still opens the form (advisory), with a warning', async () => {
    await render();
    pharmacySvc.claimQueueRow.and.returnValue(throwError(() => ({ status: 0 })));

    click('rx-dispense-rx-1');

    expect(component.showForm()).toBeTrue();
    expect(component.formClaimedRowId()).toBeNull();
    expect(toast.error).toHaveBeenCalledWith('PHARMACY.QUEUE_CLAIM.FAILED');
  });

  // ── the form's own claim ──

  it('closing the form releases the claim the form made', async () => {
    await render();
    pharmacySvc.claimQueueRow.and.returnValue(
      of({ data: { ...myClaim, renewed: false } }) as never,
    );
    click('rx-dispense-rx-1');

    component.closeForm();

    expect(pharmacySvc.releaseQueueRow).toHaveBeenCalledOnceWith('rx-1');
    expect(component.formClaimedRowId()).toBeNull();
  });

  it('closing the form keeps a claim the form only renewed', async () => {
    await render({ claim: myClaim });
    pharmacySvc.claimQueueRow.and.returnValue(of({ data: { ...myClaim, renewed: true } }) as never);
    click('rx-dispense-rx-1');

    component.closeForm();

    expect(pharmacySvc.releaseQueueRow).not.toHaveBeenCalled();
  });

  it('a dispense keeps nothing to release: the server ended the claim', async () => {
    await render();
    pharmacySvc.claimQueueRow.and.returnValue(
      of({ data: { ...myClaim, renewed: false } }) as never,
    );
    pharmacySvc.createDispense.and.returnValue(of({ data: { id: 'd-1' } }) as never);
    click('rx-dispense-rx-1');

    component.submitDispense();
    component.closeForm();

    expect(pharmacySvc.releaseQueueRow).not.toHaveBeenCalled();
  });

  it('a pharmacy change, opening a hand-over, or leaving the page releases the form’s claim', async () => {
    await render();
    pharmacySvc.claimQueueRow.and.returnValue(
      of({ data: { ...myClaim, renewed: false } }) as never,
    );

    click('rx-dispense-rx-1');
    component.onPharmacyChange();
    expect(pharmacySvc.releaseQueueRow).toHaveBeenCalledTimes(1);

    click('rx-dispense-rx-1');
    component.openHandOver(row({ id: 'rx-2' }) as never);
    expect(pharmacySvc.releaseQueueRow).toHaveBeenCalledTimes(2);

    click('rx-dispense-rx-1');
    fixture.destroy();
    expect(pharmacySvc.releaseQueueRow).toHaveBeenCalledTimes(3);
  });

  // ── Route ──

  it('Route on a colleague’s row asks first; Cancel stays on the queue', async () => {
    await render({ claim: colleagueClaim });

    click('rx-route-rx-1');
    expect(router.navigate).not.toHaveBeenCalled();
    expect(exists('claim-take-over')).toBeTrue();

    click('claim-take-over-cancel');
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('Route on an unclaimed row goes straight to routing', async () => {
    await render();
    click('rx-route-rx-1');
    expect(router.navigate).toHaveBeenCalledWith(['/pharmacy/stock-routing', 'rx-1']);
  });

  // ── the filter ──

  it('the filter asks the server for claim=MINE / UNCLAIMED', async () => {
    await render();

    click('claim-filter-MINE');
    expect(pharmacySvc.getDispenseWorkQueue).toHaveBeenCalledWith(0, 20, 'MINE');
    click('claim-filter-UNCLAIMED');
    expect(pharmacySvc.getDispenseWorkQueue).toHaveBeenCalledWith(0, 20, 'UNCLAIMED');
    click('claim-filter-ALL');
    expect(pharmacySvc.getDispenseWorkQueue).toHaveBeenCalledWith(0, 20, 'ALL');
  });

  // ── review round 1: the latest queue answer wins ──

  function pageValue(rows: unknown[]) {
    return {
      data: { content: rows, totalElements: rows.length, totalPages: 1, size: 20, number: 0 },
    };
  }

  it('the latest queue answer wins: an older ALL poll landing after the MINE answer is ignored', async () => {
    await render();
    const olderAll = new Subject<unknown>();
    const newerMine = new Subject<unknown>();
    pharmacySvc.getDispenseWorkQueue.and.returnValues(olderAll as never, newerMine as never);

    component.loadWorkQueue(true); // the 60 s poll, still ALL
    click('claim-filter-MINE');
    newerMine.next(pageValue([row({ id: 'rx-mine', claim: myClaim })]));
    olderAll.next(pageValue([row(), row({ id: 'rx-2' })]));

    expect(component.claimFilter()).toBe('MINE');
    expect(component.workQueue().map((r) => r.id)).toEqual(['rx-mine']);
  });

  it('two polls of the same filter: the older answer arriving last does not overwrite the newer', async () => {
    await render();
    const older = new Subject<unknown>();
    const newer = new Subject<unknown>();
    pharmacySvc.getDispenseWorkQueue.and.returnValues(older as never, newer as never);

    component.loadWorkQueue(true);
    component.loadWorkQueue(true);
    newer.next(pageValue([row({ claim: colleagueClaim })]));
    older.next(pageValue([row()]));

    expect(component.workQueue()[0].claim?.claimedByName).toBe('Awa Sanou');
  });

  // ── review round 1: the form keeps its claim ──

  function twoRows(): void {
    pharmacySvc.getDispenseWorkQueue.and.returnValue(
      page([row(), row({ id: 'rx-2', claim: { ...colleagueClaim, prescriptionId: 'rx-2' } })]),
    );
    component.loadWorkQueue();
    fixture.detectChanges();
  }

  it('Dispense on the row whose form is open does nothing, and Cancel still releases the form’s claim', async () => {
    await render();
    pharmacySvc.claimQueueRow.and.returnValue(
      of({ data: { ...myClaim, renewed: false } }) as never,
    );
    click('rx-dispense-rx-1');

    pharmacySvc.claimQueueRow.and.returnValue(of({ data: { ...myClaim, renewed: true } }) as never);
    click('rx-dispense-rx-1');

    expect(pharmacySvc.claimQueueRow).toHaveBeenCalledTimes(1);
    expect(component.formClaimedRowId()).toBe('rx-1');
    component.closeForm();
    expect(pharmacySvc.releaseQueueRow).toHaveBeenCalledOnceWith('rx-1');
  });

  it('a double click on Dispense sends one claim, and Cancel still releases it', async () => {
    await render();
    const first = new Subject<unknown>();
    const second = new Subject<unknown>();
    pharmacySvc.claimQueueRow.and.returnValues(first as never, second as never);

    click('rx-dispense-rx-1');
    click('rx-dispense-rx-1');
    first.next({ data: { ...myClaim, renewed: false } });
    second.next({ data: { ...myClaim, renewed: true } });
    fixture.detectChanges();

    expect(pharmacySvc.claimQueueRow).toHaveBeenCalledTimes(1);
    expect(component.formClaimedRowId()).toBe('rx-1');
    component.closeForm();
    expect(pharmacySvc.releaseQueueRow).toHaveBeenCalledOnceWith('rx-1');
  });

  it('a renewed answer for the row the form already claimed keeps the form’s claim and the form', async () => {
    await render();
    pharmacySvc.claimQueueRow.and.returnValue(
      of({ data: { ...myClaim, renewed: false } }) as never,
    );
    click('rx-dispense-rx-1');
    component.form.notes = 'typed already';

    (
      component as unknown as { openFormFor(rx: unknown, claimedByForm: boolean): void }
    ).openFormFor(row(), false);

    expect(component.formClaimedRowId()).toBe('rx-1');
    expect(component.form.notes).toBe('typed already');
  });

  it('Dispense on a colleague’s row then Cancel keeps the open form and its claim', async () => {
    await render();
    twoRows();
    pharmacySvc.claimQueueRow.and.returnValue(
      of({ data: { ...myClaim, renewed: false } }) as never,
    );
    click('rx-dispense-rx-1');

    click('rx-dispense-rx-2');
    expect(exists('claim-take-over')).toBeTrue();
    click('claim-take-over-cancel');

    expect(pharmacySvc.releaseQueueRow).not.toHaveBeenCalled();
    expect(component.formClaimedRowId()).toBe('rx-1');
    expect(component.showForm()).toBeTrue();
    expect(component.selectedPrescription?.id).toBe('rx-1');
  });

  it('Dispense on a colleague’s row then Take over moves the form and only then releases the old claim', async () => {
    await render();
    twoRows();
    pharmacySvc.claimQueueRow.and.returnValue(
      of({ data: { ...myClaim, renewed: false } }) as never,
    );
    click('rx-dispense-rx-1');
    pharmacySvc.takeOverQueueRow.and.returnValue(
      of({ data: { ...myClaim, prescriptionId: 'rx-2', renewed: false } }) as never,
    );

    click('rx-dispense-rx-2');
    expect(pharmacySvc.releaseQueueRow).not.toHaveBeenCalled();
    click('claim-take-over-confirm');

    expect(pharmacySvc.releaseQueueRow).toHaveBeenCalledOnceWith('rx-1');
    expect(component.formClaimedRowId()).toBe('rx-2');
    expect(component.selectedPrescription?.id).toBe('rx-2');
  });

  // ── polling ──

  describe('polling', () => {
    afterEach(() => jasmine.clock().uninstall());

    async function renderWithClock(): Promise<void> {
      await render();
      jasmine.clock().install();
      // restart the timer under the mocked clock
      (component as unknown as { stopQueuePolling(): void }).stopQueuePolling();
      (component as unknown as { startQueuePolling(): void }).startQueuePolling();
    }

    it('reloads the queue every minute while the tab is visible', async () => {
      await renderWithClock();
      const before = pharmacySvc.getDispenseWorkQueue.calls.count();
      jasmine.clock().tick(60_000);
      expect(pharmacySvc.getDispenseWorkQueue.calls.count()).toBe(before + 1);
    });

    it('does not poll while the tab is hidden, and reloads when it becomes visible', async () => {
      await renderWithClock();
      const visibility = spyOnProperty(document, 'visibilityState', 'get').and.returnValue(
        'hidden',
      );
      const before = pharmacySvc.getDispenseWorkQueue.calls.count();
      jasmine.clock().tick(60_000);
      expect(pharmacySvc.getDispenseWorkQueue.calls.count()).toBe(before);

      visibility.and.returnValue('visible');
      document.dispatchEvent(new Event('visibilitychange'));
      expect(pharmacySvc.getDispenseWorkQueue.calls.count()).toBe(before + 1);
    });

    it('pauses while the form, a take-over confirm or a hand-over dialog is open', async () => {
      await renderWithClock();
      const before = pharmacySvc.getDispenseWorkQueue.calls.count();

      component.showForm.set(true);
      jasmine.clock().tick(60_000);
      component.showForm.set(false);
      component.claimAction.set({ rx: row() as never, purpose: 'takeOver' });
      jasmine.clock().tick(60_000);
      component.claimAction.set(null);
      component.readyAction.set({ kind: 'handOver', rx: row() as never });
      jasmine.clock().tick(60_000);

      expect(pharmacySvc.getDispenseWorkQueue.calls.count()).toBe(before);
    });

    it('stops when the page is left', async () => {
      await renderWithClock();
      fixture.destroy();
      const before = pharmacySvc.getDispenseWorkQueue.calls.count();
      jasmine.clock().tick(180_000);
      document.dispatchEvent(new Event('visibilitychange'));
      expect(pharmacySvc.getDispenseWorkQueue.calls.count()).toBe(before);
    });
  });
});
