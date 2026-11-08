import {
  Component,
  ElementRef,
  Input,
  OnInit,
  computed,
  inject,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  AdvanceDirectiveRequest,
  AdvanceDirectiveResponse,
  AdvanceDirectiveService,
  AdvanceDirectiveStatus,
  AdvanceDirectiveType,
} from '../../services/advance-directive.service';
import { ToastService } from '../../core/toast.service';
import { blankFields, refuseFields } from '../../shared/forms/invalid-fields';
import { refusalReason, refusedFieldReasons } from '../../shared/refusal-reason';
import { missingRequiredMessage } from '../../shared/required-fields';
import { SubmitGuardDirective } from '../../shared/forms/submit-guard.directive';
import { PatientChartChanges } from '../../interceptors/patient-chart-change.interceptor';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { ModalDialogDirective } from '../../shared/a11y/modal-dialog.directive';
import { ReasonDialogComponent } from '../../shared/reason-dialog/reason-dialog.component';

/**
 * Advance-directives tab on the patient chart (P2 #13).
 *
 * First write surface for /advance-directives — the tables were read by the
 * storyboard banner and record sharing, and written by nothing a clinician
 * could reach. There is no delete anywhere: a directive that was once in
 * force is part of the record, so retirement is revoke, which stamps
 * lastReviewedAt.
 */
/**
 * The directive form. QA D1148: nothing is pre-filled — one click on "Save"
 * recorded an ACTIVE "Do not resuscitate" from the defaults. The clinician
 * picks the type and the status.
 */
export type DirectiveForm = Omit<AdvanceDirectiveRequest, 'directiveType' | 'status'> & {
  directiveType: AdvanceDirectiveType | '';
  status: AdvanceDirectiveStatus | '';
};

/** Physician orders (a DNR, a POLST) name the physician who gave them. */
const PHYSICIAN_ORDER_TYPES: ReadonlySet<string> = new Set([
  'DO_NOT_RESUSCITATE',
  'PHYSICIAN_ORDERS_FOR_LIFE_SUSTAINING_TREATMENT',
]);

const DIRECTIVE_FIELD_IDS = [
  'directive-type',
  'directive-status',
  'directive-effective',
  'directive-physician',
  'directive-expiration',
];

/** The form control of each field the server may name under fieldErrors. */
const DIRECTIVE_ID_BY_FIELD: Readonly<Record<string, string>> = {
  directiveType: 'directive-type',
  status: 'directive-status',
  effectiveDate: 'directive-effective',
  expirationDate: 'directive-expiration',
  physicianName: 'directive-physician',
};

/** The label each required field is shown under, to name it when it is blank (QA D1357). */
const DIRECTIVE_LABEL_KEYS: Readonly<Record<string, string>> = {
  'directive-type': 'DIRECTIVES.TYPE',
  'directive-status': 'DIRECTIVES.STATUS',
  'directive-effective': 'DIRECTIVES.EFFECTIVE_DATE',
  'directive-physician': 'DIRECTIVES.PHYSICIAN',
};

@Component({
  selector: 'app-advance-directives-tab',
  standalone: true,
  imports: [
    SubmitGuardDirective,
    ModalDialogDirective,
    ReasonDialogComponent,
    ServerTimePipe,
    CommonModule,
    FormsModule,
    TranslateModule,
  ],
  templateUrl: './advance-directives-tab.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './advance-directives-tab.component.scss',
})
export class AdvanceDirectivesTabComponent implements OnInit {
  @Input({ required: true }) patientId = '';

  private readonly directiveService = inject(AdvanceDirectiveService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly host = inject(ElementRef<HTMLElement>);
  private readonly chartChanges = inject(PatientChartChanges);

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
  form: DirectiveForm = this.emptyForm();

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

  /** Whether the chosen type is a physician order, whose physician is required. */
  needsPhysician(): boolean {
    return PHYSICIAN_ORDER_TYPES.has(this.form.directiveType);
  }

  submit(): void {
    const required = ['directive-type', 'directive-status', 'directive-effective'];
    if (this.needsPhysician()) required.push('directive-physician');
    const missing = blankFields(
      {
        'directive-type': this.form.directiveType,
        'directive-status': this.form.status,
        'directive-effective': this.form.effectiveDate,
        'directive-physician': this.form.physicianName,
      },
      required,
    );
    if (missing.length > 0) {
      // QA D1148: each missing field is marked where it is asked for.
      // QA D1357: and the refusal NAMES them, once ("Champs obligatoires
      // manquants : Type, Statut, Date d'effet"); it repeated one sentence
      // under every field and named none. The save button's submit guard
      // refuses first with the same words; this is the path it cannot see.
      refuseFields(
        this.host.nativeElement,
        missing,
        DIRECTIVE_FIELD_IDS,
        this.translate.instant('COMMON.FIELD_REQUIRED'),
      );
      this.toast.error(
        missingRequiredMessage(
          this.translate,
          missing.map((id) => ({ labelKey: DIRECTIVE_LABEL_KEYS[id], value: '' })),
        ) ?? this.translate.instant('COMMON.SUBMIT_BLOCKED_REQUIRED'),
      );
      return;
    }
    // Mirrors the backend rule ("cannot expire before it takes effect");
    // equal dates are allowed there, so they are allowed here.
    if (
      this.form.effectiveDate &&
      this.form.expirationDate &&
      this.form.expirationDate < this.form.effectiveDate
    ) {
      // QA D1306 (round 28): the expiration field is marked, not only a toast.
      const message = this.translate.instant('DIRECTIVES.DATES_INVALID');
      refuseFields(this.host.nativeElement, ['directive-expiration'], DIRECTIVE_FIELD_IDS, message);
      this.toast.error(message);
      return;
    }
    refuseFields(this.host.nativeElement, [], DIRECTIVE_FIELD_IDS, '');

    this.saving.set(true);
    const id = this.editingId();
    const request = this.form as AdvanceDirectiveRequest;
    const request$ = id
      ? this.directiveService.update(id, request)
      : this.directiveService.create(this.patientId, request);

    request$.subscribe({
      next: () => {
        this.toast.success(this.translate.instant('DIRECTIVES.SAVED'));
        this.saving.set(false);
        this.closeModal();
        this.load();
        // QA D1148: the chart banner's code status follows at once.
        this.chartChanges.notify(this.patientId);
      },
      error: (err: unknown) => {
        // The backend refusals are actionable ("cannot expire before it takes
        // effect", foreign patient reads as not-found) — surface them verbatim.
        this.toast.error(refusalReason(err) ?? this.translate.instant('DIRECTIVES.SAVE_ERROR'));
        // QA D1355 / D1357: every field the server names is marked with its reason.
        const refused = refusedFieldReasons(err, DIRECTIVE_ID_BY_FIELD);
        if (refused.length > 0) {
          refuseFields(
            this.host.nativeElement,
            refused.map(([id, message]) => ({ id, message })),
            DIRECTIVE_FIELD_IDS,
          );
        }
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

  /**
   * QA D1180 (round 22, regression of D1148): the server requires a reason to
   * revoke a directive — an active DNR could no longer be revoked because the
   * confirmation sent `{}`. The shared reason dialog asks for it.
   */
  executeRevoke(reason: string): void {
    const target = this.revokeTarget();
    const why = reason.trim();
    if (!target || this.revoking() || !why) {
      return;
    }
    this.revoking.set(true);
    this.directiveService.revoke(target.id, why).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('DIRECTIVES.REVOKED_TOAST'));
        this.revoking.set(false);
        this.revokeTarget.set(null);
        this.load();
        // QA D1148: a revoked DNR leaves the banner without a reload.
        this.chartChanges.notify(this.patientId);
      },
      error: (err: unknown) => {
        this.toast.error(refusalReason(err) ?? this.translate.instant('DIRECTIVES.REVOKE_ERROR'));
        this.revoking.set(false);
      },
    });
  }

  private emptyForm(): DirectiveForm {
    return {
      directiveType: '',
      status: '',
    };
  }
}
