import { isSingular, pluralKey } from './plural-key';

describe('pluralKey (QA D4194)', () => {
  it('takes the singular for 0 and 1 in French, the plural from 2', () => {
    expect(pluralKey('K', 0, 'fr-FR')).toBe('K_ONE');
    expect(pluralKey('K', 1, 'fr-FR')).toBe('K_ONE');
    expect(pluralKey('K', 1.5, 'fr-FR')).toBe('K_ONE');
    expect(pluralKey('K', 2, 'fr-FR')).toBe('K');
    expect(pluralKey('K', 90, 'fr-FR')).toBe('K');
  });

  it('takes the singular for 1 only in English and Spanish', () => {
    for (const locale of ['en-US', 'es-ES']) {
      expect(pluralKey('K', 0, locale)).toBe('K');
      expect(pluralKey('K', 1, locale)).toBe('K_ONE');
      expect(pluralKey('K', 2, locale)).toBe('K');
    }
  });

  it('reads a negative count as its size, and a value that is no number as a plural', () => {
    expect(isSingular(-1, 'fr-FR')).toBeTrue();
    expect(isSingular(-4, 'fr-FR')).toBeFalse();
    expect(isSingular(Number.NaN, 'fr-FR')).toBeFalse();
  });
});
