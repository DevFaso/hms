import { encounterOptionLabel } from './encounter-option-label';

describe('encounterOptionLabel (QA D1309)', () => {
  it('tells four same-day visits apart by their time', () => {
    const at = (h: number) => new Date(2026, 10, 2, h, 15).toISOString().slice(0, 19);
    const labels = [8, 10, 13, 16].map((h) =>
      encounterOptionLabel({ encounterDate: at(h), departmentName: 'Médecine générale' }, 'fr-FR'),
    );
    expect(new Set(labels).size).toBe(4);
    expect(labels[0]).toBe(
      `${new Date(2026, 10, 2).toLocaleDateString('fr-FR')} · 08:15 · Médecine générale`,
    );
  });

  it('leaves out what the visit does not have', () => {
    expect(encounterOptionLabel({ departmentName: 'Pédiatrie' }, 'fr-FR')).toBe('Pédiatrie');
    expect(encounterOptionLabel({ encounterDate: 'not a date' }, 'fr-FR')).toBe('');
  });
});
