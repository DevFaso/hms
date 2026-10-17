import { Component, inject, OnInit, signal, ChangeDetectionStrategy } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';

import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { AuthService } from '../auth/auth.service';
import { ProfileService } from '../services/profile.service';

/** How long the "account set up" message stays before the sign-out. */
export const SETUP_SIGN_OUT_DELAY_MS = 2500;

const USERNAME_PATTERN = /^[a-zA-Z0-9._-]+$/;

/**
 * The forced first sign-in. ONE form and ONE save: the username (when the
 * account must change it) and the password (when it must change it) go in a
 * single call, then the user is signed out to sign in with the new details.
 * Two saves could not work: the rename ends the session (the token names the
 * old username), so the password save never ran (D17).
 */
@Component({
  selector: 'app-account-setup',
  standalone: true,
  imports: [FormsModule, TranslateModule],
  templateUrl: './account-setup.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrls: ['./account-setup.scss'],
})
export class AccountSetupComponent implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly profileService = inject(ProfileService);
  private readonly router = inject(Router);
  private readonly translate = inject(TranslateService);

  needsUsername = signal(false);
  needsPassword = signal(false);

  newUsername = '';
  currentPassword = '';
  newPassword = '';
  confirmPassword = '';

  showCurrentPassword = false;
  showNewPassword = false;

  error = '';
  /** The role is not confirmed yet: the message says what to do (D22). */
  roleNotConfirmed = false;
  done = false;
  loading = false;

  ngOnInit(): void {
    const profile = this.auth.getUserProfile();
    if (!profile) {
      void this.router.navigateByUrl('/login');
      return;
    }
    this.needsUsername.set(!!profile.forceUsernameChange);
    this.needsPassword.set(!!profile.forcePasswordChange);

    if (!this.needsUsername() && !this.needsPassword()) {
      void this.router.navigateByUrl(this.auth.resolveLandingPath());
    }
  }

  get canSubmit(): boolean {
    if (this.loading || this.done || !this.currentPassword) return false;
    if (this.needsUsername() && !this.newUsername.trim()) return false;
    if (this.needsPassword() && (!this.newPassword || !this.confirmPassword)) return false;
    return true;
  }

  /** The translation key of the first problem with the form, or null. */
  validationError(): string | null {
    if (this.needsUsername()) {
      const trimmed = this.newUsername.trim();
      if (trimmed.length < 3 || trimmed.length > 50) return 'SETUP.ERR_USERNAME_LENGTH';
      if (!USERNAME_PATTERN.test(trimmed)) return 'SETUP.ERR_USERNAME_CHARS';
    }
    if (!this.currentPassword) return 'SETUP.ERR_CURRENT_REQUIRED';
    if (this.needsPassword()) {
      if (this.newPassword.length < 8) return 'SETUP.ERR_PASSWORD_LENGTH';
      if (this.newPassword !== this.confirmPassword) return 'SETUP.ERR_PASSWORD_MISMATCH';
      if (this.newPassword === this.currentPassword) return 'SETUP.ERR_PASSWORD_SAME';
    }
    return null;
  }

  submit(): void {
    this.error = '';
    this.roleNotConfirmed = false;
    const problem = this.validationError();
    if (problem) {
      this.error = this.translate.instant(problem);
      return;
    }

    this.loading = true;
    this.profileService
      .completeSetup({
        newUsername: this.needsUsername() ? this.newUsername.trim() : undefined,
        currentPassword: this.currentPassword,
        newPassword: this.needsPassword() ? this.newPassword : undefined,
      })
      .subscribe({
        next: () => {
          this.loading = false;
          this.done = true;
          // Clear the typed secrets before anything else runs.
          this.currentPassword = '';
          this.newPassword = '';
          this.confirmPassword = '';
          setTimeout(() => this.signOut(), SETUP_SIGN_OUT_DELAY_MS);
        },
        error: (err: unknown) => {
          this.loading = false;
          this.showError(err);
        },
      });
  }

  /** Leave now: sign in again with the new details. */
  signOut(): void {
    this.auth.logout();
    void this.router.navigateByUrl('/login');
  }

  private showError(err: unknown): void {
    const http = err instanceof HttpErrorResponse ? err : null;
    const body = (http?.error ?? null) as { code?: unknown; message?: unknown } | null;
    if (http?.status === 403 && body?.code === 'hospital_scope_refused') {
      this.roleNotConfirmed = true;
      this.error = this.translate.instant('SETUP.ROLE_NOT_CONFIRMED');
      return;
    }
    // A 400 answer is written for the user, in their language.
    if (http?.status === 400 && typeof body?.message === 'string') {
      this.error = body.message;
      return;
    }
    this.error = this.translate.instant('SETUP.ERR_GENERIC');
  }
}
