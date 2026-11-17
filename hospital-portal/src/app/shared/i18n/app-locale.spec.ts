import { formatCfa, formatDecimal } from './app-locale';

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

/** QA D621: "XOF" in Spanish and "F CFA4,000" in English; French read "4 000 F CFA". */
describe('formatCfa (QA D621)', () => {
  const plain = (s: string) => s.replace(/[\u00a0\u202f]/g, ' ');

  it('reads "F CFA" after the amount in every language', () => {
    expect(plain(formatCfa(4000, 'XOF', 'fr-FR'))).toBe('4 000 F CFA');
    expect(plain(formatCfa(4000, 'XOF', 'en-US'))).toBe('4,000 F CFA');
    // QA D1008: four digits are grouped in Spanish too, like five.
    expect(plain(formatCfa(4000, 'XOF', 'es-ES'))).toBe('4.000 F CFA');
    expect(plain(formatCfa(8225, 'XOF', 'es-ES'))).toBe('8.225 F CFA');
    expect(plain(formatCfa(19875, 'XOF', 'es-ES'))).toBe('19.875 F CFA');
    for (const locale of ['fr-FR', 'en-US', 'es-ES']) {
      expect(formatCfa(4000, 'XOF', locale)).not.toContain('XOF');
    }
  });

  it('treats a missing currency as francs CFA and a missing amount as zero', () => {
    expect(plain(formatCfa(null, null, 'en-US'))).toBe('0 F CFA');
    expect(plain(formatCfa(1500, undefined, 'en-US'))).toBe('1,500 F CFA');
  });

  it('leaves another currency to Intl', () => {
    expect(formatCfa(10, 'EUR', 'en-US')).toContain('€');
  });

  // QA D1008 (was pinned by #970 until fixed): es-ES groups from five digits
  // by default (minimumGroupingDigits 2), so a 4-digit amount read "4000 F
  // CFA" while French read "4 000 F CFA". #973 passes useGrouping (always);
  // the pinned expectation (formatCfa equals Intl's default) is inverted here
  // in the combined branch, as docs/qa/regression-guards.md requires of the
  // fix: four digits are grouped like five, whatever the browser's default.
  it('D1008 (fixed): formatCfa groups a 4-digit Spanish amount like a 5-digit one', () => {
    expect(plain(formatCfa(4000, 'XOF', 'es-ES'))).toMatch(/^4[.\s]000 F CFA$/);
    expect(plain(formatCfa(1500, 'XOF', 'es-ES'))).toMatch(/^1[.\s]500 F CFA$/);
    expect(plain(formatCfa(15000, 'XOF', 'es-ES'))).toMatch(/^15[.\s]000 F CFA$/);
  });
});
