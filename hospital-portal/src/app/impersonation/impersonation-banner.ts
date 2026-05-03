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
