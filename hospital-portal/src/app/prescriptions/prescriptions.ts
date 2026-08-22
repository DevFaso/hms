import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, switchMap } from 'rxjs/operators';
import {
  PrescriptionService,
  PrescriptionResponse,
  PrescriptionRequest,
  CommunityPharmacyService,
  CommunityPharmacyOption,
} from '../services/prescription.service';
import { StaffService, StaffResponse } from '../services/staff.service';
import { PatientService, PatientResponse } from '../services/patient.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { HospitalScopeUrlService } from '../core/hospital-scope-url.service';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { CdsCardListComponent } from '../shared/cds-card/cds-card.component';
import { CdsCard } from '../shared/cds-card/cds-card.model';
import { HospitalScopeChipComponent } from '../shared/hospital-scope-chip/hospital-scope-chip.component';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';

@Component({
  selector: 'app-prescriptions',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    TranslateModule,
    CdsCardListComponent,
    HospitalScopeChipComponent,
    EnumLabelPipe,
  ],
  templateUrl: './prescriptions.html',
  styleUrl: './prescriptions.scss',
})
export class PrescriptionsComponent implements OnInit {
  private readonly prescriptionService = inject(PrescriptionService);
  private readonly staffService = inject(StaffService);
  private readonly patientService = inject(PatientService);
  private readonly toast = inject(ToastService);
  private readonly roleContext = inject(RoleContextService);
  private readonly route = inject(ActivatedRoute);
  private readonly communityPharmacyService = inject(CommunityPharmacyService);
  private readonly scopeUrl = inject(HospitalScopeUrlService);
  private readonly translate = inject(TranslateService);

  /** Cross-tenant signals — drive the chip + Hospital column toggle. */
  protected readonly isSuperAdmin = this.roleContext.isSuperAdmin;
  protected readonly globalView = this.roleContext.globalView;

  prescriptions = signal<PrescriptionResponse[]>([]);
  filtered = signal<PrescriptionResponse[]>([]);
  loading = signal(true);
  searchTerm = '';
  activeTab = signal<'all' | 'active' | 'completed' | 'cancelled'>('all');
  selectedPrescription = signal<PrescriptionResponse | null>(null);

  staffMembers = signal<StaffResponse[]>([]);

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
  form: PrescriptionRequest = this.emptyForm();

  showDeleteConfirm = signal(false);
  deletingRx = signal<PrescriptionResponse | null>(null);
  deleting = signal(false);
  /** Id of the prescription currently being signed, so only its button spins. */
  signingId = signal<string | null>(null);

  /** CDS rule-engine cards from the most recent submit attempt. */
  cdsAdvisories = signal<CdsCard[]>([]);
  /**
   * True when the backend last rejected the submit with a critical CDS
   * advisory. Surfaces the override checkbox in the form.
   */
  cdsCriticalBlocked = signal(false);

  ngOnInit(): void {
    // Cross-tenant: hydrate URL scope before the first list fetch so
    // the auth interceptor sends the right X-Hospital-Id (or omits it
    // for global view). See docs/super-admin-cross-tenant-design.md.
    this.scopeUrl.applyUrlScopeSync(this.route);

    this.load();
    this.staffService.list().subscribe((s) => this.staffMembers.set(s ?? []));
    this.initPatientSearch();

    const params = this.route.snapshot.queryParamMap;
    if (params.get('new') === '1') {
      const patientId = params.get('patientId');
      this.openCreate();
      if (patientId) {
        this.form.patientId = patientId;
        const hid = this.roleContext.activeHospitalId ?? undefined;
        this.patientService.list(hid, '').subscribe((list) => {
          const match = list.find((p) => p.id === patientId);
          if (match) this.selectPatient(match);
        });
      }
    }
  }

  // Statuses a human may set directly on the edit form. The wire `value` is the
  // raw enum (preserved for API/DB equality checks); `labelKey` points at the
  // canonical PORTAL.ENUM.PRESCRIPTION_STATUS namespace introduced in
  // feat/i18n-enum-label-pipe-phase1, so this UI no longer carries its own
  // duplicate translation namespace (PR #256 keys removed in Phase 2/3).
  //
  // SIGNED and TRANSMITTED are absent on purpose. SIGNED used to be offered
  // here, which is how "signed" came to mean "somebody picked it from a
  // dropdown" — no signer, no timestamp, no digest. TRANSMITTED followed it
  // out on 2026-08-21: it is a dispensable state, nothing in the backend ever
  // writes it, and the server now refuses both in a create/update body — a
  // workflow status belongs to the workflow that owns it.
  prescriptionStatuses = [
    { value: 'DRAFT', labelKey: 'PORTAL.ENUM.PRESCRIPTION_STATUS.DRAFT' },
    { value: 'PENDING_SIGNATURE', labelKey: 'PORTAL.ENUM.PRESCRIPTION_STATUS.PENDING_SIGNATURE' },
    { value: 'CANCELLED', labelKey: 'PORTAL.ENUM.PRESCRIPTION_STATUS.CANCELLED' },
    { value: 'DISCONTINUED', labelKey: 'PORTAL.ENUM.PRESCRIPTION_STATUS.DISCONTINUED' },
  ];

  emptyForm(): PrescriptionRequest {
    return {
      patientId: '',
      medicationName: '',
      dosage: '',
      frequency: '',
      duration: '',
      notes: '',
      status: 'DRAFT',
    };
  }

  initPatientSearch(): void {
    this.patientSearch$
      .pipe(
        debounceTime(220),
        distinctUntilChanged(),
        switchMap((q) => {
          this.patientSearchLoading.set(true);
          // ── TENANT ISOLATION: scope patient search to active hospital ──
          const hid = this.roleContext.activeHospitalId ?? undefined;
          return this.patientService.list(hid, q);
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
  }

  clearPatient(): void {
    this.selectedPatient.set(null);
    this.form.patientId = '';
    this.patientQuery.set('');
  }

  patientInitials(p: PatientResponse): string {
    return ((p.firstName?.[0] ?? '') + (p.lastName?.[0] ?? '')).toUpperCase() || '?';
  }

  openCreate(): void {
    this.form = this.emptyForm();
    this.editing.set(false);
    this.editingId.set(null);
    this.selectedPatient.set(null);
    this.patientQuery.set('');
    this.showModal.set(true);
  }

  openEdit(p: PrescriptionResponse): void {
    this.form = {
      patientId: p.patientId ?? '',
      staffId: p.staffId ?? '',
      encounterId: p.encounterId,
      medicationName: p.medicationName ?? '',
      dosage: p.dosage ?? '',
      frequency: p.frequency ?? '',
      duration: p.duration ?? '',
      notes: p.notes ?? '',
      status: (p.status as PrescriptionRequest['status']) ?? 'DRAFT',
    };
    this.selectedPatient.set({
      id: p.patientId ?? '',
      firstName: p.patientFullName?.split(' ')[0] ?? '',
      lastName: p.patientFullName?.split(' ').slice(1).join(' ') ?? '',
      email: '',
    } as PatientResponse);
    this.editing.set(true);
    this.editingId.set(p.id);
    this.showModal.set(true);
  }

  closeModal(): void {
    this.showModal.set(false);
    this.resetCdsState();
  }

  submitForm(): void {
    this.saving.set(true);
    const op = this.editing()
      ? this.prescriptionService.update(this.editingId()!, this.form)
      : this.prescriptionService.create(this.form);
    op.subscribe({
      next: (saved) => {
        const advisories = saved?.cdsAdvisories ?? [];
        this.cdsAdvisories.set(advisories);
        this.cdsCriticalBlocked.set(false);
        this.toast.success(
          this.translate.instant(
            this.editing() ? 'PRESCRIPTIONS.TOAST.UPDATED' : 'PRESCRIPTIONS.TOAST.CREATED',
          ),
        );
        this.saving.set(false);
        this.load();
        // Only auto-close when there is nothing for the clinician to
        // see; otherwise the closeModal() reset would erase the
        // warning/info cards we just attached.
        if (advisories.length === 0) {
          this.closeModal();
        }
      },
      error: (err) => {
        const cards = this.extractCdsCards(err);
        if (cards.length > 0) {
          // Stay in the modal — backend has signalled a critical
          // block. Render the structured cards verbatim and expose
          // the forceOverride checkbox for re-submission.
          this.cdsAdvisories.set(cards);
          this.cdsCriticalBlocked.set(true);
          this.toast.error(this.extractErrorMessage(err));
        } else {
          this.toast.error(this.translate.instant('PRESCRIPTIONS.TOAST.SAVE_FAILED'));
        }
        this.saving.set(false);
      },
    });
  }

  /** Cleared when the modal closes so a stale advisory does not leak between rxes. */
  resetCdsState(): void {
    this.cdsAdvisories.set([]);
    this.cdsCriticalBlocked.set(false);
    this.form.forceOverride = undefined;
  }

  /** Allow the clinician to dismiss non-blocking advisories without re-submitting. */
  dismissAdvisories(): void {
    this.resetCdsState();
    this.closeModal();
  }

  private extractErrorMessage(err: unknown): string {
    if (err && typeof err === 'object') {
      const e = err as { error?: { message?: string }; message?: string };
      return e.error?.message ?? e.message ?? '';
    }
    return '';
  }

  /**
   * Pulls the structured `cdsAdvisories` array off the error response
   * body. The backend `CdsCriticalBlockException` handler returns
   * `{ message, cdsAdvisories: CdsCard[], ... }` with status 400.
   */
  private extractCdsCards(err: unknown): CdsCard[] {
    if (!err || typeof err !== 'object') return [];
    const body = (err as { error?: { cdsAdvisories?: CdsCard[] } }).error;
    return body?.cdsAdvisories ?? [];
  }

  confirmDelete(p: PrescriptionResponse): void {
    this.deletingRx.set(p);
    this.showDeleteConfirm.set(true);
  }
  cancelDelete(): void {
    this.showDeleteConfirm.set(false);
    this.deletingRx.set(null);
  }
  executeDelete(): void {
    this.deleting.set(true);
    this.prescriptionService.delete(this.deletingRx()!.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('PRESCRIPTIONS.TOAST.DELETED'));
        this.cancelDelete();
        this.deleting.set(false);
        this.load();
      },
      error: () => {
        this.toast.error(this.translate.instant('PRESCRIPTIONS.TOAST.DELETE_FAILED'));
        this.deleting.set(false);
      },
    });
  }

  /**
   * Only a prescription still awaiting a signature can be signed, and only its
   * own prescriber may do it. The backend is the real check — it compares the
   * caller against the prescribing clinician, which no template expression can
   * — so this is presentation only: it hides a button that would 403.
   */
  canSign(p: PrescriptionResponse): boolean {
    return p.status === 'DRAFT' || p.status === 'PENDING_SIGNATURE';
  }

  /**
   * A co-sign is offered while the prescription is still signable and the
   * declared requirement is unmet. Whether the CALLER may co-sign (a second
   * prescriber, not the prescription's own) is the backend's check — the row
   * doesn't carry enough to decide it here, so the refusal surfaces verbatim.
   */
  canCosign(p: PrescriptionResponse): boolean {
    return (
      !!p.requiresCosign &&
      !p.cosignedAt &&
      (p.status === 'DRAFT' || p.status === 'PENDING_SIGNATURE')
    );
  }

  cosignPrescription(p: PrescriptionResponse): void {
    this.signingId.set(p.id);
    this.prescriptionService.cosign(p.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('PRESCRIPTIONS.TOAST.COSIGNED'));
        this.signingId.set(null);
        this.load();
      },
      error: (err: unknown) => {
        const msg = this.extractErrorMessage(err);
        this.toast.error(msg || this.translate.instant('PRESCRIPTIONS.TOAST.COSIGN_FAILED'));
        this.signingId.set(null);
      },
    });
  }

  signPrescription(p: PrescriptionResponse): void {
    this.signingId.set(p.id);
    this.prescriptionService.sign(p.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('PRESCRIPTIONS.TOAST.SIGNED'));
        this.signingId.set(null);
        this.load();
      },
      error: (err: unknown) => {
        // The backend refuses for reasons the user needs to see verbatim — not
        // your prescription, controlled substance missing two-factor, co-sign
        // outstanding. Collapsing those into one generic string would leave a
        // prescriber with no idea what to do next.
        const msg = this.extractErrorMessage(err);
        this.toast.error(msg || this.translate.instant('PRESCRIPTIONS.TOAST.SIGN_FAILED'));
        this.signingId.set(null);
      },
    });
  }

  /** Re-fetch under the new cross-tenant scope when the chip emits. */
  onScopeChange(_hospitalId: string | null): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.prescriptionService.list().subscribe({
      next: (res) => {
        const list = Array.isArray(res) ? res : [];
        this.prescriptions.set(list);
        this.applyFilter();
        this.loading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('PRESCRIPTIONS.TOAST.LOAD_FAILED'));
        this.loading.set(false);
      },
    });
  }

  setTab(tab: 'all' | 'active' | 'completed' | 'cancelled'): void {
    this.activeTab.set(tab);
    this.applyFilter();
  }

  applyFilter(): void {
    let list = this.prescriptions();
    const tab = this.activeTab();
    if (tab === 'active') list = list.filter((p) => p.status === 'DRAFT');
    else if (tab === 'completed') list = list.filter((p) => p.status === 'SIGNED');
    else if (tab === 'cancelled') list = list.filter((p) => p.status === 'CANCELLED');
    const term = this.searchTerm.toLowerCase().trim();
    if (term) {
      list = list.filter(
        (p) =>
          (p.patientFullName ?? '').toLowerCase().includes(term) ||
          (p.medicationName ?? '').toLowerCase().includes(term) ||
          (p.staffFullName ?? '').toLowerCase().includes(term),
      );
    }
    this.filtered.set(list);
  }

  viewDetail(p: PrescriptionResponse): void {
    this.selectedPrescription.set(p);
  }
  closeDetail(): void {
    this.selectedPrescription.set(null);
  }

  /* ── SMS dispatch ────────────────────────────────────────── */
  showDispatchModal = signal(false);
  dispatchTarget = signal<PrescriptionResponse | null>(null);
  pharmacyOptions = signal<CommunityPharmacyOption[]>([]);
  dispatchPharmacyId = '';
  dispatchNote = '';
  dispatching = signal(false);

  openDispatchModal(p: PrescriptionResponse): void {
    this.dispatchTarget.set(p);
    this.dispatchPharmacyId = '';
    this.dispatchNote = '';
    this.showDispatchModal.set(true);
    const hospitalId = this.roleContext.activeHospitalId ?? undefined;
    this.communityPharmacyService.list(hospitalId).subscribe({
      next: (list) => this.pharmacyOptions.set(list ?? []),
      error: () => this.pharmacyOptions.set([]),
    });
  }

  closeDispatchModal(): void {
    this.showDispatchModal.set(false);
    this.dispatchTarget.set(null);
    this.dispatchPharmacyId = '';
    this.dispatchNote = '';
  }

  submitDispatch(): void {
    const target = this.dispatchTarget();
    if (!target || !this.dispatchPharmacyId || this.dispatching()) return;
    this.dispatching.set(true);
    this.prescriptionService
      .dispatchSms(target.id, this.dispatchPharmacyId, this.dispatchNote || undefined)
      .subscribe({
        next: (result) => {
          this.dispatching.set(false);
          this.toast.success(
            this.translate.instant('PRESCRIPTIONS.TOAST.SMS_SENT', {
              pharmacy: result.pharmacyName,
            }),
          );
          this.closeDispatchModal();
        },
        error: (err) => {
          this.dispatching.set(false);
          const msg = err?.error?.message || 'Could not dispatch the prescription SMS';
          this.toast.error(msg);
        },
      });
  }

  getStatusClass(status?: string): string {
    switch (status) {
      case 'DRAFT':
        return 'status-draft';
      case 'PENDING_SIGNATURE':
        return 'status-pending';
      case 'SIGNED':
        return 'status-active';
      case 'TRANSMITTED':
        return 'status-completed';
      case 'CANCELLED':
        return 'status-cancelled';
      case 'DISCONTINUED':
        return 'status-suspended';
      case 'TRANSMISSION_FAILED':
        return 'status-cancelled';
      default:
        return '';
    }
  }

  countByStatus(status: string): number {
    return this.prescriptions().filter((p) => p.status === status).length;
  }
}
