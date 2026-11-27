import { TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';
import { InventoryDashboardComponent } from './inventory-dashboard';
import { StockAdjustmentComponent } from './stock-adjustment';
import { PharmacyReconciliationComponent } from './pharmacy-reconciliation';
import { PharmacyService } from '../services/pharmacy.service';
import { PharmacyMoneyService, PharmacyReconciliation } from '../services/pharmacy-money.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { expectEmptySubmitRefusal } from '../testing/form-refusal-behaviour';

/** QA r65 D2673–D2676: the pharmacy money and stock screens. */

function toast() {
  return jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'info']);
}

function money() {
  return jasmine.createSpyObj<PharmacyMoneyService>('PharmacyMoneyService', [
    'dispensePaymentStatus',
    'setPackSize',
    'reconciliationPharmacies',
    'reconciliation',
    'reconciliationExport',
    'refunds',
    'requestRefund',
    'approveRefund',
    'rejectRefund',
    'payRefund',
    'cancelRefund',
  ]);
}

describe('Inventory pack size (QA D2674)', () => {
  function render(unitsPerPack: number | null) {
    const svc = jasmine.createSpyObj<PharmacyService>('PharmacyService', [
      'listStores',
      'listInventoryByPharmacy',
      'getReorderAlertsByPharmacy',
      'updateInventoryItem',
    ]);
    svc.listStores.and.returnValue(of({ content: [{ id: 'ph-1', name: 'Pharmacie' }] }) as never);
    svc.getReorderAlertsByPharmacy.and.returnValue(of({ data: [] }) as never);
    svc.listInventoryByPharmacy.and.returnValue(
      of({
        data: {
          content: [
            {
              id: 'inv-1',
              pharmacyId: 'ph-1',
              medicationCatalogItemId: 'med-1',
              medicationName: 'Paracétamol',
              quantityOnHand: 4005,
              reorderThreshold: 100,
              reorderQuantity: 500,
              unit: unitsPerPack ? 'comprimé' : 'plaquette',
              unitsPerPack,
              packUnit: unitsPerPack ? 'plaquette' : null,
              fullPacksOnHand: unitsPerPack ? 400 : null,
              looseUnitsOnHand: unitsPerPack ? 5 : null,
              unitPrice: 25,
              active: true,
            },
          ],
          totalPages: 1,
        },
      }) as never,
    );
    const m = money();
    m.setPackSize.and.returnValue(of({ data: {} }) as never);
    TestBed.configureTestingModule({
      imports: [InventoryDashboardComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PharmacyService, useValue: svc },
        { provide: PharmacyMoneyService, useValue: m },
        { provide: ToastService, useValue: toast() },
        { provide: RoleContextService, useValue: { hasAnyActiveRoleExactly: () => true } },
      ],
    });
    const fixture = TestBed.createComponent(InventoryDashboardComponent);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    return { fixture, m };
  }

  afterEach(() =>
    document.querySelectorAll('app-inventory-dashboard').forEach((el) => el.remove()),
  );

  it('an empty pack-size form marks its required fields and sends nothing', () => {
    const { fixture, m } = render(null);
    (fixture.nativeElement.querySelector('[data-testid="pack-size-inv-1"]') as HTMLElement).click();
    fixture.detectChanges();
    expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="pack-size-save"]',
      sent: m.setPackSize,
      // QA D3400: the radio group « compté en » has no default and is marked on its first option.
      alsoMarked: ['packCountedInUnits'],
    });
  });

  it('sends the pack size the pharmacist typed', () => {
    const { fixture, m } = render(null);
    const c = fixture.componentInstance;
    c.openPackSize(c.inventoryItems()[0]);
    c.packUnitsPerPack = 10;
    c.packTargetUnit = 'comprimé';
    c.packReason = 'Reçu en plaquettes';
    c.packCountedIn = 'PACKS';
    c.savePackSize();
    expect(m.setPackSize).toHaveBeenCalledWith('inv-1', {
      unitsPerPack: 10,
      unit: 'comprimé',
      packUnit: 'plaquette',
      reason: 'Reçu en plaquettes',
      alreadyInUnits: false,
    });
  });

  it('QA D3400: asks what the stock is counted in (no default) and says when nothing is multiplied', () => {
    const { fixture, m } = render(null);
    const c = fixture.componentInstance;
    c.openPackSize(c.inventoryItems()[0]);
    expect(c.packCountedIn).toBeNull();
    c.packUnitsPerPack = 10;
    c.packTargetUnit = 'comprimé';
    c.packReason = 'Plaquettes de 10';
    c.savePackSize();
    expect(m.setPackSize).not.toHaveBeenCalled();

    const item = c.inventoryItems()[0];
    c.packCountedIn = 'PACKS';
    expect(c.packPreview(item).qty).toBe((item.quantityOnHand ?? 0) * 10);
    c.packCountedIn = 'UNITS';
    expect(c.packPreview(item)).toEqual({
      qty: item.quantityOnHand ?? 0,
      threshold: item.reorderThreshold ?? 0,
      order: item.reorderQuantity ?? 0,
    });
    c.savePackSize();
    expect(m.setPackSize).toHaveBeenCalledWith(
      'inv-1',
      jasmine.objectContaining({ alreadyInUnits: true }),
    );
  });

  it('shows a converted item in packs and units, and offers no second conversion', () => {
    const { fixture } = render(10);
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="packs-inv-1"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="pack-size-inv-1"]')).toBeNull();
  });
});

describe('Stock return refund prompt (QA D2676)', () => {
  it('an empty refund reason is marked and nothing is sent', () => {
    const svc = jasmine.createSpyObj<PharmacyService>('PharmacyService', [
      'listStores',
      'listInventoryByPharmacy',
      'listTransactionsByPharmacy',
      'listTransactionsByType',
    ]);
    svc.listStores.and.returnValue(of({ content: [] }) as never);
    const m = money();
    TestBed.configureTestingModule({
      imports: [StockAdjustmentComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PharmacyService, useValue: svc },
        { provide: PharmacyMoneyService, useValue: m },
        { provide: ToastService, useValue: toast() },
      ],
    });
    const fixture = TestBed.createComponent(StockAdjustmentComponent);
    document.body.appendChild(fixture.nativeElement);
    fixture.componentInstance.refundPrompt.set({ returnTransactionId: 'tx-1' });
    fixture.detectChanges();
    expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="refund-request"]',
      sent: m.requestRefund,
    });
    fixture.nativeElement.remove();
  });
});

describe('Pharmacy reconciliation page (QA D2673)', () => {
  const day: PharmacyReconciliation = {
    pharmacyId: 'ph-1',
    date: '2026-11-20',
    generatedAt: '2026-11-20T18:00:00',
    money: {
      salesCount: 2,
      salesTotal: 3000,
      salesByMethod: { CASH: 2500, MOBILE_MONEY: 500 },
      dispensePaymentsCount: 3,
      dispensePaymentsTotal: 8500,
      dispensePaymentsByMethod: { CASH: 8500 },
      refundsCount: 1,
      refundsPaid: 500,
      netCollected: 11000,
      dispenseCharges: 8500,
      deferredTotal: 0,
      unpaidTotal: 0,
    },
    stock: [
      {
        medicationName: 'Paracétamol',
        lotNumber: 'LOT-A-R65-1',
        received: 0,
        soldRecorded: 12,
        soldMoved: 12,
        dispensedRecorded: 34,
        dispensedMoved: 10,
        returned: 2,
        adjusted: 0,
        transferred: 0,
        difference: true,
      },
    ],
    differences: [
      {
        type: 'DISPENSE_STOCK_MISMATCH',
        medicationName: 'Paracétamol',
        lotNumber: 'LOT-A-R65-1',
        expected: 34,
        actual: 10,
      },
    ],
    controlled: [],
    deferred: [],
  };

  it('shows the money, highlights the difference, and lists open refunds', () => {
    const m = money();
    m.reconciliationPharmacies.and.returnValue(
      of({ data: [{ pharmacyId: 'ph-1', pharmacyName: 'Pharmacie' }] }) as never,
    );
    m.reconciliation.and.returnValue(of({ data: day }) as never);
    m.refunds.and.returnValue(
      of({
        data: [
          {
            id: 'rf-1',
            pharmacyId: 'ph-1',
            returnTransactionId: 'tx-1',
            quantity: 4,
            amount: 1000,
            method: 'CASH',
            reason: 'Erreur',
            status: 'REQUESTED',
            requestedBy: 'u-1',
            requestedAt: '2026-11-20T10:00:00',
            canDecide: true,
          },
        ],
      }) as never,
    );
    TestBed.configureTestingModule({
      imports: [PharmacyReconciliationComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PharmacyMoneyService, useValue: m },
        { provide: ToastService, useValue: toast() },
      ],
    });
    const fixture = TestBed.createComponent(PharmacyReconciliationComponent);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    expect(m.reconciliation).toHaveBeenCalledWith('ph-1', jasmine.any(String));
    expect(host.querySelector('[data-testid="recon-verdict"]')?.classList).toContain('recon-ko');
    expect(host.querySelectorAll('[data-testid="recon-differences"] tbody tr').length).toBe(1);
    expect(host.querySelector('[data-testid="recon-stock"] tr.row-ko')).not.toBeNull();
    expect(host.querySelector('[data-testid="refund-approve-rf-1"]')).not.toBeNull();

    // QA D2676: an empty rejection reason is marked and nothing is sent.
    document.body.appendChild(host);
    fixture.componentInstance.startReject(fixture.componentInstance.refunds()[0]);
    fixture.detectChanges();
    expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="refund-reject-confirm"]',
      sent: m.rejectRefund,
    });
    host.remove();
  });

  it('policy rule 6: each « à facturer » fill names the deferral behind it, and an override says if it was reviewed', () => {
    const m = money();
    m.reconciliationPharmacies.and.returnValue(
      of({ data: [{ pharmacyId: 'ph-1', pharmacyName: 'Pharmacie' }] }) as never,
    );
    m.refunds.and.returnValue(of({ data: [] }) as never);
    m.reconciliation.and.returnValue(
      of({
        data: {
          ...day,
          differences: [],
          money: {
            ...day.money,
            deferredTotal: 3000,
            urgencyOverrideTotal: 1000,
            admissionAccountTotal: 1500,
          },
          deferred: [
            {
              dispenseId: 'd-1',
              medicationName: 'Paracétamol',
              quantity: 4,
              due: 1000,
              reason: 'Crise, caisse fermée',
              kind: 'URGENCY_OVERRIDE',
              patientName: 'Awa Traoré',
              patientMrn: 'CHW0042',
              handedOverByName: 'Awa Pharmacienne',
              reviewed: false,
            },
            {
              dispenseId: 'd-2',
              medicationName: 'Paracétamol',
              quantity: 6,
              due: 1500,
              kind: 'ADMISSION_ACCOUNT',
            },
            {
              dispenseId: 'd-3',
              medicationName: 'Paracétamol',
              quantity: 2,
              due: 500,
              reason: 'Hospitalisé',
              kind: 'UNAPPROVED',
            },
          ],
        },
      }) as never,
    );
    TestBed.configureTestingModule({
      imports: [PharmacyReconciliationComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PharmacyMoneyService, useValue: m },
        { provide: ToastService, useValue: toast() },
      ],
    });
    const fixture = TestBed.createComponent(PharmacyReconciliationComponent);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const kinds = Array.from(host.querySelectorAll('[data-testid="recon-deferral-kind"]')).map(
      (e) => e.textContent?.trim(),
    );
    // The label pipe falls back to the code where no translation is loaded (as here).
    expect(kinds).toEqual(['URGENCY_OVERRIDE', 'ADMISSION_ACCOUNT', 'UNAPPROVED']);
    const review = host.querySelectorAll('[data-testid="recon-override-review"]');
    expect(review.length).toBe(1);
    expect(review[0].textContent).toContain('PHARMACY_MONEY.RECON.OVERRIDE_TO_REVIEW');
    expect(host.querySelector('[data-testid="recon-urgency-total"]')).not.toBeNull();
    const patient = host.querySelectorAll('[data-testid="recon-deferral-patient"]');
    expect(patient.length).toBe(1);
    expect(patient[0].textContent).toContain('Awa Traoré');
    expect(patient[0].textContent).toContain('CHW0042');
    expect(host.querySelector('[data-testid="recon-stay-total"]')).not.toBeNull();
  });
});
