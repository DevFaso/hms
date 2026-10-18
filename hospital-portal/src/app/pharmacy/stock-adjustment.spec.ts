import { TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { Observable, of } from 'rxjs';
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
    ]);
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
});
