import { pluralKey, pluralSuffix } from './plural';

describe('plural (QA D4315)', () => {
  it('French: 0 and 1 are singular, 2 and more plural', () => {
    expect(pluralSuffix(0, 'fr-FR')).toBe('_ONE');
    expect(pluralSuffix(1, 'fr-FR')).toBe('_ONE');
    expect(pluralSuffix(2, 'fr-FR')).toBe('_OTHER');
    expect(pluralSuffix(460, 'fr-FR')).toBe('_OTHER');
  });

  it('English and Spanish: only 1 is singular', () => {
    for (const locale of ['en-US', 'es-ES']) {
      expect(pluralSuffix(0, locale)).toBe('_OTHER');
      expect(pluralSuffix(1, locale)).toBe('_ONE');
      expect(pluralSuffix(2, locale)).toBe('_OTHER');
    }
  });

  it('builds the key of a counted text', () => {
    expect(pluralKey('GOVERNANCE.DAYS', 0, 'fr-FR')).toBe('GOVERNANCE.DAYS_ONE');
    expect(pluralKey('GOVERNANCE.DAYS', 3, 'fr-FR')).toBe('GOVERNANCE.DAYS_OTHER');
  });
});
