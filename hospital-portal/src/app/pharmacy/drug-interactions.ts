import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { DrugInteractionEntry, DrugInteractionService } from '../services/drug-interaction.service';
import { InteractionSeverity } from '../services/medication-timeline.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';

/**
 * Drug-interaction KB curation page (P2 #14).
 *
 * First caller of the /drug-interactions admin API. Two rules shape the UI:
 * (1) the KB is PLATFORM-GLOBAL — every edit lands on every hospital in the
 * deployment, and the copy says so; (2) codes must be RxNorm RxCUIs (numeric),
 * because the CDS-Hooks layer joins on rxnormCode with exact equality — a
 * free-text code produces a row that silently never fires there.
 */
@Component({
  selector: 'app-drug-interactions',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './drug-interactions.html',
  styleUrl: './drug-interactions.scss',
})
export class DrugInteractionsComponent implements OnInit {
  /** Mirrors TerminologyCodes.isValidRxNorm / medication-catalog's RXNORM_PATTERN. */
  private static readonly RXNORM_PATTERN = /^\d{1,12}$/;

  private readonly interactionService = inject(DrugInteractionService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly roleContext = inject(RoleContextService);

  items = signal<DrugInteractionEntry[]>([]);
  loading = signal(true);
  error = signal<string | null>(null);

  searchTerm = signal('');
  severityFilter = signal<InteractionSeverity | ''>('');
  showInactive = signal(false);

  readonly severities: InteractionSeverity[] = [
    'CONTRAINDICATED',
    'MAJOR',
    'MODERATE',
    'MINOR',
    'UNKNOWN',
  ];

  /** Mirrors DrugInteractionAdminController.WRITE_ROLES. */
  canManage = computed(() =>
    this.roleContext.hasAnyActiveRole([
      'ROLE_PHARMACIST',
      'ROLE_HOSPITAL_ADMIN',
      'ROLE_SUPER_ADMIN',
    ]),
  );

  filtered = computed(() => {
    const term = this.searchTerm().trim().toLowerCase();
    if (!term) {
      return this.items();
    }
    return this.items().filter(
      (i) =>
        i.drug1Name.toLowerCase().includes(term) ||
        i.drug2Name.toLowerCase().includes(term) ||
        i.drug1Code.includes(term) ||
        i.drug2Code.includes(term),
    );
  });

  modalOpen = signal(false);
  editingId = signal<string | null>(null);
  saving = signal(false);
  form: DrugInteractionEntry = this.emptyForm();

  deactivateTarget = signal<DrugInteractionEntry | null>(null);
  deactivating = signal(false);
  reactivatingId = signal<string | null>(null);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.interactionService.list(this.severityFilter(), !this.showInactive()).subscribe({
      next: (rows) => {
        this.items.set(rows);
        this.loading.set(false);
      },
      error: (err) => {
        console.error('Failed to load drug interactions', err);
        this.error.set(this.translate.instant('DRUG_INTERACTIONS.LOAD_ERROR'));
        this.loading.set(false);
      },
    });
  }

  setSeverityFilter(value: InteractionSeverity | ''): void {
    this.severityFilter.set(value);
    this.load();
  }

  setShowInactive(value: boolean): void {
    this.showInactive.set(value);
    this.load();
  }

  severityClass(severity: InteractionSeverity): string {
    // Same visual convention as the medication-history timeline.
    switch (severity) {
      case 'CONTRAINDICATED':
      case 'MAJOR':
        return 'sev-badge sev-high';
      case 'MODERATE':
        return 'sev-badge sev-mid';
      default:
        return 'sev-badge sev-low';
    }
  }

  /** Preview of the server-derived action flags — the form must not offer them as choices. */
  derivedFlags(severity: InteractionSeverity): { avoid: boolean; adjust: boolean } {
    return {
      avoid: severity === 'CONTRAINDICATED',
      adjust: severity === 'CONTRAINDICATED' || severity === 'MAJOR',
    };
  }

  isRxCuiValid(code: string): boolean {
    return DrugInteractionsComponent.RXNORM_PATTERN.test(code.trim());
  }

  openCreate(): void {
    this.form = this.emptyForm();
    this.editingId.set(null);
    this.modalOpen.set(true);
  }

  openEdit(entry: DrugInteractionEntry): void {
    this.form = { ...entry };
    this.editingId.set(entry.id ?? null);
    this.modalOpen.set(true);
  }

  closeModal(): void {
    this.modalOpen.set(false);
    this.editingId.set(null);
    this.form = this.emptyForm();
  }

  submit(): void {
    if (!this.form.drug1Name.trim() || !this.form.drug2Name.trim()) {
      this.toast.error(this.translate.instant('DRUG_INTERACTIONS.NAMES_REQUIRED'));
      return;
    }
    if (!this.isRxCuiValid(this.form.drug1Code) || !this.isRxCuiValid(this.form.drug2Code)) {
      // Hard stop, not a warning: a non-RxCUI code creates a row that fires in
      // some checking layers and silently never in others.
      this.toast.error(this.translate.instant('DRUG_INTERACTIONS.CODE_INVALID'));
      return;
    }
    if (!this.form.recommendation.trim()) {
      this.toast.error(this.translate.instant('DRUG_INTERACTIONS.RECOMMENDATION_REQUIRED'));
      return;
    }

    this.saving.set(true);
    const id = this.editingId();
    const request$ = id
      ? this.interactionService.update(id, this.form)
      : this.interactionService.create(this.form);

    request$.subscribe({
      next: () => {
        this.toast.success(this.translate.instant('DRUG_INTERACTIONS.SAVED'));
        this.saving.set(false);
        this.closeModal();
        this.load();
      },
      error: (err) => {
        // "exists but was retired; reactivate it instead" is actionable —
        // surface the backend refusal verbatim.
        this.toast.error(
          err?.error?.message ?? this.translate.instant('DRUG_INTERACTIONS.SAVE_ERROR'),
        );
        this.saving.set(false);
      },
    });
  }

  confirmDeactivate(entry: DrugInteractionEntry): void {
    this.deactivateTarget.set(entry);
  }

  cancelDeactivate(): void {
    this.deactivateTarget.set(null);
  }

  executeDeactivate(): void {
    const target = this.deactivateTarget();
    if (!target?.id || this.deactivating()) {
      return;
    }
    this.deactivating.set(true);
    this.interactionService.deactivate(target.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('DRUG_INTERACTIONS.DEACTIVATED'));
        this.deactivating.set(false);
        this.deactivateTarget.set(null);
        this.load();
      },
      error: (err) => {
        this.toast.error(
          err?.error?.message ?? this.translate.instant('DRUG_INTERACTIONS.DEACTIVATE_ERROR'),
        );
        this.deactivating.set(false);
      },
    });
  }

  reactivate(entry: DrugInteractionEntry): void {
    if (!entry.id || this.reactivatingId()) {
      return;
    }
    // Guard set BEFORE dispatch — the #443 lesson.
    this.reactivatingId.set(entry.id);
    this.interactionService.reactivate(entry.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('DRUG_INTERACTIONS.REACTIVATED'));
        this.reactivatingId.set(null);
        this.load();
      },
      error: (err) => {
        this.toast.error(
          err?.error?.message ?? this.translate.instant('DRUG_INTERACTIONS.REACTIVATE_ERROR'),
        );
        this.reactivatingId.set(null);
      },
    });
  }

  private emptyForm(): DrugInteractionEntry {
    return {
      drug1Code: '',
      drug1Name: '',
      drug2Code: '',
      drug2Name: '',
      severity: 'MAJOR',
      recommendation: '',
    };
  }
}
