import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  PatientPortalService,
  PortalTreatmentPlan,
  PortalProgressEntry,
  PortalProgressEntryRequest,
} from '../../services/patient-portal.service';
import { catchError, of } from 'rxjs';

@Component({
  selector: 'app-my-treatment-plans',
  standalone: true,
  imports: [CommonModule, DatePipe, FormsModule],
  templateUrl: './my-treatment-plans.html',
  styleUrl: './my-treatment-plans.scss',
})
export class MyTreatmentPlansComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);

  loading = signal(true);
  plans = signal<PortalTreatmentPlan[]>([]);
  expandedId = signal<string | null>(null);

  // Progress tracking state per plan
  progressMap = signal<Record<string, PortalProgressEntry[]>>({});
  progressLoading = signal<Record<string, boolean>>({});
  showLogForm = signal<Record<string, boolean>>({});

  // Log form model (reused across plans)
  logForm: PortalProgressEntryRequest = {
    progressDate: new Date().toISOString().slice(0, 10),
    progressNote: '',
    selfRating: null,
    onTrack: true,
  };
  submitting = signal(false);

  ngOnInit(): void {
    this.portal.getMyTreatmentPlans().subscribe({
      next: (data) => {
        this.plans.set(data);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  toggle(id: string): void {
    const next = this.expandedId() === id ? null : id;
    this.expandedId.set(next);
    if (next && !this.progressMap()[id]) {
      this.loadProgress(id);
    }
  }

  loadProgress(planId: string): void {
    this.progressLoading.update((m) => ({ ...m, [planId]: true }));
    this.portal
      .getMyTreatmentPlanProgress(planId)
      .pipe(catchError(() => of([])))
      .subscribe((entries) => {
        this.progressMap.update((m) => ({ ...m, [planId]: entries }));
        this.progressLoading.update((m) => ({ ...m, [planId]: false }));
      });
  }

  openLogForm(planId: string): void {
    this.logForm = {
      progressDate: new Date().toISOString().slice(0, 10),
      progressNote: '',
      selfRating: null,
      onTrack: true,
    };
    this.showLogForm.update((m) => ({ ...m, [planId]: true }));
  }

  cancelLogForm(planId: string): void {
    this.showLogForm.update((m) => ({ ...m, [planId]: false }));
  }

  submitLog(planId: string): void {
    if (this.submitting()) return;
    this.submitting.set(true);
    this.portal.logMyTreatmentProgress(planId, this.logForm).subscribe({
      next: (entry) => {
        this.progressMap.update((m) => ({
          ...m,
          [planId]: [entry, ...(m[planId] ?? [])],
        }));
        this.showLogForm.update((m) => ({ ...m, [planId]: false }));
        this.submitting.set(false);
      },
      error: () => this.submitting.set(false),
    });
  }

  statusClass(status: string): string {
    const s = status?.toUpperCase();
    if (s === 'APPROVED') return 'status-approved';
    if (s === 'IN_REVIEW') return 'status-review';
    if (s === 'CANCELLED' || s === 'ARCHIVED') return 'status-inactive';
    if (s === 'REVISIONS_REQUIRED') return 'status-revisions';
    return 'status-draft';
  }

  ratingClass(rating: number | null): string {
    if (rating == null) return '';
    if (rating >= 8) return 'rating-high';
    if (rating >= 5) return 'rating-mid';
    return 'rating-low';
  }
}
