import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { ProviderContextService } from '../../core/provider-context.service';
import { ProviderProfile, isNotAvailable } from '../../services/provider.model';
import { ProviderPortalService } from '../../services/provider-portal.service';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';
import { providerNavEntries } from '../../shell/nav-groups';
import { verificationBadgeClass } from '../../super-admin/providers/provider-status';

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
export class ProviderHomeComponent implements OnInit {
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

  ngOnInit(): void {
    this.portal.profile().subscribe({
      next: (profile) => {
        this.profile.set(profile);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        if (isNotAvailable(err)) {
          this.notAvailable.set(true);
        } else {
          this.loadFailed.set(true);
        }
        this.loading.set(false);
      },
    });
  }
}
