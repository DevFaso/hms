import { Component, inject, OnInit, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import {
  RecordSharingService,
  PatientConsentResponse,
  ConsentGrantRequest,
} from '../services/record-sharing.service';
import { ToastService } from '../core/toast.service';

type ConsentTypeValue =
  | 'TREATMENT'
  | 'RESEARCH'
  | 'BILLING'
  | 'EMERGENCY'
  | 'REFERRAL'
  | 'ALL_PURPOSES';

const CONSENT_TYPES: ConsentTypeValue[] = [
  'TREATMENT',
  'RESEARCH',
  'BILLING',
  'EMERGENCY',
  'REFERRAL',
  'ALL_PURPOSES',
];

@Component({
  selector: 'app-consent-management',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './consent-management.component.html',
  styleUrl: './consent-management.component.scss',
})
export class ConsentManagementComponent implements OnInit {
  private readonly sharingService = inject(RecordSharingService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  readonly consentTypes = CONSENT_TYPES;
  readonly pageSize = 20;

  consents = signal<PatientConsentResponse[]>([]);
  loading = signal(true);
  loadError = signal<string | null>(null);
  submitting = signal(false);
  showGrantForm = signal(false);
  filterActive = signal<'' | 'true' | 'false'>('');

  currentPage = signal(0);
  totalElements = signal(0);
  totalPages = signal(0);

  filteredConsents = computed(() => {
    const fa = this.filterActive();
    if (!fa) return this.consents();
    const active = fa === 'true';
    return this.consents().filter((c) => c.consentGiven === active);
  });

  grantForm: ConsentGrantRequest = {
    patientId: '',
    fromHospitalId: '',
    toHospitalId: '',
    purpose: '',
    consentExpiration: '',
    consentType: 'TREATMENT',
    scope: '',
  };

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.sharingService.listConsents({ page: this.currentPage(), size: this.pageSize }).subscribe({
      next: (res) => {
        this.consents.set(res.content ?? []);
        this.totalElements.set(res.totalElements ?? 0);
        this.totalPages.set(res.totalPages ?? 0);
        this.loading.set(false);
      },
      error: () => {
        this.loadError.set(this.translate.instant('CONSENT.ERRORS.LOAD_FAILED'));
        this.toast.error('CONSENT.ERRORS.LOAD_FAILED');
        this.loading.set(false);
      },
    });
  }

  prevPage(): void {
    if (this.currentPage() > 0) {
      this.currentPage.update((p) => p - 1);
      this.load();
    }
  }

  nextPage(): void {
    if (this.currentPage() < this.totalPages() - 1) {
      this.currentPage.update((p) => p + 1);
      this.load();
    }
  }

  openGrantForm(): void {
    this.grantForm = {
      patientId: '',
      fromHospitalId: '',
      toHospitalId: '',
      purpose: '',
      consentExpiration: '',
      consentType: 'TREATMENT',
      scope: '',
    };
    this.showGrantForm.set(true);
  }

  cancelGrant(): void {
    this.showGrantForm.set(false);
  }

  submitGrant(): void {
    if (
      !this.grantForm.patientId ||
      !this.grantForm.fromHospitalId ||
      !this.grantForm.toHospitalId
    ) {
      this.toast.error('CONSENT.ERRORS.REQUIRED_FIELDS');
      return;
    }
    this.submitting.set(true);
    const req: ConsentGrantRequest = {
      ...this.grantForm,
      purpose: this.grantForm.purpose || undefined,
      consentExpiration: this.grantForm.consentExpiration || undefined,
      scope: this.grantForm.scope || undefined,
    };
    this.sharingService.grantConsent(req).subscribe({
      next: () => {
        this.toast.success('CONSENT.GRANTED');
        this.showGrantForm.set(false);
        this.submitting.set(false);
        this.currentPage.set(0);
        this.load();
      },
      error: () => {
        this.toast.error('CONSENT.ERRORS.GRANT_FAILED');
        this.submitting.set(false);
      },
    });
  }

  revoke(consent: PatientConsentResponse): void {
    if (!confirm(this.translate.instant('CONSENT.MANAGEMENT.REVOKE_CONFIRM'))) return;
    this.sharingService
      .revokeConsent(consent.patient.id, consent.fromHospital.id, consent.toHospital.id)
      .subscribe({
        next: () => {
          this.toast.success('CONSENT.REVOKED');
          this.load();
        },
        error: () => this.toast.error('CONSENT.ERRORS.REVOKE_FAILED'),
      });
  }

  consentTypeLabel(type: string | null): string {
    if (!type) return '--';
    return type.replace('_', ' ');
  }

  isActive(c: PatientConsentResponse): boolean {
    if (!c.consentGiven) return false;
    if (!c.consentExpiration) return true;
    return new Date(c.consentExpiration) > new Date();
  }
}
