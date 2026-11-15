import { signal } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';
import {
  LiveRequiredMessage,
  hasValue,
  joinReasons,
  missingRequiredMessage,
} from './required-fields';

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

describe('LiveRequiredMessage (QA D1940, round 54)', () => {
  const translate = {
    instant: (key: string, params?: Record<string, string>) =>
      params ? `${key}|${params['fields']}` : `label:${key}`,
  } as unknown as TranslateService;

  function setUp() {
    const values: Record<string, string> = { a: '', b: '' };
    const banner = signal<string | null>(null);
    const root = document.createElement('div');
    const input = document.createElement('input');
    root.appendChild(input);
    const live = new LiveRequiredMessage(
      translate,
      () => [
        { labelKey: 'A', value: values['a'] },
        { labelKey: 'B', value: values['b'] },
      ],
      banner,
    );
    const edit = (key: string, value: string) => {
      values[key] = value;
      input.dispatchEvent(new Event('input', { bubbles: true }));
    };
    return { banner, root, live, edit };
  }

  it('names only the fields still blank as they are filled, and clears once none is', () => {
    const { banner, root, live, edit } = setUp();
    expect(live.check(root)).toBe('COMMON.MISSING_REQUIRED_FIELDS|label:A, label:B');
    expect(banner()).toBe('COMMON.MISSING_REQUIRED_FIELDS|label:A, label:B');
    edit('a', 'x');
    expect(banner()).toBe('COMMON.MISSING_REQUIRED_FIELDS|label:B');
    edit('b', 'y');
    expect(banner()).toBeNull();
    live.stop();
  });

  it('leaves another message the form put in the banner since (a server error)', () => {
    const { banner, root, live, edit } = setUp();
    live.check(root);
    banner.set('Le serveur a refusé la demande.');
    edit('a', 'x');
    expect(banner()).toBe('Le serveur a refusé la demande.');
    live.stop();
  });

  it('is null and leaves the banner alone when the form is complete', () => {
    const { banner, root, live, edit } = setUp();
    edit('a', 'x');
    edit('b', 'y');
    banner.set('kept');
    expect(live.check(root)).toBeNull();
    expect(banner()).toBe('kept');
  });
});
