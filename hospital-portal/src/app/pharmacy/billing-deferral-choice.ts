import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { ToastService } from '../core/toast.service';
import { refusalReason } from '../shared/refusal-reason';
import { refuseFields } from '../shared/forms/invalid-fields';
import { CfaPipe } from '../shared/pipes/cfa.pipe';
import { DashPipe } from '../shared/pipes/dash.pipe';
import { KeyLabelPipe } from '../shared/pipes/key-label.pipe';
import {
  BillingDeferral,
  BillingDeferralChoice,
  BillingDeferralService,
} from '../services/billing-deferral.service';
import { RuleStatusComponent } from '../shared/rule-status/rule-status.component';

type Mode = 'approved' | 'urgency';

/**
 * Policy rule 6 (provisional, V345): what the counter does with a fill that has money due. The pharmacist no longer
 * marks it « à facturer » on their own word. An inpatient item goes on the account of the open stay; otherwise the
 * pharmacist uses a deferral somebody else approved, asks for one (billing checks a voucher, finance grants a
 * credit), or records an urgency override with a reason, which finance reviews.
 *
 * The hand-over dialog and the one-step fill both host this; the parent reads {@link choice} when it submits.
 */
@Component({
  selector: 'app-billing-deferral-choice',
  standalone: true,
  imports: [
    RuleStatusComponent,
    CommonModule,
    FormsModule,
    TranslateModule,
    CfaPipe,
    DashPipe,
    KeyLabelPipe,
  ],
  templateUrl: './billing-deferral-choice.html',
  styleUrl: './billing-deferral-choice.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BillingDeferralChoiceComponent {
  /** The prescription the fill is for. */
  readonly prescriptionId = input.required<string>();
  /** Keeps the ids of the two hosts apart (the hand-over dialog and the one-step fill). */
  readonly idPrefix = input('deferral');

  private readonly api = inject(BillingDeferralService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly open = signal<BillingDeferral[]>([]);
  /** The patient is in hospital: what is due goes on the account of the stay, nobody approves. */
  readonly onOpenStay = signal(false);
  readonly loading = signal(false);
  readonly loadFailed = signal(false);
  readonly requesting = signal(false);
  readonly mode = signal<Mode | null>(null);

  /** The deferral somebody else approved for this prescription, if any. */
  readonly approved = computed(() => this.open().find((d) => d.status === 'APPROVED') ?? null);
  /** A request still waiting for its decision. */
  readonly pending = computed(() => this.open().find((d) => d.status === 'REQUESTED') ?? null);

  urgencyReason = '';
  requestKind: 'THIRD_PARTY_COVER' | 'OUTPATIENT_CREDIT' = 'THIRD_PARTY_COVER';
  requestReason = '';
  requestCover = '';

  private loadSeq = 0;

  constructor() {
    effect(() => {
      if (this.prescriptionId()) this.reload();
    });
  }

  /** Reads the open deferrals of the prescription again (after asking, or to see a decision arrive). */
  reload(): void {
    const id = this.prescriptionId();
    if (!id) return;
    const seq = ++this.loadSeq;
    this.loading.set(true);
    this.loadFailed.set(false);
    this.api.counter(id).subscribe({
      next: (res) => {
        if (seq !== this.loadSeq) return;
        const rows = res?.data?.open ?? [];
        this.onOpenStay.set(!!res?.data?.onOpenStay);
        this.open.set(rows);
        this.loading.set(false);
        const usable = rows.find((d) => d.status === 'APPROVED' && d.canExecute);
        if (usable && this.mode() === null) this.mode.set('approved');
        if (!usable && this.mode() === 'approved') this.mode.set(null);
      },
      error: () => {
        if (seq !== this.loadSeq) return;
        this.loading.set(false);
        this.loadFailed.set(true);
      },
    });
  }

  setMode(mode: Mode): void {
    this.mode.set(mode);
  }

  fieldId(name: string): string {
    return `${this.idPrefix()}-${name}`;
  }

  /** Asks billing or finance for a deferral; decides nothing. */
  ask(): void {
    const reason = this.requestReason.trim();
    if (!reason) {
      const message = this.translate.instant('PHARMACY_MONEY.DEFERRAL.REQUEST_REASON_REQUIRED');
      const id = this.fieldId('request-reason');
      refuseFields(this.host.nativeElement, [id], [id], message);
      this.toast.error(message);
      return;
    }
    if (this.requesting()) return;
    this.requesting.set(true);
    const cover = this.requestCover.trim();
    this.api
      .request({
        prescriptionId: this.prescriptionId(),
        kind: this.requestKind,
        reason,
        ...(cover && this.requestKind === 'THIRD_PARTY_COVER' ? { coverReference: cover } : {}),
      })
      .subscribe({
        next: () => {
          this.requesting.set(false);
          this.requestReason = '';
          this.requestCover = '';
          this.toast.success(this.translate.instant('PHARMACY_MONEY.DEFERRAL.REQUEST_SENT'));
          this.reload();
        },
        error: (err: unknown) => {
          this.requesting.set(false);
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('PHARMACY_MONEY.DEFERRAL.REQUEST_FAILED'),
          );
        },
      });
  }

  /**
   * What travels with the fill: nothing for an inpatient item, the approved deferral, or the reason of the urgency
   * override. Null when the choice is not complete: the missing part is marked and said.
   */
  choice(): BillingDeferralChoice | null {
    if (this.onOpenStay()) return {};
    const approved = this.approved();
    if (this.mode() === 'approved' && approved?.canExecute) {
      return { billingDeferralId: approved.id };
    }
    if (this.mode() === 'urgency') {
      const reason = this.urgencyReason.trim();
      if (!reason) {
        this.refuse('urgency-reason', 'PHARMACY_MONEY.DEFERRAL.URGENCY_REASON_REQUIRED');
        return null;
      }
      return { billingUrgencyReason: reason };
    }
    this.refuse('mode-urgency', 'PHARMACY_MONEY.DEFERRAL.CHOICE_REQUIRED');
    return null;
  }

  private refuse(name: string, key: string): void {
    const message = this.translate.instant(key);
    const id = this.fieldId(name);
    refuseFields(this.host.nativeElement, [id], [id], message);
    this.toast.error(message);
  }
}
