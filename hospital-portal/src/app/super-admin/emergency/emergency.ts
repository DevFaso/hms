import { Component, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { EmergencyControlService } from '../../services/emergency-control.service';
import { EmergencyActionResponse } from '../../services/emergency-control.model';
import { DowntimeService, DowntimeStatus } from '../../services/downtime.service';

interface PanelState {
  busy: boolean;
  result: EmergencyActionResponse | null;
  error: string | null;
}

const FRESH_PANEL: PanelState = { busy: false, result: null, error: null };

@Component({
  selector: 'app-super-admin-emergency',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, TranslateModule, DatePipe],
  templateUrl: './emergency.html',
  styleUrl: './emergency.scss',
})
export class EmergencyComponent {
  private readonly service = inject(EmergencyControlService);
  private readonly downtimeService = inject(DowntimeService);
  private readonly translate = inject(TranslateService);

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
  }

  private refreshDowntimeCard(): void {
    this.downtimeStatus.set(this.downtimeService.status());
  }

  setDowntime(readOnly: boolean): void {
    if (this.downtimeBusy()) return;
    this.downtimeBusy.set(true);
    this.downtimeError.set('');
    this.downtimeService.toggle(readOnly, this.downtimeMessage().trim() || undefined).subscribe({
      next: (status) => {
        this.downtimeBusy.set(false);
        this.downtimeStatus.set(status);
        this.downtimeService.status.set(status);
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

  readonly mfaUserIds = signal('');
  readonly mfaReason = signal('');
  readonly mfaMfa = signal('');

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

  killFeature(): void {
    this.killPanel.set({ busy: true, result: null, error: null });
    this.service
      .killFeature({ flagKey: this.killFlagKey(), reason: this.killReason() }, this.killMfa())
      .subscribe({
        next: (response) => this.killPanel.set({ busy: false, result: response, error: null }),
        error: (err) =>
          this.killPanel.set({ busy: false, result: null, error: this.errorText(err) }),
      });
  }

  forceMfaReenrol(): void {
    const ids = this.mfaUserIds()
      .split(',')
      .map((s) => s.trim())
      .filter((s) => s.length > 0);
    this.mfaPanel.set({ busy: true, result: null, error: null });
    this.service
      .forceMfaReenrol(
        { userIds: ids.length > 0 ? ids : undefined, reason: this.mfaReason() },
        this.mfaMfa(),
      )
      .subscribe({
        next: (response) => this.mfaPanel.set({ busy: false, result: response, error: null }),
        error: (err) =>
          this.mfaPanel.set({ busy: false, result: null, error: this.errorText(err) }),
      });
  }

  broadcast(): void {
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
