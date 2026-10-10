import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';

import { serverMessage } from '../../services/provider.model';
import { UserDetail, UserService } from '../../services/user.service';
import { RoleLabelPipe } from '../../shared/pipes/role-label.pipe';

/** The roles whose registration needs a licence number (the backend's LicenseRequiredForMedicalRoles). */
const LICENSED_ROLES: readonly string[] = ['PHARMACIST', 'LAB_SCIENTIST'];

/**
 * Register a person at one provider facility through the existing
 * `POST /users/admin-register` (provider plan AC-5, AC-6): the super-admin
 * registers a facility's first PROVIDER_ADMIN, and the PROVIDER_ADMIN its
 * staff. The facility and the roles offered are fixed by the caller; the
 * backend decides what may actually be granted (only a super-admin grants
 * PROVIDER_ADMIN, a provider admin only its facility's compatible roles).
 *
 * The account starts inactive; the person activates it with the code the
 * platform sends. The parent reports where the code went.
 */
@Component({
  selector: 'app-provider-staff-register',
  standalone: true,
  imports: [FormsModule, TranslateModule, RoleLabelPipe],
  templateUrl: './provider-staff-register.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './provider-staff-register.scss',
})
export class ProviderStaffRegisterComponent implements OnInit {
  private readonly users = inject(UserService);

  /** The facility the person is registered at. */
  readonly facilityId = input.required<string>();
  readonly facilityName = input<string | null>(null);
  /** The roles offered, bare (e.g. PHARMACIST); the first is preselected. */
  readonly roles = input.required<string[]>();
  readonly titleKey = input('PROVIDER.REGISTER.TITLE');

  readonly registered = output<UserDetail>();
  readonly closed = output<void>();

  readonly firstName = signal('');
  readonly lastName = signal('');
  readonly username = signal('');
  readonly email = signal('');
  readonly phoneNumber = signal('');
  readonly role = signal('');
  readonly licenseNumber = signal('');

  readonly saving = signal(false);
  readonly errorKey = signal<string | null>(null);
  readonly errorText = signal<string | null>(null);

  readonly needsLicence = computed(() => LICENSED_ROLES.includes(this.role()));

  readonly complete = computed(
    () =>
      [this.firstName(), this.lastName(), this.username(), this.email(), this.phoneNumber()].every(
        (value) => value.trim() !== '',
      ) &&
      this.role() !== '' &&
      (!this.needsLicence() || this.licenseNumber().trim() !== ''),
  );

  ngOnInit(): void {
    this.role.set(this.roles()[0] ?? '');
  }

  close(): void {
    if (!this.saving()) this.closed.emit();
  }

  submit(): void {
    if (!this.complete()) {
      this.errorKey.set('PROVIDER.REGISTER.REQUIRED_FIELDS');
      this.errorText.set(null);
      return;
    }
    this.saving.set(true);
    this.errorKey.set(null);
    this.errorText.set(null);
    this.users
      .adminRegister({
        firstName: this.firstName().trim(),
        lastName: this.lastName().trim(),
        username: this.username().trim(),
        email: this.email().trim(),
        phoneNumber: this.phoneNumber().trim(),
        roleNames: [this.role()],
        hospitalId: this.facilityId(),
        licenseNumber: this.needsLicence() ? this.licenseNumber().trim() : undefined,
      })
      .subscribe({
        next: (user) => {
          this.saving.set(false);
          this.registered.emit(user);
        },
        error: (err: unknown) => {
          this.saving.set(false);
          this.errorText.set(serverMessage(err));
          this.errorKey.set('PROVIDER.REGISTER.FAILED');
        },
      });
  }
}
