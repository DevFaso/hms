import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';

import { AuthService, type LoginUserProfile } from '../auth/auth.service';
import { MfaService } from '../auth/mfa.service';
import { RoleContextService } from '../core/role-context.service';

/**
 * T-33: MFA Challenge page.
 * Shown after login when mfaRequired=true.
 * The mfaToken and mfaEnrolled flags are passed via Router state.
 */
@Component({
  selector: 'app-mfa-challenge',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './mfa-challenge.html',
  styleUrls: ['./mfa-enroll.scss'], // reuse same styles
})
export class MfaChallengeComponent {
  private readonly mfaService = inject(MfaService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly roleContext = inject(RoleContextService);

  loading = signal(false);
  error = signal('');

  /** 6-digit TOTP or 8-char backup code */
  code = '';

  /** Short-lived MFA token from login response */
  mfaToken = '';

  /** Whether user already has MFA enrolled */
  mfaEnrolled = true;

  /** Username for display */
  username = '';

  constructor() {
    const nav = this.router.getCurrentNavigation();
    const state = nav?.extras?.state as Record<string, unknown> | undefined;
    this.mfaToken = (state?.['mfaToken'] as string) ?? '';
    this.mfaEnrolled = (state?.['mfaEnrolled'] as boolean) ?? true;
    this.username = (state?.['username'] as string) ?? '';

    // If no mfaToken, redirect back to login
    if (!this.mfaToken) {
      this.router.navigateByUrl('/login');
    }
  }

  submitCode(): void {
    if (!this.code || this.code.length < 6) {
      this.error.set('Enter a valid code (6-digit TOTP or 8-character backup code).');
      return;
    }
    this.loading.set(true);
    this.error.set('');

    this.mfaService.verifyLogin(this.mfaToken, this.code).subscribe({
      next: (res) => {
        const token = res.accessToken;
        if (!token) {
          this.error.set(res.message ?? 'Verification failed.');
          this.loading.set(false);
          return;
        }

        // Successful MFA — same flow as normal login completion
        this.auth.setToken(token, true);
        if (res.refreshToken) {
          this.auth.setRefreshToken(res.refreshToken, true);
        }

        const jwtRoles = this.auth.getRoles();
        this.roleContext.setRoles(jwtRoles);
        if (jwtRoles.length === 1) {
          this.roleContext.activeRole = jwtRoles[0];
        }

        const bodyHospitalIds = (res.hospitalIds ?? []).filter((v: string) => !!v);
        const permittedIds =
          bodyHospitalIds.length > 0 ? bodyHospitalIds : this.auth.getPermittedHospitalIds();
        this.roleContext.setPermittedHospitalIds(permittedIds);
        if (permittedIds.length === 1) {
          this.roleContext.activeHospitalId = permittedIds[0];
        } else if (res.primaryHospitalId) {
          this.roleContext.activeHospitalId = res.primaryHospitalId;
        }

        if (res.id && res.username) {
          const profile: LoginUserProfile = {
            id: res.id,
            username: res.username,
            email: res.email ?? '',
            firstName: res.firstName,
            lastName: res.lastName,
            phoneNumber: res.phoneNumber,
            profileImageUrl: res.profilePictureUrl,
            roles: res.roles ?? [],
            profileType: res.profileType,
            licenseNumber: res.licenseNumber,
            staffId: res.staffId,
            roleName: res.roleName,
            active: res.active ?? true,
            forcePasswordChange: res.forcePasswordChange,
            forceUsernameChange: res.forceUsernameChange,
            primaryHospitalId: res.primaryHospitalId,
            primaryHospitalName: res.primaryHospitalName,
            hospitalIds: res.hospitalIds,
          };
          this.auth.setUserProfile(profile);
        }

        const needsSetup = res.forcePasswordChange || res.forceUsernameChange;
        const dest = needsSetup ? '/account-setup' : this.auth.resolveLandingPath();
        this.router.navigateByUrl(dest);
        this.loading.set(false);
      },
      error: (err) => {
        this.loading.set(false);
        this.error.set(err?.error?.message ?? 'Invalid code. Please try again.');
      },
    });
  }

  backToLogin(): void {
    this.router.navigateByUrl('/login');
  }
}
