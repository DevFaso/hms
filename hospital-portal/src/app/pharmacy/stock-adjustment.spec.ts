import { TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { Observable, of, NEVER, throwError } from 'rxjs';
import { StockAdjustmentComponent } from './stock-adjustment';
import { PharmacyService } from '../services/pharmacy.service';
import { ToastService } from '../core/toast.service';
import { expectEmptySubmitRefusal } from '../testing/form-refusal-behaviour';

type LotsPage =
  ReturnType<PharmacyService['listLotsByInventoryItem']> extends Observable<infer T> ? T : never;
type PharmaciesPage =
  ReturnType<PharmacyService['listStores']> extends Observable<infer T> ? T : never;

describe('StockAdjustmentComponent (D82 / D108)', () => {
  let svc: jasmine.SpyObj<PharmacyService>;
  let toast: jasmine.SpyObj<ToastService>;

  function create(): StockAdjustmentComponent {
    svc = jasmine.createSpyObj<PharmacyService>('PharmacyService', [
      'listStores',
      'listInventoryByPharmacy',
      'listTransactionsByPharmacy',
      'listTransactionsByType',
      'listLotsByInventoryItem',
      'recordTransaction',
      'receiveStock',
      'listWitnessCandidates',
      'listReturnableDispenses',
      'listReturnableTransfers',
      'listQuarantine',
      'destroyQuarantined',
    ]);
    svc.listReturnableDispenses.and.returnValue(of({ data: [] }) as never);
    svc.listReturnableTransfers.and.returnValue(of({ data: [] }) as never);
    svc.listQuarantine.and.returnValue(of({ data: [] }) as never);
    svc.listWitnessCandidates.and.returnValue(
      of({ data: [{ userId: 'u-2', displayName: 'Awa Traore' }] } as unknown as ReturnType<
        PharmacyService['listWitnessCandidates']
      > extends Observable<infer T>
        ? T
        : never),
    );
    svc.listStores.and.returnValue(of({ content: [] } as unknown as PharmaciesPage));
    svc.listLotsByInventoryItem.and.returnValue(
      of({
        data: { content: [{ id: 'lot-1', lotNumber: 'L1', remainingQuantity: 50 }] },
      } as unknown as LotsPage),
    );
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'info']);
    TestBed.configureTestingModule({
      imports: [StockAdjustmentComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PharmacyService, useValue: svc },
        { provide: ToastService, useValue: toast },
      ],
    });
    return TestBed.createComponent(StockAdjustmentComponent).componentInstance;
  }

  it('D3943: lists every other active store of the source hospital, including ward stores', () => {
    const c = create();
    c.selectedPharmacyId = 'source';
    const timestamps = { createdAt: '2026-11-25T00:00:00', updatedAt: '2026-11-25T00:00:00' };
    c.pharmacies.set([
      { ...timestamps, id: 'source', hospitalId: 'A', name: 'Central', active: true },
      {
        ...timestamps,
        id: 'ward',
        hospitalId: 'A',
        name: 'Ward store',
        active: true,
        pharmacyType: 'HOSPITAL_DISPENSARY',
      },
      { ...timestamps, id: 'foreign', hospitalId: 'B', name: 'Other hospital', active: true },
      { ...timestamps, id: 'closed', hospitalId: 'A', name: 'Closed', active: false },
    ]);
    expect(c.transferDestinationOptions().map((p) => p.id)).toEqual(['ward']);
    c.selectedPharmacyId = 'missing';
    expect(c.transferDestinationOptions()).toEqual([]);
  });

  it('does not offer DISPENSE as a manual stock movement', () => {
    const c = create();
    expect(c.formTransactionTypes).not.toContain('DISPENSE');
  });

  it('QA D710: refuses to receive a lot that expires today', () => {
    const c = create();
    c.openForm();
    c.onTypeChange('RECEIPT');
    c.form.inventoryItemId = 'item-1';
    c.form.quantity = 10;
    c.receiptLotNumber = 'TODAY-1';
    const d = new Date();
    c.receiptExpiryDate = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

    c.submitForm();

    expect(toast.error).toHaveBeenCalledWith('PHARMACY.RECEIPT_LOT_EXPIRED');
    expect(svc.receiveStock).not.toHaveBeenCalled();
  });

  it('refuses to receive a lot that is already expired', () => {
    const c = create();
    c.openForm();
    c.onTypeChange('RECEIPT');
    c.form.inventoryItemId = 'item-1';
    c.form.quantity = 10;
    c.receiptLotNumber = 'OLD-1';
    c.receiptExpiryDate = '2000-01-01';

    c.submitForm();

    expect(toast.error).toHaveBeenCalledWith('PHARMACY.RECEIPT_LOT_EXPIRED');
    expect(svc.receiveStock).not.toHaveBeenCalled();
    expect(svc.recordTransaction).not.toHaveBeenCalled();
  });

  it('QA D143: sends a negative adjustment (a write-off) instead of refusing it', () => {
    const c = create();
    svc.recordTransaction.and.returnValue(of({}) as never);
    svc.listTransactionsByPharmacy.and.returnValue(of({}) as never);
    svc.listInventoryByPharmacy.and.returnValue(of({}) as never);
    c.openForm();
    c.onTypeChange('ADJUSTMENT');
    c.onItemChange('item-1');
    c.form.stockLotId = 'lot-1';
    c.form.quantity = -1;
    c.adjustmentReasonCode = 'CONTROLLED_DISCREPANCY';
    c.adjustmentNotes = 'count';

    c.submitForm();

    expect(toast.error).not.toHaveBeenCalled();
    expect(svc.recordTransaction).toHaveBeenCalledWith(
      jasmine.objectContaining({ transactionType: 'ADJUSTMENT', quantity: -1 }),
    );
  });

  it('QA D143: a zero adjustment and a negative receipt get their own message', () => {
    const c = create();
    c.openForm();
    c.onTypeChange('ADJUSTMENT');
    c.form.inventoryItemId = 'item-1';
    c.form.quantity = 0;
    c.submitForm();
    expect(toast.error).toHaveBeenCalledWith('PHARMACY.ADJUSTMENT_QUANTITY_NONZERO');

    c.onTypeChange('RETURN');
    c.form.quantity = -3;
    c.submitForm();
    expect(toast.error).toHaveBeenCalledWith('PHARMACY.TX_QUANTITY_POSITIVE');
    expect(svc.recordTransaction).not.toHaveBeenCalled();
  });

  it('QA D157: a controlled item needs a witness before anything is sent', () => {
    const c = create();
    c.inventoryItems.set([{ id: 'morph', controlled: true, quantityOnHand: 10 } as never]);
    c.openForm();
    c.onTypeChange('ADJUSTMENT');
    c.onItemChange('morph');
    c.form.stockLotId = 'lot-1';
    c.form.quantity = -1;
    c.adjustmentReasonCode = 'CONTROLLED_DISCREPANCY';
    c.adjustmentNotes = 'count';

    expect(c.needsWitness()).toBeTrue();
    expect(c.witnessCandidates().length).toBe(1);
    c.submitForm();

    expect(toast.error).toHaveBeenCalledWith('PHARMACY.WITNESS_REQUIRED');
    expect(svc.recordTransaction).not.toHaveBeenCalled();
  });

  it('QA D655: a controlled receipt needs a witness and sends it with the lot', () => {
    const c = create();
    // Never answers: the reload after a success is not what this test is about.
    svc.receiveStock.and.returnValue(NEVER as never);
    c.inventoryItems.set([{ id: 'morph', controlled: true, quantityOnHand: 10 } as never]);
    c.openForm();
    c.onTypeChange('RECEIPT');
    c.onItemChange('morph');
    c.form.quantity = 10;
    c.receiptLotNumber = 'MOR-1';
    c.receiptExpiryDate = '2099-01-31';

    expect(c.needsWitness()).toBeTrue();
    c.submitForm();
    expect(toast.error).toHaveBeenCalledWith('PHARMACY.WITNESS_REQUIRED');
    expect(svc.receiveStock).not.toHaveBeenCalled();

    c.form.witnessedBy = 'u-2';
    c.submitForm();
    expect(svc.receiveStock.calls.mostRecent().args[0].witnessedBy).toBe('u-2');
  });

  describe('QA D4184: the movement list names what moved', () => {
    const ledger = [
      {
        id: 't-1',
        inventoryItemId: 'i-1',
        transactionType: 'TRANSFER',
        quantity: 20,
        stockLotNumber: 'R81-A-1',
        medicationName: 'Métronidazole 250 mg',
        form: 'TABLET',
        unit: 'comprimé',
        counterpartPharmacyName: 'Réserve de service',
        reason: 'TRANSFER to Réserve de service',
        createdAt: '2026-11-27T05:41:00',
      },
      {
        id: 't-2',
        inventoryItemId: 'i-2',
        transactionType: 'DISPENSE',
        quantity: 1,
        stockLotNumber: 'R81-A-1',
        medicationName: 'Métronidazole 500 mg',
        form: 'CAPSULE',
        unit: 'gélule',
        reason: 'Dispense for prescription 4f0c',
        createdAt: '2026-11-20T09:00:00',
      },
      {
        id: 't-3',
        inventoryItemId: 'i-9',
        transactionType: 'ADJUSTMENT',
        quantity: -2,
        reason: 'DAMAGE: flacon cassé au déballage, constaté par le magasinier',
        createdAt: '2026-11-10T09:00:00',
      },
    ];

    function withLedger() {
      const c = create();
      svc.listStores.and.returnValue(of({ content: [{ id: 'p-1', name: 'Pharmacie' }] }) as never);
      svc.listInventoryByPharmacy.and.returnValue(
        of({ data: { content: [{ id: 'i-9', medicationName: 'Sirop Paracétamol' }] } }) as never,
      );
      svc.listTransactionsByPharmacy.and.returnValue(of({ data: { content: ledger } }) as never);
      const fixture = TestBed.createComponent(StockAdjustmentComponent);
      fixture.detectChanges();
      return {
        c: fixture.componentInstance,
        fixture,
        el: fixture.nativeElement as HTMLElement,
        unused: c,
      };
    }

    it('each row names its medicine and form, its unit and a transfer its destination; the reason is whole', () => {
      const { el } = withLedger();
      const rows = Array.from(el.querySelectorAll('[data-testid="ledger-row"]')).map((row) =>
        row.textContent!.replace(/\s+/g, ' '),
      );
      // The same lot number on two medicines: each row says which.
      expect(rows[0]).toContain('Métronidazole 250 mg');
      expect(rows[0]).toContain('PHARMACY.FORM_OPTION.TABLET');
      expect(rows[0]).toContain('-20 comprimés');
      expect(rows[1]).toContain('Métronidazole 500 mg');
      expect(rows[1]).toContain('-1 gélule');
      expect(el.querySelector('[data-testid="ledger-transfer-store"]')!.textContent).toContain(
        'PHARMACY.LEDGER.TRANSFER_TO',
      );
      // A movement from a server that sends no name falls back to the store's own item list.
      expect(rows[2]).toContain('Sirop Paracétamol');
      expect(rows[2]).toContain('flacon cassé au déballage, constaté par le magasinier');
      expect(el.querySelector('.reason-cell')!.hasAttribute('title')).toBeFalse();
    });

    it('filters by medicine or lot and by period, and says when nothing matches', () => {
      const { c, fixture, el } = withLedger();
      c.filterMedicine = '500';
      expect(c.visibleTransactions().map((tx) => tx.id)).toEqual(['t-2']);
      c.filterMedicine = 'r81-a-1';
      expect(c.visibleTransactions().length).toBe(2);
      c.filterMedicine = '';
      c.filterFrom = '2026-11-15';
      c.filterTo = '2026-11-25';
      expect(c.visibleTransactions().map((tx) => tx.id)).toEqual(['t-2']);
      c.filterMedicine = 'aucun';
      fixture.detectChanges();
      expect(el.querySelector('[data-testid="ledger-no-match"]')).not.toBeNull();
      c.clearLedgerFilters();
      expect(c.visibleTransactions().length).toBe(3);
    });
  });

  it('QA D158: the ledger signs receipts positive and an adjustment by its own sign', () => {
    const c = create();
    expect(c.signedQuantity({ transactionType: 'RECEIPT', quantity: 20 } as never)).toBe('+20');
    expect(c.signedQuantity({ transactionType: 'ADJUSTMENT', quantity: -1 } as never)).toBe('-1');
    expect(c.signedQuantity({ transactionType: 'ADJUSTMENT', quantity: 4 } as never)).toBe('+4');
    expect(c.signedQuantity({ transactionType: 'DISPENSE', quantity: 2 } as never)).toBe('-2');
  });

  it('QA D227: shows ledger reasons translated, with no raw code or prescription id', () => {
    const c = create();
    const label = (reason: string) => c.reasonLabel({ reason } as never);
    expect(label('Stock receipt: lot QA-PARA-L1')).toBe('PHARMACY.LEDGER_REASON_RECEIPT_LOT');
    expect(label('Dispense for prescription 3105c0de-0000-4000-8000-000000000000')).toBe(
      'PHARMACY.LEDGER_REASON_DISPENSE',
    );
    expect(label('RETURN ref QA-RET-001')).toBe('PHARMACY.LEDGER_REASON_RETURN');
    // QA D1000: every ledger shape PharmacyUserFacingTextGuardTest#LEDGER_REASON_PREFIXES
    // lets the server store is translated here.
    expect(label('Dispense cancelled — stock returned for prescription 3105c0de')).toBe(
      'PHARMACY.LEDGER_REASON_DISPENSE_CANCELLED',
    );
    expect(label('Prepared fill cancelled — stock returned for prescription unknown')).toBe(
      'PHARMACY.LEDGER_REASON_PREPARED_CANCELLED',
    );
    expect(label('CYCLE_COUNT_VARIANCE: recount')).toBe(
      // QA D1051: the colon is the reader's (COMMON.COLON), not a French space typed in.
      'PHARMACY.ADJ_REASON_CYCLE_COUNT_VARIANCECOMMON.COLON recount',
    );
    expect(label('CONTROLLED_DISCREPANCY')).toBe('PHARMACY.ADJ_REASON_CONTROLLED_DISCREPANCY');
    expect(label('Broken bottle')).toBe('Broken bottle');
    // QA D337: the API's DAMAGED / EXPIRED spellings are translated too.
    expect(label('DAMAGED: QA R6 ampoule cassée')).toBe(
      'PHARMACY.ADJ_REASON_DAMAGECOMMON.COLON QA R6 ampoule cassée',
    );
    expect(label('EXPIRED: QA R6')).toBe('PHARMACY.ADJ_REASON_EXPIRY_WRITE_OFFCOMMON.COLON QA R6');
    expect(c.reasonLabel({ reason: null } as never)).toBe('—');
  });

  it('QA D1051: a return against a fill reads its lot and date in the reader language, old and new rows', () => {
    const c = create();
    const translate = TestBed.inject(TranslateService);
    const seen: { key: string; params?: Record<string, unknown> }[] = [];
    spyOn(translate, 'instant').and.callFake(((key: string, params?: Record<string, unknown>) => {
      seen.push({ key, params });
      return key;
    }) as never);
    expect(c.reasonLabel({ reason: 'RETURN ref R16-MOR-01 2026-10-27T09:00' } as never)).toBe(
      'PHARMACY.LEDGER_REASON_RETURN_FILL',
    );
    expect(c.reasonLabel({ reason: 'RETURN ref lot R16-MOR-01 2026-10-27 09:00' } as never)).toBe(
      'PHARMACY.LEDGER_REASON_RETURN_FILL',
    );
    const fill = seen.filter((s) => s.key === 'PHARMACY.LEDGER_REASON_RETURN_FILL');
    expect(fill.map((s) => s.params?.['lot'])).toEqual(['R16-MOR-01', 'R16-MOR-01']);
    // Never the raw ISO timestamp, never the English word "lot" from the stored text.
    for (const s of fill) expect(String(s.params?.['date'])).not.toContain('2026-10-27');
    expect(c.reasonLabel({ reason: 'RETURN ref QA-RET-001' } as never)).toBe(
      'PHARMACY.LEDGER_REASON_RETURN',
    );
  });

  it('requires a lot to adjust a lot-tracked item', () => {
    const c = create();
    c.openForm();
    c.onTypeChange('ADJUSTMENT');
    c.onItemChange('item-1');
    // QA D1243: the first reason is DAMAGE, a write-off: it takes stock off.
    c.form.quantity = -5;
    c.adjustmentReasonCode = c.adjustmentReasonCodes[0];
    c.adjustmentNotes = 'count';

    c.submitForm();

    expect(toast.error).toHaveBeenCalledWith('PHARMACY.LOT_REQUIRED_FOR_MOVEMENT');
    expect(svc.recordTransaction).not.toHaveBeenCalled();
  });

  describe('QA D1243: a write-off reason only takes stock off', () => {
    for (const code of ['DAMAGE', 'EXPIRY_WRITE_OFF']) {
      it(`refuses a positive ${code} adjustment and sends nothing`, () => {
        const c = create();
        c.openForm();
        c.onTypeChange('ADJUSTMENT');
        c.onItemChange('item-1');
        c.form.stockLotId = 'lot-1';
        c.form.quantity = 2;
        c.adjustmentReasonCode = code;
        c.adjustmentNotes = 'Flacon cassé';
        expect(c.writeOffReason()).toBeTrue();

        c.submitForm();

        expect(toast.error).toHaveBeenCalledWith('PHARMACY.WRITE_OFF_REMOVES_ONLY');
        expect(svc.recordTransaction).not.toHaveBeenCalled();
      });
    }

    it('sends a negative DAMAGE adjustment, and a count variance either way', () => {
      const c = create();
      svc.recordTransaction.and.returnValue(of({}) as never);
      svc.listTransactionsByPharmacy.and.returnValue(of({}) as never);
      svc.listInventoryByPharmacy.and.returnValue(of({}) as never);
      c.openForm();
      c.onTypeChange('ADJUSTMENT');
      c.onItemChange('item-1');
      c.form.stockLotId = 'lot-1';
      c.form.quantity = -2;
      c.adjustmentReasonCode = 'CYCLE_COUNT_VARIANCE';
      expect(c.writeOffReason()).toBeFalse();
      c.adjustmentReasonCode = 'DAMAGE';
      c.adjustmentNotes = 'Flacon cassé';
      c.submitForm();
      expect(toast.error).not.toHaveBeenCalled();
      expect(svc.recordTransaction).toHaveBeenCalledTimes(1);
    });
  });

  describe('paired transfer returns', () => {
    const transfer = {
      transferId: 'transfer-77',
      stockLotId: 'lot-1',
      destinationName: 'Ward store',
      transferredAt: '2026-11-26T08:00:00',
      returnableQuantity: 6,
    };

    function open(c: StockAdjustmentComponent): void {
      svc.listReturnableTransfers.and.returnValue(of({ data: [transfer] }) as never);
      c.openForm();
      c.onTypeChange('RETURN');
      c.onItemChange('item-1');
      c.form.quantity = 2;
      c.returnCondition = 'INTACT';
    }

    it('selects the original transfer and lot and sends no request before explicit save', () => {
      const c = create();
      open(c);
      c.returnTransferId = transfer.transferId;
      c.onReturnTransferChange();
      expect(c.form.stockLotId).toBe('lot-1');
      expect(svc.recordTransaction).not.toHaveBeenCalled();
      svc.recordTransaction.and.returnValue(NEVER);
      c.submitForm();
      expect(svc.recordTransaction).toHaveBeenCalled();
      const request = svc.recordTransaction.calls.mostRecent().args[0];
      expect(request.referenceId).toBe(transfer.transferId);
      expect(request.reason).toContain(transfer.destinationName);
    });

    it('an internal transfer return never offers a patient refund', () => {
      const c = create();
      open(c);
      c.returnTransferId = transfer.transferId;
      c.onReturnTransferChange();
      svc.listInventoryByPharmacy.and.returnValue(of({ data: { content: [] } }) as never);
      svc.listTransactionsByPharmacy.and.returnValue(of({ data: { content: [] } }) as never);
      svc.listTransactionsByType.and.returnValue(of({ data: { content: [] } }) as never);
      svc.recordTransaction.and.returnValue(of({ data: { id: 'internal-return-77' } }) as never);
      c.submitForm();
      expect(svc.recordTransaction).toHaveBeenCalled();
      expect(c.refundPrompt()).toBeNull();
    });

    it('requires a real source choice rather than treating typed text as a transfer ID', () => {
      const c = create();
      open(c);
      c.returnReference = 'typed reference';
      c.submitForm();
      expect(svc.recordTransaction).not.toHaveBeenCalled();
      expect(toast.error).toHaveBeenCalledWith('PHARMACY.RETURN_SOURCE_REQUIRED');
    });

    it('refuses an excessive quantity and keeps patient dispenses and transfer choices exclusive', () => {
      const c = create();
      open(c);
      c.returnDispenseId = 'earlier-fill';
      c.returnTransferId = transfer.transferId;
      c.onReturnTransferChange();
      expect(c.returnDispenseId).toBe('');
      c.form.quantity = 7;
      c.submitForm();
      expect(svc.recordTransaction).not.toHaveBeenCalled();
      expect(toast.error).toHaveBeenCalledWith('PHARMACY.RETURN_EXCEEDS_TRANSFER');
    });

    it('a failed source lookup shows a refusal and remains retryable', () => {
      const c = create();
      svc.listReturnableTransfers.and.returnValue(
        throwError(() => new Error('Synthetic lookup failure')),
      );
      c.openForm();
      c.onTypeChange('RETURN');
      c.onItemChange('item-1');
      c.form.quantity = 1;
      c.returnReference = 'legacy';
      c.returnCondition = 'INTACT';
      c.submitForm();
      expect(c.returnableFailed()).toBeTrue();
      expect(svc.recordTransaction).not.toHaveBeenCalled();
      svc.listReturnableTransfers.and.returnValue(of({ data: [transfer] }) as never);
      c.loadReturnableDispenses();
      expect(c.returnableFailed()).toBeFalse();
      expect(c.returnableTransfers()).toEqual([transfer]);
    });
  });

  describe('QA D932: a return is booked against its dispense', () => {
    const fill = {
      dispenseId: '3105c0de-1111-4000-8000-000000000000',
      patientName: 'Awa Ouedraogo',
      dispensedAt: '2026-10-27T09:00:00',
      quantityDispensed: 10,
      returnableQuantity: 6,
      lotNumber: 'R16-MOR-01',
    };

    function openReturn(c: StockAdjustmentComponent): void {
      svc.listReturnableDispenses.and.returnValue(of({ data: [fill] }) as never);
      c.openForm();
      c.onTypeChange('RETURN');
      c.onItemChange('item-1');
      c.form.stockLotId = 'lot-1';
      c.onLotChange();
      c.form.quantity = 2;
      c.returnCondition = 'INTACT';
    }

    it('forgets the chosen dispense when the item changes', () => {
      const c = create();
      openReturn(c);
      c.returnDispenseId = fill.dispenseId;
      c.onItemChange('item-2');
      expect(c.returnDispenseId).toBe('');
    });

    it('lists the fills of the chosen item and lot', () => {
      const c = create();
      openReturn(c);
      expect(svc.listReturnableDispenses).toHaveBeenCalledWith('item-1', 'lot-1');
      expect(c.returnableDispenses()).toEqual([fill] as never);
    });

    it('sends the dispense as the reference, so the stock goes back on the lot', () => {
      const c = create();
      openReturn(c);
      svc.recordTransaction.and.returnValue(NEVER);
      c.returnDispenseId = fill.dispenseId;

      c.submitForm();

      const body = svc.recordTransaction.calls.mostRecent().args[0];
      expect(body.transactionType).toBe('RETURN');
      expect(body.referenceId).toBe(fill.dispenseId);
      expect(body.stockLotId).toBe('lot-1');
      // QA D992 class: the fill's lot and time, not a slice of its id (was 'RETURN ref D-3105c0de').
      // QA D1051: stored as data (lot, then the ISO minute); the words and the
      // date are put in the reader's language where it is shown (was
      // 'RETURN ref lot R16-MOR-01 2026-10-27 09:00': English "lot", raw date).
      expect(body.reason).toBe('RETURN ref R16-MOR-01 2026-10-27T09:00');
      // QA D3415: the state of the returned units is sent.
      expect(body.stockCondition).toBe('INTACT');
    });

    it('QA D3415: no state, no return; a damaged return is said to go to quarantine', () => {
      const c = create();
      openReturn(c);
      c.returnDispenseId = fill.dispenseId;
      c.returnCondition = null;
      c.submitForm();
      expect(toast.error).toHaveBeenCalledWith('PHARMACY.RETURN_CONDITION_REQUIRED');
      expect(svc.recordTransaction).not.toHaveBeenCalled();

      c.returnCondition = 'DAMAGED';
      expect(c.returnGoesToQuarantine()).toBeTrue();
      c.returnCondition = 'INTACT';
      expect(c.returnGoesToQuarantine()).toBeFalse();
    });

    it('QA D3409: the fills are listed before a lot is chosen, and choosing one takes its lot', () => {
      const c = create();
      svc.listReturnableDispenses.and.returnValue(
        of({ data: [{ ...fill, stockLotId: 'lot-1' }] }) as never,
      );
      c.openForm();
      c.onTypeChange('RETURN');
      c.onItemChange('item-1');
      expect(svc.listReturnableDispenses).toHaveBeenCalledWith('item-1', undefined);
      expect(c.returnableDispenses().length).toBe(1);

      c.returnDispenseId = fill.dispenseId;
      c.onReturnDispenseChange();
      expect(c.form.stockLotId).toBe('lot-1');
    });

    it('asks for the dispense when one exists and none is chosen, and caps the quantity', () => {
      const c = create();
      openReturn(c);
      c.submitForm();
      expect(toast.error).toHaveBeenCalledWith('PHARMACY.RETURN_DISPENSE_REQUIRED');

      c.returnDispenseId = fill.dispenseId;
      c.form.quantity = 7;
      c.submitForm();
      expect(toast.error).toHaveBeenCalledWith('PHARMACY.RETURN_EXCEEDS_DISPENSE');
      expect(svc.recordTransaction).not.toHaveBeenCalled();
    });
  });

  describe('QA D3415: the quarantine and its witnessed destruction', () => {
    const morphine = {
      inventoryItemId: 'item-mor',
      medicationName: 'Morphine 10 mg/mL',
      unit: 'ampoule',
      controlled: true,
      stockLotId: 'lot-mor',
      lotNumber: 'LOT-MOR-1',
      heldQuantity: 2,
    };

    it('lists what is held and destroys a controlled drug only with a witness and a reason', () => {
      const c = create();
      svc.listQuarantine.and.returnValue(of({ data: [morphine] }) as never);
      svc.destroyQuarantined.and.returnValue(of({ data: {} }) as never);
      svc.listTransactionsByPharmacy.and.returnValue(of({ data: { content: [] } }) as never);
      c.selectedPharmacyId = 'ph-1';
      c.loadQuarantine();
      expect(c.quarantine()).toEqual([morphine]);

      c.openDestroy(morphine);
      c.destroyQuantity = 3;
      c.confirmDestroy();
      expect(svc.destroyQuarantined).not.toHaveBeenCalled();

      c.destroyQuantity = 2;
      c.destroyReason = 'Incinéré';
      c.confirmDestroy();
      expect(svc.destroyQuarantined).not.toHaveBeenCalled();

      c.destroyWitness = 'user-2';
      c.confirmDestroy();
      expect(svc.destroyQuarantined).toHaveBeenCalledWith({
        inventoryItemId: 'item-mor',
        stockLotId: 'lot-mor',
        quantity: 2,
        reason: 'Incinéré',
        witnessedBy: 'user-2',
      });
      expect(toast.success).toHaveBeenCalledWith('PHARMACY.QUARANTINE.DESTROYED');
    });
  });

  it('QA D3415: an empty destruction form marks its required fields and sends nothing', () => {
    create();
    svc.listQuarantine.and.returnValue(
      of({
        data: [
          {
            inventoryItemId: 'item-mor',
            medicationName: 'Morphine 10 mg/mL',
            unit: 'ampoule',
            controlled: true,
            stockLotId: 'lot-mor',
            lotNumber: 'LOT-MOR-1',
            heldQuantity: 2,
          },
        ],
      }) as never,
    );
    const fixture = TestBed.createComponent(StockAdjustmentComponent);
    document.body.appendChild(fixture.nativeElement);
    const c = fixture.componentInstance;
    c.selectedPharmacyId = 'ph-1';
    c.loadQuarantine();
    fixture.detectChanges();
    c.openDestroy(c.quarantine()[0]);
    fixture.detectChanges();
    expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="destroy-confirm"]',
      sent: svc.destroyQuarantined,
    });
    fixture.nativeElement.remove();
  });

  describe('QA D964: a transfer sends first expired, first out', () => {
    function withTwoLots(c: StockAdjustmentComponent): void {
      svc.listLotsByInventoryItem.and.returnValue(
        of({
          data: {
            content: [
              { id: 'long', lotNumber: 'LONG', remainingQuantity: 200, expiryDate: '2099-06-30' },
              { id: 'court', lotNumber: 'COURT', remainingQuantity: 100, expiryDate: '2098-01-31' },
            ],
          },
        } as unknown as LotsPage),
      );
      c.openForm();
      c.form.quantity = 20;
      c.onTypeChange('TRANSFER');
      c.onItemChange('item-1');
      c.transferDestinationPharmacyId = 'ph-2';
    }

    it('pre-selects the shortest-dated lot', () => {
      const c = create();
      withTwoLots(c);
      expect(c.form.stockLotId).toBe('court');
      expect(c.earlierTransferLot()).toBeUndefined();
    });

    it('a later-dated lot needs a reason, which is sent with the transfer', () => {
      const c = create();
      withTwoLots(c);
      c.form.stockLotId = 'long';

      c.submitForm();
      expect(toast.error).toHaveBeenCalledWith('PHARMACY.FEFO_REASON_REQUIRED');
      expect(svc.recordTransaction).not.toHaveBeenCalled();

      svc.recordTransaction.and.returnValue(NEVER);
      c.fefoReason = ' lot court promis au CSPS ';
      c.submitForm();
      expect(svc.recordTransaction.calls.mostRecent().args[0].fefoOverrideReason).toBe(
        'lot court promis au CSPS',
      );
    });

    it('the ledger reads the FEFO override after the destination', () => {
      const c = create();
      expect(c.reasonLabel({ reason: 'TRANSFER to CSPS | FEFO: promis' } as never)).toBe(
        'PHARMACY.LEDGER_REASON_TRANSFER · PHARMACY.LEDGER_REASON_FEFO',
      );
    });
  });
});
