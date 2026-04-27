import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';
import { ToastService } from '../core/toast.service';
import { PharmacyService, MtmReviewRequest, MtmReviewResponse } from '../services/pharmacy.service';
import { AuthService } from '../auth/auth.service';

/**
 * P-09: MTM (Medication Therapy Management) review screen — pharmacist-led
 * chronic disease review, adherence counselling flag, polypharmacy alert,
 * intervention record. This is a foundation skeleton; the workflow integrates
 * with the existing retrospective timeline produced by MedicationHistoryService.
 */
@Component({
  selector: 'app-mtm-review',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './mtm-review.html',
  styleUrl: './mtm-review.scss',
})
export class MtmReviewComponent implements OnInit {
  private readonly svc = inject(PharmacyService);
  private readonly toast = inject(ToastService);
  private readonly auth = inject(AuthService);

  reviews = signal<MtmReviewResponse[]>([]);
  loading = signal(false);
  saving = signal(false);

  // Form state
  showForm = signal(false);
  selectedReviewId: string | null = null;
  form: MtmReviewRequest = this.emptyForm();

  ngOnInit(): void {
    this.loadReviews();
  }

  loadReviews(): void {
    const hospitalId = this.auth.getHospitalId();
    if (!hospitalId) {
      this.toast.error('Active hospital context required');
      return;
    }
    this.loading.set(true);
    this.svc.listMtmReviewsByHospital(hospitalId, 0, 50).subscribe({
      next: (page) => {
        this.reviews.set(page?.content ?? []);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.toast.error('Failed to load MTM reviews');
      },
    });
  }

  openCreate(): void {
    this.selectedReviewId = null;
    this.form = this.emptyForm();
    this.form.hospitalId = this.auth.getHospitalId() ?? '';
    this.showForm.set(true);
  }

  openEdit(review: MtmReviewResponse): void {
    this.selectedReviewId = review.id;
    this.form = {
      patientId: review.patientId,
      hospitalId: review.hospitalId,
      chronicConditionFocus: review.chronicConditionFocus,
      adherenceConcern: review.adherenceConcern,
      interventionSummary: review.interventionSummary,
      recommendedActions: review.recommendedActions,
      status: review.status,
      followUpDate: review.followUpDate,
    };
    this.showForm.set(true);
  }

  closeForm(): void {
    this.showForm.set(false);
    this.selectedReviewId = null;
  }

  submit(): void {
    if (!this.form.patientId || !this.form.hospitalId) {
      this.toast.error('Patient and hospital are required');
      return;
    }
    this.saving.set(true);
    const stream = this.selectedReviewId
      ? this.svc.updateMtmReview(this.selectedReviewId, this.form)
      : this.svc.startMtmReview(this.form);
    stream.subscribe({
      next: () => {
        this.toast.success('MTM review saved');
        this.saving.set(false);
        this.closeForm();
        this.loadReviews();
      },
      error: (err) => {
        this.saving.set(false);
        this.toast.error(err?.error?.message ?? 'Failed to save MTM review');
      },
    });
  }

  statusBadgeClass(status: string): string {
    switch (status) {
      case 'COMPLETED':
        return 'badge-success';
      case 'REFERRED':
        return 'badge-warning';
      default:
        return 'badge-info';
    }
  }

  private emptyForm(): MtmReviewRequest {
    return {
      patientId: '',
      hospitalId: '',
      chronicConditionFocus: '',
      adherenceConcern: false,
      interventionSummary: '',
      recommendedActions: '',
      status: 'DRAFT',
      followUpDate: '',
    };
  }
}
