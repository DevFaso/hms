import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, Subject, throwError } from 'rxjs';
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

  it('marks a partial lot read and keeps the successful medication identifiable', () => {
    svc.listLotsByInventoryItem.and.callFake((id) =>
      id === 'inv-mor'
        ? (of({
            data: {
              content: [
                {
                  id: 'lot-a',
                  inventoryItemId: id,
                  lotNumber: 'CDX-A',
                  receivedDate: '2026-11-08',
                },
              ],
            },
          }) as any)
        : throwError(() => new Error('offline')),
    );
    component.selectedPharmacyId = 'ph-1';
    component.onPharmacyChange();
    expect(component.lotsPartial()).toBeTrue();
    expect(component.lotsFailed()).toBeFalse();
    expect(component.lotMedication(component.recentLots()[0])).toBe('Morphine');
    component.searchTerm = 'morphine';
    expect(component.visibleLots().length).toBe(1);
    component.searchTerm = 'missing';
    expect(component.visibleLots().length).toBe(0);
    expect(component.recentLots().length).toBe(1);
  });
  it('does not let a previous pharmacy lot request overwrite the new pharmacy', () => {
    const old = new Subject<any>();
    svc.listLotsByInventoryItem.and.returnValue(old);
    component.selectedPharmacyId = 'old';
    component.onPharmacyChange();
    svc.listInventoryByPharmacy.and.returnValue(of({ data: { content: [] } }) as any);
    component.selectedPharmacyId = 'new';
    component.onPharmacyChange();
    old.next({ data: { content: [{ id: 'old-lot' }] } });
    expect(component.recentLots()).toEqual([]);
    expect(component.lotsLoading()).toBeFalse();
  });
  it('distinguishes an unavailable lot list from an empty one and retries', () => {
    svc.listInventoryByPharmacy.and.returnValue(throwError(() => new Error('offline')));
    component.loadRecentLots();
    expect(component.lotsFailed()).toBeTrue();
    svc.listInventoryByPharmacy.and.returnValue(of({ data: { content: [] } }) as any);
    component.loadRecentLots();
    expect(component.lotsFailed()).toBeFalse();
    expect(component.recentLots()).toEqual([]);
  });

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
