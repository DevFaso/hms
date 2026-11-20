import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { ToastService } from '../core/toast.service';
import { refusalReason, refusedFieldReasons } from '../shared/refusal-reason';
import { refuseFields } from '../shared/forms/invalid-fields';
import { ConfirmService } from '../shared/confirm-dialog/confirm.service';
import { CfaPipe } from '../shared/pipes/cfa.pipe';
import { DashPipe } from '../shared/pipes/dash.pipe';
import { KeyLabelPipe } from '../shared/pipes/key-label.pipe';
import { ServerTimePipe } from '../shared/pipes/server-time.pipe';
import { ApiResponse } from '../services/pharmacy.service';
import {
  BillingDeferral,
  BillingDeferralDecision,
  BillingDeferralService,
  HospitalBillingPolicy,
} from '../services/billing-deferral.service';

type Step = 'approve' | 'reject' | 'cancel' | 'review';

/**
 * Policy rule 6 (provisional, V345): the work list of « à facturer ». Billing checks a third-party cover against
 * its claim or voucher; finance or the hospital admin grants an outpatient credit, with a reason and an optional
 * limit; finance reviews each urgency override the counter made. Never the same person: the server shows each
 * reader only the steps that are theirs. The page also holds the hospital's billing policy (rules 1 and 6).
 */
@Component({
  selector: 'app-billing-deferrals',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    TranslateModule,
    CfaPipe,
    DashPipe,
    KeyLabelPipe,
    ServerTimePipe,
  ],
  templateUrl: './billing-deferrals.html',
  styleUrl: './billing-deferrals.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BillingDeferralsComponent implements OnInit {
  private readonly api = inject(BillingDeferralService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly confirm = inject(ConfirmService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly rows = signal<BillingDeferral[]>([]);
  readonly loading = signal(false);
  readonly failed = signal(false);
  readonly busy = signal<string | null>(null);
  /** The row a step is being filled in for, and which step. */
  readonly acting = signal<{ id: string; step: Step } | null>(null);

  readonly policy = signal<HospitalBillingPolicy | null>(null);
  readonly policySaving = signal(false);

  stepNote = '';
  stepCover = '';
  stepLimit: number | null = null;

  ngOnInit(): void {
    this.load();
    this.api.policy().subscribe({
      next: (res) => this.policy.set(res?.data ?? null),
      error: () => this.policy.set(null),
    });
  }

  load(): void {
    this.loading.set(true);
    this.failed.set(false);
    this.api.list().subscribe({
      next: (res) => {
        this.rows.set(res?.data ?? []);
        this.loading.set(false);
      },
      error: () => {
        this.rows.set([]);
        this.loading.set(false);
        this.failed.set(true);
      },
    });
  }

  /** Whoever asked for it, or an approver, may withdraw a deferral no fill left on yet. */
  canWithdraw(row: BillingDeferral): boolean {
    return row.status === 'REQUESTED' || row.status === 'APPROVED';
  }

  start(row: BillingDeferral, step: Step): void {
    this.stepNote = '';
    this.stepCover = step === 'approve' ? (row.coverReference ?? '') : '';
    this.stepLimit = null;
    this.acting.set({ id: row.id, step });
  }

  stop(): void {
    this.acting.set(null);
  }

  isActing(row: BillingDeferral, step: Step): boolean {
    const a = this.acting();
    return a?.id === row.id && a.step === step;
  }

  fieldId(row: BillingDeferral, name: string): string {
    return `deferral-${row.id}-${name}`;
  }

  /** Whether the step's note is required: always, except the optional note of a third-party approval. */
  noteRequired(row: BillingDeferral, step: Step): boolean {
    return step !== 'approve' || row.kind === 'OUTPATIENT_CREDIT';
  }

  noteLabelKey(row: BillingDeferral, step: Step): string {
    if (step === 'approve') {
      return row.kind === 'OUTPATIENT_CREDIT'
        ? 'PHARMACY_MONEY.DEFERRAL.CREDIT_REASON'
        : 'PHARMACY_MONEY.DEFERRAL.APPROVE_NOTE';
    }
    if (step === 'reject') return 'PHARMACY_MONEY.DEFERRAL.REJECT_REASON';
    if (step === 'cancel') return 'PHARMACY_MONEY.DEFERRAL.CANCEL_REASON';
    return 'PHARMACY_MONEY.DEFERRAL.REVIEW_NOTE';
  }

  async submitStep(row: BillingDeferral, step: Step): Promise<void> {
    const note = this.stepNote.trim();
    if (step === 'approve') {
      this.approve(row, note);
      return;
    }
    if (step === 'reject') {
      if (!note) {
        this.refuse(row, 'note', 'PHARMACY_MONEY.DEFERRAL.REJECT_REASON_REQUIRED');
        return;
      }
      // QA CX-010: the reason says why, it does not ask whether.
      const sure = await this.confirm.ask({
        titleKey: 'PHARMACY_MONEY.DEFERRAL.REJECT_CONFIRM',
        confirmKey: 'PHARMACY_MONEY.DEFERRAL.REJECT',
      });
      if (!sure) return;
      this.run(row, this.api.reject(row.id, note), 'PHARMACY_MONEY.DEFERRAL.REJECT_DONE');
    } else if (step === 'cancel') {
      if (!note) {
        this.refuse(row, 'note', 'PHARMACY_MONEY.DEFERRAL.CANCEL_REASON_REQUIRED');
        return;
      }
      const sure = await this.confirm.ask({
        titleKey: 'PHARMACY_MONEY.DEFERRAL.CANCEL_CONFIRM',
        confirmKey: 'PHARMACY_MONEY.DEFERRAL.CANCEL',
      });
      if (!sure) return;
      this.run(row, this.api.cancel(row.id, note), 'PHARMACY_MONEY.DEFERRAL.CANCEL_DONE');
    } else {
      if (!note) {
        this.refuse(row, 'note', 'PHARMACY_MONEY.DEFERRAL.REVIEW_NOTE_REQUIRED');
        return;
      }
      this.run(row, this.api.review(row.id, note), 'PHARMACY_MONEY.DEFERRAL.REVIEW_DONE');
    }
  }

  private approve(row: BillingDeferral, note: string): void {
    const body: BillingDeferralDecision = {};
    if (row.kind === 'THIRD_PARTY_COVER') {
      const cover = this.stepCover.trim();
      if (!cover) {
        this.refuse(row, 'cover', 'PHARMACY_MONEY.DEFERRAL.COVER_REFERENCE_REQUIRED');
        return;
      }
      body.coverReference = cover;
    } else {
      if (!note) {
        this.refuse(row, 'note', 'PHARMACY_MONEY.DEFERRAL.CREDIT_REASON_REQUIRED');
        return;
      }
      const limit = this.stepLimit;
      if (limit !== null && limit !== undefined && `${limit}` !== '') {
        if (!Number.isInteger(Number(limit)) || Number(limit) <= 0) {
          this.refuse(row, 'limit', 'PHARMACY_MONEY.DEFERRAL.LIMIT_INVALID');
          return;
        }
        body.creditLimit = Number(limit);
      }
    }
    if (note) body.note = note;
    this.run(row, this.api.approve(row.id, body), 'PHARMACY_MONEY.DEFERRAL.APPROVE_DONE');
  }

  private run(
    row: BillingDeferral,
    call: Observable<ApiResponse<BillingDeferral>>,
    doneKey: string,
  ): void {
    if (this.busy()) return;
    this.busy.set(row.id);
    call.subscribe({
      next: () => {
        this.busy.set(null);
        this.acting.set(null);
        this.toast.success(this.translate.instant(doneKey));
        this.load();
      },
      error: (err: unknown) => {
        this.busy.set(null);
        const ids = {
          note: this.fieldId(row, 'note'),
          reason: this.fieldId(row, 'note'),
          coverReference: this.fieldId(row, 'cover'),
          creditLimit: this.fieldId(row, 'limit'),
        };
        const refused = refusedFieldReasons(err, ids);
        if (refused.length > 0) {
          refuseFields(this.host.nativeElement, [refused[0][0]], [refused[0][0]], refused[0][1]);
        }
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('PHARMACY_MONEY.DEFERRAL.STEP_FAILED'),
        );
      },
    });
  }

  private refuse(row: BillingDeferral, name: string, key: string): void {
    const message = this.translate.instant(key);
    const id = this.fieldId(row, name);
    refuseFields(this.host.nativeElement, [id], [id], message);
    this.toast.error(message);
  }

  /** Rule 6: the hospital's choice of a default consultation on planned admissions (a hospital admin). */
  setAutoConsultation(value: boolean): void {
    if (this.policySaving() || !this.policy()?.canEdit) return;
    this.policySaving.set(true);
    this.api.updatePolicy({ autoConsultationOnPlannedAdmission: value }).subscribe({
      next: (res) => {
        this.policySaving.set(false);
        this.policy.set(res?.data ?? this.policy());
        this.toast.success(this.translate.instant('PHARMACY_MONEY.POLICY.SAVED'));
      },
      error: (err: unknown) => {
        this.policySaving.set(false);
        // The box goes back to what the server holds.
        this.policy.set({ ...(this.policy() as HospitalBillingPolicy) });
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('PHARMACY_MONEY.POLICY.FAILED'),
        );
      },
    });
  }
}
