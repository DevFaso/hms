import { isPlatformBrowser } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, inject, OnInit, PLATFORM_ID } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

@Component({
  selector: 'app-reset-password',
  standalone: true,
  imports: [FormsModule, RouterModule, TranslateModule],
  templateUrl: './reset-password.html',
  styleUrls: ['./reset-password.scss'],
})
export class ResetPasswordComponent implements OnInit {
  newPassword = '';
  confirmPassword = '';
  error = '';
  success = '';
  loading = false;
  showNewPassword = false;
  showConfirmPassword = false;
  currentYear = new Date().getFullYear();

  private token = '';
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly platformId = inject(PLATFORM_ID);
  private readonly isBrowser = isPlatformBrowser(this.platformId);

  ngOnInit(): void {
    this.token = this.route.snapshot.queryParamMap.get('token') ?? '';
    if (!this.token) {
      this.error = 'Invalid or missing reset token. Please request a new password reset link.';
    }
  }

  get tokenMissing(): boolean {
    return !this.token;
  }

  toggleNewPassword(): void {
    this.showNewPassword = !this.showNewPassword;
  }

  toggleConfirmPassword(): void {
    this.showConfirmPassword = !this.showConfirmPassword;
  }

  submit(): void {
    if (!this.isBrowser || this.loading) return;
    this.error = '';

    if (!this.newPassword || !this.confirmPassword) {
      this.error = 'Both fields are required.';
      return;
    }
    if (this.newPassword.length < 8) {
      this.error = 'Password must be at least 8 characters.';
      return;
    }
    if (this.newPassword !== this.confirmPassword) {
      this.error = 'Passwords do not match.';
      return;
    }

    this.loading = true;

    this.http
      .post<void>('/auth/password/confirm', {
        token: this.token,
        newPassword: this.newPassword,
      })
      .subscribe({
        next: () => {
          this.loading = false;
          this.success = 'Your password has been reset successfully. You can now sign in.';
        },
        error: (err) => {
          this.loading = false;
          this.error =
            err?.error?.message ??
            'This reset link is invalid or has expired. Please request a new one.';
        },
      });
  }

  goToLogin(): void {
    void this.router.navigate(['/login']);
  }
}
