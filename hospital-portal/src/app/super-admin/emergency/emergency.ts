import { HttpErrorResponse } from '@angular/common/http';
import {
  Component,
  DestroyRef,
  ElementRef,
  computed,
  inject,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { EmergencyControlService } from '../../services/emergency-control.service';
import { EmergencyActionResponse } from '../../services/emergency-control.model';
import { DowntimeService, DowntimeStatus } from '../../services/downtime.service';
import { UserService, UserSummary } from '../../services/user.service';
import { HospitalResponse, HospitalService } from '../../services/hospital.service';
import { Subject, debounceTime, switchMap, of, catchError, map } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RoleLabelPipe } from '../../shared/pipes/role-label.pipe';
import { isStepUpRefusal, stepUpRefusalKey } from '../../services/step-up';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { ListEscapeDirective } from '../../shared/a11y/list-escape.directive';
import { SubmitGuardDirective } from '../../shared/forms/submit-guard.directive';
import { ConfirmService } from '../../shared/confirm-dialog/confirm.service';
import { refuseFields } from '../../shared/forms/invalid-fields';

interface PanelState {
  busy: boolean;
  result: EmergencyActionResponse | null;
  error: string | null;
}

const FRESH_PANEL: PanelState = { busy: false, result: null, error: null };

interface SearchOutcome {
  users: UserSummary[];
  failed: boolean;
}

interface HospitalsOutcome {
  hospitals: HospitalResponse[];
  failed: boolean;
}

/**
 * Typed confirmation for a platform-wide MFA reset. Deliberately NOT
 * translated — like FORCE LOGOUT ALL, it must match exactly in every
 * locale (EMERGENCY.FORCE_MFA.RESET_ALL_PLACEHOLDER shows it).
 */
export const RESET_ALL_PHRASE = 'RESET ALL MFA';

const DOWNTIME_MESSAGE_ID = 'emergency-downtime-message';

@Component({
  selector: 'app-super-admin-emergency',
  standalone: true,
  imports: [
    ListEscapeDirective,
    SubmitGuardDirective,
    ServerTimePipe,
    CommonModule,
    FormsModule,
    RouterLink,
    TranslateModule,
    DatePipe,
    RoleLabelPipe,
  ],
  templateUrl: './emergency.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './emergency.scss',
})
export class EmergencyComponent {
  private readonly service = inject(EmergencyControlService);
  private readonly downtimeService = inject(DowntimeService);
  private readonly translate = inject(TranslateService);
  private readonly userService = inject(UserService);
  private readonly hospitalService = inject(HospitalService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly confirm = inject(ConfirmService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /* ── Downtime read-only mode (P3 #23a) ── */
  readonly downtimeStatus = signal<DowntimeStatus | null>(null);
  readonly downtimeMessage = signal('');
  readonly downtimeBusy = signal(false);
  readonly downtimeError = signal('');

  constructor() {
    this.downtimeService.load();
    // The shared signal already polls in the shell; mirror it here so the
    // card reflects the current state on entry.
    this.refreshDowntimeCard();

    // MFA-reset picker: search users by name as the operator types. Nobody
    // knows a UUID by heart; the API still receives ids, resolved here.
    // No distinctUntilChanged: after a pick clears the box, retyping the
    // same two letters must search again.
    this.mfaSearch$
      .pipe(
        debounceTime(300),
        switchMap((q) => {
          const term = q.trim();
          if (term.length < 2) {
            this.mfaSearching.set(false);
            return of<SearchOutcome>({ users: [], failed: false });
          }
          this.mfaSearching.set(true);
          return this.userService.search(0, 10, { name: term }).pipe(
            map((page): SearchOutcome => ({ users: page.content ?? [], failed: false })),
            // A failed directory call is NOT "no user matches": on a
            // destructive control that reading nudges the operator toward
            // the reset-everyone path. Say the search failed.
            catchError(() => of<SearchOutcome>({ users: [], failed: true })),
          );
        }),
        takeUntilDestroyed(),
      )
      .subscribe((outcome) => {
        this.mfaSearching.set(false);
        this.mfaSearchFailed.set(outcome.failed);
        this.mfaResults.set(outcome.users);
      });

    this.loadHospitals();
  }

  /**
   * The scope dropdown is only trustworthy when the directory actually
   * answered: while loading or after a failure it must not present "whole
   * platform" as the complete list of choices.
   */
  loadHospitals(): void {
    this.hospitalsLoading.set(true);
    this.hospitalsFailed.set(false);
    this.hospitalService
      .list()
      .pipe(
        map((hospitals): HospitalsOutcome => ({ hospitals, failed: false })),
        catchError(() => of<HospitalsOutcome>({ hospitals: [], failed: true })),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((outcome) => {
        this.hospitalsLoading.set(false);
        this.hospitalsFailed.set(outcome.failed);
        this.hospitals.set(outcome.hospitals);
      });
  }

  private refreshDowntimeCard(): void {
    this.downtimeStatus.set(this.downtimeService.status());
  }

  /**
   * QA CX-001: one click on "Activate" turned every hospital read-only with no
   * message and no question, and the live incident blocked other users' writes
   * for two minutes. Activating says why (the submit guard marks the blank
   * message; the server refuses it too) and asks first; nothing is sent before
   * the answer. Lifting the mode asks too, and needs no reason.
   */
  async setDowntime(readOnly: boolean): Promise<void> {
    if (this.downtimeBusy()) return;
    this.downtimeError.set('');
    const message = readOnly ? this.downtimeMessage().trim() : '';
    if (readOnly && !message) {
      refuseFields(
        this.host.nativeElement,
        [DOWNTIME_MESSAGE_ID],
        [DOWNTIME_MESSAGE_ID],
        this.translate.instant('EMERGENCY.DOWNTIME.MESSAGE_REQUIRED'),
      );
      return;
    }
    const confirmed = await this.confirm.ask(
      readOnly
        ? {
            titleKey: 'EMERGENCY.DOWNTIME.CONFIRM_ACTIVATE',
            messageKey: 'EMERGENCY.DOWNTIME.CONFIRM_ACTIVATE_BODY',
            params: { message },
            confirmKey: 'EMERGENCY.DOWNTIME.ACTIVATE',
          }
        : {
            titleKey: 'EMERGENCY.DOWNTIME.CONFIRM_DEACTIVATE',
            messageKey: 'EMERGENCY.DOWNTIME.CONFIRM_DEACTIVATE_BODY',
            confirmKey: 'EMERGENCY.DOWNTIME.DEACTIVATE',
          },
    );
    if (!confirmed) return;
    this.downtimeBusy.set(true);
    this.downtimeService.toggle(readOnly, message || undefined).subscribe({
      next: (status) => {
        this.downtimeBusy.set(false);
        this.downtimeStatus.set(status);
        this.downtimeService.status.set(status);
        this.downtimeMessage.set('');
      },
      error: (err) => {
        this.downtimeBusy.set(false);
        this.downtimeError.set(this.errorText(err));
      },
    });
  }

  readonly logoutPanel = signal<PanelState>({ ...FRESH_PANEL });
  readonly killPanel = signal<PanelState>({ ...FRESH_PANEL });
  readonly mfaPanel = signal<PanelState>({ ...FRESH_PANEL });
  readonly broadcastPanel = signal<PanelState>({ ...FRESH_PANEL });

  readonly logoutReason = signal('');
  readonly logoutMfa = signal('');
  readonly logoutConfirm = signal('');

  readonly killFlagKey = signal('');
  readonly killReason = signal('');
  readonly killMfa = signal('');

  /* ── MFA-reset picker ── */
  private readonly mfaSearch$ = new Subject<string>();
  readonly mfaQuery = signal('');
  readonly mfaResults = signal<UserSummary[]>([]);
  readonly mfaSearching = signal(false);
  readonly mfaSearchFailed = signal(false);
  readonly mfaSelected = signal<UserSummary[]>([]);
  readonly mfaHospitalId = signal('');
  readonly hospitals = signal<HospitalResponse[]>([]);
  readonly hospitalsLoading = signal(true);
  readonly hospitalsFailed = signal(false);
  /** Typed phrase, only asked for when no user is selected (= everyone). */
  readonly mfaResetAllConfirm = signal('');
  readonly mfaResetsEveryone = computed(() => this.mfaSelected().length === 0);
  readonly mfaReason = signal('');
  readonly mfaMfa = signal('');

  onMfaQueryChange(value: string): void {
    this.mfaQuery.set(value);
    // Stale results must not stay clickable under a new name.
    this.mfaResults.set([]);
    this.mfaSearchFailed.set(false);
    this.mfaSearch$.next(value);
  }

  /**
   * Any change to the blast radius invalidates the typed phrase: it confirms
   * exactly what was on screen when it was typed, nothing later.
   */
  onMfaScopeChange(hospitalId: string): void {
    this.mfaHospitalId.set(hospitalId);
    this.mfaResetAllConfirm.set('');
  }

  addMfaTarget(user: UserSummary): void {
    if (this.mfaSelected().some((u) => u.id === user.id)) return;
    this.mfaSelected.update((list) => [...list, user]);
    this.mfaQuery.set('');
    this.mfaResults.set([]);
    // Targeted now; a phrase typed for "everyone" must not survive a later
    // chip removal.
    this.mfaResetAllConfirm.set('');
  }

  removeMfaTarget(userId: string): void {
    this.mfaSelected.update((list) => list.filter((u) => u.id !== userId));
    this.mfaResetAllConfirm.set('');
  }

  readonly broadcastMessage = signal('');
  readonly broadcastSeverity = signal<'INFO' | 'WARN' | 'CRITICAL'>('INFO');
  readonly broadcastMfa = signal('');

  forceLogoutAll(): void {
    if (this.logoutConfirm() !== 'FORCE LOGOUT ALL') {
      // The literal 'FORCE LOGOUT ALL' phrase is intentionally NOT translated —
      // it is a global confirmation token that must match exactly across locales,
      // matching the placeholder shown in emergency.html
      // (EMERGENCY.FORCE_LOGOUT.CONFIRM_PLACEHOLDER).
      this.logoutPanel.set({
        ...FRESH_PANEL,
        error: this.translate.instant('EMERGENCY.CONFIRM_TYPED'),
      });
      return;
    }
    this.logoutPanel.set({ busy: true, result: null, error: null });
    this.service.forceLogoutAll({ reason: this.logoutReason() }, this.logoutMfa()).subscribe({
      next: (response) => this.logoutPanel.set({ busy: false, result: response, error: null }),
      error: (err) =>
        this.logoutPanel.set({
          busy: false,
          result: null,
          error: this.errorText(err),
        }),
    });
  }

  async killFeature(): Promise<void> {
    // The submit guard marks a blank key or reason before this runs (QA CX-001).
    const flagKey = this.killFlagKey().trim();
    if (!flagKey || !this.killReason().trim()) return;
    const confirmed = await this.confirm.ask({
      titleKey: 'EMERGENCY.KILL_FEATURE.CONFIRM',
      params: { key: flagKey },
      confirmKey: 'EMERGENCY.KILL_FEATURE.ACTION',
    });
    if (!confirmed) return;
    this.killPanel.set({ busy: true, result: null, error: null });
    this.service.killFeature({ flagKey, reason: this.killReason() }, this.killMfa()).subscribe({
      next: (response) => this.killPanel.set({ busy: false, result: response, error: null }),
      error: (err) => this.killPanel.set({ busy: false, result: null, error: this.errorText(err) }),
    });
  }

  async forceMfaReenrol(): Promise<void> {
    const ids = this.mfaSelected().map((u) => u.id);
    const resetAll = ids.length === 0;
    if (resetAll && this.mfaResetAllConfirm() !== RESET_ALL_PHRASE) {
      this.mfaPanel.set({
        ...FRESH_PANEL,
        error: this.translate.instant('EMERGENCY.FORCE_MFA.RESET_ALL_CONFIRM_TYPED'),
      });
      return;
    }
    // Everyone: the typed phrase is the question. Named users are asked here.
    if (!resetAll) {
      const confirmed = await this.confirm.ask({
        titleKey: 'EMERGENCY.FORCE_MFA.CONFIRM',
        params: { count: ids.length },
        confirmKey: 'EMERGENCY.FORCE_MFA.ACTION',
      });
      if (!confirmed) return;
    }
    this.mfaPanel.set({ busy: true, result: null, error: null });
    this.service
      .forceMfaReenrol(
        {
          userIds: resetAll ? undefined : ids,
          hospitalId: this.mfaHospitalId() || undefined,
          resetAll: resetAll ? true : undefined,
          reason: this.mfaReason(),
        },
        this.mfaMfa(),
      )
      .subscribe({
        next: (response) => {
          this.mfaPanel.set({ busy: false, result: response, error: null });
          this.mfaSelected.set([]);
          this.mfaResetAllConfirm.set('');
        },
        error: (err) =>
          this.mfaPanel.set({ busy: false, result: null, error: this.errorText(err) }),
      });
  }

  async broadcast(): Promise<void> {
    // The submit guard marks a blank message before this runs (QA CX-001).
    if (!this.broadcastMessage().trim()) return;
    const confirmed = await this.confirm.ask({
      titleKey: 'EMERGENCY.BROADCAST.CONFIRM',
      confirmKey: 'EMERGENCY.BROADCAST.ACTION',
    });
    if (!confirmed) return;
    this.broadcastPanel.set({ busy: true, result: null, error: null });
    this.service
      .broadcast(
        { message: this.broadcastMessage(), severity: this.broadcastSeverity() },
        this.broadcastMfa(),
      )
      .subscribe({
        next: (response) => this.broadcastPanel.set({ busy: false, result: response, error: null }),
        error: (err) =>
          this.broadcastPanel.set({
            busy: false,
            result: null,
            error: this.errorText(err),
          }),
      });
  }

  private errorText(err: unknown): string {
    // A refused step-up code: the translated answer, never the server's
    // English reason text.
    if (err instanceof HttpErrorResponse && isStepUpRefusal(err)) {
      return this.translate.instant(stepUpRefusalKey(err));
    }
    if (err && typeof err === 'object' && 'error' in err) {
      const inner = (err as { error?: { message?: string } }).error;
      // Pass through any server-provided message as-is. The backend
      // GlobalExceptionHandler currently emits hardcoded English strings
      // with no Accept-Language resolution, so this branch is not localised
      // — only the client-side fallback below is.
      if (inner?.message) return inner.message;
    }
    // Fallback when the network call itself fails (no server response):
    // localise via the active TranslateService instance.
    return this.translate.instant('EMERGENCY.REQUEST_FAILED');
  }
}
