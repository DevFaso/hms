import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  inject,
  input,
  OnInit,
  signal,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { OrderReviewRow, OrderReviewService } from '../../services/order-review.service';
import { BackdropCloseDirective } from '../../shared/a11y/backdrop-close.directive';
import { ModalDialogDirective } from '../../shared/a11y/modal-dialog.directive';
import { ConfirmService } from '../../shared/confirm-dialog/confirm.service';
import { SubmitGuardDirective } from '../../shared/forms/submit-guard.directive';
import { refuseFields } from '../../shared/forms/invalid-fields';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { refusalReason, refusedFieldReasons } from '../../shared/refusal-reason';

type ReviewAction = 'continue' | 'replace' | 'stop' | 'assessment';

const FIELD_IDS = {
  reason: 'ore-reason',
  replacementOrderId: 'ore-replacement',
  orderVersion: 'ore-reason',
  disputeId: 'ore-reason',
};

/** The role of signing a prescription; a blood request takes the roles that may request blood. */
const PRESCRIPTION_ROLES = ['ROLE_DOCTOR'];
const BLOOD_ROLES = ['ROLE_DOCTOR', 'ROLE_SURGEON', 'ROLE_MIDWIFE'];
const CLINICIAN_ROLES = [...BLOOD_ROLES, 'ROLE_NURSE'];
const READER_ROLES = [...CLINICIAN_ROLES, 'ROLE_PHARMACIST'];

/**
 * The review list on a chart: orders that existed when a recorded death was disputed, each continued, replaced or
 * stopped by a prescriber, and treatment running at that moment, assessed by a clinician. Rendered only when there is
 * a row, and it stays after the dispute's outcome: no outcome clears a hold.
 */
@Component({
  selector: 'app-order-review-panel',
  standalone: true,
  imports: [
    DatePipe,
    FormsModule,
    TranslateModule,
    BackdropCloseDirective,
    ModalDialogDirective,
    ServerTimePipe,
    SubmitGuardDirective,
  ],
  changeDetection: ChangeDetectionStrategy.Eager,
  templateUrl: './order-review-panel.component.html',
  styleUrl: './order-review-panel.component.scss',
})
export class OrderReviewPanelComponent implements OnInit {
  private readonly reviews = inject(OrderReviewService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly confirm = inject(ConfirmService);
  private readonly roles = inject(RoleContextService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly patientId = input('');

  rows = signal<OrderReviewRow[]>([]);
  target = signal<OrderReviewRow | null>(null);
  action = signal<ReviewAction>('continue');
  submitting = signal(false);

  reason = '';
  replacementOrderId = '';
  password = '';
  mfaCode = '';

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    if (!this.patientId() || !this.roles.hasAnyActiveRole(READER_ROLES)) return;
    this.reviews.forPatient(this.patientId()).subscribe({
      next: (rows) => {
        this.rows.set(rows);
        // An order just written becomes a choice in the window already open.
        const open = this.target();
        if (open) this.target.set(rows.find((row) => row.id === open.id) ?? open);
      },
      // A role that may not read the list sees no list; the server still refuses every held use.
      error: () => this.rows.set([]),
    });
  }

  canDecide(row: OrderReviewRow): boolean {
    const roles = row.orderType === 'PRESCRIPTION' ? PRESCRIPTION_ROLES : BLOOD_ROLES;
    return row.status === 'HELD' && this.roles.hasAnyActiveRole(roles);
  }

  canAssess(row: OrderReviewRow): boolean {
    return row.status === 'ASSESSMENT_DUE' && this.roles.hasAnyActiveRole(CLINICIAN_ROLES);
  }

  open(row: OrderReviewRow, action: ReviewAction): void {
    this.reason = '';
    this.replacementOrderId = '';
    this.password = '';
    this.mfaCode = '';
    this.action.set(action);
    this.target.set(row);
    if (action === 'replace') this.load();
  }

  close(): void {
    this.target.set(null);
  }

  /** What the submit guard refuses: a reason always, the new order for a replacement. */
  incomplete(): boolean {
    return !this.reason.trim() || (this.action() === 'replace' && !this.replacementOrderId);
  }

  guardedFields(): string[] {
    return this.action() === 'replace' ? ['ore-reason', 'ore-replacement'] : ['ore-reason'];
  }

  async submit(): Promise<void> {
    const row = this.target();
    const reason = this.reason.trim();
    if (!row || this.incomplete()) return;
    const action = this.action();
    if (
      !(await this.confirm.ask({
        titleKey: `ORDER_REVIEW.CONFIRM_${action.toUpperCase()}`,
        confirmKey: `ORDER_REVIEW.ACTION_${action.toUpperCase()}`,
      }))
    )
      return;
    this.submitting.set(true);
    this.reviews
      .decide(row.id, action, {
        reason,
        orderVersion: row.currentOrderVersion,
        disputeId: row.disputeId,
        replacementOrderId: action === 'replace' ? this.replacementOrderId : null,
        password: this.password || null,
        mfaCode: this.mfaCode || null,
      })
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('ORDER_REVIEW.DONE'));
          this.submitting.set(false);
          this.target.set(null);
          this.load();
        },
        error: (err: unknown) => {
          this.toast.error(refusalReason(err) ?? this.translate.instant('ORDER_REVIEW.FAILED'));
          this.submitting.set(false);
          const refused = refusedFieldReasons(err, FIELD_IDS);
          if (refused.length > 0) {
            refuseFields(this.host.nativeElement, [], Object.values(FIELD_IDS), '');
            for (const [id, text] of [...refused].reverse()) {
              refuseFields(this.host.nativeElement, [id], [id], text);
            }
          }
          // The order may have changed under the reader: show them what it says now.
          this.load();
        },
      });
  }
}
