import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { Subscription } from 'rxjs';

import { ProviderContextService } from '../../core/provider-context.service';

import { ToastService } from '../../core/toast.service';
import { ProviderProfile, isNotAvailable, serverMessage } from '../../services/provider.model';
import { ProviderPortalService } from '../../services/provider-portal.service';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';
import { verificationBadgeClass } from '../../super-admin/providers/provider-status';
import { followFacility } from '../follow-facility';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';

import { SubmitGuardDirective } from '../../shared/forms/submit-guard.directive';
import { PhoneFieldDirective } from '../../shared/forms/phone-field.directive';
/**
 * The facility profile (provider plan P1-T13, AC-6): the operational contact,
 * which the facility's PROVIDER_ADMIN may change (phone, email, website), and
 * the identity as the platform verified it, read-only. Any staff member reads
 * it; the server says whether this user may edit (`editable`).
 *
 * The page follows the facility the session acts at. A branch switch re-reads
 * the profile and discards an edit in progress, and a save is refused unless
 * the form holds the profile of the facility the session acts at now: the PUT
 * goes to the CURRENT facility, so one branch's contact can never be written
 * onto another's.
 */
@Component({
  selector: 'app-provider-profile',
  standalone: true,
  imports: [
    PhoneFieldDirective,
    SubmitGuardDirective,
    ServerTimePipe,
    DatePipe,
    FormsModule,
    TranslateModule,
    EnumLabelPipe,
  ],
  templateUrl: './provider-profile.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: '../provider-pages.scss',
})
export class ProviderProfileComponent {
  private readonly portal = inject(ProviderPortalService);
  private readonly context = inject(ProviderContextService);
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

  /** The facility the page shows: the one its form was read from. */
  private readonly shownFacility = followFacility({
    load: () => this.load(),
    cleared: () => this.leave(false),
    failed: () => this.leave(true),
  });

  /**
   * The facility went away (or could not be read): the form and its edit are
   * dropped, so nothing can be saved anywhere.
   */
  private leave(failed: boolean): void {
    this.loadSub?.unsubscribe();
    this.profile.set(null);
    this.phoneNumber.set('');
    this.email.set('');
    this.website.set('');
    this.saveError.set(null);
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

  /**
   * Saving needs the form to hold THIS facility's profile: read, not
   * reloading, and the session still acting there.
   */
  readonly canSave = computed(
    () =>
      !this.saving() &&
      !this.loading() &&
      this.phoneNumber().trim() !== '' &&
      this.profile() !== null &&
      this.profile()?.id === this.context.settings()?.facilityId,
  );

  private loadSub?: Subscription;

  load(): void {
    this.loadSub?.unsubscribe();
    const facility = this.shownFacility();
    // A switch discards the edit in progress: the form shows nothing until
    // the new facility's profile arrives.
    this.profile.set(null);
    this.saveError.set(null);
    this.loading.set(true);
    this.notAvailable.set(false);
    this.loadFailed.set(false);
    this.loadSub = this.portal.profile().subscribe({
      next: (profile) => {
        if (facility !== this.shownFacility()) return;
        this.apply(profile);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        if (facility !== this.shownFacility()) return;
        if (isNotAvailable(err)) {
          this.notAvailable.set(true);
          this.profile.set(null);
          this.context.invalidate();
        } else {
          this.loadFailed.set(true);
        }
        this.loading.set(false);
      },
    });
  }

  save(): void {
    if (!this.canSave()) return;
    const facility = this.shownFacility();
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
          // An answer for a facility the page has since left is not shown.
          if (facility !== this.shownFacility()) return;
          this.apply(profile);
          this.toast.success(this.translate.instant('PROVIDER.PROFILE.SAVED'));
        },
        error: (err: unknown) => {
          this.saving.set(false);
          if (facility !== this.shownFacility()) return;
          if (isNotAvailable(err)) {
            // The seat went away (deactivated, no longer the admin): the page is no longer this user's.
            this.notAvailable.set(true);
            this.context.invalidate();
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
