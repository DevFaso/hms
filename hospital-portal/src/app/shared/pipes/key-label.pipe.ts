import { ChangeDetectorRef, OnDestroy, Pipe, PipeTransform, inject } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';
import { Subscription } from 'rxjs';

/**
 * QA D1323 (round 29): a row value the server sent outside the dictionary
 * printed the raw key — `'RECEPTION.' + entry.priority | translate` showed
 * "RECEPTION.URGENTISSIME", and a blank priority "RECEPTION.". Every label
 * built from a prefix and a server value goes through this pipe:
 *
 * ```html
 * {{ entry.priority | keyLabel: 'RECEPTION.' }}
 * ```
 *
 * - a value with a translation under the prefix → that translation;
 * - a blank value → "—";
 * - any other value → the value as sent (never the key with its prefix).
 *
 * `scripts/lib/key-label.test.mjs` fails a template that translates
 * `'PREFIX.' + row.field` directly.
 */
@Pipe({ name: 'keyLabel', standalone: true, pure: false })
export class KeyLabelPipe implements PipeTransform, OnDestroy {
  private readonly translate = inject(TranslateService);
  private readonly cdr = inject(ChangeDetectorRef, { optional: true });
  private readonly sub: Subscription;

  constructor() {
    this.sub = this.translate.onLangChange.subscribe(() => this.cdr?.markForCheck());
  }

  ngOnDestroy(): void {
    this.sub.unsubscribe();
  }

  transform(value: string | null | undefined, prefix: string): string {
    return keyLabel(this.translate, value, prefix);
  }
}

/** The label of `value` under `prefix` (see {@link KeyLabelPipe}). */
export function keyLabel(
  translate: TranslateService,
  value: string | null | undefined,
  prefix: string,
): string {
  const v = (value ?? '').trim();
  if (v === '') return '—';
  const key = `${prefix}${v}`;
  const label = translate.instant(key);
  return typeof label === 'string' && label !== key && label !== '' ? label : v;
}
