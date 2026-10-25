import { isPlatformBrowser } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import {
  AfterViewInit,
  afterNextRender,
  Component,
  ElementRef,
  inject,
  Injector,
  OnInit,
  PLATFORM_ID,
  ViewChild,
  ChangeDetectionStrategy,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { AuthService, type LoginUserProfile } from '../auth/auth.service';
import { OidcAuthService } from '../auth/oidc-auth.service';
import { safeReturnUrl } from '../auth/return-url';
import { RoleContextService } from '../core/role-context.service';
import { SessionScopeService } from '../core/session-scope.service';
import { BrandMarkComponent } from '../shared/brand-mark/brand-mark.component';
import { RoleLabelPipe } from '../shared/pipes/role-label.pipe';

const SUPER_ADMIN_ROLE = 'ROLE_SUPER_ADMIN';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [FormsModule, RouterModule, TranslateModule, BrandMarkComponent, RoleLabelPipe],
  templateUrl: './login.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrls: ['./login.scss'],
})
export class Login implements OnInit, AfterViewInit {
  /**
   * v1.0 row 11: keyboard-first staff (receptionists, data clerks) land
   * on /login on every shift change. Programmatic focus on
   * `#usernameInput` after the view paints — preferred over the HTML
   * `autofocus` attribute because `autofocus` fires before assistive
   * tech is ready and can steal focus mid-page-load. See
   * docs/ui/accessibility.md §6.8.
   */
  @ViewChild('usernameInput') private usernameInput?: ElementRef<HTMLInputElement>;

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

  // Resend activation e-mail (the third link under the form). The e-mail
  // activation link now lands on /verify; this is the way to get a fresh one
  // when the first never arrived or expired.
  resendActivationMode = false;
  resendActivationEmail = '';
  resendActivationLoading = false;
  resendActivationSuccess = '';

  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly auth = inject(AuthService);
  private readonly oidcAuth = inject(OidcAuthService);
  private readonly roleContext = inject(RoleContextService);
  private readonly injector = inject(Injector);
  private readonly hostEl = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly sessionScope = inject(SessionScopeService);
  private readonly translate = inject(TranslateService);
  private readonly platformId = inject(PLATFORM_ID);
  private readonly isBrowser = isPlatformBrowser(this.platformId);

  /**
   * KC-2b: true when SSO is enabled <em>and</em> the issuer is reachable
   * (i.e. Keycloak discovery succeeded). Bound to the SSO button —
   * hides the button automatically when Keycloak is unreachable so the
   * user falls back to the legacy form rather than tripping over a 502.
   */
  get oidcLoginEnabled(): boolean {
    return this.oidcAuth.isAvailable();
  }

  /**
   * KC-2b: true when SSO was configured but discovery failed. Drives the
   * "temporarily unavailable" banner above the legacy form so the user
   * understands why the SSO button vanished.
   */
  get oidcDiscoveryFailed(): boolean {
    return this.oidcAuth.isEnabled() && this.oidcAuth.discoveryFailed();
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

  /**
   * v1.0 row 11: focus the username input on initial paint. Skipped
   * when bootstrap or role-selection modes are active — those modes
   * render a different first field and we don't want to steal focus
   * from the field the user actually needs.
   */
  ngAfterViewInit(): void {
    if (!this.isBrowser) return;
    if (this.bootstrapMode || this.roleSelectionMode) return;
    // queueMicrotask defers one tick past Angular's first change-
    // detection cycle so the @ViewChild is populated.
    queueMicrotask(() => this.usernameInput?.nativeElement.focus({ preventScroll: true }));
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
        this.error = this.translate.instant('LOGIN.SERVER_UNREACHABLE');
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
      this.error = this.translate.instant('LOGIN.ALL_FIELDS_REQUIRED');
      return;
    }
    if (this.bootstrap.password.length < 8) {
      this.error = this.translate.instant('LOGIN.PASSWORD_MIN_LENGTH');
      return;
    }
    if (this.bootstrap.password !== this.bootstrap.confirmPassword) {
      this.error = this.translate.instant('LOGIN.PASSWORDS_MISMATCH');
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
          this.bootstrapSuccess = this.translate.instant('LOGIN.BOOTSTRAP_SUCCESS', {
            username: res?.username ?? this.bootstrap.username,
          });
          // Pre-fill login form with the new username
          this.username = this.bootstrap.username;
          this.password = '';
          // Switch back to login mode
          this.bootstrapMode = false;
          this.bootstrapAllowed = false;
        },
        error: (err) => {
          this.bootstrapLoading = false;
          this.error = err?.error?.message ?? this.translate.instant('LOGIN.SETUP_FAILED');
        },
      });
  }

  submit(selectedRole?: string): void {
    if (!this.isBrowser || this.loading) return;
    this.error = '';
    this.bootstrapSuccess = '';
    if (!this.username || !this.password) {
      this.error = this.translate.instant('LOGIN.CREDENTIALS_REQUIRED');
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
            // ROLE_SUPER_ADMIN first: it reaches every page an inherited role
            // would, and picking a lesser role by mistake 403s the admin pages.
            this.availableRoles = [...res.availableRoles].sort(
              (a, b) => Number(b === SUPER_ADMIN_ROLE) - Number(a === SUPER_ADMIN_ROLE),
            );
            this.roleContext.setHeldRoles(res.availableRoles);
            this.focusRecommendedRole();
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
            this.error = this.translate.instant('LOGIN.TOKEN_MISSING');
            this.loading = false;
            return;
          }

          this.auth.setToken(token, this.remember);

          // Persist the refresh token so the error interceptor can silently
          // renew the access token when it expires, avoiding logout mid-session.
          if (res.refreshToken) {
            this.auth.setRefreshToken(res.refreshToken, this.remember);
          }

          // Seed role context from JWT immediately so the UI has provisional
          // role information before the session bootstrap response arrives.
          const jwtRoles = this.auth.getRoles();
          this.roleContext.setRoles(jwtRoles);
          // No picker means the token holds every role; with one, the picker kept them.
          if (!selectedRole) this.roleContext.setHeldRoles(jwtRoles);
          if (jwtRoles.length === 1) {
            this.roleContext.activeRole = jwtRoles[0];
          }
          // Cross-tenant (design call #5): super-admins land on every list
          // page in "all hospitals" mode by default. Without this seed,
          // _globalView stays at its default (false) and the auth
          // interceptor falls back to _activeHospitalId — silently
          // scoping every request to the JWT primary hospital even
          // though the dropdown shows "All hospitals". That mismatch
          // showed up as "/consultations returns [] but tile says 3"
          // for super-admins whose primary hospital had no consultations.
          // app.component.ts already does this on hard-refresh /
          // re-hydration; this seeds the same state on the cold-login
          // path so the bug doesn't appear in the brand-new session.
          this.roleContext.markSuperAdminGlobalDefaults();

          // ── Session scope (E9 #55b): the live session decides, never the token ──
          // The login body is DB-fresh, so it stands in when the server cannot
          // be asked; the JWT claims are not consulted on either path.
          this.sessionScope
            .hydrate({
              forcePasswordChange: res.forcePasswordChange ?? false,
              forceUsernameChange: res.forceUsernameChange ?? false,
              phoneNumber: res.phoneNumber,
              licenseNumber: res.licenseNumber,
            })
            .subscribe((bootstrap) => {
              const forcePasswordChange = res.forcePasswordChange ?? false;
              const forceUsernameChange = res.forceUsernameChange ?? false;

              if (!bootstrap) {
                this.sessionScope.applyScope(
                  (res.hospitalIds ?? []).filter((v) => !!v),
                  res.primaryHospitalId ?? null,
                );
                if (res.id && res.username) {
                  const profile: LoginUserProfile = {
                    id: res.id,
                    username: res.username,
                    email: res.email ?? '',
                    firstName: res.firstName,
                    lastName: res.lastName,
                    phoneNumber: res.phoneNumber,
                    profileImageUrl: res.profilePictureUrl,
                    roles: jwtRoles,
                    profileType: res.profileType,
                    licenseNumber: res.licenseNumber,
                    staffId: res.staffId,
                    roleName: res.roleName,
                    active: res.active ?? true,
                    forcePasswordChange,
                    forceUsernameChange,
                    primaryHospitalId: res.primaryHospitalId,
                    primaryHospitalName: res.primaryHospitalName,
                    hospitalIds: res.hospitalIds,
                  };
                  this.auth.setUserProfile(profile);
                }
              }

              const needsSetup = forcePasswordChange || forceUsernameChange;
              // A returnUrl means the user clicked a deep link (almost always
              // from an email) and was bounced here to sign in. Honour it —
              // but account setup still wins, since a forced password or
              // username change has to happen before anything else.
              const returnUrl = safeReturnUrl(this.route.snapshot.queryParamMap.get('returnUrl'));
              const dest = needsSetup
                ? '/account-setup'
                : (returnUrl ?? this.auth.resolveLandingPath());
              void this.router.navigateByUrl(dest);
              this.loading = false;
            });
        },
        error: (err) => {
          this.loading = false;
          this.error =
            // `error` is the HTTP reason phrase ("Unauthorized") — English and
            // not a sentence. The backend's message is translated (D64).
            err?.error?.message ?? this.translate.instant('LOGIN.LOGIN_FAILED');
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

  /** Pre-selects the recommended card: Enter signs in with it, Tab still reaches the others. */
  private focusRecommendedRole(): void {
    afterNextRender(
      () =>
        this.hostEl.nativeElement
          .querySelector<HTMLButtonElement>('.role-card--recommended')
          ?.focus(),
      { injector: this.injector },
    );
  }

  /** The card the picker pre-selects: ROLE_SUPER_ADMIN when the account holds it. */
  isRecommendedRole(role: string): boolean {
    return role === SUPER_ADMIN_ROLE;
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
    this.resendActivationMode = false;
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
    const identifier = this.forgotPasswordEmail.trim();
    if (!identifier) {
      this.error = this.translate.instant('LOGIN.ENTER_EMAIL_OR_PHONE');
      return;
    }
    this.forgotPasswordLoading = true;
    // QA D171: a phone-only account gets its reset link by SMS.
    const body = identifier.includes('@') ? { email: identifier } : { phone: identifier };
    this.http.post<void>('/auth/password/request', body).subscribe({
      next: () => {
        this.forgotPasswordLoading = false;
        this.forgotPasswordSuccess = this.translate.instant('LOGIN.RESET_LINK_SENT');
      },
      error: () => {
        this.forgotPasswordLoading = false;
        // Always show neutral message to avoid email enumeration
        this.forgotPasswordSuccess = this.translate.instant('LOGIN.RESET_LINK_SENT');
      },
    });
  }

  // ─── Forgot Username ─────────────────────────────────────────────────────────

  openForgotUsername(): void {
    this.forgotUsernameMode = true;
    this.forgotPasswordMode = false;
    this.resendActivationMode = false;
    this.error = '';
  }

  closeForgotUsername(): void {
    this.forgotUsernameMode = false;
    this.error = '';
  }

  // ─── Resend activation e-mail ───────────────────────────────────────────────

  openResendActivation(): void {
    this.resendActivationMode = true;
    this.forgotPasswordMode = false;
    this.forgotUsernameMode = false;
    this.error = '';
    this.resendActivationEmail = '';
    this.resendActivationSuccess = '';
  }

  closeResendActivation(): void {
    this.resendActivationMode = false;
    this.resendActivationSuccess = '';
    this.error = '';
  }

  submitResendActivation(): void {
    if (!this.isBrowser || this.resendActivationLoading) return;
    this.error = '';
    this.resendActivationSuccess = '';
    const contact = this.resendActivationEmail.trim();
    if (!contact) {
      this.error = this.translate.instant('LOGIN.ENTER_EMAIL_OR_PHONE');
      return;
    }
    // QA D542: the contact goes in the body, never the URL (logs, history).
    // QA D311/D418: a phone-only account has no address to type; the server
    // re-sends its activation code by SMS when given the number instead.
    const params: Record<string, string> = contact.includes('@')
      ? { email: contact }
      : { phone: contact };
    this.resendActivationLoading = true;
    // One answer whatever the backend said: it does not reveal whether the
    // address exists, and neither does this dialog.
    const done = (): void => {
      this.resendActivationLoading = false;
      this.resendActivationSuccess = this.translate.instant('LOGIN.ACTIVATION_LINK_SENT');
    };
    this.http
      .post<void>('/auth/resend-verification', params)
      .subscribe({ next: done, error: done });
  }
}
