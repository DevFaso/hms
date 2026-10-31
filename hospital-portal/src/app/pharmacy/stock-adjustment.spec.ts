import { TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { Observable, of, NEVER } from 'rxjs';
import { StockAdjustmentComponent } from './stock-adjustment';
import { PharmacyService } from '../services/pharmacy.service';
import { ToastService } from '../core/toast.service';

type LotsPage =
  ReturnType<PharmacyService['listLotsByInventoryItem']> extends Observable<infer T> ? T : never;
type PharmaciesPage =
  ReturnType<PharmacyService['listPharmacies']> extends Observable<infer T> ? T : never;

describe('StockAdjustmentComponent (D82 / D108)', () => {
  let svc: jasmine.SpyObj<PharmacyService>;
  let toast: jasmine.SpyObj<ToastService>;

  function create(): StockAdjustmentComponent {
    svc = jasmine.createSpyObj<PharmacyService>('PharmacyService', [
      'listPharmacies',
      'listInventoryByPharmacy',
      'listTransactionsByPharmacy',
      'listTransactionsByType',
      'listLotsByInventoryItem',
      'recordTransaction',
      'receiveStock',
      'listWitnessCandidates',
      'listReturnableDispenses',
    ]);
    svc.listReturnableDispenses.and.returnValue(of({ data: [] }) as never);
    svc.listWitnessCandidates.and.returnValue(
      of({ data: [{ userId: 'u-2', displayName: 'Awa Traore' }] } as unknown as ReturnType<
        PharmacyService['listWitnessCandidates']
      > extends Observable<infer T>
        ? T
        : never),
    );
    svc.listPharmacies.and.returnValue(of({ content: [] } as unknown as PharmaciesPage));
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
      'PHARMACY.ADJ_REASON_CYCLE_COUNT_VARIANCE : recount',
    );
    expect(label('CONTROLLED_DISCREPANCY')).toBe('PHARMACY.ADJ_REASON_CONTROLLED_DISCREPANCY');
    expect(label('Broken bottle')).toBe('Broken bottle');
    // QA D337: the API's DAMAGED / EXPIRED spellings are translated too.
    expect(label('DAMAGED: QA R6 ampoule cassée')).toBe(
      'PHARMACY.ADJ_REASON_DAMAGE : QA R6 ampoule cassée',
    );
    expect(label('EXPIRED: QA R6')).toBe('PHARMACY.ADJ_REASON_EXPIRY_WRITE_OFF : QA R6');
    expect(c.reasonLabel({ reason: null } as never)).toBe('—');
  });

  it('requires a lot to adjust a lot-tracked item', () => {
    const c = create();
    c.openForm();
    c.onTypeChange('ADJUSTMENT');
    c.onItemChange('item-1');
    c.form.quantity = 5;
    c.adjustmentReasonCode = c.adjustmentReasonCodes[0];
    c.adjustmentNotes = 'count';

    c.submitForm();

    expect(toast.error).toHaveBeenCalledWith('PHARMACY.LOT_REQUIRED_FOR_MOVEMENT');
    expect(svc.recordTransaction).not.toHaveBeenCalled();
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
      expect(body.reason).toBe('RETURN ref D-3105c0de');
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
