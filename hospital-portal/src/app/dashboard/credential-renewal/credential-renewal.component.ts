import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { CredentialRenewal, CredentialingService } from '../../services/credentialing.service';
import { ToastService } from '../../core/toast.service';

/** The subset of a licence alert this dialog needs. */
export interface CredentialRenewalTarget {
  staffId: string;
  staffName: string;
  licenseNumber: string | null;
  licenseExpiryDate: string | null;
}

/**
 * Record a practising-licence renewal (Tier 2 item 40).
 *
 * <p>Its own component rather than more markup inside the 2,600-line
 * dashboard, following the sub-component convention the KPI cards
 * established. The parent owns the row and the button; this owns the dialog,
 * the call and the history.
 *
 * <p>The history is loaded when the dialog opens rather than on demand.
 * "Was this licence let lapse before?" is the question an administrator has
 * while deciding what to type, not one they think to go looking for
 * afterwards.
 */
@Component({
  selector: 'app-credential-renewal',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './credential-renewal.component.html',
  styleUrl: './credential-renewal.component.scss',
})
export class CredentialRenewalComponent {
  /** Null closes the dialog. */
  readonly target = input.required<CredentialRenewalTarget | null>();

  /** Emitted after a successful renewal so the parent can reload. */
  readonly renewed = output<void>();
  readonly closed = output<void>();

  protected readonly expiryDate = signal('');
  protected readonly licenseNumber = signal('');
  protected readonly issuingAuthority = signal('');
  protected readonly note = signal('');

  protected readonly saving = signal(false);
  protected readonly history = signal<CredentialRenewal[]>([]);
  protected readonly historyLoading = signal(false);
  protected readonly historyFailed = signal(false);

  /**
   * The expiry is the only required field, so it is the only thing that can
   * block the save. Everything else is deliberately optional.
   */

  private readonly credentialing = inject(CredentialingService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  constructor() {
    effect(() => {
      const t = this.target();
      if (!t) return;
      // Reset per open. Carrying the previous staff member's typed values
      // into the next dialog is how the wrong licence gets renewed.
      this.expiryDate.set('');
      this.licenseNumber.set('');
      this.issuingAuthority.set('');
      this.note.set('');
      this.saving.set(false);
      this.loadHistory(t.staffId);
    });
  }

  protected close(): void {
    this.closed.emit();
  }

  protected save(): void {
    const t = this.target();
    // No expiry check: an expiry was mandatory until V145, and credentialing
    // here is a diploma on file, which has no end date.
    if (!t || this.saving()) return;

    this.saving.set(true);
    this.credentialing
      .recordRenewal(t.staffId, {
        // Empty means "does not expire", which the backend stores as null —
        // a positive statement, not missing data. Sending "" would fail date
        // binding rather than mean anything.
        expiryDate: this.expiryDate().trim() || null,
        licenseNumber: this.licenseNumber().trim() || undefined,
        issuingAuthority: this.issuingAuthority().trim() || undefined,
        note: this.note().trim() || undefined,
      })
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.toast.success(this.translate.instant('CREDENTIALING.TOAST.RENEWED'));
          this.renewed.emit();
          this.close();
        },
        error: (err: unknown) => {
          // "A practitioner cannot record their own credential renewal" tells
          // an administrator to fetch a colleague; a generic failure does not.
          this.saving.set(false);
          const message = this.extractMessage(err);
          this.toast.error(message || this.translate.instant('CREDENTIALING.TOAST.RENEW_FAILED'));
        },
      });
  }

  private loadHistory(staffId: string): void {
    this.historyLoading.set(true);
    this.historyFailed.set(false);
    this.credentialing.history(staffId).subscribe({
      next: (rows) => {
        this.history.set(rows ?? []);
        this.historyLoading.set(false);
      },
      error: () => {
        // An explicit failure state, not an empty list: "no history" and
        // "we could not load the history" mean opposite things to somebody
        // deciding whether this licence has lapsed before.
        this.history.set([]);
        this.historyLoading.set(false);
        this.historyFailed.set(true);
      },
    });
  }

  private extractMessage(err: unknown): string | null {
    if (!err || typeof err !== 'object') return null;
    const body = (err as { error?: { message?: string } }).error;
    return body?.message ?? null;
  }
}
