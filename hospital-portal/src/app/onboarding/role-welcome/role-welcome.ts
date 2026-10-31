import { isPlatformBrowser } from '@angular/common';
import {
  Component,
  ElementRef,
  inject,
  OnInit,
  PLATFORM_ID,
  QueryList,
  ViewChildren,
  ChangeDetectionStrategy,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import {
  AssignmentPublicService,
  AssignmentPublicView,
} from '../../services/assignment-public.service';

import { RoleLabelPipe } from '../../shared/pipes/role-label.pipe';

type PageState =
  'loading' | 'loaded' | 'already-verified' | 'success' | 'not-found' | 'error' | 'by-phone';

@Component({
  selector: 'app-role-welcome',
  standalone: true,
  imports: [FormsModule, RouterModule, TranslateModule, RoleLabelPipe],
  templateUrl: './role-welcome.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrls: ['./role-welcome.scss'],
})
export class RoleWelcomeComponent implements OnInit {
  /** State machine */
  pageState: PageState = 'loading';

  /** Assignment data from server */
  assignment: AssignmentPublicView | null = null;

  /** Six individual digit values for the code inputs */
  digits: string[] = ['', '', '', '', '', ''];

  /** UI states */
  verifying = false;
  verifyError = '';
  errorMessage = '';

  /** Copy-to-clipboard feedback */
  copiedUsername = false;
  copiedPassword = false;

  @ViewChildren('digitInput') digitInputs!: QueryList<ElementRef<HTMLInputElement>>;

  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly service = inject(AssignmentPublicService);
  private readonly platformId = inject(PLATFORM_ID);
  private readonly isBrowser = isPlatformBrowser(this.platformId);

  ngOnInit(): void {
    if (!this.isBrowser) return;

    const code = this.route.snapshot.queryParamMap.get('assignment');
    if (!code) {
      // QA D1123: no link (an SMS reader, or "I have a code" on the login
      // page): the page takes the phone number and the code instead.
      this.pageState = 'by-phone';
      return;
    }

    this.service.getPublicView(code).subscribe({
      next: (data) => {
        this.assignment = data;
        if (data.confirmationVerified) {
          this.pageState = 'already-verified';
        } else {
          this.pageState = 'loaded';
          // Auto-focus first digit after view renders
          setTimeout(() => this.focusDigit(0), 50);
        }
      },
      error: (err) => {
        if (err.status === 404) {
          this.pageState = 'not-found';
          this.errorMessage = 'ONBOARDING.ERR_LINK_INVALID';
        } else {
          this.pageState = 'error';
          this.errorMessage = 'ONBOARDING.ERR_LOAD_FAILED';
        }
      },
    });
  }

  // ─── Digit input helpers ────────────────────────────────────────────────

  onDigitInput(event: Event, index: number): void {
    const input = event.target as HTMLInputElement;
    const value = input.value.replaceAll(/\D/g, '').slice(-1);
    this.digits[index] = value;
    this.verifyError = '';

    if (value && index < 5) {
      this.focusDigit(index + 1);
    }
    if (value && index === 5) {
      // All digits filled — auto-submit
      this.submitCode();
    }
  }

  onDigitKeydown(event: KeyboardEvent, index: number): void {
    if (event.key === 'Backspace' && !this.digits[index] && index > 0) {
      this.digits[index - 1] = '';
      this.focusDigit(index - 1);
    }
    if (event.key === 'ArrowLeft' && index > 0) {
      this.focusDigit(index - 1);
    }
    if (event.key === 'ArrowRight' && index < 5) {
      this.focusDigit(index + 1);
    }
  }

  onDigitPaste(event: ClipboardEvent): void {
    event.preventDefault();
    const text = event.clipboardData?.getData('text') ?? '';
    const cleaned = text.replaceAll(/\D/g, '').slice(0, 6);
    cleaned.split('').forEach((ch, i) => {
      if (i < 6) this.digits[i] = ch;
    });
    if (cleaned.length === 6) {
      this.submitCode();
    } else {
      this.focusDigit(cleaned.length);
    }
  }

  private focusDigit(index: number): void {
    const inputs = this.digitInputs?.toArray();
    if (inputs?.[index]) {
      inputs[index].nativeElement.focus();
    }
  }

  get confirmationCode(): string {
    return this.digits.join('');
  }

  get isCodeComplete(): boolean {
    return this.digits.every((d) => d.length === 1);
  }

  // ─── Copy to clipboard ──────────────────────────────────────────────────

  copyToClipboard(text: string, field: 'username' | 'password'): void {
    if (!this.isBrowser || !navigator.clipboard) return;
    navigator.clipboard.writeText(text).then(() => {
      if (field === 'username') {
        this.copiedUsername = true;
        setTimeout(() => (this.copiedUsername = false), 2000);
      } else {
        this.copiedPassword = true;
        setTimeout(() => (this.copiedPassword = false), 2000);
      }
    });
  }

  // ─── Submission ──────────────────────────────────────────────────────────

  submitCode(): void {
    if (!this.assignment || this.verifying || !this.isCodeComplete) return;

    this.verifying = true;
    this.verifyError = '';

    this.service.verifyCode(this.assignment.assignmentCode, this.confirmationCode).subscribe({
      next: (data) => {
        this.assignment = data;
        this.verifying = false;
        this.pageState = 'success';
      },
      error: (err) => {
        this.verifying = false;
        this.digits = ['', '', '', '', '', ''];
        setTimeout(() => this.focusDigit(0), 50);

        if (err.status === 400) {
          this.verifyError = 'ONBOARDING.ERR_CODE_INCORRECT';
        } else if (err.status === 404) {
          this.verifyError = 'ONBOARDING.ERR_ASSIGNMENT_NOT_FOUND';
        } else {
          this.verifyError = 'ONBOARDING.ERR_GENERIC';
        }
      },
    });
  }

  // ─── "I have a code": phone + code, no e-mail (QA D1123) ─────────────────

  phone = '';
  phoneCode = '';

  submitByPhone(): void {
    if (this.verifying) return;
    const phone = this.phone.trim();
    const code = this.phoneCode.replaceAll(/\D/g, '');
    if (!phone) {
      this.verifyError = 'ONBOARDING.ERR_PHONE_REQUIRED';
      this.focusField('rw-phone');
      return;
    }
    if (code.length !== 6) {
      this.verifyError = 'ONBOARDING.ERR_CODE_FORMAT';
      this.focusField('rw-phone-code');
      return;
    }
    this.verifying = true;
    this.verifyError = '';
    this.service.verifyByPhone(phone, code).subscribe({
      next: (data) => {
        this.assignment = data;
        this.verifying = false;
        this.pageState = 'success';
      },
      error: (err) => {
        this.verifying = false;
        this.phoneCode = '';
        // The server's own sentence (wrong code, too many tries) is already
        // in the reader's language; the generic key covers a silent failure.
        const serverMessage = err?.status === 400 ? err?.error?.message : null;
        this.verifyError = serverMessage || 'ONBOARDING.ERR_PHONE_CODE_INCORRECT';
        this.focusField('rw-phone-code');
      },
    });
  }

  private focusField(id: string): void {
    if (!this.isBrowser) return;
    setTimeout(() => document.getElementById(id)?.focus());
  }

  goToLogin(): void {
    this.router.navigate(['/login']);
  }
}
