import { Pipe, PipeTransform } from '@angular/core';
import { currentLocale } from '../i18n/app-locale';

/** Measured units and abbreviations never take a plural « s » (« 20 mg », « 2 UI »). */
const INVARIABLE = new Set([
  'mg',
  'g',
  'kg',
  'µg',
  'mcg',
  'ml',
  'l',
  'cl',
  'dl',
  'ui',
  'iu',
  'u',
  'mmol',
  'meq',
  'cp',
  'gtt',
  'unit',
]);

/**
 * QA D2317 (r70): a counted pharmacy unit read « 20 comprimé » next to « 10 comprimés ».
 * `{{ quantity | countUnit: unit }}` gives the quantity and its unit, the unit in the plural
 * when the count asks for it (French: 2 and more; English and Spanish: anything but 1). A
 * measured unit (mg, mL, UI), an abbreviation, a unit holding a digit or a slash, or one already
 * in the plural (ending in s, x or z, or « (s) ») is shown as stored. Each word of a compound
 * unit takes the plural (« comprimés pelliculés »).
 */
export function countUnit(
  quantity: number | string | null | undefined,
  unit: string | null | undefined,
  locale: string = currentLocale(),
): string {
  const shown =
    quantity === null || quantity === undefined || quantity === '' ? '' : String(quantity);
  const word = (unit ?? '').trim();
  if (!word || word === 'UNIT') return shown;
  const n = Number(quantity);
  const plural =
    Number.isFinite(n) && (locale.startsWith('fr') ? Math.abs(n) >= 2 : Math.abs(n) !== 1);
  return `${shown} ${plural ? pluralUnit(word) : word}`.trim();
}

function pluralUnit(unit: string): string {
  if (/[\d/%()]/.test(unit)) return unit;
  const words = unit.split(/\s+/);
  if (words.some((w) => INVARIABLE.has(w.toLowerCase()) || w.length < 3 || w === w.toUpperCase())) {
    return unit;
  }
  return words.map((w) => (/[sxz]$/i.test(w) ? w : `${w}s`)).join(' ');
}

@Pipe({ name: 'countUnit', standalone: true })
export class CountUnitPipe implements PipeTransform {
  transform(quantity: number | string | null | undefined, unit: string | null | undefined): string {
    return countUnit(quantity, unit);
  }
}
