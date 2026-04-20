import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, switchMap } from 'rxjs/operators';
import {
  ReferralService,
  ReferralResponse,
  ReferralRequest,
  DepartmentMinimal,
} from '../services/referral.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { PatientService, PatientResponse } from '../services/patient.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { AuthService } from '../auth/auth.service';
import { TranslateModule } from '@ngx-translate/core';

@Component({
  selector: 'app-referrals',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './referrals.html',
  styleUrl: './referrals.scss',
})
export class ReferralsComponent implements OnInit {
  private readonly referralService = inject(ReferralService);
  private readonly hospitalService = inject(HospitalService);
  private readonly patientService = inject(PatientService);
  private readonly toast = inject(ToastService);
  private readonly roleContext = inject(RoleContextService);
  private readonly auth = inject(AuthService);

  referrals = signal<ReferralResponse[]>([]);
  filtered = signal<ReferralResponse[]>([]);
  loading = signal(true);
  searchTerm = '';
  activeTab = signal<'all' | 'pending' | 'active' | 'completed'>('all');
  selectedReferral = signal<ReferralResponse | null>(null);

  hospitals = signal<HospitalResponse[]>([]);
  /** All hospitals for the destination picker (loaded once) */
  allHospitals = signal<HospitalResponse[]>([]);
  sourceDepartments = signal<DepartmentMinimal[]>([]);
  targetDepartments = signal<DepartmentMinimal[]>([]);

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

  /* ── Status transition modals ── */
  showAcknowledgeModal = signal(false);
  acknowledgingRef = signal<ReferralResponse | null>(null);
  acknowledgeNotes = '';
  actionLoading = signal(false);

  showCompleteModal = signal(false);
  completingRef = signal<ReferralResponse | null>(null);
  completeSummary = '';
  completeFollowUp = '';

  urgencies = ['ROUTINE', 'PRIORITY', 'URGENT', 'EMERGENCY'];

  referralTypes = [
    { value: 'CONSULTATION', label: 'Consultation' },
    { value: 'SHARED_CARE', label: 'Shared Care' },
    { value: 'TRANSFER_OF_CARE', label: 'Transfer of Care' },
  ];

  specialties = [
    { value: 'GENERAL_PRACTICE', label: 'General Practice' },
    { value: 'INTERNAL_MEDICINE', label: 'Internal Medicine' },
    { value: 'FAMILY_MEDICINE', label: 'Family Medicine' },
    { value: 'PEDIATRICS', label: 'Pediatrics' },
    { value: 'EMERGENCY_MEDICINE', label: 'Emergency Medicine' },
    { value: 'GENERAL_SURGERY', label: 'General Surgery' },
    { value: 'CARDIOTHORACIC_SURGERY', label: 'Cardiothoracic Surgery' },
    { value: 'NEUROSURGERY', label: 'Neurosurgery' },
    { value: 'ORTHOPEDIC_SURGERY', label: 'Orthopedic Surgery' },
    { value: 'PLASTIC_SURGERY', label: 'Plastic Surgery' },
    { value: 'VASCULAR_SURGERY', label: 'Vascular Surgery' },
    { value: 'UROLOGY', label: 'Urology' },
    { value: 'CARDIOLOGY', label: 'Cardiology' },
    { value: 'NEUROLOGY', label: 'Neurology' },
    { value: 'GASTROENTEROLOGY', label: 'Gastroenterology' },
    { value: 'PULMONOLOGY', label: 'Pulmonology' },
    { value: 'NEPHROLOGY', label: 'Nephrology' },
    { value: 'ENDOCRINOLOGY', label: 'Endocrinology' },
    { value: 'RHEUMATOLOGY', label: 'Rheumatology' },
    { value: 'HEMATOLOGY', label: 'Hematology' },
    { value: 'ONCOLOGY', label: 'Oncology' },
    { value: 'INFECTIOUS_DISEASE', label: 'Infectious Disease' },
    { value: 'OBSTETRICS_GYNECOLOGY', label: 'Obstetrics & Gynecology' },
    { value: 'MATERNAL_FETAL_MEDICINE', label: 'Maternal-Fetal Medicine' },
    { value: 'REPRODUCTIVE_ENDOCRINOLOGY', label: 'Reproductive Endocrinology' },
    { value: 'MIDWIFERY', label: 'Midwifery' },
    { value: 'OPHTHALMOLOGY', label: 'Ophthalmology' },
    { value: 'OTOLARYNGOLOGY', label: 'Otolaryngology (ENT)' },
    { value: 'AUDIOLOGY', label: 'Audiology' },
    { value: 'PSYCHIATRY', label: 'Psychiatry' },
    { value: 'PSYCHOLOGY', label: 'Psychology' },
    { value: 'BEHAVIORAL_HEALTH', label: 'Behavioral Health' },
    { value: 'PHYSICAL_MEDICINE_REHABILITATION', label: 'Physical Medicine & Rehabilitation' },
    { value: 'PHYSICAL_THERAPY', label: 'Physical Therapy' },
    { value: 'OCCUPATIONAL_THERAPY', label: 'Occupational Therapy' },
    { value: 'SPEECH_THERAPY', label: 'Speech Therapy' },
    { value: 'DERMATOLOGY', label: 'Dermatology' },
    { value: 'ALLERGY_IMMUNOLOGY', label: 'Allergy & Immunology' },
    { value: 'RADIOLOGY', label: 'Radiology' },
    { value: 'INTERVENTIONAL_RADIOLOGY', label: 'Interventional Radiology' },
    { value: 'PATHOLOGY', label: 'Pathology' },
    { value: 'ANESTHESIOLOGY', label: 'Anesthesiology' },
    { value: 'PAIN_MANAGEMENT', label: 'Pain Management' },
    { value: 'PALLIATIVE_CARE', label: 'Palliative Care' },
    { value: 'NUTRITION_DIETETICS', label: 'Nutrition & Dietetics' },
    { value: 'GENETICS', label: 'Genetics' },
    { value: 'SLEEP_MEDICINE', label: 'Sleep Medicine' },
    { value: 'GERIATRICS', label: 'Geriatrics' },
    { value: 'SPORTS_MEDICINE', label: 'Sports Medicine' },
    { value: 'WOUND_CARE', label: 'Wound Care' },
    { value: 'OTHER', label: 'Other' },
  ];

  ngOnInit(): void {
    this.load();
    this.loadAssignedHospitals();
    this.loadAllHospitals();
    this.initPatientSearch();
  }

  emptyForm(): ReferralRequest {
    return {
      patientId: '',
      hospitalId: '',
      receivingHospitalId: '',
      sourceDepartmentId: '',
      referringProviderId: this.auth.getUserProfile()?.staffId ?? '',
      targetSpecialty: '',
      referralReason: '',
      urgency: 'ROUTINE',
      referralType: 'CONSULTATION',
      targetDepartmentId: '',
    };
  }

  /** ── TENANT ISOLATION: only SUPER_ADMIN may choose from all hospitals ── */
  private loadAssignedHospitals(): void {
    if (this.roleContext.isSuperAdmin()) {
      this.hospitalService.list().subscribe((h) => this.hospitals.set(h ?? []));
    } else {
      this.hospitalService.getMyHospitalAsResponse().subscribe({
        next: (h) => {
          this.hospitals.set([h]);
          this.form.hospitalId = h.id;
          this.loadSourceDepartments(h.id);
        },
      });
    }
  }

  /** Load all hospitals for the destination hospital picker */
  private loadAllHospitals(): void {
    this.hospitalService.list().subscribe({
      next: (h) => this.allHospitals.set(h ?? []),
    });
  }

  /** Load departments for the source hospital */
  loadSourceDepartments(hospitalId: string): void {
    if (!hospitalId) {
      this.sourceDepartments.set([]);
      return;
    }
    this.referralService.getDepartmentsByHospital(hospitalId).subscribe({
      next: (depts) => this.sourceDepartments.set(depts),
      error: () => this.sourceDepartments.set([]),
    });
  }

  /** Load departments for the receiving/destination hospital */
  onReceivingHospitalChange(hospitalId: string): void {
    this.form.receivingHospitalId = hospitalId;
    this.form.targetDepartmentId = '';
    this.targetDepartments.set([]);
    if (!hospitalId) return;
    this.referralService.getDepartmentsByHospital(hospitalId).subscribe({
      next: (depts) => this.targetDepartments.set(depts),
      error: () => this.targetDepartments.set([]),
    });
  }

  /** When source hospital changes (super-admin only) */
  onSourceHospitalChange(hospitalId: string): void {
    this.form.hospitalId = hospitalId;
    this.form.sourceDepartmentId = '';
    this.sourceDepartments.set([]);
    if (hospitalId) this.loadSourceDepartments(hospitalId);
  }

  get lockedHospitalName(): string {
    const h = this.hospitals();
    return h.length === 1 ? h[0].name : 'No hospital assigned';
  }

  get hospitalLocked(): boolean {
    return !this.roleContext.isSuperAdmin();
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
    this.targetDepartments.set([]);
    // Re-apply locked hospital after emptyForm() reset
    if (this.hospitalLocked) {
      const h = this.hospitals();
      if (h.length === 1) {
        this.form.hospitalId = h[0].id;
        this.loadSourceDepartments(h[0].id);
      }
    }
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

  /* ── Submit DRAFT → SUBMITTED ── */
  submitReferral(r: ReferralResponse): void {
    this.actionLoading.set(true);
    this.referralService.submit(r.id).subscribe({
      next: () => {
        this.toast.success('Referral submitted');
        this.actionLoading.set(false);
        this.load();
        this.closeDetail();
      },
      error: () => {
        this.toast.error('Submit failed');
        this.actionLoading.set(false);
      },
    });
  }

  /* ── Acknowledge SUBMITTED → ACKNOWLEDGED ── */
  openAcknowledge(r: ReferralResponse): void {
    this.acknowledgingRef.set(r);
    this.acknowledgeNotes = '';
    this.showAcknowledgeModal.set(true);
  }
  closeAcknowledgeModal(): void {
    this.showAcknowledgeModal.set(false);
    this.acknowledgingRef.set(null);
  }
  executeAcknowledge(): void {
    const ref = this.acknowledgingRef();
    if (!ref) return;
    const providerId = this.auth.getUserProfile()?.staffId ?? '';
    this.actionLoading.set(true);
    this.referralService.acknowledge(ref.id, this.acknowledgeNotes, providerId).subscribe({
      next: () => {
        this.toast.success('Referral acknowledged');
        this.closeAcknowledgeModal();
        this.actionLoading.set(false);
        this.load();
        this.closeDetail();
      },
      error: () => {
        this.toast.error('Acknowledge failed');
        this.actionLoading.set(false);
      },
    });
  }

  /* ── Complete ACKNOWLEDGED/IN_PROGRESS → COMPLETED ── */
  openComplete(r: ReferralResponse): void {
    this.completingRef.set(r);
    this.completeSummary = '';
    this.completeFollowUp = '';
    this.showCompleteModal.set(true);
  }
  closeCompleteModal(): void {
    this.showCompleteModal.set(false);
    this.completingRef.set(null);
  }
  executeComplete(): void {
    const ref = this.completingRef();
    if (!ref) return;
    this.actionLoading.set(true);
    this.referralService.complete(ref.id, this.completeSummary, this.completeFollowUp).subscribe({
      next: () => {
        this.toast.success('Referral completed');
        this.closeCompleteModal();
        this.actionLoading.set(false);
        this.load();
        this.closeDetail();
      },
      error: () => {
        this.toast.error('Complete failed');
        this.actionLoading.set(false);
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
      case 'EMERGENCY':
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
