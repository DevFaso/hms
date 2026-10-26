import { MeasurePipe } from './measure.pipe';
import { currentLocale } from '../i18n/app-locale';

describe('MeasurePipe (QA D811)', () => {
  const pipe = new MeasurePipe();

  it('shows a bare dash, never a dash with a unit, when nothing was measured', () => {
    expect(pipe.transform(null, '°C')).toBe('—');
    expect(pipe.transform(undefined, 'g')).toBe('—');
    expect(pipe.transform(Number.NaN, 'bpm')).toBe('—');
  });

  it('formats the number in the active language, as the alerts next to it do', () => {
    const expected = new Intl.NumberFormat(currentLocale(), { maximumFractionDigits: 1 }).format(
      7000,
    );
    expect(pipe.transform(7000, 'g')).toBe(`${expected} g`);
    expect(pipe.transform(37.5, '°C')).toBe(
      `${new Intl.NumberFormat(currentLocale(), { maximumFractionDigits: 1 }).format(37.5)} °C`,
    );
  });

  it('formats 7000 g with a thousands separator in French', () => {
    const french = new Intl.NumberFormat('fr-FR').format(7000);
    expect(french).not.toBe('7000');
    if (currentLocale() === 'fr-FR') {
      expect(pipe.transform(7000, 'g')).toBe(`${french} g`);
    }
  });
});
