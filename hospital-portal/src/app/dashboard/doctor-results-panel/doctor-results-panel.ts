import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { DoctorResultQueueItem } from '../../services/dashboard.service';

@Component({
  selector: 'app-doctor-results-panel',
  standalone: true,
  imports: [CommonModule, RouterLink, TranslateModule],
  templateUrl: './doctor-results-panel.html',
  styleUrl: './doctor-results-panel.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DoctorResultsPanelComponent {
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
    return d.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', hour12: true });
  }
}
