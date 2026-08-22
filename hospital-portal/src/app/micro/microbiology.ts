import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { LabOrderResponse, LabService } from '../services/lab.service';
import {
  GROWTH_RESULTS,
  INTERPRETATIONS,
  MicroCultureResponse,
  MicroCultureStatus,
  MicroGrowthResult,
  MicroService,
  MicroSusceptibilityInterpretation,
  MicroSusceptibilityMethod,
  SUSCEPTIBILITY_METHODS,
} from '../services/micro.service';

/**
 * Microbiology workbench (P3 #19) — the lab-facing resulting surface:
 * create a culture on a lab order, record isolates and susceptibility rows,
 * finalize. A FINAL report only changes with a correction reason and shows
 * as CORRECTED afterwards; the panel enforces nothing silently — backend
 * refusals surface verbatim.
 */
@Component({
  selector: 'app-microbiology',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './microbiology.html',
  styleUrl: './microbiology.scss',
})
export class MicrobiologyComponent implements OnInit {
  private readonly microService = inject(MicroService);
  private readonly labService = inject(LabService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly roleContext = inject(RoleContextService);

  readonly growthResults = GROWTH_RESULTS;
  readonly methods = SUSCEPTIBILITY_METHODS;
  readonly interpretations = INTERPRETATIONS;

  readonly loading = signal(true);
  readonly error = signal('');
  readonly cultures = signal<MicroCultureResponse[]>([]);
  readonly statusFilter = signal<MicroCultureStatus | ''>('');
  readonly selected = signal<MicroCultureResponse | null>(null);

  /** Correction reason for mutations on a FINAL/CORRECTED report. */
  readonly correctionReason = signal('');

  /* Create-culture modal */
  readonly showCreateModal = signal(false);
  readonly creating = signal(false);
  readonly orders = signal<LabOrderResponse[]>([]);
  readonly ordersLoading = signal(false);
  readonly formOrderId = signal('');
  readonly formSpecimenSource = signal('');
  readonly formCollectedAt = signal('');
  readonly formGramStain = signal('');
  readonly formNotes = signal('');

  /* Culture edit (detail panel) */
  readonly editGrowthResult = signal<MicroGrowthResult | ''>('');
  readonly editGramStain = signal('');
  readonly editNotes = signal('');
  readonly savingCulture = signal(false);

  /* Isolate modal */
  readonly showIsolateModal = signal(false);
  readonly savingIsolate = signal(false);
  readonly isoOrganism = signal('');
  readonly isoCode = signal('');
  readonly isoQuantity = signal('');
  readonly isoNotes = signal('');

  /* Susceptibility modal (per isolate) */
  readonly showSuscModal = signal(false);
  readonly savingSusc = signal(false);
  readonly suscIsolateId = signal('');
  readonly suscAntibiotic = signal('');
  readonly suscMethod = signal<MicroSusceptibilityMethod | ''>('');
  readonly suscMic = signal('');
  readonly suscInterpretation = signal<MicroSusceptibilityInterpretation | ''>('');
  readonly suscNotes = signal('');

  readonly finalizing = signal(false);

  readonly selectedLocked = computed(() => {
    const culture = this.selected();
    return culture !== null && culture.status !== 'PRELIMINARY';
  });

  /** Mirrors MicroCultureController.ENTRY_ROLES exactly. */
  canResult(): boolean {
    return this.roleContext.hasAnyActiveRole([
      'ROLE_LAB_SCIENTIST',
      'ROLE_LAB_TECHNICIAN',
      'ROLE_LAB_MANAGER',
      'ROLE_LAB_DIRECTOR',
      'ROLE_QUALITY_MANAGER',
      'ROLE_DOCTOR',
      'ROLE_NURSE',
      'ROLE_MIDWIFE',
      'ROLE_SUPER_ADMIN',
    ]);
  }

  /** Mirrors MicroCultureController.FINALIZE_ROLES exactly. */
  canFinalize(): boolean {
    return this.roleContext.hasAnyActiveRole([
      'ROLE_LAB_SCIENTIST',
      'ROLE_LAB_MANAGER',
      'ROLE_LAB_DIRECTOR',
      'ROLE_SUPER_ADMIN',
    ]);
  }

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set('');
    const status = this.statusFilter();
    this.microService.list({ status: status || undefined, size: 100 }).subscribe({
      next: (page) => {
        this.cultures.set(page.content);
        this.loading.set(false);
        const current = this.selected();
        if (current) {
          this.selected.set(page.content.find((c) => c.id === current.id) ?? null);
        }
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? this.translate.instant('MICRO.LOAD_FAILED'));
        this.loading.set(false);
      },
    });
  }

  setFilter(status: MicroCultureStatus | ''): void {
    this.statusFilter.set(status);
    this.load();
  }

  select(culture: MicroCultureResponse): void {
    this.selected.set(culture);
    this.correctionReason.set('');
    this.editGrowthResult.set(culture.growthResult ?? '');
    this.editGramStain.set(culture.gramStain ?? '');
    this.editNotes.set(culture.notes ?? '');
  }

  closeDetail(): void {
    this.selected.set(null);
  }

  /** The selected report is locked and no correction reason is present yet. */
  private missingCorrectionReason(): boolean {
    if (this.selectedLocked() && !this.correctionReason().trim()) {
      this.toast.error(this.translate.instant('MICRO.CORRECTION_REASON_REQUIRED'));
      return true;
    }
    return false;
  }

  private reasonOrUndefined(): string | undefined {
    const reason = this.correctionReason().trim();
    return reason ? reason : undefined;
  }

  private applyUpdated(updated: MicroCultureResponse): void {
    this.selected.set(updated);
    this.correctionReason.set('');
    this.cultures.set(this.cultures().map((c) => (c.id === updated.id ? updated : c)));
  }

  /* ── Create culture ────────────────────────────────────────────────── */

  openCreateModal(): void {
    this.formOrderId.set('');
    this.formSpecimenSource.set('');
    this.formCollectedAt.set('');
    this.formGramStain.set('');
    this.formNotes.set('');
    this.showCreateModal.set(true);
    this.ordersLoading.set(true);
    this.labService.listOrders({ size: 100 }).subscribe({
      next: (orders) => {
        this.orders.set(orders);
        this.ordersLoading.set(false);
      },
      error: () => {
        this.orders.set([]);
        this.ordersLoading.set(false);
        this.toast.error(this.translate.instant('MICRO.ORDERS_LOAD_FAILED'));
      },
    });
  }

  closeCreateModal(): void {
    if (this.creating()) return;
    this.showCreateModal.set(false);
  }

  submitCreate(): void {
    if (!this.formOrderId()) {
      this.toast.error(this.translate.instant('MICRO.ORDER_REQUIRED'));
      return;
    }
    if (this.creating()) return;
    this.creating.set(true);
    this.microService
      .create({
        labOrderId: this.formOrderId(),
        specimenSource: this.formSpecimenSource() || undefined,
        collectedAt: this.formCollectedAt() || undefined,
        gramStain: this.formGramStain() || undefined,
        notes: this.formNotes() || undefined,
      })
      .subscribe({
        next: (created) => {
          this.creating.set(false);
          this.showCreateModal.set(false);
          this.toast.success(this.translate.instant('MICRO.CULTURE_CREATED'));
          this.load();
          this.select(created);
        },
        error: (err) => {
          this.creating.set(false);
          this.toast.error(err?.error?.message ?? this.translate.instant('MICRO.SAVE_FAILED'));
        },
      });
  }

  /* ── Culture edit + finalize ───────────────────────────────────────── */

  saveCulture(): void {
    const culture = this.selected();
    if (!culture || this.savingCulture() || this.missingCorrectionReason()) return;
    this.savingCulture.set(true);
    this.microService
      .update(culture.id, {
        growthResult: this.editGrowthResult() || undefined,
        gramStain: this.editGramStain() || undefined,
        notes: this.editNotes() || undefined,
        correctionReason: this.reasonOrUndefined(),
      })
      .subscribe({
        next: (updated) => {
          this.savingCulture.set(false);
          this.toast.success(this.translate.instant('MICRO.CULTURE_SAVED'));
          this.applyUpdated(updated);
        },
        error: (err) => {
          this.savingCulture.set(false);
          this.toast.error(err?.error?.message ?? this.translate.instant('MICRO.SAVE_FAILED'));
        },
      });
  }

  finalize(): void {
    const culture = this.selected();
    if (!culture || this.finalizing()) return;
    this.finalizing.set(true);
    this.microService.finalize(culture.id).subscribe({
      next: (updated) => {
        this.finalizing.set(false);
        this.toast.success(this.translate.instant('MICRO.FINALIZED'));
        this.applyUpdated(updated);
      },
      error: (err) => {
        this.finalizing.set(false);
        this.toast.error(err?.error?.message ?? this.translate.instant('MICRO.SAVE_FAILED'));
      },
    });
  }

  /* ── Isolates ──────────────────────────────────────────────────────── */

  openIsolateModal(): void {
    if (this.missingCorrectionReason()) return;
    this.isoOrganism.set('');
    this.isoCode.set('');
    this.isoQuantity.set('');
    this.isoNotes.set('');
    this.showIsolateModal.set(true);
  }

  closeIsolateModal(): void {
    if (this.savingIsolate()) return;
    this.showIsolateModal.set(false);
  }

  submitIsolate(): void {
    const culture = this.selected();
    if (!culture) return;
    if (!this.isoOrganism().trim()) {
      this.toast.error(this.translate.instant('MICRO.ORGANISM_REQUIRED'));
      return;
    }
    if (this.savingIsolate()) return;
    this.savingIsolate.set(true);
    this.microService
      .addIsolate(culture.id, {
        organismName: this.isoOrganism().trim(),
        organismCode: this.isoCode() || undefined,
        growthQuantity: this.isoQuantity() || undefined,
        notes: this.isoNotes() || undefined,
        correctionReason: this.reasonOrUndefined(),
      })
      .subscribe({
        next: (updated) => {
          this.savingIsolate.set(false);
          this.showIsolateModal.set(false);
          this.toast.success(this.translate.instant('MICRO.ISOLATE_ADDED'));
          this.applyUpdated(updated);
        },
        error: (err) => {
          this.savingIsolate.set(false);
          this.toast.error(err?.error?.message ?? this.translate.instant('MICRO.SAVE_FAILED'));
        },
      });
  }

  deleteIsolate(isolateId: string): void {
    const culture = this.selected();
    if (!culture || this.missingCorrectionReason()) return;
    this.microService.deleteIsolate(culture.id, isolateId, this.reasonOrUndefined()).subscribe({
      next: (updated) => {
        this.toast.success(this.translate.instant('MICRO.ISOLATE_REMOVED'));
        this.applyUpdated(updated);
      },
      error: (err) => {
        this.toast.error(err?.error?.message ?? this.translate.instant('MICRO.SAVE_FAILED'));
      },
    });
  }

  /* ── Susceptibilities ──────────────────────────────────────────────── */

  openSuscModal(isolateId: string): void {
    if (this.missingCorrectionReason()) return;
    this.suscIsolateId.set(isolateId);
    this.suscAntibiotic.set('');
    this.suscMethod.set('');
    this.suscMic.set('');
    this.suscInterpretation.set('');
    this.suscNotes.set('');
    this.showSuscModal.set(true);
  }

  closeSuscModal(): void {
    if (this.savingSusc()) return;
    this.showSuscModal.set(false);
  }

  submitSusc(): void {
    const culture = this.selected();
    if (!culture) return;
    if (!this.suscAntibiotic().trim()) {
      this.toast.error(this.translate.instant('MICRO.ANTIBIOTIC_REQUIRED'));
      return;
    }
    const interpretation = this.suscInterpretation();
    if (!interpretation) {
      this.toast.error(this.translate.instant('MICRO.INTERPRETATION_REQUIRED'));
      return;
    }
    if (this.savingSusc()) return;
    this.savingSusc.set(true);
    this.microService
      .addSusceptibility(culture.id, this.suscIsolateId(), {
        antibioticName: this.suscAntibiotic().trim(),
        method: this.suscMethod() || undefined,
        micValue: this.suscMic() || undefined,
        interpretation,
        notes: this.suscNotes() || undefined,
        correctionReason: this.reasonOrUndefined(),
      })
      .subscribe({
        next: (updated) => {
          this.savingSusc.set(false);
          this.showSuscModal.set(false);
          this.toast.success(this.translate.instant('MICRO.SUSCEPTIBILITY_ADDED'));
          this.applyUpdated(updated);
        },
        error: (err) => {
          this.savingSusc.set(false);
          this.toast.error(err?.error?.message ?? this.translate.instant('MICRO.SAVE_FAILED'));
        },
      });
  }

  deleteSusceptibility(isolateId: string, susceptibilityId: string): void {
    const culture = this.selected();
    if (!culture || this.missingCorrectionReason()) return;
    this.microService
      .deleteSusceptibility(culture.id, isolateId, susceptibilityId, this.reasonOrUndefined())
      .subscribe({
        next: (updated) => {
          this.toast.success(this.translate.instant('MICRO.SUSCEPTIBILITY_REMOVED'));
          this.applyUpdated(updated);
        },
        error: (err) => {
          this.toast.error(err?.error?.message ?? this.translate.instant('MICRO.SAVE_FAILED'));
        },
      });
  }

  /* ── Labels ────────────────────────────────────────────────────────── */

  statusLabel(status: MicroCultureStatus): string {
    return this.translate.instant(`MICRO.STATUS_${status}`);
  }

  growthLabel(growth: MicroGrowthResult | null): string {
    return this.translate.instant(growth ? `MICRO.GROWTH_${growth}` : 'MICRO.GROWTH_PENDING');
  }

  methodLabel(method: MicroSusceptibilityMethod | null): string {
    return method ? this.translate.instant(`MICRO.METHOD_${method}`) : '—';
  }

  interpLabel(interpretation: MicroSusceptibilityInterpretation): string {
    return this.translate.instant(`MICRO.INTERP_${interpretation}`);
  }

  orderLabel(order: LabOrderResponse): string {
    const patient = order.patientFullName || '—';
    const test = order.labTestName || order.labTestCode || '—';
    return `${patient} — ${test}`;
  }
}
