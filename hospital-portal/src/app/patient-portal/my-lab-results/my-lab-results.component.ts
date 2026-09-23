import { Component, OnInit, inject, signal, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';
import { PatientPortalService, LabResultSummary } from '../../services/patient-portal.service';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';

@Component({
  selector: 'app-my-lab-results',
  standalone: true,
  imports: [CommonModule, DatePipe, EnumLabelPipe, TranslateModule],
  templateUrl: './my-lab-results.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrls: ['./my-lab-results.component.scss', '../patient-portal-pages.scss'],
})
export class MyLabResultsComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);
  results = signal<LabResultSummary[]>([]);
  loading = signal(true);
  expandedId = signal<string | null>(null);

  ngOnInit() {
    this.portal.getMyLabResults().subscribe({
      next: (r) => {
        this.results.set(r);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  toggleExpand(id: string): void {
    this.expandedId.set(this.expandedId() === id ? null : id);
  }

  /**
   * Three states, not two: a result the lab has not released yet carries no
   * value at all, so drawing it with the "all clear" check beside an empty
   * value told the patient their test was normal before anyone had read it.
   */
  labIcon(lab: LabResultSummary): string {
    if (lab.isPending) return 'hourglass_top';
    return lab.isAbnormal ? 'warning' : 'check_circle';
  }

  labIconBackground(lab: LabResultSummary): string {
    if (lab.isPending) return '#fef3c7';
    return lab.isAbnormal ? '#fee2e2' : '#d1fae5';
  }

  labIconColor(lab: LabResultSummary): string {
    if (lab.isPending) return '#b45309';
    return lab.isAbnormal ? '#dc2626' : '#059669';
  }

  interpretation(lab: LabResultSummary): string {
    if (lab.isPending) return 'PORTAL.LAB_RESULTS.PENDING_INTERPRETATION';
    return lab.isAbnormal
      ? 'PORTAL.LAB_RESULTS.ABNORMAL_RESULT'
      : 'PORTAL.LAB_RESULTS.NORMAL_RESULT';
  }
}
