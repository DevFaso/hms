import { TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';
import { InventoryDashboardComponent } from './inventory-dashboard';
import { InventoryItemResponse, PharmacyService } from '../services/pharmacy.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';

describe('InventoryDashboardComponent savePrice (QA D264)', () => {
  it('does not send the stale quantityOnHand, so a stock movement cannot block the price', () => {
    const svc = jasmine.createSpyObj<PharmacyService>('PharmacyService', ['updateInventoryItem']);
    svc.updateInventoryItem.and.returnValue(of({ data: { unitPrice: 250 } } as any));
    const toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']);
    TestBed.configureTestingModule({
      imports: [InventoryDashboardComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PharmacyService, useValue: svc },
        { provide: ToastService, useValue: toast },
        { provide: RoleContextService, useValue: {} },
      ],
    });
    const c = TestBed.createComponent(InventoryDashboardComponent).componentInstance;
    const item = {
      id: 'inv-1',
      pharmacyId: 'ph-1',
      medicationCatalogItemId: 'med-1',
      quantityOnHand: 9,
      reorderThreshold: 5,
      reorderQuantity: 20,
      unit: 'tablet',
      unitPrice: null,
      active: true,
    } as unknown as InventoryItemResponse;

    c.savePrice(item, '250');

    const req = svc.updateInventoryItem.calls.mostRecent().args[1];
    expect('quantityOnHand' in req).toBeFalse();
    expect(req.unitPrice).toBe(250);
    expect(toast.success).toHaveBeenCalled();
  });
});
