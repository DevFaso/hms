import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { Observable, Subscription } from 'rxjs';

import { ToastService } from '../../../core/toast.service';
import { HospitalLifecycleResponse } from '../../../services/hospital-lifecycle.model';
import { HospitalLifecycleService } from '../../../services/hospital-lifecycle.service';
import {
  PROVIDER_STAFF_ROLES,
  ProviderEvidence,
  ProviderResponse,
  ProviderVerificationHistoryEntry,
  evidenceOf,
  isEvidenceComplete,
  isNotAvailable,
  isProviderFacilityType,
  normaliseEvidence,
  serverMessage,
} from '../../../services/provider.model';
import { SuperAdminProviderService } from '../../../services/super-admin-provider.service';
import { isStepUpAction, isStepUpRefusal, stepUpRefusalKey } from '../../../services/step-up';
import { UserDetail } from '../../../services/user.service';
import { deliveryWarningKeys } from '../../../shared/delivery-warnings';
import { EnumLabelPipe } from '../../../shared/pipes/enum-label.pipe';
import { ProviderStaffRegisterComponent } from '../../../provider/provider-staff-register/provider-staff-register';
import { ProviderEvidenceFormComponent } from '../provider-evidence-form/provider-evidence-form';
import { lifecycleBadgeClass, verificationBadgeClass } from '../provider-status';
import { ServerTimePipe } from '../../../shared/pipes/server-time.pipe';
import { ModalDialogDirective } from '../../../shared/a11y/modal-dialog.directive';

import { SubmitGuardDirective } from '../../../shared/forms/submit-guard.directive';
/** A verification decision taken with a reason. */
type DecisionKind = 'reject' | 'revoke';

/** The hospital-lifecycle actions offered here; purge stays on the hospital lifecycle page. */
type LifecycleKind = 'suspend' | 'restore' | 'archive';

interface VerifyDialog {
  ifuMatchesRccm: boolean;
  cnssMatchesRccm: boolean;
  evidenceNote: string;
  correcting: boolean;
  corrections: ProviderEvidence;
}

/** The minimum reason length the hospital lifecycle service accepts. */
const LIFECYCLE_REASON_MIN = 5;

/**
 * One provider facility for the super-admin (provider plan P1-T12, AC-1 to
 * AC-4, AC-16): its captured evidence, its verification history, and every
 * action the backend allows in its current state. The actions are offered
 * from the state the server reported; the server re-checks every one of them.
 */
@Component({
  selector: 'app-provider-detail',
  standalone: true,
  imports: [
    SubmitGuardDirective,
    ServerTimePipe,
    DatePipe,
    FormsModule,
    RouterLink,
    TranslateModule,
    EnumLabelPipe,
    ProviderEvidenceFormComponent,
    ProviderStaffRegisterComponent,
    ModalDialogDirective,
  ],
  templateUrl: './provider-detail.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './provider-detail.scss',
})
export class ProviderDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly service = inject(SuperAdminProviderService);
  private readonly lifecycleService = inject(HospitalLifecycleService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  readonly verificationBadge = verificationBadgeClass;
  readonly lifecycleBadge = lifecycleBadgeClass;

  readonly providerId = signal('');
  readonly provider = signal<ProviderResponse | null>(null);
  readonly history = signal<ProviderVerificationHistoryEntry[]>([]);
  readonly historyFailed = signal(false);
  readonly loading = signal(true);
  readonly notFound = signal(false);
  readonly loadFailed = signal(false);

  readonly verifyDialog = signal<VerifyDialog | null>(null);
  readonly decisionDialog = signal<{ kind: DecisionKind; reason: string } | null>(null);
  readonly lifecycleDialog = signal<{
    kind: LifecycleKind;
    reason: string;
    mfaCode: string;
  } | null>(null);
  readonly registerOpen = signal(false);
  readonly busy = signal(false);
  readonly dialogError = signal<string | null>(null);

  readonly status = computed(() => this.provider()?.verificationStatus ?? null);
  readonly state = computed(() => this.provider()?.lifecycleState ?? null);

  /** VERIFY activates a provider waiting for it: SUBMITTED evidence on a SUSPENDED facility. */
  readonly canVerify = computed(
    () => this.status() === 'SUBMITTED' && this.state() === 'SUSPENDED',
  );
  readonly canReject = computed(() => this.status() === 'SUBMITTED');
  readonly canResubmit = computed(
    () => this.status() === 'REJECTED' || this.status() === 'REVOKED',
  );
  readonly canRevoke = computed(() => this.status() === 'VERIFIED');

  readonly canSuspend = computed(() => this.state() === 'ACTIVE');
  /**
   * Restore brings a SUSPENDED provider back only when it is VERIFIED (else
   * 409 provider.not-verified: VERIFY is the only way to ACTIVE); an
   * ARCHIVED one comes back, unverified to SUSPENDED.
   */
  readonly canRestore = computed(
    () =>
      (this.state() === 'SUSPENDED' && this.status() === 'VERIFIED') || this.state() === 'ARCHIVED',
  );
  readonly canArchive = computed(() => this.state() === 'ACTIVE' || this.state() === 'SUSPENDED');

  /** The roles the platform may register at this facility: its admin, and its staff roles. */
  readonly registerRoles = computed(() => {
    const type = this.provider()?.facilityType;
    return isProviderFacilityType(type)
      ? ['PROVIDER_ADMIN', ...PROVIDER_STAFF_ROLES[type]]
      : ['PROVIDER_ADMIN'];
  });

  readonly verifyReady = computed(() => {
    const dialog = this.verifyDialog();
    return (
      !!dialog &&
      dialog.ifuMatchesRccm &&
      dialog.cnssMatchesRccm &&
      (!dialog.correcting || isEvidenceComplete(dialog.corrections))
    );
  });

  ngOnInit(): void {
    this.providerId.set(this.route.snapshot.paramMap.get('id') ?? '');
    this.refresh();
  }

  /** The reads in flight: a newer refresh cancels the older one, so it can never land after it. */
  private refreshSub?: Subscription;
  private historySub?: Subscription;

  refresh(): void {
    this.refreshSub?.unsubscribe();
    this.historySub?.unsubscribe();
    const id = this.providerId();
    this.loading.set(true);
    this.notFound.set(false);
    this.loadFailed.set(false);
    this.refreshSub = this.service.get(id).subscribe({
      next: (provider) => {
        this.provider.set(provider);
        this.loading.set(false);
        this.loadHistory();
      },
      error: (err: unknown) => {
        if (isNotAvailable(err)) {
          this.notFound.set(true);
        } else {
          this.loadFailed.set(true);
        }
        this.loading.set(false);
      },
    });
  }

  private loadHistory(): void {
    this.historySub?.unsubscribe();
    this.historyFailed.set(false);
    this.historySub = this.service.history(this.providerId()).subscribe({
      next: (rows) => this.history.set(rows),
      error: () => {
        this.history.set([]);
        this.historyFailed.set(true);
      },
    });
  }

  // ── verify ────────────────────────────────────────────────────────────

  openVerify(): void {
    const provider = this.provider();
    if (!provider) return;
    this.dialogError.set(null);
    this.verifyDialog.set({
      ifuMatchesRccm: false,
      cnssMatchesRccm: false,
      evidenceNote: '',
      correcting: false,
      corrections: evidenceOf(provider),
    });
  }

  patchVerify(patch: Partial<VerifyDialog>): void {
    this.verifyDialog.update((dialog) => (dialog ? { ...dialog, ...patch } : dialog));
  }

  submitVerify(): void {
    const dialog = this.verifyDialog();
    if (!dialog || !this.verifyReady()) return;
    this.run(
      this.service.verify(this.providerId(), {
        ifuMatchesRccm: dialog.ifuMatchesRccm,
        cnssMatchesRccm: dialog.cnssMatchesRccm,
        evidenceNote: dialog.evidenceNote.trim() || null,
        corrections: dialog.correcting ? normaliseEvidence(dialog.corrections) : null,
      }),
      'PROVIDER.DETAIL.VERIFIED',
    );
  }

  // ── reject / revoke ───────────────────────────────────────────────────

  openDecision(kind: DecisionKind): void {
    this.dialogError.set(null);
    this.decisionDialog.set({ kind, reason: '' });
  }

  setDecisionReason(reason: string): void {
    this.decisionDialog.update((dialog) => (dialog ? { ...dialog, reason } : dialog));
  }

  submitDecision(): void {
    const dialog = this.decisionDialog();
    if (!dialog || dialog.reason.trim() === '') return;
    const body = { reason: dialog.reason.trim() };
    const request =
      dialog.kind === 'reject'
        ? this.service.reject(this.providerId(), body)
        : this.service.revoke(this.providerId(), body);
    this.run(
      request,
      dialog.kind === 'reject' ? 'PROVIDER.DETAIL.REJECTED' : 'PROVIDER.DETAIL.REVOKED',
    );
  }

  // ── hospital lifecycle (suspend / restore / archive) ─────────────────

  openLifecycle(kind: LifecycleKind): void {
    this.dialogError.set(null);
    this.lifecycleDialog.set({ kind, reason: '', mfaCode: '' });
  }

  setLifecycleReason(reason: string): void {
    this.lifecycleDialog.update((dialog) => (dialog ? { ...dialog, reason } : dialog));
  }

  setLifecycleMfaCode(mfaCode: string): void {
    this.lifecycleDialog.update((dialog) => (dialog ? { ...dialog, mfaCode } : dialog));
  }

  /** Suspend and archive are MFA step-up actions on the backend (X-Mfa-Token); restore is not. */
  lifecycleNeedsStepUp(kind: LifecycleKind): boolean {
    return isStepUpAction(kind);
  }

  lifecycleNeedsReason(kind: LifecycleKind): boolean {
    return kind !== 'restore';
  }

  lifecycleReady(): boolean {
    const dialog = this.lifecycleDialog();
    if (!dialog) return false;
    return (
      !this.lifecycleNeedsReason(dialog.kind) || dialog.reason.trim().length >= LIFECYCLE_REASON_MIN
    );
  }

  submitLifecycle(): void {
    const dialog = this.lifecycleDialog();
    if (!dialog || !this.lifecycleReady()) return;
    const id = this.providerId();
    const reason = dialog.reason.trim();
    let request: Observable<HospitalLifecycleResponse>;
    let doneKey: string;
    const stepUp = this.lifecycleNeedsStepUp(dialog.kind);
    switch (dialog.kind) {
      case 'suspend':
        request = this.lifecycleService.suspend(id, { reason }, dialog.mfaCode);
        doneKey = 'PROVIDER.DETAIL.SUSPENDED';
        break;
      case 'archive':
        request = this.lifecycleService.archive(id, { reason }, dialog.mfaCode);
        doneKey = 'PROVIDER.DETAIL.ARCHIVED';
        break;
      default:
        request = this.lifecycleService.restore(id);
        doneKey = 'PROVIDER.DETAIL.RESTORED';
    }
    this.run(request, doneKey, stepUp);
  }

  // ── first administrator (AC-5) ────────────────────────────────────────

  openRegister(): void {
    this.registerOpen.set(true);
  }

  closeRegister(): void {
    this.registerOpen.set(false);
  }

  onRegistered(user: UserDetail): void {
    this.registerOpen.set(false);
    this.toast.success(this.translate.instant('PROVIDER.REGISTER.DONE'));
    for (const key of deliveryWarningKeys(user.activationDelivery)) {
      this.toast.warning(this.translate.instant(key));
    }
  }

  closeDialogs(): void {
    if (this.busy()) return;
    this.verifyDialog.set(null);
    this.decisionDialog.set(null);
    this.lifecycleDialog.set(null);
    this.dialogError.set(null);
  }

  /**
   * Run one action; on success close the dialog, say so and re-read the
   * provider from the server. `stepUp` marks the actions that send an MFA
   * step-up (suspend, archive): only their refusal is a step-up refusal.
   */
  private run(request: Observable<unknown>, doneKey: string, stepUp = false): void {
    this.busy.set(true);
    this.dialogError.set(null);
    request.subscribe({
      next: () => {
        this.busy.set(false);
        this.closeDialogs();
        this.toast.success(this.translate.instant(doneKey));
        this.refresh();
      },
      error: (err: unknown) => {
        this.busy.set(false);
        // A refused MFA step-up stays in the dialog, in the user's language
        // (the server's text is an English code); the error interceptor
        // leaves the session alone for it. Only a step-up action's refusal
        // is one: a 403 on verify, reject or revoke is an ordinary refusal.
        if (stepUp && err instanceof HttpErrorResponse && isStepUpRefusal(err)) {
          this.dialogError.set(this.translate.instant(stepUpRefusalKey(err)));
          return;
        }
        this.dialogError.set(
          serverMessage(err) ?? this.translate.instant('PROVIDER.DETAIL.ACTION_FAILED'),
        );
      },
    });
  }
}
