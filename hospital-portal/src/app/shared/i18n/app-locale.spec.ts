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
    expect(plain(formatCfa(4000, 'XOF', 'es-ES'))).toMatch(/^4\.?000 F CFA$/);
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

  // QA D1008 (pinned): es-ES groups from five digits (minimumGroupingDigits 2),
  // so a 4-digit amount reads "4000 F CFA" while French reads "4 000 F CFA" —
  // and the `\.?` above let both through. This pins the CURRENT behaviour on
  // purpose — formatCfa leaves the grouping to Intl's locale default — in a
  // way that does not depend on the browser's CLDR data: it compares with
  // Intl's own default output. The fix (`useGrouping: 'always'`, on another
  // branch) makes formatCfa differ from that default for es-ES and must flip
  // this expectation in the same PR, the way a guard's allowlist entry is
  // deleted with its fix. docs/qa/regression-guards.md lists it.
  it("D1008 (pinned until fixed): formatCfa still leaves a 4-digit Spanish amount to Intl's default grouping", () => {
    const intlDefault = (n: number) =>
      `${new Intl.NumberFormat('es-ES', { maximumFractionDigits: 0 }).format(n)} F CFA`;
    expect(plain(formatCfa(4000, 'XOF', 'es-ES'))).toBe(plain(intlDefault(4000)));
    expect(plain(formatCfa(1500, 'XOF', 'es-ES'))).toBe(plain(intlDefault(1500)));
    expect(plain(formatCfa(15000, 'XOF', 'es-ES'))).toMatch(/^15[.\s]000 F CFA$/);
  });
});
