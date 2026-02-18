import { isPlatformBrowser } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, inject, PLATFORM_ID } from '@angular/core';
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
export class Login {
  username = '';
  password = '';
  error = '';
  loading = false;
  remember = true;
  showPassword = false;
  currentYear = new Date().getFullYear();

  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);
  private readonly platformId = inject(PLATFORM_ID);
  private readonly isBrowser = isPlatformBrowser(this.platformId);

  submit(): void {
    if (!this.isBrowser || this.loading) return;
    this.error = '';
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
}
