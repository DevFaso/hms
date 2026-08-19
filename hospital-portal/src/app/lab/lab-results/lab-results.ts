import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import {
  LabService,
  LabResultResponse,
  LabResultTrendPoint,
  LabResultRequest,
  LabOrderResponse,
  LabResultComparison,
} from '../../services/lab.service';
import { ToastService } from '../../core/toast.service';
import { ProfileService } from '../../services/profile.service';
import { RoleContextService } from '../../core/role-context.service';
import { AuthService } from '../../auth/auth.service';
import { HospitalScopeUrlService } from '../../core/hospital-scope-url.service';
import { HospitalScopeChipComponent } from '../../shared/hospital-scope-chip/hospital-scope-chip.component';

@Component({
  selector: 'app-lab-results',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule, HospitalScopeChipComponent],
  templateUrl: './lab-results.html',
  styleUrl: './lab-results.scss',
})
export class LabResultsComponent implements OnInit {
  private readonly labService = inject(LabService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly profileService = inject(ProfileService);
  private readonly roleContext = inject(RoleContextService);
  private readonly auth = inject(AuthService);
  private readonly route = inject(ActivatedRoute);
  private readonly scopeUrl = inject(HospitalScopeUrlService);

  /** Cross-tenant signals — drive the chip + Hospital column toggle. */
  protected readonly isSuperAdmin = this.roleContext.isSuperAdmin;
  protected readonly globalView = this.roleContext.globalView;

  loading = signal(true);
  results = signal<LabResultResponse[]>([]);
  filtered = signal<LabResultResponse[]>([]);
  searchTerm = '';
  activeTab = signal<'all' | 'released' | 'pending' | 'critical'>('all');

  stats = signal({ total: 0, released: 0, pending: 0 });

  /* ── Critical queue ── */
  criticalList = signal<LabResultResponse[]>([]);
  criticalLoading = signal(false);

  /* ── Sign ── */
  showSignModal = signal(false);
  signTarget = signal<LabResultResponse | null>(null);
  signForm = { signature: '', notes: '' };
  signSubmitting = signal(false);

  /* ── Comparison ── */
  comparison = signal<LabResultComparison | null>(null);
  comparisonLoading = signal(false);

  readonly canSign = this.roleContext.hasAnyActiveRole([
    'ROLE_DOCTOR',
    'ROLE_MIDWIFE',
    'ROLE_LAB_SCIENTIST',
  ]);
  /** GET /lab-results/hospital/{id}/critical/unacknowledged backend role list. */
  readonly canSeeCritical = this.roleContext.hasAnyActiveRole([
    'ROLE_DOCTOR',
    'ROLE_NURSE',
    'ROLE_MIDWIFE',
    'ROLE_LAB_SCIENTIST',
    'ROLE_LAB_DIRECTOR',
    'ROLE_QUALITY_MANAGER',
    'ROLE_HOSPITAL_ADMIN',
  ]);
  /** POST /{id}/acknowledge — intersection of @PreAuthorize and SecurityConfig matcher. */
  readonly canAcknowledge = this.roleContext.hasAnyActiveRole([
    'ROLE_DOCTOR',
    'ROLE_NURSE',
    'ROLE_MIDWIFE',
    'ROLE_LAB_SCIENTIST',
    'ROLE_LAB_MANAGER',
  ]);

  private hospitalId(): string | null {
    return this.roleContext.activeHospitalId ?? this.auth.getHospitalId();
  }

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
    // Cross-tenant: hydrate URL scope before the first list fetch so
    // the auth interceptor sends the right X-Hospital-Id (or omits it
    // for global view). See docs/super-admin-cross-tenant-design.md.
    this.scopeUrl.applyUrlScopeSync(this.route);

    this.loadResults();
    this.labService.listOrders({ size: 500 }).subscribe((list) => this.orders.set(list));
    this.profileService.getAssignments().subscribe({
      next: (assignments) => {
        const active = assignments.find((a: { active: boolean }) => a.active);
        if (active) this.activeAssignmentId = active.id;
      },
    });
  }

  /** Re-fetch under the new scope when the chip emits. */
  onScopeChange(_hospitalId: string | null): void {
    this.loadResults();
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

  setTab(tab: 'all' | 'released' | 'pending' | 'critical'): void {
    this.activeTab.set(tab);
    if (tab === 'critical') {
      this.loadCritical();
    }
    this.applyFilter();
  }

  loadCritical(): void {
    const hospitalId = this.hospitalId();
    if (!hospitalId || !this.canSeeCritical) return;
    this.criticalLoading.set(true);
    this.labService.criticalUnacknowledged(hospitalId).subscribe({
      next: (list) => {
        this.criticalList.set(list ?? []);
        this.criticalLoading.set(false);
        this.applyFilter();
      },
      error: () => {
        this.toast.error(this.translate.instant('LAB_RESULTS.CRITICAL_LOAD_ERROR'));
        this.criticalLoading.set(false);
      },
    });
  }

  applyFilter(): void {
    const tab = this.activeTab();
    let list = tab === 'critical' ? this.criticalList() : this.results();
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

  /* ── Sign / acknowledge / compare ── */

  openSign(r: LabResultResponse): void {
    this.signTarget.set(r);
    this.signForm = { signature: '', notes: '' };
    this.showSignModal.set(true);
  }

  closeSign(): void {
    this.showSignModal.set(false);
    this.signTarget.set(null);
  }

  submitSign(): void {
    const target = this.signTarget();
    if (!target) return;
    this.signSubmitting.set(true);
    this.labService
      .signResult(target.id, {
        signature: this.signForm.signature.trim() || undefined,
        notes: this.signForm.notes.trim() || undefined,
      })
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('LAB_RESULTS.SIGNED'));
          this.signSubmitting.set(false);
          this.closeSign();
          this.loadResults();
        },
        error: () => {
          this.toast.error(this.translate.instant('LAB_RESULTS.SIGN_ERROR'));
          this.signSubmitting.set(false);
        },
      });
  }

  acknowledge(r: LabResultResponse): void {
    this.labService.acknowledgeResult(r.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('LAB_RESULTS.ACKNOWLEDGED'));
        // Patch the row in place — no need to refetch 200 results for one flag.
        const patch = (list: LabResultResponse[]): LabResultResponse[] =>
          list.map((row) => (row.id === r.id ? { ...row, acknowledged: true } : row));
        this.results.update(patch);
        this.criticalList.update((list) => list.filter((row) => row.id !== r.id));
        this.applyFilter();
      },
      error: () => this.toast.error(this.translate.instant('LAB_RESULTS.ACKNOWLEDGE_ERROR')),
    });
  }

  openCompare(r: LabResultResponse): void {
    this.comparisonLoading.set(true);
    this.labService.compareResult(r.id).subscribe({
      next: (cmp) => {
        this.comparison.set(cmp);
        this.comparisonLoading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('LAB_RESULTS.COMPARE_ERROR'));
        this.comparisonLoading.set(false);
      },
    });
  }

  closeCompare(): void {
    this.comparison.set(null);
  }

  trendIcon(direction: string | null | undefined): string {
    switch (direction) {
      case 'INCREASING':
        return 'trending_up';
      case 'DECREASING':
        return 'trending_down';
      case 'STABLE':
        return 'trending_flat';
      case 'FLUCTUATING':
        return 'show_chart';
      default:
        return 'help';
    }
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
