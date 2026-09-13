import { Component, computed, effect, inject, signal } from '@angular/core';
import { DatePipe, SlicePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';
import {
  BreakGlassReviewOutcome,
  BreakGlassService,
  BreakGlassSession,
} from '../services/break-glass.service';
import { HospitalScopeHintComponent } from '../shared/hospital-scope-chip/hospital-scope-hint.component';

/**
 * E8 #54 — the compliance review of break-the-glass sessions. Works the
 * hospital's register as a queue: unreviewed sessions first, each signed off
 * with an outcome and a note (PATCH /break-glass/{id}/review). The scope is
 * the effective hospital; a super-admin in global view is asked to pin one.
 */
@Component({
  selector: 'app-break-glass-review',
  standalone: true,
  imports: [
    DatePipe,
    SlicePipe,
    FormsModule,
    RouterLink,
    TranslateModule,
    HospitalScopeHintComponent,
  ],
  templateUrl: './break-glass-review.html',
  styleUrl: './break-glass-review.scss',
})
export class BreakGlassReviewComponent {
  private readonly service = inject(BreakGlassService);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  readonly outcomes: BreakGlassReviewOutcome[] = ['JUSTIFIED', 'NOT_JUSTIFIED', 'FOLLOW_UP'];

  /** See RoleContextService.hasHospitalScope: the register is one hospital's. */
  readonly scopeReady = this.roleContext.hasHospitalScope;
  readonly loading = signal(false);
  readonly error = signal('');
  readonly sessions = signal<BreakGlassSession[]>([]);
  readonly totalElements = signal(0);
  readonly totalPages = signal(0);
  readonly page = signal(0);
  /** true = only the queue (default); false = the whole register. */
  readonly queueOnly = signal(true);
  readonly reviewing = signal<string | null>(null);
  readonly outcome = signal<BreakGlassReviewOutcome>('JUSTIFIED');
  readonly note = signal('');
  readonly submitting = signal(false);

  readonly unreviewedCount = computed(() => this.sessions().filter((s) => !s.reviewed).length);

  constructor() {
    // Reload whenever the effective hospital changes (the chip, or leaving global view).
    effect(() => {
      const hospitalId = this.roleContext.effectiveHospitalIdForRequest();
      this.page.set(0);
      if (hospitalId) {
        this.load(hospitalId);
      } else {
        this.sessions.set([]);
        this.totalElements.set(0);
        this.totalPages.set(0);
      }
    });
  }

  reload(): void {
    const hospitalId = this.roleContext.effectiveHospitalIdForRequest();
    if (hospitalId) this.load(hospitalId);
  }

  setQueueOnly(value: boolean): void {
    if (this.queueOnly() === value) return;
    this.queueOnly.set(value);
    this.page.set(0);
    this.reload();
  }

  goToPage(page: number): void {
    if (page < 0 || page >= this.totalPages()) return;
    this.page.set(page);
    this.reload();
  }

  openReview(session: BreakGlassSession): void {
    this.reviewing.set(session.id);
    this.outcome.set(session.reviewOutcome ?? 'JUSTIFIED');
    this.note.set(session.reviewNote ?? '');
  }

  cancelReview(): void {
    if (this.submitting()) return;
    this.reviewing.set(null);
  }

  submitReview(): void {
    const id = this.reviewing();
    if (!id || this.submitting()) return;
    this.submitting.set(true);
    this.service
      .review(id, { outcome: this.outcome(), note: this.note().trim() || undefined })
      .subscribe({
        next: (updated) => {
          this.sessions.set(this.sessions().map((s) => (s.id === updated.id ? updated : s)));
          this.submitting.set(false);
          this.reviewing.set(null);
          this.toast.success(this.translate.instant('BREAK_GLASS_REVIEW.REVIEWED'));
          if (this.queueOnly()) this.reload();
        },
        error: (err) => {
          this.submitting.set(false);
          this.toast.error(
            err?.error?.message ?? this.translate.instant('BREAK_GLASS_REVIEW.REVIEW_FAILED'),
          );
        },
      });
  }

  private load(hospitalId: string): void {
    this.loading.set(true);
    this.error.set('');
    this.service
      .listForHospital(hospitalId, this.page(), 20, this.queueOnly() ? false : undefined)
      .subscribe({
        next: (result) => {
          this.sessions.set(result.content);
          this.totalElements.set(result.totalElements);
          this.totalPages.set(result.totalPages);
          this.loading.set(false);
        },
        error: () => {
          this.sessions.set([]);
          this.loading.set(false);
          this.error.set(this.translate.instant('BREAK_GLASS_REVIEW.LOAD_FAILED'));
        },
      });
  }
}
