import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { resetStoredLangForTests } from '../../shared/i18n/app-locale';
import fr from '../../../assets/i18n/fr.json';
import { PRICE_LIST_CSV_COLUMNS, PriceListImportComponent } from './price-list-import.component';
import { PRICE_LIST_CSV_HEADER, PriceListImportReport } from '../../services/price-list.service';
import { ToastService } from '../../core/toast.service';

const URL_IMPORT = '/billing/price-list/import';

/**
 * QA r65 D2765: a new hospital imports the shipped test grid. Its lab lines are
 * linked to tests that do not exist (or are not active) there yet, so each is
 * refused with « liaison_cle : Aucun test de laboratoire… ». The dialog said
 * neither that the import is blocked, nor what to do about it.
 */
describe('PriceListImportComponent — a refused file says what blocks and what to do (QA r65 D2765)', () => {
  let fixture: ComponentFixture<PriceListImportComponent>;
  let http: HttpTestingController;
  let previousLang: string | null;

  const ok = {
    line: 2,
    code: 'CS-GEN',
    label: 'Consultation générale',
    outcome: 'NEW_ITEM' as const,
    price: 2000,
    effectiveDate: '2026-12-01',
    refusals: [],
  };
  const labRefused = {
    line: 3,
    code: 'LAB-NFS',
    label: 'Numération formule sanguine',
    outcome: 'REFUSED' as const,
    price: 3000,
    effectiveDate: '2026-12-01',
    refusals: [
      {
        column: 'liaison_cle',
        message: 'Aucun test de laboratoire TWP-NFS n’est disponible dans cet établissement.',
      },
    ],
  };
  const priceRefused = {
    ...labRefused,
    code: 'CS-FAUX',
    refusals: [{ column: 'prix', message: 'Saisissez le prix en francs CFA entiers.' }],
  };

  const report = (rows: PriceListImportReport['rows']): PriceListImportReport => ({
    importId: 'imp-1',
    fileName: 'tarifs-test.csv',
    sha256: 'a'.repeat(64),
    result: 'DRY_RUN',
    alreadyImported: false,
    totalRows: rows.length,
    newItems: rows.filter((r) => r.outcome === 'NEW_ITEM').length,
    newPrices: 0,
    unchanged: 0,
    refused: rows.filter((r) => r.outcome === 'REFUSED').length,
    rows,
  });

  beforeEach(() => {
    previousLang = localStorage.getItem('lang');
    localStorage.setItem('lang', 'fr');
    resetStoredLangForTests();
    TestBed.configureTestingModule({
      imports: [PriceListImportComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error', 'info', 'warning']),
        },
      ],
    });
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', fr);
    translate.use('fr');
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(PriceListImportComponent);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
  });

  afterEach(() => {
    if (previousLang === null) localStorage.removeItem('lang');
    else localStorage.setItem('lang', previousLang);
    resetStoredLangForTests();
    http.verify();
    (fixture.nativeElement as HTMLElement).remove();
  });

  const el = () => fixture.nativeElement as HTMLElement;
  const byId = (id: string) => el().querySelector<HTMLElement>(`[data-testid="${id}"]`);

  function check(rows: PriceListImportReport['rows']): void {
    const input = byId('price-list-import-file') as HTMLInputElement;
    const files = new DataTransfer();
    files.items.add(
      new File([PRICE_LIST_CSV_HEADER + '\n'], 'tarifs-test.csv', { type: 'text/csv' }),
    );
    input.files = files.files;
    input.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    byId('price-list-import-check')!.click();
    http.expectOne((r) => r.url === URL_IMPORT).flush(report(rows));
    fixture.detectChanges();
  }

  it('a refused lab link: the import is said to be blocked, with what to do first, in French', () => {
    check([ok, labRefused]);

    const blocked = byId('price-list-import-blocked')!;
    expect(blocked.getAttribute('role')).toBe('alert');
    expect(blocked.textContent).toContain('Lignes refusées : 1');
    expect(blocked.textContent).toContain('Rien n’est importé');
    const hint = byId('price-list-import-link-hint')!;
    expect(hint.textContent).toContain('Config. des tests labo');
    expect(hint.textContent).toContain('videz ces deux colonnes');
    // Nothing can be applied, and nothing more was sent.
    expect(fixture.componentInstance.canApply()).toBeFalse();
  });

  it('the refused column is named as the reader knows it, with the CSV header to look for', () => {
    check([ok, labRefused]);

    const cell = byId('price-list-import-row-3')!.textContent ?? '';
    expect(cell).toContain('Clé de liaison (liaison_cle)');
    expect(cell).toContain('Aucun test de laboratoire TWP-NFS');
  });

  it('a refusal that is not about a link shows the blocked notice without the link advice', () => {
    check([ok, priceRefused]);

    expect(byId('price-list-import-blocked')).not.toBeNull();
    expect(byId('price-list-import-link-hint')).toBeNull();
    expect(byId('price-list-import-row-3')!.textContent).toContain('Prix (prix)');
  });

  it('a clean file shows no blocked notice and can be applied', () => {
    check([ok]);

    expect(byId('price-list-import-blocked')).toBeNull();
    expect(fixture.componentInstance.canApply()).toBeTrue();
  });

  it('every column of the CSV header has a label in French, English and Spanish', async () => {
    expect([...PRICE_LIST_CSV_COLUMNS]).toEqual(PRICE_LIST_CSV_HEADER.split(';'));
    const en = (await import('../../../assets/i18n/en.json')).default;
    const es = (await import('../../../assets/i18n/es.json')).default;
    for (const bundle of [fr, en, es]) {
      const labels = (bundle as { PLATFORM_SETUP: { IMPORT: { COLUMN: Record<string, string> } } })
        .PLATFORM_SETUP.IMPORT.COLUMN;
      for (const column of PRICE_LIST_CSV_COLUMNS) {
        expect(labels[column]).withContext(column).toBeTruthy();
      }
    }
    // A column this build does not know is shown as the server named it.
    expect(fixture.componentInstance.columnLabel('nouvelle_colonne')).toBe('nouvelle_colonne');
  });
});
