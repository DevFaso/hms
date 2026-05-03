import { Component, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { ImpersonationService } from '../services/impersonation.service';

@Component({
  selector: 'app-impersonation-banner',
  standalone: true,
  imports: [CommonModule, TranslateModule],
  templateUrl: './impersonation-banner.html',
  styleUrl: './impersonation-banner.scss',
})
export class ImpersonationBannerComponent {
  private readonly impersonation = inject(ImpersonationService);
  private readonly router = inject(Router);

  readonly busy = signal(false);

  readonly active = computed(() => this.impersonation.active());
  readonly visible = computed(() => this.active()?.impersonating === true);

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
