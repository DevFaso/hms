import { Component, Input, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  AdvanceDirectiveRequest,
  AdvanceDirectiveResponse,
  AdvanceDirectiveService,
  AdvanceDirectiveType,
} from '../../services/advance-directive.service';
import { ToastService } from '../../core/toast.service';

/**
 * Advance-directives tab on the patient chart (P2 #13).
 *
 * First write surface for /advance-directives — the tables were read by the
 * storyboard banner and record sharing, and written by nothing a clinician
 * could reach. There is no delete anywhere: a directive that was once in
 * force is part of the record, so retirement is revoke, which stamps
 * lastReviewedAt.
 */
@Component({
  selector: 'app-advance-directives-tab',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './advance-directives-tab.component.html',
  styleUrl: './advance-directives-tab.component.scss',
})
export class AdvanceDirectivesTabComponent implements OnInit {
  @Input({ required: true }) patientId = '';

  private readonly directiveService = inject(AdvanceDirectiveService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  directives = signal<AdvanceDirectiveResponse[]>([]);
  loading = signal(true);
  error = signal<string | null>(null);

  /** REVOKED / EXPIRED rows accumulate forever by design — hidden by default. */
  showInactive = signal(false);

  visible = computed(() => {
    const all = this.directives();
    if (this.showInactive()) {
      return all;
    }
    return all.filter((d) => d.status !== 'REVOKED' && d.status !== 'EXPIRED');
  });

  readonly directiveTypes: AdvanceDirectiveType[] = [
    'DO_NOT_RESUSCITATE',
    'LIVING_WILL',
    'DURABLE_POWER_OF_ATTORNEY',
    'PHYSICIAN_ORDERS_FOR_LIFE_SUSTAINING_TREATMENT',
    'OTHER',
  ];

  /**
   * REVOKED is deliberately not offered — revoke has its own endpoint that
   * also stamps lastReviewedAt. EXPIRED is a statement about dates, not a
   * choice.
   */
  readonly editableStatuses = ['ACTIVE', 'PENDING'] as const;

  modalOpen = signal(false);
  editingId = signal<string | null>(null);
  saving = signal(false);
  form: AdvanceDirectiveRequest = this.emptyForm();

  revokeTarget = signal<AdvanceDirectiveResponse | null>(null);
  revoking = signal(false);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.directiveService.listForPatient(this.patientId).subscribe({
      next: (list) => {
        this.directives.set(list);
        this.loading.set(false);
      },
      error: (err) => {
        console.error('Failed to load advance directives', err);
        this.error.set(this.translate.instant('DIRECTIVES.LOAD_ERROR'));
        this.loading.set(false);
      },
    });
  }

  openCreate(): void {
    this.form = this.emptyForm();
    this.editingId.set(null);
    this.modalOpen.set(true);
  }

  openEdit(directive: AdvanceDirectiveResponse): void {
    this.form = {
      directiveType: directive.directiveType,
      status: directive.status,
      description: directive.description,
      effectiveDate: directive.effectiveDate,
      expirationDate: directive.expirationDate,
      witnessName: directive.witnessName,
      physicianName: directive.physicianName,
      documentLocation: directive.documentLocation,
      sourceSystem: directive.sourceSystem,
    };
    this.editingId.set(directive.id);
    this.modalOpen.set(true);
  }

  closeModal(): void {
    this.modalOpen.set(false);
    this.editingId.set(null);
    this.form = this.emptyForm();
  }

  submit(): void {
    if (!this.form.directiveType) {
      this.toast.error(this.translate.instant('DIRECTIVES.TYPE_REQUIRED'));
      return;
    }
    // Mirrors the backend rule ("cannot expire before it takes effect");
    // equal dates are allowed there, so they are allowed here.
    if (
      this.form.effectiveDate &&
      this.form.expirationDate &&
      this.form.expirationDate < this.form.effectiveDate
    ) {
      this.toast.error(this.translate.instant('DIRECTIVES.DATES_INVALID'));
      return;
    }

    this.saving.set(true);
    const id = this.editingId();
    const request$ = id
      ? this.directiveService.update(id, this.form)
      : this.directiveService.create(this.patientId, this.form);

    request$.subscribe({
      next: () => {
        this.toast.success(this.translate.instant('DIRECTIVES.SAVED'));
        this.saving.set(false);
        this.closeModal();
        this.load();
      },
      error: (err) => {
        // The backend refusals are actionable ("cannot expire before it takes
        // effect", foreign patient reads as not-found) — surface them verbatim.
        this.toast.error(err?.error?.message ?? this.translate.instant('DIRECTIVES.SAVE_ERROR'));
        this.saving.set(false);
      },
    });
  }

  confirmRevoke(directive: AdvanceDirectiveResponse): void {
    this.revokeTarget.set(directive);
  }

  cancelRevoke(): void {
    this.revokeTarget.set(null);
  }

  executeRevoke(): void {
    const target = this.revokeTarget();
    if (!target || this.revoking()) {
      return;
    }
    this.revoking.set(true);
    this.directiveService.revoke(target.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('DIRECTIVES.REVOKED_TOAST'));
        this.revoking.set(false);
        this.revokeTarget.set(null);
        this.load();
      },
      error: (err) => {
        this.toast.error(err?.error?.message ?? this.translate.instant('DIRECTIVES.REVOKE_ERROR'));
        this.revoking.set(false);
      },
    });
  }

  private emptyForm(): AdvanceDirectiveRequest {
    return {
      directiveType: 'DO_NOT_RESUSCITATE',
      status: 'ACTIVE',
    };
  }
}
