import { Component, inject, signal, output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';

import { AuthService } from '../auth/auth.service';
import { IdleService } from '../core/idle.service';

@Component({
  selector: 'app-lock-screen',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './lock-screen.html',
  styleUrl: './lock-screen.scss',
})
export class LockScreenComponent {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly idle = inject(IdleService);

  /** Emitted when the user signs out from the lock screen */
  readonly signedOut = output<void>();

  /** Emitted when a different user wants to sign in (complete session clear) */
  readonly switchedUser = output<void>();

  password = '';
  error = '';
  loading = signal(false);
  showPassword = false;

  get userName(): string {
    const p = this.auth.getUserProfile();
    return p ? `${p.firstName ?? ''} ${p.lastName ?? ''}`.trim() || p.username : '';
  }

  get userInitials(): string {
    const p = this.auth.getUserProfile();
    if (!p) return '?';
    return `${p.firstName?.charAt(0) ?? ''}${p.lastName?.charAt(0) ?? ''}`.toUpperCase();
  }

  get userRole(): string {
    const p = this.auth.getUserProfile();
    if (!p?.roles?.length) return '';
    return this.auth.formatRole(p.roles[0]);
  }

  get userAvatarUrl(): string | null {
    return this.auth.getUserProfile()?.profileImageUrl ?? null;
  }

  get username(): string {
    return this.auth.getSubject() ?? this.auth.getUserProfile()?.username ?? '';
  }

  unlock(): void {
    if (this.loading() || !this.password) return;
    this.error = '';
    this.loading.set(true);

    this.http
      .post<{ message?: string }>('/auth/verify-password', {
        username: this.username,
        password: this.password,
      })
      .subscribe({
        next: () => {
          this.loading.set(false);
          this.password = '';
          this.idle.unlock();
        },
        error: (err) => {
          this.loading.set(false);
          if (err.status === 401) {
            this.error = 'Incorrect password. Please try again.';
          } else if (err.status === 403) {
            this.error = 'Session expired. Please sign in again.';
          } else {
            this.error = err?.error?.message ?? 'Verification failed. Try again.';
          }
          this.password = '';
        },
      });
  }

  signOut(): void {
    this.idle.unlock(); // clear lock state
    this.signedOut.emit();
  }

  /**
   * A different person at the workstation wants to sign in.
   * Clears ALL auth state (token, profile, idle lock) so the login page
   * starts completely fresh — no trace of the previous user.
   */
  switchUser(): void {
    this.idle.unlock(); // clear lock state
    this.switchedUser.emit();
  }

  togglePassword(): void {
    this.showPassword = !this.showPassword;
  }
}
