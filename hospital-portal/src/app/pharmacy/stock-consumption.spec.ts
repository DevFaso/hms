import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { StockConsumptionComponent, csvCell } from './stock-consumption';
import { StockConsumptionReport, StockCountService } from '../services/stock-count.service';
import { ToastService } from '../core/toast.service';

const report: StockConsumptionReport = {
  from: '2026-10-29',
  to: '2026-11-27',
  items: [
    {
      inventoryItemId: 'i-1',
      medicationName: 'Amoxicilline 500 mg',
      form: 'CAPSULE',
      unit: 'gélule',
      issuedQuantity: 51,
      returnedQuantity: 3,
      unitHistoryComparable: true,
      sourceMovements: 9,
    },
    {
      inventoryItemId: 'i-1',
      medicationName: 'Amoxicilline 500 mg',
      form: 'CAPSULE',
      unit: null,
      issuedQuantity: 14,
      returnedQuantity: 0,
      unitHistoryComparable: false,
      sourceMovements: 4,
    },
    {
      inventoryItemId: 'i-2',
      medicationName: 'Métronidazole 250 mg',
      unit: 'comprimé',
      issuedQuantity: 1,
      returnedQuantity: 0,
      unitHistoryComparable: true,
      sourceMovements: 1,
    },
  ],
};

/** QA D4183 (r81): the consumption report had no screen; QA D4192: old movements were a blank line. */
describe('StockConsumptionComponent (QA D4183, D4192)', () => {
  let api: jasmine.SpyObj<StockCountService>;
  let toast: jasmine.SpyObj<ToastService>;
  let fixture: ComponentFixture<StockConsumptionComponent>;
  let el: HTMLElement;

  function render(): StockConsumptionComponent {
    api = jasmine.createSpyObj<StockCountService>('StockCountService', ['stores', 'consumption']);
    api.stores.and.returnValue(of([{ pharmacyId: 'p-1', pharmacyName: 'Pharmacie principale' }]));
    api.consumption.and.returnValue(of(report));
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']);
    TestBed.configureTestingModule({
      imports: [StockConsumptionComponent, TranslateModule.forRoot()],
      providers: [
        { provide: StockCountService, useValue: api },
        { provide: ToastService, useValue: toast },
      ],
    });
    fixture = TestBed.createComponent(StockConsumptionComponent);
    fixture.detectChanges();
    el = fixture.nativeElement as HTMLElement;
    return fixture.componentInstance;
  }

  it('reads the last thirty days of the first store, and lists each medicine with its unit', () => {
    const c = render();
    expect(api.consumption).toHaveBeenCalledOnceWith('p-1', c.from, c.to);
    const days = (Date.parse(c.to) - Date.parse(c.from)) / 86_400_000;
    expect(days).toBe(29);
    const rows = Array.from(el.querySelectorAll('[data-testid="consumption-row"]')).map(
      (row) => row.textContent ?? '',
    );
    expect(rows.length).toBe(3);
    expect(rows[0]).toContain('Amoxicilline 500 mg');
    expect(rows[0]).toContain('51 gélules');
    expect(rows[0]).toContain('48 gélules');
    // One tablet stays in the singular.
    expect(rows[2]).toContain('1 comprimé');
    expect(rows[2]).not.toContain('1 comprimés');
  });

  it('QA D4192: movements older than the unit snapshot show their quantities and say the unit was not recorded', () => {
    render();
    const old = el.querySelectorAll('[data-testid="consumption-row"]')[1];
    expect(old.querySelector('[data-testid="consumption-unit-unknown"]')).not.toBeNull();
    const cells = Array.from(old.querySelectorAll('td')).map((cell) => cell.textContent!.trim());
    expect(cells).toEqual(['14', '0', '14', '4']);
  });

  it('filters by medicine, and reads again when the store or the period changes', () => {
    const c = render();
    c.search = 'métro';
    expect(c.visible().map((line) => line.medicationName)).toEqual(['Métronidazole 250 mg']);
    c.from = '2026-11-01';
    c.load();
    expect(api.consumption).toHaveBeenCalledWith('p-1', '2026-11-01', c.to);
  });

  it('shows the server’s refusal of a period it does not take', () => {
    const c = render();
    api.consumption.and.returnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 400,
            error: { message: 'Choisissez une période valide de 366 jours au maximum.' },
          }),
      ),
    );
    c.from = '2020-01-01';
    c.load();
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="consumption-error"]')!.textContent).toContain(
      'Choisissez une période valide de 366 jours au maximum.',
    );
  });

  it('exports what is shown as a CSV file a spreadsheet opens, old movements labelled', async () => {
    const c = render();
    let saved: Blob | undefined;
    spyOn(URL, 'createObjectURL').and.callFake((blob: Blob | MediaSource) => {
      saved = blob as Blob;
      return 'blob:x';
    });
    spyOn(URL, 'revokeObjectURL');
    spyOn(HTMLAnchorElement.prototype, 'click');
    c.exportCsv();
    const bytes = new Uint8Array(await saved!.arrayBuffer());
    // The UTF-8 mark comes first: the accents are read by a spreadsheet.
    expect(Array.from(bytes.slice(0, 3))).toEqual([0xef, 0xbb, 0xbf]);
    const text = new TextDecoder().decode(bytes);
    expect(text).toContain('Pharmacie principale');
    expect(text).toContain('Amoxicilline 500 mg;PHARMACY.FORM_OPTION.CAPSULE;gélule;51;3;48;9');
    expect(text).toContain('STOCK_CONSUMPTION.UNIT_NOT_RECORDED;14;0;14;4');
  });

  it('quotes a CSV cell that holds a separator and never lets a cell start a formula', () => {
    expect(csvCell('a;b')).toBe('"a;b"');
    expect(csvCell('dit "x"')).toBe('"dit ""x"""');
    expect(csvCell('=SOMME(A1)')).toBe("'=SOMME(A1)");
    expect(csvCell(-4)).toBe('-4');
    expect(csvCell(null)).toBe('');
  });
});
