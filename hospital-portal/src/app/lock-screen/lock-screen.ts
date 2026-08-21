import { Component, inject, signal, output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { Observable, of, tap } from 'rxjs';

import { AuthService } from '../auth/auth.service';
import { IdleService } from '../core/idle.service';

@Component({
  selector: 'app-lock-screen',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './lock-screen.html',
  styleUrl: './lock-screen.scss',
})
export class LockScreenComponent {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly idle = inject(IdleService);
  private readonly translate = inject(TranslateService);

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

    // The screen locks after 10 minutes idle; the access token lives 15. Any
    // break longer than that leaves an expired token behind, and
    // /auth/verify-password is an authenticated endpoint — the JWT filter
    // rejects the request before the password is ever checked, so we would
    // tell the user their correct password was wrong, on every attempt,
    // forever. Renew the access token first whenever it is already dead.
    this.ensureFreshToken().subscribe({
      next: () => this.verifyPassword(),
      error: (err: HttpErrorResponse) => this.failWith(this.renewalErrorKey(err)),
    });
  }

  /**
   * Resolves immediately when the access token is still usable. Otherwise
   * spends the refresh cookie to mint a new one — the same exchange the error
   * interceptor performs for every other endpoint. Deliberately NOT wired
   * through the interceptor's retry path: that would fire a refresh on every
   * mistyped password, letting failed unlock attempts keep a session alive.
   */
  private ensureFreshToken(): Observable<unknown> {
    if (this.auth.getToken() && !this.auth.isExpired()) {
      return of(null);
    }
    return this.auth.refreshTokenRequest().pipe(
      tap((tokens) => {
        this.auth.setToken(tokens.accessToken);
        if (tokens.refreshToken) {
          this.auth.setRefreshToken(tokens.refreshToken);
        }
      }),
    );
  }

  private verifyPassword(): void {
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
        // Reached only with a token we just confirmed good, so a 401 here
        // really is the wrong password.
        error: (err: HttpErrorResponse) => this.failWith(this.verifyErrorKey(err)),
      });
  }

  private renewalErrorKey(err: HttpErrorResponse): string {
    // 401 on the refresh call means the refresh cookie is gone or the server
    // refused it (expired, revoked, or a server-side idle timeout). The
    // session is over — say so rather than blaming the password. The error
    // interceptor has already cleared auth state and routed to /login.
    return err?.status === 401 ? 'LOCK.ERROR_SESSION_EXPIRED' : 'LOCK.ERROR_VERIFY_FAILED';
  }

  private verifyErrorKey(err: HttpErrorResponse): string {
    if (err?.status === 401) return 'LOCK.ERROR_WRONG_PASSWORD';
    // The endpoint 403s when the posted username is not the token's subject.
    if (err?.status === 403) return 'LOCK.ERROR_SESSION_MISMATCH';
    return 'LOCK.ERROR_VERIFY_FAILED';
  }

  private failWith(messageKey: string): void {
    this.loading.set(false);
    this.password = '';
    this.error = this.translate.instant(messageKey);
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
