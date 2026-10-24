import { Pipe, PipeTransform } from '@angular/core';
import { formatCfa } from '../i18n/app-locale';

/**
 * QA D621: `{{ amount | cfa }}` or `{{ amount | cfa: row.currency }}` —
 * francs CFA as "4 000 F CFA" in every language (see {@link formatCfa}).
 */
@Pipe({ name: 'cfa', standalone: true })
export class CfaPipe implements PipeTransform {
  transform(value: number | null | undefined, currency?: string | null): string {
    return formatCfa(value, currency);
  }
}
