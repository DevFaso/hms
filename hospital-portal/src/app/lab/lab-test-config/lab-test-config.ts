import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import {
  LabService,
  LabTestDefinition,
  LabTestDefinitionRequest,
  LabTestReferenceRange,
} from '../../services/lab.service';
import { ToastService } from '../../core/toast.service';
import { ProfileService } from '../../services/profile.service';

@Component({
  selector: 'app-lab-test-config',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './lab-test-config.html',
  styleUrl: './lab-test-config.scss',
})
export class LabTestConfigComponent implements OnInit {
  private readonly labService = inject(LabService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly profileService = inject(ProfileService);

  loading = signal(true);
  error = signal<string | null>(null);
  definitions = signal<LabTestDefinition[]>([]);
  totalElements = signal(0);
  searchKeyword = signal('');
  page = signal(0);
  readonly pageSize = 20;

  /** Reference-range editor modal state */
  editingDef = signal<LabTestDefinition | null>(null);
  editRanges = signal<LabTestReferenceRange[]>([]);
  saving = signal(false);

  /** CRUD modal state */
  showDefModal = signal(false);
  editingDefCrud = signal(false);
  editingDefId = signal<string | null>(null);
  savingDef = signal(false);
  defForm: LabTestDefinitionRequest = this.emptyDefForm();

  /** Delete confirm */
  showDeleteDefConfirm = signal(false);
  deletingDef = signal<LabTestDefinition | null>(null);
  deletingDefInProgress = signal(false);

  private activeAssignmentId = '';

  ngOnInit(): void {
    this.loadDefinitions();
    this.profileService.getAssignments().subscribe({
      next: (assignments) => {
        const active = assignments.find((a: { active: boolean }) => a.active);
        if (active) this.activeAssignmentId = active.id;
      },
    });
  }

  emptyDefForm(): LabTestDefinitionRequest {
    return {
      testCode: '',
      testName: '',
      category: '',
      description: '',
      unit: '',
      sampleType: '',
      preparationInstructions: '',
      turnaroundTime: undefined,
      isActive: true,
      assignmentId: this.activeAssignmentId || undefined,
      referenceRanges: [],
    };
  }

  loadDefinitions(): void {
    this.loading.set(true);
    this.error.set(null);

    this.labService
      .searchTestDefinitions({
        keyword: this.searchKeyword() || undefined,
        page: this.page(),
        size: this.pageSize,
      })
      .subscribe({
        next: (res) => {
          this.definitions.set(res.content);
          this.totalElements.set(res.totalElements);
          this.loading.set(false);
        },
        error: (err) => {
          console.error('Failed to load test definitions', err);
          this.error.set(this.translate.instant('LAB_TEST_CONFIG.LOAD_ERROR'));
          this.loading.set(false);
        },
      });
  }

  onSearch(): void {
    this.page.set(0);
    this.loadDefinitions();
  }

  nextPage(): void {
    this.page.update((p) => p + 1);
    this.loadDefinitions();
  }

  prevPage(): void {
    this.page.update((p) => Math.max(0, p - 1));
    this.loadDefinitions();
  }

  totalPages(): number {
    return Math.ceil(this.totalElements() / this.pageSize) || 1;
  }

  /** Open the reference-ranges editor modal */
  openRangeEditor(def: LabTestDefinition): void {
    this.editingDef.set(def);
    const ranges = def.referenceRanges ?? [];
    this.editRanges.set(ranges.map((r) => ({ ...r })));
  }

  closeRangeEditor(): void {
    this.editingDef.set(null);
    this.editRanges.set([]);
  }

  addRange(): void {
    this.editRanges.update((list) => [
      ...list,
      {
        minValue: null,
        maxValue: null,
        unit: null,
        ageMin: null,
        ageMax: null,
        gender: 'ALL',
        notes: null,
      },
    ]);
  }

  removeRange(index: number): void {
    this.editRanges.update((list) => list.filter((_, i) => i !== index));
  }

  saveRanges(): void {
    const def = this.editingDef();
    if (!def) return;

    this.saving.set(true);
    this.labService.updateReferenceRanges(def.id, this.editRanges()).subscribe({
      next: (updated) => {
        this.definitions.update((list) => list.map((d) => (d.id === updated.id ? updated : d)));
        this.toast.success(this.translate.instant('LAB_TEST_CONFIG.RANGES_SAVED'));
        this.saving.set(false);
        this.closeRangeEditor();
      },
      error: (err) => {
        console.error('Failed to save reference ranges', err);
        this.toast.error(
          err?.error?.message ?? this.translate.instant('LAB_TEST_CONFIG.SAVE_ERROR'),
        );
        this.saving.set(false);
      },
    });
  }

  exportCsv(): void {
    this.labService.exportTestDefinitionsCsv().subscribe({
      next: (blob) => this.downloadBlob(blob, 'lab-test-definitions.csv'),
      error: () => this.toast.error(this.translate.instant('LAB_TEST_CONFIG.EXPORT_ERROR')),
    });
  }

  exportPdf(): void {
    this.labService.exportTestDefinitionsPdf().subscribe({
      next: (blob) => this.downloadBlob(blob, 'lab-test-definitions.pdf'),
      error: () => this.toast.error(this.translate.instant('LAB_TEST_CONFIG.EXPORT_ERROR')),
    });
  }

  private downloadBlob(blob: Blob, filename: string): void {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    setTimeout(() => {
      a.remove();
      URL.revokeObjectURL(url);
    }, 0);
  }

  // ── Test Definition CRUD ─────────────────────────────────────────────

  openCreateDef(): void {
    this.defForm = this.emptyDefForm();
    this.defForm.assignmentId = this.activeAssignmentId || undefined;
    this.editingDefCrud.set(false);
    this.editingDefId.set(null);
    this.showDefModal.set(true);
  }

  openEditDef(def: LabTestDefinition): void {
    this.defForm = {
      testCode: def.testCode,
      testName: def.testName,
      category: def.category ?? '',
      description: def.description ?? '',
      unit: def.unit ?? '',
      sampleType: def.sampleType ?? '',
      preparationInstructions: def.preparationInstructions ?? '',
      turnaroundTime: def.turnaroundTime ?? undefined,
      isActive: def.isActive,
      assignmentId: this.activeAssignmentId || undefined,
      referenceRanges: def.referenceRanges ? def.referenceRanges.map((r) => ({ ...r })) : [],
    };
    this.editingDefCrud.set(true);
    this.editingDefId.set(def.id);
    this.showDefModal.set(true);
  }

  closeDefModal(): void {
    this.showDefModal.set(false);
  }

  submitDefForm(): void {
    this.savingDef.set(true);
    const op = this.editingDefCrud()
      ? this.labService.updateTestDefinition(this.editingDefId()!, this.defForm)
      : this.labService.createTestDefinition(this.defForm);
    op.subscribe({
      next: () => {
        this.toast.success(
          this.editingDefCrud()
            ? this.translate.instant('LAB_TEST_CONFIG.DEF_UPDATED')
            : this.translate.instant('LAB_TEST_CONFIG.DEF_CREATED'),
        );
        this.closeDefModal();
        this.savingDef.set(false);
        this.loadDefinitions();
      },
      error: (err) => {
        this.toast.error(
          err?.error?.message ?? this.translate.instant('LAB_TEST_CONFIG.DEF_SAVE_ERROR'),
        );
        this.savingDef.set(false);
      },
    });
  }

  confirmDeleteDef(def: LabTestDefinition): void {
    this.deletingDef.set(def);
    this.showDeleteDefConfirm.set(true);
  }

  cancelDeleteDef(): void {
    this.showDeleteDefConfirm.set(false);
    this.deletingDef.set(null);
  }

  executeDeleteDef(): void {
    this.deletingDefInProgress.set(true);
    this.labService.deleteTestDefinition(this.deletingDef()!.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('LAB_TEST_CONFIG.DEF_DELETED'));
        this.cancelDeleteDef();
        this.deletingDefInProgress.set(false);
        this.loadDefinitions();
      },
      error: (err) => {
        this.toast.error(
          err?.error?.message ?? this.translate.instant('LAB_TEST_CONFIG.DELETE_ERROR'),
        );
        this.deletingDefInProgress.set(false);
      },
    });
  }

  statusClass(status: string): string {
    switch (status) {
      case 'ACTIVE':
        return 'badge-active';
      case 'APPROVED':
        return 'badge-approved';
      case 'DRAFT':
        return 'badge-draft';
      case 'REJECTED':
        return 'badge-rejected';
      case 'RETIRED':
        return 'badge-retired';
      default:
        return 'badge-pending';
    }
  }
}
