import { DayMonthPipe } from './day-month.pipe';

/** QA D1140: "oct. 31" in French was the English order; the reader's order now. */
describe('DayMonthPipe (QA D1140)', () => {
  const pipe = new DayMonthPipe();
  const local = new Date(2026, 9, 31, 12, 0);

  it('writes the day first in French and Spanish, the month first in English', () => {
    expect(pipe.transform(local, 'fr-BF')).toMatch(/^31 oct/);
    expect(pipe.transform(local, 'es-ES')).toMatch(/^31 oct/);
    expect(pipe.transform(local, 'en-US')).toBe('Oct 31');
  });

  it('reads a bare calendar date as that local day, and nothing as nothing', () => {
    expect(pipe.transform('2026-10-31', 'fr-BF')).toMatch(/^31 oct/);
    expect(pipe.transform(null)).toBe('');
    expect(pipe.transform('not a date')).toBe('');
  });
});
