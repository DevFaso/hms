import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  OnInit,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  AllergyConflict,
  AllergyReviewDecision,
  PrescriptionService,
} from '../../services/prescription.service';
import { ModalDialogDirective } from '../a11y/modal-dialog.directive';
import { CdsCardListComponent } from '../cds-card/cds-card.component';
import { SubmitGuardDirective } from '../forms/submit-guard.directive';
import { refuseFields } from '../forms/invalid-fields';
import { refusedFieldReasons } from '../refusal-reason';

let nextId = 0;

/**
 * Policy 4 (user recommendation 2026-11-10, pending clinical approval): the authorized prescriber's
 * review of an allergy conflict that holds a signed order at the eMAR and the pharmacy. It shows the
 * allergy card as it stands, asks the prescriber to confirm it was read, and records CONTINUE (with a
 * plan) or STOP, with a rationale. The server checks the reviewer (a prescriber of the order's
 * hospital) and that the conflict is still the one shown; a changed conflict is shown again.
 *
 * A modal through the shared `appModalDialog` (QA D1004: focus moves in, stays in, Escape closes and
 * focus returns to the opener). The close button sits outside every `@if`, so the dialog always has a
 * control to take focus while the conflict loads.
 */
@Component({
  selector: 'app-allergy-review-dialog',
  standalone: true,
  imports: [
    FormsModule,
    TranslateModule,
    ModalDialogDirective,
    CdsCardListComponent,
    SubmitGuardDirective,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="modal-backdrop" tabindex="-1" (click)="close()" (keydown.escape)="close()">
      <div
        class="modal shared-dialog"
        appModalDialog
        tabindex="-1"
        [attr.aria-labelledby]="titleId"
        [attr.aria-busy]="loading()"
        (escaped)="close()"
        (click)="$event.stopPropagation()"
        (keydown.escape)="$event.stopPropagation()"
        data-testid="allergy-review-dialog"
      >
        <h2 [id]="titleId">{{ 'ALLERGY_REVIEW.TITLE' | translate }}</h2>
        <div class="dialog-body">
          @if (loading()) {
            <p role="status">{{ 'ALLERGY_REVIEW.LOADING' | translate }}</p>
          }
          @if (error(); as message) {
            <p class="form-error" role="alert" data-testid="allergy-review-error">{{ message }}</p>
          }
          @if (conflict(); as c) {
            @if (!c.conflict) {
              <p data-testid="allergy-review-none">
                {{ 'ALLERGY_REVIEW.NO_CONFLICT' | translate }}
              </p>
            } @else {
              <app-cds-card-list [cards]="c.cards" />
              @if (c.decision && c.reviewCurrent) {
                <p data-testid="allergy-review-on-record">
                  {{
                    'ALLERGY_REVIEW.ON_RECORD'
                      | translate
                        : {
                            decision: ('ALLERGY_REVIEW.DECISION_' + c.decision | translate),
                            name: c.reviewedByName || '',
                            at: c.reviewedAt || '',
                          }
                  }}
                </p>
              } @else if (c.decision) {
                <p data-testid="allergy-review-outdated">
                  {{ 'ALLERGY_REVIEW.OUTDATED' | translate }}
                </p>
              }
              <fieldset class="field">
                <legend>{{ 'ALLERGY_REVIEW.DECISION' | translate }} *</legend>
                <label>
                  <input
                    type="radio"
                    [name]="decisionName"
                    value="CONTINUE"
                    data-testid="allergy-review-continue"
                    [ngModel]="decision()"
                    (ngModelChange)="decision.set($event)"
                  />
                  {{ 'ALLERGY_REVIEW.DECISION_CONTINUE' | translate }}
                </label>
                <label>
                  <input
                    type="radio"
                    [name]="decisionName"
                    value="STOP"
                    data-testid="allergy-review-stop"
                    [ngModel]="decision()"
                    (ngModelChange)="decision.set($event)"
                  />
                  {{ 'ALLERGY_REVIEW.DECISION_STOP' | translate }}
                </label>
              </fieldset>
              @if (decision() === 'CONTINUE') {
                <div class="field">
                  <label [for]="planId">{{ 'ALLERGY_REVIEW.PLAN' | translate }} *</label>
                  <textarea
                    [id]="planId"
                    rows="2"
                    maxlength="1000"
                    required
                    aria-required="true"
                    data-testid="allergy-review-plan"
                    [ngModel]="plan()"
                    (ngModelChange)="plan.set($event)"
                  ></textarea>
                </div>
              }
              <div class="field">
                <label [for]="rationaleId">{{ 'ALLERGY_REVIEW.RATIONALE' | translate }} *</label>
                <textarea
                  [id]="rationaleId"
                  rows="3"
                  maxlength="1000"
                  required
                  aria-required="true"
                  data-testid="allergy-review-rationale"
                  [ngModel]="rationale()"
                  (ngModelChange)="rationale.set($event)"
                ></textarea>
              </div>
              <label class="confirm-line">
                <input
                  type="checkbox"
                  data-testid="allergy-review-confirm"
                  [ngModel]="confirmed()"
                  (ngModelChange)="confirmed.set($event)"
                />
                {{ 'ALLERGY_REVIEW.CONFIRM_READ' | translate: { allergen: c.allergen || '' } }}
              </label>
            }
          }
        </div>
        <div class="modal-actions">
          <button
            type="button"
            class="btn-secondary"
            data-testid="allergy-review-close"
            (click)="close()"
          >
            {{ 'COMMON.CANCEL' | translate }}
          </button>
          @if (conflict()?.conflict) {
            <button
              type="button"
              class="btn-danger"
              data-testid="allergy-review-submit"
              [disabled]="saving()"
              [appSubmitGuard]="blocked()"
              [submitGuardFields]="
                decision() === 'CONTINUE' ? [planId, rationaleId] : [rationaleId]
              "
              submitGuardRequired
              (click)="submit()"
            >
              {{ 'ALLERGY_REVIEW.SUBMIT' | translate }}
            </button>
          }
        </div>
      </div>
    </div>
  `,
  styleUrl: '../styles/shared-dialog.scss',
})
export class AllergyReviewDialogComponent implements OnInit {
  /** The signed order whose allergy conflict is reviewed. */
  readonly prescriptionId = input.required<string>();
  readonly closed = output<void>();
  /** The review was recorded: the conflict as the server answers it now. */
  readonly reviewed = output<AllergyConflict>();

  private readonly prescriptions = inject(PrescriptionService);
  private readonly translate = inject(TranslateService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject(ElementRef<HTMLElement>);

  private readonly uid = ++nextId;
  protected readonly titleId = `allergy-review-title-${this.uid}`;
  protected readonly planId = `allergy-review-plan-${this.uid}`;
  protected readonly rationaleId = `allergy-review-rationale-${this.uid}`;
  protected readonly decisionName = `allergy-review-decision-${this.uid}`;

  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly conflict = signal<AllergyConflict | null>(null);
  protected readonly decision = signal<AllergyReviewDecision | null>(null);
  protected readonly plan = signal('');
  protected readonly rationale = signal('');
  protected readonly confirmed = signal(false);

  /** Why the review cannot be sent yet (SubmitGuardDirective): a missing field, or the unread allergy. */
  protected readonly blocked = computed<string | boolean>(() => {
    if (!this.decision() || !this.rationale().trim()) return true;
    if (this.decision() === 'CONTINUE' && !this.plan().trim()) return true;
    if (!this.confirmed()) return this.translate.instant('ALLERGY_REVIEW.CONFIRM_REQUIRED');
    return false;
  });

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.prescriptions
      .getAllergyConflict(this.prescriptionId())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (conflict) => {
          this.loading.set(false);
          this.conflict.set(conflict);
          this.confirmed.set(false);
        },
        error: (err: HttpErrorResponse) => {
          this.loading.set(false);
          this.error.set(this.messageOf(err));
        },
      });
  }

  protected close(): void {
    if (this.saving()) return;
    this.closed.emit();
  }

  protected submit(): void {
    const c = this.conflict();
    const decision = this.decision();
    if (!c?.reviewToken || !decision || this.blocked() || this.saving()) return;
    this.saving.set(true);
    this.error.set(null);
    refuseFields(this.host.nativeElement, [], this.fieldIds(), '');
    this.prescriptions
      .reviewAllergyConflict(this.prescriptionId(), {
        decision,
        plan: this.plan(),
        rationale: this.rationale(),
        allergyToken: c.reviewToken,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (answer) => {
          this.saving.set(false);
          this.reviewed.emit(answer);
        },
        error: (err: HttpErrorResponse) => {
          this.saving.set(false);
          this.error.set(this.messageOf(err));
          // A field the server refused (the rationale, the plan) is marked with its reason.
          const refused = refusedFieldReasons(err, {
            rationale: this.rationaleId,
            plan: this.planId,
          });
          if (refused.length > 0) {
            refuseFields(
              this.host.nativeElement,
              refused.map(([id, message]) => ({ id, message })),
              this.fieldIds(),
            );
          }
          // The conflict changed since it was shown: show the current one, to be read again.
          if (
            err?.status === 400 &&
            (err.error as { field?: string } | null)?.field === 'allergyToken'
          ) {
            this.load();
          }
        },
      });
  }

  private fieldIds(): string[] {
    return [this.planId, this.rationaleId];
  }

  private messageOf(err: HttpErrorResponse): string {
    const message = (err?.error as { message?: unknown } | null)?.message;
    return typeof message === 'string' && message.trim()
      ? message.trim()
      : this.translate.instant('ALLERGY_REVIEW.FAILED');
  }
}
