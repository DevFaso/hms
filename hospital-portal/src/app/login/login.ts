import { isPlatformBrowser } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, inject, OnInit, PLATFORM_ID } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterModule } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { AuthService, type LoginUserProfile } from '../auth/auth.service';

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

  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);
  private readonly platformId = inject(PLATFORM_ID);
  private readonly isBrowser = isPlatformBrowser(this.platformId);

  ngOnInit(): void {
    if (!this.isBrowser) return;
    this.checkBootstrapStatus();
  }

  /** Check if the system needs initial setup (no users exist) */
  private checkBootstrapStatus(): void {
    this.http.get<{ allowed: boolean }>('/auth/bootstrap-status').subscribe({
      next: (res) => {
        this.bootstrapAllowed = res?.allowed === true;
        if (this.bootstrapAllowed) {
          this.bootstrapMode = true;
        }
      },
      error: () => {
        // If endpoint unreachable, stay on login form
        this.bootstrapAllowed = false;
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
      !this.bootstrap.lastName
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
        phoneNumber: this.bootstrap.phoneNumber || undefined,
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

  submit(): void {
    if (!this.isBrowser || this.loading) return;
    this.error = '';
    this.bootstrapSuccess = '';
    if (!this.username || !this.password) {
      this.error = 'Username and password required.';
      return;
    }
    this.loading = true;

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
      }>('/auth/login', {
        username: this.username,
        password: this.password,
      })
      .subscribe({
        next: (res) => {
          const token = res?.token ?? res?.accessToken ?? res?.jwt;
          if (!token) {
            this.error = 'Token missing in response.';
            this.loading = false;
            return;
          }

          this.auth.setToken(token, this.remember);

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
            };
            this.auth.setUserProfile(profile);
          }

          const dest = this.auth.resolveLandingPath();
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

  togglePasswordVisibility(): void {
    this.showPassword = !this.showPassword;
  }

  toggleBootstrapPasswordVisibility(): void {
    this.showBootstrapPassword = !this.showBootstrapPassword;
  }
}
