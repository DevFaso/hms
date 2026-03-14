import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { CommonModule } from '@angular/common';

import { AuthService } from '../auth/auth.service';
import { ProfileService } from '../services/profile.service';

@Component({
  selector: 'app-account-setup',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './account-setup.html',
  styleUrls: ['./account-setup.scss'],
})
export class AccountSetupComponent implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly profileService = inject(ProfileService);
  private readonly router = inject(Router);

  needsUsername = signal(false);
  needsPassword = signal(false);

  // Username form
  newUsername = '';
  usernameError = '';
  usernameSuccess = false;

  // Password form
  currentPassword = '';
  newPassword = '';
  confirmPassword = '';
  passwordError = '';
  passwordSuccess = false;

  showCurrentPassword = false;
  showNewPassword = false;

  loading = false;

  ngOnInit(): void {
    const profile = this.auth.getUserProfile();
    if (!profile) {
      void this.router.navigateByUrl('/login');
      return;
    }
    this.needsUsername.set(!!profile.forceUsernameChange);
    this.needsPassword.set(!!profile.forcePasswordChange);

    // If nothing needs changing, go to dashboard
    if (!this.needsUsername() && !this.needsPassword()) {
      void this.router.navigateByUrl(this.auth.resolveLandingPath());
    }
  }

  get allDone(): boolean {
    const usernameDone = !this.needsUsername() || this.usernameSuccess;
    const passwordDone = !this.needsPassword() || this.passwordSuccess;
    return usernameDone && passwordDone;
  }

  submitUsername(): void {
    this.usernameError = '';
    const trimmed = this.newUsername.trim();
    if (trimmed.length < 3 || trimmed.length > 50) {
      this.usernameError = 'Username must be between 3 and 50 characters.';
      return;
    }
    if (!/^[a-zA-Z0-9._-]+$/.test(trimmed)) {
      this.usernameError = 'Only letters, digits, dots, hyphens, and underscores allowed.';
      return;
    }

    this.loading = true;
    this.profileService.changeUsername(trimmed).subscribe({
      next: () => {
        this.usernameSuccess = true;
        this.loading = false;
        // Update stored profile
        const profile = this.auth.getUserProfile();
        if (profile) {
          profile.username = trimmed;
          profile.forceUsernameChange = false;
          this.auth.setUserProfile(profile);
        }
      },
      error: (err) => {
        this.loading = false;
        this.usernameError = err?.error?.message ?? 'Failed to change username. Please try again.';
      },
    });
  }

  submitPassword(): void {
    this.passwordError = '';
    if (!this.currentPassword) {
      this.passwordError = 'Please enter your current password.';
      return;
    }
    if (this.newPassword.length < 8) {
      this.passwordError = 'New password must be at least 8 characters.';
      return;
    }
    if (this.newPassword !== this.confirmPassword) {
      this.passwordError = 'Passwords do not match.';
      return;
    }
    if (this.newPassword === this.currentPassword) {
      this.passwordError = 'New password must differ from the current password.';
      return;
    }

    this.loading = true;
    this.profileService.changePassword(this.currentPassword, this.newPassword).subscribe({
      next: () => {
        this.passwordSuccess = true;
        this.loading = false;
        // Update stored profile
        const profile = this.auth.getUserProfile();
        if (profile) {
          profile.forcePasswordChange = false;
          this.auth.setUserProfile(profile);
        }
      },
      error: (err) => {
        this.loading = false;
        this.passwordError = err?.error?.message ?? 'Failed to change password. Please try again.';
      },
    });
  }

  continue(): void {
    void this.router.navigateByUrl(this.auth.resolveLandingPath());
  }
}
