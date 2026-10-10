import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

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

/**
 * The facility's staff (provider plan P1-T13, AC-6), for its PROVIDER_ADMIN:
 * who works here and in which role, deactivate a member, send a member a new
 * invitation code, and register a new member through the existing
 * admin-register flow, at this facility and in its type's roles only.
 *
 * Peers who administer the facility and the admin's own account are listed
 * but cannot be changed here: the server refuses both, so no button is offered.
 */
@Component({
  selector: 'app-provider-staff',
  standalone: true,
  imports: [TranslateModule, RoleLabelPipe, ProviderStaffRegisterComponent],
  templateUrl: './provider-staff.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: '../provider-pages.scss',
})
export class ProviderStaffComponent implements OnInit {
  private readonly portal = inject(ProviderPortalService);
  private readonly context = inject(ProviderContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  readonly staff = signal<ProviderStaffMember[]>([]);
  readonly loading = signal(true);
  readonly notAvailable = signal(false);
  readonly loadFailed = signal(false);
  /** The member an action is running for, so only their row's buttons wait. */
  readonly busyUserId = signal<string | null>(null);
  readonly confirmDeactivate = signal<ProviderStaffMember | null>(null);
  readonly registerOpen = signal(false);

  readonly facilityId = computed(() => this.context.settings()?.facilityId ?? null);

  /** The roles a member of this facility may hold (plan §3.2); the backend enforces it. */
  readonly staffRoles = computed(() => {
    const type = this.context.settings()?.facilityType;
    return isProviderFacilityType(type) ? PROVIDER_STAFF_ROLES[type] : [];
  });

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.notAvailable.set(false);
    this.loadFailed.set(false);
    this.portal.staff().subscribe({
      next: (staff) => {
        this.staff.set(staff);
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

  /** The admin's own row and a peer administrator's row are not changed here. */
  manageable(member: ProviderStaffMember): boolean {
    return !member.self && !member.providerAdmin;
  }

  displayName(member: ProviderStaffMember): string {
    const name = `${member.firstName ?? ''} ${member.lastName ?? ''}`.trim();
    return name || member.username;
  }

  askDeactivate(member: ProviderStaffMember): void {
    this.confirmDeactivate.set(member);
  }

  cancelDeactivate(): void {
    this.confirmDeactivate.set(null);
  }

  deactivate(): void {
    const member = this.confirmDeactivate();
    if (!member) return;
    this.busyUserId.set(member.userId);
    this.portal.deactivate(member.userId).subscribe({
      next: (updated) => {
        this.busyUserId.set(null);
        this.confirmDeactivate.set(null);
        this.replace(updated);
        this.toast.success(this.translate.instant('PROVIDER.STAFF.DEACTIVATED'));
      },
      error: (err: unknown) => this.failed(err),
    });
  }

  reinvite(member: ProviderStaffMember): void {
    this.busyUserId.set(member.userId);
    this.portal.activate(member.userId).subscribe({
      next: (updated) => {
        this.busyUserId.set(null);
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
      error: (err: unknown) => this.failed(err),
    });
  }

  openRegister(): void {
    this.registerOpen.set(true);
  }

  closeRegister(): void {
    this.registerOpen.set(false);
  }

  onRegistered(user: UserDetail): void {
    this.registerOpen.set(false);
    this.toast.success(this.translate.instant('PROVIDER.REGISTER.DONE'));
    for (const key of deliveryWarningKeys(user.activationDelivery)) {
      this.toast.warning(this.translate.instant(key));
    }
    this.load();
  }

  private replace(updated: ProviderStaffMember): void {
    this.staff.update((rows) => rows.map((row) => (row.userId === updated.userId ? updated : row)));
  }

  /**
   * A refusal answers like a miss (the member left, or this user is no longer
   * the admin): re-read the list rather than show the server's internals.
   */
  private failed(err: unknown): void {
    this.busyUserId.set(null);
    this.confirmDeactivate.set(null);
    if (isNotAvailable(err)) {
      this.toast.info(this.translate.instant('PROVIDER.STAFF.MEMBER_UNAVAILABLE'));
      this.load();
      return;
    }
    this.toast.error(serverMessage(err) ?? this.translate.instant('PROVIDER.STAFF.ACTION_FAILED'));
  }
}
