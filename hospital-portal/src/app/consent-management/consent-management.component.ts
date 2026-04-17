import { Component, inject, OnInit, OnDestroy, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { Subject, Subscription } from 'rxjs';
import { debounceTime, distinctUntilChanged, switchMap } from 'rxjs/operators';
import {
  RecordSharingService,
  PatientConsentResponse,
  ConsentGrantRequest,
} from '../services/record-sharing.service';
import { PatientService, PatientResponse } from '../services/patient.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
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
export class ConsentManagementComponent implements OnInit, OnDestroy {
  private readonly sharingService = inject(RecordSharingService);
  private readonly patientService = inject(PatientService);
  private readonly hospitalService = inject(HospitalService);
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

  // ── Patient picker ──────────────────────────────────────
  patientQuery = signal('');
  patientSuggestions = signal<PatientResponse[]>([]);
  patientDropdownOpen = signal(false);
  patientSearchLoading = signal(false);
  selectedPatient = signal<PatientResponse | null>(null);
  private readonly patientSearch$ = new Subject<string>();
  private patientSearchSub?: Subscription;

  // ── Hospital dropdowns ──────────────────────────────────
  hospitals = signal<HospitalResponse[]>([]);
  hospitalsLoading = signal(false);
  /** The logged-in user's hospital — auto-set as "To Hospital" */
  currentHospital = signal<HospitalResponse | null>(null);

  /** From Hospital options: all hospitals except the locked "To Hospital" */
  fromHospitalOptions = computed(() => {
    const current = this.currentHospital();
    if (!current) return this.hospitals();
    return this.hospitals().filter((h) => h.id !== current.id);
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
    this.loadHospitals();
    this.initPatientSearch();
  }

  ngOnDestroy(): void {
    this.patientSearchSub?.unsubscribe();
  }

  // ── Patient search ──────────────────────────────────────
  private initPatientSearch(): void {
    this.patientSearchSub = this.patientSearch$
      .pipe(
        debounceTime(220),
        distinctUntilChanged(),
        switchMap((q) => {
          this.patientSearchLoading.set(true);
          return this.patientService.list(undefined, q || undefined);
        }),
      )
      .subscribe({
        next: (list) => {
          this.patientSuggestions.set(list.slice(0, 8));
          this.patientDropdownOpen.set(list.length > 0);
          this.patientSearchLoading.set(false);
        },
        error: () => this.patientSearchLoading.set(false),
      });
  }

  onPatientQueryChange(q: string): void {
    this.patientQuery.set(q);
    if (q.length >= 1) this.patientSearch$.next(q);
    else {
      this.patientSuggestions.set([]);
      this.patientDropdownOpen.set(false);
    }
  }

  selectPatient(p: PatientResponse): void {
    this.selectedPatient.set(p);
    this.grantForm.patientId = p.id;
    this.patientDropdownOpen.set(false);
    this.patientQuery.set('');
  }

  clearPatient(): void {
    this.selectedPatient.set(null);
    this.grantForm.patientId = '';
    this.patientQuery.set('');
  }

  patientInitials(p: PatientResponse): string {
    return ((p.firstName?.[0] ?? '') + (p.lastName?.[0] ?? '')).toUpperCase() || '?';
  }

  // ── Hospital loading ────────────────────────────────────
  private loadHospitals(): void {
    this.hospitalsLoading.set(true);
    // Load the user's own hospital (locked as "To Hospital")
    this.hospitalService.getMyHospitalAsResponse().subscribe({
      next: (myHosp) => {
        this.currentHospital.set(myHosp);
        // All roles load the full hospital list for the "From" dropdown
        this.hospitalService.list().subscribe({
          next: (h) => {
            this.hospitals.set(h);
            this.hospitalsLoading.set(false);
          },
          error: () => {
            // Fallback: at least show the user's own hospital
            this.hospitals.set([myHosp]);
            this.hospitalsLoading.set(false);
          },
        });
      },
      error: () => this.hospitalsLoading.set(false),
    });
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
    const toId = this.currentHospital()?.id ?? '';
    this.grantForm = {
      patientId: '',
      fromHospitalId: '',
      toHospitalId: toId,
      purpose: '',
      consentExpiration: '',
      consentType: 'TREATMENT',
      scope: '',
    };
    this.selectedPatient.set(null);
    this.patientQuery.set('');
    this.patientSuggestions.set([]);
    this.patientDropdownOpen.set(false);
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
