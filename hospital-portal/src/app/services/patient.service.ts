import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface PatientResponse {
  id: string;
  firstName: string;
  lastName: string;
  middleName?: string;
  dateOfBirth?: string;
  gender?: string;
  address?: string;
  addressLine1?: string;
  addressLine2?: string;
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
  bloodType?: string;
  allergies?: string;
  medicalHistorySummary?: string;
  preferredPharmacy?: string;
  careTeamNotes?: string;
  chronicConditions?: string[];
  mrn?: string;
  displayName?: string;
  username?: string;
  hospitalId?: string;
  hospitalName?: string;
  departmentId?: string;
  departmentName?: string;
  organizationId?: string;
  active: boolean;
  createdAt?: string;
  updatedAt?: string;
  /** Populated by doctor-specific endpoints */
  lastEncounterDate?: string;
  lastLocation?: string;
}

export interface PatientCreateRequest {
  userId: string;
  hospitalId: string;
  firstName: string;
  lastName: string;
  middleName?: string;
  dateOfBirth?: string;
  gender?: string;
  address?: string;
  addressLine1?: string;
  addressLine2?: string;
  city?: string;
  state?: string;
  zipCode?: string;
  country?: string;
  phoneNumberPrimary: string;
  phoneNumberSecondary?: string;
  /** Optional — phone-first: most patients register with a phone number only. */
  email?: string;
  /** Id of a confirmed SMS OTP challenge for phoneNumberPrimary (stamps phoneVerifiedAt). */
  phoneVerificationId?: string;
  emergencyContactName?: string;
  emergencyContactPhone?: string;
  emergencyContactRelationship?: string;
  bloodType?: string;
  allergies?: string;
  medicalHistorySummary?: string;
  preferredPharmacy?: string;
  careTeamNotes?: string;
  chronicConditions?: string[];
  organizationId?: string;
  departmentId?: string;
  isActive?: boolean;
}

/* ── Structured chart data ─────────────────────────────────── */

export type AllergySeverity = 'MILD' | 'MODERATE' | 'SEVERE' | 'LIFE_THREATENING' | 'UNKNOWN';
export type AllergyVerificationStatus =
  | 'UNCONFIRMED'
  | 'PROVISIONAL'
  | 'CONFIRMED'
  | 'REFUTED'
  | 'ENTERED_IN_ERROR';

export interface PatientAllergy {
  id: string;
  patientId: string;
  hospitalId?: string;
  hospitalName?: string;
  allergenDisplay: string;
  allergenCode?: string;
  category?: string;
  severity?: AllergySeverity;
  verificationStatus?: AllergyVerificationStatus;
  reaction?: string;
  reactionNotes?: string;
  onsetDate?: string;
  lastOccurrenceDate?: string;
  recordedDate?: string;
  active?: boolean;
  recordedBy?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface PatientAllergyRequest {
  hospitalId?: string;
  allergenDisplay: string;
  allergenCode?: string;
  category?: string;
  severity?: AllergySeverity;
  verificationStatus?: AllergyVerificationStatus;
  reaction?: string;
  reactionNotes?: string;
  onsetDate?: string;
  active?: boolean;
}

export type ProblemStatus = 'ACTIVE' | 'RESOLVED' | 'INACTIVE' | 'RECURRENCE';
export type ProblemSeverity = 'UNKNOWN' | 'MILD' | 'MODERATE' | 'SEVERE' | 'LIFE_THREATENING';

export interface PatientProblem {
  id: string;
  patientId: string;
  hospitalId?: string;
  hospitalName?: string;
  problemCode?: string;
  problemDisplay: string;
  icdVersion?: string;
  status?: ProblemStatus;
  severity?: ProblemSeverity;
  onsetDate?: string;
  resolvedDate?: string;
  recordedBy?: string;
  notes?: string;
  supportingEvidence?: string;
  chronic?: boolean;
  diagnosisCodes?: string[];
}

export interface PatientDiagnosisRequest {
  hospitalId: string;
  problemDisplay: string;
  problemCode?: string;
  icdVersion?: string;
  status?: ProblemStatus;
  severity?: ProblemSeverity;
  onsetDate?: string;
  notes?: string;
  chronic?: boolean;
  changeReason?: string;
  resolvedDate?: string;
}

export type ChartSectionType =
  | 'DIAGNOSIS'
  | 'PROBLEM'
  | 'ALLERGY'
  | 'MEDICAL_HISTORY'
  | 'SURGICAL_HISTORY'
  | 'SOCIAL_HISTORY'
  | 'FAMILY_HISTORY'
  | 'HOSPITALIZATION'
  | 'IMMUNIZATION'
  | 'CARE_PLAN'
  | 'MEDICATION'
  | 'NOTE'
  | 'OTHER';

export interface ChartUpdateSection {
  sectionType: ChartSectionType;
  display?: string;
  narrative?: string;
  status?: string;
  severity?: string;
  occurredOn?: string;
}

export interface ChartUpdate {
  id: string;
  patientId: string;
  versionNumber: number;
  updateReason: string;
  summary?: string;
  sectionCount?: number;
  attachmentCount?: number;
  recordedAt: string;
  recordedByName?: string;
  recordedByRole?: string;
  sections?: (ChartUpdateSection & { id: string })[];
}

export interface ChartUpdateRequest {
  hospitalId: string;
  updateReason: string;
  summary?: string;
  notifyCareTeam?: boolean;
  sections?: ChartUpdateSection[];
}

export interface TimelineEntry {
  entryId: string;
  category: string;
  occurredAt: string;
  summary: string;
  sensitive: boolean;
}

export interface PatientTimeline {
  patientId: string;
  patientName: string;
  accessReason: string;
  entries: TimelineEntry[];
  totalEntries: number;
  generatedAt: string;
}

/** SMS OTP challenge issued by POST /patients/phone-verification. */
export interface PhoneVerificationChallenge {
  challengeId: string;
  maskedPhone: string | null;
  expiresAt: string;
  verified: boolean;
}

/** Masked cross-hospital match returned by GET /patients/registration-match. */
export interface RegistrationMatch {
  patientId: string;
  fullName: string | null;
  birthYear: number | null;
  gender: string | null;
  maskedPhone: string | null;
  maskedEmail: string | null;
  hospitalCount: number;
  alreadyRegisteredHere: boolean;
  matchedOn: 'PHONE' | 'EMAIL';
}

@Injectable({ providedIn: 'root' })
export class PatientService {
  private readonly http = inject(HttpClient);

  list(hospitalId?: string, search?: string): Observable<PatientResponse[]> {
    let params = new HttpParams();
    if (hospitalId) params = params.set('hospitalId', hospitalId);
    if (search) params = params.set('search', search);
    return this.http.get<PatientResponse[]>('/patients', { params });
  }

  getById(id: string, hospitalId?: string): Observable<PatientResponse> {
    let params = new HttpParams();
    if (hospitalId) params = params.set('hospitalId', hospitalId);
    return this.http.get<PatientResponse>(`/patients/${id}`, { params });
  }

  create(req: PatientCreateRequest): Observable<PatientResponse> {
    return this.http.post<PatientResponse>('/patients', req);
  }

  update(id: string, req: Partial<PatientCreateRequest>): Observable<PatientResponse> {
    return this.http.put<PatientResponse>(`/patients/${id}`, req);
  }

  lookup(params: {
    identifier?: string;
    email?: string;
    phone?: string;
    mrn?: string;
    hospitalId?: string;
  }): Observable<PatientResponse[]> {
    let httpParams = new HttpParams();
    if (params.identifier) httpParams = httpParams.set('identifier', params.identifier);
    if (params.email) httpParams = httpParams.set('email', params.email);
    if (params.phone) httpParams = httpParams.set('phone', params.phone);
    if (params.mrn) httpParams = httpParams.set('mrn', params.mrn);
    if (params.hospitalId) httpParams = httpParams.set('hospitalId', params.hospitalId);
    return this.http.get<PatientResponse[]>('/patients/lookup', { params: httpParams });
  }

  /**
   * Exact email/phone match across ALL hospitals for the registration form.
   * Returns a masked, privacy-minimal projection — link via POST /registrations.
   */
  registrationMatch(params: {
    email?: string;
    phone?: string;
    hospitalId?: string;
  }): Observable<RegistrationMatch[]> {
    let httpParams = new HttpParams();
    if (params.email) httpParams = httpParams.set('email', params.email);
    if (params.phone) httpParams = httpParams.set('phone', params.phone);
    if (params.hospitalId) httpParams = httpParams.set('hospitalId', params.hospitalId);
    return this.http.get<RegistrationMatch[]>('/patients/registration-match', {
      params: httpParams,
    });
  }

  /* ── SMS phone verification (IKODDI OTP) ── */

  phoneVerificationAvailability(): Observable<{ available: boolean }> {
    return this.http.get<{ available: boolean }>('/patients/phone-verification/availability');
  }

  requestPhoneVerification(phoneNumber: string): Observable<PhoneVerificationChallenge> {
    return this.http.post<PhoneVerificationChallenge>('/patients/phone-verification', {
      phoneNumber,
    });
  }

  confirmPhoneVerification(
    challengeId: string,
    code: string,
  ): Observable<PhoneVerificationChallenge> {
    return this.http.post<PhoneVerificationChallenge>('/patients/phone-verification/confirm', {
      challengeId,
      code,
    });
  }

  /** Hospital-scoped free search (GET /patients/search) — name/MRN/phone/email patterns. */
  search(params: {
    name?: string;
    mrn?: string;
    phone?: string;
    email?: string;
    hospitalId?: string;
    size?: number;
  }): Observable<PatientResponse[]> {
    let httpParams = new HttpParams();
    if (params.name) httpParams = httpParams.set('name', params.name);
    if (params.mrn) httpParams = httpParams.set('mrn', params.mrn);
    if (params.phone) httpParams = httpParams.set('phone', params.phone);
    if (params.email) httpParams = httpParams.set('email', params.email);
    if (params.hospitalId) httpParams = httpParams.set('hospitalId', params.hospitalId);
    if (params.size) httpParams = httpParams.set('size', params.size);
    return this.http.get<PatientResponse[]>('/patients/search', { params: httpParams });
  }

  /* ── Allergies ── */

  listAllergies(patientId: string, hospitalId?: string): Observable<PatientAllergy[]> {
    let params = new HttpParams();
    if (hospitalId) params = params.set('hospitalId', hospitalId);
    return this.http.get<PatientAllergy[]>(`/patients/${patientId}/allergies`, { params });
  }

  addAllergy(patientId: string, req: PatientAllergyRequest): Observable<PatientAllergy> {
    return this.http.post<PatientAllergy>(`/patients/${patientId}/allergies`, req);
  }

  updateAllergy(
    patientId: string,
    allergyId: string,
    req: PatientAllergyRequest,
  ): Observable<PatientAllergy> {
    return this.http.put<PatientAllergy>(`/patients/${patientId}/allergies/${allergyId}`, req);
  }

  deactivateAllergy(
    patientId: string,
    allergyId: string,
    reason: string,
  ): Observable<{ message: string }> {
    return this.http.delete<{ message: string }>(`/patients/${patientId}/allergies/${allergyId}`, {
      body: { reason },
    });
  }

  /* ── Diagnoses / problems ── */

  listDiagnoses(
    patientId: string,
    options?: { hospitalId?: string; includeHistorical?: boolean },
  ): Observable<PatientProblem[]> {
    let params = new HttpParams();
    if (options?.hospitalId) params = params.set('hospitalId', options.hospitalId);
    if (options?.includeHistorical) params = params.set('includeHistorical', 'true');
    return this.http.get<PatientProblem[]>(`/patients/${patientId}/diagnoses`, { params });
  }

  addDiagnosis(patientId: string, req: PatientDiagnosisRequest): Observable<PatientProblem> {
    return this.http.post<PatientProblem>(`/patients/${patientId}/diagnoses`, req);
  }

  updateDiagnosis(
    patientId: string,
    diagnosisId: string,
    req: Partial<PatientDiagnosisRequest>,
  ): Observable<PatientProblem> {
    return this.http.put<PatientProblem>(`/patients/${patientId}/diagnoses/${diagnosisId}`, req);
  }

  deleteDiagnosis(
    patientId: string,
    diagnosisId: string,
    reason: string,
  ): Observable<{ message: string }> {
    return this.http.delete<{ message: string }>(
      `/patients/${patientId}/diagnoses/${diagnosisId}`,
      { body: { reason } },
    );
  }

  /* ── Chart updates ── */

  listChartUpdates(
    patientId: string,
    options?: { hospitalId?: string; page?: number; size?: number },
  ): Observable<{ content: ChartUpdate[]; totalElements: number }> {
    let params = new HttpParams();
    if (options?.hospitalId) params = params.set('hospitalId', options.hospitalId);
    if (options?.page !== undefined) params = params.set('page', String(options.page));
    if (options?.size !== undefined) params = params.set('size', String(options.size));
    return this.http.get<{ content: ChartUpdate[]; totalElements: number }>(
      `/patients/${patientId}/chart-updates`,
      { params },
    );
  }

  createChartUpdate(patientId: string, req: ChartUpdateRequest): Observable<ChartUpdate> {
    return this.http.post<ChartUpdate>(`/patients/${patientId}/chart-updates`, req);
  }

  /* ── Doctor timeline (audited access) ── */

  getDoctorTimeline(
    patientId: string,
    accessReason: string,
    maxEvents = 50,
  ): Observable<PatientTimeline> {
    return this.http.post<PatientTimeline>(`/patients/${patientId}/doctor-timeline`, {
      accessReason,
      maxEvents,
    });
  }
}
