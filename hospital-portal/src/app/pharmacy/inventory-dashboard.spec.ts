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

describe('InventoryDashboardComponent price column (QA D835)', () => {
  function render(unitPrice: number | null) {
    const svc = jasmine.createSpyObj<PharmacyService>('PharmacyService', [
      'listPharmacies',
      'listInventoryByPharmacy',
      'getReorderAlertsByPharmacy',
      'updateInventoryItem',
    ]);
    svc.listPharmacies.and.returnValue(
      of({ content: [{ id: 'ph-1', name: 'Pharmacie centrale' }] }) as never,
    );
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
              quantityOnHand: 500,
              reorderThreshold: 100,
              reorderQuantity: 500,
              unit: 'comprimé',
              unitPrice,
              active: true,
            },
          ],
          totalPages: 1,
        },
      }) as never,
    );
    svc.updateInventoryItem.and.returnValue(of({ data: { unitPrice: 30 } }) as never);
    TestBed.configureTestingModule({
      imports: [InventoryDashboardComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PharmacyService, useValue: svc },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']),
        },
        { provide: RoleContextService, useValue: {} },
      ],
    });
    const fixture = TestBed.createComponent(InventoryDashboardComponent);
    fixture.detectChanges();
    return { fixture, svc };
  }

  it('shows the selling price as text in the row, not only inside an input', () => {
    const { fixture } = render(25);
    const cell = fixture.nativeElement.querySelector('[data-testid="price-inv-1"]') as HTMLElement;
    expect(cell).not.toBeNull();
    expect(cell.textContent).toContain('25');
    expect(cell.textContent).toMatch(/F\sCFA/); // a non-breaking space joins the unit
    // The row's own text carries the price: what a reader (or a screen reader) gets.
    const row = cell.closest('tr') as HTMLElement;
    expect(row.textContent).toContain('25');
  });

  it('shows a dash for an unpriced item', () => {
    const { fixture } = render(null);
    const cell = fixture.nativeElement.querySelector('[data-testid="price-inv-1"]') as HTMLElement;
    expect(cell.textContent!.trim()).toBe('—');
  });

  it('opens the price box on the pencil and closes it once saved', () => {
    const { fixture, svc } = render(25);
    (
      fixture.nativeElement.querySelector('[data-testid="price-edit-inv-1"]') as HTMLElement
    ).click();
    fixture.detectChanges();
    const input = fixture.nativeElement.querySelector(
      '[data-testid="price-input-inv-1"]',
    ) as HTMLInputElement;
    expect(input.value).toBe('25');
    input.value = '30';
    input.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(svc.updateInventoryItem).toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('[data-testid="price-input-inv-1"]')).toBeNull();
    expect(
      (fixture.nativeElement.querySelector('[data-testid="price-inv-1"]') as HTMLElement)
        .textContent,
    ).toContain('30');
  });
});

describe('InventoryDashboardComponent reorder trigger (QA D965)', () => {
  function create(role: string) {
    const svc = jasmine.createSpyObj<PharmacyService>('PharmacyService', [
      'listPharmacies',
      'getReorderAlertsByPharmacy',
    ]);
    svc.listPharmacies.and.returnValue(of({ content: [] }) as never);
    svc.getReorderAlertsByPharmacy.and.returnValue(of({ data: [] }) as never);
    TestBed.configureTestingModule({
      imports: [InventoryDashboardComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PharmacyService, useValue: svc },
        { provide: ToastService, useValue: jasmine.createSpyObj('ToastService', ['success', 'error']) },
        {
          provide: RoleContextService,
          useValue: { hasAnyActiveRoleExactly: (roles: string[]) => roles.includes(role) },
        },
      ],
    });
    const fixture = TestBed.createComponent(InventoryDashboardComponent);
    fixture.componentInstance.activeTab.set('alerts');
    fixture.detectChanges();
    return fixture;
  }

  it('hides "Trigger alerts" from the pharmacist, whom the endpoint refuses', () => {
    const fixture = create('ROLE_PHARMACIST');
    expect(fixture.nativeElement.querySelector('[data-testid="trigger-reorder-alerts"]')).toBeNull();
  });

  it('offers it to the store manager', () => {
    const fixture = create('ROLE_STORE_MANAGER');
    expect(
      fixture.nativeElement.querySelector('[data-testid="trigger-reorder-alerts"]'),
    ).not.toBeNull();
  });
});
