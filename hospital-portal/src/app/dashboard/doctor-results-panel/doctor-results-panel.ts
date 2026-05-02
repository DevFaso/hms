import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { CommonModule } from '@angular/common';
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
  imports: [CommonModule, RouterLink, TranslateModule],
  templateUrl: './doctor-results-panel.html',
  styleUrl: './doctor-results-panel.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DoctorResultsPanelComponent {
  private readonly translate = inject(TranslateService);

  results = input<DoctorResultQueueItem[]>([]);
  patientSelected = output<string>();
  resultAcknowledged = output<string>();

  criticalResults = computed(() => this.results().filter((r) => r.abnormalFlag === 'CRITICAL'));
  abnormalResults = computed(() => this.results().filter((r) => r.abnormalFlag === 'ABNORMAL'));
  normalResults = computed(() => this.results().filter((r) => r.abnormalFlag === 'NORMAL'));

  selectPatient(patientId: string): void {
    this.patientSelected.emit(patientId);
  }

  acknowledgeResult(resultId: string): void {
    this.resultAcknowledged.emit(resultId);
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
