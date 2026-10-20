import { formatDecimal } from './app-locale';

/** QA D434: the QC chart labels used toFixed and printed "1.235" in French. */
describe('formatDecimal (QA D434)', () => {
  it('writes the decimals with the locale separator', () => {
    expect(formatDecimal(1.2345, 3, 'fr-FR')).toBe('1,235');
    expect(formatDecimal(1.2345, 3, 'es-ES')).toBe('1,235');
    expect(formatDecimal(1.2345, 3, 'en-US')).toBe('1.235');
  });

  it('keeps the requested precision and no grouping', () => {
    expect(formatDecimal(12345.5, 1, 'fr-FR')).toBe('12345,5');
    expect(formatDecimal(2, 2, 'en-US')).toBe('2.00');
  });
});
