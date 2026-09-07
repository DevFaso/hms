import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';
import { ToastService } from '../core/toast.service';
import { PharmacyService, MtmReviewRequest, MtmReviewResponse } from '../services/pharmacy.service';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';
import { RoleContextService } from '../core/role-context.service';
import { HospitalScopeChipComponent } from '../shared/hospital-scope-chip/hospital-scope-chip.component';
import { HospitalScopeHintComponent } from '../shared/hospital-scope-chip/hospital-scope-hint.component';
import { Subject, finalize, takeUntil } from 'rxjs';
import { ActivatedRoute } from '@angular/router';
import { HospitalScopeUrlService } from '../core/hospital-scope-url.service';

/**
 * P-09: MTM (Medication Therapy Management) review screen — pharmacist-led
 * chronic disease review, adherence counselling flag, polypharmacy alert,
 * intervention record. This is a foundation skeleton; the workflow integrates
 * with the existing retrospective timeline produced by MedicationHistoryService.
 */
@Component({
  selector: 'app-mtm-review',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    TranslateModule,
    EnumLabelPipe,
    HospitalScopeChipComponent,
    HospitalScopeHintComponent,
  ],
  templateUrl: './mtm-review.html',
  styleUrl: './mtm-review.scss',
})
export class MtmReviewComponent implements OnInit {
  private readonly svc = inject(PharmacyService);
  private readonly toast = inject(ToastService);
  private readonly roleContext = inject(RoleContextService);
  private readonly route = inject(ActivatedRoute);
  private readonly scopeUrl = inject(HospitalScopeUrlService);
  /** See RoleContextService.hasHospitalScope: loads and write buttons wait for a pinned hospital. */
  readonly scopeReady = this.roleContext.hasHospitalScope;
  /** Emits on every scope change so a response for the previous hospital can never land. */
  private readonly scopeChanged$ = new Subject<void>();

  reviews = signal<MtmReviewResponse[]>([]);
  loading = signal(false);
  saving = signal(false);

  // Form state
  showForm = signal(false);
  selectedReviewId: string | null = null;
  form: MtmReviewRequest = this.emptyForm();

  ngOnInit(): void {
    // Read ?hospitalId= before the first load: the chip does the same in its
    // own ngOnInit, which runs after ours, and the interceptor must see the
    // right scope on the initial fetch (the pattern every chip host uses).
    this.scopeUrl.applyUrlScopeSync(this.route);
    this.loadReviews();
  }

  onScopeChange(): void {
    this.scopeChanged$.next();
    this.loadReviews();
  }

  loadReviews(): void {
    // hasHospitalScope is exactly "this is non-null": one gate, no fallback.
    const hospitalId = this.roleContext.effectiveHospitalIdForRequest();
    if (hospitalId == null) {
      this.reviews.set([]);
      this.loading.set(false);
      return;
    }
    this.loading.set(true);
    this.svc
      .listMtmReviewsByHospital(hospitalId, 0, 50)
      .pipe(
        takeUntil(this.scopeChanged$),
        finalize(() => this.loading.set(false)),
      )
      .subscribe({
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
    this.form.hospitalId = this.roleContext.effectiveHospitalIdForRequest() ?? '';
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
