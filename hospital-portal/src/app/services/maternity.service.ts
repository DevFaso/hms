import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/* ── Nested history groups (mirror MaternalHistoryRequestDTO.*DTO) ── */

export interface MenstrualHistory {
  lastMenstrualPeriod?: string;
  estimatedDueDate?: string;
  estimatedDueDateByUltrasound?: string;
  ultrasoundConfirmationDate?: string;
  menstrualCycleLengthDays?: number;
  /** REGULAR | IRREGULAR | UNKNOWN (free string on the wire) */
  menstrualCycleRegularity?: string;
  contraceptionMethodPrior?: string;
}

export interface ObstetricHistory {
  gravida?: number;
  para?: number;
  termBirths?: number;
  pretermBirths?: number;
  abortions?: number;
  livingChildren?: number;
  previousCesareanSections?: number;
  previousPregnancyOutcomes?: string;
  previousPregnancyComplications?: string;
}

export interface ComplicationsHistory {
  gestationalDiabetesHistory?: boolean;
  preeclampsiaHistory?: boolean;
  eclampsiaHistory?: boolean;
  hellpSyndromeHistory?: boolean;
  pretermLaborHistory?: boolean;
  postpartumHemorrhageHistory?: boolean;
  placentaPreviaHistory?: boolean;
  placentalAbruptionHistory?: boolean;
  fetalAnomalyHistory?: boolean;
  complicationsDetails?: string;
}

export interface MaternalMedicalHistory {
  chronicConditions?: string;
  diabetes?: boolean;
  hypertension?: boolean;
  thyroidDisorder?: boolean;
  cardiacDisease?: boolean;
  renalDisease?: boolean;
  autoimmuneDisorder?: boolean;
  mentalHealthConditions?: string;
  surgicalHistory?: string;
  previousAbdominalSurgery?: boolean;
  previousUterineSurgery?: boolean;
  allergies?: string;
  drugAllergies?: string;
  latexAllergy?: boolean;
}

export interface MedicationsImmunizations {
  currentMedications?: string;
  prenatalVitaminsStarted?: boolean;
  prenatalVitaminsStartDate?: string;
  folicAcidSupplementation?: boolean;
  /** IMMUNE | NON_IMMUNE | PENDING | UNKNOWN */
  rubellaImmunity?: string;
  varicellaImmunity?: string;
  hepatitisBVaccination?: boolean;
  tdapVaccination?: boolean;
  tdapVaccinationDate?: string;
  fluVaccinationCurrentSeason?: boolean;
  fluVaccinationDate?: string;
  covid19Vaccination?: boolean;
  immunizationNotes?: string;
}

export interface MaternalFamilyHistory {
  familyMedicalHistory?: string;
  familyGeneticDisorders?: boolean;
  familyPregnancyComplications?: boolean;
  familyDiabetes?: boolean;
  familyHypertension?: boolean;
  familyTwinHistory?: boolean;
  familyHistoryDetails?: string;
}

export interface LifestyleFactors {
  /** NEVER | FORMER | CURRENT */
  smokingStatus?: string;
  cigarettesPerDay?: number;
  smokingCessationDate?: string;
  /** NONE | OCCASIONAL | REGULAR | FREQUENT */
  alcoholUse?: string;
  alcoholUseDetails?: string;
  substanceUse?: string;
  recreationalDrugUse?: boolean;
  substanceUseDetails?: string;
  caffeineIntakeMgDaily?: number;
  dietType?: string;
  dietDescription?: string;
  /** SEDENTARY | LIGHT | MODERATE | ACTIVE */
  exerciseFrequency?: string;
  exerciseDetails?: string;
  occupationalHazards?: boolean;
  occupationalHazardsDetails?: string;
  environmentalExposures?: string;
  petExposure?: string;
  travelHistory?: string;
  zikaRiskExposure?: boolean;
}

export interface PsychosocialFactors {
  mentalHealthScreeningCompleted?: boolean;
  depressionScreeningScore?: number;
  anxietyPresent?: boolean;
  domesticViolenceScreening?: boolean;
  domesticViolenceConcerns?: boolean;
  domesticViolenceDetails?: string;
  supportSystem?: string;
  adequateHousing?: boolean;
  foodSecurity?: boolean;
  financialConcerns?: boolean;
  psychosocialNotes?: string;
}

/** Backend riskCategory is a plain string with exactly these values. */
export type MaternalRiskCategory = 'LOW' | 'MODERATE' | 'HIGH';

export interface MaternalHistoryRequest {
  patientId: string;
  hospitalId: string;
  recordedByStaffId?: string;
  /** ISO LocalDateTime, required by the backend. */
  recordedDate: string;
  updateReason?: string;
  menstrualHistory?: MenstrualHistory;
  obstetricHistory?: ObstetricHistory;
  complicationsHistory?: ComplicationsHistory;
  medicalHistory?: MaternalMedicalHistory;
  medicationsImmunizations?: MedicationsImmunizations;
  familyHistory?: MaternalFamilyHistory;
  lifestyleFactors?: LifestyleFactors;
  psychosocialFactors?: PsychosocialFactors;
  clinicalNotes?: string;
  dataComplete?: boolean;
  requiresSpecialistReferral?: boolean;
  specialistReferralReason?: string;
}

export interface MaternalHistoryResponse {
  id: string;
  patientId: string;
  hospitalId: string;
  recordedByStaffId?: string;
  recordedDate: string;
  versionNumber?: number;
  updateReason?: string;
  menstrualHistory?: MenstrualHistory;
  obstetricHistory?: ObstetricHistory;
  complicationsHistory?: ComplicationsHistory;
  medicalHistory?: MaternalMedicalHistory;
  medicationsImmunizations?: MedicationsImmunizations;
  familyHistory?: MaternalFamilyHistory;
  lifestyleFactors?: LifestyleFactors;
  psychosocialFactors?: PsychosocialFactors;
  clinicalNotes?: string;
  dataComplete?: boolean;
  reviewedByProvider?: boolean;
  reviewTimestamp?: string | null;
  requiresSpecialistReferral?: boolean;
  specialistReferralReason?: string;
  calculatedRiskScore?: number | null;
  riskCategory?: MaternalRiskCategory | string | null;
  identifiedRiskFactors?: string | null;
  additionalNotes?: string | null;
  createdAt?: string;
  updatedAt?: string;
  hasHighRiskHistory?: boolean;
  hasChronicMedicalConditions?: boolean;
  hasLifestyleRiskFactors?: boolean;
  needsPsychosocialSupport?: boolean;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export interface MaternalHistorySearchParams {
  patientId?: string;
  riskCategory?: string;
  dataComplete?: boolean;
  reviewedByProvider?: boolean;
  dateFrom?: string;
  dateTo?: string;
  page?: number;
  size?: number;
}

/**
 * Maternal (obstetric) history API — /maternal-history.
 * Responses are bare DTOs / Spring Page objects (no ApiResponseWrapper).
 * Effective backend access is role-based only: manage = DOCTOR/NURSE/MIDWIFE/
 * SUPER_ADMIN; mark-reviewed + calculate-risk = DOCTOR/SUPER_ADMIN;
 * pending-review + specialist-referral worklists exclude NURSE.
 */
@Injectable({ providedIn: 'root' })
export class MaternityService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/maternal-history';

  create(req: MaternalHistoryRequest): Observable<MaternalHistoryResponse> {
    return this.http.post<MaternalHistoryResponse>(this.baseUrl, req);
  }

  /** Creates a new version of the history. */
  update(id: string, req: MaternalHistoryRequest): Observable<MaternalHistoryResponse> {
    return this.http.put<MaternalHistoryResponse>(`${this.baseUrl}/${id}`, req);
  }

  getById(id: string): Observable<MaternalHistoryResponse> {
    return this.http.get<MaternalHistoryResponse>(`${this.baseUrl}/${id}`);
  }

  currentForPatient(patientId: string): Observable<MaternalHistoryResponse> {
    return this.http.get<MaternalHistoryResponse>(`${this.baseUrl}/patient/${patientId}/current`);
  }

  versionsForPatient(patientId: string): Observable<MaternalHistoryResponse[]> {
    return this.http.get<MaternalHistoryResponse[]>(
      `${this.baseUrl}/patient/${patientId}/versions`,
    );
  }

  search(
    hospitalId: string,
    params: MaternalHistorySearchParams = {},
  ): Observable<PageResponse<MaternalHistoryResponse>> {
    let httpParams = new HttpParams();
    if (params.patientId) httpParams = httpParams.set('patientId', params.patientId);
    if (params.riskCategory) httpParams = httpParams.set('riskCategory', params.riskCategory);
    if (params.dataComplete !== undefined)
      httpParams = httpParams.set('dataComplete', params.dataComplete);
    if (params.reviewedByProvider !== undefined)
      httpParams = httpParams.set('reviewedByProvider', params.reviewedByProvider);
    if (params.dateFrom) httpParams = httpParams.set('dateFrom', params.dateFrom);
    if (params.dateTo) httpParams = httpParams.set('dateTo', params.dateTo);
    if (params.page !== undefined) httpParams = httpParams.set('page', params.page);
    if (params.size !== undefined) httpParams = httpParams.set('size', params.size);
    return this.http.get<PageResponse<MaternalHistoryResponse>>(
      `${this.baseUrl}/hospital/${hospitalId}/search`,
      { params: httpParams },
    );
  }

  highRisk(
    hospitalId: string,
    page = 0,
    size = 20,
  ): Observable<PageResponse<MaternalHistoryResponse>> {
    return this.worklist(hospitalId, 'high-risk', page, size);
  }

  /** Doctor/midwife/super-admin only on the backend (no NURSE). */
  pendingReview(
    hospitalId: string,
    page = 0,
    size = 20,
  ): Observable<PageResponse<MaternalHistoryResponse>> {
    return this.worklist(hospitalId, 'pending-review', page, size);
  }

  /** Doctor/midwife/super-admin only on the backend (no NURSE). */
  specialistReferral(
    hospitalId: string,
    page = 0,
    size = 20,
  ): Observable<PageResponse<MaternalHistoryResponse>> {
    return this.worklist(hospitalId, 'specialist-referral', page, size);
  }

  psychosocialConcerns(
    hospitalId: string,
    page = 0,
    size = 20,
  ): Observable<PageResponse<MaternalHistoryResponse>> {
    return this.worklist(hospitalId, 'psychosocial-concerns', page, size);
  }

  private worklist(
    hospitalId: string,
    path: string,
    page: number,
    size: number,
  ): Observable<PageResponse<MaternalHistoryResponse>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<PageResponse<MaternalHistoryResponse>>(
      `${this.baseUrl}/hospital/${hospitalId}/${path}`,
      { params },
    );
  }

  /** DOCTOR/SUPER_ADMIN only. */
  markReviewed(id: string): Observable<MaternalHistoryResponse> {
    return this.http.post<MaternalHistoryResponse>(`${this.baseUrl}/${id}/mark-reviewed`, null);
  }

  /** DOCTOR/SUPER_ADMIN only. */
  calculateRisk(id: string): Observable<MaternalHistoryResponse> {
    return this.http.post<MaternalHistoryResponse>(`${this.baseUrl}/${id}/calculate-risk`, null);
  }
}
