import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';

import { RouterLink } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { DoctorResultQueueItem } from '../../services/dashboard.service';

const LOCALE_MAP: Record<string, string> = {
  en: 'en-US',
  fr: 'fr-FR',
  es: 'es-ES',
};

@Component({
  selector: 'app-doctor-results-panel',
  standalone: true,
  imports: [RouterLink, TranslateModule],
  templateUrl: './doctor-results-panel.html',
  styleUrl: './doctor-results-panel.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DoctorResultsPanelComponent {
  private readonly translate = inject(TranslateService);

  results = input<DoctorResultQueueItem[]>([]);
  /**
   * True when the last read of the review queue failed.
   *
   * The service no longer turns a failure into an empty array, because a 403
   * or an outage drawn as "nothing to review" is how a released result
   * reaches nobody — and this panel is the one most physicians actually look
   * at, so it needs the explicit state too, not just an empty list.
   */
  loadError = input(false);
  /**
   * A read is in flight.
   *
   * Without it, Retry cleared the error and left an empty `results` behind,
   * so the panel flipped to the green "all results reviewed" card for the
   * whole request window — the very rendering this state exists to prevent.
   * The same window opened on the first load of the page.
   */
  loading = input(false);
  patientSelected = output<string>();
  resultAcknowledged = output<string>();
  reloadRequested = output<void>();

  criticalResults = computed(() => this.results().filter((r) => r.abnormalFlag === 'CRITICAL'));
  /**
   * Abnormal, plus anything this panel does not recognise.
   *
   * `ResultReviewServiceImpl.toQueueItem` collapses the flag to the
   * three-value family today, but a second producer of the DTO could send
   * something else — and with three exact matches such a row counted towards
   * the header badge while every section stayed empty, so the panel drew a
   * count above nothing at all (the "all reviewed" card is gated on a
   * zero-length list, so it did not appear either).
   *
   * The catch-all goes HERE, not in `normalResults`: a grade this panel
   * cannot read must never be presented to the ordering physician as normal.
   * The panel has three sections and no room for an "unknown" heading; the
   * in-basket copy of this queue, which has one, gives such a row its own.
   */
  abnormalResults = computed(() =>
    this.results().filter((r) => r.abnormalFlag !== 'CRITICAL' && r.abnormalFlag !== 'NORMAL'),
  );
  normalResults = computed(() => this.results().filter((r) => r.abnormalFlag === 'NORMAL'));

  selectPatient(patientId: string): void {
    this.patientSelected.emit(patientId);
  }

  acknowledgeResult(resultId: string): void {
    this.resultAcknowledged.emit(resultId);
  }

  requestReload(): void {
    this.reloadRequested.emit();
  }

  formatDate(iso: string): string {
    if (!iso) return '';
    const d = new Date(iso);
    const lang = this.translate.currentLang || this.translate.defaultLang || 'en';
    const locale = LOCALE_MAP[lang] ?? 'en-US';
    // 12-hour clock for English; 24-hour for French/Spanish per regional convention.
    const hour12 = lang === 'en';
    return d.toLocaleTimeString(locale, { hour: 'numeric', minute: '2-digit', hour12 });
  }
}
