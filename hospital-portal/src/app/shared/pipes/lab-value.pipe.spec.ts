import { formatLabNumber, LabRangePipe, LabValuePipe, LocaleNumberPipe } from './lab-value.pipe';
import { formatLabRange, formatMeasure } from '../i18n/app-locale';

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

describe('the shared locale number formatter (QA D1106, D1115, D1116)', () => {
  it('writes Spanish like French, and keeps integers whole', () => {
    expect(formatLabNumber('5.9', 'es-ES')).toBe('5,9');
    expect(formatLabNumber('0.40', 'es-ES')).toBe('0,40');
    expect(formatLabNumber(37, 'fr-FR')).toBe('37');
    expect(formatLabNumber(undefined, 'fr-FR')).toBe('');
  });

  it('localizes the decimals inside text that is not one number', () => {
    expect(formatLabNumber('3.5 - 5', 'fr-FR')).toBe('3,5 - 5');
    expect(formatLabNumber('3.5 - 5', 'en-US')).toBe('3.5 - 5');
  });

  it('writes a reference range with the reader separator and an en dash', () => {
    expect(formatLabRange('12 - 15.5 g/dL', 'fr-FR')).toBe('12 – 15,5 g/dL');
    expect(formatLabRange('0.7-0.99 g/L', 'es-ES')).toBe('0,7 – 0,99 g/L');
    expect(formatLabRange('3.5 - 5 mmol/L', 'en-US')).toBe('3.5 – 5 mmol/L');
    expect(formatLabRange(null, 'fr-FR')).toBe('');
  });

  it('formats a measurement with its unit, and a bare dash for nothing', () => {
    expect(formatMeasure(37.9, '°C', 1, 'fr-FR')).toBe('37,9 °C');
    expect(formatMeasure(4.8, 'kg', 1, 'es-ES')).toBe('4,8 kg');
    expect(formatMeasure(4.8, 'kg', 1, 'en-US')).toBe('4.8 kg');
    expect(formatMeasure(null, 'kg')).toBe('—');
  });

  it('the pipes route through it', () => {
    expect(new LabValuePipe().transform(null)).toBe('—');
    expect(new LabRangePipe().transform('')).toBe('—');
    expect(new LocaleNumberPipe().transform(null)).toBe('—');
  });
});
