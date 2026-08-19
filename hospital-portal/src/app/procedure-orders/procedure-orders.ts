import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  ProcedureOrderService,
  ProcedureOrderRequest,
  ProcedureOrderResponse,
  ProcedureOrderStatus,
  ProcedureOrderUpdate,
  ProcedureUrgency,
} from '../services/procedure-order.service';
import { PatientResponse } from '../services/patient.service';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';
import { PatientPickerComponent } from '../shared/patient-picker/patient-picker.component';

type Worklist = 'all' | 'pending-consent' | ProcedureOrderStatus;

/**
 * Procedure orders (Phase 3 task 16): order → consent → schedule → progress →
 * complete/cancel against /procedure-orders. Detail renders from the list row
 * (never GET-by-id) because HOSPITAL_ADMIN may list but not read single
 * orders on the backend.
 */
@Component({
  selector: 'app-procedure-orders',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CommonModule, FormsModule, TranslateModule, PatientPickerComponent],
  templateUrl: './procedure-orders.html',
  styleUrl: './procedure-orders.scss',
})
export class ProcedureOrdersComponent implements OnInit {
  private readonly orderService = inject(ProcedureOrderService);
  private readonly auth = inject(AuthService);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  private hospitalId: string | null = null;

  /** Create/update = DOCTOR/NURSE/SUPER_ADMIN; HOSPITAL_ADMIN is read-only. */
  readonly canManage = this.roleContext.hasAnyActiveRole([
    'ROLE_DOCTOR',
    'ROLE_NURSE',
    'ROLE_SUPER_ADMIN',
  ]);
  /** Cancel = DOCTOR/SUPER_ADMIN only (no NURSE). */
  readonly canCancel = this.roleContext.hasAnyActiveRole(['ROLE_DOCTOR', 'ROLE_SUPER_ADMIN']);

  worklist = signal<Worklist>('all');
  readonly worklists: Worklist[] = [
    'all',
    'ORDERED',
    'SCHEDULED',
    'pending-consent',
    'IN_PROGRESS',
    'COMPLETED',
    'CANCELLED',
  ];

  orders = signal<ProcedureOrderResponse[]>([]);
  loading = signal(false);
  loadError = signal(false);

  readonly urgencies: ProcedureUrgency[] = ['ROUTINE', 'ELECTIVE', 'URGENT', 'EMERGENT'];
  readonly lateralities = ['LEFT', 'RIGHT', 'BILATERAL', 'NOT_APPLICABLE'];

  /* ── Create modal ── */
  showOrderModal = signal(false);
  orderSaving = signal(false);
  orderForm: ProcedureOrderRequest = this.emptyOrderForm();
  orderPatient = signal<PatientResponse | null>(null);

  /* ── Detail + lifecycle actions ── */
  viewedOrder = signal<ProcedureOrderResponse | null>(null);
  /** 'schedule' | 'consent' | 'cancel' inline action form inside the detail. */
  detailAction = signal<'schedule' | 'consent' | 'cancel' | null>(null);
  actionBusy = signal(false);
  scheduleDatetime = '';
  consentBy = '';
  consentFormLocation = '';
  cancelReason = '';

  ngOnInit(): void {
    this.hospitalId = this.roleContext.activeHospitalId ?? this.auth.getHospitalId();
    this.load();
  }

  setWorklist(w: Worklist): void {
    this.worklist.set(w);
    this.load();
  }

  load(): void {
    if (!this.hospitalId) return;
    const hospitalId = this.hospitalId;
    const worklist = this.worklist();
    this.loading.set(true);
    this.loadError.set(false);
    const source =
      worklist === 'pending-consent'
        ? this.orderService.pendingConsent(hospitalId)
        : this.orderService.byHospital(hospitalId, worklist === 'all' ? undefined : worklist);
    source.subscribe({
      next: (orders) => {
        this.orders.set(orders ?? []);
        this.loading.set(false);
      },
      error: () => {
        this.loadError.set(true);
        this.loading.set(false);
        this.toast.error(this.translate.instant('PROCEDURES.LOAD_ERROR'));
      },
    });
  }

  statusClass(status: ProcedureOrderStatus): string {
    switch (status) {
      case 'ORDERED':
        return 'status-badge status-submitted';
      case 'SCHEDULED':
      case 'READY_FOR_PROCEDURE':
        return 'status-badge status-acknowledged';
      case 'PRE_OP_CLEARANCE_PENDING':
      case 'POSTPONED':
        return 'status-badge status-warning';
      case 'IN_PROGRESS':
        return 'status-badge status-in-progress';
      case 'COMPLETED':
        return 'status-badge status-completed';
      default:
        return 'status-badge status-cancelled';
    }
  }

  urgencyClass(urgency: ProcedureUrgency | undefined): string {
    switch (urgency) {
      case 'EMERGENT':
        return 'risk-badge risk-high';
      case 'URGENT':
        return 'risk-badge risk-moderate';
      default:
        return 'risk-badge risk-low';
    }
  }

  isTerminal(order: ProcedureOrderResponse): boolean {
    return order.status === 'COMPLETED' || order.status === 'CANCELLED';
  }

  needsConsent(order: ProcedureOrderResponse): boolean {
    return !order.consentObtained && !this.isTerminal(order);
  }

  /** Local-timezone yyyy-MM-ddTHH:mm for datetime-local inputs. */
  private nowLocalDatetime(): string {
    const d = new Date();
    const pad = (n: number): string => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
  }

  /* ── Create ── */

  emptyOrderForm(): ProcedureOrderRequest {
    return {
      patientId: '',
      hospitalId: '',
      procedureName: '',
      indication: '',
      urgency: 'ROUTINE',
      requiresAnesthesia: false,
      requiresSedation: false,
      bloodProductsRequired: false,
      imagingGuidanceRequired: false,
    };
  }

  openCreate(): void {
    this.orderForm = this.emptyOrderForm();
    this.orderPatient.set(null);
    this.showOrderModal.set(true);
  }

  closeOrderModal(): void {
    this.showOrderModal.set(false);
  }

  onPatientPicked(p: PatientResponse | null): void {
    this.orderPatient.set(p);
    this.orderForm.patientId = p?.id ?? '';
  }

  submitOrder(): void {
    const form = this.orderForm;
    if (!form.patientId || !form.procedureName.trim() || !form.indication.trim()) {
      this.toast.error(this.translate.instant('PROCEDURES.REQUIRED_FIELDS'));
      return;
    }
    form.hospitalId = this.hospitalId ?? '';
    this.orderSaving.set(true);
    this.orderService.create(form).subscribe({
      next: (created) => {
        this.toast.success(this.translate.instant('PROCEDURES.CREATED'));
        this.orderSaving.set(false);
        this.closeOrderModal();
        this.orders.update((list) => [created, ...list]);
      },
      error: () => {
        this.toast.error(this.translate.instant('PROCEDURES.SAVE_ERROR'));
        this.orderSaving.set(false);
      },
    });
  }

  /* ── Detail + lifecycle ── */

  openOrder(order: ProcedureOrderResponse): void {
    this.viewedOrder.set(order);
    this.detailAction.set(null);
  }

  closeOrder(): void {
    this.viewedOrder.set(null);
    this.detailAction.set(null);
  }

  openAction(action: 'schedule' | 'consent' | 'cancel'): void {
    const order = this.viewedOrder();
    this.detailAction.set(action);
    if (action === 'schedule') {
      this.scheduleDatetime = order?.scheduledDatetime?.substring(0, 16) ?? '';
    } else if (action === 'consent') {
      const profile = this.auth.getUserProfile();
      this.consentBy = `${profile?.firstName ?? ''} ${profile?.lastName ?? ''}`.trim();
      this.consentFormLocation = '';
    } else {
      this.cancelReason = '';
    }
  }

  cancelAction(): void {
    this.detailAction.set(null);
  }

  submitSchedule(): void {
    const order = this.viewedOrder();
    if (!order || !this.scheduleDatetime) return;
    // Backend auto-promotes ORDERED→SCHEDULED; send the status explicitly so
    // rescheduling from POSTPONED also lands back on SCHEDULED.
    this.applyUpdate(
      order,
      { status: 'SCHEDULED', scheduledDatetime: this.scheduleDatetime },
      'PROCEDURES.SCHEDULED_SUCCESS',
    );
  }

  submitConsent(): void {
    const order = this.viewedOrder();
    if (!order || !this.consentBy.trim()) return;
    this.applyUpdate(
      order,
      {
        consentObtained: true,
        consentObtainedAt: this.nowLocalDatetime(),
        consentObtainedBy: this.consentBy.trim(),
        consentFormLocation: this.consentFormLocation.trim() || undefined,
      },
      'PROCEDURES.CONSENT_RECORDED',
    );
  }

  setStatus(status: ProcedureOrderStatus): void {
    const order = this.viewedOrder();
    if (!order) return;
    this.applyUpdate(order, { status }, 'PROCEDURES.STATUS_UPDATED');
  }

  toggleSiteMarked(): void {
    const order = this.viewedOrder();
    if (!order) return;
    this.applyUpdate(order, { siteMarked: !order.siteMarked }, 'PROCEDURES.STATUS_UPDATED');
  }

  private applyUpdate(
    order: ProcedureOrderResponse,
    update: ProcedureOrderUpdate,
    successKey: string,
  ): void {
    this.actionBusy.set(true);
    this.orderService.update(order.id, update).subscribe({
      next: (updated) => {
        this.toast.success(this.translate.instant(successKey));
        this.actionBusy.set(false);
        this.detailAction.set(null);
        this.replaceOrder(updated);
      },
      error: () => {
        this.toast.error(this.translate.instant('PROCEDURES.UPDATE_ERROR'));
        this.actionBusy.set(false);
      },
    });
  }

  submitCancel(): void {
    const order = this.viewedOrder();
    const reason = this.cancelReason.trim();
    if (!order || !reason) return;
    this.actionBusy.set(true);
    this.orderService.cancel(order.id, reason).subscribe({
      next: (updated) => {
        this.toast.success(this.translate.instant('PROCEDURES.CANCELLED_SUCCESS'));
        this.actionBusy.set(false);
        this.detailAction.set(null);
        this.replaceOrder(updated);
      },
      error: () => {
        this.toast.error(this.translate.instant('PROCEDURES.CANCEL_ERROR'));
        this.actionBusy.set(false);
      },
    });
  }

  private replaceOrder(updated: ProcedureOrderResponse): void {
    this.viewedOrder.set(updated);
    this.orders.update((list) => list.map((o) => (o.id === updated.id ? updated : o)));
  }
}
