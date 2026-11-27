import { isPlatformBrowser, Location } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import {
  Component,
  inject,
  ElementRef,
  OnInit,
  PLATFORM_ID,
  ChangeDetectionStrategy,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { linkToken } from '../shared/account-link/link-token';
import { refuseFields } from '../shared/forms/invalid-fields';
import { SubmitGuardDirective } from '../shared/forms/submit-guard.directive';

@Component({
  selector: 'app-reset-password',
  standalone: true,
  imports: [SubmitGuardDirective, FormsModule, RouterModule, TranslateModule],
  templateUrl: './reset-password.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrls: ['./reset-password.scss'],
})
export class ResetPasswordComponent implements OnInit {
  newPassword = '';
  confirmPassword = '';
  error = '';
  /** The refusal is shown under its field: the banner does not repeat it. */
  errorOnField = false;
  success = '';
  loading = false;
  showNewPassword = false;
  showConfirmPassword = false;
  currentYear = new Date().getFullYear();

  private token = '';
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly passwordFields = ['newPassword', 'confirmPassword'];
  private readonly http = inject(HttpClient);
  private readonly translate = inject(TranslateService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly location = inject(Location);
  private readonly platformId = inject(PLATFORM_ID);
  private readonly isBrowser = isPlatformBrowser(this.platformId);

  ngOnInit(): void {
    // The link carries one single-use token in its fragment (`/reset-password#token=…`); it is kept
    // in memory for the POST and leaves the address bar (and the history) once read.
    this.token = linkToken(this.route.snapshot);
    this.location.replaceState('/reset-password');
    // A missing URL token renders the translated invalid-link state, not a form.
    // There is no editable token field to mark or focus.
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
    this.errorOnField = false;
    refuseFields(this.host.nativeElement, [], this.passwordFields);

    if (!this.newPassword || !this.confirmPassword) {
      this.error = this.translate.instant('RESET.ERR_REQUIRED');
      const missing = this.passwordFields.filter((id) =>
        id === 'newPassword' ? !this.newPassword : !this.confirmPassword,
      );
      refuseFields(this.host.nativeElement, missing, this.passwordFields, this.error);
      this.errorOnField = true;
      return;
    }
    if (this.newPassword.length < 8) {
      this.error = this.translate.instant('RESET.ERR_MIN_LENGTH');
      refuseFields(this.host.nativeElement, ['newPassword'], this.passwordFields, this.error);
      this.errorOnField = true;
      return;
    }
    if (this.newPassword !== this.confirmPassword) {
      this.error = this.translate.instant('RESET.ERR_MISMATCH');
      refuseFields(this.host.nativeElement, ['confirmPassword'], this.passwordFields, this.error);
      this.errorOnField = true;
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
          this.success = this.translate.instant('RESET.SUCCESS_MSG');
        },
        error: (err) => {
          this.loading = false;
          // Only a 400 confirms a refusal. A transport/server failure leaves the
          // outcome unknown, including when the reset committed before a timeout.
          this.error =
            err?.status === 400
              ? err?.error?.message || this.translate.instant('RESET.ERR_LINK_INVALID')
              : this.translate.instant('RESET.ERR_UNAVAILABLE');
          // A refusal of the password itself (too short, too common, built on
          // the account's name) is shown under the field it is about.
          const onPassword = err?.status === 400 ? err?.error?.fieldErrors?.newPassword : null;
          if (typeof onPassword === 'string' && onPassword) {
            this.error = onPassword;
            this.errorOnField = true;
            refuseFields(this.host.nativeElement, ['newPassword'], this.passwordFields, onPassword);
          }
        },
      });
  }

  goToLogin(): void {
    void this.router.navigate(['/login']);
  }
}
