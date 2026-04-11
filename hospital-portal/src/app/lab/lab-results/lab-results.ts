import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import {
  LabService,
  LabResultResponse,
  LabResultTrendPoint,
  LabResultRequest,
  LabOrderResponse,
} from '../../services/lab.service';
import { ToastService } from '../../core/toast.service';
import { ProfileService } from '../../services/profile.service';

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
  private readonly translate = inject(TranslateService);
  private readonly profileService = inject(ProfileService);

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

  /** Format a Date as YYYY-MM-DDTHH:mm in the user's local timezone (for datetime-local inputs). */
  private toLocalDatetime(d: Date): string {
    const pad = (n: number): string => n.toString().padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
  }

  emptyForm(): LabResultRequest {
    return {
      labOrderId: '',
      assignmentId: this.activeAssignmentId ?? '',
      patientId: '',
      resultValue: '',
      resultUnit: '',
      resultDate: this.toLocalDatetime(new Date()),
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
      labOrderId: r.labOrderId ?? '',
      assignmentId: this.activeAssignmentId,
      patientId: r.patientId ?? '',
      resultValue: r.resultValue ?? '',
      resultUnit: r.resultUnit ?? '',
      resultDate: r.resultDate ? r.resultDate.slice(0, 16) : this.toLocalDatetime(new Date()),
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
      this.form.patientId = order.patientId ?? '';
    }
  }

  submitForm(): void {
    const selectedOrder = this.orders().find((o) => o.id === this.form.labOrderId);
    this.form.assignmentId = this.activeAssignmentId;
    this.form.patientId = selectedOrder?.patientId ?? this.form.patientId ?? '';
    if (!this.form.labOrderId || !this.form.assignmentId || !this.form.patientId) {
      this.toast.error(this.translate.instant('LAB_RESULTS.SAVE_VALIDATION_ERROR'));
      return;
    }
    this.saving.set(true);

    const id = this.editingId();
    const op =
      this.editing() && id
        ? this.labService.updateResult(id, this.form)
        : this.labService.createResult(this.form);

    op.subscribe({
      next: () => {
        this.toast.success(
          this.translate.instant(this.editing() ? 'LAB_RESULTS.UPDATED' : 'LAB_RESULTS.CREATED'),
        );
        this.closeModal();
        this.saving.set(false);
        this.loadResults();
      },
      error: () => {
        this.toast.error(this.translate.instant('LAB_RESULTS.SAVE_ERROR'));
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
        this.toast.success(this.translate.instant('LAB_RESULTS.DELETED'));
        this.cancelDelete();
        this.deleting.set(false);
        this.loadResults();
      },
      error: () => {
        this.toast.error(this.translate.instant('LAB_RESULTS.DELETE_ERROR'));
        this.deleting.set(false);
      },
    });
  }

  releaseResult(r: LabResultResponse): void {
    this.labService.releaseResult(r.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('LAB_RESULTS.RELEASED'));
        this.loadResults();
      },
      error: () => this.toast.error(this.translate.instant('LAB_RESULTS.RELEASE_ERROR')),
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
        this.toast.error(this.translate.instant('LAB_RESULTS.LOAD_ERROR'));
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

  getReleasedByDisplay(r: LabResultResponse): string {
    const actor = (r.releasedByFullName ?? '').trim();
    return actor || 'Autoverification';
  }

  sortedTrendHistory(r: LabResultResponse): LabResultTrendPoint[] {
    const history = r.trendHistory ?? [];
    return [...history].sort(
      (a, b) => new Date(b.resultDate).getTime() - new Date(a.resultDate).getTime(),
    );
  }

  formatReferenceRange(range: {
    minValue: number | null;
    maxValue: number | null;
    unit: string | null;
  }): string {
    const min = range.minValue;
    const max = range.maxValue;
    const unit = range.unit ? ` ${range.unit}` : '';

    if (min != null && max != null) {
      return `${min} – ${max}${unit}`;
    }
    if (min != null) {
      return `≥ ${min}${unit}`;
    }
    if (max != null) {
      return `≤ ${max}${unit}`;
    }
    return '—';
  }

  formatReferenceRangeAge(range: { ageMin?: number | null; ageMax?: number | null }): string {
    const ageMin = range.ageMin;
    const ageMax = range.ageMax;
    if (ageMin != null && ageMax != null) return `${ageMin}–${ageMax}`;
    if (ageMin != null) return `${ageMin}+`;
    if (ageMax != null) return `0–${ageMax}`;
    return 'All ages';
  }

  formatTrendDelta(sortedHistory: LabResultTrendPoint[], index: number): string {
    const current = Number(sortedHistory[index]?.resultValue);
    const previous = Number(sortedHistory[index + 1]?.resultValue);
    if (!Number.isFinite(current) || !Number.isFinite(previous)) return '—';
    const delta = current - previous;
    const sign = delta > 0 ? '+' : '';
    return `${sign}${delta.toFixed(2)}`;
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
