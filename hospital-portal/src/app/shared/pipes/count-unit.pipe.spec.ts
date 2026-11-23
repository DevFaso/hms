import { countUnit } from './count-unit.pipe';
import en from '../../../assets/i18n/en.json';
import fr from '../../../assets/i18n/fr.json';
import es from '../../../assets/i18n/es.json';

describe('countUnit (QA D2317)', () => {
  it('puts a counted French unit in the plural from 2', () => {
    expect(countUnit(20, 'comprimé', 'fr-FR')).toBe('20 comprimés');
    expect(countUnit(1, 'comprimé', 'fr-FR')).toBe('1 comprimé');
    expect(countUnit(1.5, 'comprimé', 'fr-FR')).toBe('1.5 comprimé');
    expect(countUnit(2, 'ampoule', 'fr-FR')).toBe('2 ampoules');
    expect(countUnit(3, 'comprimé pelliculé', 'fr-FR')).toBe('3 comprimés pelliculés');
  });

  it('leaves measured units, abbreviations and plurals as stored', () => {
    expect(countUnit(20, 'mg', 'fr-FR')).toBe('20 mg');
    expect(countUnit(5, 'mL', 'fr-FR')).toBe('5 mL');
    expect(countUnit(2, 'UI', 'fr-FR')).toBe('2 UI');
    expect(countUnit(4, 'comprimés', 'fr-FR')).toBe('4 comprimés');
    expect(countUnit(4, 'comprimé(s)', 'fr-FR')).toBe('4 comprimé(s)');
    expect(countUnit(2, 'boîte de 10', 'fr-FR')).toBe('2 boîte de 10');
  });

  it('follows English counting and shows nothing for no unit', () => {
    expect(countUnit(0, 'tablet', 'en-US')).toBe('0 tablets');
    expect(countUnit(1, 'tablet', 'en-US')).toBe('1 tablet');
    expect(countUnit(12, '', 'fr-FR')).toBe('12');
    expect(countUnit(12, 'UNIT', 'fr-FR')).toBe('12');
  });
});

/**
 * QA D3551 (r71): the counter sale read « 192 gélule · 16 boîte + 0 gélule »: its strings wrote
 * the count and the unit as two placeholders side by side, so the D2317 plural never reached
 * them. The class guard: no string writes a count placeholder straight before a unit
 * placeholder; the screen passes `quantity | countUnit: unit` as one value instead. The audit
 * descriptions are exempt: they read back the unit word the server recorded in its English
 * audit text (audit-description-patterns.ts), not a count the portal formats.
 */
describe('count and unit written as one value (QA D3551)', () => {
  const COUNT_THEN_UNIT = /\{\{\s*\w+\s*\}\}\s*\{\{\s*(unit|packUnit|uom|quantityUnit)\s*\}\}/;
  const EXEMPT = /^AUDIT\./;

  function offenders(node: unknown, path: string, out: string[]): string[] {
    if (typeof node === 'string') {
      if (COUNT_THEN_UNIT.test(node) && !EXEMPT.test(path)) out.push(`${path}: ${node}`);
    } else if (node && typeof node === 'object') {
      for (const [key, value] of Object.entries(node)) {
        offenders(value, path ? `${path}.${key}` : key, out);
      }
    }
    return out;
  }

  for (const [language, bundle] of Object.entries({ en, fr, es })) {
    it(`${language}: no string puts a unit placeholder after a count placeholder`, () => {
      expect(offenders(bundle, '', [])).toEqual([]);
    });
  }
});
