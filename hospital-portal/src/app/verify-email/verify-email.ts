import { HttpClient } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

/** What the page shows: the link is being checked, worked, was refused, or was incomplete. */
export type VerifyEmailState = 'verifying' | 'verified' | 'failed' | 'missing';

/**
 * Landing page of the e-mail activation link,
 * `${app.frontend.base-url}/verify?email=…&token=…`, which
 * `UserServiceImpl#sendActivationEmail` and `POST /auth/resend-verification`
 * build. Until this route existed the `**` fallback sent the link to /login
 * and the click did nothing (Standing platform debt, from the #569 review).
 *
 * The page calls `GET /auth/verify-email`, which activates the account and
 * its patient assignments, and on a refused or incomplete link offers a new
 * one from the same address. The resend answer is deliberately the same
 * whether or not the address exists: the backend does not disclose it, and
 * neither does this page.
 */
@Component({
  selector: 'app-verify-email',
  standalone: true,
  imports: [FormsModule, TranslateModule],
  templateUrl: './verify-email.html',
  // The same public card as the password-reset landing page, on purpose: the
  // two links arrive in the same kind of e-mail and should land on the same
  // kind of page.
  styleUrls: ['../reset-password/reset-password.scss'],
})
export class VerifyEmailComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly translate = inject(TranslateService);

  readonly state = signal<VerifyEmailState>('verifying');
  /** Shown after a resend, whatever the backend answered (no enumeration). */
  readonly resendSent = signal('');
  readonly resendError = signal('');
  readonly currentYear = new Date().getFullYear();

  resendEmail = '';
  resendLoading = false;

  ngOnInit(): void {
    const email = this.route.snapshot.queryParamMap.get('email') ?? '';
    const token = this.route.snapshot.queryParamMap.get('token') ?? '';
    this.resendEmail = email;
    if (!email || !token) {
      this.state.set('missing');
      return;
    }
    this.http
      .get<{ message?: string; success?: boolean }>('/auth/verify-email', {
        params: { email, token },
      })
      .subscribe({
        next: () => this.state.set('verified'),
        error: () => this.state.set('failed'),
      });
  }

  resend(): void {
    if (this.resendLoading) return;
    this.resendError.set('');
    const email = this.resendEmail.trim();
    if (!email) {
      this.resendError.set(this.translate.instant('LOGIN.ENTER_EMAIL'));
      return;
    }
    this.resendLoading = true;
    const done = (): void => {
      this.resendLoading = false;
      this.resendSent.set(this.translate.instant('VERIFY.RESENT'));
    };
    this.http
      .post<{ message?: string }>('/auth/resend-verification', null, { params: { email } })
      .subscribe({ next: done, error: done });
  }

  goToLogin(): void {
    void this.router.navigate(['/login']);
  }
}
