import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { forkJoin } from 'rxjs';
import { LabService, LabQcSummary, LabValidationSummary } from '../../services/lab.service';
import { ToastService } from '../../core/toast.service';

@Component({
  selector: 'app-lab-qc-dashboard',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './lab-qc-dashboard.html',
  styleUrl: './lab-qc-dashboard.scss',
})
export class LabQcDashboardComponent implements OnInit {
  private readonly labService = inject(LabService);
  private readonly toast = inject(ToastService);

  loading = signal(true);
  qcSummary = signal<LabQcSummary[]>([]);
  validationSummary = signal<LabValidationSummary[]>([]);

  // ── Computed aggregates ───────────────────────────────────
  totalQcEvents = computed(() => this.qcSummary().reduce((s, r) => s + r.totalEvents, 0));
  totalQcFailures = computed(() => this.qcSummary().reduce((s, r) => s + r.failedEvents, 0));
  totalValidationStudies = computed(() =>
    this.validationSummary().reduce((s, r) => s + r.totalStudies, 0),
  );
  overallPassRate = computed(() => {
    const total = this.totalValidationStudies();
    const passed = this.validationSummary().reduce((s, r) => s + r.passedStudies, 0);
    return total > 0 ? (passed / total) * 100 : 0;
  });

  ngOnInit(): void {
    forkJoin({
      qc: this.labService.getQcSummary(),
      validation: this.labService.getValidationSummary(),
    }).subscribe({
      next: ({ qc, validation }) => {
        this.qcSummary.set(qc);
        this.validationSummary.set(validation);
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load QC dashboard data.');
        this.loading.set(false);
      },
    });
  }
}
