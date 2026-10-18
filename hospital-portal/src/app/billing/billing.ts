import { Component, inject, OnInit, signal, ChangeDetectionStrategy } from '@angular/core';
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
  INVOICE_STATUSES,
} from '../services/billing.service';
import { PatientPickerComponent } from '../shared/patient-picker/patient-picker.component';
import { PatientResponse } from '../services/patient.service';
import { ToastService } from '../core/toast.service';
import { PermissionService } from '../core/permission.service';
import { RoleContextService } from '../core/role-context.service';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { currentLocale } from '../shared/i18n/app-locale';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';

type BillingTab = 'all' | 'outstanding' | 'paid' | 'overdue';

@Component({
  selector: 'app-billing',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule, EnumLabelPipe, PatientPickerComponent],
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

  // Permission-gated action flags
  readonly canCreate = this.permissions.hasAnyPermission('Create Invoice', 'Manage Billing');
  readonly canEdit = this.permissions.hasAnyPermission('Create Invoice', 'Manage Billing');
  readonly canDelete = this.permissions.hasAnyPermission('Delete Invoice', 'Manage Billing');
  readonly canEmail = this.permissions.hasAnyPermission('Email Invoice', 'Manage Billing');
  readonly canViewReports = this.permissions.hasAnyPermission(
    'View Billing Reports',
    'Manage Billing',
  );
  /** SecurityConfig excludes RECEPTIONIST from POST /billing-invoices/**, so
   *  payment recording 403s for receptionists despite the @PreAuthorize. */
  readonly canRecordPayment = this.roleContext.hasAnyActiveRole([
    'ROLE_SUPER_ADMIN',
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_BILLING_SPECIALIST',
    'ROLE_ACCOUNTANT',
  ]);

  invoices = signal<BillingInvoiceResponse[]>([]);
  filtered = signal<BillingInvoiceResponse[]>([]);
  searchTerm = '';
  loading = signal(true);
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
  /** With lines, the total is their sum and cannot be typed (QA D72). */
  editingHasLines = signal(false);

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
  paymentSaving = signal(false);

  readonly invoiceStatuses: InvoiceStatus[] = INVOICE_STATUSES;

  ngOnInit(): void {
    this.loadInvoices();
  }

  emptyForm(): BillingInvoiceRequest {
    return {
      patientId: '',
      invoiceDate: '',
      dueDate: '',
      totalAmount: 0,
      status: 'DRAFT',
    };
  }

  /** Statuses staff may choose; PAID/PARTIALLY_PAID come from recorded payments. */
  statusOptions(): InvoiceStatus[] {
    if (!this.editing()) return ['DRAFT', 'SENT'];
    const current = this.editingStatus();
    if (current === 'PAID' || current === 'PARTIALLY_PAID') return [current];
    return ['DRAFT', 'SENT', 'CANCELLED'];
  }

  statusLocked(): boolean {
    const current = this.editingStatus();
    return this.editing() && (current === 'PAID' || current === 'PARTIALLY_PAID');
  }

  openCreate(): void {
    this.form = this.emptyForm();
    // One key per form: a double click or a retry creates one invoice (QA D94).
    this.form.clientRequestId = crypto.randomUUID();
    this.formPatient.set(null);
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
  }

  closeModal(): void {
    this.showModal.set(false);
  }

  submitForm(): void {
    if (this.saving()) return;
    this.saving.set(true);
    const op = this.editing()
      ? this.billingService.updateInvoice(this.editingId()!, this.form)
      : this.billingService.createInvoice(this.form);
    op.subscribe({
      next: () => {
        this.toast.success(
          this.translate.instant(this.editing() ? 'BILLING.UPDATED' : 'BILLING.CREATED'),
        );
        this.closeModal();
        this.saving.set(false);
        this.loadInvoices(this.page());
      },
      error: (err: HttpErrorResponse) => {
        this.toast.error(err.error?.message ?? this.translate.instant('BILLING.SAVE_FAILED'));
        this.saving.set(false);
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
      error: () => {
        this.toast.error(this.translate.instant('BILLING.DELETE_FAILED'));
        this.deleting.set(false);
      },
    });
  }

  loadInvoices(page = 0): void {
    this.loading.set(true);
    if (this.activeTab() === 'overdue') {
      // Dedicated server-side worklist (SENT/PARTIALLY_PAID past due).
      this.billingService.getOverdue().subscribe({
        next: (list) => {
          this.invoices.set(list ?? []);
          this.page.set(0);
          this.totalPages.set(1);
          this.totalElements.set(list?.length ?? 0);
          this.computeStats(list ?? []);
          this.applyFilter();
          this.loading.set(false);
        },
        error: () => {
          this.toast.error(this.translate.instant('BILLING.LOAD_FAILED'));
          this.loading.set(false);
        },
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
    this.billingService
      .searchInvoices(
        {
          statuses,
          fromDate: this.filterFromDate() || undefined,
          toDate: this.filterToDate() || undefined,
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
          this.computeStats(list);
          this.applyFilter();
          this.loading.set(false);
        },
        error: () => {
          this.toast.error(this.translate.instant('BILLING.LOAD_FAILED'));
          this.loading.set(false);
        },
      });
  }

  private computeStats(list: BillingInvoiceResponse[]): void {
    const today = new Date();
    this.stats.set({
      total: this.totalElements(),
      paid: list.filter((i) => i.status === 'PAID').length,
      outstanding: list.filter((i) => i.status === 'SENT' || i.status === 'PARTIALLY_PAID').length,
      overdue: list.filter(
        (i) =>
          (i.status === 'SENT' || i.status === 'PARTIALLY_PAID') &&
          !!i.dueDate &&
          new Date(i.dueDate) < today,
      ).length,
      totalRevenue: list.reduce((sum, i) => sum + (i.amountPaid ?? 0), 0),
      balance: list.reduce((sum, i) => sum + (i.balanceDue ?? 0), 0),
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

  applyFilter(): void {
    let list = this.invoices();
    const term = this.searchTerm.toLowerCase().trim();
    if (term) {
      list = list.filter(
        (inv) =>
          (inv.invoiceNumber ?? '').toLowerCase().includes(term) ||
          (inv.patientFullName ?? '').toLowerCase().includes(term) ||
          (inv.status ?? '').toLowerCase().includes(term),
      );
    }
    this.filtered.set(list);
  }

  viewInvoice(inv: BillingInvoiceResponse): void {
    this.selectedInvoice.set(inv);
    this.resetNewItem();
    this.loadItems(inv.id);
  }

  closeDetail(): void {
    this.selectedInvoice.set(null);
    this.items.set([]);
  }

  /* ── Invoice lines ── */

  private loadItems(invoiceId: string): void {
    this.itemsLoading.set(true);
    this.billingService.getInvoiceItems(invoiceId).subscribe({
      next: (lines) => {
        this.items.set(lines ?? []);
        this.itemsLoading.set(false);
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

  canEditLines(inv: BillingInvoiceResponse): boolean {
    return this.canEdit && inv.status !== 'CANCELLED';
  }

  canAddItem(): boolean {
    const qty = this.newItemQuantity();
    const price = this.newItemUnitPrice();
    return (
      !this.itemSaving() &&
      this.newItemDescription().trim().length > 0 &&
      !!qty &&
      qty >= 1 &&
      !!price &&
      price > 0
    );
  }

  addItem(): void {
    const inv = this.selectedInvoice();
    if (!inv || !this.canAddItem()) return;
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

  removeItem(item: InvoiceItemResponse): void {
    const inv = this.selectedInvoice();
    if (!inv || this.itemSaving()) return;
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
  private refreshSelected(invoiceId: string): void {
    this.loadItems(invoiceId);
    this.billingService.getInvoice(invoiceId).subscribe({
      next: (updated) => {
        if (this.selectedInvoice()?.id === updated.id) this.selectedInvoice.set(updated);
        this.invoices.update((list) => list.map((i) => (i.id === updated.id ? updated : i)));
        this.computeStats(this.invoices());
        this.applyFilter();
      },
      error: () => this.toast.error(this.translate.instant('BILLING.LOAD_FAILED')),
    });
  }

  downloadPdf(id: string): void {
    this.billingService.getInvoicePdf(id).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `invoice-${id}.pdf`;
        a.click();
        URL.revokeObjectURL(url);
      },
      error: () => this.toast.error(this.translate.instant('BILLING.PDF_FAILED')),
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
    const to = this.emailTo()
      .split(/[\s,;]+/)
      .map((a) => a.trim())
      .filter(Boolean);
    if (to.length === 0) {
      this.toast.error(this.translate.instant('BILLING.EMAIL_TO_REQUIRED'));
      return;
    }
    const cc = this.emailCc()
      .split(/[\s,;]+/)
      .map((a) => a.trim())
      .filter(Boolean);
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
          // Sending flips the invoice to SENT server-side.
          this.loadInvoices(this.page());
        },
        error: (err: HttpErrorResponse) => {
          this.emailSending.set(false);
          this.toast.error(
            err.status === 503
              ? this.translate.instant('BILLING.EMAIL_UNAVAILABLE')
              : this.translate.instant('BILLING.EMAIL_FAILED'),
          );
        },
      });
  }

  /* ── Payment ── */

  canPayInvoice(inv: BillingInvoiceResponse): boolean {
    return (
      this.canRecordPayment &&
      (inv.status === 'SENT' || inv.status === 'PARTIALLY_PAID') &&
      (inv.balanceDue ?? 0) > 0
    );
  }

  openPayment(inv: BillingInvoiceResponse): void {
    this.paymentInv.set(inv);
    this.paymentAmount.set(inv.balanceDue ?? null);
    this.showPaymentModal.set(true);
  }

  closePayment(): void {
    this.showPaymentModal.set(false);
    this.paymentInv.set(null);
  }

  recordPayment(): void {
    const inv = this.paymentInv();
    const amount = this.paymentAmount();
    if (!inv || !amount || amount <= 0) {
      this.toast.error(this.translate.instant('BILLING.PAYMENT_AMOUNT_REQUIRED'));
      return;
    }
    if (amount > (inv.balanceDue ?? 0)) {
      this.toast.error(this.translate.instant('BILLING.PAYMENT_EXCEEDS_BALANCE'));
      return;
    }
    this.paymentSaving.set(true);
    this.billingService.recordPayment(inv.id, amount).subscribe({
      next: (updated) => {
        this.paymentSaving.set(false);
        this.toast.success(this.translate.instant('BILLING.PAYMENT_RECORDED'));
        this.closePayment();
        this.invoices.update((list) => list.map((i) => (i.id === updated.id ? updated : i)));
        this.computeStats(this.invoices());
        this.applyFilter();
      },
      error: (err: HttpErrorResponse) => {
        this.paymentSaving.set(false);
        this.toast.error(err.error?.message ?? this.translate.instant('BILLING.PAYMENT_FAILED'));
      },
    });
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

  formatCurrency(amount: number): string {
    return new Intl.NumberFormat(currentLocale(), {
      style: 'currency',
      currency: 'XOF',
      maximumFractionDigits: 0,
    }).format(amount ?? 0);
  }
}
