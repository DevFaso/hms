import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  PatientPortalService,
  PatientConsent,
  AccessLogEntry,
  PortalConsentRequest,
} from '../../services/patient-portal.service';
import { HospitalService, HospitalResponse } from '../../services/hospital.service';
import { ToastService } from '../../core/toast.service';

@Component({
  selector: 'app-my-sharing',
  standalone: true,
  imports: [CommonModule, DatePipe, FormsModule],
  styleUrl: './my-sharing.scss',
  templateUrl: './my-sharing.html',
})
export class MySharingComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);
  private readonly hospitalService = inject(HospitalService);
  private readonly toast = inject(ToastService);

  activeTab = signal<'consents' | 'access-log'>('consents');
  loadingConsents = signal(true);
  loadingLog = signal(false);
  consents = signal<PatientConsent[]>([]);
  accessLog = signal<AccessLogEntry[]>([]);
  revoking = signal(false);
  accessLogLoaded = false;

  /* ── Grant consent modal ──────────────────────────────────────────── */
  showGrantForm = signal(false);
  granting = signal(false);
  hospitals = signal<HospitalResponse[]>([]);
  loadingHospitals = signal(false);
  grantForm = signal<PortalConsentRequest>(this.emptyGrantForm());

  ngOnInit(): void {
    this.portal.getMyConsents().subscribe({
      next: (c) => {
        this.consents.set(c);
        this.loadingConsents.set(false);
      },
      error: () => this.loadingConsents.set(false),
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

  /* ── Grant consent ────────────────────────────────────────────────── */

  openGrantForm(): void {
    this.grantForm.set(this.emptyGrantForm());
    this.showGrantForm.set(true);
    if (!this.hospitals().length) {
      this.loadingHospitals.set(true);
      this.hospitalService.list().subscribe({
        next: (h) => {
          this.hospitals.set(h);
          this.loadingHospitals.set(false);
        },
        error: () => this.loadingHospitals.set(false),
      });
    }
  }

  closeGrantForm(): void {
    this.showGrantForm.set(false);
  }

  updateGrantField<K extends keyof PortalConsentRequest>(
    field: K,
    value: PortalConsentRequest[K],
  ): void {
    this.grantForm.update((f) => ({ ...f, [field]: value }));
  }

  isGrantValid(): boolean {
    const f = this.grantForm();
    return !!(f.fromHospitalId && f.toHospitalId && f.purpose && f.consentExpiration);
  }

  confirmGrant(): void {
    if (!this.isGrantValid() || this.granting()) return;
    this.granting.set(true);
    this.portal.grantConsent(this.grantForm()).subscribe({
      next: (saved) => {
        this.consents.update((list) => [saved, ...list]);
        this.toast.success('Consent granted successfully');
        this.granting.set(false);
        this.closeGrantForm();
      },
      error: () => {
        this.toast.error('Failed to grant consent. Please try again.');
        this.granting.set(false);
      },
    });
  }

  private emptyGrantForm(): PortalConsentRequest {
    return { fromHospitalId: '', toHospitalId: '', purpose: '', consentExpiration: '' };
  }
}
