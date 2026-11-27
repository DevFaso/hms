import {
  Component,
  Injector,
  OnInit,
  inject,
  signal,
  output,
  ChangeDetectionStrategy,
} from '@angular/core';

import { FormsModule } from '@angular/forms';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { Observable, of, tap } from 'rxjs';

import { AuthService } from '../auth/auth.service';
import { IdleService } from '../core/idle.service';
import { RoleLabelPipe } from '../shared/pipes/role-label.pipe';
import { refreshWithSupersededRetry } from '../interceptors/error.interceptor';
import { isAccountChanged } from '../auth/session-lifetime';
import { OidcAuthService } from '../auth/oidc-auth.service';
import { ssoTabState } from '../core/browser-storage';

import { SubmitGuardDirective } from '../shared/forms/submit-guard.directive';
import { DialogFocusDirective } from '../shared/a11y/dialog-focus.directive';
@Component({
  selector: 'app-lock-screen',
  standalone: true,
  imports: [
    SubmitGuardDirective,
    DialogFocusDirective,
    FormsModule,
    TranslateModule,
    RoleLabelPipe,
  ],
  templateUrl: './lock-screen.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './lock-screen.scss',
})
export class LockScreenComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly idle = inject(IdleService);
  private readonly translate = inject(TranslateService);
  private readonly injector = inject(Injector);

  /**
   * This tab signed in through the identity provider: it has no password
   * here, so it unlocks by signing in again at the provider.
   */
  readonly sso = ssoTabState() !== null;

  ngOnInit(): void {
    const provider = this.sso ? this.injector.get(OidcAuthService) : null;
    if (!provider?.reauthenticated()) {
      // The lock is the server's to hold: tell it again if the first call was lost.
      this.idle.ensureServerLock();
      return;
    }
    // Back from the sign-in this lock screen asked for, and only then: the
    // server answers whether that sign-in is later than the lock. Any other
    // answer leaves the screen locked.
    provider.reauthenticated.set(false);
    this.http.post('/auth/session/unlock', {}, { observe: 'response' }).subscribe({
      next: (answer) => {
        if (answer.status === 204) this.idle.unlock();
        else this.idle.ensureServerLock();
      },
      error: () => this.idle.ensureServerLock(),
    });
  }

  /** Sends the holder to the identity provider to sign in again. */
  reauthenticate(): void {
    this.loading.set(true);
    this.injector.get(OidcAuthService).reauthenticate();
  }

  /** Emitted when the user signs out from the lock screen */
  readonly signedOut = output<void>();

  /** Emitted when a different user wants to sign in (complete session clear) */
  readonly switchedUser = output<void>();

  /**
   * D2001: emitted when the server answers 423 — the account is locked after
   * too many wrong passwords (here or at the sign-in) and the server has
   * already revoked this session's tokens. The shell signs out and shows the
   * lockout on the sign-in page.
   */
  readonly lockedOut = output<void>();

  /**
   * Another account signed in from this browser since the screen locked (the
   * refresh minted its token). The shell ends this tab's session locally only.
   */
  readonly accountChanged = output<void>();

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

  /** The raw token; the template pipes it. */
  get userRole(): string {
    const p = this.auth.getUserProfile();
    if (!p?.roles?.length) return '';
    return p.roles[0];
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
      error: (err: HttpErrorResponse) => {
        if (isAccountChanged(err)) {
          this.loading.set(false);
          this.password = '';
          this.idle.release();
          this.accountChanged.emit();
          return;
        }
        this.failWith(this.renewalErrorKey(err));
      },
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
    // The same 409 "superseded" retry as the interceptor: another tab may
    // have rotated the shared refresh cookie a moment earlier.
    return refreshWithSupersededRetry(this.auth).pipe(
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
        // 200: the server checked the password and lifted the session's lock.
        next: () => {
          this.loading.set(false);
          this.password = '';
          this.idle.unlock();
        },
        // Reached only with a token we just confirmed good, so a 401 here
        // really is the wrong password.
        error: (err: HttpErrorResponse) => {
          if (err?.status === 423) {
            this.loading.set(false);
            this.password = '';
            this.idle.release();
            this.lockedOut.emit();
            return;
          }
          this.failWith(this.verifyErrorKey(err));
        },
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
    this.idle.release();
    this.signedOut.emit();
  }

  /**
   * A different person at the workstation wants to sign in.
   * Clears ALL auth state (token, profile, idle lock) so the login page
   * starts completely fresh — no trace of the previous user.
   */
  switchUser(): void {
    this.idle.release();
    this.switchedUser.emit();
  }

  /**
   * QA D2011: Escape on the lock screen does nothing — only the password,
   * "Switch user" or "Sign out" leave it. The dialog idiom routes Escape here.
   */
  keepLocked(): void {
    // Intentionally empty: the session stays locked.
  }

  togglePassword(): void {
    this.showPassword = !this.showPassword;
  }
}
