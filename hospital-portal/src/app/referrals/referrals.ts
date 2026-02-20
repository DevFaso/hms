import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, switchMap } from 'rxjs/operators';
import { ReferralService, ReferralResponse, ReferralRequest } from '../services/referral.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { PatientService, PatientResponse } from '../services/patient.service';
import { ToastService } from '../core/toast.service';

@Component({
  selector: 'app-referrals',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './referrals.html',
  styleUrl: './referrals.scss',
})
export class ReferralsComponent implements OnInit {
  private readonly referralService = inject(ReferralService);
  private readonly hospitalService = inject(HospitalService);
  private readonly patientService = inject(PatientService);
  private readonly toast = inject(ToastService);

  referrals = signal<ReferralResponse[]>([]);
  filtered = signal<ReferralResponse[]>([]);
  loading = signal(true);
  searchTerm = '';
  activeTab = signal<'all' | 'pending' | 'active' | 'completed'>('all');
  selectedReferral = signal<ReferralResponse | null>(null);

  hospitals = signal<HospitalResponse[]>([]);

  // Patient picker
  patientQuery = signal('');
  patientSuggestions = signal<PatientResponse[]>([]);
  patientDropdownOpen = signal(false);
  patientSearchLoading = signal(false);
  selectedPatient = signal<PatientResponse | null>(null);
  private readonly patientSearch$ = new Subject<string>();

  /* ── CRUD signals ── */
  showModal = signal(false);
  editing = signal(false);
  saving = signal(false);
  form: ReferralRequest = this.emptyForm();

  showDeleteConfirm = signal(false);
  deletingRef = signal<ReferralResponse | null>(null);
  deleting = signal(false);

  urgencies = ['ROUTINE', 'URGENT', 'EMERGENT', 'STAT'];

  ngOnInit(): void {
    this.load();
    this.hospitalService.list().subscribe((h) => this.hospitals.set(h ?? []));
    this.initPatientSearch();
  }

  emptyForm(): ReferralRequest {
    return {
      patientId: '',
      hospitalId: '',
      targetSpecialty: '',
      referralReason: '',
      urgency: 'ROUTINE',
    };
  }

  initPatientSearch(): void {
    this.patientSearch$
      .pipe(
        debounceTime(220),
        distinctUntilChanged(),
        switchMap((q) => {
          this.patientSearchLoading.set(true);
          return this.patientService.list(undefined, q);
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
    if (q.length >= 2) this.patientSearch$.next(q);
    else {
      this.patientSuggestions.set([]);
      this.patientDropdownOpen.set(false);
    }
  }

  selectPatient(p: PatientResponse): void {
    this.selectedPatient.set(p);
    this.form.patientId = p.id;
    this.patientDropdownOpen.set(false);
    this.patientQuery.set('');
  }

  clearPatient(): void {
    this.selectedPatient.set(null);
    this.form.patientId = '';
    this.patientQuery.set('');
  }

  patientInitials(p: PatientResponse): string {
    return ((p.firstName?.[0] ?? '') + (p.lastName?.[0] ?? '')).toUpperCase() || '?';
  }

  openCreate(): void {
    this.form = this.emptyForm();
    this.editing.set(false);
    this.selectedPatient.set(null);
    this.patientQuery.set('');
    this.showModal.set(true);
  }

  closeModal(): void {
    this.showModal.set(false);
  }

  submitForm(): void {
    this.saving.set(true);
    this.referralService.create(this.form).subscribe({
      next: () => {
        this.toast.success('Referral created');
        this.closeModal();
        this.saving.set(false);
        this.load();
      },
      error: () => {
        this.toast.error('Save failed');
        this.saving.set(false);
      },
    });
  }

  confirmCancel(r: ReferralResponse): void {
    this.deletingRef.set(r);
    this.showDeleteConfirm.set(true);
  }
  cancelDeleteAction(): void {
    this.showDeleteConfirm.set(false);
    this.deletingRef.set(null);
  }
  executeCancel(): void {
    this.deleting.set(true);
    this.referralService.cancel(this.deletingRef()!.id, 'Cancelled by admin').subscribe({
      next: () => {
        this.toast.success('Referral cancelled');
        this.cancelDeleteAction();
        this.deleting.set(false);
        this.load();
      },
      error: () => {
        this.toast.error('Cancel failed');
        this.deleting.set(false);
      },
    });
  }

  load(): void {
    this.loading.set(true);
    this.referralService.getAll().subscribe({
      next: (list) => {
        this.referrals.set(Array.isArray(list) ? list : []);
        this.applyFilter();
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load referrals');
        this.loading.set(false);
      },
    });
  }

  setTab(tab: 'all' | 'pending' | 'active' | 'completed'): void {
    this.activeTab.set(tab);
    this.applyFilter();
  }

  applyFilter(): void {
    let list = this.referrals();
    const tab = this.activeTab();
    if (tab === 'pending') list = list.filter((r) => ['DRAFT', 'SUBMITTED'].includes(r.status));
    else if (tab === 'active')
      list = list.filter((r) => ['ACKNOWLEDGED', 'IN_PROGRESS'].includes(r.status));
    else if (tab === 'completed')
      list = list.filter((r) => ['COMPLETED', 'CANCELLED'].includes(r.status));
    const term = this.searchTerm.toLowerCase().trim();
    if (term) {
      list = list.filter(
        (r) =>
          (r.patientName ?? '').toLowerCase().includes(term) ||
          (r.targetSpecialty ?? '').toLowerCase().includes(term) ||
          (r.receivingProviderName ?? '').toLowerCase().includes(term),
      );
    }
    this.filtered.set(list);
  }

  viewDetail(r: ReferralResponse): void {
    this.selectedReferral.set(r);
  }
  closeDetail(): void {
    this.selectedReferral.set(null);
  }

  getStatusClass(status: string): string {
    switch (status) {
      case 'DRAFT':
        return 'status-draft';
      case 'SUBMITTED':
        return 'status-submitted';
      case 'ACKNOWLEDGED':
      case 'IN_PROGRESS':
        return 'status-active';
      case 'COMPLETED':
        return 'status-completed';
      case 'CANCELLED':
        return 'status-cancelled';
      case 'OVERDUE':
        return 'status-overdue';
      default:
        return '';
    }
  }

  getUrgencyClass(urgency: string): string {
    switch (urgency?.toUpperCase()) {
      case 'STAT':
      case 'EMERGENT':
        return 'urgency-stat';
      case 'URGENT':
        return 'urgency-urgent';
      default:
        return 'urgency-routine';
    }
  }

  countByGroup(group: string): number {
    if (group === 'pending')
      return this.referrals().filter((r) => ['DRAFT', 'SUBMITTED'].includes(r.status)).length;
    if (group === 'active')
      return this.referrals().filter((r) => ['ACKNOWLEDGED', 'IN_PROGRESS'].includes(r.status))
        .length;
    if (group === 'completed')
      return this.referrals().filter((r) => r.status === 'COMPLETED').length;
    return 0;
  }
}
