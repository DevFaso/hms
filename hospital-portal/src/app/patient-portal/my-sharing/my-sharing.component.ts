import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import {
  PatientPortalService,
  PatientConsent,
  AccessLogEntry,
  DisclosureAccounting,
} from '../../services/patient-portal.service';
import { ToastService } from '../../core/toast.service';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';

@Component({
  selector: 'app-my-sharing',
  standalone: true,
  imports: [CommonModule, DatePipe, FormsModule, EnumLabelPipe, TranslateModule],
  templateUrl: './my-sharing.component.html',
  styleUrls: ['./my-sharing.component.scss', '../patient-portal-pages.scss'],
})
export class MySharingComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  activeTab = signal<'consents' | 'access-log'>('consents');
  loadingConsents = signal(true);
  loadingLog = signal(false);
  /**
   * Distinguished from "no rows" on purpose: an empty access log reads as
   * "nobody has looked at your records", which is not a safe thing to tell
   * a patient when what actually happened is that the request failed.
   */
  logFailed = signal(false);
  consents = signal<PatientConsent[]>([]);
  accessLog = signal<AccessLogEntry[]>([]);
  /**
   * Per-category counts across the whole history, not just the loaded page.
   * Leads the tab so the two rows that matter are visible without scrolling
   * through months of routine chart opens.
   */
  disclosureSummary = signal<DisclosureAccounting | null>(null);
  revoking = signal(false);
  accessLogLoaded = false;

  // Share form state
  showShareForm = signal(false);
  sharing = signal(false);
  shareHospitalId = '';
  sharePurpose = '';
  shareExpiration = '';
  private patientHospitalId = '';

  ngOnInit(): void {
    this.portal.getMyConsents().subscribe({
      next: (c) => {
        this.consents.set(c);
        this.loadingConsents.set(false);
      },
      error: () => this.loadingConsents.set(false),
    });
    this.portal.getMyProfile().subscribe({
      next: (profile) => {
        this.patientHospitalId = profile.hospitalId ?? '';
      },
    });
  }

  switchToAccessLog(): void {
    this.activeTab.set('access-log');
    if (!this.accessLogLoaded) {
      this.loadingLog.set(true);
      this.logFailed.set(false);
      this.portal.getMyDisclosures().subscribe({
        next: (accounting) => {
          this.disclosureSummary.set(accounting);
          this.accessLog.set(accounting.entries);
          this.loadingLog.set(false);
          this.accessLogLoaded = true;
        },
        error: () => {
          this.logFailed.set(true);
          this.loadingLog.set(false);
          // Not marked loaded: a failure should retry on the next visit
          // rather than cache itself as an answer.
        },
      });
    }
  }

  /** Retry after a failed load. */
  retryAccessLog(): void {
    this.accessLogLoaded = false;
    this.switchToAccessLog();
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

  revokeConsent(c: PatientConsent): void {
    this.revoking.set(true);
    this.portal.revokeConsent(c.fromHospitalId, c.toHospitalId).subscribe({
      next: () => {
        this.consents.update((list) =>
          list.map((item) => (item.id === c.id ? { ...item, status: 'REVOKED' } : item)),
        );
        this.toast.success(this.translate.instant('PORTAL.SHARING.CONSENT_REVOKED'));
        this.revoking.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('PORTAL.SHARING.CONSENT_REVOKE_FAILED'));
        this.revoking.set(false);
      },
    });
  }

  openShareForm(): void {
    this.shareHospitalId = '';
    this.sharePurpose = '';
    this.shareExpiration = '';
    this.showShareForm.set(true);
  }

  cancelShare(): void {
    this.showShareForm.set(false);
  }

  submitShare(): void {
    if (!this.shareHospitalId.trim()) return;
    this.sharing.set(true);
    this.portal
      .grantConsent({
        fromHospitalId: this.patientHospitalId,
        toHospitalId: this.shareHospitalId.trim(),
        purpose: this.sharePurpose.trim() || 'Treatment',
        consentExpiration: this.shareExpiration || '',
      })
      .subscribe({
        next: (consent) => {
          this.consents.update((list) => [consent, ...list]);
          this.toast.success(this.translate.instant('PORTAL.SHARING.CONSENT_GRANTED'));
          this.sharing.set(false);
          this.showShareForm.set(false);
        },
        error: () => {
          this.toast.error(this.translate.instant('PORTAL.SHARING.CONSENT_GRANT_FAILED'));
          this.sharing.set(false);
        },
      });
  }
}
