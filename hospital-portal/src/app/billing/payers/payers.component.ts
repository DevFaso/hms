import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  OnInit,
  WritableSignal,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { Observable } from 'rxjs';

import {
  PAYER_TYPES,
  Payer,
  PayerCatalogueService,
  PayerPlan,
  PayerPlanRule,
  PayerType,
} from '../../services/payer-catalogue.service';
import {
  PRICE_LIST_CATEGORIES,
  PriceListCategory,
  PriceListItem,
  PriceListService,
} from '../../services/price-list.service';
import { RoleContextService } from '../../core/role-context.service';
import { actionRoles } from '../../core/action-permissions';
import { ToastService } from '../../core/toast.service';
import { ReasonDialogComponent } from '../../shared/reason-dialog/reason-dialog.component';
import { CfaPipe } from '../../shared/pipes/cfa.pipe';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { DialogA11yDirective } from '../../shared/a11y/dialog-a11y.directive';
import { SubmitGuardDirective } from '../../shared/forms/submit-guard.directive';
import { refuseFields } from '../../shared/forms/invalid-fields';
import { refusalReason, refusedFieldReasons } from '../../shared/refusal-reason';
import { businessToday } from '../price-list/price-list.component';

/** The payer dialog's control for each request field the server may name under `fieldErrors`. */
const PAYER_FIELD_IDS: Readonly<Record<string, string>> = {
  code: 'py-code',
  name: 'py-name',
  payerType: 'py-type',
};

const PLAN_FIELD_IDS: Readonly<Record<string, string>> = {
  code: 'pp-code',
  name: 'pp-name',
  defaultCoveragePercent: 'pp-percent',
  perInvoiceCeiling: 'pp-invoice-ceiling',
  annualCeilingPerBeneficiary: 'pp-annual-ceiling',
  effectiveFrom: 'pp-from',
  effectiveTo: 'pp-to',
};

/** `replaceScheduled` is asked for, not marked. */
const RULE_FIELD_IDS: Readonly<Record<string, string>> = {
  category: 'pr-category',
  priceListItemId: 'pr-item',
  coveragePercent: 'pr-percent',
  perActCeiling: 'pr-act-ceiling',
  effectiveFrom: 'pr-from',
};

interface PayerForm {
  code: string;
  name: string;
  payerType: PayerType | null;
}

interface PlanForm {
  code: string;
  name: string;
  defaultCoveragePercent: number | null;
  perInvoiceCeiling: number | null;
  annualCeilingPerBeneficiary: number | null;
  effectiveFrom: string;
  /** False until the user picks a date: an untouched date is sent as null (the server's business day). */
  effectiveFromChanged: boolean;
  effectiveTo: string;
}

type RuleTarget = 'CATEGORY' | 'ITEM';

interface RuleForm {
  target: RuleTarget;
  category: PriceListCategory | null;
  priceListItemId: string | null;
  excluded: boolean;
  coveragePercent: number | null;
  perActCeiling: number | null;
  effectiveFrom: string;
  effectiveFromChanged: boolean;
  replaceScheduled: boolean;
}

/** A number input left empty reads as null or ''; both mean "not set". */
function numberOrNull(value: number | string | null | undefined): number | null {
  if (value === null || value === undefined || value === '') return null;
  const n = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(n) ? n : null;
}

/**
 * Billing-money plan PR 6 (scorecard M1): the hospital's payer catalogue
 * ("Organismes payeurs"): payers, their plans ("Formules") with the default
 * coverage rate and ceilings, and the plans' rules (a rate, an exclusion or a
 * ceiling per act on a category or a price-list item). Everyone who bills or
 * links a coverage reads it; only the hospital admin and the accountant
 * change it (owner decision D-1), so the write buttons follow the API's own
 * role lists (action-permissions.ts). Names are the hospital's own words and
 * are shown as typed. A rule never changes in place: a new rule replaces it
 * from a date, or it ends with a reason.
 */
@Component({
  selector: 'app-payers',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterLink,
    TranslateModule,
    CfaPipe,
    EnumLabelPipe,
    ServerTimePipe,
    DialogA11yDirective,
    SubmitGuardDirective,
    ReasonDialogComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './payers.component.html',
  styleUrl: './payers.component.scss',
})
export class PayersComponent implements OnInit {
  private readonly api = inject(PayerCatalogueService);
  private readonly priceList = inject(PriceListService);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly payerTypes = PAYER_TYPES;
  readonly categories = PRICE_LIST_CATEGORIES;

  readonly canCreate = this.roleContext.hasAnyActiveRole(actionRoles('payerCreate'));
  readonly canUpdate = this.roleContext.hasAnyActiveRole(actionRoles('payerUpdate'));
  readonly canDeactivate = this.roleContext.hasAnyActiveRole(actionRoles('payerDeactivate'));
  readonly canEditPlans = this.roleContext.hasAnyActiveRole(actionRoles('payerPlanCreate'));
  readonly canEditRules = this.roleContext.hasAnyActiveRole(actionRoles('payerRuleAdd'));

  readonly payers = signal<Payer[]>([]);
  readonly loading = signal(false);
  readonly failed = signal(false);
  readonly includeInactive = signal(false);
  readonly selected = signal<Payer | null>(null);
  /** The hospital's active price-list items, for an item rule (read once, when a rule dialog opens). */
  readonly priceItems = signal<PriceListItem[]>([]);

  readonly payerDialog = signal<'create' | 'edit' | null>(null);
  readonly payerSaving = signal(false);
  payerForm: PayerForm = { code: '', name: '', payerType: null };

  /** The plan being edited (null: a new plan), while the plan dialog is open. */
  readonly planDialog = signal<{ plan: PayerPlan | null } | null>(null);
  readonly planSaving = signal(false);
  planForm: PlanForm = PayersComponent.blankPlan();

  readonly ruleDialog = signal<PayerPlan | null>(null);
  readonly ruleSaving = signal(false);
  /** The server's question when a planned rule for the same target would be replaced. */
  readonly replaceQuestion = signal<string | null>(null);
  ruleForm: RuleForm = PayersComponent.blankRule();

  readonly deactivating = signal<Payer | null>(null);
  readonly deactivateBusy = signal(false);
  readonly ending = signal<{ plan: PayerPlan; rule: PayerPlanRule } | null>(null);
  readonly endBusy = signal(false);

  /** Read on each change detection, never cached: a page left open past midnight moves to the new day. */
  get today(): string {
    return businessToday();
  }

  ngOnInit(): void {
    this.load();
  }

  private static blankPlan(): PlanForm {
    return {
      code: '',
      name: '',
      defaultCoveragePercent: null,
      perInvoiceCeiling: null,
      annualCeilingPerBeneficiary: null,
      effectiveFrom: businessToday(),
      effectiveFromChanged: false,
      effectiveTo: '',
    };
  }

  private static blankRule(): RuleForm {
    return {
      target: 'CATEGORY',
      category: null,
      priceListItemId: null,
      excluded: false,
      coveragePercent: null,
      perActCeiling: null,
      effectiveFrom: businessToday(),
      effectiveFromChanged: false,
      replaceScheduled: false,
    };
  }

  load(): void {
    this.loading.set(true);
    this.failed.set(false);
    this.api
      .list(this.includeInactive())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (rows) => {
          this.payers.set(rows ?? []);
          this.loading.set(false);
          const open = this.selected();
          if (open) this.selected.set((rows ?? []).find((p) => p.id === open.id) ?? open);
        },
        error: () => {
          this.payers.set([]);
          this.failed.set(true);
          this.loading.set(false);
        },
      });
  }

  toggleInactive(value: boolean): void {
    this.includeInactive.set(value);
    this.load();
  }

  open(payer: Payer): void {
    this.api
      .get(payer.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (detail) => this.selected.set(detail),
        error: () => this.toast.error(this.translate.instant('BILLING.PAYERS.LOAD_FAILED')),
      });
  }

  closeDetail(): void {
    this.selected.set(null);
  }

  /** What a rule applies to: its category's label, or the item's code and label as typed. */
  ruleTargetIsItem(rule: PayerPlanRule): boolean {
    return !!rule.priceListItemId;
  }

  // ── payer dialog ─────────────────────────────────────────────────────

  openCreate(): void {
    this.payerForm = { code: '', name: '', payerType: null };
    this.payerDialog.set('create');
  }

  openEdit(payer: Payer): void {
    this.payerForm = { code: payer.code, name: payer.name, payerType: payer.payerType };
    this.payerDialog.set('edit');
  }

  closePayerDialog(): void {
    this.payerDialog.set(null);
  }

  savePayer(): void {
    const mode = this.payerDialog();
    if (!mode || this.payerSaving()) return;
    const form = this.payerForm;
    this.payerSaving.set(true);
    const request =
      mode === 'create'
        ? this.api.create({
            code: form.code.trim(),
            name: form.name.trim(),
            payerType: form.payerType,
          })
        : this.api.update(this.selected()?.id ?? '', {
            name: form.name.trim(),
            payerType: form.payerType,
          });
    this.run(request, this.payerSaving, PAYER_FIELD_IDS, 'BILLING.PAYERS.SAVED', () =>
      this.payerDialog.set(null),
    );
  }

  // ── plan dialog ──────────────────────────────────────────────────────

  openPlan(plan: PayerPlan | null): void {
    this.planForm = plan
      ? {
          code: plan.code,
          name: plan.name,
          defaultCoveragePercent: plan.defaultCoveragePercent,
          perInvoiceCeiling: plan.perInvoiceCeiling ?? null,
          annualCeilingPerBeneficiary: plan.annualCeilingPerBeneficiary ?? null,
          effectiveFrom: plan.effectiveFrom,
          effectiveFromChanged: false,
          effectiveTo: plan.effectiveTo ?? '',
        }
      : PayersComponent.blankPlan();
    this.planDialog.set({ plan });
  }

  planDateChanged(value: string): void {
    this.planForm.effectiveFrom = value;
    this.planForm.effectiveFromChanged = true;
  }

  closePlanDialog(): void {
    this.planDialog.set(null);
  }

  savePlan(): void {
    const dialog = this.planDialog();
    const payer = this.selected();
    if (!dialog || !payer || this.planSaving()) return;
    const form = this.planForm;
    this.planSaving.set(true);
    const body = {
      name: form.name.trim(),
      defaultCoveragePercent: numberOrNull(form.defaultCoveragePercent),
      perInvoiceCeiling: numberOrNull(form.perInvoiceCeiling),
      annualCeilingPerBeneficiary: numberOrNull(form.annualCeilingPerBeneficiary),
      effectiveFrom: (form.effectiveFromChanged && form.effectiveFrom) || null,
      effectiveTo: form.effectiveTo || null,
    };
    const request = dialog.plan
      ? this.api.updatePlan(payer.id, dialog.plan.id, body)
      : this.api.createPlan(payer.id, { ...body, code: form.code.trim() });
    this.run(request, this.planSaving, PLAN_FIELD_IDS, 'BILLING.PAYERS.PLAN_SAVED', () =>
      this.planDialog.set(null),
    );
  }

  // ── rule dialog ──────────────────────────────────────────────────────

  openRule(plan: PayerPlan): void {
    this.ruleForm = PayersComponent.blankRule();
    this.replaceQuestion.set(null);
    this.loadPriceItems();
    this.ruleDialog.set(plan);
  }

  closeRuleDialog(): void {
    this.ruleDialog.set(null);
    this.replaceQuestion.set(null);
  }

  pickTarget(target: RuleTarget): void {
    this.ruleForm = { ...this.ruleForm, target, category: null, priceListItemId: null };
    this.resetReplace();
  }

  /** An exclusion carries no rate and no ceiling: ticking it clears them. */
  pickExcluded(excluded: boolean): void {
    this.ruleForm = {
      ...this.ruleForm,
      excluded,
      coveragePercent: excluded ? null : this.ruleForm.coveragePercent,
      perActCeiling: excluded ? null : this.ruleForm.perActCeiling,
    };
  }

  /** A new date or target needs its own confirmation: the question asked for the old one no longer applies. */
  ruleDateChanged(value: string): void {
    this.ruleForm.effectiveFrom = value;
    this.ruleForm.effectiveFromChanged = true;
    this.resetReplace();
  }

  resetReplace(): void {
    this.ruleForm.replaceScheduled = false;
    this.replaceQuestion.set(null);
  }

  private loadPriceItems(): void {
    if (this.priceItems().length > 0) return;
    this.priceList
      .list(null, false)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (items) => this.priceItems.set(items ?? []),
        error: () => this.priceItems.set([]),
      });
  }

  saveRule(): void {
    const plan = this.ruleDialog();
    const payer = this.selected();
    if (!plan || !payer || this.ruleSaving()) return;
    const form = this.ruleForm;
    this.ruleSaving.set(true);
    this.api
      .addRule(payer.id, plan.id, {
        category: form.target === 'CATEGORY' ? form.category : null,
        priceListItemId: form.target === 'ITEM' ? form.priceListItemId : null,
        excluded: form.excluded,
        coveragePercent: form.excluded ? null : numberOrNull(form.coveragePercent),
        perActCeiling: form.excluded ? null : numberOrNull(form.perActCeiling),
        effectiveFrom: (form.effectiveFromChanged && form.effectiveFrom) || null,
        replaceScheduled: form.replaceScheduled,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (saved) => {
          this.ruleSaving.set(false);
          this.ruleDialog.set(null);
          this.replaceQuestion.set(null);
          this.selected.set(saved);
          this.toast.success(this.translate.instant('BILLING.PAYERS.RULE_SAVED'));
          this.load();
        },
        error: (err) => {
          this.ruleSaving.set(false);
          const ask = (err?.error?.fieldErrors as { replaceScheduled?: unknown } | undefined)
            ?.replaceScheduled;
          if (typeof ask === 'string' && ask.trim() !== '') {
            // A rule is planned for the same target: the reader confirms its replacement, then saves again.
            this.replaceQuestion.set(ask);
            return;
          }
          this.markRefused(err, RULE_FIELD_IDS);
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('BILLING.PAYERS.SAVE_FAILED'),
          );
        },
      });
  }

  // ── deactivate a payer, end a rule (both ask why first) ─────────────

  askDeactivate(payer: Payer): void {
    this.deactivating.set(payer);
  }

  deactivate(payer: Payer, reason: string): void {
    if (this.deactivateBusy()) return;
    this.deactivateBusy.set(true);
    this.run(
      this.api.deactivate(payer.id, reason),
      this.deactivateBusy,
      {},
      'BILLING.PAYERS.DEACTIVATED',
      () => this.deactivating.set(null),
    );
  }

  askEndRule(plan: PayerPlan, rule: PayerPlanRule): void {
    this.ending.set({ plan, rule });
  }

  endRule(plan: PayerPlan, rule: PayerPlanRule, reason: string): void {
    const payer = this.selected();
    if (!payer || this.endBusy()) return;
    this.endBusy.set(true);
    this.run(
      this.api.endRule(payer.id, plan.id, rule.id, reason),
      this.endBusy,
      {},
      'BILLING.PAYERS.RULE_ENDED',
      () => this.ending.set(null),
    );
  }

  /** A rule may be ended while it applies or is planned. */
  canEnd(rule: PayerPlanRule): boolean {
    return rule.status === 'IN_FORCE' || rule.status === 'SCHEDULED';
  }

  /** Sends a write that answers the whole payer; on success the detail shows it and the list reloads. */
  private run(
    request: Observable<Payer>,
    busy: WritableSignal<boolean>,
    fieldIds: Readonly<Record<string, string>>,
    doneKey: string,
    close: () => void,
  ): void {
    request.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (saved) => {
        busy.set(false);
        close();
        this.selected.set(saved);
        this.toast.success(this.translate.instant(doneKey));
        this.load();
      },
      error: (err) => {
        busy.set(false);
        this.markRefused(err, fieldIds);
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('BILLING.PAYERS.SAVE_FAILED'),
        );
      },
    });
  }

  /** Marks each field the server named, with its own reason. */
  private markRefused(err: unknown, idByField: Readonly<Record<string, string>>): void {
    const refused = refusedFieldReasons(err, idByField);
    if (refused.length > 0) {
      refuseFields(
        this.host.nativeElement,
        refused.map(([id, message]) => ({ id, message })),
        Object.values(idByField),
      );
    }
  }
}
