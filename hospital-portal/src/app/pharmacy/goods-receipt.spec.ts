import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';
import { GoodsReceiptComponent } from './goods-receipt';
import { PharmacyService } from '../services/pharmacy.service';
import { ToastService } from '../core/toast.service';

/**
 * QA D655: POST /pharmacy/inventory/lots for morphine (10 units) was taken
 * with no witness. The receipt form now asks for one on a controlled item
 * and sends it; an ordinary item is received as before.
 */
describe('GoodsReceiptComponent', () => {
  let fixture: ComponentFixture<GoodsReceiptComponent>;
  let component: GoodsReceiptComponent;
  let svc: jasmine.SpyObj<PharmacyService>;
  let toast: jasmine.SpyObj<ToastService>;

  const items = [
    {
      id: 'inv-mor',
      medicationCatalogItemId: 'cat-mor',
      medicationName: 'Morphine',
      controlled: true,
    },
    { id: 'inv-par', medicationCatalogItemId: 'cat-par', medicationName: 'Paracetamol' },
  ];

  beforeEach(async () => {
    svc = jasmine.createSpyObj('PharmacyService', [
      'listPharmacies',
      'listInventoryByPharmacy',
      'listLotsByInventoryItem',
      'listWitnessCandidates',
      'receiveStock',
    ]);
    toast = jasmine.createSpyObj('ToastService', ['success', 'error']);
    svc.listPharmacies.and.returnValue(of({ content: [{ id: 'ph-1', name: 'Main' }] } as any));
    svc.listInventoryByPharmacy.and.returnValue(of({ data: { content: items } } as any));
    svc.listLotsByInventoryItem.and.returnValue(of({ data: { content: [] } } as any));
    svc.listWitnessCandidates.and.returnValue(
      of({ data: [{ userId: 'user-2', displayName: 'Pharmacist B' }] } as any),
    );
    svc.receiveStock.and.returnValue(of({ data: { id: 'lot-new' } } as any));

    await TestBed.configureTestingModule({
      imports: [GoodsReceiptComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PharmacyService, useValue: svc },
        { provide: ToastService, useValue: toast },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(GoodsReceiptComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  function fillForm(itemId: string): void {
    component.openReceiveForm();
    component.selectedInventoryItemId = itemId;
    component.form.lotNumber = 'QA-MOR-1';
    component.form.expiryDate = '2099-03-31';
    component.form.initialQuantity = 10;
    fixture.detectChanges();
  }

  it('QA D655: a controlled item shows the witness picker and is not received without one', () => {
    fillForm('inv-mor');
    const el: HTMLElement = fixture.nativeElement;
    const picker = el.querySelector<HTMLSelectElement>('#grWitness');
    expect(picker).not.toBeNull();
    expect(picker!.textContent).toContain('Pharmacist B');

    component.submitForm();
    expect(svc.receiveStock).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalledWith('PHARMACY.WITNESS_REQUIRED');

    component.form.witnessedBy = 'user-2';
    component.submitForm();
    expect(svc.receiveStock.calls.mostRecent().args[0].witnessedBy).toBe('user-2');
  });

  it('QA D655: an ordinary item has no witness picker and sends no witness', () => {
    fillForm('inv-par');
    expect(fixture.nativeElement.querySelector('#grWitness')).toBeNull();

    component.form.witnessedBy = 'user-2';
    component.submitForm();
    expect(svc.receiveStock.calls.mostRecent().args[0].witnessedBy).toBeUndefined();
  });

  it('QA D778: a negative unit cost is not sent', () => {
    fillForm('inv-par');
    component.form.unitCost = -10;
    component.submitForm();
    expect(svc.receiveStock).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalledWith('PHARMACY.UNIT_COST_NEGATIVE');
  });
});
