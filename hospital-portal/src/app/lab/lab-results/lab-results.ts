import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';
import {
  LabService,
  LabResultResponse,
  LabResultRequest,
  LabOrderResponse,
} from '../../services/lab.service';
import { ToastService } from '../../core/toast.service';
import { ProfileService } from '../../services/profile.service';
import { AuthService } from '../../auth/auth.service';

@Component({
  selector: 'app-lab-results',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './lab-results.html',
  styleUrl: './lab-results.scss',
})
export class LabResultsComponent implements OnInit {
  private readonly labService = inject(LabService);
  private readonly toast = inject(ToastService);
  private readonly profileService = inject(ProfileService);
  private readonly auth = inject(AuthService);

  loading = signal(true);
  results = signal<LabResultResponse[]>([]);
  filtered = signal<LabResultResponse[]>([]);
  searchTerm = '';
  activeTab = signal<'all' | 'released' | 'pending'>('all');

  stats = signal({ total: 0, released: 0, pending: 0 });

  orders = signal<LabOrderResponse[]>([]);
  private activeAssignmentId = '';

  /* ── CRUD signals ── */
  showModal = signal(false);
  editing = signal(false);
  editingId = signal<string | null>(null);
  saving = signal(false);
  form: LabResultRequest = this.emptyForm();

  showDeleteConfirm = signal(false);
  deletingResult = signal<LabResultResponse | null>(null);
  deleting = signal(false);

  /* ── Detail panel ── */
  selectedResult = signal<LabResultResponse | null>(null);

  ngOnInit(): void {
    this.loadResults();
    this.labService.listOrders({ size: 500 }).subscribe((list) => this.orders.set(list));
    this.profileService.getAssignments().subscribe({
      next: (assignments) => {
        const active = assignments.find((a: { active: boolean }) => a.active);
        if (active) this.activeAssignmentId = active.id;
      },
    });
  }

  emptyForm(): LabResultRequest {
    return {
      labOrderId: '',
      assignmentId: this.activeAssignmentId ?? '',
      patientId: '',
      resultValue: '',
      resultUnit: '',
      resultDate: new Date().toISOString().slice(0, 16),
      notes: '',
    };
  }

  /* ── CRUD ── */

  openCreate(): void {
    this.form = this.emptyForm();
    this.editing.set(false);
    this.editingId.set(null);
    this.showModal.set(true);
  }

  openEdit(r: LabResultResponse): void {
    this.form = {
      labOrderId: '',
      assignmentId: this.activeAssignmentId,
      patientId: '',
      resultValue: r.resultValue ?? '',
      resultUnit: r.resultUnit ?? '',
      resultDate: r.resultDate ? r.resultDate.slice(0, 16) : new Date().toISOString().slice(0, 16),
      notes: r.notes ?? '',
    };
    this.editing.set(true);
    this.editingId.set(r.id);
    this.showModal.set(true);
  }

  closeModal(): void {
    this.showModal.set(false);
  }

  onOrderChange(orderId: string): void {
    const order = this.orders().find((o) => o.id === orderId);
    if (order) {
      this.form.patientId = '';
    }
  }

  submitForm(): void {
    this.saving.set(true);
    this.form.assignmentId = this.activeAssignmentId;

    const id = this.editingId();
    const op =
      this.editing() && id
        ? this.labService.updateResult(id, this.form)
        : this.labService.createResult(this.form);

    op.subscribe({
      next: () => {
        this.toast.success(this.editing() ? 'Lab result updated' : 'Lab result created');
        this.closeModal();
        this.saving.set(false);
        this.loadResults();
      },
      error: () => {
        this.toast.error('Save failed');
        this.saving.set(false);
      },
    });
  }

  confirmDelete(r: LabResultResponse): void {
    this.deletingResult.set(r);
    this.showDeleteConfirm.set(true);
  }

  cancelDelete(): void {
    this.showDeleteConfirm.set(false);
    this.deletingResult.set(null);
  }

  executeDelete(): void {
    const result = this.deletingResult();
    if (!result) return;
    this.deleting.set(true);
    this.labService.deleteResult(result.id).subscribe({
      next: () => {
        this.toast.success('Lab result deleted');
        this.cancelDelete();
        this.deleting.set(false);
        this.loadResults();
      },
      error: () => {
        this.toast.error('Delete failed');
        this.deleting.set(false);
      },
    });
  }

  releaseResult(r: LabResultResponse): void {
    this.labService.releaseResult(r.id).subscribe({
      next: () => {
        this.toast.success('Result released');
        this.loadResults();
      },
      error: () => this.toast.error('Release failed'),
    });
  }

  /* ── Data loading ── */

  loadResults(): void {
    this.loading.set(true);
    this.labService.listResults({ size: 200 }).subscribe({
      next: (list) => {
        this.results.set(list);
        this.computeStats(list);
        this.applyFilter();
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load lab results');
        this.loading.set(false);
      },
    });
  }

  private computeStats(list: LabResultResponse[]): void {
    this.stats.set({
      total: list.length,
      released: list.filter((r) => r.released).length,
      pending: list.filter((r) => !r.released).length,
    });
  }

  setTab(tab: 'all' | 'released' | 'pending'): void {
    this.activeTab.set(tab);
    this.applyFilter();
  }

  applyFilter(): void {
    let list = this.results();
    const tab = this.activeTab();
    if (tab === 'released') {
      list = list.filter((r) => r.released);
    } else if (tab === 'pending') {
      list = list.filter((r) => !r.released);
    }
    const term = this.searchTerm.toLowerCase().trim();
    if (term) {
      list = list.filter(
        (r) =>
          (r.labOrderCode ?? '').toLowerCase().includes(term) ||
          (r.patientFullName ?? '').toLowerCase().includes(term) ||
          (r.labTestName ?? '').toLowerCase().includes(term) ||
          (r.resultValue ?? '').toLowerCase().includes(term),
      );
    }
    this.filtered.set(list);
  }

  viewResult(r: LabResultResponse): void {
    this.selectedResult.set(r);
  }

  closeDetail(): void {
    this.selectedResult.set(null);
  }

  getSeverityClass(flag: string): string {
    switch (flag?.toUpperCase()) {
      case 'CRITICAL':
        return 'severity-badge severity-critical';
      case 'HIGH':
        return 'severity-badge severity-high';
      case 'NORMAL':
        return 'severity-badge severity-normal';
      default:
        return 'severity-badge';
    }
  }
}
