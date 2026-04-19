import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';

import { MfaService } from '../auth/mfa.service';

/**
 * T-32: MFA Enrollment page.
 * Shown when a user whose role requires MFA has not yet enrolled.
 * Flow: Enroll → show QR/secret → user enters first TOTP code → verify → show backup codes → done.
 */
@Component({
  selector: 'app-mfa-enroll',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './mfa-enroll.html',
  styleUrls: ['./mfa-enroll.scss'],
})
export class MfaEnrollComponent {
  private readonly mfaService = inject(MfaService);
  private readonly router = inject(Router);

  /** Wizard step: 'start' | 'qr' | 'verify' | 'backup' */
  step = signal<'start' | 'qr' | 'verify' | 'backup'>('start');

  loading = signal(false);
  error = signal('');

  secret = '';
  otpauthUri = '';
  backupCodes: string[] = [];

  /** 6-digit TOTP code entered by user */
  totpCode = '';

  /** Called from the mfaToken (login) or mfaEnrolled=false redirect */
  mfaToken = ''; // stored from login response

  startEnrollment(): void {
    this.loading.set(true);
    this.error.set('');
    this.mfaService.enroll().subscribe({
      next: (res) => {
        this.secret = res.secret;
        this.otpauthUri = res.otpauthUri;
        this.backupCodes = res.backupCodes;
        this.step.set('qr');
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? 'Failed to start enrollment.');
        this.loading.set(false);
      },
    });
  }

  proceedToVerify(): void {
    this.step.set('verify');
  }

  submitVerification(): void {
    if (!this.totpCode || this.totpCode.length < 6) {
      this.error.set('Please enter a valid 6-digit code.');
      return;
    }
    this.loading.set(true);
    this.error.set('');
    this.mfaService.verifyEnrollment(this.totpCode).subscribe({
      next: () => {
        this.step.set('backup');
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? 'Invalid code. Please try again.');
        this.loading.set(false);
      },
    });
  }

  finish(): void {
    this.router.navigateByUrl('/login');
  }

  /** Copy backup codes to clipboard */
  copyBackupCodes(): void {
    const text = this.backupCodes.join('\n');
    navigator.clipboard.writeText(text).catch(() => {
      /* ignore clipboard errors */
    });
  }
}
