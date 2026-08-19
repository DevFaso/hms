import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/*
 * Staff-facing medical-history API — /medical-history (bare DTOs, List only,
 * no pagination). Notable backend behaviors the UI must design around:
 *  - PUT is a FULL REPLACE: omitted fields are nulled. Edit forms must
 *    round-trip the complete object (spread the existing record).
 *  - DELETE is a soft delete but list endpoints DON'T filter `active` —
 *    the client filters `active === false` itself.
 *  - Social-history create supersedes: prior records auto-deactivate and
 *    versionNumber auto-increments.
 *  - GET .../social/current returns 200 with an EMPTY body when none exists.
 *  - The persisted immunization `overdue` flag is stale (only recomputed on
 *    writes) — compute overdue client-side from nextDoseDueDate.
 *  - No enums anywhere: coded-looking fields are free-text strings. The one
 *    load-bearing vocabulary: immunization status must be 'COMPLETED'
 *    (uppercase) for reminders/DHIS2 export to see the record.
 * Effective roles: social/family writes = DOCTOR/NURSE/MIDWIFE; immunization
 * writes = + PHARMACIST; reads = DOCTOR/NURSE/MIDWIFE/LAB_SCIENTIST/
 * PHARMACIST; deletes = HOSPITAL_ADMIN/SUPER_ADMIN only (admins cannot read).
 */

/* ── Social history ── */

export interface SocialHistoryBase {
  hospitalId?: string;
  recordedByStaffId?: string;
  recordedDate?: string;
  tobaccoUse?: boolean;
  tobaccoType?: string;
  tobaccoPacksPerDay?: number;
  tobaccoYearsUsed?: number;
  tobaccoQuitDate?: string;
  tobaccoNotes?: string;
  alcoholUse?: boolean;
  alcoholFrequency?: string;
  alcoholDrinksPerWeek?: number;
  alcoholBingeDrinking?: boolean;
  alcoholNotes?: string;
  recreationalDrugUse?: boolean;
  drugTypesUsed?: string;
  intravenousDrugUse?: boolean;
  substanceAbuseTreatment?: boolean;
  substanceNotes?: string;
  exerciseFrequency?: string;
  exerciseType?: string;
  exerciseMinutesPerWeek?: number;
  dietType?: string;
  dietRestrictions?: string;
  nutritionalConcerns?: string;
  occupation?: string;
  employmentStatus?: string;
  occupationalHazards?: string;
  maritalStatus?: string;
  livingArrangement?: string;
  housingStability?: boolean;
  householdMembers?: number;
  hasPrimaryCaregiver?: boolean;
  socialSupportNetwork?: string;
  socialIsolationRisk?: boolean;
  educationLevel?: string;
  healthLiteracyConcerns?: boolean;
  preferredLanguage?: string;
  interpreterNeeded?: boolean;
  insuranceStatus?: string;
  financialBarriers?: boolean;
  transportationAccess?: boolean;
  sexuallyActive?: boolean;
  numberOfPartners?: number;
  contraceptionUse?: string;
  stiHistory?: boolean;
  sexualHealthNotes?: string;
  stressLevel?: string;
  stressSources?: string;
  copingMechanisms?: string;
  mentalHealthSupport?: boolean;
  domesticViolenceScreening?: boolean;
  feelsSafeAtHome?: boolean;
  abuseHistory?: boolean;
  safetyConcerns?: string;
  additionalNotes?: string;
  versionNumber?: number;
  active?: boolean;
}

export interface SocialHistoryRequest extends SocialHistoryBase {
  patientId: string;
  hospitalId: string;
  recordedDate: string;
}

export interface SocialHistoryResponse extends SocialHistoryBase {
  id: string;
  patientId: string;
  patientName?: string;
  hospitalName?: string;
  recordedByName?: string;
  createdAt?: string;
  updatedAt?: string;
}

/* ── Family history ── */

export interface FamilyHistoryBase {
  hospitalId?: string;
  recordedByStaffId?: string;
  recordedDate?: string;
  relationship?: string;
  relationshipSide?: string;
  relativeName?: string;
  relativeGender?: string;
  relativeLiving?: boolean;
  relativeAge?: number;
  relativeAgeAtDeath?: number;
  causeOfDeath?: string;
  conditionCode?: string;
  conditionDisplay?: string;
  conditionCategory?: string;
  ageAtOnset?: number;
  severity?: string;
  outcome?: string;
  geneticCondition?: boolean;
  geneticTestingDone?: boolean;
  geneticMarker?: string;
  inheritancePattern?: string;
  clinicallySignificant?: boolean;
  riskFactorForPatient?: boolean;
  screeningRecommended?: boolean;
  screeningType?: string;
  recommendedAgeForScreening?: number;
  /** Serialized with the `is` prefix (Lombok Boolean getter naming). */
  isCancer?: boolean;
  isCardiovascular?: boolean;
  isDiabetes?: boolean;
  isMentalHealth?: boolean;
  isNeurological?: boolean;
  isAutoimmune?: boolean;
  notes?: string;
  sourceOfInformation?: string;
  verified?: boolean;
  verificationDate?: string;
  active?: boolean;
  generation?: number;
  pedigreeId?: string;
}

export interface FamilyHistoryRequest extends FamilyHistoryBase {
  patientId: string;
  hospitalId: string;
  recordedDate: string;
  relationship: string;
  conditionDisplay: string;
}

export interface FamilyHistoryResponse extends FamilyHistoryBase {
  id: string;
  patientId: string;
  patientName?: string;
  hospitalName?: string;
  recordedByName?: string;
  createdAt?: string;
  updatedAt?: string;
}

/* ── Immunizations ── */

export interface ImmunizationBase {
  hospitalId?: string;
  administeredByStaffId?: string;
  encounterId?: string;
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
  expirationDate?: string;
  ndcCode?: string;
  /** Must be 'COMPLETED' (uppercase) to be visible to reminders/exports. */
  status?: string;
  statusReason?: string;
  verified?: boolean;
  sourceOfRecord?: string;
  adverseReaction?: boolean;
  reactionDescription?: string;
  reactionSeverity?: string;
  contraindication?: boolean;
  contraindicationReason?: string;
  nextDoseDueDate?: string;
  reminderSent?: boolean;
  reminderSentDate?: string;
  requiredForSchool?: boolean;
  requiredForTravel?: boolean;
  occupationalRequirement?: boolean;
  pregnancyRelated?: boolean;
  visGiven?: boolean;
  visDate?: string;
  consentObtained?: boolean;
  consentDate?: string;
  insuranceReported?: boolean;
  registryReported?: boolean;
  registryReportedDate?: string;
  notes?: string;
  active?: boolean;
}

export interface ImmunizationRequest extends ImmunizationBase {
  patientId: string;
  hospitalId: string;
  vaccineCode: string;
  vaccineDisplay: string;
  administrationDate: string;
  status: string;
}

export interface ImmunizationResponse extends ImmunizationBase {
  id: string;
  patientId: string;
  patientName?: string;
  hospitalName?: string;
  administeredByName?: string;
  /** Server-persisted flag — STALE; compute overdue from nextDoseDueDate. */
  overdue?: boolean;
  createdAt?: string;
  updatedAt?: string;
}

@Injectable({ providedIn: 'root' })
export class MedicalHistoryService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/medical-history';

  /* ── Social ── */

  createSocialHistory(req: SocialHistoryRequest): Observable<SocialHistoryResponse> {
    return this.http.post<SocialHistoryResponse>(`${this.baseUrl}/social`, req);
  }

  socialHistoryForPatient(patientId: string): Observable<SocialHistoryResponse[]> {
    return this.http.get<SocialHistoryResponse[]>(`${this.baseUrl}/patient/${patientId}/social`);
  }

  /** 200 with an empty body when the patient has no active record. */
  currentSocialHistory(patientId: string): Observable<SocialHistoryResponse | null> {
    return this.http.get<SocialHistoryResponse | null>(
      `${this.baseUrl}/patient/${patientId}/social/current`,
    );
  }

  /* ── Family ── */

  createFamilyHistory(req: FamilyHistoryRequest): Observable<FamilyHistoryResponse> {
    return this.http.post<FamilyHistoryResponse>(`${this.baseUrl}/family`, req);
  }

  updateFamilyHistory(id: string, req: FamilyHistoryRequest): Observable<FamilyHistoryResponse> {
    return this.http.put<FamilyHistoryResponse>(`${this.baseUrl}/family/${id}`, req);
  }

  familyHistoryForPatient(patientId: string): Observable<FamilyHistoryResponse[]> {
    return this.http.get<FamilyHistoryResponse[]>(`${this.baseUrl}/patient/${patientId}/family`);
  }

  geneticFamilyHistory(patientId: string): Observable<FamilyHistoryResponse[]> {
    return this.http.get<FamilyHistoryResponse[]>(
      `${this.baseUrl}/patient/${patientId}/family/genetic`,
    );
  }

  screeningNeededFamilyHistory(patientId: string): Observable<FamilyHistoryResponse[]> {
    return this.http.get<FamilyHistoryResponse[]>(
      `${this.baseUrl}/patient/${patientId}/family/screening-needed`,
    );
  }

  /* ── Immunizations ── */

  createImmunization(req: ImmunizationRequest): Observable<ImmunizationResponse> {
    return this.http.post<ImmunizationResponse>(`${this.baseUrl}/immunizations`, req);
  }

  updateImmunization(id: string, req: ImmunizationRequest): Observable<ImmunizationResponse> {
    return this.http.put<ImmunizationResponse>(`${this.baseUrl}/immunizations/${id}`, req);
  }

  immunizationsForPatient(patientId: string): Observable<ImmunizationResponse[]> {
    return this.http.get<ImmunizationResponse[]>(
      `${this.baseUrl}/patient/${patientId}/immunizations`,
    );
  }

  /** Both bounds required by the backend (yyyy-MM-dd). */
  upcomingImmunizations(
    patientId: string,
    startDate: string,
    endDate: string,
  ): Observable<ImmunizationResponse[]> {
    const params = new HttpParams().set('startDate', startDate).set('endDate', endDate);
    return this.http.get<ImmunizationResponse[]>(
      `${this.baseUrl}/patient/${patientId}/immunizations/upcoming`,
      { params },
    );
  }

  /** DOCTOR/NURSE/RECEPTIONIST; returns 200 with an empty body — refetch after. */
  markReminderSent(id: string): Observable<void> {
    return this.http.patch<void>(`${this.baseUrl}/immunizations/${id}/mark-reminder-sent`, null);
  }
}
