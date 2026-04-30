import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, switchMap } from 'rxjs/operators';
import {
  AdmissionService,
  AdmissionResponse,
  AdmissionRequest,
  AdmissionType,
  AcuityLevel,
} from '../services/admission.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { StaffService, StaffResponse } from '../services/staff.service';
import { PatientService, PatientResponse } from '../services/patient.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { OrderSetPickerComponent } from './order-set-picker/order-set-picker.component';

interface OrderSetPickerCtx {
  hospitalId: string;
  admissionId: string;
  encounterId: string;
  orderingStaffId: string;
}

@Component({
  selector: 'app-admissions',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule, OrderSetPickerComponent],
  templateUrl: './admissions.html',
  styleUrl: './admissions.scss',
})
export class AdmissionsComponent implements OnInit {
  private readonly admissionService = inject(AdmissionService);
  private readonly hospitalService = inject(HospitalService);
  private readonly staffService = inject(StaffService);
  private readonly patientService = inject(PatientService);
  private readonly toast = inject(ToastService);
  private readonly roleContext = inject(RoleContextService);
  private readonly route = inject(ActivatedRoute);
  private readonly translate = inject(TranslateService);

  admissions = signal<AdmissionResponse[]>([]);
  filtered = signal<AdmissionResponse[]>([]);
  loading = signal(true);
  searchTerm = '';
  activeTab = signal<'all' | 'admitted' | 'discharged'>('all');

  /**
   * When non-null, the order-set picker is rendered as a modal-style
   * overlay. Set by {@link openOrderSetPicker}; cleared by
   * {@link closeOrderSetPicker} or after a successful apply.
   *
   * <p>encounterId is left blank in v0 because AdmissionResponse does
   * not carry the active encounter — fan-out to MEDICATION + LAB tolerates
   * a null encounter; IMAGING items in the set will surface a clear
   * server error. Follow-up work will add `currentEncounterId` to
   * AdmissionResponse so imaging-rich sets work end-to-end here too.
   */
  orderSetPicker = signal<OrderSetPickerCtx | null>(null);

  hospitals = signal<HospitalResponse[]>([]);
  staffMembers = signal<StaffResponse[]>([]);
  private allStaff: StaffResponse[] = [];
  departments = signal<{ id: string; name: string }[]>([]);

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
  editingId = signal<string | null>(null);
  form: AdmissionRequest = this.emptyForm();

  showDeleteConfirm = signal(false);
  deletingAdm = signal<AdmissionResponse | null>(null);
  deleting = signal(false);

  /* ── Discharge signals ── */
  showDischargeModal = signal(false);
  dischargingAdm = signal<AdmissionResponse | null>(null);
  discharging = signal(false);
  dischargeForm = {
    dischargeDisposition: 'HOME' as string,
    dischargeSummary: '',
    dischargeInstructions: '',
    dischargingProviderId: '',
  };
  readonly dispositionOptions = [
    { value: 'HOME', labelKey: 'ADMISSIONS.DISPOSITION_HOME' },
    { value: 'HOME_WITH_HOME_HEALTH', labelKey: 'ADMISSIONS.DISPOSITION_HOME_WITH_HOME_HEALTH' },
    {
      value: 'SKILLED_NURSING_FACILITY',
      labelKey: 'ADMISSIONS.DISPOSITION_SKILLED_NURSING_FACILITY',
    },
    {
      value: 'LONG_TERM_CARE_FACILITY',
      labelKey: 'ADMISSIONS.DISPOSITION_LONG_TERM_CARE_FACILITY',
    },
    {
      value: 'REHABILITATION_FACILITY',
      labelKey: 'ADMISSIONS.DISPOSITION_REHABILITATION_FACILITY',
    },
    { value: 'HOSPICE_HOME', labelKey: 'ADMISSIONS.DISPOSITION_HOSPICE_HOME' },
    { value: 'HOSPICE_FACILITY', labelKey: 'ADMISSIONS.DISPOSITION_HOSPICE_FACILITY' },
    { value: 'PSYCHIATRIC_FACILITY', labelKey: 'ADMISSIONS.DISPOSITION_PSYCHIATRIC_FACILITY' },
    { value: 'AGAINST_MEDICAL_ADVICE', labelKey: 'ADMISSIONS.DISPOSITION_AGAINST_MEDICAL_ADVICE' },
    {
      value: 'TRANSFERRED_TO_ANOTHER_HOSPITAL',
      labelKey: 'ADMISSIONS.DISPOSITION_TRANSFERRED_TO_ANOTHER_HOSPITAL',
    },
    { value: 'OTHER', labelKey: 'ADMISSIONS.DISPOSITION_OTHER' },
  ];

  admissionTypes: AdmissionType[] = [
    'EMERGENCY',
    'ELECTIVE',
    'URGENT',
    'NEWBORN',
    'TRANSFER',
    'OBSERVATION',
    'DAY_CASE',
    'LABOR_DELIVERY',
    'PSYCHIATRIC',
  ];
  acuityLevels: AcuityLevel[] = [
    'LEVEL_5_CRITICAL',
    'LEVEL_4_SEVERE',
    'LEVEL_3_MAJOR',
    'LEVEL_2_MODERATE',
    'LEVEL_1_MINIMAL',
  ];

  readonly acuityLabel: Record<string, string> = {
    LEVEL_5_CRITICAL: 'Critical',
    LEVEL_4_SEVERE: 'Severe',
    LEVEL_3_MAJOR: 'Major',
    LEVEL_2_MODERATE: 'Moderate',
    LEVEL_1_MINIMAL: 'Minimal',
  };

  ngOnInit(): void {
    this.load();
    this.loadAssignedHospitals();
    // ── TENANT ISOLATION: scope staff list to active hospital ──
    const hid = this.roleContext.activeHospitalId;
    this.staffService.list(hid ?? undefined).subscribe((s) => {
      this.allStaff = s ?? [];
      this.staffMembers.set(s ?? []);
    });
    this.initPatientSearch();

    const tab = this.route.snapshot.queryParamMap.get('tab');
    if (tab === 'admitted' || tab === 'discharged') {
      this.activeTab.set(tab);
    }
  }

  emptyForm(): AdmissionRequest {
    return {
      patientId: '',
      hospitalId: '',
      admittingProviderId: '',
      admissionType: 'ELECTIVE',
      acuityLevel: 'LEVEL_2_MODERATE',
      admissionDateTime: '',
      chiefComplaint: '',
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
        },
      });
    }
  }

  get lockedHospitalName(): string {
    const h = this.hospitals();
    return h.length === 1 ? h[0].name : 'No hospital assigned';
  }

  get hospitalLocked(): boolean {
    return !this.roleContext.isSuperAdmin();
  }

  // ── Patient picker ──
  initPatientSearch(): void {
    this.patientSearch$
      .pipe(
        debounceTime(220),
        distinctUntilChanged(),
        switchMap((q) => {
          this.patientSearchLoading.set(true);
          // ── TENANT ISOLATION: scope patient search to active hospital ──
          const hid = this.roleContext.activeHospitalId ?? undefined;
          return this.patientService.list(hid, q);
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

  // ── Department derivation ──
  onHospitalChange(hospitalId: string): void {
    this.form.hospitalId = hospitalId;
    this.form.departmentId = undefined;
    this.loadDepartmentsFor(hospitalId);
  }

  loadDepartmentsFor(hospitalId: string): void {
    if (!hospitalId) {
      this.departments.set([]);
      return;
    }
    const seen = new Set<string>();
    const depts = this.allStaff
      .filter((s) => s.hospitalId === hospitalId && s.departmentId)
      .reduce<{ id: string; name: string }[]>((acc, s) => {
        if (!seen.has(s.departmentId!)) {
          seen.add(s.departmentId!);
          acc.push({ id: s.departmentId!, name: s.departmentName || s.departmentId! });
        }
        return acc;
      }, []);
    this.departments.set(depts);
  }

  openCreate(): void {
    this.form = this.emptyForm();
    this.editing.set(false);
    this.editingId.set(null);
    this.selectedPatient.set(null);
    this.patientQuery.set('');
    this.departments.set([]);
    // Re-apply locked hospital after emptyForm() reset
    if (this.hospitalLocked) {
      const h = this.hospitals();
      if (h.length === 1) this.form.hospitalId = h[0].id;
    }
    this.showModal.set(true);
  }

  openOrderSetPicker(a: AdmissionResponse): void {
    if (!a.id || !a.hospitalId) return;
    this.orderSetPicker.set({
      hospitalId: a.hospitalId,
      admissionId: a.id,
      encounterId: '',
      orderingStaffId: a.admittingProviderId ?? '',
    });
  }

  closeOrderSetPicker(): void {
    this.orderSetPicker.set(null);
  }

  onOrderSetApplied(): void {
    // Show a confirmation toast; let the picker linger for the user to dismiss.
    this.toast.success(
      this.translate.instant('ORDER_SETS.APPLIED_RESULT', { count: '', skipped: '' }),
    );
  }

  openEdit(a: AdmissionResponse): void {
    this.form = {
      patientId: a.patientId ?? '',
      hospitalId: a.hospitalId ?? '',
      admittingProviderId: a.admittingProviderId ?? '',
      departmentId: a.departmentId,
      roomBed: a.roomBed,
      admissionType: a.admissionType ?? 'ELECTIVE',
      acuityLevel: a.acuityLevel ?? 'MODERATE',
      admissionDateTime: a.admissionDateTime?.substring(0, 10) ?? '',
      chiefComplaint: a.chiefComplaint ?? '',
      admissionNotes: a.admissionNotes,
    };
    this.selectedPatient.set({
      id: a.patientId ?? '',
      firstName: a.patientName?.split(' ')[0] ?? '',
      lastName: a.patientName?.split(' ').slice(1).join(' ') ?? '',
      email: '',
    } as PatientResponse);
    this.loadDepartmentsFor(a.hospitalId ?? '');
    this.editing.set(true);
    this.editingId.set(a.id);
    this.showModal.set(true);
  }

  closeModal(): void {
    this.showModal.set(false);
  }

  submitForm(): void {
    this.saving.set(true);
    const op = this.editing()
      ? this.admissionService.update(this.editingId()!, this.form)
      : this.admissionService.create(this.form);
    op.subscribe({
      next: () => {
        this.toast.success(this.editing() ? 'Admission updated' : 'Admission created');
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

  confirmDelete(a: AdmissionResponse): void {
    this.deletingAdm.set(a);
    this.showDeleteConfirm.set(true);
  }
  cancelDelete(): void {
    this.showDeleteConfirm.set(false);
    this.deletingAdm.set(null);
  }
  executeDelete(): void {
    this.deleting.set(true);
    this.admissionService.delete(this.deletingAdm()!.id).subscribe({
      next: () => {
        this.toast.success('Admission deleted');
        this.cancelDelete();
        this.deleting.set(false);
        this.load();
      },
      error: () => {
        this.toast.error('Delete failed');
        this.deleting.set(false);
      },
    });
  }

  /* ── Discharge ── */
  confirmDischarge(a: AdmissionResponse): void {
    this.dischargingAdm.set(a);
    this.dischargeForm = {
      dischargeDisposition: 'HOME',
      dischargeSummary: '',
      dischargeInstructions: '',
      dischargingProviderId: '',
    };
    this.showDischargeModal.set(true);
  }

  cancelDischarge(): void {
    this.showDischargeModal.set(false);
    this.dischargingAdm.set(null);
  }

  executeDischarge(): void {
    this.discharging.set(true);
    const adm = this.dischargingAdm()!;
    this.admissionService
      .discharge(adm.id, {
        dischargeDisposition: this.dischargeForm.dischargeDisposition,
        dischargeSummary: this.dischargeForm.dischargeSummary,
        dischargeInstructions: this.dischargeForm.dischargeInstructions || undefined,
        dischargingProviderId: this.dischargeForm.dischargingProviderId,
      })
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('ADMISSIONS.DISCHARGE_SUCCESS'));
          this.cancelDischarge();
          this.discharging.set(false);
          this.load();
        },
        error: () => {
          this.toast.error(this.translate.instant('ADMISSIONS.DISCHARGE_ERROR'));
          this.discharging.set(false);
        },
      });
  }

  load(): void {
    this.loading.set(true);
    this.admissionService.getAll().subscribe({
      next: (list) => {
        this.admissions.set(list ?? []);
        this.applyFilter();
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load admissions');
        this.loading.set(false);
      },
    });
  }

  setTab(tab: 'all' | 'admitted' | 'discharged'): void {
    this.activeTab.set(tab);
    this.applyFilter();
  }

  applyFilter(): void {
    let list = this.admissions();
    const tab = this.activeTab();
    if (tab === 'admitted') list = list.filter((a) => a.status === 'ACTIVE');
    else if (tab === 'discharged') list = list.filter((a) => a.status === 'DISCHARGED');
    const term = this.searchTerm.toLowerCase().trim();
    if (term) {
      list = list.filter(
        (a) =>
          (a.patientName ?? '').toLowerCase().includes(term) ||
          (a.chiefComplaint ?? '').toLowerCase().includes(term) ||
          (a.roomBed ?? '').toLowerCase().includes(term),
      );
    }
    this.filtered.set(list);
  }

  getStatusClass(status: string): string {
    switch (status) {
      case 'ACTIVE':
        return 'status-admitted';
      case 'DISCHARGED':
        return 'status-discharged';
      case 'PENDING':
        return 'status-pending';
      case 'TRANSFERRED':
        return 'status-transferred';
      default:
        return '';
    }
  }

  getAcuityClass(level: string): string {
    switch (level) {
      case 'LEVEL_5_CRITICAL':
        return 'acuity-critical';
      case 'LEVEL_4_SEVERE':
        return 'acuity-high';
      case 'LEVEL_3_MAJOR':
        return 'acuity-moderate';
      default:
        return 'acuity-low';
    }
  }

  countByStatus(status: string): number {
    return this.admissions().filter((a) => a.status === status).length;
  }
}
