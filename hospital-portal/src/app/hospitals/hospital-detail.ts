import { CommonModule, DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  Component,
  OnInit,
  computed,
  inject,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { catchError, forkJoin, of } from 'rxjs';

import {
  HospitalLifecycleResponse,
  HospitalLifecycleState,
} from '../services/hospital-lifecycle.model';
import { HospitalLifecycleService } from '../services/hospital-lifecycle.service';
import { HospitalResponse, HospitalService } from '../services/hospital.service';
import { isStepUpAction, isStepUpRefusal, stepUpRefusalKey } from '../services/step-up';
import { localDateString } from '../shared/date-utils';
import { ServerTimePipe } from '../shared/pipes/server-time.pipe';

type ActionKind = 'suspend' | 'restore' | 'archive' | 'schedule-purge' | 'cancel-purge';

interface ActionDialog {
  kind: ActionKind;
  reason: string;
  scheduledFor: string;
  /** The current authenticator or backup code, sent as X-Mfa-Token on a step-up action. */
  mfaCode: string;
  busy: boolean;
  errorKey: string | null;
}

@Component({
  selector: 'app-hospital-detail',
  standalone: true,
  imports: [ServerTimePipe, CommonModule, FormsModule, RouterLink, TranslateModule, DatePipe],
  templateUrl: './hospital-detail.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './hospital-detail.scss',
})
export class HospitalDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly hospitalService = inject(HospitalService);
  private readonly lifecycleService = inject(HospitalLifecycleService);

  readonly hospitalId = signal<string>('');
  readonly hospital = signal<HospitalResponse | null>(null);
  readonly lifecycle = signal<HospitalLifecycleResponse | null>(null);
  readonly loading = signal(true);
  readonly errored = signal(false);
  readonly dialog = signal<ActionDialog | null>(null);

  readonly state = computed<HospitalLifecycleState | null>(
    () => this.lifecycle()?.lifecycleState ?? null,
  );

  readonly canSuspend = computed(() => this.state() === 'ACTIVE');
  readonly canRestore = computed(() => this.state() === 'SUSPENDED' || this.state() === 'ARCHIVED');
  readonly canArchive = computed(() => this.state() === 'ACTIVE' || this.state() === 'SUSPENDED');
  readonly canSchedulePurge = computed(() => this.state() === 'ARCHIVED');
  readonly canCancelPurge = computed(() => this.state() === 'PENDING_PURGE');

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      this.errored.set(true);
      this.loading.set(false);
      return;
    }
    this.hospitalId.set(id);
    this.refresh();
  }

  refresh(): void {
    this.loading.set(true);
    this.errored.set(false);

    // Copilot review fix — coordinate both fetches with forkJoin so
    // loading flips off only after BOTH calls complete; the prior
    // staggered subscribe could clear loading before the hospital
    // request had returned.
    forkJoin({
      hospital: this.hospitalService.getById(this.hospitalId()).pipe(catchError(() => of(null))),
      lifecycle: this.lifecycleService.get(this.hospitalId()).pipe(catchError(() => of(null))),
    }).subscribe(({ hospital, lifecycle }) => {
      if (hospital === null || lifecycle === null) {
        this.errored.set(true);
      }
      if (hospital !== null) {
        this.hospital.set(hospital);
      }
      if (lifecycle !== null) {
        this.lifecycle.set(lifecycle);
      }
      this.loading.set(false);
    });
  }

  // ── Action dialog ────────────────────────────────────────────────────

  openDialog(kind: ActionKind): void {
    this.dialog.set({
      kind,
      reason: '',
      scheduledFor: this.defaultPurgeDate(),
      mfaCode: '',
      busy: false,
      errorKey: null,
    });
  }

  closeDialog(): void {
    this.dialog.set(null);
  }

  patchDialog<K extends keyof ActionDialog>(key: K, value: ActionDialog[K]): void {
    this.dialog.update((current) => (current ? { ...current, [key]: value } : current));
  }

  submitDialog(): void {
    const state = this.dialog();
    // The button's rules again: a dialog left open past midnight, or Enter.
    if (!state || state.busy || !this.isReasonValid(state) || !this.isPurgeDateValid(state)) return;
    this.dialog.set({ ...state, busy: true, errorKey: null });

    this.dispatchLifecycleAction(state)
      .pipe(
        catchError((error: unknown) => {
          // A refused step-up (401 mfa_required, or 403 with a reason) stays in
          // the dialog, in the user's language: the code is asked again, the
          // session is untouched. Only a step-up action's refusal is one.
          const stepUp =
            this.needsStepUp(state.kind) &&
            error instanceof HttpErrorResponse &&
            isStepUpRefusal(error);
          this.dialog.update((current) =>
            current
              ? {
                  ...current,
                  busy: false,
                  errorKey: stepUp
                    ? stepUpRefusalKey(error as HttpErrorResponse)
                    : 'HOSPITAL_LIFECYCLE.ERROR.ACTION_FAILED',
                }
              : current,
          );
          return of(null);
        }),
      )
      .subscribe((updated) => {
        if (!updated) return;
        this.lifecycle.set(updated);
        this.dialog.set(null);
      });
  }

  /**
   * Dispatch the lifecycle service call matching {@code state.kind}.
   * Extracted from {@link submitDialog} so the ternary chain doesn't
   * trip Sonar's nested-ternary rule (S3358).
   */
  private dispatchLifecycleAction(state: ActionDialog) {
    const id = this.hospitalId();
    const reason = state.reason.trim();
    switch (state.kind) {
      case 'suspend':
        return this.lifecycleService.suspend(id, { reason }, state.mfaCode);
      case 'restore':
        return this.lifecycleService.restore(id);
      case 'archive':
        return this.lifecycleService.archive(id, { reason }, state.mfaCode);
      case 'schedule-purge':
        return this.lifecycleService.schedulePurge(
          id,
          { reason, purgeScheduledFor: purgeInstant(state.scheduledFor) ?? undefined },
          state.mfaCode,
        );
      case 'cancel-purge':
        return this.lifecycleService.cancelPurge(id);
    }
  }

  // ── helpers ──────────────────────────────────────────────────────────

  /** The actions the backend guards with an MFA step-up (X-Mfa-Token). */
  needsStepUp(kind: ActionKind): boolean {
    return isStepUpAction(kind);
  }

  needsReason(kind: ActionKind): boolean {
    return kind === 'suspend' || kind === 'archive' || kind === 'schedule-purge';
  }

  needsScheduledFor(kind: ActionKind): boolean {
    return kind === 'schedule-purge';
  }

  isReasonValid(state: ActionDialog): boolean {
    if (!this.needsReason(state.kind)) return true;
    return state.reason.trim().length >= 5;
  }

  /**
   * The earliest purge day the dialog offers: tomorrow. The purge starts at
   * the beginning of the picked day, so today is already past (the server
   * refuses it) and a cleared field means the server's 30-day default.
   */
  get minPurgeDate(): string {
    // Read each time: a page left open past midnight moves with the clock.
    return localDay(1);
  }

  isPurgeDateValid(state: ActionDialog): boolean {
    if (!this.needsScheduledFor(state.kind) || !state.scheduledFor) return true;
    return state.scheduledFor >= this.minPurgeDate;
  }

  /**
   * MVP-2 default — schedule purge 30 days out unless the operator
   * picks a different date.
   */
  private defaultPurgeDate(): string {
    return localDay(30);
  }

  stateColor(state: HospitalLifecycleState | null): string {
    switch (state) {
      case 'ACTIVE':
        return '#10b981';
      case 'SUSPENDED':
        return '#f59e0b';
      case 'ARCHIVED':
        return '#64748b';
      case 'PENDING_PURGE':
        return '#dc2626';
      case 'PURGED':
        return '#1e293b';
      default:
        return '#94a3b8';
    }
  }
}

/** `YYYY-MM-DD` of the local day `offset` days from today. */
export function localDay(offset: number): string {
  const d = new Date();
  d.setDate(d.getDate() + offset);
  return localDateString(d);
}

/**
 * The picked purge day (`YYYY-MM-DD` from the date input) as the ISO instant
 * the backend reads: the start of that day in the operator's time zone.
 */
export function purgeInstant(day: string | null | undefined): string | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(day ?? '');
  if (!match) {
    // A cleared date: send none, and the server applies its 30-day default.
    return null;
  }
  return new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3])).toISOString();
}
