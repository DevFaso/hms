import { Component, computed, inject, signal, ChangeDetectionStrategy } from '@angular/core';

import { Router } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { ImpersonationService } from '../services/impersonation.service';
import { AuthService } from '../auth/auth.service';

@Component({
  selector: 'app-impersonation-banner',
  standalone: true,
  imports: [TranslateModule],
  templateUrl: './impersonation-banner.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './impersonation-banner.scss',
})
export class ImpersonationBannerComponent {
  private readonly impersonation = inject(ImpersonationService);
  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);

  readonly busy = signal(false);

  readonly active = computed(() => this.impersonation.active());
  readonly visible = computed(() => this.active()?.impersonating === true);

  /** The person acted for, by name: the profile shown is theirs while the session lasts. */
  readonly targetName = computed(() => {
    const profile = this.auth.currentProfile();
    const username = this.active()?.targetUsername ?? '';
    const sameAccount = !!profile && (!username || profile.username === username);
    const name = sameAccount ? `${profile.firstName ?? ''} ${profile.lastName ?? ''}`.trim() : '';
    return name || username;
  });

  /** The super-admin who opened the session, by name when their own profile is held. */
  readonly impersonatorName = computed(() => {
    this.active();
    const own = this.auth.heldProfile();
    const name = own ? `${own.firstName ?? ''} ${own.lastName ?? ''}`.trim() : '';
    return name || (this.active()?.impersonatorUsername ?? '');
  });

  // MVP-4b — countdown bridge from the service.
  readonly remainingMs = computed(() => this.impersonation.remainingMs());
  readonly nearingExpiry = computed(() => this.impersonation.nearingExpiry());

  /** mm:ss formatted countdown for the banner badge. Empty when null. */
  readonly remainingLabel = computed(() => {
    const ms = this.remainingMs();
    if (ms === null) return '';
    const totalSeconds = Math.max(0, Math.floor(ms / 1000));
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = totalSeconds % 60;
    return `${minutes}:${seconds.toString().padStart(2, '0')}`;
  });

  exit(): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.impersonation.stop().subscribe({
      next: () => {
        this.busy.set(false);
        this.router.navigateByUrl('/super-admin');
      },
      error: () => {
        // Even on error, drop the impersonation token so the user is not stuck.
        this.impersonation.forceStop();
        this.busy.set(false);
        this.router.navigateByUrl('/super-admin');
      },
    });
  }
}
