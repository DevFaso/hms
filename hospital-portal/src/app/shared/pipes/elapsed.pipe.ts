import { inject, Pipe, PipeTransform } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';

const MINUTES_PER_HOUR = 60;
const MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR;

/**
 * QA D1479: a long wait reads as time, never as raw minutes ("7151m"):
 * 45 → "45 min", 135 → "2 h 15 min", 7151 → "4 j 23 h".
 * Impure for the language, as the translate pipe is.
 */
@Pipe({ name: 'elapsed', standalone: true, pure: false })
export class ElapsedPipe implements PipeTransform {
  private readonly translate = inject(TranslateService);

  transform(totalMinutes: number | null | undefined): string {
    if (totalMinutes === null || totalMinutes === undefined) return '';
    const total = Math.max(0, Math.floor(totalMinutes));
    if (total >= MINUTES_PER_DAY) {
      return this.translate.instant('ELAPSED.DAYS', {
        days: Math.floor(total / MINUTES_PER_DAY),
        hours: Math.floor((total % MINUTES_PER_DAY) / MINUTES_PER_HOUR),
      });
    }
    if (total >= MINUTES_PER_HOUR) {
      return this.translate.instant('ELAPSED.HOURS', {
        hours: Math.floor(total / MINUTES_PER_HOUR),
        minutes: total % MINUTES_PER_HOUR,
      });
    }
    return this.translate.instant('ELAPSED.MINUTES', { minutes: total });
  }
}
