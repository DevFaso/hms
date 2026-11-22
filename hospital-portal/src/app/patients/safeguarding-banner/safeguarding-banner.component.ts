import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  OnChanges,
  inject,
  input,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { SafeguardingReview, SafeguardingService } from '../../services/safeguarding.service';
import { SubmitGuardDirective } from '../../shared/forms/submit-guard.directive';
import { DateTimePipe } from '../../shared/pipes/date-time.pipe';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { refuseFields } from '../../shared/forms/invalid-fields';
import { refusalReason, refusedFieldReasons } from '../../shared/refusal-reason';
import { SAFEGUARDING_RECORD_ROLES, SAFEGUARDING_VIEW_ROLES } from '../patient-chart/chart-access';
import { RuleStatusComponent } from '../../shared/rule-status/rule-status.component';

/**
 * r53, provisional rule (user decision 2026-11-14, pending the obstetric lead's and the child-protection lead's
 * approval): the open safeguarding review of a minor admitted in labour, on her chart, for the treating team and the
 * hospital administrator only. Neutral wording: a review is required, nothing is stated or implied about its cause.
 * It never blocks care, and it shows nothing when there is no open review or the read fails.
 */
@Component({
  selector: 'app-safeguarding-banner',
  standalone: true,
  imports: [
    RuleStatusComponent,
    FormsModule,
    TranslateModule,
    DateTimePipe,
    ServerTimePipe,
    SubmitGuardDirective,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: [
    `
      .safeguarding {
        margin: 0 0 1rem;
        padding: 0.75rem 1rem;
        border-left: 4px solid var(--danger, #b42318);
        background: var(--danger-bg, #fef3f2);
        border-radius: 6px;
      }
      .safeguarding h2 {
        font-size: 1rem;
        margin: 0 0 0.25rem;
      }
      .safeguarding p {
        margin: 0.25rem 0;
      }
      .safeguarding .provisional {
        font-style: italic;
      }
      .safeguarding .field {
        display: flex;
        flex-direction: column;
        gap: 0.25rem;
        margin-top: 0.5rem;
      }
      .safeguarding textarea {
        width: 100%;
        min-height: 4rem;
        box-sizing: border-box;
      }
      .safeguarding .actions {
        margin-top: 0.5rem;
      }
    `,
  ],
  template: `
    @for (review of open(); track review.id) {
      <section
        class="safeguarding"
        role="status"
        [attr.aria-labelledby]="'safeguarding-' + review.id"
        data-testid="safeguarding-banner"
      >
        <h2 [id]="'safeguarding-' + review.id">{{ 'SAFEGUARDING.TITLE' | translate }}</h2>
        <p>
          {{
            'SAFEGUARDING.BODY'
              | translate: { age: review.patientAgeYears, threshold: review.ageThresholdYears }
          }}
        </p>
        <p>{{ 'SAFEGUARDING.CONFIDENTIAL' | translate }}</p>
        <p>
          {{ 'SAFEGUARDING.RAISED' | translate: { date: review.raisedAt | serverTime | dateTime } }}
        </p>
        @if (review.provisionalRule) {
          <p class="provisional">
            <app-rule-status ruleKey="MINOR_LABOUR_SAFEGUARDING">{{
              'SAFEGUARDING.PROVISIONAL' | translate
            }}</app-rule-status>
          </p>
        }
        @if (canRecord()) {
          <div class="field">
            <label [for]="'sgr-note-' + review.id"
              >{{ 'SAFEGUARDING.NOTE_LABEL' | translate }} *</label
            >
            <textarea
              required
              maxlength="4000"
              [id]="'sgr-note-' + review.id"
              [ngModel]="note()"
              (ngModelChange)="note.set($event)"
            ></textarea>
          </div>
          <div class="actions">
            <button
              type="button"
              class="btn-primary"
              submitGuardRequired
              data-testid="safeguarding-record"
              [disabled]="saving()"
              [appSubmitGuard]="!note().trim()"
              [submitGuardFields]="['sgr-note-' + review.id]"
              (click)="record(review)"
            >
              {{ 'SAFEGUARDING.RECORD' | translate }}
            </button>
          </div>
        } @else {
          <p>{{ 'SAFEGUARDING.RECORD_BY_PHYSICIAN' | translate }}</p>
        }
      </section>
    }
  `,
})
export class SafeguardingBannerComponent implements OnChanges {
  private readonly safeguarding = inject(SafeguardingService);
  private readonly roles = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly patientId = input.required<string>();
  readonly open = signal<SafeguardingReview[]>([]);
  readonly note = signal('');
  readonly saving = signal(false);
  private request = 0;

  ngOnChanges(): void {
    this.load();
  }

  canRecord(): boolean {
    return this.roles.hasAnyActiveRole(SAFEGUARDING_RECORD_ROLES);
  }

  load(): void {
    const id = this.patientId();
    const ticket = ++this.request;
    this.open.set([]);
    if (!id || !this.roles.hasAnyActiveRole(SAFEGUARDING_VIEW_ROLES)) {
      return;
    }
    this.safeguarding
      .forPatient(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (rows) => {
          if (ticket === this.request) {
            this.open.set((rows ?? []).filter((row) => row.status === 'OPEN'));
          }
        },
        error: () => {
          if (ticket === this.request) {
            this.open.set([]);
          }
        },
      });
  }

  record(review: SafeguardingReview): void {
    const note = this.note().trim();
    if (!note) {
      return;
    }
    this.saving.set(true);
    this.safeguarding.recordReview(review.id, note).subscribe({
      next: () => {
        this.saving.set(false);
        this.note.set('');
        this.toast.success(this.translate.instant('SAFEGUARDING.RECORDED'));
        this.load();
      },
      error: (err: unknown) => {
        this.saving.set(false);
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('SAFEGUARDING.RECORD_FAILED'),
        );
        // A 400 names the note: it is marked where the reader typed it, and the text is kept.
        const noteId = `sgr-note-${review.id}`;
        for (const [id, text] of refusedFieldReasons(err, { note: noteId })) {
          refuseFields(this.host.nativeElement, [id], [id], text);
        }
      },
    });
  }
}
