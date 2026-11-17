import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { resetStoredLangForTests } from '../../shared/i18n/app-locale';
import fr from '../../../assets/i18n/fr.json';
import { PriceListImportComponent, priceListTemplate } from './price-list-import.component';
import { PRICE_LIST_CSV_HEADER, PriceListImportReport } from '../../services/price-list.service';
import { ToastService } from '../../core/toast.service';
import {
  expectEmptySubmitRefusal,
  expectFieldsMarked,
  fieldErrorsResponse,
} from '../../testing/form-refusal-behaviour';

const URL_IMPORT = '/billing/price-list/import';

/** Two rows of the fictional test clinic's grid, one of them wrong (README §6.1). */
const DRY_RUN_REFUSED: PriceListImportReport = {
  importId: 'imp-1',
  fileName: 'tarifs-test.csv',
  sha256: 'a'.repeat(64),
  result: 'DRY_RUN',
  alreadyImported: false,
  totalRows: 2,
  newItems: 1,
  newPrices: 0,
  unchanged: 0,
  refused: 1,
  rows: [
    {
      line: 2,
      code: 'CS-GEN',
      label: 'Consultation générale (médecin généraliste)',
      outcome: 'NEW_ITEM',
      price: 2000,
      effectiveDate: '2026-12-01',
      refusals: [],
    },
    {
      line: 3,
      code: 'LAB-FAUX',
      label: 'Test avec prix décimal',
      outcome: 'REFUSED',
      price: null,
      effectiveDate: '2026-12-01',
      refusals: [
        {
          column: 'prix',
          message: 'Saisissez le prix unitaire en francs CFA entiers, sans décimales.',
        },
      ],
    },
  ],
};

const DRY_RUN_CLEAN: PriceListImportReport = {
  ...DRY_RUN_REFUSED,
  totalRows: 1,
  refused: 0,
  rows: [DRY_RUN_REFUSED.rows[0]],
};

describe('PriceListImportComponent (billing-money plan PR 4)', () => {
  let fixture: ComponentFixture<PriceListImportComponent>;
  let http: HttpTestingController;
  let toast: jasmine.SpyObj<ToastService>;
  let previousLang: string | null;

  beforeEach(() => {
    previousLang = localStorage.getItem('lang');
    localStorage.setItem('lang', 'fr');
    resetStoredLangForTests();
    toast = jasmine.createSpyObj('ToastService', ['success', 'error', 'info', 'warning']);
    TestBed.configureTestingModule({
      imports: [PriceListImportComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ToastService, useValue: toast },
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

  function el(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function button(testId: string): HTMLButtonElement {
    return el().querySelector<HTMLButtonElement>(`[data-testid="${testId}"]`)!;
  }

  /** "Appliquer l’import" lets a click through (its guard holds no reason). */
  function applyEnabled(): boolean {
    return fixture.componentInstance.canApply() && !button('price-list-import-apply').disabled;
  }

  function pick(file: File): void {
    const input = el().querySelector<HTMLInputElement>('[data-testid="price-list-import-file"]')!;
    const files = new DataTransfer();
    files.items.add(file);
    input.files = files.files;
    input.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  function csv(name = 'tarifs-test.csv'): File {
    return new File([PRICE_LIST_CSV_HEADER + '\n'], name, { type: 'text/csv' });
  }

  it('shows the French labels; with no file and no check, neither button sends anything', () => {
    expect(el().textContent).toContain('Importer une grille (CSV)');
    expect(el().textContent).toContain('Modèle à télécharger');
    expect(button('price-list-import-check').textContent).toContain('Vérifier le fichier');
    // Pressed with no file: the picker is marked and nothing is sent, for both buttons.
    expect(
      expectEmptySubmitRefusal(fixture, { button: '[data-testid="price-list-import-check"]' }),
    ).toEqual(['pl-import-file']);
    expect(
      expectEmptySubmitRefusal(fixture, { button: '[data-testid="price-list-import-apply"]' }),
    ).toEqual(['pl-import-file']);
    http.expectNone(URL_IMPORT);
    expect(el().textContent).toContain('Vérifiez d’abord le fichier');
    expect(applyEnabled()).toBeFalse();
  });

  it('lists each refused row with its line, column and reason, and does not enable the apply', () => {
    pick(csv());
    button('price-list-import-check').click();
    const req = http.expectOne((r) => r.url === URL_IMPORT);
    expect(req.request.method).toBe('POST');
    expect(req.request.params.get('dryRun')).toBe('true');
    expect(req.request.params.get('replaceScheduled')).toBe('false');
    expect(req.request.body instanceof FormData).toBeTrue();
    req.flush(DRY_RUN_REFUSED);
    fixture.detectChanges();

    expect(el().querySelector('[data-testid="price-list-import-valid"]')!.textContent!.trim()).toBe(
      '1',
    );
    expect(
      el().querySelector('[data-testid="price-list-import-refused"]')!.textContent!.trim(),
    ).toBe('1');
    const refused = el().querySelector('[data-testid="price-list-import-row-3"]')!;
    expect(refused.textContent).toContain('LAB-FAUX');
    expect(refused.textContent).toContain('prix');
    expect(refused.textContent).toContain('Saisissez le prix unitaire en francs CFA entiers');
    expect(refused.textContent).toContain('Refusé');
    expect(el().querySelector('[data-testid="price-list-import-row-2"]')!.textContent).toContain(
      'Nouvel article',
    );
    expect(applyEnabled()).toBeFalse();
    button('price-list-import-apply').click();
    http.expectNone(URL_IMPORT);
  });

  it('enables the apply only after a clean check, and a new file or option takes it back', () => {
    pick(csv());
    button('price-list-import-check').click();
    http.expectOne((r) => r.url === URL_IMPORT).flush(DRY_RUN_CLEAN);
    fixture.detectChanges();
    expect(applyEnabled()).toBeTrue();

    const replace = el().querySelector<HTMLInputElement>(
      '[data-testid="price-list-import-replace"]',
    )!;
    replace.click();
    fixture.detectChanges();
    expect(applyEnabled()).withContext('the option changed: check again').toBeFalse();

    button('price-list-import-check').click();
    const again = http.expectOne((r) => r.url === URL_IMPORT);
    expect(again.request.params.get('replaceScheduled')).toBe('true');
    again.flush(DRY_RUN_CLEAN);
    fixture.detectChanges();
    expect(applyEnabled()).toBeTrue();

    pick(csv('autre.csv'));
    expect(applyEnabled()).withContext('another file: check again').toBeFalse();
  });

  it('applies with dryRun=false and tells the page', () => {
    const applied: PriceListImportReport[] = [];
    fixture.componentInstance.applied.subscribe((r) => applied.push(r));
    pick(csv());
    button('price-list-import-check').click();
    http.expectOne((r) => r.url === URL_IMPORT).flush(DRY_RUN_CLEAN);
    fixture.detectChanges();
    button('price-list-import-apply').click();
    const req = http.expectOne((r) => r.url === URL_IMPORT);
    expect(req.request.params.get('dryRun')).toBe('false');
    req.flush({ ...DRY_RUN_CLEAN, result: 'APPLIED' });
    fixture.detectChanges();
    expect(applied.length).toBe(1);
    expect(toast.success).toHaveBeenCalled();
    expect(applyEnabled()).withContext('applied once').toBeFalse();
  });

  it('shows the report of an apply refused as a whole (422)', () => {
    pick(csv());
    button('price-list-import-check').click();
    http.expectOne((r) => r.url === URL_IMPORT).flush(DRY_RUN_CLEAN);
    fixture.detectChanges();
    button('price-list-import-apply').click();
    http
      .expectOne((r) => r.url === URL_IMPORT)
      .flush(
        { ...DRY_RUN_REFUSED, result: 'REFUSED', importId: null },
        {
          status: 422,
          statusText: 'Unprocessable',
        },
      );
    fixture.detectChanges();
    expect(toast.error).toHaveBeenCalled();
    expect(el().querySelector('[data-testid="price-list-import-row-3"]')).not.toBeNull();
    expect(el().querySelector('[data-testid="price-list-import-row-2"]'))
      .withContext('only the refused rows are shown first')
      .toBeNull();
    expect(applyEnabled()).toBeFalse();
  });

  it('marks the file picker with the server’s reason when the whole file is refused', () => {
    pick(csv());
    button('price-list-import-check').click();
    const reason = 'La ligne d’en-tête n’a pas de colonne « prix ».';
    const refusal = fieldErrorsResponse({ file: reason });
    http
      .expectOne((r) => r.url === URL_IMPORT)
      .flush(refusal.error, { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();
    expectFieldsMarked(fixture, { 'pl-import-file': reason });
  });

  it('shows a refusal that names no field in the server’s words', () => {
    pick(csv());
    button('price-list-import-check').click();
    http
      .expectOne((r) => r.url === URL_IMPORT)
      .flush(
        {
          message:
            'Seuls l’administrateur de l’établissement et le comptable modifient la grille tarifaire.',
        },
        { status: 403, statusText: 'Forbidden' },
      );
    fixture.detectChanges();
    expect(
      el().querySelector('[data-testid="price-list-import-file-error"]')!.textContent,
    ).toContain('Seuls l’administrateur');
  });

  it('says another person created the same code when the apply meets a concurrent insert', () => {
    pick(csv());
    button('price-list-import-check').click();
    http.expectOne((r) => r.url === URL_IMPORT).flush(DRY_RUN_CLEAN);
    fixture.detectChanges();
    button('price-list-import-apply').click();
    const refusal = fieldErrorsResponse({
      code: 'Le code CS-GEN existe déjà dans la grille tarifaire.',
    });
    http
      .expectOne((r) => r.url === URL_IMPORT)
      .flush(refusal.error, { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();
    expect(
      el().querySelector('[data-testid="price-list-import-file-error"]')!.textContent,
    ).toContain('Un autre utilisateur vient de créer ce code');
  });

  it('refuses a file over 1 MB before sending it', () => {
    pick(new File([new Uint8Array(1_048_577)], 'gros.csv', { type: 'text/csv' }));
    expect(
      el().querySelector('[data-testid="price-list-import-file-error"]')!.textContent,
    ).toContain('Le fichier dépasse 1 Mo');
    button('price-list-import-check').click();
    http.expectNone(URL_IMPORT);
  });

  it('says when the file was already applied', () => {
    pick(csv());
    button('price-list-import-check').click();
    http
      .expectOne((r) => r.url === URL_IMPORT)
      .flush({ ...DRY_RUN_CLEAN, alreadyImported: true, alreadyImportedAt: '2026-11-15T09:00:00' });
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="price-list-import-already"]')!.textContent).toContain(
      'Ce fichier a déjà été importé le',
    );
  });

  it('offers a template with the import header, a BOM and CRLF', async () => {
    const bytes = new Uint8Array(await priceListTemplate().arrayBuffer());
    expect(Array.from(bytes.slice(0, 3))).toEqual([0xef, 0xbb, 0xbf]);
    expect(new TextDecoder().decode(bytes.slice(3))).toBe(PRICE_LIST_CSV_HEADER + '\r\n');
  });
});
