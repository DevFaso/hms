import { Pipe, PipeTransform } from '@angular/core';

/**
 * QA D1328 (round 29): a missing value reads "—". `{{ p.address ?? '—' }}`
 * showed an empty cell when the server sent `""` (an optional field left
 * blank at registration): `??` stops only `null` and `undefined`. Province,
 * Adresse and Ethnie were blank while Code postal showed "—".
 *
 * `{{ p.address | dash }}`: null, undefined, an empty or blank string → "—";
 * anything else (0 included) as it is. `scripts/lib/round29-front-office.test.mjs`
 * fails a template that writes `?? '—'`.
 */
@Pipe({ name: 'dash', standalone: true })
export class DashPipe implements PipeTransform {
  transform<T>(value: T | null | undefined): T | string {
    return orDash(value);
  }
}

/** `value`, or "—" when it is missing or a blank string. */
export function orDash<T>(value: T | null | undefined): T | string {
  if (value === null || value === undefined) return '—';
  if (typeof value === 'string' && value.trim() === '') return '—';
  return value;
}
