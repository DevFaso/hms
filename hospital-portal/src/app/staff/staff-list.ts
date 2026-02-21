import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { StaffService, StaffResponse, StaffUpsertRequest } from '../services/staff.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { UserService, UserSummary } from '../services/user.service';
import { ToastService } from '../core/toast.service';

@Component({
  selector: 'app-staff-list',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './staff-list.html',
  styleUrl: './staff-list.scss',
})
export class StaffListComponent implements OnInit {
  private readonly staffService = inject(StaffService);
  private readonly hospitalService = inject(HospitalService);
  private readonly userService = inject(UserService);
  private readonly toast = inject(ToastService);

  staff = signal<StaffResponse[]>([]);
  filtered = signal<StaffResponse[]>([]);
  searchTerm = '';
  loading = signal(true);

  hospitals = signal<HospitalResponse[]>([]);
  users = signal<UserSummary[]>([]);

  // Create / Edit
  showModal = signal(false);
  editing = signal<StaffResponse | null>(null);
  saving = signal(false);
  form: StaffUpsertRequest = this.emptyForm();

  // Delete
  showDeleteConfirm = signal(false);
  deletingStaff = signal<StaffResponse | null>(null);
  deleting = signal(false);

  readonly employmentTypes = ['FULL_TIME', 'PART_TIME', 'CONTRACT', 'VOLUNTEER', 'INTERN'];

  /**
   * Exact values from the backend JobTitle enum.
   * PATIENT / VISITOR / SUPER_ADMIN are omitted — not valid staff job titles.
   */
  readonly jobTitles = [
    'DOCTOR',
    'PHYSICIAN',
    'NURSE_PRACTITIONER',
    'NURSE',
    'MIDWIFE',
    'SURGEON',
    'ANESTHESIOLOGIST',
    'RADIOLOGIST',
    'PHYSIOTHERAPIST',
    'PSYCHOLOGIST',
    'PHARMACIST',
    'LAB_TECHNICIAN',
    'LABORATORY_SCIENTIST',
    'TECHNICIAN',
    'HOSPITAL_ADMIN',
    'HOSPITAL_ADMINISTRATOR',
    'ADMINISTRATIVE_STAFF',
    'ADMINISTRATIVE_ASSISTANT',
    'RECEPTIONIST',
    'BILLING_SPECIALIST',
    'SOCIAL_WORKER',
    'HUMAN_RESOURCES',
    'IT_SUPPORT',
    'CLEANING_STAFF',
    'SECURITY_PERSONNEL',
  ];

  /**
   * Exact values from the backend Specialization enum.
   * Grouped by clinical category — no free-text allowed.
   */
  readonly specializations = [
    // Doctors (all require licence)
    'GENERAL_PRACTICE',
    'CARDIOLOGY',
    'NEUROLOGY',
    'PEDIATRICS',
    'ORTHOPEDICS',
    'DERMATOLOGY',
    'GYNECOLOGY',
    'ONCOLOGY',
    'RADIOLOGY',
    'PSYCHIATRY',
    // Nurses (all require licence)
    'GENERAL_NURSE',
    'ICU_NURSE',
    'PEDIATRIC_NURSE',
    'SURGICAL_NURSE',
    // Pharmacists (all require licence)
    'CLINICAL_PHARMACY',
    'HOSPITAL_PHARMACY',
    // Lab staff (LAB_TECHNICIAN + PATHOLOGY require licence; MICROBIOLOGY role-dependent)
    'LAB_TECHNICIAN',
    'PATHOLOGY',
    'MICROBIOLOGY',
    // Default / non-clinical
    'OTHER',
  ];

  /**
   * Job titles that require a medical licence number.
   *
   * Rules (aligned with international medical standards + backend validation):
   *   ✅ ALL doctors/clinicians     — DOCTOR, PHYSICIAN, SURGEON, ANESTHESIOLOGIST,
   *                                   RADIOLOGIST, PHYSIOTHERAPIST, PSYCHOLOGIST
   *   ✅ ALL nurses                  — NURSE, NURSE_PRACTITIONER, MIDWIFE
   *   ✅ ALL pharmacists             — PHARMACIST
   *   ✅ Lab / diagnostic staff      — LAB_TECHNICIAN, LABORATORY_SCIENTIST
   *   ❌ Administrative / support    — RECEPTIONIST, ADMIN_STAFF, HR, IT, etc. → no licence
   */
  private readonly licencedTitles = new Set([
    // Doctors & clinicians
    'DOCTOR',
    'PHYSICIAN',
    'SURGEON',
    'ANESTHESIOLOGIST',
    'RADIOLOGIST',
    'PHYSIOTHERAPIST',
    'PSYCHOLOGIST',
    // Nurses
    'NURSE',
    'NURSE_PRACTITIONER',
    'MIDWIFE',
    // Pharmacists
    'PHARMACIST',
    // Lab & diagnostic
    'LAB_TECHNICIAN',
    'LABORATORY_SCIENTIST',
  ]);

  /** Whether the currently-selected job title requires a specialization / licence */
  get needsSpecialization(): boolean {
    return this.licencedTitles.has(this.form.jobTitle ?? '');
  }

  get needsLicence(): boolean {
    return this.licencedTitles.has(this.form.jobTitle ?? '');
  }

  ngOnInit(): void {
    this.loadStaff();
    this.hospitalService.list().subscribe({
      next: (data) => this.hospitals.set(data),
    });
    this.userService.list(0, 500).subscribe({
      next: (page) => this.users.set(page.content),
    });
  }

  loadStaff(): void {
    this.loading.set(true);
    this.staffService.list().subscribe({
      next: (data) => {
        this.staff.set(data);
        this.applyFilter();
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load staff');
        this.loading.set(false);
      },
    });
  }

  applyFilter(): void {
    const term = this.searchTerm.toLowerCase().trim();
    if (!term) {
      this.filtered.set(this.staff());
      return;
    }
    this.filtered.set(
      this.staff().filter(
        (s) =>
          s.name.toLowerCase().includes(term) ||
          s.email.toLowerCase().includes(term) ||
          (s.departmentName?.toLowerCase().includes(term) ?? false) ||
          (s.jobTitle?.toLowerCase().includes(term) ?? false) ||
          (s.roleName?.toLowerCase().includes(term) ?? false),
      ),
    );
  }

  // ---------- Create ----------
  openCreate(): void {
    this.form = this.emptyForm();
    this.editing.set(null);
    this.showModal.set(true);
  }

  // ---------- Edit ----------
  openEdit(member: StaffResponse): void {
    this.editing.set(member);
    // Sanitize specialization: clear any old free-text value that isn't a valid enum member
    const rawSpec = member.specialization ?? '';
    const validSpec = this.specializations.includes(rawSpec) ? rawSpec : '';
    this.form = {
      userId: member.userId,
      hospitalId: member.hospitalId,
      departmentId: member.departmentId ?? '',
      specialization: validSpec,
      licenseNumber: member.licenseNumber ?? '',
      jobTitle: member.jobTitle ?? '',
      employmentType: member.employmentType ?? '',
    };
    this.showModal.set(true);
  }

  closeModal(): void {
    this.showModal.set(false);
    this.editing.set(null);
  }

  submitForm(): void {
    if (!this.form.userId || !this.form.hospitalId) {
      this.toast.error('User and hospital are required');
      return;
    }
    this.saving.set(true);
    const payload: StaffUpsertRequest = { ...this.form };
    if (!payload.departmentId) delete payload.departmentId;
    if (!payload.specialization) delete payload.specialization;
    if (!payload.licenseNumber) delete payload.licenseNumber;
    if (!payload.jobTitle) delete payload.jobTitle;
    if (!payload.employmentType) delete payload.employmentType;
    // Never send specialization or licenseNumber for non-clinical roles
    if (!this.needsSpecialization) {
      delete payload.specialization;
      delete payload.licenseNumber;
    }

    const existing = this.editing();
    const request$ = existing
      ? this.staffService.update(existing.id, payload)
      : this.staffService.create(payload);

    request$.subscribe({
      next: () => {
        this.toast.success(existing ? 'Staff updated' : 'Staff created');
        this.closeModal();
        this.saving.set(false);
        this.loadStaff();
      },
      error: (err) => {
        const body = err?.error;
        let msg = `Failed to ${existing ? 'update' : 'create'} staff`;
        if (body?.fieldErrors) {
          msg = Object.values(body.fieldErrors).join('. ');
        } else if (body?.message) {
          msg = body.message;
        }
        this.toast.error(msg);
        this.saving.set(false);
      },
    });
  }

  // ---------- Delete ----------
  confirmDelete(member: StaffResponse): void {
    this.deletingStaff.set(member);
    this.showDeleteConfirm.set(true);
  }

  cancelDelete(): void {
    this.showDeleteConfirm.set(false);
    this.deletingStaff.set(null);
  }

  executeDelete(): void {
    const member = this.deletingStaff();
    if (!member) return;
    this.deleting.set(true);
    this.staffService.deactivate(member.id).subscribe({
      next: () => {
        this.toast.success('Staff deactivated');
        this.cancelDelete();
        this.deleting.set(false);
        this.loadStaff();
      },
      error: (err) => {
        this.toast.error(err?.error?.message ?? 'Failed to deactivate staff');
        this.deleting.set(false);
      },
    });
  }

  // ---------- Helpers ----------
  getInitials(name: string): string {
    if (!name) return '??';
    const parts = name.trim().split(/\s+/);
    if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase();
    return name.substring(0, 2).toUpperCase();
  }

  formatJobTitle(jobTitle?: string): string {
    if (!jobTitle) return 'Staff';
    return jobTitle
      .replaceAll('_', ' ')
      .toLowerCase()
      .replaceAll(/\b\w/g, (c) => c.toUpperCase());
  }

  private emptyForm(): StaffUpsertRequest {
    return {
      userId: '',
      hospitalId: '',
      departmentId: '',
      specialization: '',
      licenseNumber: '',
      jobTitle: '',
      employmentType: '',
    };
  }
}
