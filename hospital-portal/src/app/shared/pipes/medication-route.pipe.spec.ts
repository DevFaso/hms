import { TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  MEDICATION_ROUTE_CODES,
  MedicationRoutePipe,
  canonicalRoute,
} from './medication-route.pipe';

describe('medicationRoute (QA D950)', () => {
  it('reads codes, words and form labels as one route code', () => {
    expect(canonicalRoute('INTRAVENOUS')).toBe('IV');
    expect(canonicalRoute('Intraveineuse')).toBe('IV');
    expect(canonicalRoute('intraveineuse (IV)')).toBe('IV');
    expect(canonicalRoute('ORAL')).toBe('PO');
    expect(canonicalRoute('sous-cutanée')).toBe('SC');
    expect(canonicalRoute('SUBCUTANEOUS')).toBe('SC');
    expect(canonicalRoute('dans le thé')).toBeNull();
    expect(canonicalRoute('')).toBeNull();
    expect(canonicalRoute(null)).toBeNull();
    for (const code of MEDICATION_ROUTE_CODES) expect(canonicalRoute(code)).toBe(code);
  });

  it('shows a known route in the reader language and anything else as written', () => {
    TestBed.configureTestingModule({ imports: [TranslateModule.forRoot()] });
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', { PRESCRIPTIONS: { ROUTES: { IV: 'Intraveineuse (IV)' } } });
    translate.use('fr');
    const pipe = TestBed.runInInjectionContext(() => new MedicationRoutePipe());
    expect(pipe.transform('INTRAVENOUS')).toBe('Intraveineuse (IV)');
    expect(pipe.transform('dans le thé')).toBe('dans le thé');
    expect(pipe.transform(null)).toBe('');
    pipe.ngOnDestroy();
  });
});
