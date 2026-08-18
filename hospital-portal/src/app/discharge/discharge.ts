import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, switchMap } from 'rxjs/operators';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  DischargeService,
  DischargeApproval,
  DischargeStatus,
  DischargeSummary,
  DischargeSummaryRequest,
  DischargeDisposition,
  MedicationReconciliationAction,
} from '../services/discharge.service';
import { EncounterService, EncounterResponse } from '../services/encounter.service';
import { PatientService, PatientResponse } from '../services/patient.service';
import { ProfileService } from '../services/profile.service';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';

type ApprovalFilter = DischargeStatus | 'ALL';
type SummaryFilter = 'unfinalized' | 'pending-results' | 'range';

@Component({
  selector: 'app-discharge',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './discharge.html',
  styleUrl: './discharge.scss',
})
export class DischargeComponent implements OnInit {
  private readonly dischargeService = inject(DischargeService);
  private readonly encounterService = inject(EncounterService);
  private readonly patientService = inject(PatientService);
  private readonly profileService = inject(ProfileService);
  private readonly auth = inject(AuthService);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  /* ── Context ── */
  private hospitalId: string | null = null;
  private staffId: string | null = null;
  private assignmentId: string | null = null;

  readonly canDecide = this.hasAnyRole(['ROLE_DOCTOR', 'ROLE_HOSPITAL_ADMIN', 'ROLE_SUPER_ADMIN']);
  readonly canRequest = this.hasAnyRole([
    'ROLE_NURSE',
    'ROLE_MIDWIFE',
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_SUPER_ADMIN',
  ]);
  readonly canEditSummaries = this.hasAnyRole([
    'ROLE_DOCTOR',
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_SUPER_ADMIN',
  ]);

  activeTab = signal<'approvals' | 'summaries'>('approvals');

  /* ── Approvals state ── */
  approvals = signal<DischargeApproval[]>([]);
  approvalsLoading = signal(false);
  approvalFilter = signal<ApprovalFilter>('PENDING');
  readonly approvalFilters: ApprovalFilter[] = [
    'PENDING',
    'APPROVED',
    'REJECTED',
    'CANCELLED',
    'ALL',
  ];

  showDecisionModal = signal(false);
  decisionMode = signal<'approve' | 'reject'>('approve');
  decisionTarget = signal<DischargeApproval | null>(null);
  decisionNote = '';
  decisionSubmitting = signal(false);

  showCancelModal = signal(false);
  cancelTarget = signal<DischargeApproval | null>(null);
  cancelReason = '';
  cancelSubmitting = signal(false);

  showRequestModal = signal(false);
  requestSummaryText = '';
  requestSubmitting = signal(false);

  /* ── Summaries state ── */
  summaries = signal<DischargeSummary[]>([]);
  summariesLoading = signal(false);
  summaryFilter = signal<SummaryFilter>('unfinalized');
  rangeStart = this.isoDaysAgo(30);
  rangeEnd = this.isoDaysAgo(0);

  showSummaryModal = signal(false);
  editingSummaryId = signal<string | null>(null);
  summarySaving = signal(false);
  summaryForm: DischargeSummaryRequest = this.emptySummaryForm();
  patientEncounters = signal<EncounterResponse[]>([]);

  showViewModal = signal(false);
  viewedSummary = signal<DischargeSummary | null>(null);

  showFinalizeModal = signal(false);
  finalizeTarget = signal<DischargeSummary | null>(null);
  finalizeSignature = '';
  finalizeSubmitting = signal(false);

  showDeleteModal = signal(false);
  deleteTarget = signal<DischargeSummary | null>(null);
  deleteSubmitting = signal(false);

  /* ── Patient picker (shared by request + summary modals) ── */
  patientQuery = signal('');
  patientSuggestions = signal<PatientResponse[]>([]);
  patientDropdownOpen = signal(false);
  selectedPatient = signal<PatientResponse | null>(null);
  private readonly patientSearch$ = new Subject<string>();

  readonly dispositions: DischargeDisposition[] = [
    'HOME',
    'HOME_WITH_HOME_HEALTH',
    'SKILLED_NURSING_FACILITY',
    'LONG_TERM_CARE_FACILITY',
    'REHABILITATION_FACILITY',
    'HOSPICE_HOME',
    'HOSPICE_FACILITY',
    'PSYCHIATRIC_FACILITY',
    'AGAINST_MEDICAL_ADVICE',
    'LEFT_WITHOUT_BEING_SEEN',
    'TRANSFERRED_TO_ANOTHER_HOSPITAL',
    'EXPIRED',
    'OTHER',
  ];

  readonly medActions: MedicationReconciliationAction[] = [
    'CONTINUED',
    'DISCONTINUED',
    'NEW_STARTED',
    'MODIFIED',
    'RESUMED',
    'REPLACED',
    'TEMPORARY_ONLY',
  ];

  ngOnInit(): void {
    this.hospitalId = this.roleContext.activeHospitalId ?? this.auth.getHospitalId();
    this.staffId = this.auth.getUserProfile()?.staffId ?? null;
    this.resolveAssignment();
    this.initPatientSearch();
    this.loadApprovals();
    if (!this.canDecide && !this.canRequest) {
      this.activeTab.set('summaries');
    }
    this.loadSummaries();
  }

  private hasAnyRole(roles: string[]): boolean {
    const active = this.roleContext.activeRole;
    if (active) return roles.includes(active);
    return this.auth.hasAnyRole(roles);
  }

  private resolveAssignment(): void {
    this.profileService.getAssignments().subscribe({
      next: (assignments) => {
        const match =
          assignments.find((a) => a.hospitalId === this.hospitalId) ?? assignments[0] ?? null;
        this.assignmentId = match?.id ?? null;
      },
      error: () => {
        this.assignmentId = null;
      },
    });
  }

  private isoDaysAgo(days: number): string {
    const d = new Date();
    d.setDate(d.getDate() - days);
    return d.toISOString().substring(0, 10);
  }

  setTab(tab: 'approvals' | 'summaries'): void {
    this.activeTab.set(tab);
  }

  /* ── Approvals ── */

  setApprovalFilter(filter: ApprovalFilter): void {
    this.approvalFilter.set(filter);
    this.loadApprovals();
  }

  loadApprovals(): void {
    if (!this.hospitalId) return;
    this.approvalsLoading.set(true);
    const filter = this.approvalFilter();
    this.dischargeService
      .approvalsByHospital(this.hospitalId, filter === 'ALL' ? undefined : filter)
      .subscribe({
        next: (list) => {
          this.approvals.set(list ?? []);
          this.approvalsLoading.set(false);
        },
        error: () => {
          this.toast.error(this.translate.instant('DISCHARGE.LOAD_APPROVALS_ERROR'));
          this.approvalsLoading.set(false);
        },
      });
  }

  openDecision(approval: DischargeApproval, mode: 'approve' | 'reject'): void {
    if (!this.requireStaffContext()) return;
    this.decisionTarget.set(approval);
    this.decisionMode.set(mode);
    this.decisionNote = '';
    this.showDecisionModal.set(true);
  }

  closeDecision(): void {
    this.showDecisionModal.set(false);
    this.decisionTarget.set(null);
  }

  submitDecision(): void {
    const target = this.decisionTarget();
    if (!target || !this.staffId || !this.assignmentId) return;
    const mode = this.decisionMode();
    if (mode === 'reject' && !this.decisionNote.trim()) {
      this.toast.error(this.translate.instant('DISCHARGE.REJECTION_REASON_REQUIRED'));
      return;
    }
    this.decisionSubmitting.set(true);
    const decision = {
      doctorStaffId: this.staffId,
      doctorAssignmentId: this.assignmentId,
      doctorNote: mode === 'approve' ? this.decisionNote.trim() || undefined : undefined,
      rejectionReason: mode === 'reject' ? this.decisionNote.trim() : undefined,
    };
    const op =
      mode === 'approve'
        ? this.dischargeService.approve(target.id, decision)
        : this.dischargeService.reject(target.id, decision);
    op.subscribe({
      next: () => {
        this.toast.success(
          this.translate.instant(
            mode === 'approve' ? 'DISCHARGE.APPROVED_SUCCESS' : 'DISCHARGE.REJECTED_SUCCESS',
          ),
        );
        this.decisionSubmitting.set(false);
        this.closeDecision();
        this.loadApprovals();
      },
      error: () => {
        this.toast.error(this.translate.instant('DISCHARGE.DECISION_ERROR'));
        this.decisionSubmitting.set(false);
      },
    });
  }

  openCancel(approval: DischargeApproval): void {
    if (!this.requireStaffContext()) return;
    this.cancelTarget.set(approval);
    this.cancelReason = '';
    this.showCancelModal.set(true);
  }

  closeCancel(): void {
    this.showCancelModal.set(false);
    this.cancelTarget.set(null);
  }

  submitCancel(): void {
    const target = this.cancelTarget();
    if (!target || !this.staffId) return;
    this.cancelSubmitting.set(true);
    this.dischargeService
      .cancel(target.id, this.staffId, this.cancelReason.trim() || undefined)
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('DISCHARGE.CANCELLED_SUCCESS'));
          this.cancelSubmitting.set(false);
          this.closeCancel();
          this.loadApprovals();
        },
        error: () => {
          this.toast.error(this.translate.instant('DISCHARGE.CANCEL_ERROR'));
          this.cancelSubmitting.set(false);
        },
      });
  }

  openRequest(): void {
    if (!this.requireStaffContext()) return;
    this.resetPatientPicker();
    this.requestSummaryText = '';
    this.showRequestModal.set(true);
  }

  closeRequest(): void {
    this.showRequestModal.set(false);
  }

  submitRequest(): void {
    const patient = this.selectedPatient();
    if (!patient || !this.hospitalId || !this.staffId || !this.assignmentId) return;
    this.requestSubmitting.set(true);
    this.dischargeService.findActiveRegistration(patient.id, this.hospitalId).subscribe({
      next: (page) => {
        const registration = page?.content?.[0];
        if (!registration) {
          this.toast.error(this.translate.instant('DISCHARGE.NO_REGISTRATION'));
          this.requestSubmitting.set(false);
          return;
        }
        this.dischargeService
          .requestApproval({
            registrationId: registration.id,
            nurseStaffId: this.staffId!,
            nurseAssignmentId: this.assignmentId!,
            nurseSummary: this.requestSummaryText.trim() || undefined,
          })
          .subscribe({
            next: () => {
              this.toast.success(this.translate.instant('DISCHARGE.REQUEST_SUCCESS'));
              this.requestSubmitting.set(false);
              this.closeRequest();
              this.loadApprovals();
            },
            error: () => {
              this.toast.error(this.translate.instant('DISCHARGE.REQUEST_ERROR'));
              this.requestSubmitting.set(false);
            },
          });
      },
      error: () => {
        this.toast.error(this.translate.instant('DISCHARGE.NO_REGISTRATION'));
        this.requestSubmitting.set(false);
      },
    });
  }

  /* ── Summaries ── */

  setSummaryFilter(filter: SummaryFilter): void {
    this.summaryFilter.set(filter);
    this.loadSummaries();
  }

  loadSummaries(): void {
    if (!this.hospitalId) return;
    this.summariesLoading.set(true);
    const filter = this.summaryFilter();
    const source =
      filter === 'unfinalized'
        ? this.dischargeService.unfinalizedSummaries(this.hospitalId)
        : filter === 'pending-results'
          ? this.dischargeService.summariesWithPendingResults(this.hospitalId)
          : this.dischargeService.summariesByHospital(
              this.hospitalId,
              this.rangeStart,
              this.rangeEnd,
            );
    source.subscribe({
      next: (list) => {
        this.summaries.set(list ?? []);
        this.summariesLoading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('DISCHARGE.LOAD_SUMMARIES_ERROR'));
        this.summariesLoading.set(false);
      },
    });
  }

  emptySummaryForm(): DischargeSummaryRequest {
    return {
      patientId: '',
      encounterId: '',
      hospitalId: '',
      dischargingProviderId: '',
      assignmentId: '',
      dischargeDate: this.isoDaysAgo(0),
      disposition: 'HOME',
      dischargeDiagnosis: '',
      medicationReconciliation: [],
      pendingTestResults: [],
      followUpAppointments: [],
    };
  }

  openCreateSummary(): void {
    if (!this.requireStaffContext()) return;
    this.summaryForm = this.emptySummaryForm();
    this.editingSummaryId.set(null);
    this.resetPatientPicker();
    this.patientEncounters.set([]);
    this.showSummaryModal.set(true);
  }

  openEditSummary(summary: DischargeSummary): void {
    if (!this.requireStaffContext()) return;
    this.summaryForm = {
      patientId: summary.patientId,
      encounterId: summary.encounterId,
      hospitalId: summary.hospitalId,
      dischargingProviderId: summary.dischargingProviderId,
      assignmentId: summary.assignmentId ?? this.assignmentId ?? '',
      approvalRecordId: summary.approvalRecordId,
      dischargeDate: summary.dischargeDate,
      dischargeTime: summary.dischargeTime,
      disposition: summary.disposition,
      dischargeDiagnosis: summary.dischargeDiagnosis,
      hospitalCourse: summary.hospitalCourse,
      dischargeCondition: summary.dischargeCondition,
      activityRestrictions: summary.activityRestrictions,
      dietInstructions: summary.dietInstructions,
      woundCareInstructions: summary.woundCareInstructions,
      followUpInstructions: summary.followUpInstructions,
      warningSigns: summary.warningSigns,
      patientEducationProvided: summary.patientEducationProvided,
      medicationReconciliation: (summary.medicationReconciliation ?? []).map((m) => ({ ...m })),
      pendingTestResults: (summary.pendingTestResults ?? []).map((t) => ({ ...t })),
      followUpAppointments: (summary.followUpAppointments ?? []).map((f) => ({ ...f })),
      additionalNotes: summary.additionalNotes,
    };
    this.editingSummaryId.set(summary.id);
    this.selectedPatient.set({
      id: summary.patientId,
      firstName: summary.patientName?.split(' ')[0] ?? '',
      lastName: summary.patientName?.split(' ').slice(1).join(' ') ?? '',
      email: '',
    } as PatientResponse);
    this.loadEncountersFor(summary.patientId);
    this.showSummaryModal.set(true);
  }

  closeSummaryModal(): void {
    this.showSummaryModal.set(false);
  }

  submitSummary(): void {
    if (!this.hospitalId || !this.staffId || !this.assignmentId) return;
    const form = this.summaryForm;
    if (
      !form.patientId ||
      !form.encounterId ||
      !form.dischargeDate ||
      !form.dischargeDiagnosis.trim()
    ) {
      this.toast.error(this.translate.instant('DISCHARGE.SUMMARY_REQUIRED_FIELDS'));
      return;
    }
    form.hospitalId = this.hospitalId;
    if (!this.editingSummaryId()) {
      form.dischargingProviderId = this.staffId;
      form.assignmentId = this.assignmentId;
    }
    this.summarySaving.set(true);
    const op = this.editingSummaryId()
      ? this.dischargeService.updateSummary(this.editingSummaryId()!, form)
      : this.dischargeService.createSummary(form);
    op.subscribe({
      next: () => {
        this.toast.success(
          this.translate.instant(
            this.editingSummaryId() ? 'DISCHARGE.SUMMARY_UPDATED' : 'DISCHARGE.SUMMARY_CREATED',
          ),
        );
        this.summarySaving.set(false);
        this.closeSummaryModal();
        this.loadSummaries();
      },
      error: () => {
        this.toast.error(this.translate.instant('DISCHARGE.SUMMARY_SAVE_ERROR'));
        this.summarySaving.set(false);
      },
    });
  }

  openView(summary: DischargeSummary): void {
    this.viewedSummary.set(summary);
    this.showViewModal.set(true);
  }

  closeView(): void {
    this.showViewModal.set(false);
    this.viewedSummary.set(null);
  }

  openFinalize(summary: DischargeSummary): void {
    if (!this.requireStaffContext()) return;
    this.finalizeTarget.set(summary);
    this.finalizeSignature = '';
    this.showFinalizeModal.set(true);
  }

  closeFinalize(): void {
    this.showFinalizeModal.set(false);
    this.finalizeTarget.set(null);
  }

  submitFinalize(): void {
    const target = this.finalizeTarget();
    if (!target || !this.staffId) return;
    if (!this.finalizeSignature.trim()) {
      this.toast.error(this.translate.instant('DISCHARGE.SIGNATURE_REQUIRED'));
      return;
    }
    this.finalizeSubmitting.set(true);
    this.dischargeService
      .finalizeSummary(target.id, this.finalizeSignature.trim(), this.staffId)
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('DISCHARGE.FINALIZED_SUCCESS'));
          this.finalizeSubmitting.set(false);
          this.closeFinalize();
          this.loadSummaries();
        },
        error: () => {
          this.toast.error(this.translate.instant('DISCHARGE.FINALIZE_ERROR'));
          this.finalizeSubmitting.set(false);
        },
      });
  }

  openDelete(summary: DischargeSummary): void {
    if (!this.requireStaffContext()) return;
    this.deleteTarget.set(summary);
    this.showDeleteModal.set(true);
  }

  closeDelete(): void {
    this.showDeleteModal.set(false);
    this.deleteTarget.set(null);
  }

  submitDelete(): void {
    const target = this.deleteTarget();
    if (!target || !this.staffId) return;
    this.deleteSubmitting.set(true);
    this.dischargeService.deleteSummary(target.id, this.staffId).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('DISCHARGE.SUMMARY_DELETED'));
        this.deleteSubmitting.set(false);
        this.closeDelete();
        this.loadSummaries();
      },
      error: () => {
        this.toast.error(this.translate.instant('DISCHARGE.DELETE_ERROR'));
        this.deleteSubmitting.set(false);
      },
    });
  }

  /* ── Dynamic rows ── */

  addMedication(): void {
    this.summaryForm.medicationReconciliation!.push({
      medicationName: '',
      dosage: '',
      frequency: '',
      reconciliationAction: 'CONTINUED',
      continueAtDischarge: true,
    });
  }

  removeMedication(index: number): void {
    this.summaryForm.medicationReconciliation!.splice(index, 1);
  }

  addPendingTest(): void {
    this.summaryForm.pendingTestResults!.push({ testType: 'LAB', testName: '', isCritical: false });
  }

  removePendingTest(index: number): void {
    this.summaryForm.pendingTestResults!.splice(index, 1);
  }

  addFollowUp(): void {
    this.summaryForm.followUpAppointments!.push({ appointmentType: '', providerName: '' });
  }

  removeFollowUp(index: number): void {
    this.summaryForm.followUpAppointments!.splice(index, 1);
  }

  /* ── Patient picker ── */

  private initPatientSearch(): void {
    this.patientSearch$
      .pipe(
        debounceTime(220),
        distinctUntilChanged(),
        switchMap((q) => this.patientService.list(this.hospitalId ?? undefined, q)),
      )
      .subscribe({
        next: (list) => {
          this.patientSuggestions.set((list ?? []).slice(0, 8));
          this.patientDropdownOpen.set((list ?? []).length > 0);
        },
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
    this.summaryForm.patientId = p.id;
    this.summaryForm.encounterId = '';
    this.patientDropdownOpen.set(false);
    this.patientQuery.set('');
    this.loadEncountersFor(p.id);
  }

  clearPatient(): void {
    this.resetPatientPicker();
    this.summaryForm.patientId = '';
    this.summaryForm.encounterId = '';
    this.patientEncounters.set([]);
  }

  private resetPatientPicker(): void {
    this.selectedPatient.set(null);
    this.patientQuery.set('');
    this.patientSuggestions.set([]);
    this.patientDropdownOpen.set(false);
  }

  private loadEncountersFor(patientId: string): void {
    this.encounterService.list({ patientId }).subscribe({
      next: (list) => this.patientEncounters.set(list ?? []),
      error: () => this.patientEncounters.set([]),
    });
  }

  encounterLabel(e: EncounterResponse): string {
    const date = e.encounterDate ? e.encounterDate.substring(0, 10) : '';
    return `${date} — ${e.encounterType ?? ''} (${e.status ?? ''})`.trim();
  }

  patientInitials(p: PatientResponse): string {
    return ((p.firstName?.[0] ?? '') + (p.lastName?.[0] ?? '')).toUpperCase() || '?';
  }

  /* ── Helpers ── */

  private requireStaffContext(): boolean {
    if (!this.staffId || !this.hospitalId) {
      this.toast.error(this.translate.instant('DISCHARGE.NO_STAFF_CONTEXT'));
      return false;
    }
    return true;
  }

  statusClass(status: DischargeStatus): string {
    switch (status) {
      case 'PENDING':
        return 'status-pending';
      case 'APPROVED':
        return 'status-approved';
      case 'REJECTED':
        return 'status-rejected';
      default:
        return 'status-cancelled';
    }
  }

  countByStatus(status: DischargeStatus): number {
    return this.approvals().filter((a) => a.status === status).length;
  }
}
