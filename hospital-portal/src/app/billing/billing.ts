import { allRows } from '../shared/all-pages';
import { textMatches } from '../shared/person-search';
import {
  Component,
  computed,
  ElementRef,
  inject,
  OnInit,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import {
  BillingService,
  BillingInvoiceResponse,
  BillingInvoiceRequest,
  InvoiceItemCategory,
  InvoiceItemResponse,
  InvoiceStatus,
  INVOICE_ITEM_CATEGORIES,
  INVOICE_ADJUSTMENT_TYPES,
  InvoiceAdjustmentRow,
  InvoiceAdjustmentType,
  INVOICE_STATUSES,
  PaymentRow,
  STAFF_PAYMENT_METHODS,
  StaffPaymentMethod,
} from '../services/billing.service';
import { PatientPickerComponent } from '../shared/patient-picker/patient-picker.component';
import { PatientResponse } from '../services/patient.service';
import { ToastService } from '../core/toast.service';
import { PermissionService } from '../core/permission.service';
import { RoleContextService } from '../core/role-context.service';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { CashClosingComponent } from './cash-closing/cash-closing.component';
import { formatCfa } from '../shared/i18n/app-locale';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';
import { ServerTimePipe } from '../shared/pipes/server-time.pipe';
import { DialogA11yDirective } from '../shared/a11y/dialog-a11y.directive';
import { saveResponse } from '../shared/download-file';
import {
  CFA_AMOUNT_REQUIRED_KEY,
  cfaAmountMessageKey,
  cfaAmountProblem,
  cfaPriceProblem,
} from '../shared/cfa-amount';
import { PersonNamePipe } from '../shared/pipes/person-name.pipe';
import { refuseFields, type FieldRefusal } from '../shared/forms/invalid-fields';
import { emailAddresses, invalidEmailAddresses } from '../shared/forms/email-address';

import { SubmitGuardDirective } from '../shared/forms/submit-guard.directive';
import { InlineSubFormDirective } from '../shared/a11y/inline-sub-form.directive';
import { InlineDiscardAskComponent } from '../shared/a11y/inline-discard-ask.component';
import { focusWhenRendered } from '../shared/a11y/focus-when-rendered';
import { amountStillDue, pendingDeclared } from '../shared/payment-prefill';
import { refusalReason } from '../shared/refusal-reason';
import { joinReasons } from '../shared/required-fields';
import { ConfirmService } from '../shared/confirm-dialog/confirm.service';
import { serverFieldErrors } from '../shared/forms/server-field-errors';
import { KeyLabelPipe } from '../shared/pipes/key-label.pipe';
type BillingTab = 'all' | 'outstanding' | 'paid' | 'overdue';

/** The cancellation reason's field (QA D1069), marked when it is refused (QA D1072). */
const CANCEL_REASON_ID = 'bill-cancel-reason';
/** The invoice e-mail's address fields (QA D1179), marked when an address is refused. */
const EMAIL_TO_ID = 'email-to';
const EMAIL_CC_ID = 'email-cc';
const EMAIL_FIELD_IDS = [EMAIL_TO_ID, EMAIL_CC_ID];
/** QA D1306 class: the payment form's fields a refusal marks or clears. */
const PAYMENT_FIELD_IDS = ['payment-amount', 'payment-reference'];

/** QA D1294: a blank or zero unit price says so (not "a payment amount"). */
const LINE_PRICE_REQUIRED_KEY = 'BILLING.LINE_UNIT_PRICE_REQUIRED';
/** QA D1467: the field the server refuses an issue on while lines stand at 0 F CFA. */
const UNPRICED_LINES_FIELD = 'confirmUnpricedLines';

/**
 * QA D1344 (round 29): what is wrong with a line quantity — blank is
 * required, anything else that is not a whole number above zero says so
 * ("Ce champ est obligatoire." was shown for a typed 0). `null`: it is fine.
 */
export function lineQuantityMessageKey(qty: number | string | null | undefined): string | null {
  if (qty === null || qty === undefined || qty === '') return 'COMMON.FIELD_REQUIRED';
  const value = typeof qty === 'number' ? qty : Number(qty);
  return Number.isInteger(value) && value >= 1 ? null : 'BILLING.LINE_QUANTITY_POSITIVE';
}

@Component({
  selector: 'app-billing',
  standalone: true,
  imports: [
    KeyLabelPipe,
    SubmitGuardDirective,
    InlineSubFormDirective,
    InlineDiscardAskComponent,
    ServerTimePipe,
    PersonNamePipe,
    CommonModule,
    FormsModule,
    TranslateModule,
    EnumLabelPipe,
    PatientPickerComponent,
    DialogA11yDirective,
    CashClosingComponent,
  ],
  templateUrl: './billing.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './billing.scss',
})
export class BillingComponent implements OnInit {
  private readonly billingService = inject(BillingService);
  private readonly toast = inject(ToastService);
  private readonly permissions = inject(PermissionService);
  private readonly roleContext = inject(RoleContextService);
  private readonly translate = inject(TranslateService);
  /** QA D1229: the in-app confirmation, never the browser's. */
  private readonly confirmService = inject(ConfirmService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  // Permission-gated action flags
  /** QA D1059: the cashier's daily cash closing, opened from the page header. */
  readonly showCashClosing = signal(false);

  /** QA D1059: the date range of the last search run, which the KPI tiles follow. */
  private appliedRange: { fromDate?: string; toDate?: string } = {};

  readonly canCreate = this.permissions.hasAnyPermission('Create Invoice', 'Manage Billing');
  readonly canEdit = this.permissions.hasAnyPermission('Create Invoice', 'Manage Billing');
  /** QA D181: the server deletes only for SUPER_ADMIN and HOSPITAL_ADMIN, so a
   *  billing specialist holding "Manage Billing" no longer sees a button that 403s. */
  readonly canDelete =
    this.permissions.hasAnyPermission('Delete Invoice', 'Manage Billing') &&
    this.roleContext.hasAnyActiveRole(['ROLE_SUPER_ADMIN', 'ROLE_HOSPITAL_ADMIN']);
  readonly canEmail = this.permissions.hasAnyPermission('Email Invoice', 'Manage Billing');
  readonly canViewReports = this.permissions.hasAnyPermission(
    'View Billing Reports',
    'Manage Billing',
  );
  /** QA D973: the front desk takes payments (the server admits the receptionist
   *  on POST /billing-invoices/{id}/payments), so the button is not hidden from it. */
  readonly canRecordPayment = this.roleContext.hasAnyActiveRole([
    'ROLE_SUPER_ADMIN',
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_BILLING_SPECIALIST',
    'ROLE_ACCOUNTANT',
    'ROLE_RECEPTIONIST',
  ]);
  /** QA D974: the server lets these roles discount, waive or credit an issued invoice. */
  readonly canAdjust = this.roleContext.hasAnyActiveRole([
    'ROLE_SUPER_ADMIN',
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_BILLING_SPECIALIST',
  ]);
  /** QA D142: the server reverses a payment for these roles only. */
  readonly canReversePayment = this.roleContext.hasAnyActiveRole([
    'ROLE_SUPER_ADMIN',
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_BILLING_SPECIALIST',
    'ROLE_ACCOUNTANT',
  ]);

  invoices = signal<BillingInvoiceResponse[]>([]);
  filtered = signal<BillingInvoiceResponse[]>([]);
  searchTerm = '';
  loading = signal(true);
  /**
   * QA D1145 (round 21): the invoice list could not be read. Shown in place of
   * the list with a retry: a failed read is never "No invoices found" with
   * zero totals (it read as an empty ledger after a language switch).
   */
  loadFailed = signal(false);
  /** QA D1145: the totals could not be read; the tiles say "—", never 0. */
  private readonly summaryFailed = signal(false);
  readonly statsUnknown = computed(() => this.loadFailed() || this.summaryFailed());
  activeTab = signal<BillingTab>('all');
  selectedInvoice = signal<BillingInvoiceResponse | null>(null);

  /* Server-side search state */
  page = signal(0);
  totalPages = signal(0);
  totalElements = signal(0);
  readonly pageSize = 20;
  filterStatus = signal('');
  filterFromDate = signal('');
  filterToDate = signal('');

  stats = signal({ total: 0, paid: 0, outstanding: 0, overdue: 0, totalRevenue: 0, balance: 0 });

  /* ── CRUD signals ── */
  showModal = signal(false);
  editing = signal(false);
  saving = signal(false);
  editingId = signal<string | null>(null);
  form: BillingInvoiceRequest = this.emptyForm();
  /** The patient chosen in the picker (QA D92: phone-only patients too). */
  formPatient = signal<PatientResponse | null>(null);
  /** The number of the invoice being edited; the server allocates it (QA D94). */
  editingNumber = signal('');
  /** The status the edited invoice has now; a paid status follows payments (QA D91). */
  editingStatus = signal<InvoiceStatus>('DRAFT');
  /** QA D974: what the edited invoice has received (a waiver can settle it with none). */
  editingPaid = signal(0);
  /** With lines, the total is their sum and cannot be typed (QA D72). */
  editingHasLines = signal(false);
  /**
   * QA D936: the picked patient's paid check-in copays that no visit invoice
   * has taken yet. Billing cannot open the encounter list, so the form names
   * the visit each one was paid for and links it in one click.
   */
  openCopays = signal<BillingInvoiceResponse[]>([]);
  /** The visit code the form filled in itself (cleared when the patient changes). */
  private proposedVisit: string | null = null;

  /* ── Invoice lines (detail panel) ── */
  items = signal<InvoiceItemResponse[]>([]);
  itemsLoading = signal(false);
  itemSaving = signal(false);
  newItemDescription = signal('');
  newItemCategory = signal<InvoiceItemCategory>('SERVICE');
  newItemQuantity = signal<number | null>(1);
  newItemUnitPrice = signal<number | null>(null);
  readonly itemCategories: InvoiceItemCategory[] = INVOICE_ITEM_CATEGORIES;

  showDeleteConfirm = signal(false);
  deletingInv = signal<BillingInvoiceResponse | null>(null);
  deleting = signal(false);

  /* ── Email modal ── */
  showEmailModal = signal(false);
  emailInv = signal<BillingInvoiceResponse | null>(null);
  emailTo = signal('');
  emailCc = signal('');
  emailMessage = signal('');
  emailAttachPdf = signal(true);
  emailSending = signal(false);

  /* ── Payment modal ── */
  showPaymentModal = signal(false);
  paymentInv = signal<BillingInvoiceResponse | null>(null);
  paymentAmount = signal<number | null>(null);
  /** QA D146: the method and the reference are recorded, not discarded. */
  paymentMethod = signal<StaffPaymentMethod>('CASH');
  paymentReference = signal('');
  paymentSaving = signal(false);
  readonly paymentMethods: StaffPaymentMethod[] = STAFF_PAYMENT_METHODS;

  /* ── Adjustments (detail panel, QA D974) ── */
  adjustments = signal<InvoiceAdjustmentRow[]>([]);
  readonly adjustmentTypes: InvoiceAdjustmentType[] = INVOICE_ADJUSTMENT_TYPES;
  adjustmentType = signal<InvoiceAdjustmentType>('DISCOUNT');
  adjustmentAmount = signal<number | null>(null);
  adjustmentReason = signal('');
  adjustmentLineId = signal('');
  adjustmentSaving = signal(false);

  /** QA D974: only an issued invoice is adjusted; a draft's lines are edited instead. */
  canAdjustInvoice(inv: BillingInvoiceResponse): boolean {
    return this.canAdjust && (inv.status === 'SENT' || inv.status === 'PARTIALLY_PAID');
  }

  /**
   * QA D1105: a standing adjustment of an issued invoice can be undone with a
   * reason (a mistaken waiver); the copay paid at check-in stays received.
   */
  canReverseAdjustment(inv: BillingInvoiceResponse, a: InvoiceAdjustmentRow): boolean {
    return (
      this.canAdjust &&
      !a.reversedAt &&
      (inv.status === 'SENT' || inv.status === 'PARTIALLY_PAID' || inv.status === 'PAID')
    );
  }

  reversingAdjustment = signal<InvoiceAdjustmentRow | null>(null);
  adjustmentReversalReason = signal('');
  adjustmentReversalSaving = signal(false);

  openAdjustmentReversal(a: InvoiceAdjustmentRow): void {
    this.reversingAdjustment.set(a);
    this.adjustmentReversalReason.set('');
  }

  closeAdjustmentReversal(): void {
    this.reversingAdjustment.set(null);
  }

  submitAdjustmentReversal(): void {
    const inv = this.selectedInvoice();
    const a = this.reversingAdjustment();
    if (!inv || !a || this.adjustmentReversalSaving()) return;
    const reason = this.adjustmentReversalReason().trim();
    if (!reason) {
      // QA D1069 / D1105: the reason is asked for where it is typed, never by a dead button.
      refuseFields(
        this.host.nativeElement,
        ['adj-reversal-reason'],
        ['adj-reversal-reason'],
        this.translate.instant('BILLING.ADJUSTMENT_REVERSAL_REASON_REQUIRED'),
      );
      return;
    }
    this.adjustmentReversalSaving.set(true);
    this.billingService.reverseAdjustment(inv.id, a.id, reason).subscribe({
      next: (updated) => {
        this.adjustmentReversalSaving.set(false);
        this.closeAdjustmentReversal();
        this.toast.success(this.translate.instant('BILLING.ADJUSTMENT_REVERSED'));
        this.applyUpdated(updated);
        this.loadAdjustments(updated.id);
      },
      error: (err: HttpErrorResponse) => {
        this.adjustmentReversalSaving.set(false);
        this.toast.error(
          err.error?.message ?? this.translate.instant('BILLING.ADJUSTMENT_REVERSAL_FAILED'),
        );
      },
    });
  }

  private loadAdjustments(invoiceId: string): void {
    this.billingService.listAdjustments(invoiceId).subscribe({
      next: (rows) => {
        if (this.selectedInvoice()?.id === invoiceId) this.adjustments.set(rows ?? []);
      },
      error: () => this.adjustments.set([]),
    });
  }

  private resetAdjustment(): void {
    this.adjustmentType.set('DISCOUNT');
    this.adjustmentAmount.set(null);
    this.adjustmentReason.set('');
    this.adjustmentLineId.set('');
  }

  submitAdjustment(): void {
    const inv = this.selectedInvoice();
    if (!inv || this.adjustmentSaving()) return;
    const reason = this.adjustmentReason().trim();
    const type = this.adjustmentType();
    const amount = this.adjustmentAmount();
    const line = this.adjustmentLineId();
    // QA D1278 (D1256 class, round 27): the submit guard names every blank
    // field at once (Montant, Motif, the credit note's line) and marks each;
    // this handler used to refuse them one press at a time, as toasts.
    const amountProblem = type === 'CREDIT_NOTE' ? null : cfaAmountProblem(amount);
    const amountBlank = amount === null || amount === undefined || `${amount}`.trim() === '';
    if (!reason || (type === 'CREDIT_NOTE' && !line) || (amountProblem && amountBlank)) return;
    // QA D1089: a typed amount that is zero or has decimals is refused under the field.
    if (amountProblem) {
      const message = this.translate.instant(
        cfaAmountMessageKey(amountProblem, 'BILLING.ADJUSTMENT_AMOUNT_REQUIRED'),
      );
      refuseFields(this.host.nativeElement, ['adj-amount'], ['adj-amount'], message);
      this.toast.error(message);
      return;
    }
    this.adjustmentSaving.set(true);
    this.billingService
      .addAdjustment(inv.id, {
        type,
        amount: amount || null,
        reason,
        invoiceItemId: type === 'CREDIT_NOTE' ? line : null,
      })
      .subscribe({
        next: (updated) => {
          this.adjustmentSaving.set(false);
          this.resetAdjustment();
          this.toast.success(this.translate.instant('BILLING.ADJUSTMENT_SAVED'));
          this.applyUpdated(updated);
          this.loadAdjustments(updated.id);
        },
        error: (err: HttpErrorResponse) => {
          this.adjustmentSaving.set(false);
          this.toast.error(
            err.error?.message ?? this.translate.instant('BILLING.ADJUSTMENT_FAILED'),
          );
        },
      });
  }

  /* ── Payments ledger (detail panel, QA D140/D142) ── */
  payments = signal<PaymentRow[]>([]);
  paymentActionBusy = signal(false);
  reversingPayment = signal<PaymentRow | null>(null);
  reversalMode = signal<'reverse' | 'reject'>('reverse');
  reversalReason = signal('');

  readonly invoiceStatuses: InvoiceStatus[] = INVOICE_STATUSES;

  ngOnInit(): void {
    this.loadInvoices();
  }

  /**
   * QA D436: the new-invoice form opened with both required dates empty.
   * The invoice is dated today and due in 30 days, both editable.
   */
  emptyForm(): BillingInvoiceRequest {
    const today = new Date();
    const due = new Date(today.getFullYear(), today.getMonth(), today.getDate() + 30);
    return {
      patientId: '',
      invoiceDate: this.isoDay(today),
      dueDate: this.isoDay(due),
      totalAmount: 0,
      status: 'DRAFT',
    };
  }

  /** Statuses staff may choose; PAID/PARTIALLY_PAID come from recorded payments. */
  statusOptions(): InvoiceStatus[] {
    if (!this.editing()) return ['DRAFT', 'SENT'];
    const current = this.editingStatus();
    // QA D974: settled by a waiver with no money received: a mistaken waiver is undone by cancelling.
    if (current === 'PAID' && this.editingPaid() === 0) return ['PAID', 'CANCELLED'];
    if (current === 'PAID' || current === 'PARTIALLY_PAID') return [current];
    // QA D1057: an issued invoice never goes back to DRAFT (the server refuses
    // it: billing.invoice.noReturnToDraft); it stays as it is or is cancelled.
    if (current && current !== 'DRAFT') return [current, 'CANCELLED'];
    return ['DRAFT', 'SENT', 'CANCELLED'];
  }

  /**
   * QA D1069: the edit moves an invoice to CANCELLED, so the reason is asked
   * for (the server refuses the move without it).
   *
   * QA D1217 (round 23): a DRAFT too. The round-19 rule exempted drafts, and a
   * draft generated at checkout, priced lines and all, was cancelled with
   * `cancelReason: null`. Real hospitals audit every void: no status is exempt.
   */
  cancelling(): boolean {
    const current = this.editingStatus();
    return this.editing() && this.form.status === 'CANCELLED' && current !== 'CANCELLED';
  }

  /** QA D614: an issued invoice keeps its patient; it is cancelled and re-issued instead. */
  patientLocked(): boolean {
    return this.editing() && this.editingStatus() !== 'DRAFT';
  }

  /** QA D656: an issued invoice keeps its dates, total and visit; only its status moves. */
  issuedContentLocked(): boolean {
    return this.patientLocked();
  }

  /** QA D632: an invoice is not dated in the future. */
  maxInvoiceDate(): string {
    return this.isoDay(new Date());
  }

  statusLocked(): boolean {
    const current = this.editingStatus();
    return (
      this.editing() &&
      (current === 'PARTIALLY_PAID' || (current === 'PAID' && this.editingPaid() > 0))
    );
  }

  openCreate(): void {
    this.form = this.emptyForm();
    // One key per form: a double click or a retry creates one invoice (QA D94).
    this.form.clientRequestId = crypto.randomUUID();
    this.formPatient.set(null);
    this.openCopays.set([]);
    this.editingHasLines.set(false);
    this.editing.set(false);
    this.editingId.set(null);
    this.showModal.set(true);
  }

  openEdit(inv: BillingInvoiceResponse): void {
    this.form = {
      patientId: inv.patientId ?? '',
      invoiceDate: inv.invoiceDate?.substring(0, 10) ?? '',
      dueDate: inv.dueDate?.substring(0, 10) ?? '',
      totalAmount: inv.totalAmount ?? 0,
      status: inv.status ?? 'DRAFT',
      notes: inv.notes,
      // QA D148: the visit link survives an edit.
      encounterReference: inv.encounterCode ?? undefined,
    };
    this.formPatient.set(
      inv.patientId
        ? {
            id: inv.patientId,
            firstName: inv.patientFullName ?? '',
            lastName: '',
            email: inv.patientEmail,
            phoneNumberPrimary: inv.patientPhone,
            active: true,
          }
        : null,
    );
    this.editingNumber.set(inv.invoiceNumber ?? '');
    this.editingStatus.set(inv.status ?? 'DRAFT');
    this.editingPaid.set(Number(inv.amountPaid ?? 0));
    this.editingHasLines.set(false);
    this.billingService.getInvoiceItems(inv.id).subscribe({
      next: (lines) => this.editingHasLines.set((lines ?? []).length > 0),
      error: () => this.editingHasLines.set(false),
    });
    this.editing.set(true);
    this.editingId.set(inv.id);
    this.showModal.set(true);
  }

  onPatientPicked(p: PatientResponse | null): void {
    this.formPatient.set(p);
    this.form.patientId = p?.id ?? '';
    this.loadOpenCopays(p?.id ?? null);
  }

  /** QA D936: the copays a new invoice for this patient should take. */
  private loadOpenCopays(patientId: string | null): void {
    this.openCopays.set([]);
    if (this.proposedVisit && this.form.encounterReference === this.proposedVisit) {
      this.form.encounterReference = undefined;
    }
    this.proposedVisit = null;
    if (!patientId || this.editing()) return;
    // QA D1347: every paid invoice of the patient (all pages), not the 50 newest.
    allRows((page, size) =>
      this.billingService.searchInvoices({ patientId, statuses: ['PAID'] }, page, size),
    ).subscribe({
      next: (invoices) => {
        if (this.form.patientId !== patientId) return;
        const open = invoices.filter(
          (inv) => inv.checkInCopay && !inv.creditedToInvoiceNumber && !!inv.encounterCode,
        );
        this.openCopays.set(open);
        // QA D936: one open copay is proposed as the visit; the clerk sees it and may clear it.
        if (open.length === 1 && !this.form.encounterReference) {
          this.form.encounterReference = open[0].encounterCode ?? undefined;
          this.proposedVisit = this.form.encounterReference ?? null;
        }
      },
      error: () => this.openCopays.set([]),
    });
  }

  /** QA D936: the visit the copay was paid for becomes this invoice's visit. */
  linkCopayVisit(copay: BillingInvoiceResponse): void {
    this.form.encounterReference = copay.encounterCode ?? undefined;
  }

  closeModal(): void {
    this.showModal.set(false);
  }

  submitForm(): void {
    if (this.saving()) return;
    // QA D1069: only a cancellation carries a reason; any other edit sends none.
    if (this.form.status !== 'CANCELLED') {
      this.form.cancelReason = undefined;
    } else if (this.cancelling() && !this.form.cancelReason?.trim()) {
      // QA D1072 (round-18 merge): the missing reason is marked under its field
      // and focused, not only named in a toast.
      const message = this.translate.instant('INVOICE_CANCEL.REASON_REQUIRED');
      refuseFields(this.host.nativeElement, [CANCEL_REASON_ID], [CANCEL_REASON_ID], message);
      this.toast.error(message);
      return;
    }
    this.saving.set(true);
    const op = this.editing()
      ? this.billingService.updateInvoice(this.editingId()!, this.form)
      : this.billingService.createInvoice(this.form);
    op.subscribe({
      next: (saved: BillingInvoiceResponse) => {
        this.toast.success(
          this.translate.instant(this.editing() ? 'BILLING.UPDATED' : 'BILLING.CREATED'),
        );
        // QA D1466: the copay taken at check-in was above this visit: say so at issue.
        if (Number(saved?.refundDue ?? 0) > 0) {
          this.toast.info(
            this.translate.instant('BILLING.REFUND_DUE_NOTICE', {
              amount: this.formatCurrency(saved.refundDue ?? 0),
            }),
          );
        }
        this.form.confirmUnpricedLines = undefined;
        this.closeModal();
        this.saving.set(false);
        this.loadInvoices(this.page());
      },
      error: (err: HttpErrorResponse) => {
        this.saving.set(false);
        // QA D1467: the server names the lines at 0 F CFA; issued only on the clerk's word.
        const unpriced = serverFieldErrors(err).get(UNPRICED_LINES_FIELD);
        if (unpriced) {
          void this.confirmService
            .ask({ titleKey: 'BILLING.UNPRICED_LINES_CONFIRM', message: unpriced })
            .then((confirmed) => {
              if (!confirmed) return;
              this.form = { ...this.form, confirmUnpricedLines: true };
              this.submitForm();
            });
          return;
        }
        // QA D529: the stay already has an invoice. Billed twice only on purpose.
        if (!this.editing() && err.status === 409 && !this.form.supplementary) {
          const why = err.error?.message ?? '';
          // QA D1229: the in-app confirmation, never the browser's.
          void this.confirmService
            .ask({ titleKey: 'BILLING.SUPPLEMENTARY_CONFIRM', message: why })
            .then((confirmed) => {
              if (!confirmed) return;
              this.form = { ...this.form, supplementary: true };
              this.submitForm();
            });
          return;
        }
        this.toast.error(refusalReason(err) ?? this.translate.instant('BILLING.SAVE_FAILED'));
      },
    });
  }

  confirmDelete(inv: BillingInvoiceResponse): void {
    this.deletingInv.set(inv);
    this.showDeleteConfirm.set(true);
  }
  cancelDelete(): void {
    this.showDeleteConfirm.set(false);
    this.deletingInv.set(null);
  }
  executeDelete(): void {
    this.deleting.set(true);
    this.billingService.deleteInvoice(this.deletingInv()!.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('BILLING.DELETED'));
        this.cancelDelete();
        this.deleting.set(false);
        this.loadInvoices(this.page());
      },
      error: (err: unknown) => {
        this.toast.error(refusalReason(err) ?? this.translate.instant('BILLING.DELETE_FAILED'));
        this.deleting.set(false);
      },
    });
  }

  loadInvoices(page = 0): void {
    this.loading.set(true);
    this.loadFailed.set(false);
    if (this.activeTab() === 'overdue') {
      // Dedicated server-side worklist (SENT/PARTIALLY_PAID past due).
      this.billingService.getOverdue().subscribe({
        next: (list) => {
          this.invoices.set(list ?? []);
          this.page.set(0);
          this.totalPages.set(1);
          this.totalElements.set(list?.length ?? 0);
          this.refreshStats();
          this.applyFilter();
          this.loading.set(false);
        },
        error: () => this.listLoadFailed(),
      });
      return;
    }
    const tab = this.activeTab();
    const statuses: InvoiceStatus[] | undefined =
      tab === 'paid'
        ? ['PAID']
        : tab === 'outstanding'
          ? ['SENT', 'PARTIALLY_PAID']
          : this.filterStatus()
            ? [this.filterStatus() as InvoiceStatus]
            : undefined;
    // QA D1059: the tiles follow the range of the search actually run (an
    // inverted one is not a range: the tiles then cover every invoice).
    const fromDate = this.filterFromDate() || undefined;
    const toDate = this.filterToDate() || undefined;
    this.appliedRange = fromDate && toDate && fromDate > toDate ? {} : { fromDate, toDate };
    this.billingService
      .searchInvoices(
        {
          statuses,
          fromDate,
          toDate,
          query: this.searchTerm.trim() || undefined,
        },
        page,
        this.pageSize,
      )
      .subscribe({
        next: (res) => {
          const list = res?.content ?? [];
          this.invoices.set(list);
          this.page.set(res?.number ?? page);
          this.totalPages.set(res?.totalPages ?? 0);
          this.totalElements.set(res?.totalElements ?? list.length);
          this.refreshStats();
          this.applyFilter();
          this.loading.set(false);
        },
        error: () => this.listLoadFailed(),
      });
  }

  /** QA D1145: the list and its totals are not shown as empty when the read failed. */
  private listLoadFailed(): void {
    this.toast.error(this.translate.instant('BILLING.LOAD_FAILED'));
    this.invoices.set([]);
    this.applyFilter();
    this.loadFailed.set(true);
    this.loading.set(false);
  }

  /**
   * QA D209: the KPIs come from the server over every invoice in scope; a
   * page of 20 rows undercounted revenue and money owed.
   */
  private refreshStats(): void {
    // QA D1059: the tiles follow the list's date filter (the invoice date, as the search reads it).
    this.billingService.getSummary(this.appliedRange).subscribe({
      next: (summary) => {
        this.summaryFailed.set(false);
        this.stats.set({
          total: Number(summary?.total ?? 0),
          paid: Number(summary?.paid ?? 0),
          outstanding: Number(summary?.outstanding ?? 0),
          overdue: Number(summary?.overdue ?? 0),
          totalRevenue: Number(summary?.totalRevenue ?? 0),
          balance: Number(summary?.balance ?? 0),
        });
      },
      error: () => this.summaryFailed.set(true),
    });
  }

  setTab(tab: BillingTab): void {
    this.activeTab.set(tab);
    this.loadInvoices(0);
  }

  applyServerFilters(): void {
    this.loadInvoices(0);
  }

  clearServerFilters(): void {
    this.filterStatus.set('');
    this.filterFromDate.set('');
    this.filterToDate.set('');
    this.loadInvoices(0);
  }

  private searchTimer: ReturnType<typeof setTimeout> | null = null;

  /** QA D203: the search runs on the server over every invoice, not the loaded page. */
  onSearchInput(): void {
    if (this.activeTab() === 'overdue') {
      this.applyFilter();
      return;
    }
    if (this.searchTimer) clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => {
      this.searchTimer = null;
      this.loadInvoices(0);
    }, 300);
  }

  applyFilter(): void {
    let list = this.invoices();
    const term = this.searchTerm.toLowerCase().trim();
    // The overdue worklist is loaded whole, so it is filtered here; the other
    // tabs were already searched by the server.
    if (term && this.activeTab() === 'overdue') {
      list = list.filter(
        (inv) =>
          (inv.invoiceNumber ?? '').toLowerCase().includes(term) ||
          textMatches(inv.patientFullName, term) ||
          (inv.status ?? '').toLowerCase().includes(term),
      );
    }
    this.filtered.set(list);
  }

  viewInvoice(inv: BillingInvoiceResponse): void {
    this.selectedInvoice.set(inv);
    this.resetNewItem();
    this.loadItems(inv.id);
    this.loadPayments(inv.id);
    this.resetAdjustment();
    this.adjustments.set([]);
    this.loadAdjustments(inv.id);
  }

  closeDetail(): void {
    this.selectedInvoice.set(null);
    this.items.set([]);
    this.payments.set([]);
  }

  /* ── Invoice lines ── */

  private loadItems(invoiceId: string, focusLineId?: string): void {
    this.itemsLoading.set(true);
    this.billingService.getInvoiceItems(invoiceId).subscribe({
      next: (lines) => {
        this.items.set(lines ?? []);
        this.itemsLoading.set(false);
        if (focusLineId) {
          focusWhenRendered(
            this.host.nativeElement,
            `[data-testid="line-edit-${CSS.escape(focusLineId)}"]`,
          );
        }
      },
      error: () => {
        this.items.set([]);
        this.itemsLoading.set(false);
        this.toast.error(this.translate.instant('BILLING.ITEMS_LOAD_FAILED'));
      },
    });
  }

  private resetNewItem(): void {
    this.newItemDescription.set('');
    this.newItemCategory.set('SERVICE');
    this.newItemQuantity.set(1);
    this.newItemUnitPrice.set(null);
  }

  /**
   * QA D147/D656: only a DRAFT takes line changes; an issued invoice is
   * cancelled and re-issued instead (the server refuses them too).
   */
  canEditLines(inv: BillingInvoiceResponse): boolean {
    return this.canEdit && inv.status === 'DRAFT';
  }

  addItem(): void {
    const inv = this.selectedInvoice();
    if (!inv || this.itemSaving()) return;
    // QA D1089: the button is never dead; the field that is missing or wrong says so.
    const qty = this.newItemQuantity();
    const priceProblem = cfaPriceProblem(this.newItemUnitPrice());
    // QA D1344 (round 29): every wrong field at once, each under its own
    // reason — a quantity of 0 is not "required", it is not above zero.
    const refusals: FieldRefusal[] = [];
    if (this.newItemDescription().trim().length === 0) {
      refusals.push({ id: 'line-desc', message: this.translate.instant('COMMON.FIELD_REQUIRED') });
    }
    const qtyKey = lineQuantityMessageKey(qty);
    if (qtyKey) refusals.push({ id: 'line-qty', message: this.translate.instant(qtyKey) });
    if (priceProblem) {
      refusals.push({
        id: 'line-price',
        message: this.translate.instant(cfaAmountMessageKey(priceProblem, LINE_PRICE_REQUIRED_KEY)),
      });
    }
    if (refusals.length > 0) {
      refuseFields(this.host.nativeElement, refusals, ['line-desc', 'line-qty', 'line-price']);
      return;
    }
    this.itemSaving.set(true);
    this.billingService
      .createInvoiceItem({
        billingInvoiceId: inv.id,
        itemDescription: this.newItemDescription().trim(),
        itemCategory: this.newItemCategory(),
        quantity: this.newItemQuantity() ?? 1,
        unitPrice: this.newItemUnitPrice() ?? 0,
      })
      .subscribe({
        next: () => {
          this.itemSaving.set(false);
          this.resetNewItem();
          this.toast.success(this.translate.instant('BILLING.ITEM_ADDED'));
          this.refreshSelected(inv.id);
        },
        error: (err: HttpErrorResponse) => {
          this.itemSaving.set(false);
          this.toast.error(
            err.error?.message ?? this.translate.instant('BILLING.ITEM_SAVE_FAILED'),
          );
        },
      });
  }

  /* QA D1088: a line posted at zero (the checkout's unpriced charges) is priced in place. */
  editingLineId = signal<string | null>(null);
  editLineQuantity = signal<number | null>(null);
  editLineUnitPrice = signal<number | null>(null);

  startEditLine(item: InvoiceItemResponse): void {
    this.editingLineId.set(item.id);
    this.editLineQuantity.set(item.quantity ?? 1);
    this.editLineUnitPrice.set(Number(item.unitPrice) > 0 ? Number(item.unitPrice) : null);
    // QA D1195 / D1155: the editing row is an appInlineSubForm — it takes the
    // keyboard, keeps Escape to itself (asking first over a changed value) and
    // gives the keyboard back to the line's pencil when it closes.
  }

  cancelEditLine(): void {
    this.editingLineId.set(null);
  }

  canSaveLine(): boolean {
    return (
      !this.itemSaving() &&
      lineQuantityMessageKey(this.editLineQuantity()) === null &&
      cfaPriceProblem(this.editLineUnitPrice()) === null
    );
  }

  saveLine(item: InvoiceItemResponse): void {
    const inv = this.selectedInvoice();
    if (!inv || this.itemSaving()) return;
    // QA D1089: the button is never dead; the field that is missing or wrong says so.
    const qtyKey = lineQuantityMessageKey(this.editLineQuantity());
    const priceProblem = cfaPriceProblem(this.editLineUnitPrice());
    if (qtyKey || priceProblem) {
      // QA D1296 class (round 28): every wrong field at once, each under its
      // own reason (the price said "required" when only the quantity was).
      // QA D1344 (round 29): a quantity of 0 says it must be above zero.
      const refusals: FieldRefusal[] = [];
      if (qtyKey) {
        refusals.push({ id: 'line-edit-qty', message: this.translate.instant(qtyKey) });
      }
      if (priceProblem) {
        refusals.push({
          id: 'line-edit-price',
          message: this.translate.instant(
            cfaAmountMessageKey(priceProblem, LINE_PRICE_REQUIRED_KEY),
          ),
        });
      }
      refuseFields(this.host.nativeElement, refusals, ['line-edit-qty', 'line-edit-price']);
      return;
    }
    this.itemSaving.set(true);
    this.billingService
      .updateInvoiceItem(item.id, {
        billingInvoiceId: inv.id,
        itemDescription: item.itemDescription,
        itemCategory: item.itemCategory as InvoiceItemCategory,
        quantity: this.editLineQuantity() ?? 1,
        unitPrice: this.editLineUnitPrice() ?? 0,
      })
      .subscribe({
        next: () => {
          this.itemSaving.set(false);
          this.editingLineId.set(null);
          this.toast.success(this.translate.instant('BILLING.ITEM_UPDATED'));
          // QA D1343 (round 29): the lines reload (the table leaves under its
          // loading flag), so the keyboard goes back to this line's pencil
          // once it is rendered again, not to the dialog box.
          this.refreshSelected(inv.id, item.id);
        },
        error: (err: HttpErrorResponse) => {
          this.itemSaving.set(false);
          this.toast.error(
            err.error?.message ?? this.translate.instant('BILLING.ITEM_SAVE_FAILED'),
          );
        },
      });
  }

  async removeItem(item: InvoiceItemResponse): Promise<void> {
    const inv = this.selectedInvoice();
    if (!inv || this.itemSaving()) return;
    // QA D147: a line deletion is not silent. QA D1229: asked in the app.
    const confirmed = await this.confirmService.ask({
      titleKey: 'BILLING.ITEM_REMOVE_CONFIRM',
      confirmKey: 'COMMON.DELETE',
    });
    if (!confirmed || this.itemSaving()) return;
    this.itemSaving.set(true);
    this.billingService.deleteInvoiceItem(item.id).subscribe({
      next: () => {
        this.itemSaving.set(false);
        this.toast.success(this.translate.instant('BILLING.ITEM_REMOVED'));
        this.refreshSelected(inv.id);
      },
      error: (err: HttpErrorResponse) => {
        this.itemSaving.set(false);
        this.toast.error(err.error?.message ?? this.translate.instant('BILLING.ITEM_SAVE_FAILED'));
      },
    });
  }

  /** Re-read the invoice after a line change: the server recomputed its total. */
  private refreshSelected(invoiceId: string, focusLineId?: string): void {
    this.loadItems(invoiceId, focusLineId);
    this.billingService.getInvoice(invoiceId).subscribe({
      next: (updated) => {
        if (this.selectedInvoice()?.id === updated.id) this.selectedInvoice.set(updated);
        this.invoices.update((list) => list.map((i) => (i.id === updated.id ? updated : i)));
        this.refreshStats();
        this.applyFilter();
      },
      error: () => this.toast.error(this.translate.instant('BILLING.LOAD_FAILED')),
    });
  }

  /* ── Payments ledger ── */

  private loadPayments(invoiceId: string): void {
    this.billingService.listPayments(invoiceId).subscribe({
      next: (rows) => {
        if (this.selectedInvoice()?.id === invoiceId) this.payments.set(rows ?? []);
      },
      error: () => this.payments.set([]),
    });
  }

  private applyUpdated(updated: BillingInvoiceResponse): void {
    if (this.selectedInvoice()?.id === updated.id) this.selectedInvoice.set(updated);
    this.invoices.update((list) => list.map((i) => (i.id === updated.id ? updated : i)));
    this.refreshStats();
    this.applyFilter();
    this.loadPayments(updated.id);
  }

  confirmPendingPayment(p: PaymentRow): void {
    const inv = this.selectedInvoice();
    if (!inv || this.paymentActionBusy()) return;
    this.paymentActionBusy.set(true);
    this.billingService.confirmPayment(inv.id, p.id).subscribe({
      next: (updated) => {
        this.paymentActionBusy.set(false);
        this.toast.success(this.translate.instant('BILLING.PAYMENT_CONFIRMED'));
        this.applyUpdated(updated);
      },
      error: (err: HttpErrorResponse) => {
        this.paymentActionBusy.set(false);
        this.toast.error(err.error?.message ?? this.translate.instant('BILLING.PAYMENT_FAILED'));
      },
    });
  }

  openReversal(p: PaymentRow, mode: 'reverse' | 'reject'): void {
    this.reversingPayment.set(p);
    this.reversalMode.set(mode);
    this.reversalReason.set('');
  }

  closeReversal(): void {
    this.reversingPayment.set(null);
  }

  submitReversal(): void {
    const inv = this.selectedInvoice();
    const p = this.reversingPayment();
    const reason = this.reversalReason().trim();
    if (!inv || !p || this.paymentActionBusy()) return;
    if (!reason) {
      // QA D1069: the button is never dead; the reason is asked for where it is typed.
      refuseFields(
        this.host.nativeElement,
        ['reversal-reason'],
        ['reversal-reason'],
        this.translate.instant('BILLING.REVERSAL_REASON_REQUIRED'),
      );
      return;
    }
    this.paymentActionBusy.set(true);
    const reversing = this.reversalMode() === 'reverse';
    const op = reversing
      ? this.billingService.reversePayment(inv.id, p.id, reason)
      : this.billingService.rejectPayment(inv.id, p.id, reason);
    op.subscribe({
      next: (updated) => {
        this.paymentActionBusy.set(false);
        this.toast.success(
          this.translate.instant(
            reversing ? 'BILLING.PAYMENT_REVERSED' : 'BILLING.PAYMENT_REJECTED',
          ),
        );
        this.closeReversal();
        this.applyUpdated(updated);
      },
      error: (err: HttpErrorResponse) => {
        this.paymentActionBusy.set(false);
        this.toast.error(err.error?.message ?? this.translate.instant('BILLING.PAYMENT_FAILED'));
      },
    });
  }

  /** QA D1009: each receipt keeps its own number ("…-R2") instead of sharing one name. */
  downloadReceipt(p: PaymentRow): void {
    const inv = this.selectedInvoice();
    if (!inv) return;
    this.billingService.getReceiptPdf(inv.id, p.id).subscribe({
      next: (res) =>
        saveResponse(
          res,
          `receipt-${inv.invoiceNumber}-${(p.paymentDate ?? '').substring(0, 10)}.pdf`,
        ),
      error: (err: unknown) =>
        this.toast.error(refusalReason(err) ?? this.translate.instant('BILLING.PDF_FAILED')),
    });
  }

  /** QA D1009: the invoice PDF is named by its number, not its database id. */
  downloadPdf(inv: BillingInvoiceResponse): void {
    this.billingService.getInvoicePdf(inv.id).subscribe({
      next: (res) => saveResponse(res, `invoice-${inv.invoiceNumber}.pdf`),
      error: (err: unknown) =>
        this.toast.error(refusalReason(err) ?? this.translate.instant('BILLING.PDF_FAILED')),
    });
  }

  /* ── Email ── */

  openEmail(inv: BillingInvoiceResponse): void {
    this.emailInv.set(inv);
    this.emailTo.set(inv.patientEmail ?? '');
    this.emailCc.set('');
    this.emailMessage.set('');
    this.emailAttachPdf.set(true);
    this.showEmailModal.set(true);
  }

  closeEmail(): void {
    this.showEmailModal.set(false);
    this.emailInv.set(null);
  }

  sendEmail(): void {
    const inv = this.emailInv();
    if (!inv) return;
    const to = emailAddresses(this.emailTo());
    const cc = emailAddresses(this.emailCc());
    if (to.length === 0) {
      this.refuseEmail([EMAIL_TO_ID], this.translate.instant('BILLING.EMAIL_TO_REQUIRED'));
      return;
    }
    // QA D1179: a malformed address is refused on its field, in the reader's
    // language, before anything is sent (the server answered in raw Jakarta text).
    const badTo = invalidEmailAddresses(to);
    const badCc = invalidEmailAddresses(cc);
    if (badTo.length > 0 || badCc.length > 0) {
      this.refuseEmail(
        [...(badTo.length > 0 ? [EMAIL_TO_ID] : []), ...(badCc.length > 0 ? [EMAIL_CC_ID] : [])],
        this.translate.instant('BILLING.EMAIL_ADDRESS_INVALID', {
          addresses: [...badTo, ...badCc].join(', '),
        }),
      );
      return;
    }
    refuseFields(this.host.nativeElement, [], EMAIL_FIELD_IDS, '');
    this.emailSending.set(true);
    this.billingService
      .emailInvoice(inv.id, {
        to,
        cc: cc.length ? cc : undefined,
        message: this.emailMessage().trim() || undefined,
        locale: this.translate.currentLang || 'fr',
        attachPdf: this.emailAttachPdf(),
      })
      .subscribe({
        next: () => {
          this.emailSending.set(false);
          this.toast.success(this.translate.instant('BILLING.EMAIL_SENT'));
          this.closeEmail();
        },
        error: (err: HttpErrorResponse) => {
          this.emailSending.set(false);
          this.toast.error(
            err.status === 503
              ? this.translate.instant('BILLING.EMAIL_UNAVAILABLE')
              : (err.error?.message ?? this.translate.instant('BILLING.EMAIL_FAILED')),
          );
        },
      });
  }

  /** QA D1072 / D1179: the refused address field is marked and focused; the toast says it too. */
  private refuseEmail(fields: string[], message: string): void {
    refuseFields(this.host.nativeElement, fields, EMAIL_FIELD_IDS, message);
    this.toast.error(message);
  }

  /* ── Payment ── */

  canPayInvoice(inv: BillingInvoiceResponse): boolean {
    return (
      this.canRecordPayment &&
      (inv.status === 'SENT' || inv.status === 'PARTIALLY_PAID') &&
      (inv.balanceDue ?? 0) > 0
    );
  }

  /** QA D634: what the patient declared on the open invoice and staff have not confirmed yet. */
  paymentPending = signal(0);

  openPayment(inv: BillingInvoiceResponse): void {
    this.paymentInv.set(inv);
    this.paymentAmount.set(inv.balanceDue ?? null);
    this.paymentPending.set(0);
    this.paymentMethod.set('CASH');
    this.paymentReference.set('');
    this.showPaymentModal.set(true);
    // QA D634: the modal prefilled the whole balance and said nothing of a
    // pending patient declaration, so the cashier took the money twice once
    // the declaration was confirmed. The prefill leaves the declared part out.
    this.billingService.listPayments(inv.id).subscribe({
      next: (rows) => {
        if (this.paymentInv()?.id !== inv.id) return;
        // QA D1321: the same rule as the desk's pay-collect (shared/payment-prefill).
        const pending = pendingDeclared(rows);
        this.paymentPending.set(pending);
        if (pending > 0 && this.paymentAmount() === (inv.balanceDue ?? null)) {
          this.paymentAmount.set(amountStillDue(inv.balanceDue, pending));
        }
      },
      error: () => this.paymentPending.set(0),
    });
  }

  /** QA D1007: the server refuses these methods without a transaction reference. */
  paymentReferenceRequired(): boolean {
    return ['MOBILE_MONEY', 'INSURANCE', 'BANK_TRANSFER'].includes(this.paymentMethod());
  }

  closePayment(): void {
    this.showPaymentModal.set(false);
    this.paymentInv.set(null);
  }

  recordPayment(): void {
    const inv = this.paymentInv();
    const amount = this.paymentAmount();
    if (!inv) return;
    // QA D1306 class (round 28): a refusal was a toast only, one field per
    // press. Every refused field is now marked at once, each under its own
    // reason, focused, and named in the toast.
    const refusals = this.paymentRefusals(inv, amount);
    if (refusals.length > 0) {
      refuseFields(this.host.nativeElement, refusals, PAYMENT_FIELD_IDS);
      this.toast.error(joinReasons(refusals.map((r) => r.message)) ?? '');
      return;
    }
    // A field fixed since the last press loses its mark.
    refuseFields(this.host.nativeElement, [], PAYMENT_FIELD_IDS);
    if (amount === null) return; // refused above; narrows the type
    const reference = this.paymentReference().trim();
    this.paymentSaving.set(true);
    const payment = { amount, method: this.paymentMethod(), reference: reference || undefined };
    this.billingService.recordPayment(inv.id, payment).subscribe({
      next: (updated) => {
        this.paymentSaving.set(false);
        this.toast.success(this.translate.instant('BILLING.PAYMENT_RECORDED'));
        this.closePayment();
        this.invoices.update((list) => list.map((i) => (i.id === updated.id ? updated : i)));
        this.refreshStats();
        this.applyFilter();
        if (this.selectedInvoice()?.id === updated.id) {
          this.selectedInvoice.set(updated);
          this.loadPayments(updated.id);
        }
      },
      error: (err: HttpErrorResponse) => {
        this.paymentSaving.set(false);
        this.toast.error(err.error?.message ?? this.translate.instant('BILLING.PAYMENT_FAILED'));
      },
    });
  }

  /** The payment form's refused fields, each with its reason (empty: it may be sent). */
  private paymentRefusals(inv: BillingInvoiceResponse, amount: number | null): FieldRefusal[] {
    const refusals: FieldRefusal[] = [];
    // QA D184 / D1089: an amount first, then whole francs (francs CFA have no cents).
    const amountProblem = cfaAmountProblem(amount);
    if (amountProblem || amount === null) {
      refusals.push({
        id: 'payment-amount',
        message: this.translate.instant(
          cfaAmountMessageKey(amountProblem ?? 'required', CFA_AMOUNT_REQUIRED_KEY),
        ),
      });
    } else if (amount > (inv.balanceDue ?? 0)) {
      refusals.push({
        id: 'payment-amount',
        message: this.translate.instant('BILLING.PAYMENT_EXCEEDS_BALANCE'),
      });
    }
    if (this.paymentReferenceRequired() && !this.paymentReference().trim()) {
      refusals.push({
        id: 'payment-reference',
        message: this.translate.instant('BILLING.PAYMENT_REFERENCE_REQUIRED'),
      });
    }
    return refusals;
  }

  getStatusClass(status: string): string {
    switch (status) {
      case 'PAID':
        return 'status-badge status-paid';
      case 'DRAFT':
        return 'status-badge status-pending';
      case 'SENT':
        return 'status-badge status-pending';
      case 'PARTIALLY_PAID':
        return 'status-badge status-partial';
      case 'CANCELLED':
        return 'status-badge status-cancelled';
      default:
        return 'status-badge';
    }
  }

  /** A local calendar day as yyyy-MM-dd, the value an input[type=date] takes. */
  private isoDay(day: Date): string {
    const mm = String(day.getMonth() + 1).padStart(2, '0');
    const dd = String(day.getDate()).padStart(2, '0');
    return `${day.getFullYear()}-${mm}-${dd}`;
  }

  formatCurrency(amount: number): string {
    // QA D621: "4 000 F CFA" in every language, not XOF / F CFA4,000.
    return formatCfa(amount);
  }
}
