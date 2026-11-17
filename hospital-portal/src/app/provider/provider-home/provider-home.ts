import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { Subscription } from 'rxjs';

import { ProviderContextService } from '../../core/provider-context.service';
import { ProviderProfile, isNotAvailable } from '../../services/provider.model';
import { ProviderPortalService } from '../../services/provider-portal.service';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';
import { providerNavEntries } from '../../shell/nav-groups';
import { verificationBadgeClass } from '../../super-admin/providers/provider-status';
import { followFacility } from '../follow-facility';

/**
 * The provider home (provider plan AC-12): where a user acting at a private
 * pharmacy or laboratory lands. It names the facility and links to the pages
 * the facility type and the user's seat allow, the same entries as the nav.
 */
@Component({
  selector: 'app-provider-home',
  standalone: true,
  imports: [RouterLink, TranslateModule, EnumLabelPipe],
  templateUrl: './provider-home.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: '../provider-pages.scss',
})
export class ProviderHomeComponent {
  private readonly context = inject(ProviderContextService);
  private readonly portal = inject(ProviderPortalService);

  readonly settings = this.context.settings;
  readonly profile = signal<ProviderProfile | null>(null);
  readonly loading = signal(true);
  readonly notAvailable = signal(false);
  readonly loadFailed = signal(false);
  readonly badgeClass = verificationBadgeClass;

  /** The pages besides this one, as the nav lists them. */
  readonly links = computed(() => {
    const settings = this.settings();
    return providerNavEntries(settings?.facilityType, settings?.providerAdmin ?? false).filter(
      (entry) => entry.route !== '/provider',
    );
  });

  /** The facility the page shows; a branch switch re-reads it. */
  private readonly shownFacility = followFacility({
    load: () => this.load(),
    cleared: () => this.leave(false),
    failed: () => this.leave(true),
  });
  private loadSub?: Subscription;

  /** The facility went away (or could not be read): nothing of it stays on screen. */
  private leave(failed: boolean): void {
    this.loadSub?.unsubscribe();
    this.profile.set(null);
    this.loading.set(false);
    this.notAvailable.set(!failed);
    this.loadFailed.set(failed);
  }

  /**
   * Retry after a failure: the page's own read when it still shows a
   * facility, otherwise the context itself (it could not be read).
   */
  retry(): void {
    if (this.shownFacility()) {
      this.load();
    } else {
      this.context.invalidate();
    }
  }

  load(): void {
    this.loadSub?.unsubscribe();
    const facility = this.shownFacility();
    this.loading.set(true);
    this.notAvailable.set(false);
    this.loadFailed.set(false);
    this.loadSub = this.portal.profile().subscribe({
      next: (profile) => {
        if (facility !== this.shownFacility()) return;
        this.profile.set(profile);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        if (facility !== this.shownFacility()) return;
        if (isNotAvailable(err)) {
          this.notAvailable.set(true);
          this.profile.set(null);
          // The seat went away: the shell and the guards ask the server again.
          this.context.invalidate();
        } else {
          this.loadFailed.set(true);
        }
        this.loading.set(false);
      },
    });
  }
}
