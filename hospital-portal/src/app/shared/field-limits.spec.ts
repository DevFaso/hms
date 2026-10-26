import { TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { FieldLimit, firstOutOfRange, labelKeysOf, rangeMessage } from './field-limits';
import { labelledRefusalReason } from './refusal-reason';

const LIMITS: readonly FieldLimit[] = [
  { field: 'cervicalDilationCm', labelKey: 'DILATION', min: 0, max: 10 },
  { field: 'urineOutputMl', labelKey: 'URINE', min: 0 },
];

describe('field limits (QA D808)', () => {
  let translate: TranslateService;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [TranslateModule.forRoot()] });
    translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', {
      DILATION: 'Dilatation (cm)',
      URINE: 'Urine (ml)',
      FIELD_LIMITS: {
        RANGE: '{{field}} : la valeur doit être comprise entre {{min}} et {{max}}.',
        MIN: '{{field}} : la valeur doit être supérieure ou égale à {{min}}.',
        FIELD_REASON: '{{field}} : {{reason}}',
      },
    });
    translate.use('fr');
  });

  it('finds the first filled field outside its bounds', () => {
    expect(firstOutOfRange({ cervicalDilationCm: 15 }, LIMITS)?.field).toBe('cervicalDilationCm');
    expect(firstOutOfRange({ urineOutputMl: -5 }, LIMITS)?.field).toBe('urineOutputMl');
  });

  it('ignores blank fields and accepts the bounds themselves', () => {
    expect(firstOutOfRange({}, LIMITS)).toBeNull();
    expect(firstOutOfRange({ cervicalDilationCm: null, urineOutputMl: '' }, LIMITS)).toBeNull();
    expect(firstOutOfRange({ cervicalDilationCm: 10, urineOutputMl: 0 }, LIMITS)).toBeNull();
    expect(firstOutOfRange({ urineOutputMl: 99999 }, LIMITS)).toBeNull();
  });

  it('names the field in the message', () => {
    expect(rangeMessage(LIMITS[0], translate)).toBe(
      'Dilatation (cm) : la valeur doit être comprise entre 0 et 10.',
    );
    expect(rangeMessage(LIMITS[1], translate)).toBe(
      'Urine (ml) : la valeur doit être supérieure ou égale à 0.',
    );
  });

  it('labels a bean-validation reason, and leaves a business reason and a 5xx alone', () => {
    const labels = labelKeysOf(LIMITS);
    const validation = {
      status: 400,
      error: {
        error: 'Validation Failed',
        message: 'generic',
        fieldErrors: { cervicalDilationCm: 'doit être inférieur ou égal à 10', other: 'x' },
      },
    };
    expect(labelledRefusalReason(validation, translate, labels)).toBe(
      'Dilatation (cm) : doit être inférieur ou égal à 10. x',
    );
    const business = {
      status: 400,
      error: {
        error: 'Bad Request',
        message: 'La délivrance ne peut pas précéder la naissance.',
        fieldErrors: { cervicalDilationCm: 'La délivrance ne peut pas précéder la naissance.' },
      },
    };
    expect(labelledRefusalReason(business, translate, labels)).toBe(
      'La délivrance ne peut pas précéder la naissance.',
    );
    expect(
      labelledRefusalReason({ status: 500, error: { message: 'boom' } }, translate, labels),
    ).toBeNull();
  });
});
