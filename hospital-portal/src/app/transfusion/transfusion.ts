import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  AboGroup,
  AntibodyScreenResult,
  BloodProductType,
  BloodUnitResponse,
  BloodUnitStatus,
  PatientBloodGroupResponse,
  RhFactor,
  TransfusionReactionSeverity,
  TransfusionReactionType,
  TransfusionRequestResponse,
  TransfusionRequestStatus,
  TransfusionService,
  TransfusionUrgency,
} from '../services/transfusion.service';
import { PatientResponse } from '../services/patient.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';
import { PatientPickerComponent } from '../shared/patient-picker/patient-picker.component';

type Tab = 'requests' | 'units';

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
  imports: [CommonModule, FormsModule, TranslateModule, EnumLabelPipe, PatientPickerComponent],
  templateUrl: './transfusion.html',
  styleUrl: './transfusion.scss',
})
export class TransfusionComponent implements OnInit {
  private readonly transfusion = inject(TransfusionService);
  private readonly toast = inject(ToastService);
  private readonly roleContext = inject(RoleContextService);
  private readonly translate = inject(TranslateService);

  tab = signal<Tab>('requests');
  loading = signal(false);

  requests = signal<TransfusionRequestResponse[]>([]);
  requestStatusFilter = signal<TransfusionRequestStatus | ''>('');
  selectedRequest = signal<TransfusionRequestResponse | null>(null);

  units = signal<BloodUnitResponse[]>([]);
  unitStatusFilter = signal<BloodUnitStatus | ''>('');
  assignableUnits = signal<BloodUnitResponse[]>([]);

  /* ── Role gates: mirror TransfusionController exactly ── */
  readonly canRequest = this.roleContext.hasAnyActiveRole([
    'ROLE_DOCTOR',
    'ROLE_SURGEON',
    'ROLE_MIDWIFE',
    'ROLE_SUPER_ADMIN',
  ]);
  readonly canLab = this.roleContext.hasAnyActiveRole([
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
    'ROLE_SUPER_ADMIN',
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

  /* ── Unit modal ── */
  showUnitModal = signal(false);
  unitSubmitting = signal(false);
  unitForm = this.emptyUnitForm();

  /* ── Crossmatch modal ── */
  showCrossmatchModal = signal(false);
  crossmatchSubmitting = signal(false);
  crossmatchForm = { bloodUnitId: '', compatible: true, method: '', incompatibilityReason: '' };

  /* ── Bedside modals ── */
  showHangModal = signal(false);
  hangSubmitting = signal(false);
  hangForm = { bloodUnitId: '', verifiedByStaffId: '', verificationMethod: 'Wristband scan' };

  showReactionModal = signal(false);
  reactionSubmitting = signal(false);
  reactionTarget = signal<string | null>(null);
  reactionForm = this.emptyReactionForm();

  ngOnInit(): void {
    this.loadRequests();
  }

  setTab(tab: Tab): void {
    this.tab.set(tab);
    if (tab === 'units' && this.units().length === 0) {
      this.loadUnits();
    }
  }

  /* ── Requests ── */

  loadRequests(): void {
    this.loading.set(true);
    const filter = this.requestStatusFilter();
    this.transfusion.listRequests(filter || undefined).subscribe({
      next: (list) => {
        this.requests.set(list ?? []);
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
      next: (full) => this.selectedRequest.set(full),
      error: () => this.toast.error(this.translate.instant('TRANSFUSION.LOAD_ERROR')),
    });
  }

  closeRequest(): void {
    this.selectedRequest.set(null);
  }

  openNewRequest(): void {
    this.requestForm = this.emptyRequestForm();
    this.selectedPatient.set(null);
    this.patientGroup.set(null);
    this.showRequestModal.set(true);
  }

  closeNewRequest(): void {
    this.showRequestModal.set(false);
  }

  onPatientPicked(patient: PatientResponse | null): void {
    this.selectedPatient.set(patient);
    this.requestForm.patientId = patient?.id ?? '';
    this.patientGroup.set(null);
    if (!patient) return;
    // Surface the standing type and screen before the clinician commits, so a
    // missing or lapsed one is visible here rather than as a 400 on submit.
    this.transfusion.getCurrentBloodGroup(patient.id).subscribe({
      next: (group) => this.patientGroup.set(group),
      error: () => this.patientGroup.set(null),
    });
  }

  submitRequest(): void {
    if (!this.requestForm.patientId) return;
    this.requestSubmitting.set(true);
    this.transfusion
      .createRequest({
        patientId: this.requestForm.patientId,
        productType: this.requestForm.productType,
        unitsRequested: this.requestForm.unitsRequested,
        indication: this.requestForm.indication.trim(),
        urgency: this.requestForm.urgency,
        notes: this.requestForm.notes.trim() || undefined,
      })
      .subscribe({
        next: (created) => {
          this.toast.success(this.translate.instant('TRANSFUSION.REQUEST_CREATED'));
          this.requestSubmitting.set(false);
          this.showRequestModal.set(false);
          this.selectedRequest.set(created);
          this.loadRequests();
        },
        error: () => {
          this.toast.error(this.translate.instant('TRANSFUSION.REQUEST_ERROR'));
          this.requestSubmitting.set(false);
        },
      });
  }

  cancelRequest(request: TransfusionRequestResponse): void {
    const reason = window.prompt(this.translate.instant('TRANSFUSION.CANCEL_PROMPT'));
    if (!reason || !reason.trim()) return;
    this.transfusion.cancelRequest(request.id, reason.trim()).subscribe({
      next: (updated) => {
        this.toast.success(this.translate.instant('TRANSFUSION.REQUEST_CANCELLED'));
        this.selectedRequest.set(updated);
        this.loadRequests();
      },
      error: () => this.toast.error(this.translate.instant('TRANSFUSION.CANCEL_ERROR')),
    });
  }

  /* ── Type and screen ── */

  openGroupModal(): void {
    const patient = this.selectedPatient();
    this.groupForm = this.emptyGroupForm();
    this.groupForm.patientId = patient?.id ?? this.selectedRequest()?.patientId ?? '';
    this.showGroupModal.set(true);
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
        },
        error: () => {
          // The backend refuses a silent group change without a correction
          // reason; the message is the useful part, so surface a generic
          // failure and let the operator re-read the form hint.
          this.toast.error(this.translate.instant('TRANSFUSION.GROUP_ERROR'));
          this.groupSubmitting.set(false);
        },
      });
  }

  /* ── Units ── */

  loadUnits(): void {
    this.loading.set(true);
    const filter = this.unitStatusFilter();
    this.transfusion.listUnits(filter || undefined).subscribe({
      next: (list) => {
        this.units.set(list ?? []);
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

  openUnitModal(): void {
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
        error: () => {
          this.toast.error(this.translate.instant('TRANSFUSION.UNIT_ERROR'));
          this.unitSubmitting.set(false);
        },
      });
  }

  discardUnit(unit: BloodUnitResponse): void {
    const reason = window.prompt(this.translate.instant('TRANSFUSION.DISCARD_PROMPT'));
    if (!reason || !reason.trim()) return;
    this.transfusion.discardUnit(unit.id, reason.trim()).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('TRANSFUSION.UNIT_DISCARDED'));
        this.loadUnits();
      },
      error: () => this.toast.error(this.translate.instant('TRANSFUSION.DISCARD_ERROR')),
    });
  }

  /* ── Crossmatch and issue ── */

  openCrossmatch(): void {
    this.crossmatchForm = {
      bloodUnitId: '',
      compatible: true,
      method: '',
      incompatibilityReason: '',
    };
    this.transfusion.listAssignableUnits().subscribe({
      next: (list) => this.assignableUnits.set(list ?? []),
      error: () => this.assignableUnits.set([]),
    });
    this.showCrossmatchModal.set(true);
  }

  closeCrossmatch(): void {
    this.showCrossmatchModal.set(false);
  }

  submitCrossmatch(): void {
    const request = this.selectedRequest();
    if (!request || !this.crossmatchForm.bloodUnitId) return;
    this.crossmatchSubmitting.set(true);
    this.transfusion
      .recordCrossmatch(request.id, {
        bloodUnitId: this.crossmatchForm.bloodUnitId,
        compatible: this.crossmatchForm.compatible,
        method: this.crossmatchForm.method.trim() || undefined,
        incompatibilityReason: this.crossmatchForm.incompatibilityReason.trim() || undefined,
      })
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('TRANSFUSION.CROSSMATCH_RECORDED'));
          this.crossmatchSubmitting.set(false);
          this.showCrossmatchModal.set(false);
          this.refreshSelected();
        },
        error: () => {
          // The most important failure here is the ABO/Rh refusal. It carries
          // the groups involved, so the operator is told what to look at.
          this.toast.error(this.translate.instant('TRANSFUSION.CROSSMATCH_ERROR'));
          this.crossmatchSubmitting.set(false);
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
      error: () => this.toast.error(this.translate.instant('TRANSFUSION.ISSUE_ERROR')),
    });
  }

  /* ── Bedside ── */

  openHang(unit: BloodUnitResponse): void {
    this.hangForm = {
      bloodUnitId: unit.id,
      verifiedByStaffId: '',
      verificationMethod: 'Wristband scan',
    };
    this.showHangModal.set(true);
  }

  closeHang(): void {
    this.showHangModal.set(false);
  }

  submitHang(): void {
    const request = this.selectedRequest();
    if (!request || !this.hangForm.verifiedByStaffId.trim()) return;
    this.hangSubmitting.set(true);
    this.transfusion
      .startAdministration({
        requestId: request.id,
        bloodUnitId: this.hangForm.bloodUnitId,
        verifiedByStaffId: this.hangForm.verifiedByStaffId.trim(),
        verificationMethod: this.hangForm.verificationMethod.trim() || undefined,
      })
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('TRANSFUSION.HUNG'));
          this.hangSubmitting.set(false);
          this.showHangModal.set(false);
          this.refreshSelected();
        },
        error: () => {
          this.toast.error(this.translate.instant('TRANSFUSION.HANG_ERROR'));
          this.hangSubmitting.set(false);
        },
      });
  }

  openReaction(administrationId: string): void {
    this.reactionForm = this.emptyReactionForm();
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
        onsetAt: this.reactionForm.onsetAt,
        signsSymptoms: this.reactionForm.signsSymptoms.trim(),
        actionsTaken: this.reactionForm.actionsTaken.trim() || undefined,
        unitReturnedToLab: this.reactionForm.unitReturnedToLab,
      })
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('TRANSFUSION.REACTION_RECORDED'));
          this.reactionSubmitting.set(false);
          this.closeReaction();
          this.refreshSelected();
        },
        error: () => {
          this.toast.error(this.translate.instant('TRANSFUSION.REACTION_ERROR'));
          this.reactionSubmitting.set(false);
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
    (this.selectedRequest()?.units ?? []).filter((u) => u.status === 'ISSUED'),
  );

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

  private refreshSelected(): void {
    const current = this.selectedRequest();
    if (!current) return;
    this.transfusion.getRequest(current.id).subscribe({
      next: (full) => {
        this.selectedRequest.set(full);
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
