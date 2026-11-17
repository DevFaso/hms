import { Pipe, PipeTransform } from '@angular/core';
import { formatLabNumber, formatLabRange } from '../i18n/app-locale';

export { formatLabNumber } from '../i18n/app-locale';

/**
 * `{{ r.resultValue | labValue: r.resultUnit }}` gives "0,32 g/L" in French
 * and "0.32 g/L" in English: the value in the reader's format, a space, then
 * the unit; a bare "—" when there is no value, never a dash with a unit.
 * One formatter for every surface (QA D1002, D1106, D1116): formatLabNumber.
 */
@Pipe({ name: 'labValue', standalone: true, pure: false })
export class LabValuePipe implements PipeTransform {
  transform(value: string | number | null | undefined, unit?: string | null): string {
    const formatted = formatLabNumber(value);
    if (!formatted) return '—';
    const u = (unit ?? '').trim();
    return u ? `${formatted} ${u}` : formatted;
  }
}

/** `{{ r.referenceRange | labRange }}`: "12 – 15,5 g/dL" in French; "—" when there is none. */
@Pipe({ name: 'labRange', standalone: true, pure: false })
export class LabRangePipe implements PipeTransform {
  transform(range: string | null | undefined): string {
    return formatLabRange(range) || '—';
  }
}

/**
 * `{{ v.temperatureCelsius | localeNumber }}`: a measured number in the
 * reader's format with the precision it was recorded at ("37,9", "4,8"), for
 * a template that prints the unit itself (QA D1115). "—" when there is none.
 */
@Pipe({ name: 'localeNumber', standalone: true, pure: false })
export class LocaleNumberPipe implements PipeTransform {
  transform(value: string | number | null | undefined): string {
    return formatLabNumber(value) || '—';
  }
}
