import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { Observable } from 'rxjs';

import { FormControlStyleDirective } from '../../shared/forms/form-control-style.directive';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import {
  AgeingReport,
  ClaimWriteOff,
  InsurerClaimsService,
  ReconciliationReport,
} from '../../services/insurer-claims.service';
import { CfaPipe } from '../../shared/pipes/cfa.pipe';
import { formatCfa } from '../../shared/i18n/app-locale';
import { DayMonthPipe } from '../../shared/pipes/day-month.pipe';
import { KeyLabelPipe } from '../../shared/pipes/key-label.pipe';
import { refusalReason } from '../../shared/refusal-reason';
import { refuseFields } from '../../shared/forms/invalid-fields';
import { ConfirmService } from '../../shared/confirm-dialog/confirm.service';
import { saveResponse } from '../../shared/download-file';
import { ClaimsNavComponent } from './claims-nav.component';

/**
 * Insurer claims PR 4 (QA D3983): « Créances assureurs ». What each payer owes, by days since its bordereaux were
 * sent; the reconciliation of the bordereaux sent in a period (claimed = paid + re-billed + written off +
 * outstanding) beside the money received; and the write-off requests that wait for a second person. The hospital
 * admin and the accountant decide a write-off, never the person who asked for it.
 */
@Component({
  selector: 'app-insurer-receivables',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterLink,
    TranslateModule,
    FormControlStyleDirective,
    CfaPipe,
    DayMonthPipe,
    KeyLabelPipe,
    ClaimsNavComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './insurer-receivables.component.html',
  styleUrl: './insurer-claims.scss',
})
export class InsurerReceivablesComponent {
  private readonly claims = inject(InsurerClaimsService);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly confirm = inject(ConfirmService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly isFinance = this.roleContext.hasAnyActiveRole([
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_ACCOUNTANT',
  ]);
  readonly canBuild = this.roleContext.hasAnyActiveRole([
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_ACCOUNTANT',
    'ROLE_BILLING_SPECIALIST',
    'ROLE_CLAIMS_REVIEWER',
  ]);

  readonly ageing = signal<AgeingReport | null>(null);
  readonly ageingFailed = signal(false);
  readonly reconciliation = signal<ReconciliationReport | null>(null);
  readonly reconciliationFailed = signal(false);
  readonly fromDate = signal('');
  readonly toDate = signal('');
  readonly writeOffs = signal<ClaimWriteOff[]>([]);
  readonly writeOffsFailed = signal(false);
  readonly busy = signal(false);
  readonly refusing = signal<string | null>(null);
  readonly refuseReason = signal('');

  constructor() {
    this.loadAgeing();
    this.loadReconciliation();
    this.loadWriteOffs();
  }

  loadAgeing(): void {
    this.claims
      .ageing()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (report) => {
          const usable = Array.isArray(report?.rows) && !!report.total;
          this.ageing.set(usable ? report : null);
          this.ageingFailed.set(!usable);
        },
        error: () => this.ageingFailed.set(true),
      });
  }

  loadReconciliation(): void {
    if (this.fromDate() && this.toDate() && this.toDate() < this.fromDate()) {
      const problem = this.translate.instant('INSURER_CLAIMS.SHARES.PERIOD_REVERSED');
      refuseFields(this.host.nativeElement, ['receivables-to'], ['receivables-to'], problem);
      this.toast.error(problem);
      return;
    }
    this.claims
      .reconciliation({ fromDate: this.fromDate() || null, toDate: this.toDate() || null })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (report) => {
          const usable = Array.isArray(report?.rows) && !!report.total;
          this.reconciliation.set(usable ? report : null);
          this.reconciliationFailed.set(!usable);
        },
        error: () => this.reconciliationFailed.set(true),
      });
  }

  loadWriteOffs(): void {
    this.claims
      .listWriteOffs(['REQUESTED'])
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (rows) => {
          this.writeOffs.set(Array.isArray(rows) ? rows : []);
          this.writeOffsFailed.set(false);
        },
        error: () => this.writeOffsFailed.set(true),
      });
  }

  exportAgeing(): void {
    this.claims
      .exportAgeing()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => saveResponse(response, 'creances-assureurs.csv'),
        error: () => this.toast.error(this.translate.instant('INSURER_CLAIMS.BATCH.EXPORT_FAILED')),
      });
  }

  exportReconciliation(): void {
    this.claims
      .exportReconciliation({ fromDate: this.fromDate() || null, toDate: this.toDate() || null })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => saveResponse(response, 'rapprochement-assureurs.csv'),
        error: () => this.toast.error(this.translate.instant('INSURER_CLAIMS.BATCH.EXPORT_FAILED')),
      });
  }

  /* ── Write-offs ── */

  canDecide(row: ClaimWriteOff): boolean {
    return this.isFinance && !row.requestedByMe;
  }

  canWithdraw(row: ClaimWriteOff): boolean {
    return this.canBuild && (row.requestedByMe || this.isFinance);
  }

  async approve(row: ClaimWriteOff): Promise<void> {
    const sure = await this.confirm.ask({
      titleKey: 'INSURER_CLAIMS.WRITE_OFFS.APPROVE_CONFIRM',
      params: { amount: formatCfa(row.amount, 'XOF'), payer: row.payerName ?? '' },
      confirmKey: 'INSURER_CLAIMS.WRITE_OFFS.APPROVE',
    });
    if (sure)
      this.decide(this.claims.approveWriteOff(row.id), 'INSURER_CLAIMS.WRITE_OFFS.APPROVED');
  }

  startRefuse(row: ClaimWriteOff): void {
    this.refusing.set(row.id);
    this.refuseReason.set('');
  }

  confirmRefuse(row: ClaimWriteOff): void {
    const reason = this.refuseReason().trim();
    if (!reason) {
      const problem = this.translate.instant('INSURER_CLAIMS.BATCH.REASON_REQUIRED');
      refuseFields(
        this.host.nativeElement,
        ['write-off-refuse-reason'],
        ['write-off-refuse-reason'],
        problem,
      );
      this.toast.error(problem);
      return;
    }
    this.decide(this.claims.refuseWriteOff(row.id, reason), 'INSURER_CLAIMS.WRITE_OFFS.REFUSED');
  }

  async withdraw(row: ClaimWriteOff): Promise<void> {
    const sure = await this.confirm.ask({
      titleKey: 'INSURER_CLAIMS.WRITE_OFFS.WITHDRAW_CONFIRM',
      confirmKey: 'INSURER_CLAIMS.WRITE_OFFS.WITHDRAW',
    });
    if (sure)
      this.decide(this.claims.cancelWriteOff(row.id), 'INSURER_CLAIMS.WRITE_OFFS.WITHDRAWN');
  }

  private decide(call: Observable<ClaimWriteOff>, doneKey: string): void {
    if (this.busy()) return;
    this.busy.set(true);
    call.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.busy.set(false);
        this.refusing.set(null);
        this.toast.success(this.translate.instant(doneKey));
        this.loadWriteOffs();
        this.loadAgeing();
        this.loadReconciliation();
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('INSURER_CLAIMS.BATCH.FAILED'),
        );
      },
    });
  }
}
