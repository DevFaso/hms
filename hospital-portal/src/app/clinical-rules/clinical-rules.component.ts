import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';
import {
  ClinicalRuleStatus,
  ClinicalRulesOverview,
  ClinicalRulesService,
  ruleText,
} from '../services/clinical-rules.service';
import { ModalDialogDirective } from '../shared/a11y/modal-dialog.directive';
import { blobRefusalReason } from '../shared/blob-refusal';
import { localDateString } from '../shared/date-utils';
import { saveBlob } from '../shared/download-file';
import { refuseFields } from '../shared/forms/invalid-fields';
import { SubmitGuardDirective } from '../shared/forms/submit-guard.directive';
import { PageHeaderComponent } from '../shared/page-header/page-header.component';
import { personName } from '../shared/pipes/person-name.pipe';
import { DayMonthPipe } from '../shared/pipes/day-month.pipe';
import { ReasonDialogComponent } from '../shared/reason-dialog/reason-dialog.component';
import { refusalReason, refusedFieldReasons } from '../shared/refusal-reason';
import { joinReasons, missingRequiredMessage } from '../shared/required-fields';

/** The dialog's fields, by the request field a 400 names. */
const SIGN_FIELDS = {
  signerName: 'rule-signer-name',
  signerTitle: 'rule-signer-title',
  signedOn: 'rule-signed-on',
  note: 'rule-signer-note',
} as const;

/** The fields of « Enregistrer la validation ». */
interface SignDraft {
  signerName: string;
  signerTitle: string;
  signedOn: string;
  note: string;
  /** The signer is the person entering it (then their account is recorded too). */
  selfSigned: boolean;
}

/**
 * V356 (owner direction 2026-11-21): « Règles cliniques et validation ».
 *
 * <p>Every rule built so far is « provisoire » until THIS hospital's named
 * approver (the médecin-chef, or the role the registry names) signs it off.
 * The page lists every rule set with its source, its approver role and its
 * status here; the hospital admin (or the super-admin acting for the selected
 * hospital) records the approver's signature — usually made on the printed
 * sign-off pack — or revokes it. Doctors, midwives, nurses and pharmacists
 * read the same list. Recording a sign-off changes no rule's behaviour.
 */
@Component({
  selector: 'app-clinical-rules',
  standalone: true,
  imports: [
    FormsModule,
    TranslateModule,
    ModalDialogDirective,
    SubmitGuardDirective,
    PageHeaderComponent,
    DayMonthPipe,
    ReasonDialogComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './clinical-rules.component.html',
  styleUrls: ['./clinical-rules.component.scss', '../shared/styles/shared-dialog.scss'],
})
export class ClinicalRulesComponent {
  private readonly rulesService = inject(ClinicalRulesService);
  private readonly roleContext = inject(RoleContextService);
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  protected readonly hospitalId = computed(() => this.roleContext.effectiveHospitalIdForRequest());
  protected readonly overview = signal<ClinicalRulesOverview | null>(null);
  protected readonly loading = signal(false);
  protected readonly failed = signal(false);
  protected readonly busy = signal(false);
  protected readonly printing = signal(false);

  /** The rule set being signed, and the dialog's fields. */
  protected readonly signing = signal<ClinicalRuleStatus | null>(null);
  protected draft: SignDraft = this.emptyDraft();
  /** The rule set whose sign-off is being revoked. */
  protected readonly revoking = signal<ClinicalRuleStatus | null>(null);

  protected readonly today = localDateString();
  protected readonly text = ruleText;
  protected readonly rules = computed(() => this.overview()?.rules ?? []);

  constructor() {
    effect(() => {
      const id = this.hospitalId();
      untracked(() => this.load(id));
    });
  }

  protected load(hospitalId: string | null): void {
    this.overview.set(null);
    this.failed.set(false);
    if (!hospitalId) return;
    this.loading.set(true);
    this.rulesService.overview(hospitalId).subscribe({
      next: (overview) => {
        if (this.hospitalId() === hospitalId) this.overview.set(overview);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        if (this.hospitalId() === hospitalId) this.failed.set(true);
      },
    });
  }

  protected openSign(rule: ClinicalRuleStatus): void {
    this.draft = this.emptyDraft();
    this.signing.set(rule);
  }

  protected closeSign(): void {
    if (this.busy()) return;
    this.signing.set(null);
  }

  protected toggleSelf(selfSigned: boolean): void {
    this.draft.selfSigned = selfSigned;
    if (selfSigned && !this.draft.signerName.trim()) {
      const profile = this.auth.getUserProfile();
      this.draft.signerName = personName(profile);
    }
  }

  /** Why the sign-off cannot be sent yet, or null when it can. */
  protected signBlocked(): string | null {
    const d = this.draft;
    // QA D1256: every reason at once — the blank fields by name, and a date in the future.
    return joinReasons([
      missingRequiredMessage(this.translate, [
        { labelKey: 'CLINICAL_RULES.SIGN.SIGNER_NAME', value: d.signerName.trim() },
        { labelKey: 'CLINICAL_RULES.SIGN.SIGNER_TITLE', value: d.signerTitle.trim() },
        { labelKey: 'CLINICAL_RULES.SIGN.SIGNED_ON', value: d.signedOn },
      ]),
      d.signedOn && d.signedOn > this.today
        ? this.translate.instant('CLINICAL_RULES.SIGN.FUTURE_DATE')
        : null,
    ]);
  }

  protected submitSign(): void {
    const rule = this.signing();
    const hospitalId = this.hospitalId();
    if (!rule || !hospitalId || this.busy() || this.signBlocked()) return;
    this.busy.set(true);
    this.rulesService
      .sign(hospitalId, rule.key, {
        ruleVersion: rule.version,
        signerName: this.draft.signerName.trim(),
        signerTitle: this.draft.signerTitle.trim(),
        signerUserId: this.draft.selfSigned ? this.auth.getUserId() : null,
        signedOn: this.draft.signedOn,
        note: this.draft.note.trim() || null,
      })
      .subscribe({
        next: (status) => {
          this.busy.set(false);
          this.replace(status);
          this.signing.set(null);
          this.toast.success(this.translate.instant('CLINICAL_RULES.SIGN.DONE'));
        },
        error: (err: unknown) => {
          this.busy.set(false);
          // The server's own reason at the field it names (RuleSignoffRequestDTO).
          const refused = refusedFieldReasons(err, SIGN_FIELDS);
          if (refused.length) {
            refuseFields(
              this.host.nativeElement,
              refused.map(([id, message]) => ({ id, message })),
              Object.values(SIGN_FIELDS),
            );
          }
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('CLINICAL_RULES.SIGN.FAILED'),
          );
        },
      });
  }

  protected revoke(rule: ClinicalRuleStatus, reason: string): void {
    const hospitalId = this.hospitalId();
    if (!hospitalId || this.busy()) return;
    this.busy.set(true);
    this.rulesService.revoke(hospitalId, rule.key, reason).subscribe({
      next: (status) => {
        this.busy.set(false);
        this.replace(status);
        this.revoking.set(null);
        this.toast.success(this.translate.instant('CLINICAL_RULES.REVOKE.DONE'));
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('CLINICAL_RULES.REVOKE.FAILED'),
        );
      },
    });
  }

  protected printPack(): void {
    const hospitalId = this.hospitalId();
    if (!hospitalId || this.printing()) return;
    this.printing.set(true);
    this.rulesService.signoffPack(hospitalId).subscribe({
      next: (blob) => {
        this.printing.set(false);
        saveBlob(blob, 'regles-cliniques-validation.pdf');
      },
      error: async (err: unknown) => {
        this.printing.set(false);
        this.toast.error(
          (await blobRefusalReason(err)) ?? this.translate.instant('CLINICAL_RULES.PACK_FAILED'),
        );
      },
    });
  }

  private replace(status: ClinicalRuleStatus): void {
    const overview = this.overview();
    if (!overview) return;
    const rules = overview.rules.map((rule) => (rule.key === status.key ? status : rule));
    this.overview.set({
      ...overview,
      rules,
      signedCount: rules.filter((rule) => rule.status === 'SIGNED').length,
    });
  }

  private emptyDraft(): SignDraft {
    return {
      signerName: '',
      signerTitle: this.translate.instant('CLINICAL_RULES.SIGN.DEFAULT_TITLE'),
      signedOn: localDateString(),
      note: '',
      selfSigned: false,
    };
  }
}
