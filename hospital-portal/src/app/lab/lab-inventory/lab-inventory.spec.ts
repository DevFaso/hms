import { TestBed, ComponentFixture } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { LabInventoryComponent } from './lab-inventory';
import { AuthService } from '../../auth/auth.service';
import { LabInventoryItemResponse } from '../../services/lab-instrument.service';
import { PagedResponse } from '../../services/staff.service';
import { ConfirmService } from '../../shared/confirm-dialog/confirm.service';

function mockItem(overrides: Partial<LabInventoryItemResponse> = {}): LabInventoryItemResponse {
  return {
    id: 'item-1',
    name: 'Test Tubes',
    itemCode: 'TT-001',
    category: 'Consumables',
    hospitalId: 'h-1',
    hospitalName: 'Test Hospital',
    quantity: 500,
    unit: 'pcs',
    reorderThreshold: 100,
    supplier: 'MedSupply Co.',
    lotNumber: 'LOT-2025-A',
    expirationDate: '2026-06-01',
    lowStock: false,
    expired: false,
    createdAt: '2025-01-01T00:00:00Z',
    ...overrides,
  };
}

const PAGED: PagedResponse<LabInventoryItemResponse> = {
  content: [
    mockItem(),
    mockItem({
      id: 'item-2',
      name: 'Reagent X',
      itemCode: 'RX-001',
      category: 'Reagents',
      quantity: 5,
      reorderThreshold: 10,
      lowStock: true,
    }),
    mockItem({
      id: 'item-3',
      name: 'Expired Reagent',
      itemCode: 'ER-001',
      expired: true,
      expirationDate: '2024-01-01',
    }),
  ],
  totalElements: 3,
  totalPages: 1,
  number: 0,
  size: 20,
};

describe('LabInventoryComponent', () => {
  let fixture: ComponentFixture<LabInventoryComponent>;
  let component: LabInventoryComponent;
  let httpMock: HttpTestingController;
  let authSpy: jasmine.SpyObj<AuthService>;

  beforeEach(() => {
    authSpy = jasmine.createSpyObj('AuthService', ['getRoles', 'getHospitalId']);
    authSpy.getRoles.and.returnValue(['ROLE_LAB_DIRECTOR']);
    authSpy.getHospitalId.and.returnValue('h-1');

    TestBed.configureTestingModule({
      imports: [LabInventoryComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: AuthService, useValue: authSpy },
      ],
    });

    fixture = TestBed.createComponent(LabInventoryComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  function flushItems(data: PagedResponse<LabInventoryItemResponse> = PAGED): void {
    const req = httpMock.expectOne((r) => r.url.includes('/lab/inventory/hospital/h-1'));
    expect(req.request.method).toBe('GET');
    req.flush(data);
  }

  it('should create the component', () => {
    expect(component).toBeTruthy();
    fixture.detectChanges();
    flushItems();
  });

  it('should load inventory items on init', () => {
    fixture.detectChanges();
    flushItems();

    expect(component.loading()).toBeFalse();
    expect(component.items().length).toBe(3);
    expect(component.totalElements()).toBe(3);
  });

  it('distinguishes empty stock from low stock and displays the reorder threshold', () => {
    fixture.detectChanges();
    flushItems({
      ...PAGED,
      content: [mockItem({ name: 'Reagent', quantity: 0, reorderThreshold: 10, lowStock: true })],
    });
    fixture.detectChanges();
    const row = (fixture.nativeElement as HTMLElement).querySelector('tbody tr') as HTMLElement;
    expect(row.textContent).toContain('PHARMACY.OUT_OF_STOCK');
    expect(row.textContent).not.toContain('LAB_INVENTORY.LOW_STOCK');
    expect(row.querySelectorAll('td')[4].textContent).toContain('10');
  });

  it('pluralizes counted units while leaving measured units unchanged', () => {
    fixture.detectChanges();
    flushItems({
      ...PAGED,
      content: [
        mockItem({ id: 'vial', name: 'Reagent', quantity: 4, unit: 'flacon' }),
        mockItem({ id: 'weight', name: 'Powder', quantity: 4, unit: 'mg' }),
      ],
    });
    fixture.detectChanges();
    const rows = (fixture.nativeElement as HTMLElement).querySelectorAll('tbody tr');
    expect(rows[0].textContent).toContain('4 flacons');
    expect(rows[1].textContent).toContain('4 mg');
    expect(rows[1].textContent).not.toContain('4 mgs');
  });

  it('searches across inventory pages and retains the search on navigation', () => {
    fixture.detectChanges();
    flushItems();
    component.searchText = ' reagent ';
    component.search();
    let req = httpMock.expectOne((r) => r.url.includes('/lab/inventory/hospital/h-1'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ query: 'reagent' });
    expect(req.request.urlWithParams).not.toContain('reagent');
    expect(req.request.params.get('page')).toBe('0');
    req.flush({ ...PAGED, totalPages: 2 });
    component.goToPage(1);
    req = httpMock.expectOne((r) => r.url.includes('/lab/inventory/hospital/h-1'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ query: 'reagent' });
    expect(req.request.urlWithParams).not.toContain('reagent');
    expect(req.request.params.get('page')).toBe('1');
    req.flush({ ...PAGED, number: 1, totalPages: 2 });
  });

  it('searches with Enter and clears the filter with an empty search', () => {
    fixture.detectChanges();
    flushItems();
    const host = fixture.nativeElement as HTMLElement;
    const input = host.querySelector<HTMLInputElement>('#lab-inventory-search')!;
    input.value = 'reagent';
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    let req = httpMock.expectOne((r) => r.url.includes('/lab/inventory/hospital/h-1'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ query: 'reagent' });
    expect(req.request.urlWithParams).not.toContain('reagent');
    req.flush(PAGED);
    fixture.detectChanges();
    input.value = '';
    input.dispatchEvent(new Event('input'));
    host.querySelector<HTMLButtonElement>('[role="search"] button')!.click();
    req = httpMock.expectOne((r) => r.url.includes('/lab/inventory/hospital/h-1'));
    expect(req.request.method).toBe('GET');
    expect(req.request.params.has('search')).toBeFalse();
    req.flush(PAGED);
  });

  it('shows expiration dates in day/month/year order', () => {
    fixture.detectChanges();
    flushItems({ ...PAGED, content: [mockItem({ expirationDate: '2025-03-15' })] });
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('tbody tr')!.textContent).toContain(
      '15/03/2025',
    );
  });

  it('should show loading state initially', () => {
    fixture.detectChanges();
    expect(component.loading()).toBeTrue();
    flushItems();
  });

  it('should handle load error', () => {
    fixture.detectChanges();
    const req = httpMock.expectOne((r) => r.url.includes('/lab/inventory/hospital/h-1'));
    req.error(new ProgressEvent('error'));

    expect(component.loading()).toBeFalse();
    expect(component.error()).toBeTruthy();
    expect(component.items().length).toBe(0);
  });

  it('should set error if no hospital context', () => {
    authSpy.getHospitalId.and.returnValue(null);
    component.loadItems();

    expect(component.error()).toBeTruthy();
    expect(component.loading()).toBeFalse();
  });

  it('should allow management for lab director role', () => {
    expect(component.canManage()).toBeTrue();
    fixture.detectChanges();
    flushItems();
  });

  it('should allow management for hospital admin role', () => {
    authSpy.getRoles.and.returnValue(['ROLE_HOSPITAL_ADMIN']);
    fixture = TestBed.createComponent(LabInventoryComponent);
    component = fixture.componentInstance;

    expect(component.canManage()).toBeTrue();
    fixture.detectChanges();
    const req = httpMock.expectOne((r) => r.url.includes('/lab/inventory/hospital/h-1'));
    req.flush(PAGED);
  });

  it('should not allow management for lab technician role', () => {
    authSpy.getRoles.and.returnValue(['ROLE_LAB_TECHNICIAN']);
    fixture = TestBed.createComponent(LabInventoryComponent);
    component = fixture.componentInstance;

    expect(component.canManage()).toBeFalse();
    fixture.detectChanges();
    const req = httpMock.expectOne((r) => r.url.includes('/lab/inventory/hospital/h-1'));
    req.flush(PAGED);
  });

  it('should open create form', () => {
    fixture.detectChanges();
    flushItems();

    component.openCreate();

    expect(component.showForm()).toBeTrue();
    expect(component.editingId()).toBeNull();
    expect(component.form.name).toBe('');
    expect(component.form.quantity).toBe(0);
  });

  it('should open edit form with item data', () => {
    fixture.detectChanges();
    flushItems();

    const item = component.items()[0];
    component.openEdit(item);

    expect(component.showForm()).toBeTrue();
    expect(component.editingId()).toBe('item-1');
    expect(component.form.name).toBe('Test Tubes');
    expect(component.form.itemCode).toBe('TT-001');
    expect(component.form.quantity).toBe(500);
  });

  it('should cancel form', () => {
    fixture.detectChanges();
    flushItems();

    component.openCreate();
    component.form.name = 'Draft Item';
    component.cancelForm();

    expect(component.showForm()).toBeFalse();
    expect(component.editingId()).toBeNull();
    expect(component.form.name).toBe('');
  });

  // QA D1193 (round 22): an empty "Créer" posted and showed the server's raw
  // "ne doit pas être vide" (instruments) or a generic toast (inventory).
  it('QA D1193: an empty "Créer" sends nothing and names the missing fields', () => {
    fixture.detectChanges();
    flushItems();
    component.openCreate();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    host.querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
    fixture.detectChanges();
    httpMock.expectNone((r) => r.url.includes('/lab/inventory') && r.method === 'POST');
    const reason = host.querySelector('[data-testid="submit-guard-reason"]')?.textContent ?? '';
    expect(reason).toContain('COMMON.MISSING_REQUIRED_FIELDS');
    host
      .querySelectorAll('input[required]')
      .forEach((f) => expect(f.getAttribute('aria-invalid')).toBe('true'));
  });

  it('should save new inventory item', () => {
    fixture.detectChanges();
    flushItems();

    component.openCreate();
    component.form.name = 'New Reagent';
    component.form.itemCode = 'NR-001';
    component.form.quantity = 100;
    component.form.reorderThreshold = 20;
    component.save();

    const req = httpMock.expectOne(
      (r) => r.url.includes('/lab/inventory/hospital/h-1') && r.method === 'POST',
    );
    expect(req.request.body.name).toBe('New Reagent');
    expect(req.request.body.quantity).toBe(100);
    req.flush(mockItem({ id: 'item-new', name: 'New Reagent', itemCode: 'NR-001' }));

    expect(component.showForm()).toBeFalse();
    expect(component.saving()).toBeFalse();

    // Reload triggered
    flushItems();
  });

  it('should save updated inventory item', () => {
    fixture.detectChanges();
    flushItems();

    const item = component.items()[0];
    component.openEdit(item);
    component.form.quantity = 800;
    component.save();

    const req = httpMock.expectOne(
      (r) => r.url.includes('/lab/inventory/item-1') && r.method === 'PUT',
    );
    expect(req.request.body.quantity).toBe(800);
    req.flush(mockItem({ quantity: 800 }));

    expect(component.showForm()).toBeFalse();
    flushItems();
  });

  it('should handle save error', () => {
    fixture.detectChanges();
    flushItems();

    component.openCreate();
    component.form.name = 'Fail';
    component.form.itemCode = 'FAIL-001';
    component.save();

    const req = httpMock.expectOne((r) => r.method === 'POST');
    req.error(new ProgressEvent('error'));

    expect(component.saving()).toBeFalse();
  });

  it('should deactivate inventory item', async () => {
    fixture.detectChanges();
    flushItems();

    spyOn(TestBed.inject(ConfirmService), 'ask').and.resolveTo(true);

    const item = component.items()[0];
    await component.deactivate(item);

    const req = httpMock.expectOne(
      (r) => r.url.includes('/lab/inventory/item-1') && r.method === 'DELETE',
    );
    req.flush(null);

    // Reload triggered
    flushItems();
  });

  it('should not deactivate if user cancels confirm', async () => {
    fixture.detectChanges();
    flushItems();

    spyOn(TestBed.inject(ConfirmService), 'ask').and.resolveTo(false);

    const item = component.items()[0];
    await component.deactivate(item);

    // No HTTP request should fire
  });

  it('should handle deactivate error', async () => {
    fixture.detectChanges();
    flushItems();

    spyOn(TestBed.inject(ConfirmService), 'ask').and.resolveTo(true);

    const item = component.items()[0];
    await component.deactivate(item);

    const req = httpMock.expectOne((r) => r.method === 'DELETE');
    req.error(new ProgressEvent('error'));

    // Component stays operational
    expect(component.items().length).toBe(3);
  });

  it('should not save when no hospitalId', () => {
    fixture.detectChanges();
    flushItems();

    authSpy.getHospitalId.and.returnValue(null);
    component.openCreate();
    component.save();

    // No HTTP request
    expect(component.saving()).toBeFalse();
  });

  it('should refresh on reload', () => {
    fixture.detectChanges();
    flushItems();

    component.loadItems();
    flushItems();

    expect(component.items().length).toBe(3);
  });
});
