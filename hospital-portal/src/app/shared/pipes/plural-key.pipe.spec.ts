import { pluralKey } from './plural-key.pipe';
import en from '../../../assets/i18n/en.json';
import fr from '../../../assets/i18n/fr.json';
import es from '../../../assets/i18n/es.json';

describe('pluralKey (QA D4194)', () => {
  it('French: 0 and 1 are singular, 2 and more plural', () => {
    expect(pluralKey(0, 'K', 'fr-FR')).toBe('K_ONE');
    expect(pluralKey(1, 'K', 'fr-FR')).toBe('K_ONE');
    expect(pluralKey(1.5, 'K', 'fr-FR')).toBe('K_ONE');
    expect(pluralKey(2, 'K', 'fr-FR')).toBe('K');
    expect(pluralKey(17, 'K', 'fr-FR')).toBe('K');
  });

  it('English and Spanish: only 1 is singular', () => {
    for (const locale of ['en-US', 'es-ES']) {
      expect(pluralKey(0, 'K', locale)).toBe('K');
      expect(pluralKey(1, 'K', locale)).toBe('K_ONE');
      expect(pluralKey(2, 'K', locale)).toBe('K');
    }
  });

  it('a count that is not a number takes the plural', () => {
    expect(pluralKey(null, 'K', 'fr-FR')).toBe('K');
    expect(pluralKey(undefined, 'K', 'fr-FR')).toBe('K');
    expect(pluralKey('', 'K', 'fr-FR')).toBe('K');
    expect(pluralKey('abc', 'K', 'fr-FR')).toBe('K');
    expect(pluralKey('1', 'K', 'fr-FR')).toBe('K_ONE');
  });
});

/**
 * QA D4194 (r81): the counted sentences of the dispensing, checkout and counter-sale screens. Each has its
 * plural and its singular in every language, neither written with « (s) », and the French singular really
 * is one (« 1 renouvellement restant », never « 1 renouvellements »).
 */
describe('counted sentences of the dispensing screens (QA D4194)', () => {
  const KEYS = [
    'PHARMACY.REFILL_REMAINING',
    'PHARMACY_WORKSPACE.LOADED_QUEUE',
    'R81_DISPENSING.CHECKOUT.FILLS_SHOWN',
    'R81_DISPENSING.HISTORY.SHOWN',
  ];
  const at = (bundle: unknown, key: string): unknown =>
    key
      .split('.')
      .reduce<unknown>(
        (node, part) =>
          node && typeof node === 'object' ? (node as Record<string, unknown>)[part] : undefined,
        bundle,
      );

  for (const [name, bundle] of [
    ['fr', fr],
    ['en', en],
    ['es', es],
  ] as const) {
    it(`${name}: every counted key has a plural and a singular, without « (s) »`, () => {
      for (const key of KEYS) {
        for (const variant of [key, `${key}_ONE`]) {
          const text = at(bundle, variant);
          expect(typeof text)
            .withContext(`${name} ${variant}`)
            .toBe('string');
          expect(String(text))
            .withContext(`${name} ${variant}`)
            .not.toMatch(/\(s\)|\(es\)/);
        }
      }
    });
  }

  it('the French singular and plural differ', () => {
    for (const key of KEYS) {
      expect(at(fr, `${key}_ONE`))
        .withContext(key)
        .not.toEqual(at(fr, key));
    }
  });
});
