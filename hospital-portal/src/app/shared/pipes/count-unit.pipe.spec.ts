import { countUnit } from './count-unit.pipe';

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
