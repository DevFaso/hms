import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

// ── Types mirroring backend PatientRecordDTO / RecordShareResultDTO ──────────

export interface SharedEncounterEntry {
  id: string;
  patientId?: string;
  patientName?: string;
  staffId?: string;
  staffName?: string;
  departmentId?: string;
  departmentName?: string;
  hospitalId?: string;
  hospitalName?: string;
  appointmentId?: string;
  encounterType?: string;
  status?: string;
  encounterDate?: string;
  notes?: string;
  arrivalTimestamp?: string;
  chiefComplaint?: string;
  esiScore?: number;
  roomAssignment?: string;
  triageTimestamp?: string;
  checkoutTimestamp?: string;
  followUpInstructions?: string;
  dischargeDiagnoses?: string;
}

export interface SharedEncounterTreatmentEntry {
  id: string;
  encounterId?: string;
  encounterCode?: string;
  encounterType?: string;
  patientId?: string;
  patientFullName?: string;
  treatmentId?: string;
  treatmentName?: string;
  staffId?: string;
  staffFullName?: string;
  performedAt?: string;
  outcome?: string;
  notes?: string;
}

export interface SharedLabOrderEntry {
  id: string;
  labOrderCode?: string;
  patientId?: string;
  patientFullName?: string;
  hospitalName?: string;
  labTestName?: string;
  labTestCode?: string;
  orderDatetime?: string;
  status?: string;
  priority?: string;
  clinicalIndication?: string;
  notes?: string;
}

export interface SharedLabResultEntry {
  id: string;
  labOrderId?: string;
  labOrderCode?: string;
  patientId?: string;
  patientFullName?: string;
  hospitalName?: string;
  labTestName?: string;
  resultValue?: string;
  resultUnit?: string;
  resultDate?: string;
  notes?: string;
  severityFlag?: string;
  acknowledged?: boolean;
  released?: boolean;
}

export interface SharedAllergyEntry {
  id: string;
  patientId?: string;
  hospitalId?: string;
  hospitalName?: string;
  allergenDisplay?: string;
  allergenCode?: string;
  category?: string;
  severity?: string;
  verificationStatus?: string;
  reaction?: string;
  reactionNotes?: string;
  onsetDate?: string;
  active?: boolean;
}

export interface SharedPrescriptionEntry {
  id: string;
  patientId?: string;
  patientFullName?: string;
  staffId?: string;
  staffFullName?: string;
  encounterId?: string;
  hospitalId?: string;
  medicationName?: string;
  medicationDisplayName?: string;
  dosage?: string;
  frequency?: string;
  duration?: string;
  route?: string;
  instructions?: string;
  notes?: string;
  status?: string;
  createdAt?: string;
}

export interface SharedInsuranceEntry {
  id: string;
  patientId?: string;
  hospitalId?: string;
  providerName?: string;
  policyNumber?: string;
  groupNumber?: string;
  subscriberName?: string;
  subscriberRelationship?: string;
  effectiveDate?: string;
  expirationDate?: string;
  primary?: boolean;
}

export interface SharedProblemEntry {
  id: string;
  patientId?: string;
  hospitalId?: string;
  hospitalName?: string;
  problemCode?: string;
  problemDisplay?: string;
  status?: string;
  severity?: string;
  onsetDate?: string;
  resolvedDate?: string;
  notes?: string;
  chronic?: boolean;
}

export interface SharedSurgicalHistoryEntry {
  id: string;
  patientId?: string;
  hospitalId?: string;
  hospitalName?: string;
  procedureCode?: string;
  procedureDisplay?: string;
  procedureDate?: string;
  outcome?: string;
  performedBy?: string;
  location?: string;
  notes?: string;
}

export interface SharedAdvanceDirectiveEntry {
  id: string;
  patientId?: string;
  hospitalId?: string;
  hospitalName?: string;
  directiveType?: string;
  status?: string;
  description?: string;
  effectiveDate?: string;
  expirationDate?: string;
  witnessName?: string;
  physicianName?: string;
}

export interface SharedEncounterHistoryEntry {
  id: string;
  encounterId?: string;
  changedAt?: string;
  changedBy?: string;
  encounterType?: string;
  status?: string;
  encounterDate?: string;
  notes?: string;
  changeType?: string;
}

export interface SharedVitalSignEntry {
  id: string;
  patientId?: string;
  hospitalId?: string;
  recordedByName?: string;
  source?: string;
  temperatureCelsius?: number;
  heartRateBpm?: number;
  respiratoryRateBpm?: number;
  systolicBpMmHg?: number;
  diastolicBpMmHg?: number;
  spo2Percent?: number;
  bloodGlucoseMgDl?: number;
  weightKg?: number;
  bodyPosition?: string;
  notes?: string;
  clinicallySignificant?: boolean;
  recordedAt?: string;
}

export interface SharedImmunizationEntry {
  id: string;
  patientId?: string;
  hospitalId?: string;
  hospitalName?: string;
  administeredByName?: string;
  vaccineCode?: string;
  vaccineDisplay?: string;
  vaccineType?: string;
  targetDisease?: string;
  administrationDate?: string;
  doseNumber?: number;
  totalDosesInSeries?: number;
  doseQuantity?: number;
  doseUnit?: string;
  route?: string;
  site?: string;
  manufacturer?: string;
  lotNumber?: string;
  status?: string;
  statusReason?: string;
  verified?: boolean;
  adverseReaction?: boolean;
  reactionDescription?: string;
  reactionSeverity?: string;
  nextDoseDueDate?: string;
}

export interface PatientRecord {
  patientId: string;
  firstName: string;
  lastName: string;
  middleName?: string;
  dateOfBirth?: string;
  gender?: string;
  bloodType?: string;
  medicalHistorySummary?: string;
  allergies?: string;
  address?: string;
  city?: string;
  state?: string;
  zipCode?: string;
  country?: string;
  phoneNumberPrimary?: string;
  phoneNumberSecondary?: string;
  email?: string;
  emergencyContactName?: string;
  emergencyContactPhone?: string;
  emergencyContactRelationship?: string;
  hospitalMRNs?: string[];
  hospitalMrnMap?: Record<string, string>;
  consentId?: string;
  consentTimestamp?: string;
  consentExpiration?: string;
  consentPurpose?: string;
  fromHospitalId?: string;
  fromHospitalName?: string;
  toHospitalId?: string;
  toHospitalName?: string;
  encounters?: SharedEncounterEntry[];
  treatments?: SharedEncounterTreatmentEntry[];
  labOrders?: SharedLabOrderEntry[];
  labResults?: SharedLabResultEntry[];
  allergiesDetailed?: SharedAllergyEntry[];
  prescriptions?: SharedPrescriptionEntry[];
  insurances?: SharedInsuranceEntry[];
  problems?: SharedProblemEntry[];
  surgicalHistory?: SharedSurgicalHistoryEntry[];
  advanceDirectives?: SharedAdvanceDirectiveEntry[];
  encounterHistory?: SharedEncounterHistoryEntry[];
  vitalSigns?: SharedVitalSignEntry[];
  immunizations?: SharedImmunizationEntry[];
}

export type ShareScope = 'SAME_HOSPITAL' | 'INTRA_ORG' | 'CROSS_ORG';

export interface RecordShareResult {
  // Scope / provenance
  shareScope: ShareScope;
  shareScopeLabel: string;
  resolvedFromHospitalId: string;
  resolvedFromHospitalName: string;
  requestingHospitalId: string;
  requestingHospitalName: string;
  organizationName: string | null;
  organizationId: string | null;
  // Consent metadata
  consentId: string | null;
  consentGrantedAt: string | null;
  consentExpiresAt: string | null;
  consentPurpose: string | null;
  consentActive: boolean;
  // Audit
  resolvedAt: string;
  // Full record
  patientRecord: PatientRecord;
}

export interface ConsentGrantRequest {
  patientId: string;
  fromHospitalId: string;
  toHospitalId: string;
  purpose?: string;
  consentExpiration?: string; // ISO-8601 datetime
  consentType?: 'TREATMENT' | 'RESEARCH' | 'BILLING' | 'EMERGENCY' | 'REFERRAL' | 'ALL_PURPOSES';
  scope?: string; // e.g. 'PRESCRIPTIONS,LAB_RESULTS,ENCOUNTERS'
}

export interface PatientConsentResponse {
  id: string;
  patient: { id: string; firstName: string; lastName: string };
  fromHospital: { id: string; name: string };
  toHospital: { id: string; name: string };
  consentGiven: boolean;
  consentTimestamp: string;
  consentExpiration: string | null;
  purpose: string | null;
  consentType:
    | 'TREATMENT'
    | 'RESEARCH'
    | 'BILLING'
    | 'EMERGENCY'
    | 'REFERRAL'
    | 'ALL_PURPOSES'
    | null;
  scope: string | null;
}

// ── Service ──────────────────────────────────────────────────────────────────

@Injectable({ providedIn: 'root' })
export class RecordSharingService {
  private readonly http = inject(HttpClient);

  /**
   * Smart resolver: automatically picks the fastest consent path
   * (SAME_HOSPITAL → INTRA_ORG → CROSS_ORG) and returns the full
   * patient record with provenance metadata.
   */
  resolveAndShare(patientId: string, requestingHospitalId: string): Observable<RecordShareResult> {
    const params = new HttpParams()
      .set('patientId', patientId)
      .set('requestingHospitalId', requestingHospitalId);
    return this.http.get<RecordShareResult>('/records/resolve', { params });
  }

  /**
   * Classic explicit share: caller provides both source and target hospitals.
   */
  getPatientRecord(
    patientId: string,
    fromHospitalId: string,
    toHospitalId: string,
  ): Observable<PatientRecord> {
    return this.http.post<PatientRecord>('/records/share', {
      patientId,
      fromHospitalId,
      toHospitalId,
    });
  }

  /**
   * Aggregates records from ALL hospitals that have granted consent to the requesting hospital.
   */
  getAggregatedRecord(patientId: string, requestingHospitalId: string): Observable<PatientRecord> {
    const params = new HttpParams()
      .set('patientId', patientId)
      .set('requestingHospitalId', requestingHospitalId);
    return this.http.get<PatientRecord>('/records/aggregate', { params });
  }

  /**
   * Grant a sharing consent from one hospital to another.
   */
  grantConsent(req: ConsentGrantRequest): Observable<PatientConsentResponse> {
    return this.http.post<PatientConsentResponse>('/patient-consents/grant', req);
  }

  /**
   * Revoke an existing consent.
   */
  revokeConsent(patientId: string, fromHospitalId: string, toHospitalId: string): Observable<void> {
    const params = new HttpParams()
      .set('patientId', patientId)
      .set('fromHospitalId', fromHospitalId)
      .set('toHospitalId', toHospitalId);
    return this.http.post<void>('/patient-consents/revoke', null, { params });
  }

  /**
   * Export patient record as PDF or CSV.
   */
  exportRecord(
    patientId: string,
    fromHospitalId: string,
    toHospitalId: string,
    format: 'pdf' | 'csv',
  ): Observable<Blob> {
    const params = new HttpParams()
      .set('patientId', patientId)
      .set('fromHospitalId', fromHospitalId)
      .set('toHospitalId', toHospitalId)
      .set('format', format);
    return this.http.post('/records/export', null, { params, responseType: 'blob' });
  }

  /**
   * List all consents (admin/staff view), paginated.
   */
  listConsents(params?: {
    page?: number;
    size?: number;
  }): Observable<{ content: PatientConsentResponse[]; totalElements: number; totalPages: number }> {
    let httpParams = new HttpParams();
    if (params?.page != null) httpParams = httpParams.set('page', params.page);
    if (params?.size) httpParams = httpParams.set('size', params.size);
    return this.http.get<{
      content: PatientConsentResponse[];
      totalElements: number;
      totalPages: number;
    }>('/patient-consents', { params: httpParams });
  }
}
