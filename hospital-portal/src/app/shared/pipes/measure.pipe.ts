import { Pipe, PipeTransform } from '@angular/core';
import { currentLocale } from '../i18n/app-locale';

/**
 * QA D811: a missing temperature read "—°C", and a birth weight read "7000 g"
 * on the summary but "7 000 g" in the alert next to it.
 * `{{ value | measure: '°C' }}` gives the number in the active language's
 * format followed by its unit ("37,5 °C", "7 000 g"), and a bare "—" when
 * nothing was measured, never a dash with a unit.
 */
@Pipe({ name: 'measure', standalone: true })
export class MeasurePipe implements PipeTransform {
  transform(value: number | null | undefined, unit: string, maxFractionDigits = 1): string {
    if (value === null || value === undefined || Number.isNaN(value)) return '—';
    const number = new Intl.NumberFormat(currentLocale(), {
      maximumFractionDigits: maxFractionDigits,
    }).format(value);
    return unit === '%' && currentLocale().startsWith('en') ? `${number}%` : `${number} ${unit}`;
  }
}
