import { HttpClient } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';

import { AuthService } from '../auth/auth.service';

@Component({
  selector: 'app-force-change-password',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './force-change-password.html',
  styleUrls: ['./force-change-password.scss'],
})
export class ForceChangePasswordComponent {
  currentPassword = '';
  newPassword = '';
  confirmPassword = '';
  showCurrent = false;
  showNew = false;
  showConfirm = false;

  loading = signal(false);
  error = signal('');
  success = signal(false);

  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);

  get passwordsMatch(): boolean {
    return this.newPassword.length > 0 && this.newPassword === this.confirmPassword;
  }

  get newPasswordValid(): boolean {
    return this.newPassword.length >= 8;
  }

  submit(): void {
    this.error.set('');

    if (!this.currentPassword) {
      this.error.set('Please enter your temporary (current) password.');
      return;
    }
    if (!this.newPasswordValid) {
      this.error.set('New password must be at least 8 characters.');
      return;
    }
    if (!this.passwordsMatch) {
      this.error.set('New passwords do not match.');
      return;
    }
    if (this.newPassword === this.currentPassword) {
      this.error.set('Your new password must differ from the temporary password.');
      return;
    }

    this.loading.set(true);

    this.http
      .post<{ message?: string }>('/auth/me/change-password', {
        currentPassword: this.currentPassword,
        newPassword: this.newPassword,
      })
      .subscribe({
        next: () => {
          this.loading.set(false);
          this.success.set(true);
          // Redirect to dashboard after a brief pause so the user sees the success message
          setTimeout(() => {
            void this.router.navigateByUrl(this.auth.resolveLandingPath());
          }, 2000);
        },
        error: (err) => {
          this.loading.set(false);
          this.error.set(
            (err?.error?.message as string | undefined) ??
              'Failed to change password. Please try again.',
          );
        },
      });
  }

  logout(): void {
    this.auth.logout();
    void this.router.navigateByUrl('/login');
  }
}
