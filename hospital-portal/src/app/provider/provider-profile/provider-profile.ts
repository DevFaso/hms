import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { ToastService } from '../../core/toast.service';
import { ProviderProfile, isNotAvailable, serverMessage } from '../../services/provider.model';
import { ProviderPortalService } from '../../services/provider-portal.service';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';
import { verificationBadgeClass } from '../../super-admin/providers/provider-status';

/**
 * The facility profile (provider plan P1-T13, AC-6): the operational contact,
 * which the facility's PROVIDER_ADMIN may change (phone, email, website), and
 * the identity as the platform verified it, read-only. Any staff member reads
 * it; the server says whether this user may edit (`editable`).
 */
@Component({
  selector: 'app-provider-profile',
  standalone: true,
  imports: [DatePipe, FormsModule, TranslateModule, EnumLabelPipe],
  templateUrl: './provider-profile.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: '../provider-pages.scss',
})
export class ProviderProfileComponent implements OnInit {
  private readonly portal = inject(ProviderPortalService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  readonly profile = signal<ProviderProfile | null>(null);
  readonly loading = signal(true);
  readonly notAvailable = signal(false);
  readonly loadFailed = signal(false);
  readonly badgeClass = verificationBadgeClass;

  readonly phoneNumber = signal('');
  readonly email = signal('');
  readonly website = signal('');
  readonly saving = signal(false);
  readonly saveError = signal<string | null>(null);

  /** The identity is shown only once the platform has verified it. */
  readonly verified = computed(() => !!this.profile()?.verifiedAt);

  readonly canSave = computed(() => !this.saving() && this.phoneNumber().trim() !== '');

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.notAvailable.set(false);
    this.loadFailed.set(false);
    this.portal.profile().subscribe({
      next: (profile) => {
        this.apply(profile);
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

  save(): void {
    if (!this.canSave()) return;
    this.saving.set(true);
    this.saveError.set(null);
    this.portal
      .updateProfile({
        phoneNumber: this.phoneNumber().trim(),
        email: this.email().trim() || null,
        website: this.website().trim() || null,
      })
      .subscribe({
        next: (profile) => {
          this.saving.set(false);
          this.apply(profile);
          this.toast.success(this.translate.instant('PROVIDER.PROFILE.SAVED'));
        },
        error: (err: unknown) => {
          this.saving.set(false);
          if (isNotAvailable(err)) {
            // The seat went away (deactivated, no longer the admin): the page is no longer this user's.
            this.notAvailable.set(true);
            return;
          }
          this.saveError.set(
            serverMessage(err) ?? this.translate.instant('PROVIDER.PROFILE.SAVE_FAILED'),
          );
        },
      });
  }

  private apply(profile: ProviderProfile): void {
    this.profile.set(profile);
    this.phoneNumber.set(profile.phoneNumber ?? '');
    this.email.set(profile.email ?? '');
    this.website.set(profile.website ?? '');
  }
}
