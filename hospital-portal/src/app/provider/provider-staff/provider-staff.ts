import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { Subscription } from 'rxjs';

import { ProviderContextService } from '../../core/provider-context.service';
import { ToastService } from '../../core/toast.service';
import {
  PROVIDER_STAFF_ROLES,
  ProviderStaffMember,
  isNotAvailable,
  isProviderFacilityType,
  serverMessage,
} from '../../services/provider.model';
import { ProviderPortalService } from '../../services/provider-portal.service';
import { UserDetail } from '../../services/user.service';
import { deliveryWarningKeys, hasActivationSent } from '../../shared/delivery-warnings';
import { RoleLabelPipe } from '../../shared/pipes/role-label.pipe';
import { ProviderStaffRegisterComponent } from '../provider-staff-register/provider-staff-register';
import { followFacility } from '../follow-facility';

/**
 * The facility's staff (provider plan P1-T13, AC-6), for its PROVIDER_ADMIN:
 * who works here and in which role, deactivate a member, send a member a new
 * invitation code, and register a new member through the existing
 * admin-register flow, at this facility and in its type's roles only.
 *
 * Which actions a row offers is the server's answer (`canDeactivate`,
 * `canReinvite`), computed by the same rules the endpoints apply: the admin's
 * own account, an administrator anywhere and a disabled account are never
 * offered what would be refused.
 *
 * The page follows the facility the session acts at: when the scope moves to
 * another branch, the list is read again and any open dialog closes, so
 * nothing is registered or changed at a facility the page no longer shows.
 * An action answer that arrives after such a switch is dropped, never applied
 * to the new branch's list or toasted.
 */
@Component({
  selector: 'app-provider-staff',
  standalone: true,
  imports: [TranslateModule, RoleLabelPipe, ProviderStaffRegisterComponent],
  templateUrl: './provider-staff.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: '../provider-pages.scss',
})
export class ProviderStaffComponent {
  private readonly portal = inject(ProviderPortalService);
  private readonly context = inject(ProviderContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  readonly staff = signal<ProviderStaffMember[]>([]);
  readonly loading = signal(true);
  readonly notAvailable = signal(false);
  readonly loadFailed = signal(false);
  /** The members an action is running for: only their rows' buttons wait. */
  readonly busyUserIds = signal<ReadonlySet<string>>(new Set<string>());
  readonly confirmDeactivate = signal<ProviderStaffMember | null>(null);
  readonly registerOpen = signal(false);
  /** The facility the open registration targets: fixed when it opens, never the next branch's. */
  readonly registerFacilityId = signal<string | null>(null);
  /** A registration is in flight: a branch switch must not destroy it (its result would be lost). */
  private registerBusy = false;
  /** A branch switch arrived while a registration was in flight: close it once it settles. */
  private closeRegisterWhenSettled = false;

  /** The facility the session acts at now: the one every action here targets. */
  readonly facilityId = computed(() => this.context.settings()?.facilityId ?? null);
  /**
   * Admin-only controls (Register) show only on a successful answer that says
   * so: on a settings error the page opens in its retry state, and the route
   * guard let it through without knowing, so nothing admin-only appears.
   */
  readonly isAdmin = computed(
    () => this.context.status() === 'provider' && this.context.settings()?.providerAdmin === true,
  );

  /** The roles a member of this facility may hold (plan §3.2); the backend enforces it. */
  readonly staffRoles = computed(() => {
    const type = this.context.settings()?.facilityType;
    return isProviderFacilityType(type) ? PROVIDER_STAFF_ROLES[type] : [];
  });

  private loadSub?: Subscription;

  /** The facility the list shows: read on open, and again on every branch switch. */
  private readonly shownFacility = followFacility({
    load: () => {
      this.closeEverything();
      this.load();
    },
    cleared: () => this.leave(false),
    failed: () => this.leave(true),
  });

  private closeEverything(): void {
    this.confirmDeactivate.set(null);
    if (this.registerBusy) {
      // Its success toast, delivery warnings and list refresh must not be lost.
      this.closeRegisterWhenSettled = true;
    } else {
      this.registerOpen.set(false);
    }
    this.busyUserIds.set(new Set<string>());
  }

  /** The facility went away (or could not be read): no member, no dialog, no action stays. */
  private leave(failed: boolean): void {
    this.loadSub?.unsubscribe();
    this.closeEverything();
    this.staff.set([]);
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
    this.loadSub = this.portal.staff().subscribe({
      next: (staff) => {
        if (facility !== this.shownFacility()) return;
        this.staff.set(staff);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        if (facility !== this.shownFacility()) return;
        if (isNotAvailable(err)) {
          this.notAvailable.set(true);
          // The seat went away: the shell and the guards ask the server again.
          this.context.invalidate();
        } else {
          this.loadFailed.set(true);
        }
        this.loading.set(false);
      },
    });
  }

  isBusy(member: ProviderStaffMember): boolean {
    return this.busyUserIds().has(member.userId);
  }

  displayName(member: ProviderStaffMember): string {
    const name = `${member.firstName ?? ''} ${member.lastName ?? ''}`.trim();
    return name || member.username;
  }

  askDeactivate(member: ProviderStaffMember): void {
    if (!member.canDeactivate || this.isBusy(member)) return;
    this.confirmDeactivate.set(member);
  }

  cancelDeactivate(): void {
    this.confirmDeactivate.set(null);
  }

  deactivate(): void {
    const member = this.confirmDeactivate();
    if (!member || !this.begin(member)) return;
    const facility = this.shownFacility();
    this.confirmDeactivate.set(null);
    this.portal.deactivate(member.userId).subscribe({
      next: (updated) => {
        if (facility !== this.shownFacility()) return;
        this.end(member);
        this.replace(updated);
        this.toast.success(this.translate.instant('PROVIDER.STAFF.DEACTIVATED'));
      },
      error: (err: unknown) => this.failed(member, facility, err),
    });
  }

  reinvite(member: ProviderStaffMember): void {
    if (!member.canReinvite || !this.begin(member)) return;
    const facility = this.shownFacility();
    this.portal.activate(member.userId).subscribe({
      next: (updated) => {
        if (facility !== this.shownFacility()) return;
        this.end(member);
        this.replace(updated);
        const report = updated.activationDelivery;
        if (hasActivationSent(report)) {
          this.toast.success(this.translate.instant('PROVIDER.STAFF.REINVITED'));
        } else {
          this.toast.info(this.translate.instant('PROVIDER.STAFF.REINVITE_NOTHING_SENT'));
        }
        for (const key of deliveryWarningKeys(report)) {
          this.toast.warning(this.translate.instant(key));
        }
      },
      error: (err: unknown) => this.failed(member, facility, err),
    });
  }

  openRegister(): void {
    this.registerFacilityId.set(this.facilityId());
    this.closeRegisterWhenSettled = false;
    this.registerOpen.set(true);
  }

  closeRegister(): void {
    this.registerOpen.set(false);
  }

  onRegisterSubmitting(submitting: boolean): void {
    this.registerBusy = submitting;
    if (!submitting && this.closeRegisterWhenSettled) {
      // Settled after a branch switch: the dialog belonged to the old branch.
      this.closeRegisterWhenSettled = false;
      this.registerOpen.set(false);
    }
  }

  onRegistered(user: UserDetail): void {
    this.registerOpen.set(false);
    this.toast.success(this.translate.instant('PROVIDER.REGISTER.DONE'));
    for (const key of deliveryWarningKeys(user.activationDelivery)) {
      this.toast.warning(this.translate.instant(key));
    }
    this.load();
  }

  /** Mark a row busy; false when an action already runs for it (no double request). */
  private begin(member: ProviderStaffMember): boolean {
    if (this.isBusy(member)) return false;
    this.busyUserIds.update((ids) => new Set(ids).add(member.userId));
    return true;
  }

  private end(member: ProviderStaffMember): void {
    this.busyUserIds.update((ids) => {
      const next = new Set(ids);
      next.delete(member.userId);
      return next;
    });
  }

  private replace(updated: ProviderStaffMember): void {
    this.staff.update((rows) => rows.map((row) => (row.userId === updated.userId ? updated : row)));
  }

  /**
   * A refusal answers like a miss (the member left, or this user is no longer
   * the admin): re-read the list rather than show the server's internals.
   * Only this member's row stops waiting.
   */
  private failed(member: ProviderStaffMember, facility: string | null, err: unknown): void {
    // An answer about the branch the page has left is dropped.
    if (facility !== this.shownFacility()) return;
    this.end(member);
    if (isNotAvailable(err)) {
      this.toast.info(this.translate.instant('PROVIDER.STAFF.MEMBER_UNAVAILABLE'));
      this.load();
      return;
    }
    this.toast.error(serverMessage(err) ?? this.translate.instant('PROVIDER.STAFF.ACTION_FAILED'));
  }
}
