import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';
import {
  PatientPortalService,
  PatientConsent,
  AccessLogEntry,
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

  activeTab = signal<'consents' | 'access-log'>('consents');
  loadingConsents = signal(true);
  loadingLog = signal(false);
  consents = signal<PatientConsent[]>([]);
  accessLog = signal<AccessLogEntry[]>([]);
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
      this.portal.getMyAccessLog().subscribe({
        next: (log) => {
          this.accessLog.set(log);
          this.loadingLog.set(false);
          this.accessLogLoaded = true;
        },
        error: () => this.loadingLog.set(false),
      });
    }
  }

  revokeConsent(c: PatientConsent): void {
    this.revoking.set(true);
    this.portal.revokeConsent(c.fromHospitalId, c.toHospitalId).subscribe({
      next: () => {
        this.consents.update((list) =>
          list.map((item) => (item.id === c.id ? { ...item, status: 'REVOKED' } : item)),
        );
        this.toast.success('Consent revoked successfully');
        this.revoking.set(false);
      },
      error: () => {
        this.toast.error('Failed to revoke consent');
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
          this.toast.success('PORTAL.SHARING.CONSENT_GRANTED');
          this.sharing.set(false);
          this.showShareForm.set(false);
        },
        error: () => {
          this.toast.error('PORTAL.SHARING.CONSENT_GRANT_FAILED');
          this.sharing.set(false);
        },
      });
  }
}
