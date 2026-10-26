import {
  Component,
  computed,
  inject,
  OnInit,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, switchMap } from 'rxjs/operators';
import {
  LabService,
  LabOrderResponse,
  LabOrderRequest,
  LabTestDefinition,
  LabTestDefinitionApprovalRequest,
  LabSpecimen,
  PerformingLab,
} from '../services/lab.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { PatientService, PatientResponse } from '../services/patient.service';
import { EncounterService, EncounterResponse } from '../services/encounter.service';
import { ProfileService } from '../services/profile.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { AuthService } from '../auth/auth.service';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { PrintLabelService } from '../services/print-label.service';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';
import { currentLocale } from '../shared/i18n/app-locale';
import { shortOrderCode } from '../shared/lab/order-code';
import { actionRoles } from '../core/action-permissions';
import { ServerTimePipe } from '../shared/pipes/server-time.pipe';
import { serverInstant } from '../shared/date-utils';

@Component({
  selector: 'app-lab',
  standalone: true,
  imports: [ServerTimePipe, CommonModule, FormsModule, TranslateModule, EnumLabelPipe],
  templateUrl: './lab.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './lab.scss',
})
export class LabComponent implements OnInit {
  /** QA D227: a readable order code, not the raw id. */
  readonly shortOrderCode = shortOrderCode;
  private readonly labService = inject(LabService);
  private readonly hospitalService = inject(HospitalService);
  private readonly patientService = inject(PatientService);
  private readonly encounterService = inject(EncounterService);
  /** QA D177: the selected patient's encounters, so the order links one by name, not by a typed id. */
  readonly patientEncounters = signal<EncounterResponse[]>([]);
  private readonly toast = inject(ToastService);
  private readonly roleContext = inject(RoleContextService);
  private readonly auth = inject(AuthService);
  private readonly profileService = inject(ProfileService);
  private readonly printService = inject(PrintLabelService);
  private readonly translate = inject(TranslateService);

  /**
   * Only providers can place lab orders (matches POST /lab-orders @PreAuthorize
   * and RoleValidator.canOrderLabTests). D106: a midwife was offered the form
   * and then refused by the server, so the button is not shown to her.
   */
  readonly canCreateOrder = computed(() => this.auth.hasAnyRole(actionRoles('labOrderCreate')));
  /** QA D527: PUT /lab-orders/{id} is the ordering clinicians'; the lab bench is refused. */
  readonly canEditOrder = computed(() =>
    this.roleContext.hasAnyActiveRole(actionRoles('labOrderUpdate')),
  );
  /** QA D527: DELETE /lab-orders/{id} is an admin's only; a lab technician was offered it. */
  readonly canDeleteOrder = computed(() =>
    this.roleContext.hasAnyActiveRole(actionRoles('labOrderDelete')),
  );
  /**
   * QA D820: a COMPLETED order had no way out but an admin DELETE. The lab
   * manager or director (or a super-admin) voids it as entered in error.
   */
  readonly canVoidOrder = computed(() =>
    this.roleContext.hasAnyActiveRole(actionRoles('labOrderVoid')),
  );

  /**
   * Everything the laboratory still owes: from the order being placed,
   * through the specimen being collected and received, to the analysis.
   * COLLECTED and RECEIVED are here because the backend now moves an
   * order through them on the specimen events; without them an order
   * vanished from both tabs between collection and result entry.
   */
  static readonly PENDING_STATUSES: readonly string[] = [
    'ORDERED',
    'PENDING',
    'COLLECTED',
    'RECEIVED',
    'IN_PROGRESS',
  ];
  /** Results are in: entered, verified, or released and closed. */
  static readonly COMPLETED_STATUSES: readonly string[] = ['RESULTED', 'VERIFIED', 'COMPLETED'];

  orders = signal<LabOrderResponse[]>([]);
  filtered = signal<LabOrderResponse[]>([]);
  searchTerm = '';
  loading = signal(true);
  selectedOrder = signal<LabOrderResponse | null>(null);

  activeTab = signal<'all' | 'pending' | 'completed'>('all');

  stats = signal({ total: 0, pending: 0, completed: 0, cancelled: 0 });

  hospitals = signal<HospitalResponse[]>([]);
  labTestDefs = signal<LabTestDefinition[]>([]);
  /** Laboratories an order can be sent to (B1); empty until a provider opens the page. */
  performingLabs = signal<PerformingLab[]>([]);
  private activeAssignmentId = '';

  // Patient picker
  patientQuery = signal('');
  patientSuggestions = signal<PatientResponse[]>([]);
  patientDropdownOpen = signal(false);
  patientSearchLoading = signal(false);
  selectedPatient = signal<PatientResponse | null>(null);
  private readonly patientSearch$ = new Subject<string>();

  /* ── CRUD signals ── */
  showModal = signal(false);
  editing = signal(false);
  saving = signal(false);
  editingId = signal<string | null>(null);
  form: LabOrderRequest = this.emptyForm();

  // QA D820: void (entered in error)
  voidingOrder = signal<LabOrderResponse | null>(null);
  voidReason = signal('');
  voiding = signal(false);

  showDeleteConfirm = signal(false);
  deletingOrder = signal<LabOrderResponse | null>(null);
  deleting = signal(false);

  /* ── Specimens ── */
  showSpecimensModal = signal(false);
  specimensOrder = signal<LabOrderResponse | null>(null);
  specimens = signal<LabSpecimen[]>([]);
  specimensLoading = signal(false);
  specimenForm = { specimenType: '', currentLocation: '', notes: '' };
  specimenSaving = signal(false);
  receivingSpecimenId = signal<string | null>(null);
  printingSpecimenId = signal<string | null>(null);

  /** Matches the specimen create/receive @PreAuthorize (lab staff; E9 #67: no admins). */
  readonly canManageSpecimens = this.roleContext.hasAnyActiveRole(
    actionRoles('labSpecimenCollect'),
  );

  priorities = ['ROUTINE', 'URGENT', 'STAT', 'ASAP'];

  /** Localised on every read so the options follow a runtime language switch. */
  get orderChannels(): { value: string; label: string }[] {
    return [
      { value: 'PORTAL', label: this.translate.instant('LAB.ORDER_CHANNEL_OPTION.PORTAL') },
      { value: 'ELECTRONIC', label: this.translate.instant('LAB.ORDER_CHANNEL_OPTION.ELECTRONIC') },
      { value: 'PHONE', label: this.translate.instant('LAB.ORDER_CHANNEL_OPTION.PHONE') },
      { value: 'FAX', label: this.translate.instant('LAB.ORDER_CHANNEL_OPTION.FAX') },
      { value: 'EMAIL', label: this.translate.instant('LAB.ORDER_CHANNEL_OPTION.EMAIL') },
      { value: 'WRITTEN', label: this.translate.instant('LAB.ORDER_CHANNEL_OPTION.WRITTEN') },
      { value: 'WALK_IN', label: this.translate.instant('LAB.ORDER_CHANNEL_OPTION.WALK_IN') },
      { value: 'OTHER', label: this.translate.instant('LAB.ORDER_CHANNEL_OPTION.OTHER') },
    ];
  }

  ngOnInit(): void {
    this.loadOrders();
    this.loadAssignedHospitals();
    this.initPatientSearch();
    this.labService.listTestDefinitions().subscribe((defs) => this.labTestDefs.set(defs));
    if (this.canCreateOrder()) {
      this.labService.listPerformingLabs().subscribe({
        next: (labs) => this.performingLabs.set(labs ?? []),
        error: () => this.performingLabs.set([]),
      });
    }
    this.profileService.getAssignments().subscribe({
      next: (assignments) => {
        const active = assignments.find((a) => a.active);
        if (active) this.activeAssignmentId = active.id;
      },
    });
  }

  emptyForm(): LabOrderRequest {
    const profile = this.auth.getUserProfile();
    return {
      patientId: '',
      hospitalId: '',
      testName: '',
      status: 'ORDERED',
      clinicalIndication: '',
      medicalNecessityNote: '',
      orderingStaffId: profile?.staffId ?? '',
      labTestDefinitionId: '',
      assignmentId: this.activeAssignmentId,
      primaryDiagnosisCode: '',
      orderChannel: 'PORTAL',
      providerSignature: '',
      documentationSharedWithLab: null,
      performingHospitalId: '',
    };
  }

  /**
   * B1: an order is "incoming" when another hospital sent it to this
   * laboratory, and "outgoing" when this hospital sent it out.
   *
   * Read through effectiveHospitalIdForRequest, the id the API is actually
   * called with — not activeHospitalId, which for a super-admin is the
   * primary hospital rather than the one the chip is pinned to. Reading the
   * wrong one inverted every label for a chip-scoped super-admin and offered
   * them Edit and Delete on incoming orders that the backend then 404s.
   */
  isIncomingExternal(o: LabOrderResponse): boolean {
    const active = this.roleContext.effectiveHospitalIdForRequest();
    return !!o.performingHospitalId && !!active && o.performingHospitalId === active;
  }

  /**
   * QA D601/D616: an order that carries results is part of the chart and the
   * API refuses to delete it (409); the icon is not offered there.
   */
  hasResultsStage(o: LabOrderResponse): boolean {
    return ['RESULTED', 'VERIFIED', 'COMPLETED'].includes(String(o.status ?? '').toUpperCase());
  }

  /**
   * QA D820: an order voided as entered in error is kept exactly as it was
   * voided; the server refuses an edit (400) and a delete (409) on it, so
   * neither is offered. Nor is either offered on an order another hospital
   * sent here, which belongs to the ordering hospital.
   */
  isEditable(o: LabOrderResponse): boolean {
    return !this.isIncomingExternal(o) && o.status !== 'ENTERED_IN_ERROR';
  }

  /** As {@link isEditable}, and never on an order that carries results (QA D601/D616). */
  isDeletable(o: LabOrderResponse): boolean {
    return this.isEditable(o) && !this.hasResultsStage(o);
  }

  isSentOut(o: LabOrderResponse): boolean {
    return !!o.performingHospitalId && !this.isIncomingExternal(o);
  }

  /**
   * The hospital on this side of the relationship — the one whose worklist
   * this is. For an order another hospital sent here that is the performing
   * laboratory (us), not the hospital that ordered it.
   */
  actingHospitalName(o: LabOrderResponse): string {
    const name = this.isIncomingExternal(o) ? o.performingHospitalName : o.hospitalName;
    return name || '—';
  }

  /** The hospital on the other side: who ordered it, or where it was sent. */
  counterpartHospitalName(o: LabOrderResponse): string {
    const name = this.isIncomingExternal(o) ? o.hospitalName : o.performingHospitalName;
    return name || '—';
  }

  /** The label that side carries: "Ordered by" when it came to us, "Sent to" when it left us. */
  counterpartLabelKey(o: LabOrderResponse): string {
    return this.isIncomingExternal(o) ? 'LAB.ORDERED_BY' : 'LAB.SENT_TO';
  }

  /**
   * D48: only an ACTIVE definition may be ordered (the API refuses the rest).
   * The test an edited order already names stays listed, so the select keeps
   * showing it.
   */
  orderableTestDefs(): LabTestDefinition[] {
    const current = this.form.labTestDefinitionId;
    return this.labTestDefs().filter(
      (d) => (d.isActive && d.approvalStatus === 'ACTIVE') || (!!current && d.id === current),
    );
  }

  onTestDefChange(defId: string): void {
    const def = this.labTestDefs().find((d) => d.id === defId);
    if (def) {
      this.form.testName = def.testName;
      this.form.testCode = def.testCode;
    }
  }

  /** ── TENANT ISOLATION: only SUPER_ADMIN may choose from all hospitals ── */
  private loadAssignedHospitals(): void {
    if (this.roleContext.isSuperAdmin()) {
      this.hospitalService.list().subscribe((h) => this.hospitals.set(h ?? []));
    } else {
      this.hospitalService.getMyHospitalAsResponse().subscribe({
        next: (h) => {
          this.hospitals.set([h]);
          this.form.hospitalId = h.id;
        },
      });
    }
  }

  get lockedHospitalName(): string {
    const h = this.hospitals();
    return h.length === 1 ? h[0].name : this.translate.instant('COMMON.NO_HOSPITAL_ASSIGNED');
  }

  get hospitalLocked(): boolean {
    return !this.roleContext.isSuperAdmin();
  }

  initPatientSearch(): void {
    this.patientSearch$
      .pipe(
        debounceTime(220),
        distinctUntilChanged(),
        switchMap((q) => {
          this.patientSearchLoading.set(true);
          return this.patientService.list(this.patientSearchHospitalId(), q);
        }),
      )
      .subscribe({
        next: (list) => {
          this.patientSuggestions.set(list.slice(0, 8));
          this.patientDropdownOpen.set(list.length > 0);
          this.patientSearchLoading.set(false);
        },
        error: () => this.patientSearchLoading.set(false),
      });
  }

  onPatientQueryChange(q: string): void {
    this.patientQuery.set(q);
    if (q.length >= 2) this.patientSearch$.next(q);
    else {
      this.patientSuggestions.set([]);
      this.patientDropdownOpen.set(false);
    }
  }

  selectPatient(p: PatientResponse): void {
    this.selectedPatient.set(p);
    this.form.patientId = p.id;
    this.patientDropdownOpen.set(false);
    this.patientQuery.set('');
    this.loadPatientEncounters(p.id);
  }

  /** QA D177: the encounters the order may be linked to, newest first. */
  private loadPatientEncounters(patientId: string): void {
    this.patientEncounters.set([]);
    this.form.encounterId = '';
    const hospitalId = this.patientSearchHospitalId();
    this.encounterService.list({ patientId, hospitalId }).subscribe({
      next: (list) => {
        if (this.form.patientId !== patientId) return;
        this.patientEncounters.set(
          [...list].sort((a, b) => (b.encounterDate ?? '').localeCompare(a.encounterDate ?? '')),
        );
      },
      error: () => this.patientEncounters.set([]),
    });
  }

  /** QA D177: the date and department of an encounter, for the picker (the type is a pipe). */
  encounterLabel(e: EncounterResponse): string {
    const date = e.encounterDate
      ? serverInstant(e.encounterDate).toLocaleDateString(currentLocale())
      : '';
    return [date, e.departmentName].filter((part) => !!part).join(' · ');
  }

  onHospitalChange(hospitalId: string): void {
    this.form.hospitalId = hospitalId;
    this.clearPatient();
    this.patientSuggestions.set([]);
    this.patientDropdownOpen.set(false);
  }

  clearPatient(): void {
    this.selectedPatient.set(null);
    this.form.patientId = '';
    this.patientQuery.set('');
    this.form.encounterId = '';
    this.patientEncounters.set([]);
  }

  patientInitials(p: PatientResponse): string {
    return ((p.firstName?.[0] ?? '') + (p.lastName?.[0] ?? '')).toUpperCase() || '?';
  }

  private patientSearchHospitalId(): string | undefined {
    return this.form.hospitalId || this.roleContext.activeHospitalId || undefined;
  }

  openCreate(): void {
    this.form = this.emptyForm();
    this.editing.set(false);
    this.editingId.set(null);
    this.selectedPatient.set(null);
    this.patientQuery.set('');
    // Re-apply locked hospital after emptyForm() reset
    if (this.hospitalLocked) {
      const h = this.hospitals();
      if (h.length === 1) this.form.hospitalId = h[0].id;
    }
    this.showModal.set(true);
  }

  openEdit(o: LabOrderResponse): void {
    const profile = this.auth.getUserProfile();
    this.form = {
      patientId: '',
      hospitalId: '',
      testName: o.labTestName ?? '',
      testCode: o.labTestCode,
      status: o.status ?? 'ORDERED',
      clinicalIndication: o.clinicalIndication ?? '',
      medicalNecessityNote: o.medicalNecessityNote ?? '',
      notes: o.notes,
      orderingStaffId: profile?.staffId ?? '',
      labTestDefinitionId: '',
      assignmentId: this.activeAssignmentId,
      primaryDiagnosisCode: o.primaryDiagnosisCode ?? '',
      orderChannel: o.orderChannel ?? 'PORTAL',
      providerSignature: '',
      documentationSharedWithLab: null,
      performingHospitalId: o.performingHospitalId ?? '',
    };
    this.selectedPatient.set({
      id: '',
      firstName: o.patientFullName?.split(' ')[0] ?? '',
      lastName: o.patientFullName?.split(' ').slice(1).join(' ') ?? '',
    } as PatientResponse);
    this.editing.set(true);
    this.editingId.set(o.id);
    this.showModal.set(true);
  }

  closeModal(): void {
    this.showModal.set(false);
  }

  submitForm(): void {
    this.saving.set(true);
    // The select is always sent, and "" is an explicit null rather than an
    // absent field: absent tells the API to leave the routing as it is, so
    // omitting it would make "this hospital's laboratory" unreachable on an
    // order that had been sent out.
    const payload: LabOrderRequest = {
      ...this.form,
      performingHospitalId: this.form.performingHospitalId || null,
    };
    const op = this.editing()
      ? this.labService.updateOrder(this.editingId()!, payload)
      : this.labService.createOrder(payload);
    op.subscribe({
      next: () => {
        this.toast.success(
          this.translate.instant(this.editing() ? 'LAB.TOAST.UPDATED' : 'LAB.TOAST.CREATED'),
        );
        this.closeModal();
        this.saving.set(false);
        this.loadOrders();
      },
      error: () => {
        this.toast.error(this.translate.instant('LAB.TOAST.SAVE_FAILED'));
        this.saving.set(false);
      },
    });
  }

  /**
   * QA D820: offered on this hospital's own orders while open or completed;
   * not on one already voided, nor on a cancelled one (already closed, and
   * the server keeps its cancel reason).
   */
  isVoidable(o: LabOrderResponse): boolean {
    return (
      !this.isIncomingExternal(o) && o.status !== 'ENTERED_IN_ERROR' && o.status !== 'CANCELLED'
    );
  }

  openVoid(o: LabOrderResponse): void {
    this.voidReason.set('');
    this.voidingOrder.set(o);
  }

  closeVoid(): void {
    this.voidingOrder.set(null);
    this.voidReason.set('');
  }

  onVoidReasonInput(event: Event): void {
    this.voidReason.set((event.target as HTMLTextAreaElement).value);
  }

  executeVoid(): void {
    const order = this.voidingOrder();
    const reason = this.voidReason().trim();
    if (!order || !reason) return;
    this.voiding.set(true);
    this.labService.voidOrder(order.id, reason).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('LAB.VOID.DONE'));
        this.voiding.set(false);
        this.closeVoid();
        this.loadOrders();
      },
      error: (err: { error?: { message?: string } }) => {
        // The server's refusal is written for the reader (localised).
        this.toast.error(err?.error?.message ?? this.translate.instant('LAB.VOID.FAILED'));
        this.voiding.set(false);
      },
    });
  }

  confirmDelete(o: LabOrderResponse): void {
    this.deletingOrder.set(o);
    this.showDeleteConfirm.set(true);
  }
  cancelDelete(): void {
    this.showDeleteConfirm.set(false);
    this.deletingOrder.set(null);
  }
  executeDelete(): void {
    this.deleting.set(true);
    this.labService.deleteOrder(this.deletingOrder()!.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('LAB.TOAST.DELETED'));
        this.cancelDelete();
        this.deleting.set(false);
        this.loadOrders();
      },
      error: () => {
        this.toast.error(this.translate.instant('LAB.TOAST.DELETE_FAILED'));
        this.deleting.set(false);
      },
    });
  }

  loadOrders(): void {
    this.loading.set(true);
    this.labService.listOrders({ size: 200 }).subscribe({
      next: (list) => {
        this.orders.set(list);
        this.computeStats(list);
        this.applyFilter();
        this.loading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('LAB.TOAST.LOAD_FAILED'));
        this.loading.set(false);
      },
    });
  }

  isPending(order: LabOrderResponse): boolean {
    return LabComponent.PENDING_STATUSES.includes(order.status ?? '');
  }

  isCompleted(order: LabOrderResponse): boolean {
    return LabComponent.COMPLETED_STATUSES.includes(order.status ?? '');
  }

  private computeStats(list: LabOrderResponse[]): void {
    this.stats.set({
      total: list.length,
      pending: list.filter((o) => this.isPending(o)).length,
      completed: list.filter((o) => this.isCompleted(o)).length,
      cancelled: list.filter((o) => o.status === 'CANCELLED').length,
    });
  }

  setTab(tab: 'all' | 'pending' | 'completed'): void {
    this.activeTab.set(tab);
    this.applyFilter();
  }

  applyFilter(): void {
    let list = this.orders();
    const tab = this.activeTab();
    if (tab === 'pending') {
      list = list.filter((o) => this.isPending(o));
    } else if (tab === 'completed') {
      list = list.filter((o) => this.isCompleted(o));
    }
    const term = this.searchTerm.toLowerCase().trim();
    if (term) {
      list = list.filter(
        (o) =>
          (o.labOrderCode ?? '').toLowerCase().includes(term) ||
          (o.patientFullName ?? '').toLowerCase().includes(term) ||
          (o.labTestName ?? '').toLowerCase().includes(term) ||
          (o.status ?? '').toLowerCase().includes(term),
      );
    }
    this.filtered.set(list);
  }

  viewOrder(order: LabOrderResponse): void {
    this.selectedOrder.set(order);
  }

  closeDetail(): void {
    this.selectedOrder.set(null);
  }

  getStatusClass(status: string): string {
    switch (status) {
      case 'COMPLETED':
      case 'VERIFIED':
      case 'RESULTED':
        return 'status-badge status-completed';
      case 'IN_PROGRESS':
        return 'status-badge status-in_progress';
      case 'COLLECTED':
      case 'RECEIVED':
        return 'status-badge status-collected';
      case 'PENDING':
      case 'ORDERED':
        return 'status-badge status-pending';
      case 'CANCELLED':
      case 'ENTERED_IN_ERROR':
        return 'status-badge status-cancelled';
      default:
        return 'status-badge';
    }
  }

  // ── Lab Test Definition Approval Workflow ────────────────────────────

  showApprovalModal = signal(false);
  approvalTarget = signal<LabTestDefinition | null>(null);
  approvalAction = signal<LabTestDefinitionApprovalRequest['action'] | ''>('');
  approvalRejectionReason = signal('');
  processingApproval = signal(false);

  openApprovalModal(def: LabTestDefinition): void {
    this.approvalTarget.set(def);
    this.approvalAction.set('');
    this.approvalRejectionReason.set('');
    this.showApprovalModal.set(true);
  }

  closeApprovalModal(): void {
    this.showApprovalModal.set(false);
    this.approvalTarget.set(null);
  }

  submitApprovalAction(): void {
    const def = this.approvalTarget();
    const action = this.approvalAction();
    if (!def || !action) return;

    const req: LabTestDefinitionApprovalRequest = {
      action: action as LabTestDefinitionApprovalRequest['action'],
    };
    if (action === 'REJECT') {
      req.rejectionReason = this.approvalRejectionReason();
    }

    this.processingApproval.set(true);
    this.labService.submitApprovalAction(def.id, req).subscribe({
      next: (updated) => {
        this.toast.success(
          this.translate.instant('LAB.TOAST.DEFINITION_STATUS_UPDATED', {
            status: this.translate.instant(`LAB.APPROVAL_STATUS.${updated.approvalStatus}`),
          }),
        );
        this.labService.listTestDefinitions().subscribe((defs) => this.labTestDefs.set(defs));
        this.closeApprovalModal();
        this.processingApproval.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('LAB.TOAST.APPROVAL_FAILED'));
        this.processingApproval.set(false);
      },
    });
  }

  getApprovalStatusClass(status: string): string {
    switch (status) {
      case 'ACTIVE':
        return 'status-badge status-completed';
      case 'APPROVED':
        return 'status-badge status-collected';
      case 'DRAFT':
        return 'status-badge status-pending';
      case 'REJECTED':
        return 'status-badge status-cancelled';
      default:
        return 'status-badge';
    }
  }

  // ── Specimen tracking ────────────────────────────────────────────────

  openSpecimens(order: LabOrderResponse): void {
    this.specimensOrder.set(order);
    // QA D177: the test's own sample type, so the collector confirms it
    // rather than retyping it (still editable for an unusual specimen).
    this.specimenForm = {
      specimenType: this.sampleTypeOf(order),
      currentLocation: '',
      notes: '',
    };
    this.showSpecimensModal.set(true);
    this.loadSpecimens(order.id);
  }

  private sampleTypeOf(order: LabOrderResponse): string {
    const def = this.labTestDefs().find((d) => d.testCode === order.labTestCode);
    return def?.sampleType ?? '';
  }

  closeSpecimens(): void {
    this.showSpecimensModal.set(false);
    this.specimensOrder.set(null);
    this.specimens.set([]);
  }

  private loadSpecimens(orderId: string): void {
    this.specimensLoading.set(true);
    this.labService.listSpecimens(orderId).subscribe({
      next: (list) => {
        this.specimens.set(list ?? []);
        this.specimensLoading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('LAB.TOAST.SPECIMENS_LOAD_FAILED'));
        this.specimensLoading.set(false);
      },
    });
  }

  collectSpecimen(): void {
    const order = this.specimensOrder();
    if (!order || !this.specimenForm.specimenType.trim()) return;
    this.specimenSaving.set(true);
    this.labService
      .createSpecimen(order.id, {
        specimenType: this.specimenForm.specimenType.trim(),
        currentLocation: this.specimenForm.currentLocation.trim() || undefined,
        notes: this.specimenForm.notes.trim() || undefined,
      })
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('LAB.TOAST.SPECIMEN_COLLECTED'));
          this.specimenForm = { specimenType: '', currentLocation: '', notes: '' };
          this.specimenSaving.set(false);
          this.loadSpecimens(order.id);
        },
        error: () => {
          this.toast.error(this.translate.instant('LAB.TOAST.SPECIMEN_COLLECT_FAILED'));
          this.specimenSaving.set(false);
        },
      });
  }

  /** Specimen label PDF (P3 #23b) — first reader of the stored barcode_value. */
  printSpecimenLabel(specimen: LabSpecimen): void {
    if (this.printingSpecimenId()) return;
    this.printingSpecimenId.set(specimen.id);
    this.printService.getSpecimenLabelPdf(specimen.id).subscribe({
      next: (blob) => {
        this.printingSpecimenId.set(null);
        this.printService.openForPrint(blob);
      },
      error: (err) => {
        this.printingSpecimenId.set(null);
        this.toast.error(err?.error?.message ?? this.translate.instant('LAB.PRINT_LABEL_FAILED'));
      },
    });
  }

  receiveSpecimen(specimen: LabSpecimen): void {
    const order = this.specimensOrder();
    if (!order) return;
    this.receivingSpecimenId.set(specimen.id);
    this.labService.receiveSpecimen(specimen.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('LAB.TOAST.SPECIMEN_RECEIVED'));
        this.receivingSpecimenId.set(null);
        this.loadSpecimens(order.id);
      },
      error: () => {
        this.toast.error(this.translate.instant('LAB.TOAST.SPECIMEN_RECEIVE_FAILED'));
        this.receivingSpecimenId.set(null);
      },
    });
  }

  canReceive(specimen: LabSpecimen): boolean {
    return specimen.status === 'COLLECTED' || specimen.status === 'IN_TRANSIT';
  }

  getSpecimenStatusClass(status: string): string {
    switch (status) {
      case 'RECEIVED':
      case 'COMPLETED':
        return 'status-badge status-completed';
      case 'COLLECTED':
      case 'IN_TRANSIT':
        return 'status-badge status-collected';
      case 'PROCESSING':
        return 'status-badge status-in_progress';
      case 'REJECTED':
        return 'status-badge status-cancelled';
      default:
        return 'status-badge status-pending';
    }
  }
}
