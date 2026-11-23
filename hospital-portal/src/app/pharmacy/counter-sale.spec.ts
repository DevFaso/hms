import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { CounterSaleComponent } from './counter-sale';
import { PharmacyService } from '../services/pharmacy.service';
import { PharmacySaleService } from '../services/pharmacy-sale.service';
import { AuthService } from '../auth/auth.service';
import { ToastService } from '../core/toast.service';
import { expectEmptySubmitRefusal, expectFieldsMarked } from '../testing/form-refusal-behaviour';
import { closeScreens, openScreen } from '../testing/screen-frame';
import { TranslateService } from '@ngx-translate/core';
import fr from '../../assets/i18n/fr.json';

/**
 * QA D2309: the pharmacy had no counter-sale screen although POST /pharmacy-sales
 * existed. The screen sells only active, uncontrolled items in stock, sends
 * no price (the server prices the sale), a quantity in packs as packs, one
 * request key per open sale, and shows the server's refusal.
 */
describe('CounterSaleComponent', () => {
  let fixture: ComponentFixture<CounterSaleComponent>;
  let component: CounterSaleComponent;
  let pharmacy: jasmine.SpyObj<PharmacyService>;
  let sales: jasmine.SpyObj<PharmacySaleService>;
  let toast: jasmine.SpyObj<ToastService>;

  const items = [
    {
      id: 'inv-par',
      medicationCatalogItemId: 'cat-par',
      medicationName: 'Paracétamol 500 mg',
      quantityOnHand: 120,
      unit: 'comprimé',
      unitPrice: 25,
      unitsPerPack: 10,
      packUnit: 'plaquette',
      fullPacksOnHand: 12,
      looseUnitsOnHand: 0,
      active: true,
    },
    {
      id: 'inv-mor',
      medicationCatalogItemId: 'cat-mor',
      medicationName: 'Morphine',
      quantityOnHand: 40,
      unitPrice: 500,
      controlled: true,
      active: true,
    },
    {
      id: 'inv-amo',
      medicationCatalogItemId: 'cat-amo',
      medicationName: 'Amoxicilline',
      quantityOnHand: 0,
      unitPrice: 100,
      active: true,
    },
    {
      id: 'inv-old',
      medicationCatalogItemId: 'cat-old',
      medicationName: 'Ancien sirop',
      quantityOnHand: 5,
      unitPrice: 100,
      active: false,
    },
  ];
  const lots = [
    {
      id: 'lot-late',
      inventoryItemId: 'inv-par',
      lotNumber: 'L-2029',
      expiryDate: '2029-06-30',
      remainingQuantity: 60,
    },
    {
      id: 'lot-early',
      inventoryItemId: 'inv-par',
      lotNumber: 'L-2028',
      expiryDate: '2028-01-31',
      remainingQuantity: 60,
    },
  ];

  const saleResponse = {
    id: 'sale-1',
    pharmacyId: 'ph-1',
    hospitalId: 'h-1',
    saleDate: '2026-11-22T10:00:00',
    paymentMethod: 'CASH',
    totalAmount: 500,
    currency: 'XOF',
    lines: [
      {
        id: 'sl-1',
        medicationCatalogItemId: 'cat-par',
        medicationName: 'Paracétamol 500 mg',
        quantity: 20,
        unitPrice: 25,
        lineTotal: 500,
      },
    ],
  };

  const refusal = (message: string, fieldErrors?: Record<string, string>) =>
    new HttpErrorResponse({
      status: 400,
      error: { message, ...(fieldErrors ? { fieldErrors } : {}) },
    });

  beforeEach(async () => {
    pharmacy = jasmine.createSpyObj('PharmacyService', [
      'listPharmacies',
      'listInventoryByPharmacy',
      'listLotsByInventoryItem',
    ]);
    sales = jasmine.createSpyObj('PharmacySaleService', ['createSale', 'listByPharmacy']);
    toast = jasmine.createSpyObj('ToastService', ['success', 'error', 'warning']);
    pharmacy.listPharmacies.and.returnValue(
      of({ content: [{ id: 'ph-1', hospitalId: 'h-1', name: 'Pharmacie centrale' }] } as any),
    );
    pharmacy.listInventoryByPharmacy.and.returnValue(of({ data: { content: items } } as any));
    pharmacy.listLotsByInventoryItem.and.returnValue(of({ data: { content: lots } } as any));
    sales.listByPharmacy.and.returnValue(of({ content: [] } as any));
    sales.createSale.and.returnValue(of(saleResponse as any));

    await TestBed.configureTestingModule({
      imports: [CounterSaleComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PharmacyService, useValue: pharmacy },
        { provide: PharmacySaleService, useValue: sales },
        { provide: ToastService, useValue: toast },
        {
          provide: AuthService,
          useValue: { getHospitalId: () => 'h-1', getUserId: () => 'u-1' },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(CounterSaleComponent);
    component = fixture.componentInstance;
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
  });

  afterEach(() => fixture.nativeElement.remove());

  const el = (): HTMLElement => fixture.nativeElement;
  const sentRequest = () => sales.createSale.calls.mostRecent().args[0];
  const addParacetamol = () => {
    component.addItem(component.sellableItems().find((i) => i.id === 'inv-par')!);
    fixture.detectChanges();
    return component.lines()[component.lines().length - 1];
  };

  it('hides controlled, out-of-stock and inactive items from the picker', () => {
    expect(component.sellableItems().map((i) => i.id)).toEqual(['inv-par']);
    const list = el().querySelector('[data-testid="item-results"]')!;
    expect(list.textContent).toContain('Paracétamol 500 mg');
    expect(list.textContent).not.toContain('Morphine');
    expect(list.textContent).not.toContain('Amoxicilline');
    expect(list.textContent).not.toContain('Ancien sirop');
  });

  it('preselects the first-expired lot and sends no unit price', () => {
    const line = addParacetamol();
    expect(line.stockLotId).toBe('lot-early');
    line.quantity = 4;
    component.submit();
    const req = sentRequest();
    expect(req.pharmacyId).toBe('ph-1');
    expect(req.hospitalId).toBe('h-1');
    expect(req.paymentMethod).toBe('CASH');
    expect(req.lines).toEqual([
      { medicationCatalogItemId: 'cat-par', stockLotId: 'lot-early', quantity: 4 },
    ]);
    expect(req.lines.every((l) => !('unitPrice' in l))).toBeTrue();
    expect(req.mobileMoneyOperator).toBeUndefined();
  });

  it('sends a quantity in packs with inPacks and previews it in units', () => {
    const line = addParacetamol();
    line.quantity = 2;
    line.inPacks = true;
    expect(component.lineUnits(line)).toBe(20);
    expect(component.lineTotal(line)).toBe(500);
    component.submit();
    expect(sentRequest().lines[0]).toEqual(
      jasmine.objectContaining({ quantity: 2, inPacks: true }),
    );
  });

  it('asks a reason for a later lot and sends it', () => {
    const line = addParacetamol();
    line.stockLotId = 'lot-late';
    component.onLotChange(line);
    fixture.detectChanges();
    component.submit();
    fixture.detectChanges();
    expect(sales.createSale).not.toHaveBeenCalled();
    const reason = el().querySelector<HTMLInputElement>(`#${component.lineFefoId(line)}`)!;
    expect(reason.getAttribute('aria-invalid')).toBe('true');

    line.fefoOverrideReason = 'Lot ouvert au comptoir';
    component.submit();
    expect(sentRequest().lines[0]).toEqual(
      jasmine.objectContaining({
        stockLotId: 'lot-late',
        fefoOverrideReason: 'Lot ouvert au comptoir',
      }),
    );
  });

  it('refuses mobile money without an operator and a reference', () => {
    addParacetamol();
    component.paymentMethod = 'MOBILE_MONEY';
    fixture.detectChanges();
    component.submit();
    fixture.detectChanges();
    expect(sales.createSale).not.toHaveBeenCalled();
    expect(el().querySelector('#saleOperator')!.getAttribute('aria-invalid')).toBe('true');
    expect(el().querySelector('#saleRef')!.getAttribute('aria-invalid')).toBe('true');

    component.mobileMoneyOperator = 'ORANGE_MONEY';
    component.referenceNumber = ' OM2611.1234 ';
    component.submit();
    expect(sentRequest()).toEqual(
      jasmine.objectContaining({
        paymentMethod: 'MOBILE_MONEY',
        mobileMoneyOperator: 'ORANGE_MONEY',
        referenceNumber: 'OM2611.1234',
      }),
    );
  });

  it('refuses an empty basket without calling the server', () => {
    const marked = expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="sale-submit"]',
      alsoMarked: ['saleItemSearch'],
      sent: sales.createSale,
    });
    expect(marked).toContain('saleItemSearch');
  });

  it('reuses the request key on a retry and renews it for a new sale', () => {
    const firstKey = component.clientRequestId;
    expect(firstKey).toBeTruthy();
    addParacetamol();
    sales.createSale.and.returnValue(throwError(() => refusal('Stock insuffisant')));
    component.submit();
    expect(sentRequest().clientRequestId).toBe(firstKey);

    sales.createSale.and.returnValue(of(saleResponse as any));
    component.submit();
    expect(sales.createSale).toHaveBeenCalledTimes(2);
    expect(sentRequest().clientRequestId).toBe(firstKey);
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="sale-receipt"]')).not.toBeNull();
    expect(toast.success).toHaveBeenCalled();

    el().querySelector<HTMLButtonElement>('[data-testid="new-sale"]')!.click();
    fixture.detectChanges();
    expect(component.receipt()).toBeNull();
    expect(component.lines()).toEqual([]);
    expect(component.clientRequestId).toBeTruthy();
    expect(component.clientRequestId).not.toBe(firstKey);
  });

  it('review of #1285: a sale whose answer was lost is retried exactly, whatever the basket says since', fakeAsync(() => {
    const line = addParacetamol();
    line.quantity = 2;
    sales.createSale.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 0, statusText: 'Unknown Error' })),
    );
    component.submit();
    tick(5000);
    const first = sentRequest();
    expect(component.unsettledRequest()).toEqual(first);

    // The basket is frozen: nothing is added or removed, and an edit is not sent.
    component.removeLine(line);
    expect(component.lines().length).toBe(1);
    line.quantity = 9;
    sales.createSale.and.returnValue(of(saleResponse as any));
    component.submit();
    tick(5000);
    expect(sentRequest()).toBe(first);
    expect(sentRequest().lines[0].quantity).toBe(2);
    expect(component.unsettledRequest()).toBeNull();
  }));

  it('review of #1285: the pharmacy cannot change while a sale waits for its answer', fakeAsync(() => {
    addParacetamol();
    sales.createSale.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 0, statusText: 'Unknown Error' })),
    );
    component.submit();
    tick(5000);
    const pharmacyId = component.selectedPharmacyId;
    fixture.detectChanges();
    tick();
    expect(el().querySelector<HTMLSelectElement>('#salePharmacy')!.disabled).toBeTrue();

    component.selectedPharmacyId = 'another-pharmacy';
    component.onPharmacyChange();
    expect(component.selectedPharmacyId).toBe(pharmacyId);
    expect(component.lines().length).toBe(1);
  }));

  it("shows the server's refusal and marks the field it names", () => {
    const line = addParacetamol();
    sales.createSale.and.returnValue(
      throwError(() =>
        refusal('Le lot L-2028 ne contient pas assez de stock.', {
          stockLotId: 'Le lot L-2028 ne contient pas assez de stock.',
        }),
      ),
    );
    component.submit();
    fixture.detectChanges();
    expect(component.saving()).toBeFalse();
    expect(el().querySelector('[data-testid="sale-error"]')!.textContent).toContain(
      'Le lot L-2028 ne contient pas assez de stock.',
    );
    expect(toast.error).toHaveBeenCalledWith('Le lot L-2028 ne contient pas assez de stock.');
    expectFieldsMarked(fixture, {
      [component.lineLotId(line)]: 'Le lot L-2028 ne contient pas assez de stock.',
    });
  });

  it("lists today's sales of the pharmacy, newest first, and says when there are more", () => {
    expect(sales.listByPharmacy).toHaveBeenCalledWith('ph-1', 0, 50, [
      'saleDate,desc',
      'createdAt,desc',
    ]);
    const now = new Date();
    const pad = (n: number) => String(n).padStart(2, '0');
    const todayUtc = `${now.getUTCFullYear()}-${pad(now.getUTCMonth() + 1)}-${pad(now.getUTCDate())}T${pad(now.getUTCHours())}:${pad(now.getUTCMinutes())}:00`;
    sales.listByPharmacy.and.returnValue(
      of({ content: [{ ...saleResponse, saleDate: todayUtc }], totalPages: 3 } as any),
    );
    component.loadTodaySales();
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="today-sales"]')!.textContent).toContain(
      'Paracétamol 500 mg',
    );
    expect(el().querySelector('[data-testid="today-more"]')).not.toBeNull();
  });

  /* ── r71: D3550 (layout at 768 px) and D3551 (unit plurals) ───────── */

  describe('the article list at every width (QA D3550)', () => {
    /**
     * A window of that width (a media query follows the window, not a box),
     * less the shell: the sidebar and the content padding at 1366 and 1024, the
     * gutter alone at 768 and 390 where the sidebar is an overlay.
     */
    async function listOn(screenWidth: number): Promise<HTMLElement> {
      const screen = await openScreen(screenWidth);
      const host = screen.createElement('div');
      host.style.width = `${screenWidth >= 1024 ? screenWidth - 310 : screenWidth - 32}px`;
      screen.body.appendChild(host);
      host.appendChild(fixture.nativeElement);
      return host;
    }

    afterEach(closeScreens);

    for (const width of [390, 768, 1024, 1366]) {
      it(`keeps each article's name readable at ${width} px`, async () => {
        const host = await listOn(width);
        const info = host.querySelector<HTMLElement>('.item-info')!;
        const name = host.querySelector<HTMLElement>('.item-name')!;
        expect(info.getBoundingClientRect().width)
          .withContext('the text box beside the « Ajouter » button')
          .toBeGreaterThanOrEqual(150);
        const lineHeight = parseFloat(getComputedStyle(name).lineHeight) || 24;
        expect(name.getBoundingClientRect().height)
          .withContext('« Paracétamol 500 mg » on one or two lines, not one letter per line')
          .toBeLessThanOrEqual(lineHeight * 2 + 1);
        const row = host.querySelector<HTMLElement>('.item-result')!;
        expect(row.scrollWidth).toBeLessThanOrEqual(row.clientWidth + 1);
      });
    }
  });

  it('writes every count with its unit in the plural (QA D3551)', () => {
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', fr, true);
    translate.use('fr');
    fixture.detectChanges();
    const text = (selector: string) =>
      el().querySelector(selector)!.textContent!.replace(/\s+/g, ' ');
    const meta = text('[data-testid="item-inv-par"] .item-meta');
    expect(meta).toContain('En stock : 120 comprimés');
    expect(meta).toContain('12 plaquettes + 0 comprimé');
    expect(meta).not.toContain('{{');
    addParacetamol();
    const basket = text('[data-testid="line-inv-par"]');
    expect(basket).toContain('1 plaquette = 10 comprimés');
    expect(basket).toContain('60 comprimés');
    expect(basket).not.toContain('{{');
  });
});
