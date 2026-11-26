import { FormControlStyleDirective } from '../shared/forms/form-control-style.directive';
import {
  Component,
  DestroyRef,
  ElementRef,
  computed,
  inject,
  OnInit,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { isOrderCancellable } from '../shared/order-lifecycle';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  AboGroup,
  AntibodyScreenResult,
  AntibodyStatusResponse,
  AntibodyWorkupStatus,
  BloodProductType,
  BloodUnitResponse,
  BloodUnitStatus,
  EmergencyReleaseOptions,
  PatientBloodGroupResponse,
  RhFactor,
  TransfusionAdministrationResponse,
  TransfusionReactionSeverity,
  TransfusionReactionType,
  TransfusionRequestResponse,
  TransfusionRequestStatus,
  TransfusionService,
  TransfusionUrgency,
  VerifierCandidate,
  VitalUrgencyLevel,
} from '../services/transfusion.service';
import { PatientResponse } from '../services/patient.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { finalize } from 'rxjs';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';
import { PatientPickerComponent } from '../shared/patient-picker/patient-picker.component';
import { ServerTimePipe } from '../shared/pipes/server-time.pipe';
import { localDateString, nowLocalDatetime, toServerDateTime } from '../shared/date-utils';
import { refusalReason } from '../shared/refusal-reason';
import { BackdropCloseDirective } from '../shared/a11y/backdrop-close.directive';
import { ModalDialogDirective } from '../shared/a11y/modal-dialog.directive';
import { AuthorshipService } from '../shared/authorship.service';

import { SubmitGuardDirective } from '../shared/forms/submit-guard.directive';
import { ReasonDialogComponent } from '../shared/reason-dialog/reason-dialog.component';
import { ConfirmDialogComponent } from '../shared/confirm-dialog/confirm-dialog.component';
import { focusWhenRendered } from '../shared/a11y/focus-when-rendered';
import { FocusOnRenderDirective } from '../shared/a11y/focus-on-render.directive';
import { OPositiveReleasesComponent } from './o-positive-releases/o-positive-releases.component';
type Tab = 'requests' | 'units' | 'opositive';

/**
 * Blood bank workbench (Tier 2 item 28) — the first and only caller of every
 * `/transfusions` endpoint.
 *
 * <p>Compatibility is NEVER decided here. The backend owns the ABO/Rh rules and
 * this page reports what it says: a second implementation of that rule in
 * TypeScript would be one that can silently drift from the one that matters.
 */
@Component({
  selector: 'app-transfusion',
  standalone: true,
  imports: [
    FormControlStyleDirective,
    FocusOnRenderDirective,
    ConfirmDialogComponent,
    ReasonDialogComponent,
    SubmitGuardDirective,
    ModalDialogDirective,
    BackdropCloseDirective,
    ServerTimePipe,
    CommonModule,
    FormsModule,
    TranslateModule,
    EnumLabelPipe,
    PatientPickerComponent,
    OPositiveReleasesComponent,
  ],
  templateUrl: './transfusion.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './transfusion.scss',
})
export class TransfusionComponent implements OnInit {
  private readonly destroyRef = inject(DestroyRef);
  private readonly authorship = inject(AuthorshipService);
  private readonly transfusion = inject(TransfusionService);
  private readonly toast = inject(ToastService);
  private readonly roleContext = inject(RoleContextService);

  private readonly translate = inject(TranslateService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  tab = signal<Tab>('requests');
  loading = signal(false);

  requests = signal<TransfusionRequestResponse[]>([]);
  requestStatusFilter = signal<TransfusionRequestStatus | ''>('');
  selectedRequest = signal<TransfusionRequestResponse | null>(null);

  units = signal<BloodUnitResponse[]>([]);
  /** "Fetched for the current scope" — an empty list is a valid answer, not "not loaded yet". */
  readonly requestsLoaded = signal(false);
  readonly unitsLoaded = signal(false);
  unitStatusFilter = signal<BloodUnitStatus | ''>('');
  assignableUnits = signal<BloodUnitResponse[]>([]);

  /* ── Positive antibody screen (QA D3351, provisional rule) ── */

  /** Only the hospital's biologist records the antibody (TransfusionController#recordAntibodyWorkup). */
  readonly canRecordAntibody = this.roleContext.hasAnyActiveRoleExactly(['ROLE_LAB_DIRECTOR']);
  readonly vitalUrgencyLevels: VitalUrgencyLevel[] = ['UVI', 'UV'];
  antibodyStatus = signal<AntibodyStatusResponse | null>(null);
  showAntibodyModal = signal(false);
  antibodySubmitting = signal(false);
  antibodyForm = this.emptyAntibodyForm();

  /**
   * What is known of the antibodies of the patient picked on the request form, at this hospital or another:
   * the flag follows the patient, so it is read on its own, with or without a blood group on record here.
   */
  requestAntibody = signal<AntibodyStatusResponse | null>(null);

  /** The patient of the request being written is allo-immunised, by the group read here or by the patient's flag. */
  get requestPatientAlloImmunised(): boolean {
    return !!this.patientGroup()?.alloImmunised || !!this.requestAntibody()?.alloImmunised;
  }

  /** The request being written is a red-cell emergency for an allo-immunised patient: it is declared. */
  get needsVitalUrgency(): boolean {
    return (
      this.requestPatientAlloImmunised &&
      this.requestForm.urgency === 'EMERGENCY' &&
      this.isRedCells(this.requestForm.productType)
    );
  }

  /** The declaration is complete, or not asked for. */
  get vitalUrgencyDeclared(): boolean {
    return (
      !this.needsVitalUrgency ||
      (!!this.requestForm.vitalUrgencyLevel &&
        !!this.requestForm.vitalUrgencyReason.trim() &&
        this.requestForm.preTransfusionSampleDrawn)
    );
  }

  /** A compatible result on red cells for an allo-immunised patient carries the four attestations. */
  get crossmatchNeedsCompatibilisation(): boolean {
    const request = this.selectedRequest();
    return !!request?.alloImmunised && this.crossmatchForm.compatible && !this.lateResultUnit();
  }

  get crossmatchCompatibilised(): boolean {
    return (
      !this.crossmatchNeedsCompatibilisation ||
      (!!this.crossmatchForm.sampleCollectedAt &&
        this.crossmatchForm.antiglobulinMethod &&
        this.crossmatchForm.antigenNegative &&
        this.crossmatchForm.rhKellMatched)
    );
  }

  private isRedCells(product: BloodProductType): boolean {
    return product === 'PACKED_RED_CELLS' || product === 'WHOLE_BLOOD';
  }

  private emptyAntibodyForm() {
    return {
      status: 'IDENTIFIED' as AntibodyWorkupStatus,
      antibodySpecificities: '',
      referralCentre: '',
      decisionReason: '',
      notes: '',
    };
  }

  /** The flag and the biologist's records of the open request's patient. */
  private loadAntibodyStatus(patientId: string): void {
    this.transfusion.getAntibodyStatus(patientId).subscribe({
      next: (status) => {
        if (this.selectedRequest()?.patientId === patientId) this.antibodyStatus.set(status);
      },
      error: () => this.antibodyStatus.set(null),
    });
  }

  openAntibodyWorkup(): void {
    this.antibodyForm = this.emptyAntibodyForm();
    this.showAntibodyModal.set(true);
  }

  get antibodyFormComplete(): boolean {
    return this.antibodyForm.status === 'IDENTIFIED'
      ? !!this.antibodyForm.antibodySpecificities.trim()
      : !!this.antibodyForm.referralCentre.trim() && !!this.antibodyForm.decisionReason.trim();
  }

  submitAntibodyWorkup(): void {
    const request = this.selectedRequest();
    if (!request || !this.antibodyFormComplete || this.antibodySubmitting()) return;
    const referred = this.antibodyForm.status === 'REFERRED';
    this.antibodySubmitting.set(true);
    this.transfusion
      .recordAntibodyWorkup({
        patientId: request.patientId,
        status: this.antibodyForm.status,
        antibodySpecificities: referred
          ? undefined
          : this.antibodyForm.antibodySpecificities.trim(),
        referralCentre: referred ? this.antibodyForm.referralCentre.trim() : undefined,
        decisionReason: referred ? this.antibodyForm.decisionReason.trim() : undefined,
        transfusionRequestId: referred ? request.id : undefined,
        notes: this.antibodyForm.notes.trim() || undefined,
      })
      .subscribe({
        next: (status) => {
          this.toast.success(this.translate.instant('TRANSFUSION.ANTIBODY_RECORDED'));
          this.antibodySubmitting.set(false);
          this.showAntibodyModal.set(false);
          this.antibodyStatus.set(status);
          this.refreshSelected();
        },
        error: (err: unknown) => {
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('TRANSFUSION.ANTIBODY_ERROR'),
          );
          this.antibodySubmitting.set(false);
        },
      });
  }

  /* ── Role gates: mirror TransfusionController exactly ── */
  readonly canRequest = this.roleContext.hasAnyActiveRole([
    'ROLE_DOCTOR',
    'ROLE_SURGEON',
    'ROLE_MIDWIFE',
  ]);
  readonly canLab = this.roleContext.hasAnyActiveRole([
    'ROLE_LAB_SCIENTIST',
    'ROLE_LAB_TECHNICIAN',
    'ROLE_LAB_MANAGER',
    'ROLE_LAB_DIRECTOR',
  ]);
  /**
   * QA D2120 review: receiving and discarding a unit are blood-bank stock, which the super-admin
   * keeps (TransfusionController#receiveUnit/#discardUnit are not clinical acts); every other
   * lab action is clinical and stays with {@link canLab}.
   */
  readonly canStock = this.roleContext.hasAnyActiveRole([
    'ROLE_LAB_SCIENTIST',
    'ROLE_LAB_TECHNICIAN',
    'ROLE_LAB_MANAGER',
    'ROLE_LAB_DIRECTOR',
    'ROLE_SUPER_ADMIN',
  ]);
  readonly canBedside = this.roleContext.hasAnyActiveRole([
    'ROLE_DOCTOR',
    'ROLE_SURGEON',
    'ROLE_MIDWIFE',
    'ROLE_NURSE',
  ]);

  readonly aboGroups: AboGroup[] = ['A', 'B', 'AB', 'O'];
  readonly rhFactors: RhFactor[] = ['POSITIVE', 'NEGATIVE'];
  readonly screenResults: AntibodyScreenResult[] = ['NEGATIVE', 'POSITIVE', 'NOT_DONE'];
  readonly productTypes: BloodProductType[] = [
    'WHOLE_BLOOD',
    'PACKED_RED_CELLS',
    'FRESH_FROZEN_PLASMA',
    'PLATELETS',
    'CRYOPRECIPITATE',
  ];
  readonly urgencies: TransfusionUrgency[] = ['ROUTINE', 'URGENT', 'EMERGENCY'];
  readonly requestStatuses: TransfusionRequestStatus[] = [
    'REQUESTED',
    'CROSSMATCHED',
    'ISSUED',
    'COMPLETED',
    'CANCELLED',
  ];
  readonly unitStatuses: BloodUnitStatus[] = [
    'AVAILABLE',
    'CROSSMATCHED',
    'ISSUED',
    'TRANSFUSED',
    'RETURNED',
    'DISCARDED',
    'EXPIRED',
  ];
  readonly reactionTypes: TransfusionReactionType[] = [
    'FEBRILE_NON_HEMOLYTIC',
    'ACUTE_HEMOLYTIC',
    'DELAYED_HEMOLYTIC',
    'ALLERGIC',
    'ANAPHYLACTIC',
    'TACO',
    'TRALI',
    'SEPTIC',
    'HYPOTENSIVE',
    'OTHER',
  ];
  readonly reactionSeverities: TransfusionReactionSeverity[] = [
    'MILD',
    'MODERATE',
    'SEVERE',
    'LIFE_THREATENING',
  ];

  /* ── Request modal ── */
  showRequestModal = signal(false);
  requestSubmitting = signal(false);
  selectedPatient = signal<PatientResponse | null>(null);
  patientGroup = signal<PatientBloodGroupResponse | null>(null);
  requestForm = this.emptyRequestForm();

  /* ── Type & screen modal ── */
  showGroupModal = signal(false);
  groupSubmitting = signal(false);
  groupForm = this.emptyGroupForm();
  groupNeedsPatient = signal(false);
  groupPickerPatient = signal<PatientResponse | null>(null);
  /** QA r70 D3221: the chart's (declared) group of the patient being typed; a cross-check, never a default. */
  groupDeclared = signal<string | null>(null);

  /* ── Unit modal ── */
  showUnitModal = signal(false);
  unitSubmitting = signal(false);
  unitForm = this.emptyUnitForm();

  /* ── Crossmatch modal ── */
  showCrossmatchModal = signal(false);
  crossmatchSubmitting = signal(false);
  /** Set when the form records a late result on a bag whose transfusion ended. */
  lateResultUnit = signal<string | null>(null);
  crossmatchForm = this.emptyCrossmatchForm();

  /* ── Bedside modals ── */
  showHangModal = signal(false);
  hangSubmitting = signal(false);
  hangForm = { bloodUnitId: '', verifiedByUserId: '', verificationMethod: 'Wristband scan' };
  /** QA CX-008: colleagues the server accepts as the second check; a staff id was typed by hand. */
  verifierCandidates = signal<VerifierCandidate[]>([]);
  verifierCandidatesFailed = signal(false);

  showReactionModal = signal(false);
  reactionSubmitting = signal(false);
  reactionTarget = signal<string | null>(null);
  reactionForm = this.emptyReactionForm();

  /* ── The open request's administrations (QA CX-008) ── */
  administrations = signal<TransfusionAdministrationResponse[]>([]);
  /** Until 'loaded' nothing says a bag is not already hung, so "hang" is not offered. */
  administrationsState = signal<'loading' | 'loaded' | 'failed'>('loading');

  completingAdministration = signal<TransfusionAdministrationResponse | null>(null);
  completeBusy = signal(false);
  completeVolumeMl: number | null = null;

  stoppingAdministration = signal<TransfusionAdministrationResponse | null>(null);
  stopBusy = signal(false);

  ngOnInit(): void {
    this.loadRequests();
  }

  setTab(tab: Tab): void {
    this.tab.set(tab);
    // Each tab fetches lazily and once per scope; a scope change resets both.
    if (tab === 'units' && !this.unitsLoaded()) {
      this.loadUnits();
    }
    if (tab === 'requests' && !this.requestsLoaded()) {
      this.loadRequests();
    }
  }

  /* ── Requests ── */

  loadRequests(): void {
    this.loading.set(true);
    const filter = this.requestStatusFilter();
    this.transfusion
      .listRequests(filter || undefined)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.loading.set(false)),
      )
      .subscribe({
        next: (list) => {
          this.requests.set(list ?? []);
          this.requestsLoaded.set(true);
          this.loading.set(false);
        },
        error: () => {
          this.toast.error(this.translate.instant('TRANSFUSION.LOAD_ERROR'));
          this.loading.set(false);
        },
      });
  }

  onRequestStatusFilter(status: string): void {
    this.requestStatusFilter.set(status as TransfusionRequestStatus | '');
    this.loadRequests();
  }

  openRequest(request: TransfusionRequestResponse): void {
    // Always re-fetch: the list projection carries no units or crossmatches,
    // so rendering the panel from it would show an empty workup for a request
    // that has one.
    this.transfusion.getRequest(request.id).subscribe({
      next: (full) => this.showRequest(full),
      error: () => this.toast.error(this.translate.instant('TRANSFUSION.LOAD_ERROR')),
    });
  }

  closeRequest(): void {
    this.selectedRequest.set(null);
    this.administrations.set([]);
  }

  private showRequest(request: TransfusionRequestResponse, thenFocus = false): void {
    // Another request's running bag must not sit under this one's header.
    if (this.selectedRequest()?.id !== request.id) {
      this.administrations.set([]);
    }
    this.selectedRequest.set(request);
    // QA D3351: the biologist's records are read only for a patient the rule holds.
    this.antibodyStatus.set(null);
    if (request.alloImmunised) this.loadAntibodyStatus(request.patientId);
    this.loadAdministrations(thenFocus);
  }

  /**
   * QA CX-008: what is running on the open request. The server lists them by
   * patient, the open request keeps its own. An answer that arrives after the
   * panel moved to another request is dropped.
   */
  loadAdministrations(thenFocus = false): void {
    const request = this.selectedRequest();
    if (!request) return;
    this.administrationsState.set('loading');
    this.transfusion
      .listAdministrationsForPatient(request.patientId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (list) => {
          if (this.selectedRequest()?.id !== request.id) return;
          this.administrations.set((list ?? []).filter((a) => a.requestId === request.id));
          this.administrationsState.set('loaded');
          if (thenFocus) {
            focusWhenRendered(this.host.nativeElement, '#tx-administrations-heading');
          }
        },
        error: () => {
          if (this.selectedRequest()?.id !== request.id) return;
          // No stale actions: what these cards offer may already have ended.
          this.administrations.set([]);
          this.administrationsState.set('failed');
        },
      });
  }

  openNewRequest(): void {
    this.requestForm = this.emptyRequestForm();
    this.selectedPatient.set(null);
    this.patientGroup.set(null);
    this.requestAntibody.set(null);
    this.showRequestModal.set(true);
  }

  closeNewRequest(): void {
    this.showRequestModal.set(false);
  }

  onPatientPicked(patient: PatientResponse | null): void {
    this.selectedPatient.set(patient);
    this.requestForm.patientId = patient?.id ?? '';
    this.patientGroup.set(null);
    this.requestAntibody.set(null);
    if (!patient) return;
    // The allo-immunisation flag follows the patient: asked for on its own, so it shows for a patient with
    // no type and screen at this hospital yet (the emergency request that needs the declaration).
    // Review of #1300: every answer is checked against the patient still picked. A late answer (or a late
    // failure) for the previous patient never fills, or clears, the form of the one picked since.
    const stillPicked = () => this.requestForm.patientId === patient.id;
    this.transfusion.getAntibodyStatus(patient.id).subscribe({
      next: (status) => {
        if (stillPicked()) this.requestAntibody.set(status);
      },
      error: () => {
        if (stillPicked()) this.requestAntibody.set(null);
      },
    });
    // Surface the standing type and screen before the clinician commits, so a
    // missing or lapsed one is visible here rather than as a 400 on submit.
    // QA D3352: no type and screen yet is a 204, read as null.
    this.transfusion.getCurrentBloodGroup(patient.id).subscribe({
      next: (group) => {
        if (stillPicked()) this.patientGroup.set(group ?? null);
      },
      error: () => {
        if (stillPicked()) this.patientGroup.set(null);
      },
    });
  }

  submitRequest(): void {
    if (!this.requestForm.patientId || !this.vitalUrgencyDeclared) return;
    const declared = this.needsVitalUrgency;
    this.requestSubmitting.set(true);
    this.transfusion
      .createRequest({
        patientId: this.requestForm.patientId,
        productType: this.requestForm.productType,
        unitsRequested: this.requestForm.unitsRequested,
        indication: this.requestForm.indication.trim(),
        urgency: this.requestForm.urgency,
        notes: this.requestForm.notes.trim() || undefined,
        // QA D3351: the declaration of a vital emergency, sent only when it was asked for.
        ...(declared && this.requestForm.vitalUrgencyLevel
          ? {
              vitalUrgencyLevel: this.requestForm.vitalUrgencyLevel,
              vitalUrgencyReason: this.requestForm.vitalUrgencyReason.trim(),
              preTransfusionSampleDrawn: true,
            }
          : {}),
      })
      .subscribe({
        next: (created) => {
          this.toast.success(this.translate.instant('TRANSFUSION.REQUEST_CREATED'));
          this.requestSubmitting.set(false);
          this.showRequestModal.set(false);
          this.showRequest(created);
          this.loadRequests();
        },
        error: (err: unknown) => {
          // QA D885: the server's reason, in the caller's language.
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('TRANSFUSION.REQUEST_ERROR'),
          );
          this.requestSubmitting.set(false);
        },
      });
  }

  /** QA D1081: a transfusion request is cancelled by the clinician who asked for it. */
  canCancelRequest(r: TransfusionRequestResponse): boolean {
    return this.canRequest && isOrderCancellable(r.status) && this.authorship.isAuthor(r);
  }

  /** QA D1201: the request being cancelled; the shared reason dialog asks why. */
  cancellingRequest = signal<TransfusionRequestResponse | null>(null);
  cancelRequestBusy = signal(false);

  cancelRequest(request: TransfusionRequestResponse): void {
    this.cancellingRequest.set(request);
  }

  confirmCancelRequest(request: TransfusionRequestResponse, reason: string): void {
    if (this.cancelRequestBusy()) return;
    this.cancelRequestBusy.set(true);
    this.transfusion.cancelRequest(request.id, reason).subscribe({
      next: (updated) => {
        this.toast.success(this.translate.instant('TRANSFUSION.REQUEST_CANCELLED'));
        this.cancelRequestBusy.set(false);
        this.cancellingRequest.set(null);
        this.showRequest(updated);
        this.loadRequests();
      },
      error: (err: unknown) => {
        this.cancelRequestBusy.set(false);
        this.toast.error(refusalReason(err) ?? this.translate.instant('TRANSFUSION.CANCEL_ERROR'));
      },
    });
  }

  /* ── Type and screen ── */

  openGroupModal(): void {
    this.resetGroupFor(this.selectedPatient()?.id ?? '', this.selectedPatient()?.bloodType);
    this.showGroupModal.set(true);
  }

  /**
   * QA CX-011: a repeat screen is lab work, and the lab raises no requests, so
   * the form inside "new request" was out of its reach. From an open request it
   * screens that request's patient, whichever patient the request form last held.
   */
  openGroupModalForRequest(request: TransfusionRequestResponse): void {
    this.resetGroupFor(request.patientId, request.patientDeclaredBloodType);
    this.showGroupModal.set(true);
  }

  /** From the page: the laboratory picks the patient in the form. */
  openGroupModalForAnyPatient(): void {
    this.groupPickerPatient.set(null);
    this.resetGroupFor('');
    this.showGroupModal.set(true);
  }

  private resetGroupFor(patientId: string, declared?: string | null): void {
    this.groupForm = this.emptyGroupForm();
    this.groupForm.patientId = patientId;
    this.groupNeedsPatient.set(!patientId);
    this.groupDeclared.set(declared || null);
  }

  onGroupPatientPicked(patient: PatientResponse | null): void {
    this.groupPickerPatient.set(patient);
    this.groupForm.patientId = patient?.id ?? '';
    this.groupDeclared.set(patient?.bloodType || null);
  }

  /** « O+ », « AB- »: the lab's ABO and Rh written the way the chart writes a group. */
  labGroupCode(abo?: AboGroup | null, rh?: RhFactor | null): string {
    if (!abo || !rh) return '';
    return `${abo}${rh === 'POSITIVE' ? '+' : '-'}`;
  }

  /**
   * QA r70 D3221: the chart's declared group and a lab-typed one disagree. Nothing is reconciled automatically
   * (the lab's group stands for the crossmatch); the screen says so, so somebody checks the identity and the chart.
   */
  groupsDisagree(
    declared: string | null | undefined,
    abo?: AboGroup | null,
    rh?: RhFactor | null,
  ): boolean {
    const typed = this.labGroupCode(abo, rh);
    if (!declared || !typed) return false;
    const chart = declared.trim().toUpperCase().replace('−', '-');
    return chart !== typed;
  }

  closeGroupModal(): void {
    this.showGroupModal.set(false);
  }

  submitGroup(): void {
    if (!this.groupForm.patientId) return;
    this.groupSubmitting.set(true);
    this.transfusion
      .recordBloodGroup({
        patientId: this.groupForm.patientId,
        aboGroup: this.groupForm.aboGroup,
        rhFactor: this.groupForm.rhFactor,
        antibodyScreen: this.groupForm.antibodyScreen,
        antibodyDetail: this.groupForm.antibodyDetail.trim() || undefined,
        correctionReason: this.groupForm.correctionReason.trim() || undefined,
        notes: this.groupForm.notes.trim() || undefined,
      })
      .subscribe({
        next: (group) => {
          this.toast.success(this.translate.instant('TRANSFUSION.GROUP_RECORDED'));
          this.groupSubmitting.set(false);
          this.showGroupModal.set(false);
          this.patientGroup.set(group);
          // The open request now stands on this screen: its banner and group follow.
          this.refreshSelected();
        },
        error: (err: unknown) => {
          // The backend refuses a silent group change without a correction
          // reason; the message is the useful part (QA D885: now in the
          // caller's language), so it is shown, with a generic fallback.
          this.toast.error(refusalReason(err) ?? this.translate.instant('TRANSFUSION.GROUP_ERROR'));
          this.groupSubmitting.set(false);
        },
      });
  }

  /* ── Units ── */

  loadUnits(): void {
    this.loading.set(true);
    const filter = this.unitStatusFilter();
    this.transfusion
      .listUnits(filter || undefined)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.loading.set(false)),
      )
      .subscribe({
        next: (list) => {
          this.units.set(list ?? []);
          this.unitsLoaded.set(true);
          this.loading.set(false);
        },
        error: () => {
          this.toast.error(this.translate.instant('TRANSFUSION.LOAD_ERROR'));
          this.loading.set(false);
        },
      });
  }

  onUnitStatusFilter(status: string): void {
    this.unitStatusFilter.set(status as BloodUnitStatus | '');
    this.loadUnits();
  }

  /**
   * QA D858/D883/D884: the pickers stop at today / now and the counts at what
   * the server accepts, so the common slip is caught before it is sent. The
   * server still refuses it on its own (these are hints, not the rule).
   */
  readonly maxUnitsPerRequest = 20;
  readonly maxUnitVolumeMl = 1000;
  maxCollectedOn = localDateString();
  maxOnsetAt = nowLocalDatetime();

  openUnitModal(): void {
    this.maxCollectedOn = localDateString();
    this.unitForm = this.emptyUnitForm();
    this.showUnitModal.set(true);
  }

  closeUnitModal(): void {
    this.showUnitModal.set(false);
  }

  submitUnit(): void {
    this.unitSubmitting.set(true);
    this.transfusion
      .receiveUnit({
        unitNumber: this.unitForm.unitNumber.trim(),
        productType: this.unitForm.productType,
        aboGroup: this.unitForm.aboGroup,
        rhFactor: this.unitForm.rhFactor,
        volumeMl: this.unitForm.volumeMl || undefined,
        collectedOn: this.unitForm.collectedOn || undefined,
        expiresOn: this.unitForm.expiresOn,
        source: this.unitForm.source.trim() || undefined,
      })
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('TRANSFUSION.UNIT_RECEIVED'));
          this.unitSubmitting.set(false);
          this.showUnitModal.set(false);
          this.loadUnits();
        },
        error: (err: unknown) => {
          // QA D885: the server's reason, in the caller's language.
          this.toast.error(refusalReason(err) ?? this.translate.instant('TRANSFUSION.UNIT_ERROR'));
          this.unitSubmitting.set(false);
        },
      });
  }

  /** QA D1201: the unit being discarded; the shared reason dialog asks why. */
  discardingUnit = signal<BloodUnitResponse | null>(null);
  discardBusy = signal(false);

  discardUnit(unit: BloodUnitResponse): void {
    this.discardingUnit.set(unit);
  }

  confirmDiscardUnit(unit: BloodUnitResponse, reason: string): void {
    if (this.discardBusy()) return;
    this.discardBusy.set(true);
    this.transfusion.discardUnit(unit.id, reason).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('TRANSFUSION.UNIT_DISCARDED'));
        this.discardBusy.set(false);
        this.discardingUnit.set(null);
        this.loadUnits();
      },
      error: (err: unknown) => {
        this.discardBusy.set(false);
        this.toast.error(refusalReason(err) ?? this.translate.instant('TRANSFUSION.DISCARD_ERROR'));
      },
    });
  }

  /* ── Crossmatch and issue ── */

  openCrossmatch(): void {
    this.lateResultUnit.set(null);
    this.crossmatchForm = this.emptyCrossmatchForm();
    // QA CX-011: a bag this request already holds can be crossmatched again, and a
    // refused issue tells the lab to. The stock list does not hold it. That includes
    // a bag already running: an emergency bag goes up before its crossmatch, and the
    // result still has to reach the clinician.
    const held = (this.selectedRequest()?.units ?? []).filter(
      (u) => u.status === 'CROSSMATCHED' || u.status === 'ISSUED',
    );
    this.assignableUnits.set(held);
    this.transfusion.listAssignableUnits().subscribe({
      next: (list) =>
        this.assignableUnits.set([
          ...held,
          ...(list ?? []).filter((u) => !held.some((h) => h.id === u.id)),
        ]),
      error: () => this.assignableUnits.set(held),
    });
    this.showCrossmatchModal.set(true);
  }

  closeCrossmatch(): void {
    this.showCrossmatchModal.set(false);
  }

  /**
   * A bag whose transfusion ended can still be found incompatible, on a closed request too:
   * the server records it and tells the requester and the nurse. It takes nothing else.
   */
  canRecordLateResult(administration: TransfusionAdministrationResponse): boolean {
    return (
      this.canLab &&
      !this.isInProgress(administration) &&
      administration.crossmatch?.compatible !== false
    );
  }

  openLateResult(administration: TransfusionAdministrationResponse): void {
    this.crossmatchForm = {
      ...this.emptyCrossmatchForm(),
      bloodUnitId: administration.bloodUnitId,
      compatible: false,
    };
    this.lateResultUnit.set(administration.unitNumber);
    this.showCrossmatchModal.set(true);
  }

  submitCrossmatch(): void {
    const request = this.selectedRequest();
    if (!request || !this.crossmatchForm.bloodUnitId) return;
    // QA D1507: an incompatible crossmatch records why.
    if (!this.crossmatchForm.compatible && !this.crossmatchForm.incompatibilityReason.trim())
      return;
    if (!this.crossmatchCompatibilised) return;
    const attested = this.crossmatchNeedsCompatibilisation;
    this.crossmatchSubmitting.set(true);
    this.transfusion
      .recordCrossmatch(request.id, {
        bloodUnitId: this.crossmatchForm.bloodUnitId,
        compatible: this.crossmatchForm.compatible,
        method: this.crossmatchForm.method.trim() || undefined,
        incompatibilityReason: this.crossmatchForm.incompatibilityReason.trim() || undefined,
        // QA D3351: sent only for an allo-immunised patient; every other result keeps its payload.
        ...(attested
          ? {
              sampleCollectedAt: toServerDateTime(this.crossmatchForm.sampleCollectedAt),
              antiglobulinMethod: true,
              antigenNegative: true,
              rhKellMatched: true,
            }
          : {}),
      })
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('TRANSFUSION.CROSSMATCH_RECORDED'));
          this.crossmatchSubmitting.set(false);
          this.showCrossmatchModal.set(false);
          this.refreshSelected();
        },
        error: (err: unknown) => {
          // The most important failure here is the ABO/Rh refusal. It carries
          // the groups involved, so the operator is told what to look at.
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('TRANSFUSION.CROSSMATCH_ERROR'),
          );
          this.crossmatchSubmitting.set(false);
        },
      });
  }

  /* ── Emergency release (QA CX-008; user-supplied guidance 2026-11-06) ── */

  /** The server's rules for the open request; this page holds no copy of them. */
  emergencyOptions = signal<EmergencyReleaseOptions | null>(null);
  showEmergencyModal = signal(false);
  emergencySubmitting = signal(false);
  emergencyUnitId = '';
  emergencyReason = '';
  emergencyAuthoriserId = '';
  emergencyNegativeStated = false;
  /** V380: no physician can be reached: the bag still goes out, the laboratory director is alerted. */
  emergencyUnreachable = false;
  /** V380: the physician's order was verbal (the default): it is countersigned. */
  emergencyVerbalOrder = true;
  /** V380: the biologist deciding with the physician, for a patient with a known antibody. */
  emergencyBiologistId = '';

  /** V380: what the O positive authority rule says for the open request; the server still decides. */
  positiveRule() {
    return this.emergencyOptions()?.positiveRule ?? null;
  }

  /** The O positive decision belongs to a named physician who is not the person issuing. */
  emergencyPhysicianTier(): boolean {
    const rule = this.positiveRule();
    return this.emergencyDecisionRequired() && !!rule && !rule.issuerMayDecide;
  }

  /** Who the form offers as deciding: physicians only outside the low-risk tier, never the caller. */
  emergencyDeciders(): VerifierCandidate[] {
    const options = this.emergencyOptions();
    if (!options) return [];
    const rule = options.positiveRule;
    return rule && !rule.issuerMayDecide ? rule.physicians : options.deciders;
  }

  /** A name is needed unless no physician can be reached: that statement releases the bag too. */
  emergencyNameMissing(): boolean {
    if (this.emergencyPhysicianTier() && this.emergencyUnreachable) return false;
    return !this.emergencyAuthoriserId;
  }

  /** What the submit button waits for. Nothing here waits for a person who cannot be reached. */
  emergencyFormIncomplete(): boolean {
    return (
      !this.emergencyUnitId ||
      (this.emergencyDecisionRequired() &&
        (this.emergencyNameMissing() || !this.emergencyReason.trim())) ||
      (this.emergencyStatementRequired() && !this.emergencyNegativeStated)
    );
  }

  onEmergencyUnreachableChanged(): void {
    if (this.emergencyUnreachable) this.emergencyAuthoriserId = '';
  }

  /** The server's uncrossmatched release had no way in: a bag only reached the row by a crossmatch. */
  canReleaseInEmergency(request: TransfusionRequestResponse): boolean {
    return (
      this.canLab &&
      request.urgency === 'EMERGENCY' &&
      // O is the universal red cell donor, not a universal plasma or platelet donor.
      (request.productType === 'PACKED_RED_CELLS' || request.productType === 'WHOLE_BLOOD') &&
      request.status !== 'COMPLETED' &&
      request.status !== 'CANCELLED'
    );
  }

  /** The bag picked needs a reason and a name before the server releases it. */
  emergencyDecisionRequired(): boolean {
    return !!this.emergencyOptions()?.units.find((u) => u.id === this.emergencyUnitId)
      ?.decisionRequired;
  }

  /** The bag picked goes out ahead of O negative the system shows: a statement first. */
  emergencyStatementRequired(): boolean {
    const options = this.emergencyOptions();
    const id = this.emergencyUnitId;
    return !!(
      options?.units.find((u) => u.id === id)?.statementRequired ||
      options?.incompatibleUnits.find((u) => u.id === id)?.statementRequired
    );
  }

  /** The unit numbers the form names beside the statement. */
  negativeStockNumbers(): string {
    return (this.emergencyOptions()?.negativeStock ?? []).map((u) => u.unitNumber).join(', ');
  }

  /** A refusal can mean the stock moved: the form asks for what the server now asks. */
  private refreshEmergencyOptions(request: TransfusionRequestResponse): void {
    this.transfusion.emergencyReleaseOptions(request.id).subscribe({
      next: (options) => {
        if (this.selectedRequest()?.id !== request.id) return;
        // A statement is about the bags it named: another list asks again.
        const before = (this.emergencyOptions()?.negativeStock ?? []).map((u) => u.id).join();
        if (before !== options.negativeStock.map((u) => u.id).join()) {
          this.emergencyNegativeStated = false;
        }
        this.emergencyOptions.set(options);
      },
      // The form keeps what it has: the server still decides on the next submit.
      error: () => this.toast.error(this.translate.instant('TRANSFUSION.LOAD_ERROR')),
    });
  }

  /** A statement is made for one bag: picking another asks again. */
  onEmergencyUnitPicked(): void {
    this.emergencyNegativeStated = false;
  }

  private loadEmergencyOptions(request: TransfusionRequestResponse): void {
    this.emergencyNegativeStated = false;
    this.emergencyUnitId = '';
    this.emergencyReason = '';
    this.emergencyAuthoriserId = '';
    this.emergencyUnreachable = false;
    this.emergencyVerbalOrder = true;
    this.emergencyBiologistId = '';
    this.emergencyOptions.set(null);
    this.transfusion.emergencyReleaseOptions(request.id).subscribe({
      // A late answer for a request the panel has left is dropped.
      next: (options) => {
        if (this.selectedRequest()?.id === request.id) this.emergencyOptions.set(options);
      },
      error: () => this.toast.error(this.translate.instant('TRANSFUSION.LOAD_ERROR')),
    });
  }

  openEmergencyRelease(request: TransfusionRequestResponse): void {
    this.loadEmergencyOptions(request);
    this.showEmergencyModal.set(true);
  }

  closeEmergencyRelease(): void {
    this.showEmergencyModal.set(false);
  }

  submitEmergencyRelease(): void {
    const request = this.selectedRequest();
    if (!request || !this.emergencyUnitId || this.emergencySubmitting()) return;
    const documented = this.emergencyDecisionRequired();
    if (documented && (!this.emergencyReason.trim() || this.emergencyNameMissing())) return;
    const stating = this.emergencyStatementRequired();
    if (stating && !this.emergencyNegativeStated) return;
    const physicianTier = this.emergencyPhysicianTier();
    this.emergencySubmitting.set(true);
    const release = documented
      ? this.transfusion.emergencyRelease(request.id, {
          bloodUnitId: this.emergencyUnitId,
          releaseType: 'O_POSITIVE_DECISION',
          reason: this.emergencyReason.trim(),
          ...(this.emergencyAuthoriserId ? { authoriserUserId: this.emergencyAuthoriserId } : {}),
          ...(stating ? { negativeUnobtainable: true } : {}),
          // V380: outside the low-risk tier the order is a physician's, or nobody could be reached.
          ...(physicianTier && !this.emergencyAuthoriserId ? { physicianUnreachable: true } : {}),
          ...(physicianTier && this.emergencyAuthoriserId
            ? { verbalOrder: this.emergencyVerbalOrder }
            : {}),
          ...(physicianTier && this.emergencyBiologistId
            ? { biologistUserId: this.emergencyBiologistId }
            : {}),
        })
      : this.transfusion.issueUnit(request.id, this.emergencyUnitId);
    release.subscribe({
      next: () => {
        this.toast.success(this.translate.instant('TRANSFUSION.UNIT_ISSUED'));
        this.emergencySubmitting.set(false);
        this.showEmergencyModal.set(false);
        this.refreshSelected();
      },
      error: (err: unknown) => {
        this.emergencySubmitting.set(false);
        this.toast.error(refusalReason(err) ?? this.translate.instant('TRANSFUSION.ISSUE_ERROR'));
        this.refreshEmergencyOptions(request);
      },
    });
  }

  /* ── Emergency release, serologically incompatible ── */

  showIncompatibleModal = signal(false);
  incompatibleSubmitting = signal(false);

  /** A separate action: only a bag with an incompatible result on this request can take it. */
  canReleaseIncompatible(request: TransfusionRequestResponse): boolean {
    return (
      this.canReleaseInEmergency(request) &&
      (request.crossmatches ?? []).some((c) => c.compatible === false)
    );
  }

  openIncompatibleRelease(request: TransfusionRequestResponse): void {
    this.loadEmergencyOptions(request);
    this.showIncompatibleModal.set(true);
  }

  closeIncompatibleRelease(): void {
    this.showIncompatibleModal.set(false);
  }

  submitIncompatibleRelease(): void {
    const request = this.selectedRequest();
    if (
      !request ||
      !this.emergencyUnitId ||
      !this.emergencyReason.trim() ||
      !this.emergencyAuthoriserId ||
      this.incompatibleSubmitting()
    ) {
      return;
    }
    const stating = this.emergencyStatementRequired();
    if (stating && !this.emergencyNegativeStated) return;
    this.incompatibleSubmitting.set(true);
    this.transfusion
      .emergencyRelease(request.id, {
        bloodUnitId: this.emergencyUnitId,
        releaseType: 'SEROLOGICALLY_INCOMPATIBLE',
        reason: this.emergencyReason.trim(),
        authoriserUserId: this.emergencyAuthoriserId,
        ...(stating ? { negativeUnobtainable: true } : {}),
      })
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('TRANSFUSION.UNIT_ISSUED'));
          this.incompatibleSubmitting.set(false);
          this.showIncompatibleModal.set(false);
          this.refreshSelected();
        },
        error: (err: unknown) => {
          this.incompatibleSubmitting.set(false);
          this.toast.error(refusalReason(err) ?? this.translate.instant('TRANSFUSION.ISSUE_ERROR'));
          this.refreshEmergencyOptions(request);
        },
      });
  }

  issueUnit(unit: BloodUnitResponse): void {
    const request = this.selectedRequest();
    if (!request) return;
    this.transfusion.issueUnit(request.id, unit.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('TRANSFUSION.UNIT_ISSUED'));
        this.refreshSelected();
      },
      error: (err: unknown) =>
        this.toast.error(refusalReason(err) ?? this.translate.instant('TRANSFUSION.ISSUE_ERROR')),
    });
  }

  /* ── Bedside ── */

  openHang(unit: BloodUnitResponse): void {
    this.hangForm = {
      bloodUnitId: unit.id,
      verifiedByUserId: '',
      verificationMethod: 'Wristband scan',
    };
    this.verifierCandidatesFailed.set(false);
    this.transfusion
      .listVerifierCandidates()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (list) => this.verifierCandidates.set(list ?? []),
        error: () => {
          this.verifierCandidates.set([]);
          this.verifierCandidatesFailed.set(true);
        },
      });
    this.showHangModal.set(true);
  }

  closeHang(): void {
    this.showHangModal.set(false);
  }

  submitHang(): void {
    const request = this.selectedRequest();
    if (!request || !this.hangForm.verifiedByUserId.trim()) return;
    this.hangSubmitting.set(true);
    this.transfusion
      .startAdministration({
        requestId: request.id,
        bloodUnitId: this.hangForm.bloodUnitId,
        verifiedByUserId: this.hangForm.verifiedByUserId.trim(),
        verificationMethod: this.hangForm.verificationMethod.trim() || undefined,
      })
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('TRANSFUSION.HUNG'));
          this.hangSubmitting.set(false);
          this.showHangModal.set(false);
          this.refreshSelected();
        },
        error: (err: unknown) => {
          // QA D885: the server's reason, in the caller's language.
          this.toast.error(refusalReason(err) ?? this.translate.instant('TRANSFUSION.HANG_ERROR'));
          this.hangSubmitting.set(false);
        },
      });
  }

  openReaction(administrationId: string): void {
    this.maxOnsetAt = nowLocalDatetime();
    this.reactionForm = this.emptyReactionForm();
    // QA CX-008: an emergency path. The nurse reports what she sees now and can
    // move the time back; she should not have to set the clock first.
    this.reactionForm.onsetAt = this.maxOnsetAt;
    this.reactionTarget.set(administrationId);
    this.showReactionModal.set(true);
  }

  closeReaction(): void {
    this.showReactionModal.set(false);
    this.reactionTarget.set(null);
  }

  submitReaction(): void {
    const administrationId = this.reactionTarget();
    if (!administrationId || !this.reactionForm.signsSymptoms.trim()) return;
    this.reactionSubmitting.set(true);
    this.transfusion
      .recordReaction(administrationId, {
        reactionType: this.reactionForm.reactionType,
        severity: this.reactionForm.severity,
        onsetAt: toServerDateTime(this.reactionForm.onsetAt), // QA D777/D784: the picker gives local wall time; the server keeps UTC.
        signsSymptoms: this.reactionForm.signsSymptoms.trim(),
        actionsTaken: this.reactionForm.actionsTaken.trim() || undefined,
        unitReturnedToLab: this.reactionForm.unitReturnedToLab,
      })
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('TRANSFUSION.REACTION_RECORDED'));
          this.reactionSubmitting.set(false);
          this.closeReaction();
          this.refreshSelected(true);
        },
        error: (err: unknown) => {
          // QA D885: the server's reason, in the caller's language.
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('TRANSFUSION.REACTION_ERROR'),
          );
          this.reactionSubmitting.set(false);
        },
      });
  }

  /* ── A transfusion in progress (QA CX-008) ── */

  isInProgress(administration: TransfusionAdministrationResponse): boolean {
    return administration.status === 'IN_PROGRESS';
  }

  openComplete(administration: TransfusionAdministrationResponse): void {
    this.completeVolumeMl = null;
    this.completingAdministration.set(administration);
  }

  confirmComplete(administration: TransfusionAdministrationResponse): void {
    if (this.completeBusy()) return;
    this.completeBusy.set(true);
    this.transfusion
      .completeAdministration(administration.id, this.completeVolumeMl ?? undefined)
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('TRANSFUSION.ADMIN_COMPLETED'));
          this.completeBusy.set(false);
          this.completingAdministration.set(null);
          this.refreshSelected(true);
        },
        error: (err: unknown) => {
          this.completeBusy.set(false);
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('TRANSFUSION.ADMIN_COMPLETE_ERROR'),
          );
          // A colleague may have ended it: the cards say so.
          this.loadAdministrations();
        },
      });
  }

  openStop(administration: TransfusionAdministrationResponse): void {
    this.stoppingAdministration.set(administration);
  }

  confirmStop(administration: TransfusionAdministrationResponse, reason: string): void {
    if (this.stopBusy()) return;
    this.stopBusy.set(true);
    this.transfusion.stopAdministration(administration.id, reason).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('TRANSFUSION.ADMIN_STOPPED'));
        this.stopBusy.set(false);
        this.stoppingAdministration.set(null);
        this.refreshSelected(true);
      },
      error: (err: unknown) => {
        this.stopBusy.set(false);
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('TRANSFUSION.ADMIN_STOP_ERROR'),
        );
        this.loadAdministrations();
      },
    });
  }

  /* ── Derived ── */

  /** Units on this request that the lab may still release to the ward. */
  readonly issuableUnits = computed(() =>
    (this.selectedRequest()?.units ?? []).filter((u) => u.status === 'CROSSMATCHED'),
  );

  /** Units already at the bedside, waiting to be hung. */
  readonly hangableUnits = computed(() =>
    (this.selectedRequest()?.units ?? []).filter(
      (u) => u.status === 'ISSUED' && !this.administrationOf(u.id),
    ),
  );

  administrationOf(unitId: string): TransfusionAdministrationResponse | undefined {
    return this.administrations().find((a) => a.bloodUnitId === unitId);
  }

  /** QA CX-008: a bag is hung once. The server refuses a second, so it is not offered. */
  canHang(unit: BloodUnitResponse): boolean {
    return (
      this.canBedside &&
      unit.status === 'ISSUED' &&
      this.administrationsState() === 'loaded' &&
      !this.administrationOf(unit.id)
    );
  }

  /** A bag on the open request found incompatible while it is running. Nothing stops it: the clinician decides. */
  runningIncompatible(): TransfusionAdministrationResponse[] {
    return this.administrations().filter(
      (a) => this.isInProgress(a) && a.crossmatch?.compatible === false,
    );
  }

  crossmatchFor(unitId: string) {
    return (this.selectedRequest()?.crossmatches ?? []).find((c) => c.bloodUnitId === unitId);
  }

  statusClass(status: string): string {
    switch (status) {
      case 'COMPLETED':
      case 'TRANSFUSED':
      case 'AVAILABLE':
        return 'status-completed';
      case 'REQUESTED':
      case 'RETURNED':
        return 'status-preliminary';
      case 'CROSSMATCHED':
      case 'ISSUED':
        return 'status-progress';
      case 'CANCELLED':
      case 'DISCARDED':
      case 'EXPIRED':
        return 'status-cancelled';
      default:
        return '';
    }
  }

  urgencyClass(urgency: TransfusionUrgency): string {
    switch (urgency) {
      case 'EMERGENCY':
        return 'urgency-emergency';
      case 'URGENT':
        return 'urgency-urgent';
      default:
        return 'urgency-routine';
    }
  }

  private refreshSelected(thenFocus = false): void {
    const current = this.selectedRequest();
    if (!current) return;
    this.transfusion.getRequest(current.id).subscribe({
      next: (full) => {
        // The panel was closed while this was on its way: it stays closed.
        if (this.selectedRequest()?.id === current.id) {
          this.showRequest(full, thenFocus);
        }
        this.loadRequests();
      },
      error: () => this.toast.error(this.translate.instant('TRANSFUSION.LOAD_ERROR')),
    });
  }

  private emptyRequestForm() {
    return {
      patientId: '',
      productType: 'PACKED_RED_CELLS' as BloodProductType,
      unitsRequested: 1,
      indication: '',
      urgency: 'ROUTINE' as TransfusionUrgency,
      notes: '',
      vitalUrgencyLevel: '' as VitalUrgencyLevel | '',
      vitalUrgencyReason: '',
      preTransfusionSampleDrawn: false,
    };
  }

  private emptyCrossmatchForm() {
    return {
      bloodUnitId: '',
      compatible: true,
      method: '',
      incompatibilityReason: '',
      sampleCollectedAt: '',
      antiglobulinMethod: false,
      antigenNegative: false,
      rhKellMatched: false,
    };
  }

  private emptyGroupForm() {
    return {
      patientId: '',
      aboGroup: 'O' as AboGroup,
      rhFactor: 'POSITIVE' as RhFactor,
      antibodyScreen: 'NEGATIVE' as AntibodyScreenResult,
      antibodyDetail: '',
      correctionReason: '',
      notes: '',
    };
  }

  private emptyUnitForm() {
    return {
      unitNumber: '',
      productType: 'PACKED_RED_CELLS' as BloodProductType,
      aboGroup: 'O' as AboGroup,
      rhFactor: 'NEGATIVE' as RhFactor,
      volumeMl: 0,
      collectedOn: '',
      expiresOn: '',
      source: '',
    };
  }

  private emptyReactionForm() {
    return {
      reactionType: 'FEBRILE_NON_HEMOLYTIC' as TransfusionReactionType,
      severity: 'MILD' as TransfusionReactionSeverity,
      onsetAt: '',
      signsSymptoms: '',
      actionsTaken: '',
      unitReturnedToLab: true,
    };
  }
}
