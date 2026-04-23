import { isPlatformBrowser } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, inject, OnInit, PLATFORM_ID } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterModule } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { AuthService, type LoginUserProfile } from '../auth/auth.service';
import { OidcAuthService } from '../auth/oidc-auth.service';
import { RoleContextService } from '../core/role-context.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [FormsModule, RouterModule, TranslateModule],
  templateUrl: './login.html',
  styleUrls: ['./login.scss'],
})
export class Login implements OnInit {
  username = '';
  password = '';
  error = '';
  loading = false;
  remember = true;
  showPassword = false;
  showBootstrapPassword = false;
  currentYear = new Date().getFullYear();

  /** Multi-role selection state */
  roleSelectionMode = false;
  availableRoles: string[] = [];

  /** Bootstrap (first-time setup) state */
  bootstrapAllowed = false;
  bootstrapMode = false;
  bootstrapLoading = false;
  bootstrapSuccess = '';
  bootstrap = {
    username: '',
    email: '',
    password: '',
    confirmPassword: '',
    firstName: '',
    lastName: '',
    phoneNumber: '',
  };

  /** Forgot-password state */
  forgotPasswordMode = false;
  forgotPasswordEmail = '';
  forgotPasswordLoading = false;
  forgotPasswordSuccess = '';

  /** Forgot-username state */
  forgotUsernameMode = false;

  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);
  private readonly oidcAuth = inject(OidcAuthService);
  private readonly roleContext = inject(RoleContextService);
  private readonly platformId = inject(PLATFORM_ID);
  private readonly isBrowser = isPlatformBrowser(this.platformId);

  /** KC-2b: true when the env enables Keycloak/OIDC login. */
  get oidcLoginEnabled(): boolean {
    return this.oidcAuth.isEnabled();
  }

  /** KC-2b: kick off Authorization Code + PKCE — browser navigates to Keycloak. */
  loginWithKeycloak(): void {
    if (!this.isBrowser || !this.oidcLoginEnabled) return;
    this.error = '';
    this.oidcAuth.login();
  }

  ngOnInit(): void {
    if (!this.isBrowser) return;
    this.checkBootstrapStatus();
  }

  /** Check if the system needs initial setup (no users exist) */
  private checkBootstrapStatus(attempt = 1): void {
    this.http.get<{ allowed: boolean }>('/auth/bootstrap-status').subscribe({
      next: (res) => {
        this.bootstrapAllowed = res?.allowed === true;
        if (this.bootstrapAllowed) {
          this.bootstrapMode = true;
        }
      },
      error: (err) => {
        // Retry once after a short delay (backend may still be starting)
        if (attempt < 2) {
          setTimeout(() => this.checkBootstrapStatus(attempt + 1), 2000);
          return;
        }
        // After retries exhausted, show a helpful message
        this.bootstrapAllowed = false;
        console.error('[bootstrap-status] Failed after retries:', err?.status, err?.message);
        this.error = 'Unable to reach the server. Please refresh the page or try again shortly.';
      },
    });
  }

  /** Create the first Super Admin account */
  submitBootstrap(): void {
    if (!this.isBrowser || this.bootstrapLoading) return;
    this.error = '';
    this.bootstrapSuccess = '';

    if (
      !this.bootstrap.username ||
      !this.bootstrap.email ||
      !this.bootstrap.password ||
      !this.bootstrap.firstName ||
      !this.bootstrap.lastName ||
      !this.bootstrap.phoneNumber
    ) {
      this.error = 'All required fields must be filled.';
      return;
    }
    if (this.bootstrap.password.length < 8) {
      this.error = 'Password must be at least 8 characters.';
      return;
    }
    if (this.bootstrap.password !== this.bootstrap.confirmPassword) {
      this.error = 'Passwords do not match.';
      return;
    }

    this.bootstrapLoading = true;

    this.http
      .post<{ message?: string; username?: string }>('/auth/bootstrap-signup', {
        username: this.bootstrap.username,
        email: this.bootstrap.email,
        password: this.bootstrap.password,
        firstName: this.bootstrap.firstName,
        lastName: this.bootstrap.lastName,
        phoneNumber: this.bootstrap.phoneNumber,
      })
      .subscribe({
        next: (res) => {
          this.bootstrapLoading = false;
          this.bootstrapSuccess = `Super Admin account "${res?.username ?? this.bootstrap.username}" created successfully! You can now sign in.`;
          // Pre-fill login form with the new username
          this.username = this.bootstrap.username;
          this.password = '';
          // Switch back to login mode
          this.bootstrapMode = false;
          this.bootstrapAllowed = false;
        },
        error: (err) => {
          this.bootstrapLoading = false;
          this.error =
            err?.error?.message ?? err?.error?.error ?? 'Setup failed. Please try again.';
        },
      });
  }

  submit(selectedRole?: string): void {
    if (!this.isBrowser || this.loading) return;
    this.error = '';
    this.bootstrapSuccess = '';
    if (!this.username || !this.password) {
      this.error = 'Username and password required.';
      return;
    }
    this.loading = true;

    const payload: Record<string, string> = {
      username: this.username,
      password: this.password,
    };
    if (selectedRole) {
      payload['selectedRole'] = selectedRole;
    }

    this.http
      .post<{
        tokenType?: string;
        accessToken?: string;
        refreshToken?: string;
        token?: string;
        jwt?: string;
        error?: string;
        id?: string;
        username?: string;
        email?: string;
        firstName?: string;
        lastName?: string;
        phoneNumber?: string;
        profilePictureUrl?: string;
        roles?: string[];
        profileType?: 'STAFF' | 'PATIENT';
        licenseNumber?: string;
        staffId?: string;
        roleName?: string;
        active?: boolean;
        forcePasswordChange?: boolean;
        forceUsernameChange?: boolean;
        primaryHospitalId?: string;
        primaryHospitalName?: string;
        hospitalIds?: string[];
        roleSelectionRequired?: boolean;
        availableRoles?: string[];
        mfaRequired?: boolean;
        mfaEnrolled?: boolean;
        mfaToken?: string;
      }>('/auth/login', payload)
      .subscribe({
        next: (res) => {
          // ── Multi-role gate: ask user to pick a role ──
          if (res?.roleSelectionRequired && res.availableRoles?.length) {
            this.availableRoles = res.availableRoles;
            this.roleSelectionMode = true;
            this.loading = false;
            return;
          }

          // ── MFA gate (T-33): redirect to challenge or enrollment ──
          if (res?.mfaRequired) {
            this.loading = false;
            if (res.mfaEnrolled) {
              this.router.navigateByUrl('/mfa-challenge', {
                state: {
                  mfaToken: res.mfaToken,
                  mfaEnrolled: true,
                  username: res.username ?? this.username,
                },
              });
            } else {
              // User needs to enroll — pass the MFA token via router state
              // so the enrollment page can authenticate with it.
              this.router.navigateByUrl('/mfa-enroll', {
                state: {
                  mfaToken: res.mfaToken,
                  username: res.username ?? this.username,
                },
              });
            }
            return;
          }

          const token = res?.token ?? res?.accessToken ?? res?.jwt;
          if (!token) {
            this.error = 'Token missing in response.';
            this.loading = false;
            return;
          }

          this.auth.setToken(token, this.remember);

          // Persist the refresh token so the error interceptor can silently
          // renew the access token when it expires, avoiding logout mid-session.
          if (res.refreshToken) {
            this.auth.setRefreshToken(res.refreshToken, this.remember);
          }

          // Hydrate role context immediately so the interceptor sends X-Hospital-Id
          // on all subsequent requests (including the very first one after redirect).
          const jwtRoles = this.auth.getRoles();
          this.roleContext.setRoles(jwtRoles);

          // For multi-role users the JWT now contains only the chosen role.
          // For single-role users setRoles() already sets activeRole automatically.
          if (jwtRoles.length === 1) {
            this.roleContext.activeRole = jwtRoles[0];
          }

          // Prefer hospital IDs from the response body (authoritative, fresh from DB)
          // over JWT claims (which may be stale if assignment changed since last login).
          const bodyHospitalIds = (res.hospitalIds ?? []).filter((v) => !!v);
          const permittedIds =
            bodyHospitalIds.length > 0 ? bodyHospitalIds : this.auth.getPermittedHospitalIds();
          this.roleContext.setPermittedHospitalIds(permittedIds);

          // Non-admin staff always resolve to exactly one hospital (their primary).
          // Admin roles with a single hospital also get locked here.
          if (permittedIds.length === 1) {
            this.roleContext.activeHospitalId = permittedIds[0];
          } else if (res.primaryHospitalId) {
            // Multi-hospital admin: pre-select the primary hospital
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

          // Redirect to account setup page if user must change credentials
          const needsSetup = res.forcePasswordChange || res.forceUsernameChange;
          const dest = needsSetup ? '/account-setup' : this.auth.resolveLandingPath();
          void this.router.navigateByUrl(dest);
          this.loading = false;
        },
        error: (err) => {
          this.loading = false;
          this.error =
            err?.error?.message ?? err?.error?.error ?? 'Login failed. Please try again.';
        },
      });
  }

  // ─── Multi-Role Selection ─────────────────────────────────────────────────

  /** User picks a role from the role-picker UI → re-submit with that role. */
  selectRole(role: string): void {
    this.roleSelectionMode = false;
    this.submit(role);
  }

  /** Go back to the credential form. */
  cancelRoleSelection(): void {
    this.roleSelectionMode = false;
    this.availableRoles = [];
    this.password = '';
    this.error = '';
  }

  /** Human-readable role label, e.g. "ROLE_DOCTOR" → "Doctor" */
  formatRoleName(role: string): string {
    return this.auth.formatRole(role);
  }

  /** Material icon for a role — used in the role-picker cards. */
  roleIcon(role: string): string {
    const icons: Record<string, string> = {
      ROLE_SUPER_ADMIN: 'admin_panel_settings',
      ROLE_HOSPITAL_ADMIN: 'local_hospital',
      ROLE_ADMIN: 'settings',
      ROLE_DOCTOR: 'stethoscope',
      ROLE_NURSE: 'medical_services',
      ROLE_MIDWIFE: 'pregnant_woman',
      ROLE_RECEPTIONIST: 'desk',
      ROLE_LAB_DIRECTOR: 'biotech',
      ROLE_LAB_MANAGER: 'science',
      ROLE_LAB_SCIENTIST: 'science',
      ROLE_LAB_TECHNICIAN: 'biotech',
      ROLE_QUALITY_MANAGER: 'verified',
      ROLE_PHARMACIST: 'medication',
      ROLE_RADIOLOGIST: 'radiology',
      ROLE_BILLING_OFFICER: 'payments',
      ROLE_PATIENT: 'person',
      ROLE_STAFF: 'badge',
    };
    return icons[role] ?? 'person';
  }

  togglePasswordVisibility(): void {
    this.showPassword = !this.showPassword;
  }

  toggleBootstrapPasswordVisibility(): void {
    this.showBootstrapPassword = !this.showBootstrapPassword;
  }

  // ─── Forgot Password ────────────────────────────────────────────────────────

  openForgotPassword(): void {
    this.forgotPasswordMode = true;
    this.forgotUsernameMode = false;
    this.error = '';
    this.forgotPasswordEmail = '';
    this.forgotPasswordSuccess = '';
  }

  closeForgotPassword(): void {
    this.forgotPasswordMode = false;
    this.forgotPasswordSuccess = '';
    this.error = '';
  }

  submitForgotPassword(): void {
    if (!this.isBrowser || this.forgotPasswordLoading) return;
    this.error = '';
    this.forgotPasswordSuccess = '';
    if (!this.forgotPasswordEmail) {
      this.error = 'Please enter your email address.';
      return;
    }
    this.forgotPasswordLoading = true;
    this.http.post<void>('/auth/password/request', { email: this.forgotPasswordEmail }).subscribe({
      next: () => {
        this.forgotPasswordLoading = false;
        this.forgotPasswordSuccess =
          'If that email is registered, a password reset link has been sent. Please check your inbox.';
      },
      error: () => {
        this.forgotPasswordLoading = false;
        // Always show neutral message to avoid email enumeration
        this.forgotPasswordSuccess =
          'If that email is registered, a password reset link has been sent. Please check your inbox.';
      },
    });
  }

  // ─── Forgot Username ─────────────────────────────────────────────────────────

  openForgotUsername(): void {
    this.forgotUsernameMode = true;
    this.forgotPasswordMode = false;
    this.error = '';
  }

  closeForgotUsername(): void {
    this.forgotUsernameMode = false;
    this.error = '';
  }
}
