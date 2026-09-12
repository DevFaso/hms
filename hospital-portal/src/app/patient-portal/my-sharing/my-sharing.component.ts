import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import {
  PatientPortalService,
  AccessLogEntry,
  DisclosureAccounting,
  RecordSharingOptOut,
} from '../../services/patient-portal.service';
import { ToastService } from '../../core/toast.service';

/**
 * "Who accessed my record" (E9 #66, decision D7).
 *
 * <p>The consent-grant form that used to live here is gone: a patient's record
 * follows them on the treatment relationship, so there is nothing to grant.
 * What a patient controls is the opt-out (V157): close the door to other
 * hospitals, keep their own hospital's access, and see every access and
 * disclosure below, emergency ones first.
 */
@Component({
  selector: 'app-my-sharing',
  standalone: true,
  imports: [CommonModule, DatePipe, FormsModule, TranslateModule],
  templateUrl: './my-sharing.component.html',
  styleUrls: ['./my-sharing.component.scss', '../patient-portal-pages.scss'],
})
export class MySharingComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  /* ── Opt-out ── */
  optOut = signal<RecordSharingOptOut | null>(null);
  optOutLoading = signal(true);
  /** Load failure is shown as such, never as "sharing is on". */
  optOutFailed = signal(false);
  optOutSaving = signal(false);
  showOptOutForm = signal(false);
  optOutReason = '';
  private patientId = '';

  /* ── Access log ── */
  loadingLog = signal(true);
  /**
   * Distinguished from "no rows" on purpose: an empty access log reads as
   * "nobody has looked at your records", which is not a safe thing to tell
   * a patient when what actually happened is that the request failed.
   */
  logFailed = signal(false);
  accessLog = signal<AccessLogEntry[]>([]);
  /**
   * Per-category counts across the whole history, not just the loaded page.
   * Leads the page so the two rows that matter are visible without scrolling
   * through months of routine chart opens.
   */
  disclosureSummary = signal<DisclosureAccounting | null>(null);

  ngOnInit(): void {
    this.loadAccessLog();
    this.portal.getMyProfile().subscribe({
      next: (profile) => {
        this.patientId = profile.id ?? '';
        this.loadOptOut();
      },
      error: () => {
        this.optOutFailed.set(true);
        this.optOutLoading.set(false);
      },
    });
  }

  optedOut(): boolean {
    return this.optOut()?.inForce === true;
  }

  loadAccessLog(): void {
    this.loadingLog.set(true);
    this.logFailed.set(false);
    this.portal.getMyDisclosures().subscribe({
      next: (accounting) => {
        this.disclosureSummary.set(accounting);
        this.accessLog.set(accounting.entries);
        this.loadingLog.set(false);
      },
      error: () => {
        this.logFailed.set(true);
        this.loadingLog.set(false);
      },
    });
  }

  /** Retry after a failed load. */
  retryAccessLog(): void {
    this.loadAccessLog();
  }

  /**
   * i18n key for a category, e.g. EMERGENCY_ACCESS -> the "someone opened
   * your chart in an emergency" wording. Unknown or absent categories fall
   * back to a neutral label rather than rendering the raw enum name.
   */
  categoryLabelKey(entry: AccessLogEntry): string {
    return entry.category
      ? `PORTAL.SHARING.CATEGORY.${entry.category}`
      : 'PORTAL.SHARING.CATEGORY.UNKNOWN';
  }

  /** Emergency overrides across the whole history, or 0 if unknown. */
  emergencyCount(): number {
    return this.disclosureSummary()?.countsByCategory?.EMERGENCY_ACCESS ?? 0;
  }

  /** Times the record went outside the treating team, across the whole history. */
  externalCount(): number {
    return this.disclosureSummary()?.externalDisclosures ?? 0;
  }

  openOptOutForm(): void {
    this.optOutReason = '';
    this.showOptOutForm.set(true);
  }

  cancelOptOut(): void {
    this.showOptOutForm.set(false);
  }

  confirmOptOut(): void {
    if (!this.patientId || this.optOutSaving()) return;
    this.optOutSaving.set(true);
    this.portal.optOutOfSharing(this.patientId, this.optOutReason.trim() || null).subscribe({
      next: (state) => {
        this.optOut.set(state);
        this.showOptOutForm.set(false);
        this.optOutSaving.set(false);
        this.toast.success(this.translate.instant('PORTAL.SHARING.OPT_OUT.SAVED_ON'));
      },
      error: () => {
        this.optOutSaving.set(false);
        this.toast.error(this.translate.instant('PORTAL.SHARING.OPT_OUT.FAILED'));
      },
    });
  }

  revokeOptOut(): void {
    if (!this.patientId || this.optOutSaving()) return;
    this.optOutSaving.set(true);
    this.portal.revokeOptOut(this.patientId).subscribe({
      next: (state) => {
        this.optOut.set(state);
        this.optOutSaving.set(false);
        this.toast.success(this.translate.instant('PORTAL.SHARING.OPT_OUT.SAVED_OFF'));
      },
      error: () => {
        this.optOutSaving.set(false);
        this.toast.error(this.translate.instant('PORTAL.SHARING.OPT_OUT.FAILED'));
      },
    });
  }

  private loadOptOut(): void {
    if (!this.patientId) {
      this.optOutFailed.set(true);
      this.optOutLoading.set(false);
      return;
    }
    this.optOutLoading.set(true);
    this.optOutFailed.set(false);
    this.portal.getMyOptOut(this.patientId).subscribe({
      next: (state) => {
        this.optOut.set(state);
        this.optOutLoading.set(false);
      },
      error: () => {
        this.optOutFailed.set(true);
        this.optOutLoading.set(false);
      },
    });
  }
}
