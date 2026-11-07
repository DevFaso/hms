import { TranslateService } from '@ngx-translate/core';
import { hasValue, joinReasons, missingRequiredMessage } from './required-fields';

describe('missingRequiredMessage (QA D890)', () => {
  const translate = {
    instant: (key: string, params?: Record<string, string>) =>
      params ? `${key}|${params['fields']}` : `label:${key}`,
  } as unknown as TranslateService;

  it('names every empty required field, in form order', () => {
    expect(
      missingRequiredMessage(translate, [
        { labelKey: 'A', value: '' },
        { labelKey: 'B', value: 'filled' },
        { labelKey: 'C', value: '   ' },
        { labelKey: 'D', value: null },
      ]),
    ).toBe('COMMON.MISSING_REQUIRED_FIELDS|label:A, label:C, label:D');
  });

  it('is null when the form is complete', () => {
    expect(missingRequiredMessage(translate, [{ labelKey: 'A', value: 'x' }])).toBeNull();
  });

  it('counts numbers and booleans as given', () => {
    expect(hasValue(0)).toBeTrue();
    expect(hasValue(false)).toBeTrue();
    expect(hasValue(undefined)).toBeFalse();
  });
});

describe('joinReasons (QA D1256 class, round 27)', () => {
  it('says every reason at once, each as a sentence; one reason as given; none as null', () => {
    expect(joinReasons(['Choisissez le témoin', false, null, 'Vérifiez les cinq bons.'])).toBe(
      'Choisissez le témoin. Vérifiez les cinq bons.',
    );
    expect(joinReasons(['Saisissez la quantité', false])).toBe('Saisissez la quantité');
    expect(joinReasons([false, undefined, ''])).toBeNull();
  });
});
