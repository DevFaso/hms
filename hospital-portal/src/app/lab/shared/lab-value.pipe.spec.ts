import { formatLabNumber } from './lab-value.pipe';

describe('formatLabNumber (QA D1002)', () => {
  it('writes a lab value with the reader decimal mark, keeping its precision', () => {
    expect(formatLabNumber('0.32', 'fr')).toBe('0,32');
    expect(formatLabNumber('0.320', 'fr')).toBe('0,320');
    expect(formatLabNumber('0.32', 'en')).toBe('0.32');
    expect(formatLabNumber('7,9', 'en')).toBe('7.9');
    expect(formatLabNumber(6.5, 'fr')).toBe('6,5');
    expect(formatLabNumber('12', 'fr')).toBe('12');
  });

  it('keeps a comparator and shows a non-numeric answer as typed', () => {
    expect(formatLabNumber('< 0.5', 'fr')).toBe('< 0,5');
    expect(formatLabNumber('Positif', 'fr')).toBe('Positif');
    expect(formatLabNumber('1/64', 'fr')).toBe('1/64');
    expect(formatLabNumber(null, 'fr')).toBe('');
  });
});
