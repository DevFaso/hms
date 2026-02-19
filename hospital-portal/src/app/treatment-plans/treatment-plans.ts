import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  TreatmentPlanService,
  TreatmentPlanResponse,
  TreatmentPlanRequest,
} from '../services/treatment-plan.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { StaffService, StaffResponse } from '../services/staff.service';
import { ToastService } from '../core/toast.service';

@Component({
  selector: 'app-treatment-plans',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './treatment-plans.html',
  styleUrl: './treatment-plans.scss',
})
export class TreatmentPlansComponent implements OnInit {
  private readonly tpService = inject(TreatmentPlanService);
  private readonly hospitalService = inject(HospitalService);
  private readonly staffService = inject(StaffService);
  private readonly toast = inject(ToastService);

  plans = signal<TreatmentPlanResponse[]>([]);
  filtered = signal<TreatmentPlanResponse[]>([]);
  loading = signal(true);
  searchTerm = '';
  activeTab = signal<'all' | 'active' | 'draft' | 'completed'>('all');
  selectedPlan = signal<TreatmentPlanResponse | null>(null);

  hospitals = signal<HospitalResponse[]>([]);
  staffList = signal<StaffResponse[]>([]);

  /* ── CRUD signals ── */
  showModal = signal(false);
  editing = signal(false);
  saving = signal(false);
  editId = '';
  form: TreatmentPlanRequest = this.emptyForm();

  ngOnInit(): void {
    this.load();
    this.hospitalService.list().subscribe((h) => this.hospitals.set(h ?? []));
    this.staffService.list().subscribe((s) => this.staffList.set(s ?? []));
  }

  emptyForm(): TreatmentPlanRequest {
    return {
      patientId: '',
      hospitalId: '',
      assignmentId: '',
      authorStaffId: '',
      problemStatement: '',
      timelineStartDate: '',
      timelineReviewDate: '',
    };
  }

  openCreate(): void {
    this.form = this.emptyForm();
    this.editing.set(false);
    this.editId = '';
    this.showModal.set(true);
  }

  openEdit(p: TreatmentPlanResponse): void {
    this.form = {
      patientId: p.patientId ?? '',
      hospitalId: p.hospitalId ?? '',
      assignmentId: '',
      authorStaffId: '',
      problemStatement: p.problemStatement ?? '',
      timelineStartDate: p.timelineStartDate ?? '',
      timelineReviewDate: p.timelineReviewDate ?? '',
    };
    this.editId = p.id;
    this.editing.set(true);
    this.showModal.set(true);
  }

  closeModal(): void {
    this.showModal.set(false);
  }

  submitForm(): void {
    this.saving.set(true);
    const op = this.editing()
      ? this.tpService.update(this.editId, this.form)
      : this.tpService.create(this.form);
    op.subscribe({
      next: () => {
        this.toast.success(this.editing() ? 'Plan updated' : 'Plan created');
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

  load(): void {
    this.loading.set(true);
    this.tpService.getAll({ size: 200 }).subscribe({
      next: (list) => {
        this.plans.set(list ?? []);
        this.applyFilter();
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load treatment plans');
        this.loading.set(false);
      },
    });
  }

  setTab(tab: 'all' | 'active' | 'draft' | 'completed'): void {
    this.activeTab.set(tab);
    this.applyFilter();
  }

  applyFilter(): void {
    let list = this.plans();
    const tab = this.activeTab();
    if (tab === 'active') list = list.filter((p) => ['ACTIVE', 'APPROVED'].includes(p.status));
    else if (tab === 'draft')
      list = list.filter((p) => ['DRAFT', 'PENDING_REVIEW'].includes(p.status));
    else if (tab === 'completed') list = list.filter((p) => p.status === 'COMPLETED');
    const term = this.searchTerm.toLowerCase().trim();
    if (term) {
      list = list.filter(
        (p) =>
          (p.patientName ?? '').toLowerCase().includes(term) ||
          (p.problemStatement ?? '').toLowerCase().includes(term) ||
          (p.authorStaffName ?? '').toLowerCase().includes(term),
      );
    }
    this.filtered.set(list);
  }

  viewDetail(p: TreatmentPlanResponse): void {
    this.selectedPlan.set(p);
  }
  closeDetail(): void {
    this.selectedPlan.set(null);
  }

  getStatusClass(status: string): string {
    switch (status) {
      case 'DRAFT':
        return 'status-draft';
      case 'PENDING_REVIEW':
        return 'status-pending';
      case 'APPROVED':
      case 'ACTIVE':
        return 'status-active';
      case 'COMPLETED':
        return 'status-completed';
      case 'REJECTED':
        return 'status-rejected';
      default:
        return '';
    }
  }

  countByGroup(group: string): number {
    if (group === 'active')
      return this.plans().filter((p) => ['ACTIVE', 'APPROVED'].includes(p.status)).length;
    if (group === 'draft')
      return this.plans().filter((p) => ['DRAFT', 'PENDING_REVIEW'].includes(p.status)).length;
    if (group === 'completed') return this.plans().filter((p) => p.status === 'COMPLETED').length;
    return 0;
  }
}
