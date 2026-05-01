import {
  ChangeDetectionStrategy,
  Component,
  OnChanges,
  OnDestroy,
  SimpleChanges,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';
import { Subscription } from 'rxjs';

import {
  BreakGlassDeclareRequest,
  BreakGlassService,
  BreakGlassSession,
} from '../../services/break-glass.service';
import { ToastService } from '../../core/toast.service';
import { AuthService } from '../../auth/auth.service';

type LoadState = 'idle' | 'loading' | 'ready' | 'error';

/**
 * Patient-detail emergency-access banner.
 *
 * <p>Shows the caller's currently-active break-glass session for the patient
 * (with a live countdown to expiry), or a "Declare break-the-glass" button
 * that opens an inline modal. Hidden entirely for users without a privileged
 * clinical/admin role so it doesn't add noise for receptionists/patients.
 */
@Component({
  selector: 'app-break-glass-banner',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './break-glass-banner.component.html',
  styleUrl: './break-glass-banner.component.scss',
})
export class BreakGlassBannerComponent implements OnChanges, OnDestroy {
  readonly patientId = input<string | null | undefined>(null);
  readonly hospitalId = input<string | null | undefined>(null);

  /** Emitted whenever a session is declared or revoked, so the parent can refresh dependent data. */
  readonly sessionChanged = output<BreakGlassSession | null>();

  private readonly service = inject(BreakGlassService);
  private readonly toast = inject(ToastService);
  private readonly auth = inject(AuthService);

  protected readonly state = signal<LoadState>('idle');
  protected readonly mySession = signal<BreakGlassSession | null>(null);
  protected readonly modalOpen = signal(false);
  protected readonly submitting = signal(false);

  protected readonly reason = signal('');
  protected readonly ttlMinutes = signal<number>(240);

  protected readonly remainingLabel = signal<string>('');
  private clockTimer?: ReturnType<typeof setInterval>;
  private inFlight?: Subscription;

  private static readonly DECLARE_ROLES = new Set([
    'DOCTOR',
    'ROLE_DOCTOR',
    'NURSE',
    'ROLE_NURSE',
    'MIDWIFE',
    'ROLE_MIDWIFE',
    'HOSPITAL_ADMIN',
    'ROLE_HOSPITAL_ADMIN',
    'SUPER_ADMIN',
    'ROLE_SUPER_ADMIN',
  ]);

  /**
   * True when the caller's role allows declaring break-glass at all (drives
   * whether the banner appears at all).
   */
  protected readonly canDeclare = computed(() => {
    const roles = this.auth.getRoles?.() ?? [];
    return roles.some((r) => BreakGlassBannerComponent.DECLARE_ROLES.has(r));
  });

  /**
   * True only when the declare action is *actually* invokable — caller is
   * privileged AND we know which patient/hospital to attach the session to.
   * Used by the template to disable the button (with a tooltip) instead of
   * letting the user click and then hit a useless "missing patient/hospital"
   * error toast.
   */
  protected readonly canInvokeDeclare = computed(
    () => this.canDeclare() && !!this.patientId() && !!this.hospitalId(),
  );

  ngOnChanges(_changes: SimpleChanges): void {
    this.refreshFromInputs();
  }

  ngOnDestroy(): void {
    this.cancelInFlight();
    this.stopClock();
  }

  protected openModal(): void {
    this.reason.set('');
    this.ttlMinutes.set(240);
    this.modalOpen.set(true);
  }

  protected closeModal(): void {
    if (this.submitting()) {
      return;
    }
    this.modalOpen.set(false);
  }

  /**
   * Closes the modal only when the user clicks the backdrop itself —
   * clicks inside the panel bubble up here too (no stopPropagation on the
   * panel, which would trip @angular-eslint/template/click-events-have-key-events).
   */
  protected onBackdropClick(event: MouseEvent): void {
    if (event.target === event.currentTarget) {
      this.closeModal();
    }
  }

  protected submit(): void {
    const patientId = this.patientId();
    const hospitalId = this.hospitalId();
    const reason = this.reason().trim();

    if (!patientId || !hospitalId) {
      this.toast.error('Cannot declare: patient or hospital is missing.');
      return;
    }
    if (reason.length < 10) {
      this.toast.error('Reason must be at least 10 characters.');
      return;
    }

    const ttl = this.ttlMinutes();
    const body: BreakGlassDeclareRequest = {
      patientId,
      hospitalId,
      reason,
      ttlMinutes: ttl >= 15 && ttl <= 240 ? ttl : 240,
    };

    this.submitting.set(true);
    this.cancelInFlight();
    this.inFlight = this.service.declare(body).subscribe({
      next: (session) => {
        this.mySession.set(session);
        this.sessionChanged.emit(session);
        this.toast.success('Break-the-glass session active.');
        this.submitting.set(false);
        this.modalOpen.set(false);
        this.startClock();
      },
      error: (err) => {
        this.submitting.set(false);
        this.toast.error(err?.error?.message ?? 'Could not declare break-the-glass.');
      },
    });
  }

  protected revoke(): void {
    const session = this.mySession();
    if (!session || !session.live) {
      return;
    }
    this.cancelInFlight();
    this.inFlight = this.service.revoke(session.id, {}).subscribe({
      next: (updated) => {
        this.mySession.set(updated.live ? updated : null);
        this.sessionChanged.emit(null);
        this.toast.success('Break-the-glass session revoked.');
        this.stopClock();
      },
      error: (err) => {
        this.toast.error(err?.error?.message ?? 'Could not revoke session.');
      },
    });
  }

  private refreshFromInputs(): void {
    const id = this.patientId();
    if (!id) {
      this.mySession.set(null);
      this.state.set('idle');
      this.stopClock();
      return;
    }
    this.state.set('loading');
    this.cancelInFlight();
    this.inFlight = this.service.findMyLiveSession(id).subscribe({
      next: (session) => {
        this.mySession.set(session);
        this.state.set('ready');
        if (session) {
          this.startClock();
        } else {
          this.stopClock();
        }
      },
      error: () => {
        this.state.set('error');
        this.mySession.set(null);
        this.stopClock();
      },
    });
  }

  private startClock(): void {
    this.cancelClockTimer();
    this.remainingLabel.set('');
    this.tickClock();
    this.clockTimer = setInterval(() => this.tickClock(), 30_000);
  }

  /** Cancels the interval but leaves {@link remainingLabel} alone — needed
   *  for the "expired" path so the label stays observable in the template. */
  private cancelClockTimer(): void {
    if (this.clockTimer) {
      clearInterval(this.clockTimer);
      this.clockTimer = undefined;
    }
  }

  /** Cancels the interval AND resets the label — used when the banner returns
   *  to the idle/no-session state. */
  private stopClock(): void {
    this.cancelClockTimer();
    this.remainingLabel.set('');
  }

  private tickClock(): void {
    const session = this.mySession();
    if (!session || !session.expiresAt) {
      this.stopClock();
      return;
    }
    const ms = new Date(session.expiresAt).getTime() - Date.now();
    if (ms <= 0) {
      this.remainingLabel.set('expired');
      this.mySession.set({ ...session, live: false });
      this.sessionChanged.emit(null);
      // Stop the timer but keep the 'expired' label so the template shows it.
      this.cancelClockTimer();
      return;
    }
    const totalMinutes = Math.floor(ms / 60_000);
    const hours = Math.floor(totalMinutes / 60);
    const minutes = totalMinutes % 60;
    this.remainingLabel.set(hours > 0 ? `${hours}h ${minutes}m` : `${minutes}m`);
  }

  private cancelInFlight(): void {
    this.inFlight?.unsubscribe();
    this.inFlight = undefined;
  }
}
