import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/* ── Approvals ─────────────────────────────────────────────── */

export type DischargeStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED';

export interface DischargeApprovalRequest {
  registrationId: string;
  nurseStaffId: string;
  nurseAssignmentId: string;
  nurseSummary?: string;
}

export interface DischargeApprovalDecision {
  doctorStaffId: string;
  doctorAssignmentId: string;
  doctorNote?: string;
  rejectionReason?: string;
}

export interface DischargeApproval {
  id: string;
  status: DischargeStatus;
  patientId: string;
  patientName: string;
  registrationId: string;
  hospitalId: string;
  hospitalName: string;
  nurseStaffId: string;
  nurseName: string;
  nurseAssignmentId: string;
  doctorStaffId?: string;
  doctorName?: string;
  doctorAssignmentId?: string;
  nurseSummary?: string;
  doctorNote?: string;
  rejectionReason?: string;
  requestedAt: string;
  approvedAt?: string;
  resolvedAt?: string;
  currentStayStatus?: string;
  stayStatusUpdatedAt?: string;
}

/* ── Summaries ─────────────────────────────────────────────── */

export type DischargeDisposition =
  | 'HOME'
  | 'HOME_WITH_HOME_HEALTH'
  | 'SKILLED_NURSING_FACILITY'
  | 'LONG_TERM_CARE_FACILITY'
  | 'REHABILITATION_FACILITY'
  | 'HOSPICE_HOME'
  | 'HOSPICE_FACILITY'
  | 'PSYCHIATRIC_FACILITY'
  | 'AGAINST_MEDICAL_ADVICE'
  | 'LEFT_WITHOUT_BEING_SEEN'
  | 'TRANSFERRED_TO_ANOTHER_HOSPITAL'
  | 'EXPIRED'
  | 'OTHER';

export type MedicationReconciliationAction =
  | 'CONTINUED'
  | 'DISCONTINUED'
  | 'NEW_STARTED'
  | 'MODIFIED'
  | 'RESUMED'
  | 'REPLACED'
  | 'TEMPORARY_ONLY';

export interface MedicationReconciliationItem {
  medicationName: string;
  dosage?: string;
  route?: string;
  frequency?: string;
  reconciliationAction: MedicationReconciliationAction;
  continueAtDischarge?: boolean;
  patientInstructions?: string;
}

export interface PendingTestResultItem {
  testType: string;
  testName: string;
  expectedResultDate?: string;
  followUpProvider?: string;
  isCritical?: boolean;
}

export interface FollowUpAppointmentItem {
  appointmentType: string;
  providerName?: string;
  specialty?: string;
  appointmentDate?: string;
  location?: string;
  purpose?: string;
}

export interface DischargeSummaryRequest {
  patientId: string;
  encounterId: string;
  hospitalId: string;
  dischargingProviderId: string;
  assignmentId: string;
  approvalRecordId?: string;
  dischargeDate: string;
  dischargeTime?: string;
  disposition: DischargeDisposition;
  dischargeDiagnosis: string;
  hospitalCourse?: string;
  dischargeCondition?: string;
  activityRestrictions?: string;
  dietInstructions?: string;
  woundCareInstructions?: string;
  followUpInstructions?: string;
  warningSigns?: string;
  patientEducationProvided?: string;
  medicationReconciliation?: MedicationReconciliationItem[];
  pendingTestResults?: PendingTestResultItem[];
  followUpAppointments?: FollowUpAppointmentItem[];
  additionalNotes?: string;
}

export interface DischargeSummary {
  id: string;
  patientId: string;
  patientName: string;
  patientMrn?: string;
  encounterId: string;
  encounterType?: string;
  hospitalId: string;
  hospitalName: string;
  dischargingProviderId: string;
  dischargingProviderName: string;
  assignmentId?: string;
  approvalRecordId?: string;
  dischargeDate: string;
  dischargeTime?: string;
  disposition: DischargeDisposition;
  dischargeDiagnosis: string;
  hospitalCourse?: string;
  dischargeCondition?: string;
  activityRestrictions?: string;
  dietInstructions?: string;
  woundCareInstructions?: string;
  followUpInstructions?: string;
  warningSigns?: string;
  patientEducationProvided?: string;
  medicationReconciliation?: MedicationReconciliationItem[];
  pendingTestResults?: PendingTestResultItem[];
  followUpAppointments?: FollowUpAppointmentItem[];
  providerSignature?: string;
  providerSignatureDateTime?: string;
  isFinalized: boolean;
  finalizedAt?: string;
  additionalNotes?: string;
  createdAt?: string;
  updatedAt?: string;
}

/** Minimal shape of GET /registrations page items — used to resolve the
 *  registrationId that a discharge-approval request must reference. */
export interface PatientRegistration {
  id: string;
  patientId?: string;
  hospitalId?: string;
  active?: boolean;
}

@Injectable({ providedIn: 'root' })
export class DischargeService {
  private readonly http = inject(HttpClient);
  private readonly approvalsUrl = '/discharge-approvals';
  private readonly summariesUrl = '/discharge-summaries';

  /* ── Approvals ── */

  requestApproval(req: DischargeApprovalRequest): Observable<DischargeApproval> {
    return this.http.post<DischargeApproval>(this.approvalsUrl, req);
  }

  approve(id: string, decision: DischargeApprovalDecision): Observable<DischargeApproval> {
    return this.http.post<DischargeApproval>(`${this.approvalsUrl}/${id}/approve`, decision);
  }

  reject(id: string, decision: DischargeApprovalDecision): Observable<DischargeApproval> {
    return this.http.post<DischargeApproval>(`${this.approvalsUrl}/${id}/reject`, decision);
  }

  cancel(id: string, staffId: string, reason?: string): Observable<DischargeApproval> {
    let params = new HttpParams().set('staffId', staffId);
    if (reason) params = params.set('reason', reason);
    return this.http.post<DischargeApproval>(`${this.approvalsUrl}/${id}/cancel`, null, {
      params,
    });
  }

  approvalsByHospital(
    hospitalId: string,
    status?: DischargeStatus,
  ): Observable<DischargeApproval[]> {
    let params = new HttpParams();
    if (status) params = params.set('status', status);
    return this.http.get<DischargeApproval[]>(`${this.approvalsUrl}/hospital/${hospitalId}`, {
      params,
    });
  }

  pendingApprovals(hospitalId: string): Observable<DischargeApproval[]> {
    return this.http.get<DischargeApproval[]>(
      `${this.approvalsUrl}/hospital/${hospitalId}/pending`,
    );
  }

  approvalsByPatient(patientId: string): Observable<DischargeApproval[]> {
    return this.http.get<DischargeApproval[]>(`${this.approvalsUrl}/patient/${patientId}`);
  }

  /**
   * Resolve the active hospital registration an approval request must
   * reference. The backend returns a BARE array (no Page envelope) and, when
   * patientId is present, filters by patient only — active=true narrows it.
   */
  findActiveRegistration(patientId: string, hospitalId: string): Observable<PatientRegistration[]> {
    const params = new HttpParams()
      .set('patientId', patientId)
      .set('hospitalId', hospitalId)
      .set('active', 'true')
      .set('size', '1');
    return this.http.get<PatientRegistration[]>('/registrations', { params });
  }

  /* ── Summaries ── */

  createSummary(req: DischargeSummaryRequest): Observable<DischargeSummary> {
    return this.http.post<DischargeSummary>(this.summariesUrl, req);
  }

  updateSummary(id: string, req: DischargeSummaryRequest): Observable<DischargeSummary> {
    return this.http.put<DischargeSummary>(`${this.summariesUrl}/${id}`, req);
  }

  finalizeSummary(
    id: string,
    providerSignature: string,
    providerId: string,
  ): Observable<DischargeSummary> {
    const params = new HttpParams()
      .set('providerSignature', providerSignature)
      .set('providerId', providerId);
    return this.http.post<DischargeSummary>(`${this.summariesUrl}/${id}/finalize`, null, {
      params,
    });
  }

  getSummary(id: string): Observable<DischargeSummary> {
    return this.http.get<DischargeSummary>(`${this.summariesUrl}/${id}`);
  }

  summariesByHospital(
    hospitalId: string,
    startDate: string,
    endDate: string,
  ): Observable<DischargeSummary[]> {
    const params = new HttpParams().set('startDate', startDate).set('endDate', endDate);
    return this.http.get<DischargeSummary[]>(`${this.summariesUrl}/hospital/${hospitalId}`, {
      params,
    });
  }

  unfinalizedSummaries(hospitalId: string): Observable<DischargeSummary[]> {
    return this.http.get<DischargeSummary[]>(
      `${this.summariesUrl}/hospital/${hospitalId}/unfinalized`,
    );
  }

  summariesWithPendingResults(hospitalId: string): Observable<DischargeSummary[]> {
    return this.http.get<DischargeSummary[]>(
      `${this.summariesUrl}/hospital/${hospitalId}/pending-results`,
    );
  }

  deleteSummary(id: string, deletedByProviderId: string): Observable<void> {
    const params = new HttpParams().set('deletedByProviderId', deletedByProviderId);
    return this.http.delete<void>(`${this.summariesUrl}/${id}`, { params });
  }
}
