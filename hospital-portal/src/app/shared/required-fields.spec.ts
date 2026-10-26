import { TranslateService } from '@ngx-translate/core';
import { hasValue, missingRequiredMessage } from './required-fields';

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
