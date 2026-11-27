import { Location } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import {
  Component,
  ElementRef,
  OnInit,
  inject,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { SubmitGuardDirective } from '../shared/forms/submit-guard.directive';
import { linkToken } from '../shared/account-link/link-token';
import { refuseFields } from '../shared/forms/invalid-fields';

/** QA D1306 (round 28): the resend form's one field. */
const EMAIL_FIELD_IDS = ['verify-email'];

/** What the page shows: the link is being checked, worked, was refused, or was incomplete. */
export type VerifyEmailState = 'verifying' | 'verified' | 'failed' | 'missing';

/**
 * Landing page of the e-mail activation link,
 * `${app.frontend.base-url}/verify#token=…`, which
 * `UserServiceImpl#sendActivationEmail` and `POST /auth/resend-verification`
 * build. Until this route existed the `**` fallback sent the link to /login
 * and the click did nothing (Standing platform debt, from the #569 review).
 *
 * The link carries one single-use token in its fragment and no address. The page
 * reads it, clears the address bar and posts it to `POST /auth/verify-email`
 * (in the body, never in a request URL), which activates the account and
 * its patient assignments, and on a refused or incomplete link offers a new
 * one for the address the reader types. A link sent before carried the address
 * and the token in its query string and still works until it expires.
 * The resend answer is deliberately the same
 * whether or not the address exists: the backend does not disclose it, and
 * neither does this page.
 */
@Component({
  selector: 'app-verify-email',
  standalone: true,
  imports: [SubmitGuardDirective, FormsModule, TranslateModule],
  templateUrl: './verify-email.html',
  // The same public card as the password-reset landing page, on purpose: the
  // two links arrive in the same kind of e-mail and should land on the same
  // kind of page.
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrls: ['../reset-password/reset-password.scss'],
})
export class VerifyEmailComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly location = inject(Location);
  private readonly translate = inject(TranslateService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly state = signal<VerifyEmailState>('verifying');
  /** Shown after a resend, whatever the backend answered (no enumeration). */
  readonly resendSent = signal('');
  readonly resendError = signal('');
  readonly currentYear = new Date().getFullYear();

  resendEmail = '';
  resendLoading = false;

  ngOnInit(): void {
    const email = this.route.snapshot.queryParamMap.get('email') ?? '';
    const token = linkToken(this.route.snapshot);
    this.resendEmail = email;
    // The link's token leaves the address bar (and the history) once read.
    this.location.replaceState('/verify');
    if (!token) {
      this.state.set('missing');
      return;
    }
    this.http
      .post<{ message?: string; success?: boolean }>(
        '/auth/verify-email',
        email ? { email, token } : { token },
      )
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
      // QA D1306 (round 28): the field is marked, not only the banner.
      const message = this.translate.instant('LOGIN.ENTER_EMAIL');
      refuseFields(this.host.nativeElement, EMAIL_FIELD_IDS, EMAIL_FIELD_IDS, message);
      this.resendError.set(message);
      return;
    }
    refuseFields(this.host.nativeElement, [], EMAIL_FIELD_IDS, '');
    this.resendLoading = true;
    const done = (): void => {
      this.resendLoading = false;
      this.resendSent.set(this.translate.instant('VERIFY.RESENT'));
    };
    this.http
      .post<{ message?: string }>('/auth/resend-verification', { email })
      .subscribe({ next: done, error: done });
  }

  goToLogin(): void {
    void this.router.navigate(['/login']);
  }
}
