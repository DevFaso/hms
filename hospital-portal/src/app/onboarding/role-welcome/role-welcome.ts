import { isPlatformBrowser } from '@angular/common';
import {
  Component,
  ElementRef,
  inject,
  OnInit,
  PLATFORM_ID,
  QueryList,
  ViewChildren,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';

import {
  AssignmentPublicService,
  AssignmentPublicView,
} from '../../services/assignment-public.service';

type PageState = 'loading' | 'loaded' | 'already-verified' | 'success' | 'not-found' | 'error';

@Component({
  selector: 'app-role-welcome',
  standalone: true,
  imports: [FormsModule, RouterModule],
  templateUrl: './role-welcome.html',
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
      this.pageState = 'not-found';
      this.errorMessage = 'No assignment code was found in the link. Please check your email.';
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
          this.errorMessage = 'This assignment link is invalid or has expired.';
        } else {
          this.pageState = 'error';
          this.errorMessage = 'Unable to load your assignment. Please try again later.';
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
          this.verifyError = 'Incorrect code. Please check your email and try again.';
        } else if (err.status === 404) {
          this.verifyError = 'Assignment not found. Please use the original email link.';
        } else {
          this.verifyError = 'Something went wrong. Please try again.';
        }
      },
    });
  }

  goToLogin(): void {
    this.router.navigate(['/login']);
  }
}
