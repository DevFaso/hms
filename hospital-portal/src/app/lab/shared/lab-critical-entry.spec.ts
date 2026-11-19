import { criticalAtEntry, parseEnteredNumber } from './lab-critical-entry';

describe('criticalAtEntry (r63 D2312)', () => {
  const potassium = [{ unit: 'mmol/L', criticalLow: 2.5, criticalHigh: 6.5 }];

  it('warns beyond the configured critical limits, with a decimal comma', () => {
    expect(criticalAtEntry('6,9', potassium, 'mmol/L')).toBe('CRITICAL_HIGH');
    expect(criticalAtEntry('2.1', potassium, 'mmol/L')).toBe('CRITICAL_LOW');
    expect(criticalAtEntry('4.2', potassium, 'mmol/L')).toBeNull();
  });

  it('reads a blank unit and a unit-less range as the test unit', () => {
    expect(criticalAtEntry('7', [{ criticalHigh: 6.5 }], '', 'mmol/L')).toBe('CRITICAL_HIGH');
  });

  it('does not compare across units, and has nothing to say without limits or a number', () => {
    expect(criticalAtEntry('7', potassium, 'mEq/L')).toBeNull();
    expect(criticalAtEntry('7', [{ unit: 'mmol/L' }], 'mmol/L')).toBeNull();
    expect(criticalAtEntry('Hémolysé', potassium, 'mmol/L')).toBeNull();
    expect(criticalAtEntry('7', [], 'mmol/L')).toBeNull();
  });

  it('parses comparators to their bound', () => {
    expect(parseEnteredNumber('> 7')).toBe(7);
    expect(parseEnteredNumber('abc')).toBeNull();
  });
});
