import {
  Component,
  computed,
  DestroyRef,
  inject,
  OnInit,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { forkJoin, of, Subject } from 'rxjs';
import { catchError, debounceTime, distinctUntilChanged, map, switchMap } from 'rxjs/operators';
import {
  PrescriptionService,
  PrescriptionResponse,
  PrescriptionRequest,
  CommunityPharmacyService,
  CommunityPharmacyOption,
} from '../services/prescription.service';
import {
  DispenseResponse,
  PharmacyService,
  RoutingDecisionResponse,
} from '../services/pharmacy.service';
import { StaffService, StaffResponse } from '../services/staff.service';
import { PatientService, PatientResponse } from '../services/patient.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { expandRoleEquivalents, roleSatisfies } from '../core/role-equivalence';
import { HospitalScopeUrlService } from '../core/hospital-scope-url.service';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { CdsCardListComponent } from '../shared/cds-card/cds-card.component';
import { CdsCard } from '../shared/cds-card/cds-card.model';
import { HospitalScopeChipComponent } from '../shared/hospital-scope-chip/hospital-scope-chip.component';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';
import { PrescriptionClarificationComponent } from '../shared/prescription-clarification/prescription-clarification.component';

/**
 * The prescriber's list tabs (gap G10).
 *
 * `all` is the only one that is not a status bucket; the other five PARTITION
 * {@link PRESCRIPTION_STATUSES}, so every value the backend enum can send is
 * reachable from exactly one of them. The tabs this page shipped with filtered
 * on three statuses out of seventeen, which meant a prescription the pharmacy
 * had sent back — a refusal, a back order, an unanswered question — was
 * invisible everywhere except the unfiltered list.
 */
export type PrescriptionTab = 'all' | 'draft' | 'attention' | 'inPharmacy' | 'dispensed' | 'closed';

/** Every tab except `all`: the buckets that partition the status enum. */
export type PrescriptionStatusTab = Exclude<PrescriptionTab, 'all'>;

/**
 * Why a prescription is waiting on its PRESCRIBER rather than on the pharmacy,
 * and the key that says so on screen.
 *
 * This list is the definition of the "Needs attention" tab — {@link
 * TAB_BY_STATUS} is built from it rather than repeating it, because the one
 * failure mode that matters here is a status that is flagged in one place and
 * not the other. Each entry is a state the pharmacy has handed BACK: it cannot
 * fill the order as written, and only the prescriber can move it on.
 *
 * `labelKey` is the field name on purpose — check-i18n-referenced-keys.mjs
 * reads it, so a typo fails the gate instead of rendering the raw key.
 */
export const ATTENTION_REASONS: readonly { status: string; labelKey: string }[] = [
  { status: 'PENDING_CLARIFICATION', labelKey: 'PRESCRIPTIONS.ATTENTION.PENDING_CLARIFICATION' },
  { status: 'TRANSMISSION_FAILED', labelKey: 'PRESCRIPTIONS.ATTENTION.TRANSMISSION_FAILED' },
  { status: 'PARTNER_REJECTED', labelKey: 'PRESCRIPTIONS.ATTENTION.PARTNER_REJECTED' },
  { status: 'PENDING_STOCK', labelKey: 'PRESCRIPTIONS.ATTENTION.PENDING_STOCK' },
  { status: 'REQUIRES_EXTERNAL_FILL', labelKey: 'PRESCRIPTIONS.ATTENTION.REQUIRES_EXTERNAL_FILL' },
];

/**
 * What an unmapped status is called. A value the portal has never heard of
 * lands in "Needs attention" rather than nowhere: a prescription the
 * prescriber cannot see is the defect, and a status this build predates is
 * exactly the case a hard-coded list gets wrong.
 */
export const UNRECOGNISED_STATUS = {
  labelKey: 'PRESCRIPTIONS.ATTENTION.UNRECOGNISED_STATUS',
};

/**
 * Which tab each {@code PrescriptionStatus} falls under.
 *
 * SIGNED and TRANSMITTED sit under "At the pharmacy" because that is where a
 * signed order physically is — in the queue, waiting to be filled.
 * PARTIALLY_FILLED sits there too: the remainder is still the pharmacy's to
 * route, and the prescriber has nothing to do until it comes back.
 * PRINTED_FOR_PATIENT is filed as dispensed, not as attention: the patient is
 * holding the paper and the hospital's part is over.
 */
export const TAB_BY_STATUS: Readonly<Record<string, PrescriptionStatusTab>> = {
  DRAFT: 'draft',
  PENDING_SIGNATURE: 'draft',

  SIGNED: 'inPharmacy',
  TRANSMITTED: 'inPharmacy',
  SENT_TO_PARTNER: 'inPharmacy',
  PARTNER_ACCEPTED: 'inPharmacy',
  PARTIALLY_FILLED: 'inPharmacy',

  DISPENSED: 'dispensed',
  PARTNER_DISPENSED: 'dispensed',
  PRINTED_FOR_PATIENT: 'dispensed',

  CANCELLED: 'closed',
  DISCONTINUED: 'closed',

  ...Object.fromEntries(ATTENTION_REASONS.map((r) => [r.status, 'attention' as const])),
};

/** The tab bar, in render order. */
export const PRESCRIPTION_TABS: readonly { id: PrescriptionTab; labelKey: string }[] = [
  { id: 'all', labelKey: 'COMMON.ALL' },
  { id: 'attention', labelKey: 'PRESCRIPTIONS.TAB.ATTENTION' },
  { id: 'draft', labelKey: 'PRESCRIPTIONS.TAB.DRAFT' },
  { id: 'inPharmacy', labelKey: 'PRESCRIPTIONS.TAB.IN_PHARMACY' },
  { id: 'dispensed', labelKey: 'PRESCRIPTIONS.TAB.DISPENSED' },
  { id: 'closed', labelKey: 'PRESCRIPTIONS.TAB.CLOSED' },
];

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
    PrescriptionClarificationComponent,
  ],
  templateUrl: './prescriptions.html',
  changeDetection: ChangeDetectionStrategy.Eager,
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
  private readonly pharmacyService = inject(PharmacyService);
  private readonly scopeUrl = inject(HospitalScopeUrlService);
  private readonly translate = inject(TranslateService);
  private readonly destroyRef = inject(DestroyRef);

  /** Cross-tenant signals — drive the chip + Hospital column toggle. */
  protected readonly isSuperAdmin = this.roleContext.isSuperAdmin;
  protected readonly globalView = this.roleContext.globalView;

  prescriptions = signal<PrescriptionResponse[]>([]);
  filtered = signal<PrescriptionResponse[]>([]);
  loading = signal(true);
  searchTerm = '';
  activeTab = signal<PrescriptionTab>('all');
  protected readonly tabs = PRESCRIPTION_TABS;
  selectedPrescription = signal<PrescriptionResponse | null>(null);

  staffMembers = signal<StaffResponse[]>([]);

  /**
   * Roles `SecurityConfig`'s `GET /staff` matcher admits — first-match-wins
   * and terminal, so this is the whole gate, not a hint.
   *
   * <p>This page's route is WIDER than that matcher: PHARMACIST and, since
   * G9, PHARMACY_VERIFIER may open it and neither may read `/staff`. Firing
   * the request anyway costs them a 403 on page open — silent in the UI
   * (`SILENT_403_PATTERNS` covers `/staff`) but an unhandled error and a
   * frontend-audit row all the same. The list only fills the prescriber
   * dropdown in the create form, which those two roles cannot submit.
   */
  private static readonly STAFF_READ_ROLES = [
    'ROLE_DOCTOR',
    'ROLE_NURSE',
    'ROLE_MIDWIFE',
    'ROLE_RECEPTIONIST',
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_SUPER_ADMIN',
    'ROLE_LAB_DIRECTOR',
    'ROLE_LAB_MANAGER',
    'ROLE_LAB_SCIENTIST',
    'ROLE_LAB_TECHNICIAN',
    'ROLE_QUALITY_MANAGER',
  ];

  /**
   * Read live, not captured: the active role changes on a scope switch.
   *
   * <p>NOT `hasAnyActiveRole`, which matches the stored role name literally.
   * The list above holds POST-expansion names, and a prescriber's JWT carries
   * `ROLE_PHYSICIAN` or `ROLE_SURGEON` — the backend adds `ROLE_DOCTOR` before
   * its own matcher runs, so those two are served, and a literal check here
   * would leave the create form's prescriber dropdown permanently empty for
   * exactly the people who write prescriptions. `roleSatisfies` /
   * `expandRoleEquivalents` are the shared rule (role audit C2).
   */
  protected readonly canReadStaff = computed(() => {
    const active = this.roleContext.activeRole;
    if (active) {
      return roleSatisfies(PrescriptionsComponent.STAFF_READ_ROLES, active);
    }
    return expandRoleEquivalents(this.roleContext.activeRoles).some((r) =>
      PrescriptionsComponent.STAFF_READ_ROLES.includes(r),
    );
  });

  private loadPrescribers(): void {
    if (!this.canReadStaff()) {
      this.staffMembers.set([]);
      return;
    }
    this.staffService
      .list()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (s) => this.staffMembers.set(s ?? []),
        // Said out loud rather than rendered as an empty dropdown: a
        // prescriber who cannot find their own name would otherwise assume
        // the list is simply short.
        error: () => this.toast.error(this.translate.instant('STAFF.LOAD_FAILED')),
      });
  }

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

  /* ── Pharmacist verification (Tier 2 item 33) ── */
  showVerifyModal = signal(false);
  verifyTarget = signal<PrescriptionResponse | null>(null);
  verifyNote = '';
  verifying = signal(false);

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
    this.loadPrescribers();
    this.initPatientSearch();
    this.initPharmacyHistory();

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
   * SMS dispatch hands a prescription to an outside pharmacy and moves it to
   * SENT_TO_PARTNER; the backend refuses any other state (400), so the button
   * is only offered where the call can succeed. The list mirrors the backend's
   * DISPATCHABLE_STATUSES exactly: a refusal, a back order, and a pharmacy that
   * has gone quiet on an offer must all leave the clinician free to send the
   * prescription somewhere else. Re-sending supersedes the previous offer.
   */
  canDispatchSms(p: PrescriptionResponse): boolean {
    return (
      p.status === 'SIGNED' ||
      p.status === 'TRANSMITTED' ||
      p.status === 'PARTNER_REJECTED' ||
      p.status === 'PENDING_STOCK' ||
      p.status === 'SENT_TO_PARTNER'
    );
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

  /* ── Pharmacist verification (Tier 2 item 33) ───────────────────────── */

  /**
   * Roles the backend's `/pharmacist-verify` endpoint admits. Kept as one
   * named list rather than pasted at each call site: the recurring defect in
   * this codebase is copies of a role list drifting apart (#523).
   */
  private static readonly VERIFIER_ROLES = [
    'ROLE_PHARMACIST',
    'ROLE_PHARMACY_VERIFIER',
    'ROLE_SUPER_ADMIN',
  ];

  /**
   * Offered when the prescription is in scope for the gate, nobody has
   * verified the version currently on the row, and it has reached a state
   * worth verifying — a draft is still freely rewritable, and the edit would
   * clear the verification the moment it happened.
   *
   * <p>Whether the CALLER may verify is still the backend's call: it refuses
   * the prescribing clinician even when they hold a pharmacist role, and the
   * row does not carry the prescriber's user id to decide that here. The role
   * check below only hides a button that would certainly 403; the
   * self-verification refusal surfaces verbatim.
   */
  canPharmacistVerify(p: PrescriptionResponse): boolean {
    return (
      !!p.requiresPharmacistVerification &&
      !p.pharmacistVerifiedAt &&
      (p.status === 'SIGNED' || p.status === 'TRANSMITTED') &&
      this.roleContext.hasAnyActiveRole(PrescriptionsComponent.VERIFIER_ROLES)
    );
  }

  openVerifyModal(p: PrescriptionResponse): void {
    this.verifyTarget.set(p);
    this.verifyNote = '';
    this.showVerifyModal.set(true);
  }

  closeVerifyModal(): void {
    this.showVerifyModal.set(false);
    this.verifyTarget.set(null);
    this.verifyNote = '';
  }

  submitPharmacistVerify(): void {
    const target = this.verifyTarget();
    if (!target || this.verifying()) return;
    this.verifying.set(true);
    this.prescriptionService.pharmacistVerify(target.id, this.verifyNote).subscribe({
      next: () => {
        this.verifying.set(false);
        this.toast.success(this.translate.instant('PRESCRIPTIONS.TOAST.PHARMACIST_VERIFIED'));
        this.closeVerifyModal();
        this.load();
      },
      error: (err: unknown) => {
        // The refusals are all things the pharmacist must read: already
        // verified, not yours to verify, wrong status. Collapsing them into
        // one string would leave them with no idea what to do next — the
        // same reason signPrescription surfaces its message verbatim.
        this.verifying.set(false);
        const msg = this.extractErrorMessage(err);
        this.toast.error(
          msg || this.translate.instant('PRESCRIPTIONS.TOAST.PHARMACIST_VERIFY_FAILED'),
        );
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

  /**
   * Re-fetch under the new cross-tenant scope when the chip emits.
   *
   * <p>The prescriber list comes too. `canReadStaff` is read live precisely so
   * that a scope switch can change the answer, and it only ever ran at
   * `ngOnInit`: someone who opened the page as a pharmacist and switched to
   * their doctor role kept the empty prescriber dropdown they started with,
   * because nothing re-asked.
   */
  onScopeChange(_hospitalId: string | null): void {
    this.load();
    this.loadPrescribers();
  }

  /**
   * Gap G12 — the statuses that make up "Needs attention", as the backend
   * `status` filter understands them. Derived from {@link ATTENTION_REASONS}
   * rather than re-listed, for the same reason {@link TAB_BY_STATUS} is: a
   * status flagged in one place and not the other is the only failure mode
   * that matters here.
   */
  private static readonly ATTENTION_STATUSES = ATTENTION_REASONS.map((r) => r.status);

  /**
   * How many rows the UNFILTERED page returned. Tracked separately from
   * `prescriptions()` because the attention rows are merged into that signal
   * and would otherwise push it past the page size and make every load look
   * truncated.
   */
  private readonly pageRowCount = signal(0);

  load(): void {
    this.loading.set(true);
    // Two requests, because they answer different questions. The first is the
    // page: the newest 200 prescriptions, whatever their status, which is what
    // five of the six tabs list. The second is the newest 200 in an ATTENTION
    // status, because that is the tab the clinical inbox sends a prescriber
    // to — an inbox saying "3 orders await clarification" over a page holding
    // none of them is the defect this fixes, and no page size makes it go away
    // on a busy tenant.
    //
    // It makes the bucket much more complete, NOT provably complete, and the
    // page deliberately does not claim otherwise: the filtered query has the
    // same 200-row ceiling, and `tabForStatus` also files any status this
    // build has never heard of under "Needs attention", which no status
    // filter can ask for. The truncation banner therefore goes on saying the
    // counts are a minimum.
    forkJoin({
      page: this.prescriptionService.list().pipe(
        map((rows) => ({ rows, failed: false })),
        catchError(() => of({ rows: [] as PrescriptionResponse[], failed: true })),
      ),
      attention: this.prescriptionService
        .list({ statuses: PrescriptionsComponent.ATTENTION_STATUSES })
        .pipe(
          map((rows) => ({ rows, failed: false })),
          catchError(() => of({ rows: [] as PrescriptionResponse[], failed: true })),
        ),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((res) => {
        this.loading.set(false);
        if (res.page.failed) {
          // Nothing is rendered as empty: the list keeps whatever it held and
          // the failure is said out loud, as before.
          this.toast.error(this.translate.instant('PRESCRIPTIONS.TOAST.LOAD_FAILED'));
          return;
        }
        const page = Array.isArray(res.page.rows) ? res.page.rows : [];
        this.pageRowCount.set(page.length);
        this.prescriptions.set(this.mergeById(page, res.attention.rows));
        this.applyFilter();
      });
  }

  /**
   * The page plus the attention rows it did not reach, newest first.
   *
   * <p>De-duplicated on id — the two queries overlap by design, since a
   * recent PENDING_CLARIFICATION is on both — and re-sorted, because
   * appending the second result would otherwise break the `createdAt,desc`
   * order the page was fetched in. A row with no `createdAt` sorts last
   * rather than to the top, where a missing timestamp would read as "just
   * now".
   */
  private mergeById(
    page: PrescriptionResponse[],
    extra: PrescriptionResponse[],
  ): PrescriptionResponse[] {
    const byId = new Map<string, PrescriptionResponse>();
    for (const row of page) byId.set(row.id, row);
    for (const row of extra ?? []) byId.set(row.id, row);
    return [...byId.values()].sort(
      (a, b) => eventTime(b.createdAt, null) - eventTime(a.createdAt, null),
    );
  }

  setTab(tab: PrescriptionTab): void {
    this.activeTab.set(tab);
    this.applyFilter();
  }

  /**
   * Which bucket a status belongs to (gap G10). An unmapped or absent status
   * falls into "Needs attention" rather than out of the list altogether — a
   * prescription nobody can see is worse than one filed under the wrong tab,
   * and that is the failure this whole change exists to remove.
   */
  tabForStatus(status: string | null | undefined): PrescriptionStatusTab {
    if (!status) return 'attention';
    return TAB_BY_STATUS[status] ?? 'attention';
  }

  /**
   * Every tab's count in one pass, memoised by the signal.
   *
   * <p>The tab bar binds six counts and the summary card a seventh; filtering
   * the whole list per call re-scanned it seven times on every change
   * detection, which on a tenant with thousands of unpaged prescriptions is
   * seven full scans per keystroke in the search box.
   */
  private readonly tabCounts = computed<Record<PrescriptionTab, number>>(() => {
    const counts: Record<PrescriptionTab, number> = {
      all: 0,
      draft: 0,
      attention: 0,
      inPharmacy: 0,
      dispensed: 0,
      closed: 0,
    };
    for (const p of this.prescriptions()) {
      counts.all += 1;
      counts[this.tabForStatus(p.status)] += 1;
    }
    return counts;
  });

  /** How many prescriptions the tab holds, before the search box narrows it. */
  countInTab(tab: PrescriptionTab): number {
    return this.tabCounts()[tab];
  }

  /**
   * True when the list came back full, so there may be prescriptions this
   * page has never seen — and every count on the tab bar is a lower bound
   * rather than a total. Said out loud, because a silently truncated
   * "Needs attention 0" is worse than no count at all.
   */
  readonly listTruncated = computed(
    () => this.pageRowCount() >= PrescriptionService.LIST_PAGE_SIZE,
  );

  /** True while the prescriber, not the pharmacy, is the one holding this up. */
  needsAttention(p: PrescriptionResponse): boolean {
    return this.tabForStatus(p.status) === 'attention';
  }

  /**
   * The translation key that says WHY, or null when nothing is waiting on the
   * prescriber. Never the raw status.
   */
  attentionReasonKey(p: PrescriptionResponse): string | null {
    if (!this.needsAttention(p)) return null;
    const match = ATTENTION_REASONS.find((r) => r.status === p.status);
    return match ? match.labelKey : UNRECOGNISED_STATUS.labelKey;
  }

  applyFilter(): void {
    let list = this.prescriptions();
    const tab = this.activeTab();
    if (tab !== 'all') list = list.filter((p) => this.tabForStatus(p.status) === tab);
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

  /* ── Pharmacy state + history (gaps G7 / G11) ─────────────────── */

  /**
   * Roles BOTH history endpoints admit — the intersection of
   * `DispenseController#listByPrescription` and
   * `StockOutRoutingController#listByPrescription`, which happen to agree.
   *
   * <p>The prescriptions ROUTE is wider than that: it also admits NURSE,
   * MIDWIFE and ADMIN. Rendering the panel for them would fire two requests
   * that 403 and leave an error box on a page they opened for something else,
   * so the panel is absent for those three rather than broken.
   */
  private static readonly HISTORY_ROLES = [
    'ROLE_DOCTOR',
    'ROLE_PHARMACIST',
    'ROLE_PHARMACY_VERIFIER',
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_SUPER_ADMIN',
  ];

  /**
   * Read live off the role signal rather than captured at construction: the
   * active role set changes under a hospital-scope switch, and a panel gated
   * on a constructor snapshot keeps the answer it was born with.
   *
   * <p>No global-view exception any more. Both history services used to open
   * with `roleValidator.requireActiveHospitalId()` and dereference its result,
   * which is **null** for a super-admin in global view — two 500s on a page
   * that is explicitly cross-tenant, so this panel used to decline to fire the
   * calls and ask for a hospital instead. #740 fixed that at the source: the
   * reads now treat that caller the way the rest of the read surface does (no
   * hospital, no narrowing), so the message would suppress a panel the backend
   * is willing to serve. The panel is read-only — no routing WRITE lives in
   * it, and #740 deliberately kept those refusing without a scope.
   */
  protected readonly canReadPharmacyHistory = computed(() =>
    this.roleContext.hasAnyActiveRole(PrescriptionsComponent.HISTORY_ROLES),
  );

  dispenseHistory = signal<DispenseResponse[]>([]);
  routingHistory = signal<RoutingDecisionResponse[]>([]);
  historyLoading = signal(false);

  /**
   * Tracked per half. The two endpoints fail independently, and a transient
   * 500 on the routing decisions used to discard a fill list that had loaded
   * perfectly well — on a controlled drug the fills are the half that matters
   * most. Nothing is swallowed either way: whichever half is missing says so.
   */
  dispenseError = signal(false);
  routingError = signal(false);

  /**
   * True when the server holds more fills than the page that was fetched, so
   * the list cannot be summed. See {@link #fillsAreCountable}.
   */
  private readonly dispensesTruncated = signal(false);

  /** Both halves gone: the panel has nothing but the failure to report. */
  readonly historyError = computed(() => this.dispenseError() && this.routingError());

  /** One half loaded, the other did not — render what arrived AND say so. */
  readonly historyPartialError = computed(
    () => (this.dispenseError() || this.routingError()) && !this.historyError(),
  );

  /**
   * True when this prescription can have a pharmacy history at all. A draft
   * has never been dispensable, so opening one must not cost two requests.
   */
  hasPharmacyHistory(p: PrescriptionResponse): boolean {
    return this.tabForStatus(p.status) !== 'draft';
  }

  /** True when the response carries anything about where the order went. */
  hasPharmacyState(p: PrescriptionResponse): boolean {
    return !!(p.pharmacyName || p.dispatchedAt || p.lastPharmacyEvent);
  }

  /**
   * Whether the dispatch columns still describe where the order IS.
   *
   * <p>`clearPharmacy` nulls the three pharmacy columns on a refusal and on a
   * no-show, but leaves `dispatchChannel`, `dispatchStatus` and `dispatchedAt`
   * exactly as the SMS path wrote them. Rendered flatly, a PARTNER_REJECTED
   * order therefore reads "Dispatched — SMS — Sent" with no pharmacy beside
   * it: a live, successful dispatch, for an order that is back in the
   * hospital's queue. The columns are still worth showing — they are the last
   * thing that happened — so they are labelled as past instead of suppressed.
   *
   * <p>A re-route is the same trap one step further on, and worse:
   * `routeToPartner` sets the three pharmacy columns to the NEW partner and
   * never touches the dispatch ones, so "Held by B / Dispatched T1 / SMS Sent"
   * would say B had been SMS'd at a time that belongs to A. Only
   * `PrescriptionSmsDispatchServiceImpl` writes those columns, so a routing
   * decision taken after `dispatchedAt` means they describe a previous
   * destination. (`dispatchReference` on the response DTO would settle it
   * outright — flagged to the coordinator.)
   */
  dispatchIsCurrent(p: PrescriptionResponse): boolean {
    if (!p.pharmacyName || !p.dispatchedAt) return false;
    const decision = this.routingHistory()[0];
    if (!decision) return true;
    return eventTime(decision.decidedAt, decision.createdAt) <= eventTime(p.dispatchedAt, null);
  }

  /**
   * The pharmacist's question and the prescriber's answer, once either exists.
   *
   * <p>Checked separately from {@link #hasPharmacyState} because
   * `resolveClarification` restores the status the prescription held BEFORE
   * the query — often SIGNED, which is not a pharmacy-owned status, so
   * `lastPharmacyEvent` goes back to null. Keying the section off the pharmacy
   * columns alone made the exchange disappear from the prescriber's screen the
   * moment they answered it.
   */
  hasClarificationExchange(p: PrescriptionResponse): boolean {
    return !!(p.clarificationReason || p.clarificationResponse);
  }

  /**
   * Whether the "Pharmacy and dispatch" block has anything to say. Two of the
   * facts in it — the refusing partner and the outstanding quantity — come out
   * of the history rather than off the prescription, so a PARTIALLY_FILLED row
   * whose pharmacy columns are empty still has a remainder worth showing.
   */
  showPharmacySection(p: PrescriptionResponse): boolean {
    return (
      this.hasPharmacyState(p) ||
      this.needsAttention(p) ||
      this.hasClarificationExchange(p) ||
      !!this.outstandingQuantity() ||
      !!this.lastRefusedBy()
    );
  }

  /**
   * The partner that refused this order.
   *
   * <p>`StockOutRoutingServiceImpl` clears the prescription's own pharmacy
   * columns on a refusal, so the name is only recoverable from the routing
   * decisions — which is why this is a computed over the history and not a
   * field. Mirrors the backend's `lastRefusedBy` guard exactly: the row must
   * be PARTNER_REJECTED and the LATEST decision must be the partner refusal,
   * or a re-route since would make this the wrong pharmacy.
   */
  readonly lastRefusedBy = computed<string | null>(() => {
    const rx = this.selectedPrescription();
    if (!rx || rx.status !== 'PARTNER_REJECTED') return null;
    const latest = this.routingHistory()[0];
    if (!latest || latest.routingType !== 'PARTNER' || latest.status !== 'REJECTED') return null;
    return latest.targetPharmacyName ?? null;
  });

  /**
   * The remainder the SERVER computed, taken off the latest routing
   * decision's `remainingQuantity`
   * (`FillAccounting.remaining` at the moment that routing was decided).
   *
   * <p>It is not derived from the dispense rows, and an earlier draft of this
   * that summed `requested − dispensed` across them was wrong: the backend
   * records each fill as a NEW Dispense row and compares the SUM of
   * `quantityDispensed` against the lifetime expected quantity
   * (`updatePrescriptionStatusFromHistory`). A 10-of-30 partial followed by a
   * 20-of-20 second fill completes the order, but the first row's shortfall of
   * 20 stays in any per-row sum forever — so a DISPENSED prescription would
   * have gone on claiming 20 tablets were owed.
   *
   * <p>Two guards, because the server's number is a snapshot, not a live
   * balance. The first is the tab bucket rather than a list of status names:
   * everything under `dispensed` or `closed` is finished with the hospital,
   * and `printForPatient` writes a `remainingQuantity` on its PRINT decision
   * and creates no dispense row at all — so a printed prescription would have
   * gone on claiming a remainder forever, with no fill able to clear it.
   * The second is a LIVE fill recorded after the decision, which makes the
   * decision's remainder stale; a cancelled fill is skipped, because the
   * backend excludes it from the dispensed total too, and letting one hide a
   * real remainder is the opposite of the caution this comment argues for.
   *
   * <p>In both cases this returns null and the row is simply absent — the
   * per-fill "dispensed / requested" column below still shows what happened.
   */
  private readonly routingSnapshotRemainder = computed<number | null>(() => {
    const decision = this.routingHistory()[0];
    const remaining = decision?.remainingQuantity;
    if (remaining == null || remaining <= 0) return null;
    const fill = this.dispenseHistory().find((d) => d.status !== 'CANCELLED');
    if (
      fill &&
      eventTime(fill.dispensedAt, fill.createdAt) >
        eventTime(decision.decidedAt, decision.createdAt)
    ) {
      return null;
    }
    return remaining;
  });

  /**
   * The expected LIFETIME quantity of the order (gap G13):
   * `quantity * (1 + refillsUsed)`, which is the arithmetic
   * `DispenseServiceImpl.updatePrescriptionStatusFromHistory` runs. Null when
   * the row carries no quantity — the column is nullable and pre-dates the
   * pharmacy module, so a legacy order has none and the snapshot below is
   * still the only answer available.
   */
  private readonly expectedQuantity = computed<number | null>(() => {
    const rx = this.selectedPrescription();
    const ordered = rx?.quantity;
    if (ordered == null || ordered <= 0) return null;
    const refillsUsed = rx?.refillsUsed ?? 0;
    return ordered * (1 + Math.max(0, refillsUsed));
  });

  /**
   * What the prescription still owes.
   *
   * <p>Computed off the prescription itself now that the response carries
   * `quantity` and `refillsUsed` (gap G13): expected lifetime quantity minus
   * the sum of the fills that were not cancelled — the same comparison the
   * backend makes when it decides between PARTIALLY_FILLED and DISPENSED.
   * That is a LIVE balance, where {@link #routingSnapshotRemainder} is the
   * figure a routing decision froze at the moment it was taken, and it needed
   * two guards to stay honest afterwards.
   *
   * <p>The snapshot is still the fallback, for three cases it is the only
   * answer to: a row with no `quantity` on it, a fill list that failed to
   * load (where "expected minus nothing" would claim the whole order is
   * owed), and an order nothing has been filled against yet — a back order,
   * where the remainder the pharmacy recorded is on the routing decision.
   * The tab-bucket guard applies to both — everything under `dispensed` or
   * `closed` is finished with the hospital, and `printForPatient` writes a
   * `remainingQuantity` and creates no dispense row at all, so a printed
   * prescription would otherwise claim a remainder forever with no fill able
   * to clear it.
   *
   * <p>Rounded to two decimals: the quantity column is `numeric(12,2)` and
   * the subtraction is in binary floating point, so 30 − 10.1 must not render
   * as 19.899999999999999.
   */
  readonly outstandingQuantity = computed<number | null>(() => {
    const rx = this.selectedPrescription();
    if (!rx) return null;
    const bucket = this.tabForStatus(rx.status);
    if (bucket === 'dispensed' || bucket === 'closed') return null;

    const expected = this.expectedQuantity();
    if (expected != null && !this.dispenseError() && this.fillsAreCountable()) {
      const dispensed = this.countableFills().reduce(
        (sum, d) => sum + (d.quantityDispensed ?? 0),
        0,
      );
      // Only once something HAS been filled. "Nothing dispensed yet" is not a
      // remainder the prescriber needs told: it is the whole order, it would
      // appear on every signed prescription, and it would flash onto the
      // panel while the fill list was still in flight. A back order with no
      // fill still reports one — through the routing snapshot below, which is
      // where the pharmacy actually recorded it.
      if (dispensed > 0) {
        const remaining = Math.round((expected - dispensed) * 100) / 100;
        if (remaining === 0) return null;
        // A NEGATIVE balance means the two halves disagree: the fills are
        // fetched when the panel opens, `quantity`/`refillsUsed` came with
        // the list, and a refill approved and filled in between leaves the
        // row's `refillsUsed` behind. Reporting "nothing owed" there would
        // hide a real remainder, so the server's own figure is used instead.
        if (remaining > 0) return remaining;
      }
    }
    return this.routingSnapshotRemainder();
  });

  /** The fills that count against the order: everything not cancelled. */
  private readonly countableFills = computed(() =>
    this.dispenseHistory().filter((d) => d.status !== 'CANCELLED'),
  );

  /**
   * Whether the fills can be SUBTRACTED from the ordered quantity at all.
   *
   * <p>Two ways they cannot, and both make the live balance wrong rather than
   * merely imprecise, so both send it back to the routing snapshot.
   *
   * <p>The list is PAGED — `initPharmacyHistory` asks for the 20 most recent
   * fills. On an order with more than that, summing what arrived understates
   * what has been dispensed and therefore overstates what is owed, and unlike
   * the snapshot this figure reads as an authoritative balance.
   * `dispensesTruncated` is set from the server's `totalElements`.
   *
   * <p>And a fill records its OWN unit. A 200 ml syrup dispensed as 2 bottles
   * would be subtracted as "200 − 2 = 198", then labelled "ml". The backend
   * makes the same unitless comparison, but only to pick a status threshold;
   * this is the first place the number is printed to a clinician.
   */
  private readonly fillsAreCountable = computed<boolean>(() => {
    if (this.dispensesTruncated()) return false;
    const orderUnit = this.selectedPrescription()?.quantityUnit?.trim().toLowerCase();
    // A fill that declares no unit is taken to be in the order's. A fill that
    // declares one the order does not — including an order that declares none
    // at all, which is the legacy row this whole fallback exists for — is not
    // subtractable, and licensing it there would be the "200 ml dispensed as
    // 2 bottles" failure by another route.
    return this.countableFills().every((d) => {
      const fillUnit = d.unit?.trim().toLowerCase();
      return !fillUnit || fillUnit === orderUnit;
    });
  });

  /**
   * The unit the remainder is counted in — "comprimés", "flacons". It is the
   * ORDER's unit, so it labels the routing snapshot just as correctly as the
   * computed balance: both are quantities of the same prescription. Null when
   * the row carries no unit, and the number then renders bare, as it always
   * did.
   */
  readonly outstandingQuantityUnit = computed<string | null>(
    () => this.selectedPrescription()?.quantityUnit?.trim() || null,
  );

  /**
   * Refills granted and left (gap G13). Rendered only when the prescriber
   * actually granted one: "0 of 0" on the great majority of orders would be
   * a row of noise on every detail panel.
   */
  hasRefills(p: PrescriptionResponse): boolean {
    // `refillsRemaining` is nullable. "0 of 2 remaining" for a row that does
    // not say how many are left presents unknown as none, which is the
    // confident-wrong number the rest of this panel works to avoid.
    return (p.refillsAllowed ?? 0) > 0 && p.refillsRemaining != null;
  }

  viewDetail(p: PrescriptionResponse): void {
    this.selectedPrescription.set(p);
    this.loadPharmacyHistory(p);
  }

  closeDetail(): void {
    this.selectedPrescription.set(null);
    this.dispenseHistory.set([]);
    this.routingHistory.set([]);
    this.historyLoadedFor = null;
    this.historyLoading.set(false);
    this.dispenseError.set(false);
    this.routingError.set(false);
    this.dispensesTruncated.set(false);
  }

  private readonly historyRequest$ = new Subject<string>();

  /**
   * One stream for both histories.
   *
   * <p>`switchMap` so that opening prescription B while A is still in flight
   * cancels A: the detail panel is a full-screen overlay, so the user always
   * closes one before opening the next, and on a slow link A's fills were
   * landing in the signals while B's panel was on screen — one patient's
   * dispense records rendered under another's order.
   *
   * <p>The id is carried through and re-checked on arrival as well, because
   * `switchMap` cannot cancel what never emitted: opening a DRAFT fires no
   * request at all, and closing the panel fires none either, so an earlier
   * response would still have arrived and populated a panel that is showing
   * something else (or nothing).
   *
   * <p>`catchError` sits INSIDE the `switchMap` on purpose — on the outer
   * pipe it would complete the stream and the panel would never load again
   * for the rest of the session.
   */
  private initPharmacyHistory(): void {
    this.historyRequest$
      .pipe(
        switchMap((prescriptionId) =>
          forkJoin({
            // Each arm collapses its own failure to null so the other still
            // arrives; forkJoin is otherwise all-or-nothing.
            dispenses: this.pharmacyService
              .listDispensesByPrescription(prescriptionId, 0, 20, 'dispensedAt,desc')
              .pipe(catchError(() => of(null))),
            routings: this.pharmacyService
              .listRoutingDecisionsByPrescription(prescriptionId, 0, 20, 'decidedAt,desc')
              .pipe(catchError(() => of(null))),
          }).pipe(
            map((res) => ({
              prescriptionId,
              dispenseFailed: res.dispenses === null,
              routingFailed: res.routings === null,
              // The fills are paged; the remainder may only be computed from
              // them when the page IS the whole list.
              dispensesTruncated:
                (res.dispenses?.data?.totalElements ?? 0) >
                (res.dispenses?.data?.content?.length ?? 0),
              dispenses: [...(res.dispenses?.data?.content ?? [])].sort(
                (a, b) =>
                  eventTime(b.dispensedAt, b.createdAt) - eventTime(a.dispensedAt, a.createdAt),
              ),
              routings: [...(res.routings?.data?.content ?? [])].sort(
                (a, b) => eventTime(b.decidedAt, b.createdAt) - eventTime(a.decidedAt, a.createdAt),
              ),
            })),
            // Belt and braces: each arm already swallows its own failure into
            // a null, so this only fires on something neither arm produced.
            catchError(() =>
              of({
                prescriptionId,
                dispenseFailed: true,
                routingFailed: true,
                dispensesTruncated: false,
                dispenses: [] as DispenseResponse[],
                routings: [] as RoutingDecisionResponse[],
              }),
            ),
          ),
        ),
        // Navigating away with the panel open otherwise leaves the requests
        // running and this subscription writing signals on a dead component.
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((res) => {
        if (this.selectedPrescription()?.id !== res.prescriptionId) return;
        // Only overwrite a half that actually came back. A retry whose OTHER
        // half fails this time must not take away rows the prescriber could
        // read a second ago.
        if (!res.dispenseFailed) {
          this.dispenseHistory.set(res.dispenses);
          this.dispensesTruncated.set(res.dispensesTruncated);
        }
        if (!res.routingFailed) this.routingHistory.set(res.routings);
        // A refusal or an outage is an explicit error state — never "no fills
        // recorded", which is the one thing a prescriber must not be told
        // wrongly about a controlled drug.
        this.dispenseError.set(res.dispenseFailed);
        this.routingError.set(res.routingFailed);
        this.historyLoading.set(false);
      });
  }

  /** The prescription the loaded history belongs to, so a RETRY is not a swap. */
  private historyLoadedFor: string | null = null;

  loadPharmacyHistory(p: PrescriptionResponse): void {
    if (this.historyLoadedFor !== p.id) {
      this.dispenseHistory.set([]);
      this.routingHistory.set([]);
      this.dispensesTruncated.set(false);
      this.historyLoadedFor = p.id;
    }
    this.dispenseError.set(false);
    this.routingError.set(false);
    if (!this.canReadPharmacyHistory() || !this.hasPharmacyHistory(p)) {
      this.historyLoading.set(false);
      return;
    }
    this.historyLoading.set(true);
    this.historyRequest$.next(p.id);
  }

  retryPharmacyHistory(): void {
    const rx = this.selectedPrescription();
    if (rx) this.loadPharmacyHistory(rx);
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
          // The dispatch moved the prescription to SENT_TO_PARTNER. Without a
          // reload the row keeps its old status AND its SMS button, and a
          // second click supersedes the decision just made — a second SMS, and
          // the token the first pharmacy is holding stops working.
          this.load();
          this.closeDispatchModal();
        },
        error: (err) => {
          this.dispatching.set(false);
          const msg = err?.error?.message || 'Could not dispatch the prescription SMS';
          this.toast.error(msg);
        },
      });
  }

  /**
   * One badge colour per TAB, so the badge and the tab cannot disagree.
   *
   * <p>The eleven pharmacy-owned statuses used to fall through to `''` and
   * render as unstyled text beside the four that had a badge. The obvious fix
   * — a seventeen-case switch — was worse than it looked: it put
   * `TRANSMITTED` (sitting untouched in the pharmacy queue) in the same green
   * as `DISPENSED`, and `PARTNER_REJECTED` (a live order the prescriber must
   * re-route) in the same red as `CANCELLED`. A prescriber scanning a list of
   * controlled drugs reads colour before text.
   *
   * <p>Deriving the class from `tabForStatus` makes that impossible by
   * construction, and a new status inherits its bucket's colour rather than
   * rendering unstyled. Five buckets, five distinct colours the stylesheet
   * already defines — no new pairs for axe to check.
   */
  private static readonly TAB_BADGE_CLASS: Readonly<Record<PrescriptionStatusTab, string>> = {
    draft: 'status-draft',
    attention: 'status-pending',
    inPharmacy: 'status-active',
    dispensed: 'status-completed',
    closed: 'status-cancelled',
  };

  getStatusClass(status?: string): string {
    return PrescriptionsComponent.TAB_BADGE_CLASS[this.tabForStatus(status)];
  }
}

/**
 * Newest first, tolerating a row whose primary timestamp is missing. An
 * unparseable or absent pair sorts last rather than throwing NaN through the
 * comparator and scrambling the order.
 */
function eventTime(primary?: string | null, fallback?: string | null): number {
  const raw = primary ?? fallback;
  if (!raw) return 0;
  const parsed = Date.parse(raw);
  return Number.isNaN(parsed) ? 0 : parsed;
}
